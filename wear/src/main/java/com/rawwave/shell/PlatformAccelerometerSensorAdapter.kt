package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android standard TYPE_ACCELEROMETER boundary. Values are platform calibrated m/s² and
 * include gravity. This is intentionally separate from the Samsung SDK raw integer
 * ACCELEROMETER stream; it is requested at 100 Hz and one row is stored per callback.
 */
class PlatformAccelerometerSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-platform-accelerometer/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if (!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == SensorCatalog.PLATFORM_ACCELEROMETER)
            val hardware = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?: error("UNSUPPORTED_PLATFORM_ACCELEROMETER")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object : SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val v = event.values
                        val row = PlatformAccelerometer.row(v[0], v[1], v[2], event.timestamp, event.accuracy,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime(), ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("PLATFORM_ACCELEROMETER_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop(SensorCatalog.PLATFORM_ACCELEROMETER)
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            // 100 Hz request (10,000 us); the platform may round to its nearest supported rate.
            check(manager.registerListener(callbacks, hardware, 10_000, 0, Handler(Looper.myLooper()!!))) {
                "PLATFORM_ACCELEROMETER_REGISTER_REJECTED"
            }
        } catch (e: Exception) { stop(sensor); failed.accept("PLATFORM_ACCELEROMETER_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "PLATFORM_ACCELEROMETER_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close() { stop(SensorCatalog.PLATFORM_ACCELEROMETER); closed = true }
}
