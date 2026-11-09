package com.rawwave.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import com.rawwave.core.*

/**
 * Android platform-location boundary. LocationManager has no flush API, so drain returns
 * DELIVERED_ONLY with an explicit unknown tail. Values are copied unchanged; missing
 * optional fields stay null and are never replaced by zero.
 */
class LocationSensorAdapter(private val context: Context, private val dispatch: (() -> Unit) -> Unit): SensorPort {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var gnssSnapshot: LocationFix.Gnss? = null
    private var fence = SensorCallbackFence()
    private var closed = false
    private var failureDelivered = false
    private var deliveredFixes = 0L
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "android-location-gps/2"
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) { dispatch { if(!closed) ready.run() } }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        try {
            check(!closed && listener == null && sensor == "GPS")
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                error("GPS_PERMISSION_MISSING")
            check(manager.allProviders.contains(LocationManager.GPS_PROVIDER)) { "UNSUPPORTED_GPS_PROVIDER" }
            check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "GPS_PROVIDER_DISABLED" }
            failureDelivered = false
            deliveredFixes = 0L
            gnssSnapshot = null
            val gate = SensorCallbackFence(); fence = gate
            var sequence = 0L
            val callbacks = object: LocationListener {
                override fun onLocationChanged(location: Location) { gate.accept {
                    try {
                        val row = LocationFix.row(fix(location), gnssSnapshot, System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), ++sequence, 0, 1)
                        val snapshot = SensorRows.snapshot(listOf(row))
                        // requestLocationUpdates targets the capture owner's Looper.
                        if (!closed) { data.accept(snapshot); deliveredFixes++ }
                    } catch (e: Exception) {
                        dispatch { if (!closed) fail(failed, "GPS_CALLBACK_COPY:${e.message}") }
                    }
                } }
                override fun onProviderDisabled(provider: String) {
                    dispatch { if (!closed) fail(failed, "GPS_PROVIDER_DISABLED_MID_CAPTURE") }
                }
                override fun onProviderEnabled(provider: String) = Unit
            }
            listener = callbacks
            val gnss = object: GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    gnssSnapshot = snapshot(status)
                }
            }
            gnssCallback = gnss
            try {
                manager.registerGnssStatusCallback(gnss, Handler(Looper.myLooper()!!))
            } catch (_: Exception) {
                // The location stream remains useful without a GnssStatus snapshot.
                gnssCallback = null
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, callbacks, Looper.myLooper()!!)
        } catch (e: Exception) { fail(failed, "GPS_START:${e.message}") }
    }
    override fun drain(sensor: String, done: SensorPort.Drain) {
        if (listener == null) { done.accept(SensorPort.DrainStatus.FAILED, "GPS_NOT_ACTIVE"); return }
        val delivered = deliveredFixes
        stop(sensor)
        dispatch {
            if (!closed) done.accept(SensorPort.DrainStatus.DELIVERED_ONLY,
                "LOCATIONMANAGER_HAS_NO_FLUSH_API;REMOVE_UPDATES_RETURNED;DELIVERED_ROWS_ONLY_TAIL_UNKNOWN;DELIVERED_FIX_COUNT=$delivered" +
                    if (delivered == 0L) ";ZERO_FIXES_MEANS_NO_LOCATION_WAS_DELIVERED" else "")
        }
    }
    override fun stop(sensor: String) {
        fence.close()
        listener?.let { current -> try { manager.removeUpdates(current) } catch (_: Exception) {} }
        gnssCallback?.let { callback -> try { manager.unregisterGnssStatusCallback(callback) } catch (_: Exception) {} }
        listener = null
        gnssCallback = null
        gnssSnapshot = null
    }
    override fun close(){ stop("GPS"); closed = true }
    private fun fail(callback: SensorPort.Failure, detail: String) {
        if (failureDelivered) return
        failureDelivered = true
        stop("GPS")
        callback.accept(detail)
    }
    private fun snapshot(status: GnssStatus): LocationFix.Gnss {
        val counts = linkedMapOf<String, Int>()
        val usedCounts = linkedMapOf<String, Int>()
        var cn0Sum = 0.0
        var cn0Count = 0
        var maxCn0: Double? = null
        for (i in 0 until status.satelliteCount) {
            val name = constellationName(status.getConstellationType(i))
            counts[name] = (counts[name] ?: 0) + 1
            if (status.usedInFix(i)) usedCounts[name] = (usedCounts[name] ?: 0) + 1
            val cn0 = status.getCn0DbHz(i).toDouble()
            if (cn0.isFinite() && cn0 > 0.0) {
                cn0Sum += cn0
                cn0Count++
                if (maxCn0 == null || cn0 > maxCn0!!) maxCn0 = cn0
            }
        }
        return LocationFix.Gnss(
            status.satelliteCount,
            usedCounts.values.sum(),
            if (cn0Count > 0) cn0Sum / cn0Count else null,
            maxCn0,
            counts,
            usedCounts
        )
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "OTHER_$type"
    }

    private fun fix(location: Location) = LocationFix.Fix(
        location.provider ?: LocationManager.GPS_PROVIDER,
        location.latitude, location.longitude,
        if (location.hasAltitude()) location.altitude else null,
        if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
        if (location.hasSpeed()) location.speed.toDouble() else null,
        if (location.hasBearing()) location.bearing.toDouble() else null,
        if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
        if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
        if (location.time > 0L) location.time else null,
        location.elapsedRealtimeNanos,
        location.isFromMockProvider)
}
