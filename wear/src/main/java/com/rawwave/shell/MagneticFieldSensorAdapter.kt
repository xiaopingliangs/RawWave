package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/** Android stream boundary. Platform timestamp nanoseconds and uT values stay unchanged. */
class MagneticFieldSensorAdapter(context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-magnetic-field/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "MAGNETIC_FIELD")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: error("UNSUPPORTED_MAGNETIC_FIELD")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                    val row = linkedMapOf<String,Any>("sensorTimestampNs" to event.timestamp,
                        "sdkTimestamp" to event.timestamp / 1_000_000L,
                        "timestampMeaning" to "Android boot nanoseconds; sdkTimestamp is derived milliseconds",
                        "source" to "Android SensorManager TYPE_MAGNETIC_FIELD", "unit" to "uT",
                        "accuracy" to event.accuracy, "receivedUtcMs" to System.currentTimeMillis(),
                        "receivedElapsedMs" to SystemClock.elapsedRealtime(), "captureCallbackSequence" to ++sequence,
                        "captureCallbackIndex" to 0, "captureCallbackSize" to 1)
                    listOf("MAGNETIC_FIELD_X","MAGNETIC_FIELD_Y","MAGNETIC_FIELD_Z").forEachIndexed { i,key ->
                        val v=event.values[i]; row[key]=if(v.isFinite()) v else v.toString()
                    }
                    val snapshot=SensorRows.snapshot(listOf(row))
                    dispatch { if(!closed) data.accept(snapshot) }
                    } catch(e:Exception) { dispatch { if(!closed) failed.accept("MAGNETIC_FIELD_CALLBACK_COPY:${e.message}") } }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if(listener === this) {
                        val done=completion
                        if(done != null) {
                            completion=null; stop("MAGNETIC_FIELD")
                            dispatch { if(!closed) done.accept(SensorPort.DrainStatus.FLUSHED,"ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener=callbacks
            check(manager.registerListener(callbacks,hardware,100_000,0,Handler(Looper.myLooper()!!))) { "MAGNETIC_FIELD_REGISTER_REJECTED" }
        } catch(e:Exception) { stop(sensor); failed.accept("MAGNETIC_FIELD_START:${e.message}") }
    }
    override fun drain(sensor:String, done:SensorPort.Drain) {
        val current=listener
        if(current==null) {done.accept(SensorPort.DrainStatus.FAILED,"MAGNETIC_FIELD_NOT_ACTIVE");return}
        check(completion==null);completion=done
        if(!manager.flush(current)) {
            completion=null;stop(sensor)
            dispatch { if(!closed) done.accept(SensorPort.DrainStatus.FAILED,"ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor:String) {
        fence.close();listener?.let { manager.unregisterListener(it) };listener=null;completion=null
    }
    override fun close(){stop("MAGNETIC_FIELD");closed=true}
}
