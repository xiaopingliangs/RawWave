package com.rawwave.shell

import android.os.Handler
import android.os.Looper
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
    private var prepareBudgetMs=ActivityTransferBudget.INITIAL_MS
    private var pendingRawBytesValue=0L
    internal var post: (() -> Unit) -> Unit = { action -> main.post(action) }
    internal var postDelayed: (Long, () -> Unit) -> Unit = { delay, action -> main.postDelayed(action, delay) }
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
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_STARTED", mapOf("trigger" to trigger)) } catch (_: Exception) {}
        app.activeSyncOwner=owner; app.stopBackgroundSync={ source ->
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_STOP_APPLIED", mapOf("source" to source,
                "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            finish(false,"同步已停止（$source）；数据仍保留在手表。")
        }
        pendingRawBytesValue=try { pendingRawBytes() } catch (_: Exception) { 0L }
        prepareBudgetMs=ActivityTransferBudget.prepareBudgetMs(pendingRawBytesValue)
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_PREPARE_BUDGET",mapOf(
            "trigger" to trigger,"rawBytes" to pendingRawBytesValue,
            "prepareBudgetMs" to prepareBudgetMs,
            "leaseMs" to ActivityTransferBudget.prepareLeaseMs(pendingRawBytesValue))) } catch (_: Exception) {}
        publish(true,false,"正在打包并准备活动原始数据…")
        try {
            attempts=1
            transfer=transferFactory(callbacks(attempts),prepareBudgetMs)
            // Packaging is a separate phase. A long activity must not be aborted by the
            // 80-second transfer window; the lease covers the raw-byte packaging estimate and
            // is extended only after the exact ZIP size is known.
            openLease()
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_START_FAILED",error) } catch (_: Exception) {}
            finish(false,"无法开始同步：${error.message}。数据仍保留在手表。")
        }
    }
    private fun openLease() {
        lease=leaseFactory(ActivityTransferBudget.prepareLeaseMs(pendingRawBytesValue),
            { transfer?.cancel() },
            { if(!finished) { lease?.extendBy(ActivityTransferBudget.initialLeaseMs()); transfer?.start() } },
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
        postDelayed(ActivityRetryPolicy.RETRY_DELAY_MS) {
            if(!finished && app.activeSyncOwner == owner) startRetry()
        }
    }
    private fun startRetry() {
        if(finished || app.activeSyncOwner != owner) return
        attempts++
        try {
            transfer?.cancel()
            // The previous attempt's lease is closed. A retry must open a new lease; reusing
            // the old one reports SYNC_DEADLINE within seconds even though the data is intact.
            SyncGuardService.close(lease); lease=null
            leaseExtended=false
            transfer=transferFactory(callbacks(attempts),prepareBudgetMs)
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_RETRY_STARTED",mapOf("attempt" to attempts,
                "maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS,"trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            publish(true,false,"正在自动重试同步（第 $attempts 次，共 ${ActivityRetryPolicy.MAX_ATTEMPTS} 次）…")
            openLease()
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
            // The watch home now stays on the last screen so a screen-on returns to it.
            // The capture and sync services own their own lifetimes; the UI never removes the task.
            app.pendingActivitySyncExit.set(false)
            publish(false,completed,text)
        }
        if(app.activeWatchActivitySync === this) app.activeWatchActivitySync=null
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_FINISHED",mapOf("success" to completed,"text" to text.take(500),"trigger" to trigger,"owner" to owner,"ownedStatus" to ownsStatus,"attempts" to attempts,"maxAttempts" to ActivityRetryPolicy.MAX_ATTEMPTS)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
    }
}
