package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rawwave.core.RunDeadlines
import com.rawwave.core.WorkBudget

/** Main-thread adapter; does not wake a sleeping device or hold it awake. */
class DeadlineRuntime(private val expired: (String, Map<String, Any>) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val deadlines = RunDeadlines { SystemClock.elapsedRealtime() }
    private var closed = false
    private var parked = false
    private val timer = Runnable { if (check("TIMER")) schedule() }

    fun evidence(): Map<String, Any> = mapOf(
        "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
        "uptimeMs" to SystemClock.uptimeMillis(),
        "deadlineClock" to "ELAPSED_REALTIME_INCLUDES_SLEEP",
        "deadlines" to deadlines.snapshot().mapKeys { it.key.name },
        "suspendedProcessDeadlineGuarantee" to false)

    fun check(trigger: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return false
        val due = deadlines.expired()
        if (due != null) {
            val details = evidence() + mapOf("trigger" to trigger, "expiredKind" to due.kind().name,
                "deadlineMs" to due.deadlineMs(), "observedMs" to due.observedMs(), "lateMs" to due.lateMs())
            close()
            expired("DEADLINE_${due.kind().name}", details)
            return false
        }
        return true
    }
    fun touchIdle() {
        if (check("INTERACTION")) { deadlines.touchIdle(WorkBudget.IDLE_MS); schedule() }
    }
    fun parkIdle(): Boolean {
        if (closed || deadlines.snapshot().keys.any { it != RunDeadlines.Kind.IDLE }) return false
        parked = true
        handler.removeCallbacks(timer)
        return true
    }
    fun resumeIdle(): Boolean {
        if (!parked || closed) return false
        parked = false
        if (!check("RESUME_IDLE")) return false
        val renewed = deadlines.resumeIdle(WorkBudget.IDLE_MS)
        if (renewed) schedule()
        return renewed
    }
    fun begin(kind: RunDeadlines.Kind, budgetMs: Long): Boolean {
        if (!check("BEGIN_${kind.name}")) return false
        val accepted = deadlines.begin(kind, budgetMs)
        schedule()
        return accepted
    }
    fun finishExport() {
        if (check("EXPORT_FINISHED")) { deadlines.finishExport(WorkBudget.IDLE_MS); schedule() }
    }
    fun finishTransfer() {
        if (check("TRANSFER_FINISHED")) { deadlines.finishTransfer(WorkBudget.IDLE_MS); schedule() }
    }
    private fun schedule() {
        handler.removeCallbacks(timer)
        val delay = deadlines.delayMs()
        if (!closed && !parked && delay != Long.MAX_VALUE) handler.postDelayed(timer, delay)
    }
    fun close() { closed = true; deadlines.close(); handler.removeCallbacks(timer) }
}

