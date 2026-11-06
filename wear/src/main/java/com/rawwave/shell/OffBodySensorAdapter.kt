package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android low-latency off-body boundary. The platform state value, original
 * timestamp and accuracy stay unchanged. State changes may be sparse; a long
 * quiet period is not evidence that the watch stayed on or off the wrist.
 */
class OffBodySensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-offbody/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "OFFBODY")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) ?: error("UNSUPPORTED_OFFBODY")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val row = OffBody.row(event.values[0], event.timestamp, event.accuracy,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                            ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        // SensorManager invokes this callback on the capture owner Handler.
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("OFFBODY_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("OFFBODY")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            // On-change state sensor: the period is only a hint and no fixed-rate stream is implied.
            check(manager.registerListener(callbacks, hardware, 0, Handler(Looper.myLooper()!!))) { "OFFBODY_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("OFFBODY_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "OFFBODY_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("OFFBODY"); closed = true }
}
