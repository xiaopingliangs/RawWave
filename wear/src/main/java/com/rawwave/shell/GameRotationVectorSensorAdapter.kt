package com.rawwave.shell

import android.content.Context
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android game rotation vector boundary. Values 0..3 are the platform's
 * unitless quaternion (x, y, z, w) using gyroscope and accelerometer without
 * the magnetometer. Timestamp and values stay unchanged; the app performs no
 * normalization or conversion.
 */
class GameRotationVectorSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-game-rotation-vector/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "GAME_ROTATION_VECTOR")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: error("UNSUPPORTED_GAME_ROTATION_VECTOR")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val v = event.values
                        val row = GameRotationVector.row(v[0], v[1], v[2], v[3],
                            event.timestamp, event.accuracy, System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("GAME_ROTATION_VECTOR_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("GAME_ROTATION_VECTOR")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            check(manager.registerListener(callbacks, hardware, 40_000, 0, Handler(Looper.myLooper()!!))) { "GAME_ROTATION_VECTOR_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("GAME_ROTATION_VECTOR_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "GAME_ROTATION_VECTOR_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("GAME_ROTATION_VECTOR"); closed = true }
}
