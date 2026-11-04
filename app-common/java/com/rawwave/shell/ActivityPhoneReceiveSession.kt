package com.rawwave.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rawwave.core.ActivityTransferBudget
import com.rawwave.core.ActivityTransferFiles
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.SyncAdmission
import com.rawwave.core.WakeHistory
import org.json.JSONArray
import org.json.JSONObject

/** Phone-side owner for one watch-initiated raw activity transfer. */
class ActivityPhoneReceiveSession(private val app: ProbeApplication,private val node: String,private val bytes: ByteArray) {
    private val main=Handler(Looper.getMainLooper())
    private var transfer: ActivityTransferWindow?=null
    private var lease: SyncGuardService.Lease?=null
    private var owner: String?=null
    private var trigger=ActivitySyncPolicy.UNKNOWN
    private var finished=false
    private var idleHeld=false
    fun start() {
        try {
            val hello=JSONObject(String(bytes,Charsets.UTF_8))
            check(hello.getInt("protocol") == 1 && hello.getString("kind") == "RAW_ACTIVITY" && hello.getString("type") == "HELLO")
            trigger=ActivitySyncPolicy.normalize(hello.optString("trigger",ActivitySyncPolicy.UNKNOWN))
            val window=hello.getString("senderWindow")
            val archiveBytes=hello.getLong("bytes")
            ActivityTransferFiles.validate(window,hello.getString("hash"),archiveBytes)
            ActivityTransferFiles.validate(hello.getString("id"),hello.getString("hash"),archiveBytes)
            when(SyncAdmission.activityArrival(app.activeSyncOwner != null,app.preemptDiagnosticReceive != null)) {
                SyncAdmission.Decision.ACCEPT -> Unit
                SyncAdmission.Decision.PREEMPT_DIAGNOSTIC -> {
                    app.journal?.event("ACTIVITY_PHONE_WAKE_PREEMPTING_DIAGNOSTIC",mapOf("owner" to (app.activeSyncOwner ?: "")))
                    app.preemptDiagnosticReceive?.invoke()
                    if(app.activeSyncOwner != null) {
                        app.journal?.event("ACTIVITY_PHONE_WAKE_PREEMPT_FAILED",mapOf("owner" to (app.activeSyncOwner ?: "")))
                        return
                    }
                }
                SyncAdmission.Decision.REJECT_BUSY -> {
                    app.journal?.event("ACTIVITY_PHONE_WAKE_BUSY",mapOf("owner" to (app.activeSyncOwner ?: "")))
                    return
                }
            }
            val prefs=app.getSharedPreferences("activity-wake-windows",Context.MODE_PRIVATE)
            val saved=JSONArray(prefs.getString("recent","[]")); val history=WakeHistory((0 until saved.length()).map { saved.getString(it) })
            if(!history.accept(window)) return
            check(prefs.edit().putString("recent",JSONArray(history.snapshot()).toString()).commit()) { "ACTIVITY_WAKE_LEDGER_SAVE_FAILED" }
            // Hold the diagnostic page's idle countdown before the file transfer starts. If a
            // page owns a deadline and cannot suspend it, do not start a receive that the idle
            // exit would immediately cancel.
            val holdIdle=app.activityIdleHold
            if(holdIdle!=null) {
                if(!holdIdle()) {
                    try { app.journal?.event("ACTIVITY_PHONE_WAKE_IDLE_HOLD_FAILED",mapOf(
                        "senderWindow" to window,"trigger" to trigger,"automaticRetry" to false)) } catch (_: Exception) {}
                    return
                }
                idleHeld=true
            }
            owner=window; app.activeSyncOwner=window; app.stopBackgroundSync={ source -> finish("USER_EXIT:$source") }
            app.journal?.mark("RUNNING"); app.journal?.event("ACTIVITY_WATCH_INITIATED_PHONE_WAKE",mapOf("senderWindow" to window,"node" to node,"trigger" to trigger))
            val publish: (String) -> Unit = { text -> main.post {
                if(finished || app.activeSyncOwner != window) return@post
                val status=ProbeApplication.ActivitySyncStatus(true,false,text,trigger)
                app.activitySyncStatus=status; app.activitySyncObserver?.invoke(status)
            } }
            publish("手机已被手表唤醒，正在准备接收活动数据…")
            transfer=ActivityTransferWindow(app,app,publish, { success,text -> main.post { finish(if(success) "SUCCESS" else "FAILED",text) } },node,bytes,
                onArchiveSizeKnown = { size ->
                    val extension = ActivityTransferBudget.leaseExtensionMs(size)
                    if (extension > 0L) lease?.extendBy(extension)
                })
            lease=SyncGuardService.open(app,leaseMs=ActivityTransferBudget.leaseMs(archiveBytes),
                cancel={ transfer?.cancel() },
                ready={ if(!finished) { lease?.extendBy(ActivityTransferBudget.initialLeaseMs()); transfer?.start() } },
                ended={ reason -> finish(reason) })
        } catch(error: Exception) {
            try { app.journal?.fault("ACTIVITY_PHONE_WAKE_FAILED",error) } catch (_: Exception) {}
            finish("START_FAILED")
        }
    }
    private fun finish(reason: String,text: String="") {
        if(finished) return
        finished=true; transfer?.cancel(); transfer=null; SyncGuardService.close(lease); lease=null
        val token=owner
        val ownsStatus=token != null && app.activeSyncOwner == token
        if(ownsStatus) {
            app.lastStopReason="ACTIVITY_BACKGROUND_SYNC_$reason"
            app.activeSyncOwner=null; app.stopBackgroundSync=null
            app.activitySyncStatus=ProbeApplication.ActivitySyncStatus(false,reason == "SUCCESS",if(text.isEmpty()) reason else text,trigger)
            app.activitySyncObserver?.invoke(app.activitySyncStatus)
        }
        if(idleHeld && (ownsStatus || token == null)) {
            idleHeld=false
            try { app.activityIdleRelease?.invoke() } catch (_: Exception) {}
        }
        if(ownsStatus && reason == "SUCCESS" && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger)) app.syncFinished?.invoke(reason)
        try { app.journal?.event("ACTIVITY_PHONE_WAKE_FINISHED",mapOf("reason" to reason,"owner" to (token ?: ""),"trigger" to trigger,"uiExitRequested" to (ownsStatus && reason == "SUCCESS" && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger)),"automaticRetry" to false)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
        owner=null
    }
}


