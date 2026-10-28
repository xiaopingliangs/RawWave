package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import com.rawwave.core.SensorPort
import com.rawwave.core.SensorRows
import com.rawwave.core.SensorCallbackFence
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*

/** SDK boundary only. No storage, phase decisions, retry, or inferred samples. */
class SamsungSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit, private val biaProfile: com.rawwave.core.BiaProfile? = null) : SensorPort {
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "samsung-health-sensor-1.4.1/adapter-8"
    private fun cleanupEvidence(stage: String, sensor: String, detail: String = "") {
        try { (context.applicationContext as? ProbeApplication)?.journal?.event("CAPTURE_VENDOR_CLEANUP", mapOf(
            "stage" to stage, "sensor" to sensor, "detail" to detail,
            "elapsedMs" to SystemClock.elapsedRealtime(), "hardwareStoppedVerified" to false)) } catch (_: Exception) {}
    }
    private var ready: Runnable? = null
    private var disconnected: SensorPort.Failure? = null
    private var connecting = false
    private var closed = false
    private val trackers = mutableMapOf<String, HealthTracker>()
    private class Delivery {
        val fence = SensorCallbackFence()
        val endBoundary = com.rawwave.core.EdaEndBoundary()
        var sequence = 0L
        var completion: SensorPort.Drain? = null // serial owner only
        var flushRequestedMs: Long? = null
    }
    private val deliveries = mutableMapOf<String, Delivery>()
    private val service = HealthTrackingService(object : ConnectionListener {
        override fun onConnectionSuccess() { dispatch { if (!closed) ready?.run() } }
        override fun onConnectionEnded() { dispatch { if (!closed) disconnected?.accept("SDK_CONNECTION_ENDED") } }
        override fun onConnectionFailed(error: HealthTrackerException) {
            dispatch { if (!closed) disconnected?.accept("SDK_CONNECTION_FAILED:${error.errorCode}:${error.message}") }
        }
    }, context)
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) {
        check(!closed && !connecting) { "SENSOR_CONNECTION_ALREADY_USED" }
        this.ready = ready; this.disconnected = disconnected; connecting = true
        service.connectService()
    }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed) { "SENSOR_PORT_CLOSED" }
            check(!trackers.containsKey(sensor)) { "SENSOR_ALREADY_STARTED:$sensor" }
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
            val tracker = if (sensor == "PPG") service.getHealthTracker(type,
                setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)) else if ((sensor == "BIA" || sensor == "MF_BIA")) {
                val p = checkNotNull(biaProfile) { "BIA_PROFILE_REQUIRED" }
                service.getHealthTracker(type, TrackerUserProfile.Builder().setHeight(p.heightCm())
                    .setWeight(p.weightKg()).setAge(p.age()).setGender(p.gender()).build())
            } else service.getHealthTracker(type)
            trackers[sensor] = tracker
            val delivery = Delivery()
            deliveries[sensor] = delivery
            tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    delivery.fence.accept {
                    val utc = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    try {
                        val callbackSequence = ++delivery.sequence
                        // Copy SDK-owned values immediately. captureCallback* fields are acquisition
                        // metadata; SDK fields keep their official names and are never inferred.
                        val rows = points.mapIndexed { index, p ->
                            val row = linkedMapOf<String, Any?>("sdkTimestamp" to p.timestamp,
                                "receivedUtcMs" to utc, "receivedElapsedMs" to elapsed,
                                "captureCallbackSequence" to callbackSequence,
                                "captureCallbackIndex" to index,
                                "captureCallbackSize" to points.size)
                            fun <T> field(name: String, key: ValueKey<T>) {
                                try {
                                    val v = p.getValue(key)
                                    row[name] = when {
                                        v is List<*> -> v.toList()
                                        v is Float && !v.isFinite() -> v.toString()
                                        v is Double && !v.isFinite() -> v.toString()
                                        else -> v
                                    }
                                    if ((v is Float && !v.isFinite()) || (v is Double && !v.isFinite())) row["${name}_encoding"] = "SDK_NON_FINITE_AS_STRING"
                                } catch (e: Exception) {
                                    row[name] = null
                                    row["${name}_readError"] = "${e.javaClass.simpleName}:${e.message}"
                                }
                            }
                            when (sensor) {
                                "MF_BIA" -> {
                                    field("STATUS", ValueKey.MfBiaSet.STATUS)
                                    field("PROGRESS", ValueKey.MfBiaSet.PROGRESS)
                                    field("BODY_IMPEDANCE_MAGNITUDE_5K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K)
                                    field("BODY_IMPEDANCE_PHASE_5K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_10K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K)
                                    field("BODY_IMPEDANCE_PHASE_10K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_50K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K)
                                    field("BODY_IMPEDANCE_PHASE_50K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_250K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K)
                                    field("BODY_IMPEDANCE_PHASE_250K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K)
                                }
                                "BIA" -> {
                                    field("STATUS", ValueKey.BiaSet.STATUS)
                                    field("BODY_FAT_RATIO", ValueKey.BiaSet.BODY_FAT_RATIO)
                                    field("BODY_FAT_MASS", ValueKey.BiaSet.BODY_FAT_MASS)
                                    field("TOTAL_BODY_WATER", ValueKey.BiaSet.TOTAL_BODY_WATER)
                                    field("SKELETAL_MUSCLE_RATIO", ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO)
                                    field("SKELETAL_MUSCLE_MASS", ValueKey.BiaSet.SKELETAL_MUSCLE_MASS)
                                    field("BASAL_METABOLIC_RATE", ValueKey.BiaSet.BASAL_METABOLIC_RATE)
                                    field("FAT_FREE_RATIO", ValueKey.BiaSet.FAT_FREE_RATIO)
                                    field("FAT_FREE_MASS", ValueKey.BiaSet.FAT_FREE_MASS)
                                    field("PROGRESS", ValueKey.BiaSet.PROGRESS)
                                    field("BODY_IMPEDANCE_MAGNITUDE", ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE)
                                    field("BODY_IMPEDANCE_DEGREE", ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE)
                                }
                                "SPO2" -> {
                                    field("SPO2", ValueKey.SpO2Set.SPO2)
                                    field("STATUS", ValueKey.SpO2Set.STATUS)
                                    field("HEART_RATE", ValueKey.SpO2Set.HEART_RATE)
                                }
                                "ECG" -> {
                                    field("ECG_MV", ValueKey.EcgSet.ECG_MV)
                                    // Samsung documents these fields by callback position.
                                    if (index == 0) {
                                        field("LEAD_OFF", ValueKey.EcgSet.LEAD_OFF)
                                        field("SEQUENCE", ValueKey.EcgSet.SEQUENCE)
                                        field("PPG_GREEN", ValueKey.EcgSet.PPG_GREEN)
                                        field("MAX_THRESHOLD_MV", ValueKey.EcgSet.MAX_THRESHOLD_MV)
                                        field("MIN_THRESHOLD_MV", ValueKey.EcgSet.MIN_THRESHOLD_MV)
                                    } else if (points.size == 10 && index == 5) {
                                        field("PPG_GREEN", ValueKey.EcgSet.PPG_GREEN)
                                    }
                                }
                                "HEART_RATE" -> {
                                    field("HEART_RATE", ValueKey.HeartRateSet.HEART_RATE)
                                    field("HEART_RATE_STATUS", ValueKey.HeartRateSet.HEART_RATE_STATUS)
                                    field("IBI_LIST", ValueKey.HeartRateSet.IBI_LIST)
                                    field("IBI_STATUS_LIST", ValueKey.HeartRateSet.IBI_STATUS_LIST)
                                }
                                "ACCELEROMETER" -> {
                                    field("ACCELEROMETER_X", ValueKey.AccelerometerSet.ACCELEROMETER_X)
                                    field("ACCELEROMETER_Y", ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                                    field("ACCELEROMETER_Z", ValueKey.AccelerometerSet.ACCELEROMETER_Z)
                                }
                                "PPG" -> {
                                    field("PPG_GREEN", ValueKey.PpgSet.PPG_GREEN)
                                    field("GREEN_STATUS", ValueKey.PpgSet.GREEN_STATUS)
                                    field("PPG_IR", ValueKey.PpgSet.PPG_IR)
                                    field("IR_STATUS", ValueKey.PpgSet.IR_STATUS)
                                    field("PPG_RED", ValueKey.PpgSet.PPG_RED)
                                    field("RED_STATUS", ValueKey.PpgSet.RED_STATUS)
                                }
                                "SKIN_TEMPERATURE" -> {
                                    field("OBJECT_TEMPERATURE", ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                                    field("AMBIENT_TEMPERATURE", ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                                    field("STATUS", ValueKey.SkinTemperatureSet.STATUS)
                                }
                                "EDA" -> {
                                    field("SKIN_CONDUCTANCE", ValueKey.EdaSet.SKIN_CONDUCTANCE)
                                    field("STATUS", ValueKey.EdaSet.STATUS)
                                }
                                else -> error("UNKNOWN_SENSOR_CALLBACK:$sensor")
                            }
                            row
                        }
                        val snapshot = SensorRows.snapshot(rows)
                        dispatch {
                            if (!closed) {
                                data.accept(snapshot)
                                if(sensor=="EDA" && deliveries[sensor] === delivery) {
                                    val covered=delivery.endBoundary.observe(snapshot)
                                    if(covered && delivery.completion!=null)
                                        endDrain(sensor,delivery,SensorPort.DrainStatus.END_TIME_COVERED,delivery.endBoundary.evidence())
                                }
                            }
                        }
                    } catch (e: Exception) { dispatch { if (!closed) failed.accept("CALLBACK_COPY:${e.message}") } }
                    }
                }
                override fun onFlushCompleted() {
                    val callbackElapsedMs = SystemClock.elapsedRealtime()
                    dispatch {
                        cleanupEvidence("FLUSH_CALLBACK", sensor,
                            "callbackElapsedMs=$callbackElapsedMs; dispatchElapsedMs=${SystemClock.elapsedRealtime()}; requestElapsedMs=${delivery.flushRequestedMs}; active=${deliveries[sensor] === delivery}; waiting=${delivery.completion != null}")
                        if (!closed && deliveries[sensor] === delivery && delivery.completion != null)
                            endDrain(sensor, delivery, SensorPort.DrainStatus.FLUSHED, "SDK_FLUSH_ACKNOWLEDGED")
                    }
                }
                override fun onError(error: HealthTracker.TrackerError) { dispatch {
                    if (!closed) {
                        if (deliveries[sensor] === delivery && delivery.completion != null)
                            endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_ERROR:$error")
                        else failed.accept(error.toString())
                    }
                } }
            })
        } catch (e: Exception) { failed.accept("${e.javaClass.simpleName}:${e.message}") }
    }
    override fun drain(sensor: String, completed: SensorPort.Drain) {
        val delivery = deliveries[sensor]
        if (closed || delivery == null) { completed.accept(SensorPort.DrainStatus.FAILED, "SOURCE_NOT_ACTIVE"); return }
        check(delivery.completion == null) { "DRAIN_ALREADY_REQUESTED:$sensor" }
        delivery.completion = completed
        if(sensor=="EDA") delivery.endBoundary.arm(System.currentTimeMillis(),SystemClock.elapsedRealtime())
        if (sensor == "ECG" || sensor == "SPO2" || (sensor == "BIA" || sensor == "MF_BIA")) {
            endDrain(sensor, delivery, SensorPort.DrainStatus.DELIVERED_ONLY, "${sensor}_HAS_NO_SDK_FLUSH;ACCEPTED_CALLBACKS_DRAINED")
            return
        }
        try {
            delivery.flushRequestedMs = SystemClock.elapsedRealtime()
            cleanupEvidence("FLUSH_REQUEST", sensor, "requestElapsedMs=${delivery.flushRequestedMs}")
            val accepted = trackers[sensor]?.flush() == true
            cleanupEvidence("FLUSH_RETURNED", sensor, "accepted=$accepted; requestElapsedMs=${delivery.flushRequestedMs}")
            if (!accepted)
                endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_FLUSH_REJECTED")
        } catch (e: Exception) {
            cleanupEvidence("FLUSH_EXCEPTION", sensor, e.toString())
            endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_FLUSH_EXCEPTION:${e.message}")
        }
    }
    private fun endDrain(sensor: String, delivery: Delivery, status: SensorPort.DrainStatus, detail: String) {
        val completed = delivery.completion ?: return
        delivery.completion = null
        var outcome = status
        var description = detail
        try { stop(sensor) } catch (e: Exception) {
            outcome = SensorPort.DrainStatus.FAILED; description += ";STOP_FAILED:${e.message}"
        }
        // stop acquires the same monitor as callback copying/posting. Every accepted data
        // task is therefore ahead of this completion task on the owner's serial dispatcher.
        dispatch { if (!closed) completed.accept(outcome, description) }
    }
    override fun stop(sensor: String) {
        val delivery = deliveries[sensor]
        delivery?.fence?.close()
        // Never call vendor APIs under the callback monitor: unset may wait for a callback.
        val tracker = trackers[sensor]
        if (tracker != null) {
            cleanupEvidence("UNSET_REQUEST", sensor)
            try { tracker.unsetEventListener(); cleanupEvidence("UNSET_RETURNED", sensor) }
            catch(e: Exception) { cleanupEvidence("UNSET_FAILED", sensor, e.toString()); throw e }
        }
        trackers.remove(sensor); deliveries.remove(sensor)
    }
    override fun close() {
        closed = true
        var failure: Exception? = null
        trackers.keys.toList().forEach { try { stop(it) } catch (e: Exception) { failure = e } }
        cleanupEvidence("DISCONNECT_REQUEST", "ALL")
        try { service.disconnectService(); cleanupEvidence("DISCONNECT_RETURNED", "ALL") }
        catch (e: Exception) { cleanupEvidence("DISCONNECT_FAILED", "ALL", e.toString()); failure = e }
        failure?.let { throw it }
    }
}
