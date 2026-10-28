package com.rawwave.shell

import android.content.Context
import com.rawwave.core.SensorPort

/** Only composition point knows the concrete vendor adapter. No global mutable factory. */
object CaptureAssembly {
    fun sensors(context: Context, dispatch: (() -> Unit) -> Unit, needsSamsung: Boolean = true, profile: com.rawwave.core.BiaProfile? = null): SensorPort =
        if (!needsSamsung) PlatformSensors(context, dispatch) else
        CombinedSensors(SensorPort.requireCompatible(SamsungSensorAdapter(context, dispatch, profile)),
            PlatformSensors(context, dispatch))
}

/** Routes ownership to its source adapter; neither adapter knows activity phases or storage. */
private class CombinedSensors(private val samsung: SensorPort, private val android: SensorPort): SensorPort {
    override fun contractVersion()=SensorPort.CONTRACT_VERSION
    override fun implementationVersion()=samsung.implementationVersion()+";"+android.implementationVersion()
    private fun port(sensor:String)=if(sensor=="GYROSCOPE" || sensor=="PRESSURE" || sensor=="MAGNETIC_FIELD") android else samsung
    override fun connect(ready:Runnable, failed:SensorPort.Failure)=samsung.connect(ready,failed)
    override fun start(sensor:String, rows:SensorPort.Rows, failed:SensorPort.Failure)=port(sensor).start(sensor,rows,failed)
    override fun drain(sensor:String, done:SensorPort.Drain)=port(sensor).drain(sensor,done)
    override fun stop(sensor:String)=port(sensor).stop(sensor)
    override fun close(){try{samsung.close()}finally{android.close()}}
}

/** Separate listeners/fences let gyro and pressure drain independently. */
private class PlatformSensors(context: Context, dispatch: (() -> Unit) -> Unit): SensorPort {
    private val gyro = GyroscopeSensorAdapter(context, dispatch)
    private val magnetic = MagneticFieldSensorAdapter(context, dispatch)
    private val pressure = PressureSensorAdapter(context, dispatch)
    private fun port(sensor: String): SensorPort = when(sensor) {
        "GYROSCOPE" -> gyro
        "PRESSURE" -> pressure
        "MAGNETIC_FIELD" -> magnetic
        else -> error("UNKNOWN_PLATFORM_SENSOR:$sensor")
    }
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = gyro.implementationVersion()+";"+pressure.implementationVersion()+";"+magnetic.implementationVersion()
    override fun connect(ready: Runnable, failed: SensorPort.Failure) = gyro.connect(ready,failed)
    override fun start(sensor:String, rows:SensorPort.Rows, failed:SensorPort.Failure)=port(sensor).start(sensor,rows,failed)
    override fun drain(sensor:String, done:SensorPort.Drain)=port(sensor).drain(sensor,done)
    override fun stop(sensor:String)=port(sensor).stop(sensor)
    override fun close(){try{gyro.close()}finally{try{pressure.close()}finally{magnetic.close()}}}
}
