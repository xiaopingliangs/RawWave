package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android uncalibrated magnetic-field boundary. Values 0..2 are the uncalibrated
 * field (microtesla), 3..5 are the hard-iron bias (microtesla). Timestamp and
 * values stay unchanged; the app performs no calibration.
 */
class MagneticFieldUncalibratedSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-magnetic-field-uncalibrated/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "MAGNETIC_FIELD_UNCALIBRATED")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: error("UNSUPPORTED_MAGNETIC_FIELD_UNCALIBRATED")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val v = event.values
                        val row = MagneticFieldUncalibrated.row(v[0], v[1], v[2], v[3], v[4], v[5],
                            event.timestamp, event.accuracy, System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("MAGNETIC_FIELD_UNCALIBRATED_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("MAGNETIC_FIELD_UNCALIBRATED")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            check(manager.registerListener(callbacks, hardware, 100_000, 0, Handler(Looper.myLooper()!!))) { "MAGNETIC_FIELD_UNCALIBRATED_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("MAGNETIC_FIELD_UNCALIBRATED_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "MAGNETIC_FIELD_UNCALIBRATED_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("MAGNETIC_FIELD_UNCALIBRATED"); closed = true }
}
