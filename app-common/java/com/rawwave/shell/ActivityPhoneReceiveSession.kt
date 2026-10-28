package com.rawwave.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    fun start() {
        try {
            val hello=JSONObject(String(bytes,Charsets.UTF_8))
            check(hello.getInt("protocol") == 1 && hello.getString("kind") == "RAW_ACTIVITY" && hello.getString("type") == "HELLO")
            trigger=ActivitySyncPolicy.normalize(hello.optString("trigger",ActivitySyncPolicy.UNKNOWN))
            val window=hello.getString("senderWindow")
            ActivityTransferFiles.validate(window,hello.getString("hash"),hello.getLong("bytes"))
            ActivityTransferFiles.validate(hello.getString("id"),hello.getString("hash"),hello.getLong("bytes"))
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
            owner=window; app.activeSyncOwner=window; app.stopBackgroundSync={ source -> finish("USER_EXIT:$source") }
            app.journal?.mark("RUNNING"); app.journal?.event("ACTIVITY_WATCH_INITIATED_PHONE_WAKE",mapOf("senderWindow" to window,"node" to node,"trigger" to trigger))
            val publish: (String) -> Unit = { text -> main.post {
                if(finished || app.activeSyncOwner != window) return@post
                val status=ProbeApplication.ActivitySyncStatus(true,false,text,trigger)
                app.activitySyncStatus=status; app.activitySyncObserver?.invoke(status)
            } }
            publish("手机已被手表唤醒，正在准备接收活动数据…")
            transfer=ActivityTransferWindow(app,app,publish, { success,text -> main.post { finish(if(success) "SUCCESS" else "FAILED",text) } },node,bytes)
            lease=SyncGuardService.open(app,cancel={ transfer?.cancel() },ready={ if(!finished) transfer?.start() },ended={ reason -> finish(reason) })
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
            if(reason == "SUCCESS" && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger)) app.syncFinished?.invoke(reason)
        }
        try { app.journal?.event("ACTIVITY_PHONE_WAKE_FINISHED",mapOf("reason" to reason,"owner" to (token ?: ""),"trigger" to trigger,"uiExitRequested" to (ownsStatus && reason == "SUCCESS" && ActivitySyncPolicy.exitAfterSuccessfulSync(trigger)),"automaticRetry" to false)); if(ownsStatus && app.activeSyncOwner == null) app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
        owner=null
    }
}


