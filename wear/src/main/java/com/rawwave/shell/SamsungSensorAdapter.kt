package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import com.rawwave.core.SensorPort
import com.rawwave.core.SensorRows
import com.rawwave.core.SensorCallbackFence
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*

/** SDK boundary only. No storage, phase decisions, retry, or inferred samples. */
class SamsungSensorAdapter internal constructor(
    private val backend: SamsungBackend,
    private val dispatch: (() -> Unit) -> Unit,
    private val evidence: (String, Map<String, Any>) -> Unit,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val utc: () -> Long = { System.currentTimeMillis() },
    private val dispatchRows: ((List<Map<String, Any?>>, () -> Unit) -> Unit)? = null,
    private val releaseGraceMs: Long = 0L,
    private val postRetryGraceMs: Long = 0L,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : SensorPort {
    constructor(context: Context, dispatch: (() -> Unit) -> Unit, biaProfile: com.rawwave.core.BiaProfile? = null,
                dispatchRows: ((List<Map<String, Any?>>, () -> Unit) -> Unit)? = null) :
        this(DeviceSamsungBackend(context, biaProfile), dispatch, SensorReleaseDiagnostics(context)::record,
            dispatchRows = dispatchRows, releaseGraceMs = PRODUCTION_RELEASE_GRACE_MS,
            postRetryGraceMs = PRODUCTION_POST_RETRY_GRACE_MS)
    private companion object {
        /** A short pause between the last unset and connector teardown; not a hardware receipt. */
        const val PRODUCTION_RELEASE_GRACE_MS = 300L
        /** Extra time after the final unset retry, before the vendor connector is unbound. */
        const val PRODUCTION_POST_RETRY_GRACE_MS = 300L
    }
    private val ownerId = java.util.UUID.randomUUID().toString()
    private var attemptSequence = 0L
    override fun contractVersion() = SensorPort.CONTRACT_VERSION
    override fun implementationVersion() = "samsung-health-sensor-1.4.1/adapter-12"
    private fun cleanupEvidence(stage: String, sensor: String, detail: String = "") {
        try { evidence("CAPTURE_VENDOR_CLEANUP", mapOf("ownerId" to ownerId,
            "stage" to stage, "sensor" to sensor, "detail" to detail,
            "elapsedMs" to elapsed(), "hardwareStoppedVerified" to false)) } catch (_: Exception) {}
    }
    private var ready: Runnable? = null
    private var disconnected: SensorPort.Failure? = null
    private var connecting = false
    @Volatile private var closed = false
    private val trackers = mutableMapOf<String, SamsungTracker>()
    private class Delivery(val id: Long) {
        @Volatile var stoppedMs: Long? = null
        val lateCallbacks = java.util.concurrent.atomic.AtomicLong()
        @Volatile var lastStatus: Any? = null
        val fence = SensorCallbackFence()
        val endBoundary = com.rawwave.core.EdaEndBoundary()
        var sequence = 0L
        var completion: SensorPort.Drain? = null // serial owner only
        var flushRequestedMs: Long? = null
    }
    private val deliveries = mutableMapOf<String, Delivery>()
    override fun connect(ready: Runnable, disconnected: SensorPort.Failure) {
        check(!closed && !connecting) { "SENSOR_CONNECTION_ALREADY_USED" }
        this.ready = ready; this.disconnected = disconnected; connecting = true
        cleanupEvidence("CONNECT_REQUEST", "ALL")
        backend.connect({
            cleanupEvidence("CONNECTION_SUCCESS", "ALL", "closed=$closed")
            dispatch { if (!closed) ready.run() }
        }, {
            // Record at callback entry, even when the serial owner is already closed.
            cleanupEvidence("CONNECTION_ENDED_CALLBACK", "ALL", "closed=$closed")
            dispatch { if (!closed) disconnected.accept("SDK_CONNECTION_ENDED") }
        }, { reason ->
            cleanupEvidence("CONNECTION_FAILED_CALLBACK", "ALL", reason)
            dispatch { if (!closed) disconnected.accept(reason) }
        })
    }
    override fun start(sensor: String, data: SensorPort.Rows, failed: SensorPort.Failure) {
        if (closed || deliveries.containsKey(sensor)) {
            failed.accept(if (closed) "SENSOR_PORT_CLOSED" else "SENSOR_ALREADY_STARTED:$sensor")
            return
        }
        try {
            val tracker = trackers.getOrPut(sensor) {
                backend.create(sensor).also { cleanupEvidence("TRACKER_CREATED", sensor) }
            }
            trackers[sensor] = tracker
            val delivery = Delivery(++attemptSequence)
            deliveries[sensor] = delivery
            cleanupEvidence("LISTEN_REQUEST", sensor, "attempt=${delivery.id}; tracker=${System.identityHashCode(tracker)}")
            tracker.listen(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (delivery.stoppedMs != null) {
                        val n = delivery.lateCallbacks.incrementAndGet()
                        // Bounded evidence, including callbacks previously hidden by the fence.
                        if (n == 1L || n == 10L || n % 1000L == 0L)
                            cleanupEvidence("CALLBACK_AFTER_STOP", sensor,
                                "attempt=${delivery.id}; count=$n; points=${points.size}; sinceStopMs=${elapsed() - delivery.stoppedMs!!}; closed=$closed")
                    }
                    delivery.fence.accept {
                    val utc = utc()
                    val elapsed = elapsed()
                    try {
                        val callbackSequence = ++delivery.sequence
                        // Copy SDK-owned values immediately. captureCallback* fields are acquisition
                        // metadata; SDK fields keep their official names and are never inferred.
                        val rows = points.mapIndexed { index, p ->
                            val row = linkedMapOf<String, Any?>("sdkTimestamp" to p.timestamp,
                                "receivedUtcMs" to utc, "receivedElapsedMs" to elapsed,
                                "captureCallbackSequence" to callbackSequence,
                                "captureCallbackIndex" to index,
                                "captureCallbackSize" to points.size)
                            fun <T> field(name: String, key: ValueKey<T>) {
                                try {
                                    val v = p.getValue(key)
                                    row[name] = when {
                                        v is List<*> -> v.toList()
                                        v is Float && !v.isFinite() -> v.toString()
                                        v is Double && !v.isFinite() -> v.toString()
                                        else -> v
                                    }
                                    if ((v is Float && !v.isFinite()) || (v is Double && !v.isFinite())) row["${name}_encoding"] = "SDK_NON_FINITE_AS_STRING"
                                } catch (e: Exception) {
                                    row[name] = null
                                    row["${name}_readError"] = "${e.javaClass.simpleName}:${e.message}"
                                }
                            }
                            when (sensor) {
                                "MF_BIA" -> {
                                    field("STATUS", ValueKey.MfBiaSet.STATUS)
                                    field("PROGRESS", ValueKey.MfBiaSet.PROGRESS)
                                    field("BODY_IMPEDANCE_MAGNITUDE_5K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K)
                                    field("BODY_IMPEDANCE_PHASE_5K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_10K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K)
                                    field("BODY_IMPEDANCE_PHASE_10K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_50K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K)
                                    field("BODY_IMPEDANCE_PHASE_50K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K)
                                    field("BODY_IMPEDANCE_MAGNITUDE_250K", ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K)
                                    field("BODY_IMPEDANCE_PHASE_250K", ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K)
                                }
                                "BIA" -> {
                                    field("STATUS", ValueKey.BiaSet.STATUS)
                                    field("BODY_FAT_RATIO", ValueKey.BiaSet.BODY_FAT_RATIO)
                                    field("BODY_FAT_MASS", ValueKey.BiaSet.BODY_FAT_MASS)
                                    field("TOTAL_BODY_WATER", ValueKey.BiaSet.TOTAL_BODY_WATER)
                                    field("SKELETAL_MUSCLE_RATIO", ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO)
                                    field("SKELETAL_MUSCLE_MASS", ValueKey.BiaSet.SKELETAL_MUSCLE_MASS)
                                    field("BASAL_METABOLIC_RATE", ValueKey.BiaSet.BASAL_METABOLIC_RATE)
                                    field("FAT_FREE_RATIO", ValueKey.BiaSet.FAT_FREE_RATIO)
                                    field("FAT_FREE_MASS", ValueKey.BiaSet.FAT_FREE_MASS)
                                    field("PROGRESS", ValueKey.BiaSet.PROGRESS)
                                    field("BODY_IMPEDANCE_MAGNITUDE", ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE)
                                    field("BODY_IMPEDANCE_DEGREE", ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE)
                                }
                                "SPO2" -> {
                                    field("SPO2", ValueKey.SpO2Set.SPO2)
                                    field("STATUS", ValueKey.SpO2Set.STATUS)
                                    field("HEART_RATE", ValueKey.SpO2Set.HEART_RATE)
                                }
                                "ECG" -> {
                                    field("ECG_MV", ValueKey.EcgSet.ECG_MV)
                                    // Samsung documents these fields by callback position.
                                    if (index == 0) {
                                        field("LEAD_OFF", ValueKey.EcgSet.LEAD_OFF)
                                        field("SEQUENCE", ValueKey.EcgSet.SEQUENCE)
                                        field("PPG_GREEN", ValueKey.EcgSet.PPG_GREEN)
                                        field("MAX_THRESHOLD_MV", ValueKey.EcgSet.MAX_THRESHOLD_MV)
                                        field("MIN_THRESHOLD_MV", ValueKey.EcgSet.MIN_THRESHOLD_MV)
                                    } else if (points.size == 10 && index == 5) {
                                        field("PPG_GREEN", ValueKey.EcgSet.PPG_GREEN)
                                    }
                                }
                                "HEART_RATE" -> {
                                    field("HEART_RATE", ValueKey.HeartRateSet.HEART_RATE)
                                    field("HEART_RATE_STATUS", ValueKey.HeartRateSet.HEART_RATE_STATUS)
                                    field("IBI_LIST", ValueKey.HeartRateSet.IBI_LIST)
                                    field("IBI_STATUS_LIST", ValueKey.HeartRateSet.IBI_STATUS_LIST)
                                }
                                "ACCELEROMETER" -> {
                                    field("ACCELEROMETER_X", ValueKey.AccelerometerSet.ACCELEROMETER_X)
                                    field("ACCELEROMETER_Y", ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                                    field("ACCELEROMETER_Z", ValueKey.AccelerometerSet.ACCELEROMETER_Z)
                                }
                                "PPG", "PPG_ON_DEMAND" -> {
                                    field("PPG_GREEN", ValueKey.PpgSet.PPG_GREEN)
                                    field("GREEN_STATUS", ValueKey.PpgSet.GREEN_STATUS)
                                    field("PPG_IR", ValueKey.PpgSet.PPG_IR)
                                    field("IR_STATUS", ValueKey.PpgSet.IR_STATUS)
                                    field("PPG_RED", ValueKey.PpgSet.PPG_RED)
                                    field("RED_STATUS", ValueKey.PpgSet.RED_STATUS)
                                }
                                "SKIN_TEMPERATURE" -> {
                                    field("OBJECT_TEMPERATURE", ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                                    field("AMBIENT_TEMPERATURE", ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                                    field("STATUS", ValueKey.SkinTemperatureSet.STATUS)
                                }
                                "EDA" -> {
                                    field("SKIN_CONDUCTANCE", ValueKey.EdaSet.SKIN_CONDUCTANCE)
                                    field("STATUS", ValueKey.EdaSet.STATUS)
                                }
                                else -> error("UNKNOWN_SENSOR_CALLBACK:$sensor")
                            }
                            row
                        }
                        rows.lastOrNull()?.let { delivery.lastStatus = it["STATUS"] ?: it["HEART_RATE_STATUS"] }
                        val snapshot = SensorRows.snapshot(rows)
                        val deliver = {
                            if (!closed) {
                                data.accept(snapshot)
                                if(sensor=="EDA" && deliveries[sensor] === delivery) {
                                    val covered=delivery.endBoundary.observe(snapshot)
                                    if(covered && delivery.completion!=null)
                                        endDrain(sensor,delivery,SensorPort.DrainStatus.END_TIME_COVERED,delivery.endBoundary.evidence())
                                }
                            }
                        }
                        if (dispatchRows != null) dispatchRows.invoke(snapshot, deliver) else dispatch(deliver)
                    } catch (e: Exception) { dispatch { if (!closed) failed.accept("CALLBACK_COPY:${e.message}") } }
                    }
                }
                override fun onFlushCompleted() {
                    val callbackElapsedMs = elapsed()
                    dispatch {
                        cleanupEvidence("FLUSH_CALLBACK", sensor,
                            "callbackElapsedMs=$callbackElapsedMs; dispatchElapsedMs=${elapsed()}; requestElapsedMs=${delivery.flushRequestedMs}; active=${deliveries[sensor] === delivery}; waiting=${delivery.completion != null}")
                        if (!closed && deliveries[sensor] === delivery && delivery.completion != null)
                            endDrain(sensor, delivery, SensorPort.DrainStatus.FLUSHED, "SDK_FLUSH_ACKNOWLEDGED")
                    }
                }
                override fun onError(error: HealthTracker.TrackerError) {
                    cleanupEvidence("TRACKER_ERROR", sensor, "attempt=${delivery.id}; error=$error; stopped=${delivery.stoppedMs != null}")
                    dispatch {
                    if (!closed && deliveries[sensor] === delivery) {
                        if (deliveries[sensor] === delivery && delivery.completion != null)
                            endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_ERROR:$error")
                        else failed.accept(error.toString())
                    }
                } }
            })
            cleanupEvidence("LISTEN_RETURNED", sensor, "attempt=${delivery.id}")
        } catch (e: Exception) {
            cleanupEvidence("START_FAILED", sensor, e.toString())
            // A set-listener failure may follow a partial registration. Retain the handle
            // for final cleanup even if immediate removal fails.
            if (!closed && deliveries.containsKey(sensor)) try { stop(sensor) } catch (_: Exception) {}
            failed.accept("${e.javaClass.simpleName}:${e.message}")
        }
    }
    override fun drain(sensor: String, completed: SensorPort.Drain) {
        val delivery = deliveries[sensor]
        if (closed || delivery == null) { completed.accept(SensorPort.DrainStatus.FAILED, "SOURCE_NOT_ACTIVE"); return }
        check(delivery.completion == null) { "DRAIN_ALREADY_REQUESTED:$sensor" }
        delivery.completion = completed
        if(sensor=="EDA") delivery.endBoundary.arm(utc(),elapsed())
        if (sensor == "ECG" || sensor == "SPO2" || sensor == "PPG_ON_DEMAND" || (sensor == "BIA" || sensor == "MF_BIA")) {
            endDrain(sensor, delivery, SensorPort.DrainStatus.DELIVERED_ONLY, "${sensor}_HAS_NO_SDK_FLUSH;ACCEPTED_CALLBACKS_DRAINED")
            return
        }
        try {
            delivery.flushRequestedMs = elapsed()
            cleanupEvidence("FLUSH_REQUEST", sensor, "requestElapsedMs=${delivery.flushRequestedMs}")
            val accepted = trackers[sensor]?.flush() == true
            cleanupEvidence("FLUSH_RETURNED", sensor, "accepted=$accepted; requestElapsedMs=${delivery.flushRequestedMs}")
            if (!accepted)
                endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_FLUSH_REJECTED")
        } catch (e: Exception) {
            cleanupEvidence("FLUSH_EXCEPTION", sensor, e.toString())
            endDrain(sensor, delivery, SensorPort.DrainStatus.FAILED, "SDK_FLUSH_EXCEPTION:${e.message}")
        }
    }
    private fun endDrain(sensor: String, delivery: Delivery, status: SensorPort.DrainStatus, detail: String) {
        val completed = delivery.completion ?: return
        delivery.completion = null
        var outcome = status
        var description = detail
        try { stop(sensor) } catch (e: Exception) {
            outcome = SensorPort.DrainStatus.FAILED; description += ";STOP_FAILED:${e.message}"
        }
        // stop acquires the same monitor as callback copying/posting. Every accepted data
        // task is therefore ahead of this completion task on the owner's serial dispatcher.
        dispatch { if (!closed) completed.accept(outcome, description) }
    }
    override fun stop(sensor: String) {
        val delivery = deliveries[sensor] ?: return
        delivery.stoppedMs = elapsed()
        delivery.fence.close()
        val tracker = trackers[sensor]
        if (tracker != null) {
            cleanupEvidence("UNSET_REQUEST", sensor, "attempt=${delivery.id}; lastStatus=${delivery.lastStatus}")
            try { tracker.unset(); cleanupEvidence("UNSET_RETURNED", sensor, "attempt=${delivery.id}") }
            catch(e: Exception) { cleanupEvidence("UNSET_FAILED", sensor, e.toString()); throw e }
        }
        // Keep one handle per type for retries and a final release sweep. Samsung's void
        // unset API can swallow RemoteException; return is not a remote release receipt.
        deliveries.remove(sensor)
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        deliveries.keys.toList().forEach { try { stop(it) } catch (e: Exception) { failure = e } }
        trackers.forEach { (sensor, tracker) ->
            cleanupEvidence("FINAL_UNSET_REQUEST", sensor, "tracker=${System.identityHashCode(tracker)}")
            try { tracker.unset(); cleanupEvidence("FINAL_UNSET_RETURNED", sensor) }
            catch(e: Exception) { cleanupEvidence("FINAL_UNSET_FAILED", sensor, e.toString()); failure = e }
        }
        // Give the vendor service a short window to process the final unset before the
        // connector is torn down, then retry each retained handle once. SDK return values
        // are still not treated as a hardware-stop receipt.
        if (releaseGraceMs > 0L && trackers.isNotEmpty()) {
            cleanupEvidence("RELEASE_GRACE_BEFORE_DISCONNECT", "ALL", "graceMs=$releaseGraceMs")
            try { pause(releaseGraceMs) }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cleanupEvidence("RELEASE_GRACE_INTERRUPTED", "ALL", e.toString())
            }
            trackers.forEach { (sensor, tracker) ->
                cleanupEvidence("FINAL_UNSET_RETRY_REQUEST", sensor, "tracker=${System.identityHashCode(tracker)}")
                try { tracker.unset(); cleanupEvidence("FINAL_UNSET_RETRY_RETURNED", sensor) }
                catch(e: Exception) { cleanupEvidence("FINAL_UNSET_RETRY_FAILED", sensor, e.toString()); failure = e }
            }
        }
        // Give the vendor service a short window after the last unset retry before unbinding.
        // This is still only cleanup evidence; it is not a hardware-stop receipt.
        if (postRetryGraceMs > 0L && trackers.isNotEmpty()) {
            cleanupEvidence("RELEASE_GRACE_AFTER_UNSET_RETRY", "ALL", "graceMs=$postRetryGraceMs")
            try { pause(postRetryGraceMs) }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cleanupEvidence("RELEASE_GRACE_AFTER_RETRY_INTERRUPTED", "ALL", e.toString())
            }
        }
        // Clear adapter-owned vendor references before tearing the connector down. The final
        // unset retries above have already run; no tracker handle should outlive this point.
        val retainedTypes = trackers.keys.toList()
        val retainedDeliveries = deliveries.keys.toList()
        cleanupEvidence("REFS_CLEARED_BEFORE_DISCONNECT", "ALL",
            "trackers=$retainedTypes; deliveries=$retainedDeliveries")
        trackers.clear(); deliveries.clear()
        cleanupEvidence("DISCONNECT_REQUEST", "ALL", "retainedTypes=$retainedTypes")
        try { backend.disconnect(); cleanupEvidence("DISCONNECT_RETURNED", "ALL") }
        catch (e: Exception) { cleanupEvidence("DISCONNECT_FAILED", "ALL", e.toString()); failure = e }
        cleanupEvidence("DISCONNECT_RECHECK", "ALL",
            "trackersRetained=${trackers.size}; deliveriesRetained=${deliveries.size}; hardwareStoppedVerified=false")
        cleanupEvidence("OWNER_CLOSED", "ALL", "failure=${failure?.toString().orEmpty()}")
        failure?.let { throw it }
    }
}
