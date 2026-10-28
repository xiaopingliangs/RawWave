package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Application-retained watch sync owner so capture/UI lifecycles cannot cut a committed transfer in half. */
class WatchActivitySyncSession(private val app: ProbeApplication, private val trigger: String) {
    private val main=Handler(Looper.getMainLooper())
    private val owner=UUID.randomUUID().toString()
    private var transfer: ActivityTransferWindow?=null
    private var lease: SyncGuardService.Lease?=null
    private var finished=false
    fun start() {
        if(BuildConfig.DEVICE_ROLE != "watch" || app.activeSyncOwner != null) { finish(false,"已有工作正在进行，活动数据稍后再同步。"); return }
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_STARTED", mapOf("trigger" to trigger)) } catch (_: Exception) {}
        app.activeSyncOwner=owner; app.stopBackgroundSync={ source ->
            try { app.journal?.event("ACTIVITY_WATCH_SYNC_STOP_APPLIED", mapOf("source" to source,
                "trigger" to trigger,"owner" to owner)) } catch (_: Exception) {}
            finish(false,"同步已停止（$source）；数据仍保留在手表。")
        }
        publish(true,false,"正在准备活动原始数据…")
        transfer=ActivityTransferWindow(app,app,{ text -> main.post { if(!finished && app.activeSyncOwner == owner) publish(true,false,text) } },
            { success,text -> main.post { finish(success,text) } }, trigger=trigger)
        try {
            lease=SyncGuardService.open(app,cancel={ transfer?.cancel() },ready={ if(!finished) transfer?.start() },
                ended={ reason -> finish(false,"同步在80秒内没有完成（$reason）；数据仍保留在手表。") })
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
        finished=true; transfer?.cancel(); transfer=null; SyncGuardService.close(lease); lease=null
        val ownsStatus=app.activeSyncOwner == owner
        if(ownsStatus) {
            app.activeSyncOwner=null; app.stopBackgroundSync=null
            publish(false,success,text)
        }
        if(app.activeWatchActivitySync === this) app.activeWatchActivitySync=null
        try { app.journal?.event("ACTIVITY_WATCH_SYNC_FINISHED",mapOf("success" to success,"text" to text.take(500),"trigger" to trigger,"owner" to owner,"ownedStatus" to ownsStatus)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
    }
}
