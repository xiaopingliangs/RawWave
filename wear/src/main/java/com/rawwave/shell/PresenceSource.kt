package com.rawwave.shell

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * "Is the watch on a wrist right now?" as a separate, replaceable seam.
 *
 * The platform low-latency off-body state is the only signal used. It is a state event, not
 * optical or skin data, and it is deliberately not inferred from PPG/SpO2 callbacks alone.
 * A missing sensor or a device that never reports leaves the state unknown, which keeps the
 * longer no-presence window instead of inventing a wrist.
 */
internal interface PresenceSource {
    fun start(listener: (Boolean) -> Unit)
    fun stop()

    object None : PresenceSource {
        override fun start(listener: (Boolean) -> Unit) = Unit
        override fun stop() = Unit
    }
}

internal class OffBodyPresence(context: Context) : PresenceSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener2? = null

    override fun start(listener: (Boolean) -> Unit) {
        if (this.listener != null) return
        val hardware = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) ?: return
        val callbacks = object : SensorEventListener2 {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull() ?: return
                if (value.isFinite()) listener(value > 0.5f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onFlushCompleted(sensor: Sensor?) = Unit
        }
        this.listener = callbacks
        // On-change state sensor: the platform normally delivers the current state on register,
        // so a worn watch becomes "on body" without waiting for a physical transition.
        if (manager == null || !manager.registerListener(callbacks, hardware, 0, Handler(Looper.getMainLooper()))) {
            this.listener = null
        }
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}
