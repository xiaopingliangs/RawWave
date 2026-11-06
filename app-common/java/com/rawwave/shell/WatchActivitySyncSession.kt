package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import com.rawwave.core.ActivityRetryPolicy
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.ActivityTransferBudget
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
    internal var post: (() -> Unit) -> Unit = { action -> main.post(action) }
    internal var postDelayed: (Long, () -> Unit) -> Unit = { delay, action -> main.postDelayed(action, delay) }
    internal var leaseFactory: (Long, () -> Unit, () -> Unit, (String) -> Unit) -> SyncGuardService.Lease? = { leaseMs, cancel, ready, ended ->
        SyncGuardService.open(app,leaseMs=leaseMs,cancel=cancel,ready=ready,ended=ended)
    }
    internal var transferFactory: (ActivityTransferCallbacks) -> ActivityTransferHandle = { callbacks ->
        ActivityTransferWindow(app,app,callbacks.update,callbacks.finished,trigger=trigger,
            onArchiveSizeKnown=callbacks.sizeKnown)
    }
    fun start() {
        if(BuildConfig.DEVICE_ROLE != "watch" || app.activeSyncOwner != null) { finish(false,"已有工作正在进行，活动数据稍后再同步。"); return }
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_STARTED", mapOf("trigger" to trigger)) } catch (_: Exception) {}
        app.activeSyncOwner=owner; app.stopBackgroundSync={ source ->
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_STOP_APPLIED", mapOf("source" to source,
                "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            finish(false,"同步已停止（$source）；数据仍保留在手表。")
        }
        publish(true,false,"正在准备活动原始数据…")
        try {
            attempts=1
            transfer=transferFactory(callbacks(attempts))
            // Start with the same short window as the transfer. The window extends this lease
            // only after packaging proves the archive is large enough; a small or failed package
            // therefore closes near the initial 80-second window instead of holding for 20 minutes.
            lease=leaseFactory(ActivityTransferBudget.initialLeaseMs(),
                { transfer?.cancel() },
                { if(!finished) { lease?.extendBy(ActivityTransferBudget.initialLeaseMs()); transfer?.start() } },
                { reason -> finish(false,"同步在限定时间内没有完成（$reason）；数据仍保留在手表。") })
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_START_FAILED",error) } catch (_: Exception) {}
            finish(false,"无法开始同步：${error.message}。数据仍保留在手表。")
        }
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
        if(!ActivityRetryPolicy.shouldRetry(attempts)) { finish(false,text); return }
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_RETRY_SCHEDULED",mapOf("attempt" to attempts,
            "maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS,"text" to text.take(300),
            "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
        publish(true,false,"第一次同步没有完成，${ActivityRetryPolicy.RETRY_DELAY_MS / 1000} 秒后自动重试…")
        postDelayed(ActivityRetryPolicy.RETRY_DELAY_MS) {
            if(!finished && app.activeSyncOwner == owner) startRetry()
        }
    }
    private fun startRetry() {
        if(finished || app.activeSyncOwner != owner) return
        attempts++
        try {
            transfer?.cancel()
            transfer=transferFactory(callbacks(attempts))
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_RETRY_STARTED",mapOf("attempt" to attempts,
                "maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS,"trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            publish(true,false,"正在自动重试同步（第 $attempts 次，共 ${ActivityRetryPolicy.MAX_ATTEMPTS} 次）…")
            transfer?.start()
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_RETRY_START_FAILED",error) } catch (_: Exception) {}
            finish(false,"无法开始重试：${error.message}。数据仍保留在手表。")
        }
    }
    private fun publish(running: Boolean,success: Boolean,text: String) {
        val status=ProbeApplication.ActivitySyncStatus(running,success,text,trigger)
        app.activitySyncStatus=status
        // A queued notification must not overwrite a newer owner's displayed state.
        post { if(app.activitySyncStatus === status) app.activitySyncObserver?.invoke(status) }
    }
    private fun finish(success: Boolean,text: String) {
        if(finished) return
        finished=true
        val cleanupError=com.rawwave.core.TransferCleanup.run({transfer?.cancel()},{SyncGuardService.close(lease)})
        transfer=null;lease=null
        if(cleanupError != null) try {app.journal?.fault("ACTIVITY_WATCH_OWNER_CLEANUP_FAILED",cleanupError)} catch(_:Exception) {}
        val completed=success && cleanupError == null
        val ownsStatus=app.activeSyncOwner == owner
        if(ownsStatus) {
            app.activeSyncOwner=null; app.stopBackgroundSync=null
            app.pendingActivitySyncExit.set(completed && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger))
            publish(false,completed,text)
        }
        if(app.activeWatchActivitySync === this) app.activeWatchActivitySync=null
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_FINISHED",mapOf("success" to completed,"text" to text.take(500),"trigger" to trigger,"owner" to owner,"ownedStatus" to ownsStatus,"attempts" to attempts,"maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
    }
}
