package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.ActivityTransferBudget
import java.util.UUID

/** Application-retained watch sync owner so capture/UI lifecycles cannot cut a committed transfer in half. */
class WatchActivitySyncSession(private val app: ProbeApplication, private val trigger: String) {
    private val main=Handler(Looper.getMainLooper())
    private val owner=UUID.randomUUID().toString()
    private var transfer: ActivityTransferWindow?=null
    private var lease: SyncGuardService.Lease?=null
    private var finished=false
    internal var transferFactory: () -> ActivityTransferWindow = {
        ActivityTransferWindow(app,app,{ text -> main.post { if(!finished && app.activeSyncOwner == owner) publish(true,false,text) } },
            { success,text -> main.post { finish(success,text) } }, trigger=trigger,
            onArchiveSizeKnown = { bytes ->
                val extension = ActivityTransferBudget.leaseExtensionMs(bytes)
                if (extension > 0L) lease?.extendBy(extension)
            })
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
            transfer=transferFactory()
            // Start with the same short window as the transfer. The window extends this lease
            // only after packaging proves the archive is large enough; a small or failed package
            // therefore closes near the initial 80-second window instead of holding for 20 minutes.
            lease=SyncGuardService.open(app,leaseMs=ActivityTransferBudget.initialLeaseMs(),
                cancel={ transfer?.cancel() },
                ready={ if(!finished) { lease?.extendBy(ActivityTransferBudget.initialLeaseMs()); transfer?.start() } },
                ended={ reason -> finish(false,"同步在限定时间内没有完成（$reason）；数据仍保留在手表。") })
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_WATCH_SYNC_START_FAILED",error) } catch (_: Exception) {}
            finish(false,"无法开始同步：${error.message}。数据仍保留在手表。")
        }
    }
    private fun publish(running: Boolean,success: Boolean,text: String) {
        val status=ProbeApplication.ActivitySyncStatus(running,success,text,trigger)
        app.activitySyncStatus=status
        // A queued notification must not overwrite a newer owner's displayed state.
        main.post { if(app.activitySyncStatus === status) app.activitySyncObserver?.invoke(status) }
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
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_FINISHED",mapOf("success" to completed,"text" to text.take(500),"trigger" to trigger,"owner" to owner,"ownedStatus" to ownsStatus)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
    }
}
