package com.rawwave.shell

import android.content.Context
import com.rawwave.core.SensorCatalog
import com.rawwave.core.SensorPort

/** Only composition point knows the concrete vendor adapter. No global mutable factory. */
object CaptureAssembly {
    fun sensors(context: Context, dispatch: (() -> Unit) -> Unit, needsSamsung: Boolean = true, profile: com.rawwave.core.BiaProfile? = null,
                dispatchRows: ((List<Map<String, Any?>>, () -> Unit) -> Unit)? = null): SensorPort =
        if (!needsSamsung) PlatformSensors(context, dispatch) else
        CombinedSensors(SensorPort.requireCompatible(SamsungSensorAdapter(context, dispatch, profile, dispatchRows)),
            PlatformSensors(context, dispatch))
}

/** Routes ownership to its source adapter; neither adapter knows activity phases or storage. */
private class CombinedSensors(private val samsung: SensorPort, private val android: SensorPort): SensorPort {
    override fun contractVersion()=SensorPort.CONTRACT_VERSION
    override fun implementationVersion()=samsung.implementationVersion()+";"+android.implementationVersion()
    private fun port(sensor:String)=if(SensorCatalog.isAndroidPlatformSource(sensor)) android else samsung
    override fun connect(ready:Runnable, failed:SensorPort.Failure)=samsung.connect(ready,failed)
    override fun start(sensor:String, rows:SensorPort.Rows, failed:SensorPort.Failure)=port(sensor).start(sensor,rows,failed)
    override fun drain(sensor:String, done:SensorPort.Drain)=port(sensor).drain(sensor,done)
    override fun stop(sensor:String)=port(sensor).stop(sensor)
    override fun close(){try{samsung.close()}finally{android.close()}}
}

/** Separate listeners/fences let each platform source drain independently. */
private class PlatformSensors(context: Context, dispatch: (() -> Unit) -> Unit): SensorPort {
    private val gyro = GyroscopeSensorAdapter(context, dispatch)
    private val gyroUncalibrated = GyroscopeUncalibratedSensorAdapter(context, dispatch)
    private val platformAccelerometer = PlatformAccelerometerSensorAdapter(context, dispatch)
    private val accelerometerUncalibrated = AccelerometerUncalibratedSensorAdapter(context, dispatch)
    private val linearAcceleration = LinearAccelerationSensorAdapter(context, dispatch)
    private val gameRotationVector = GameRotationVectorSensorAdapter(context, dispatch)
    private val rotationVector = RotationVectorSensorAdapter(context, dispatch)
    private val geomagneticRotationVector = GeomagneticRotationVectorSensorAdapter(context, dispatch)
    private val gravity = GravitySensorAdapter(context, dispatch)
    private val wristTiltGesture = WristTiltGestureSensorAdapter(context, dispatch)
    private val magnetic = MagneticFieldSensorAdapter(context, dispatch)
    private val magneticUncalibrated = MagneticFieldUncalibratedSensorAdapter(context, dispatch)
    private val pressure = PressureSensorAdapter(context, dispatch)
    private val light = LightSensorAdapter(context, dispatch)
    private val location = LocationSensorAdapter(context, dispatch)
    private val steps = StepCounterSensorAdapter(context, dispatch)
    private val stepDetector = StepDetectorSensorAdapter(context, dispatch)
    private val offBody = OffBodySensorAdapter(context, dispatch)
    private fun port(sensor: String): SensorPort = when(sensor) {
        "GYROSCOPE" -> gyro
        "GYROSCOPE_UNCALIBRATED" -> gyroUncalibrated
        "PLATFORM_ACCELEROMETER" -> platformAccelerometer
        "ACCELEROMETER_UNCALIBRATED" -> accelerometerUncalibrated
        "LINEAR_ACCELERATION" -> linearAcceleration
        "GAME_ROTATION_VECTOR" -> gameRotationVector
        "ROTATION_VECTOR" -> rotationVector
        "GEOMAGNETIC_ROTATION_VECTOR" -> geomagneticRotationVector
        "GRAVITY" -> gravity
        "WRIST_TILT_GESTURE" -> wristTiltGesture
        "PRESSURE" -> pressure
        "MAGNETIC_FIELD" -> magnetic
        "MAGNETIC_FIELD_UNCALIBRATED" -> magneticUncalibrated
        "LIGHT" -> light
        "GPS" -> location
        "STEP_COUNT" -> steps
        "STEP_DETECTOR" -> stepDetector
        "OFFBODY" -> offBody
        else -> error("UNKNOWN_PLATFORM_SENSOR:$sensor")
    }
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = gyro.implementationVersion()+";"+gyroUncalibrated.implementationVersion()+";"+platformAccelerometer.implementationVersion()+";"+accelerometerUncalibrated.implementationVersion()+";"+linearAcceleration.implementationVersion()+";"+gameRotationVector.implementationVersion()+";"+rotationVector.implementationVersion()+";"+geomagneticRotationVector.implementationVersion()+";"+gravity.implementationVersion()+";"+wristTiltGesture.implementationVersion()+";"+pressure.implementationVersion()+";"+magnetic.implementationVersion()+";"+magneticUncalibrated.implementationVersion()+";"+light.implementationVersion()+";"+location.implementationVersion()+";"+steps.implementationVersion()+";"+stepDetector.implementationVersion()+";"+offBody.implementationVersion()
    override fun connect(ready: Runnable, failed: SensorPort.Failure) = gyro.connect(ready,failed)
    override fun start(sensor:String, rows:SensorPort.Rows, failed:SensorPort.Failure)=port(sensor).start(sensor,rows,failed)
    override fun drain(sensor:String, done:SensorPort.Drain)=port(sensor).drain(sensor,done)
    override fun stop(sensor:String)=port(sensor).stop(sensor)
    override fun close(){
        var failure: Throwable? = null
        listOf(gyro, gyroUncalibrated, platformAccelerometer, accelerometerUncalibrated, linearAcceleration, gameRotationVector, rotationVector, geomagneticRotationVector, gravity, wristTiltGesture, pressure, magnetic, magneticUncalibrated, light, location, steps, stepDetector, offBody).forEach {
            try { it.close() } catch (t: Throwable) { if (failure == null) failure = t }
        }
        failure?.let { throw it }
    }
}
