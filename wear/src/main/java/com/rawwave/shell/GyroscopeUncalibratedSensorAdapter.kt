package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android uncalibrated gyroscope boundary. Values 0..2 are angular velocity
 * (rad/s), 3..5 are the platform's estimated bias (rad/s). Timestamp and
 * values stay unchanged; the app performs no bias estimation.
 */
class GyroscopeUncalibratedSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-gyroscope-uncalibrated/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "GYROSCOPE_UNCALIBRATED")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: error("UNSUPPORTED_GYROSCOPE_UNCALIBRATED")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val v = event.values
                        val row = GyroscopeUncalibrated.row(v[0], v[1], v[2], v[3], v[4], v[5],
                            event.timestamp, event.accuracy, System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("GYROSCOPE_UNCALIBRATED_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("GYROSCOPE_UNCALIBRATED")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            check(manager.registerListener(callbacks, hardware, 40_000, 0, Handler(Looper.myLooper()!!))) { "GYROSCOPE_UNCALIBRATED_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("GYROSCOPE_UNCALIBRATED_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "GYROSCOPE_UNCALIBRATED_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("GYROSCOPE_UNCALIBRATED"); closed = true }
}
