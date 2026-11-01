package com.rawwave.shell

import android.content.Context
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*

/** Small vendor seam: host tests exercise the production adapter, including cleanup failures. */
internal interface SamsungBackend {
    fun connect(ready: () -> Unit, ended: () -> Unit, failed: (String) -> Unit)
    fun create(sensor: String): SamsungTracker
    fun disconnect()
}
internal interface SamsungTracker {
    fun listen(listener: HealthTracker.TrackerEventListener)
    fun unset()
    fun flush(): Boolean
}
internal class DeviceSamsungBackend(private val context: Context,
    private val biaProfile: com.rawwave.core.BiaProfile?) : SamsungBackend {
    private var service: HealthTrackingService? = null
    override fun connect(ready: () -> Unit, ended: () -> Unit, failed: (String) -> Unit) {
        service = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() = ready()
            override fun onConnectionEnded() = ended()
            override fun onConnectionFailed(error: HealthTrackerException) = failed("SDK_CONNECTION_FAILED:${error.errorCode}:${error.message}")
        }, context).also { it.connectService() }
    }
    override fun disconnect() { service?.disconnectService() }
    override fun create(sensor: String): SamsungTracker {
        val tracker = createDeviceTracker(sensor)
        return object : SamsungTracker {
            override fun listen(listener: HealthTracker.TrackerEventListener) = tracker.setEventListener(listener)
            override fun unset() = tracker.unsetEventListener()
            override fun flush() = tracker.flush()
        }
    }
    private fun createDeviceTracker(sensor: String): HealthTracker {
        val service = checkNotNull(service)
            val type = when (sensor) {
                "ECG" -> HealthTrackerType.ECG_ON_DEMAND
                "MF_BIA" -> HealthTrackerType.MF_BIA_ON_DEMAND
                "BIA" -> HealthTrackerType.BIA_ON_DEMAND
                "SPO2" -> HealthTrackerType.SPO2_ON_DEMAND
                "HEART_RATE" -> HealthTrackerType.HEART_RATE_CONTINUOUS
                "ACCELEROMETER" -> HealthTrackerType.ACCELEROMETER_CONTINUOUS
                "PPG" -> HealthTrackerType.PPG_CONTINUOUS
                "SKIN_TEMPERATURE" -> HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
                "EDA" -> HealthTrackerType.EDA_CONTINUOUS
                else -> error("UNKNOWN_SENSOR:$sensor")
            }
            val permission = when {
                sensor == "ACCELEROMETER" -> android.Manifest.permission.ACTIVITY_RECOGNITION
                android.os.Build.VERSION.SDK_INT < 36 -> android.Manifest.permission.BODY_SENSORS
                sensor == "HEART_RATE" -> "android.permission.health.READ_HEART_RATE"
                sensor == "SPO2" -> "android.permission.health.READ_OXYGEN_SATURATION"
                sensor == "SKIN_TEMPERATURE" -> "android.permission.health.READ_SKIN_TEMPERATURE"
                (sensor == "BIA" || sensor == "MF_BIA") || sensor == "ECG" || sensor == "PPG" || sensor == "EDA" ->
                    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
                else -> error("NO_PERMISSION_MAPPING:$sensor")
            }
            check(context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "PERMISSION_DENIED:$permission" }
            check(service.trackingCapability.supportHealthTrackerTypes.contains(type)) { "UNSUPPORTED_$sensor" }
            return if (sensor == "PPG") service.getHealthTracker(type,
                setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)) else if ((sensor == "BIA" || sensor == "MF_BIA")) {
                val p = checkNotNull(biaProfile) { "BIA_PROFILE_REQUIRED" }
                service.getHealthTracker(type, TrackerUserProfile.Builder().setHeight(p.heightCm())
                    .setWeight(p.weightKg()).setAge(p.age()).setGender(p.gender()).build())
            } else service.getHealthTracker(type)
    }
}
