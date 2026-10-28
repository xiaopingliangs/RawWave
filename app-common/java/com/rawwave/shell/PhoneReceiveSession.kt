package com.rawwave.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rawwave.core.TransferFiles
import com.rawwave.core.WakeHistory
import org.json.JSONArray
import org.json.JSONObject

/** Retained by the foreground lease until cleanup, independent of UI and listener bindings. */
class PhoneReceiveSession(private val app: ProbeApplication, private val node: String, private val bytes: ByteArray) {
    private val main = Handler(Looper.getMainLooper())
    private var transfer: TransferWindow? = null
    private var lease: SyncGuardService.Lease? = null
    private var owner: String? = null
    private var finished = false
    private val activityPreempt: () -> Unit = { finish("PREEMPTED_BY_ACTIVITY") }

    fun start() {
        try {
            val hello = JSONObject(String(bytes, Charsets.UTF_8))
            check(hello.getInt("protocol") == 1 && hello.getString("type") == "HELLO")
            val window = hello.getString("senderWindow")
            TransferFiles.validate(window, hello.getString("hash"), hello.getLong("bytes"))
            TransferFiles.validate(hello.getString("id"), hello.getString("hash"), hello.getLong("bytes"))
            if (app.activeSyncOwner != null) return
            val prefs = app.getSharedPreferences("wake-windows", Context.MODE_PRIVATE)
            val saved = JSONArray(prefs.getString("recent", "[]"))
            val history = WakeHistory((0 until saved.length()).map { saved.getString(it) })
            if (!history.accept(window)) return
            check(prefs.edit().putString("recent", JSONArray(history.snapshot()).toString()).remove("seen").commit()) {
                "WAKE_LEDGER_SAVE_FAILED"
            }
            owner = window; app.activeSyncOwner = window
            app.stopBackgroundSync = { source -> finish("USER_EXIT:$source") }
            app.preemptDiagnosticReceive = activityPreempt
            app.journal?.mark("RUNNING")
            app.journal?.event("WATCH_INITIATED_PHONE_WAKE", mapOf("senderWindow" to window, "node" to node))
            transfer = TransferWindow(app, app, {}, { success, _ -> main.post {
                finish(if (success) "SUCCESS" else "FAILED")
            } }, node, bytes)
            lease = SyncGuardService.open(app,
                cancel = { transfer?.cancel() },
                ready = { if (!finished) transfer?.start() },
                ended = { reason -> finish(reason) })
        } catch (error: Exception) {
            try { app.journal?.fault("PHONE_WAKE_FAILED", error) } catch (_: Exception) {}
            finish("START_FAILED")
        }
    }

    private fun finish(reason: String) {
        if (finished) return
        finished = true
        transfer?.cancel(); transfer = null
        SyncGuardService.close(lease); lease = null
        val token = owner
        if (token != null) app.lastStopReason = "BACKGROUND_SYNC_$reason"
        if (token != null && app.activeSyncOwner == token) {
            app.activeSyncOwner = null
            app.stopBackgroundSync = null
            if (app.preemptDiagnosticReceive === activityPreempt) app.preemptDiagnosticReceive = null
            app.syncFinished?.invoke(reason)
        }
        try {
            app.journal?.event("PHONE_WAKE_FINISHED", mapOf("reason" to reason, "owner" to (token ?: ""), "automaticRetry" to false))
            if (token != null) app.journal?.mark("CLEANUP_REQUESTED")
        } catch (_: Exception) {}
        owner = null
    }
}



