package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/** Android stream boundary. Platform timestamp nanoseconds and rad/s values stay unchanged. */
class GyroscopeSensorAdapter(context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-gyroscope/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "GYROSCOPE")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: error("UNSUPPORTED_GYROSCOPE")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                    val row = linkedMapOf<String,Any>("sensorTimestampNs" to event.timestamp,
                        "sdkTimestamp" to event.timestamp / 1_000_000L,
                        "timestampMeaning" to "Android boot nanoseconds; sdkTimestamp is derived milliseconds",
                        "source" to "Android SensorManager TYPE_GYROSCOPE", "unit" to "rad/s",
                        "accuracy" to event.accuracy, "receivedUtcMs" to System.currentTimeMillis(),
                        "receivedElapsedMs" to SystemClock.elapsedRealtime(), "captureCallbackSequence" to ++sequence,
                        "captureCallbackIndex" to 0, "captureCallbackSize" to 1)
                    listOf("GYROSCOPE_X","GYROSCOPE_Y","GYROSCOPE_Z").forEachIndexed { i,key ->
                        val v=event.values[i]; row[key]=if(v.isFinite()) v else v.toString()
                    }
                    val snapshot=SensorRows.snapshot(listOf(row))
                    dispatch { if(!closed) data.accept(snapshot) }
                    } catch(e:Exception) { dispatch { if(!closed) failed.accept("GYROSCOPE_CALLBACK_COPY:${e.message}") } }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if(listener === this) {
                        val done=completion
                        if(done != null) {
                            completion=null; stop("GYROSCOPE")
                            dispatch { if(!closed) done.accept(SensorPort.DrainStatus.FLUSHED,"ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener=callbacks
            check(manager.registerListener(callbacks,hardware,40_000,0,Handler(Looper.myLooper()!!))) { "GYROSCOPE_REGISTER_REJECTED" }
        } catch(e:Exception) { stop(sensor); failed.accept("GYROSCOPE_START:${e.message}") }
    }
    override fun drain(sensor:String, done:SensorPort.Drain) {
        val current=listener
        if(current==null) {done.accept(SensorPort.DrainStatus.FAILED,"GYROSCOPE_NOT_ACTIVE");return}
        check(completion==null);completion=done
        if(!manager.flush(current)) {
            completion=null;stop(sensor)
            dispatch { if(!closed) done.accept(SensorPort.DrainStatus.FAILED,"ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor:String) {
        fence.close();listener?.let { manager.unregisterListener(it) };listener=null;completion=null
    }
    override fun close(){stop("GYROSCOPE");closed=true}
}
