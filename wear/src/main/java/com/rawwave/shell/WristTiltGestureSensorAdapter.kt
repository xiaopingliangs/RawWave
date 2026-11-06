package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/** TYPE_WRIST_TILT_GESTURE is exposed by Wear OS but not a public SDK constant. */
private const val TYPE_WRIST_TILT_GESTURE = 26

/**
 * Android wrist-tilt gesture boundary. This is an on-change platform event
 * sensor; sparse callbacks are expected. The event value, original timestamp
 * and accuracy stay unchanged. The app performs no gesture inference.
 */
class WristTiltGestureSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-wrist-tilt-gesture/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "WRIST_TILT_GESTURE")
            val hardware = manager.getDefaultSensor(TYPE_WRIST_TILT_GESTURE) ?: error("UNSUPPORTED_WRIST_TILT_GESTURE")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val row = WristTiltGesture.row(event.values[0], event.timestamp, event.accuracy,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                            ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("WRIST_TILT_GESTURE_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("WRIST_TILT_GESTURE")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            // On-change event sensor: 0 is a hint only; no fixed-rate stream is implied.
            check(manager.registerListener(callbacks, hardware, 0, Handler(Looper.myLooper()!!))) { "WRIST_TILT_GESTURE_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("WRIST_TILT_GESTURE_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "WRIST_TILT_GESTURE_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("WRIST_TILT_GESTURE"); closed = true }
}
