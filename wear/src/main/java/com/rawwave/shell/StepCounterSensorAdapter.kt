package com.rawwave.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.*
import com.rawwave.core.*

/**
 * Android step-counter boundary. The platform cumulative count and original timestamp
 * stay unchanged. Callbacks are on-change, so long quiet periods while stationary are
 * expected; they are gaps in callbacks, not evidence that zero steps occurred.
 */
class StepCounterSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null
    private var fence = SensorCallbackFence()
    private var completion: SensorPort.Drain? = null
    private var closed = false
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-step-counter/1"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "STEP_COUNT")
            if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
                error("STEP_COUNT_PERMISSION_MISSING")
            val hardware = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: error("UNSUPPORTED_STEP_COUNTER")
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: SensorEventListener2 {
                override fun onSensorChanged(event: SensorEvent) { gate.accept {
                    try {
                        val row = StepCount.row(event.values[0], event.timestamp, event.accuracy,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                            ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        // SensorManager invokes this callback on the capture owner Handler.
                        if (!closed) data.accept(snapshot)
                    } catch (e: Exception) {
                        dispatch { if (!closed) failed.accept("STEP_COUNT_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onFlushCompleted(sensor: Sensor?) { dispatch {
                    if (listener === this) {
                        val done = completion
                        if (done != null) {
                            completion = null; stop("STEP_COUNT")
                            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FLUSHED, "ANDROID_FLUSH_ACKNOWLEDGED") }
                        }
                    }
                } }
            }
            listener = callbacks
            // On-change sensor: the period is only a hint and no fixed-rate stream is implied.
            check(manager.registerListener(callbacks, hardware, 0, Handler(Looper.myLooper()!!))) { "STEP_COUNT_REGISTER_REJECTED" }
        } catch (e: Exception) { stop(sensor); failed.accept("STEP_COUNT_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        val current = listener
        if (current == null) { done.accept(SensorPort.DrainStatus.FAILED, "STEP_COUNT_NOT_ACTIVE"); return }
        check(completion == null); completion = done
        if (!manager.flush(current)) {
            completion = null; stop(sensor)
            dispatch { if (!closed) done.accept(SensorPort.DrainStatus.FAILED, "ANDROID_FLUSH_REJECTED") }
        }
    }
    override fun stop(sensor: String) {
        fence.close(); listener?.let { manager.unregisterListener(it) }; listener = null; completion = null
    }
    override fun close(){ stop("STEP_COUNT"); closed = true }
}
