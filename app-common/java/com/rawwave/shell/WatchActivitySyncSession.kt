package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rawwave.core.ActivityArchive
import com.rawwave.core.ActivityRetryPolicy
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.ActivityTransferBudget
import java.io.File
import java.util.UUID

internal data class ActivityTransferCallbacks(
    val update: (String) -> Unit,
    val finished: (Boolean,String) -> Unit,
    val sizeKnown: (Long) -> Unit
)

/** Application-retained watch sync owner so capture/UI lifecycles cannot cut a committed transfer in half. */
class WatchActivitySyncSession(private val app: ProbeApplication, private val trigger: String) {
    private val main=Handler(Looper.getMainLooper())
    private val owner=UUID.randomUUID().toString()
    private var transfer: ActivityTransferHandle?=null
    private var lease: SyncGuardService.Lease?=null
    private var finished=false
    @Volatile private var attempts=0
    @Volatile private var leaseExtended=false
    @Volatile private var retryPending=false
    @Volatile private var startedAtMs=0L
    @Volatile private var lastProgressMs=0L
    private var prepareBudgetMs=ActivityTransferBudget.INITIAL_MS
    private var pendingRawBytesValue=0L
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var post: (() -> Unit) -> Unit = { action -> main.post(action) }
    internal var postDelayed: (Long, () -> Unit) -> Unit = { delay, action -> main.postDelayed(action, delay) }
    /**
     * Runs the raw-byte scan away from the main thread. Walking a long activity's block files
     * took more than 10 seconds on SM-L350 and froze the UI (ANR), so sync preparation must
     * never do file I/O on the main thread.
     */
    internal var background: ((() -> Unit) -> Unit) = { work ->
        Thread(work, "RawWaveSyncPrep").apply { isDaemon = true }.start()
    }
    @Volatile private var leaseReady = false
    /** Raw bytes for the packaging budget; injectable so unit tests do not need Android filesDir. */
    internal var pendingRawBytes: () -> Long = {
        try {
            val root = app.filesDir
            if (root == null) 0L else ActivityArchive.pendingRawBytes(File(root, "activities"))
        } catch (_: Exception) { 0L }
    }
    internal var leaseFactory: (Long, () -> Unit, () -> Unit, (String) -> Unit) -> SyncGuardService.Lease? = { leaseMs, cancel, ready, ended ->
        SyncGuardService.open(app,leaseMs=leaseMs,cancel=cancel,ready=ready,ended=ended)
    }
    internal var transferFactory: (ActivityTransferCallbacks, Long) -> ActivityTransferHandle = { callbacks, prepareBudget ->
        ActivityTransferWindow(app,app,callbacks.update,callbacks.finished,trigger=trigger,
            prepareBudgetMs=prepareBudget,onArchiveSizeKnown=callbacks.sizeKnown)
    }
    fun start() {
        if(BuildConfig.DEVICE_ROLE != "watch" || app.activeSyncOwner != null) { finish(false,"已有工作正在进行，活动数据稍后再同步。"); return }
        startedAtMs=clock(); lastProgressMs=startedAtMs; retryPending=false
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_STARTED", mapOf("trigger" to trigger)) } catch (_: Exception) {}
        app.activeSyncOwner=owner; app.stopBackgroundSync={ source ->
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_STOP_APPLIED", mapOf("source" to source,
                "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            finish(false,"同步已停止（$source）；数据仍保留在手表。")
        }
        publish(true,false,"正在准备活动原始数据…")
        // Open the foreground lease first: the scan walks the session's block files and must
        // not run on the main thread, while the process still has to stay alive for it.
        // A background FGS start can be rejected by the platform; that must finish the session
        // with a readable message, not crash the watch UI process.
        try {
            openLease(ActivityTransferBudget.prepareLeaseMs(0L))
        } catch (error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_START_FAILED", error) } catch (_: Exception) {}
            finish(false, retryStartFailureText(error))
            return
        }
        background {
            val bytes = try { pendingRawBytes() } catch (_: Exception) { 0L }
            post { beginTransfer(bytes) }
        }
    }
    private fun beginTransfer(rawBytes: Long) {
        if (finished || app.activeSyncOwner != owner) return
        pendingRawBytesValue = rawBytes
        prepareBudgetMs = ActivityTransferBudget.prepareBudgetMs(rawBytes)
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_PREPARE_BUDGET",mapOf(
            "trigger" to trigger,"rawBytes" to pendingRawBytesValue,
            "prepareBudgetMs" to prepareBudgetMs,
            "leaseMs" to ActivityTransferBudget.prepareLeaseMs(pendingRawBytesValue))) } catch (_: Exception) {}
        // The early lease only covered the scan; extend it for the real packaging estimate.
        val current = lease
        if (current != null && !current.closed.get() && current.deadline > clock()) {
            val needed = prepareBudgetMs + ActivityTransferBudget.PACKAGING_MARGIN_MS
            if (current.deadline - clock() < needed) current.extendBy(needed)
        }
        try {
            attempts=1
            transfer=transferFactory(callbacks(attempts),prepareBudgetMs)
            if (leaseReady) transfer?.start()
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_START_FAILED",error) } catch (_: Exception) {}
            finish(false,"无法开始同步：${error.message}。数据仍保留在手表。")
        }
    }
    private fun openLease(leaseMs: Long = ActivityTransferBudget.prepareLeaseMs(pendingRawBytesValue)) {
        // A retry must not close the live foreground service first: closing it removes the
        // very exemption needed to start the next one, so a screen-off retry was rejected
        // with "startForegroundService() not allowed" after a long packaging failure.
        // Reuse the live lease when possible and only extend its already-running timer.
        val current = lease
        if (current != null && !current.closed.get() && current.service != null && current.deadline > clock()) {
            val needed = prepareBudgetMs + ActivityTransferBudget.PACKAGING_MARGIN_MS
            val remaining = current.deadline - clock()
            if (remaining < needed) current.extendBy(needed)
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_LEASE_REUSED", mapOf(
                "trigger" to trigger, "owner" to owner, "attempts" to attempts,
                "remainingMs" to (current.deadline - clock()).coerceAtLeast(0))) } catch (_: Exception) {}
            leaseReady = true
            transfer?.start()
            return
        }
        SyncGuardService.close(lease); lease = null
        leaseReady = false
        lease=leaseFactory(leaseMs,
            { transfer?.cancel() },
            { if(!finished) {
                leaseReady = true
                lease?.extendBy(ActivityTransferBudget.initialLeaseMs())
                transfer?.start()
            } },
            { reason -> finish(false,"同步在限定时间内没有完成（$reason）；数据仍保留在手表。") })
    }
    private fun callbacks(attemptId: Int): ActivityTransferCallbacks = ActivityTransferCallbacks(
        update = { text -> post { if(!finished && app.activeSyncOwner == owner && attemptId == attempts) publish(true,false,text) } },
        finished = { success,text -> post { if(attemptId == attempts) onAttemptFinished(success,text) } },
        sizeKnown = { bytes ->
            if(attemptId == attempts && !leaseExtended) {
                val extension = ActivityTransferBudget.leaseExtensionMs(bytes)
                if(extension > 0L) leaseExtended = lease?.extendBy(extension) == true
            }
        })
    private fun onAttemptFinished(success: Boolean,text: String) {
        if(finished) return
        if(success) { finish(true,text); return }
        // The hard per-attempt window is a deliberate pause, not a transient fault. Retrying
        // immediately would turn "5 minutes" into 15 minutes of continuous drain; the next
        // open/resume continues from the receiver's durable offset instead.
        if(text.contains("本轮同步时间已用完")) {
            finish(false,"已到 ${com.rawwave.core.ActivityTransferBudget.MAX_TRANSFER_MS / 60_000} 分钟单次上限，已停止；下次打开原波会从断点继续。")
            return
        }
        if(!ActivityRetryPolicy.shouldRetry(attempts)) { finish(false,text); return }
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_RETRY_SCHEDULED",mapOf("attempt" to attempts,
            "maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS,"text" to text.take(300),
            "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
        publish(true,false,"第 ${attempts} 次同步没有完成，${ActivityRetryPolicy.RETRY_DELAY_MS / 1000} 秒后自动重试…")
        retryPending=true
        postDelayed(ActivityRetryPolicy.RETRY_DELAY_MS) {
            if(!finished && app.activeSyncOwner == owner) startRetry()
        }
    }
    private fun startRetry() {
        if(finished || app.activeSyncOwner != owner) return
        retryPending=false
        attempts++
        try {
            transfer?.cancel()
            // Keep the current lease if it is still alive; openLease reuses it instead of
            // closing the foreground service before a background retry.
            leaseExtended=false
            transfer=transferFactory(callbacks(attempts),prepareBudgetMs)
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_RETRY_STARTED",mapOf("attempt" to attempts,
                "maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS,"trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            publish(true,false,"正在自动重试同步（第 $attempts 次，共 ${ActivityRetryPolicy.MAX_ATTEMPTS} 次）…")
            openLease()
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_RETRY_START_FAILED",error) } catch (_: Exception) {}
            finish(false, retryStartFailureText(error))
        }
    }

    private fun retryStartFailureText(error: Exception): String {
        val message = error.message.orEmpty()
        return if (message.contains("startForegroundService", ignoreCase = true) ||
            message.contains("ForegroundServiceStartNotAllowed", ignoreCase = true))
            "自动重试需要可见页面：请点亮手表并重新打开原波，再点“立即同步”。数据仍保留在手表。"
        else "无法开始重试：${error.message}。数据仍保留在手表。"
    }
    private fun publish(running: Boolean,success: Boolean,text: String) {
        lastProgressMs=clock()
        val status=ProbeApplication.ActivitySyncStatus(running,success,text,trigger)
        app.activitySyncStatus=status
        // A queued notification must not overwrite a newer owner's displayed state.
        post { if(app.activitySyncStatus === status) app.activitySyncObserver?.invoke(status) }
    }
    /**
     * A session can outlive its SyncGuardService if the service is destroyed without delivering
     * the lease callback (or if a callback is lost while the process survives). The home must not
     * stay on "同步中" forever. A live lease, a scheduled retry, or the short service-acquisition
     * grace keeps the session alive; otherwise it is stale after [STALL_GRACE_MS].
     */
    internal fun isStalled(): Boolean {
        if (finished) return true
        if (startedAtMs <= 0L) return false
        if (retryPending) return false
        if (hasLiveLease()) return false
        return clock() - maxOf(startedAtMs, lastProgressMs) >= STALL_GRACE_MS
    }

    internal fun abortStale(reason: String) {
        if (finished) return
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_STALE_ABORT", mapOf(
            "reason" to reason, "trigger" to trigger, "owner" to owner,
            "attempts" to attempts, "running" to app.activitySyncStatus.running,
            "text" to app.activitySyncStatus.text.take(200))) } catch (_: Exception) {}
        finish(false, "上次同步已结束；数据仍保留，可重新同步。")
    }

    private fun hasLiveLease(): Boolean {
        val current = lease ?: return false
        if (current.closed.get()) return false
        val service = current.service
        if (service != null) return current.deadline > clock()
        // startForegroundService() has been requested but the service has not acquired the
        // lease yet. Give it a short window before declaring the session dead.
        return clock() - startedAtMs < LEASE_ACQUIRE_GRACE_MS
    }

    private fun finish(success: Boolean,text: String) {
        if(finished) return
        finished=true
        retryPending=false
        leaseReady=false
        val cleanupError=com.rawwave.core.TransferCleanup.run({transfer?.cancel()},{SyncGuardService.close(lease)})
        transfer=null;lease=null
        if(cleanupError != null) try {app.journal?.fault("ACTIVITY_WATCH_OWNER_CLEANUP_FAILED",cleanupError)} catch(_:Exception) {}
        val completed=success && cleanupError == null
        val ownsStatus=app.activeSyncOwner == owner
        if(ownsStatus) {
            app.activeSyncOwner=null; app.stopBackgroundSync=null
            // Only a successful capture-finished/manual sync closes the watch task. A background
            // backlog sync must leave the home usable. The one-shot is consumed by the UI even
            // when the screen was off during the transfer.
            app.pendingActivitySyncExit.set(completed && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger))
            publish(false,completed,text)
        }
        if(app.activeWatchActivitySync === this) app.activeWatchActivitySync=null
        // The just-acknowledged sessions are now safe to evict if the watch is over its local
        // budget. Unacknowledged/unsealed sessions are never touched by the runner.
        if (completed) ActivityRetentionRunner.schedule(app, "AFTER_SYNC", force = true)
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_FINISHED",mapOf("success" to completed,"text" to text.take(500),"trigger" to trigger,"owner" to owner,"ownedStatus" to ownsStatus,"attempts" to attempts,"maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
    }

    private companion object {
        /** startForegroundService() may take a moment to bind before the lease is acquired. */
        const val LEASE_ACQUIRE_GRACE_MS = 20_000L
        /** No live lease, no pending retry, no progress for this long: the session is dead. */
        const val STALL_GRACE_MS = 45_000L
    }
}
