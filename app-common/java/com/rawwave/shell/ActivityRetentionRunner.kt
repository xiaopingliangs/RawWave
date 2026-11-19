package com.rawwave.shell

import com.rawwave.core.ActivityRetention
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch-only background cleanup of watch-local raw sessions.
 *
 * It never deletes the active session, an unsealed session, or a session without a durable
 * phone acknowledgement. The phone keeps its received copies; this only bounds the watch.
 * Scanning block sizes is metadata work, but it still runs off the main thread.
 */
internal object ActivityRetentionRunner {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rawwave-retention").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastRunMs = 0L

    fun schedule(app: ProbeApplication, reason: String, force: Boolean = false) {
        if (BuildConfig.DEVICE_ROLE != "watch") return
        val now = System.currentTimeMillis()
        // A fresh process has lastRunMs = 0 and always checks once. After a successful sync we
        // force a check so the just-acknowledged session can be evicted if the cap is exceeded.
        if (!force && lastRunMs != 0L && now - lastRunMs < 60L * 60L * 1000L) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val summary = ActivityRetention.enforce(File(app.filesDir, "activities"), now)
                app.lastActivityRetention = summary
                lastRunMs = System.currentTimeMillis()
                try {
                    app.journal?.event("ACTIVITY_RETENTION_RUN", mapOf(
                        "reason" to reason,
                        "keptSynced" to summary.keptSynced(),
                        "pending" to summary.pending(),
                        "keptSyncedBytes" to summary.keptSyncedBytes(),
                        "pendingBytes" to summary.pendingBytes(),
                        "deletedSessions" to summary.deletedSessions(),
                        "freedBytes" to summary.freedBytes(),
                        "maxSessions" to ActivityRetention.MAX_SYNCED_SESSIONS,
                        "maxLocalBytes" to ActivityRetention.MAX_LOCAL_BYTES,
                        "maxAgeDays" to ActivityRetention.MAX_AGE_MS / 86_400_000L,
                    ))
                } catch (_: Exception) {}
            } catch (error: Exception) {
                try { app.journal?.fault("ACTIVITY_RETENTION_FAILED", error) } catch (_: Exception) {}
            } finally {
                running.set(false)
            }
        }
    }
}
