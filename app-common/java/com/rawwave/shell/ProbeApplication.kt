package com.rawwave.shell

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import com.rawwave.core.ActivitySyncAttemptGuard
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.Journal
import java.io.File

class ProbeApplication : Application() {
    // One application-owned storage instance; listener lifetime does not own transfer leases.
    val summaryReceiver by lazy {
        com.rawwave.core.SummaryReceiver(com.rawwave.core.FileSummaryStore(filesDir) { folder ->
            val fd = android.system.Os.open(folder.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        })
    }
    data class ActivitySyncStatus(val running: Boolean = false, val success: Boolean = false,
        val text: String = "", val trigger: String = "")
    @Volatile var activeSyncOwner: String? = null
    // Main-thread UI observers, never required for background transfer or cleanup.
    var syncFinished: ((String) -> Unit)? = null
    var stopBackgroundSync: ((String) -> Unit)? = null
    // Only diagnostic receive windows expose this hook; raw activity may preempt them.
    var preemptDiagnosticReceive: (() -> Unit)? = null
    var receiveHello: ((String, ByteArray) -> Unit)? = null
    var receiveActivityHello: ((String, ByteArray) -> Unit)? = null
    /** Main-thread UI idle hooks; absent when no diagnostic page owns a deadline. */
    var activityIdleHold: (() -> Boolean)? = null
    var activityIdleRelease: (() -> Boolean)? = null
    @Volatile var activitySyncStatus = ActivitySyncStatus()
    var activitySyncObserver: ((ActivitySyncStatus) -> Unit)? = null
    /** One-shot completion signal; consumed by the watch UI even after onStop/onStart. */
    val pendingActivitySyncExit = java.util.concurrent.atomic.AtomicBoolean(false)
    var activeWatchActivitySync: WatchActivitySyncSession? = null
    val activitySyncAttemptGuard = ActivitySyncAttemptGuard()
    var lastStopReason: String? = null
    var journal: Journal? = null
        private set
    var journalFailure: String? = null
        private set

    /** Explicit UI/capture trigger only. No alarm, worker, receiver or retry loop calls this on watch. */
    fun startWatchActivitySync(trigger: String): Boolean {
        if (BuildConfig.DEVICE_ROLE != "watch") return false
        // A dead transfer must not block every later retry with "已有工作正在进行".
        reconcileStaleSyncState()
        if (activeWatchActivitySync != null || activeSyncOwner != null) return false
        pendingActivitySyncExit.set(false)
        val normalized = ActivitySyncPolicy.normalize(trigger)
        activitySyncAttemptGuard.markStarted(normalized)
        WatchActivitySyncSession(this, normalized).also { activeWatchActivitySync = it; it.start() }
        return activeWatchActivitySync != null && activitySyncStatus.running
    }

    fun requestStopBackgroundSync(reason: String) {
        val source = reason.take(96)
        try { journal?.event("SYNC_STOP_REQUESTED", mapOf("source" to source,
            "activityTrigger" to activitySyncStatus.trigger,
            "activityRunning" to activitySyncStatus.running,
            "activeOwner" to activeSyncOwner.orEmpty())) } catch (_: Exception) {}
        stopBackgroundSync?.invoke(source)
    }

    /**
     * Clear a sync owner/status that no longer has a live session or bounded lease.
     * A process can survive a missed callback; the home must never stay blocked by a
     * transfer that is already gone. Called on every home start and before capture.
     */
    fun reconcileStaleSyncState() {
        val session = activeWatchActivitySync
        val leaseActive = try { SyncGuardService.hasActiveLease() } catch (_: Exception) { false }
        if (activeSyncOwner != null && session == null && !leaseActive) {
            try { journal?.event("ACTIVITY_SYNC_STALE_OWNER_CLEARED",
                mapOf("owner" to activeSyncOwner.orEmpty(),
                    "running" to activitySyncStatus.running,
                    "trigger" to activitySyncStatus.trigger)) } catch (_: Exception) {}
            activeSyncOwner = null
            stopBackgroundSync = null
        }
        if (activitySyncStatus.running && session == null && !leaseActive) {
            try { journal?.event("ACTIVITY_SYNC_STALE_STATUS_CLEARED",
                mapOf("trigger" to activitySyncStatus.trigger,
                    "text" to activitySyncStatus.text.take(200))) } catch (_: Exception) {}
            activitySyncStatus = ActivitySyncStatus(false, false,
                "上次同步已结束；数据仍保留，可重新同步。", activitySyncStatus.trigger)
        }
    }

    private val releaseHandler = Handler(Looper.getMainLooper())
    private var releaseWakeLock: PowerManager.WakeLock? = null

    /**
     * Keep the vendor connector alive for [holdMs] after capture stops the sensor, then
     * release it. This runs outside the capture service so the home is immediately usable
     * and a new activity can start; the 60-second LED hold no longer blocks the UI.
     */
    fun scheduleSensorRelease(holdMs: Long, wakeLock: PowerManager.WakeLock?, release: () -> Unit) {
        if (holdMs <= 0L) {
            try { release() } catch (_: Exception) {}
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            return
        }
        val held = wakeLock ?: try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawWave:SensorRelease")
                .apply { setReferenceCounted(false); acquire(holdMs + 15_000L) }
        } catch (_: Exception) { null }
        releaseWakeLock = held
        releaseHandler.postDelayed({
            try { release() } catch (_: Exception) {}
            try { if (held != null && held.isHeld) held.release() } catch (_: Exception) {}
            if (releaseWakeLock === held) releaseWakeLock = null
        }, holdMs)
    }

    override fun onCreate() {
        super.onCreate()
        com.rawwave.designsystem.RwTheme.forceDark = BuildConfig.DEVICE_ROLE == "watch"
        try {
            journal = Journal(File(filesDir, "diagnostics"), BuildConfig.DEVICE_ROLE)
            journal?.event("BUILD", mapOf("version" to BuildConfig.VERSION_NAME,
                "run" to BuildConfig.BUILD_RUN, "pid" to Process.myPid(), "stage" to "ACTIVITY_RAW_SYNC"))
            journal?.event("MODULE_CONTRACTS", mapOf("buildRun" to BuildConfig.BUILD_RUN,
                "contracts" to com.rawwave.core.ModuleVersions.contracts(),
                "implementationIdentity" to "buildRun plus source hashes; sensor adapter logs its own version"))
        } catch (failure: Exception) {
            journalFailure = "诊断存储初始化失败：${failure.javaClass.simpleName}: ${failure.message}"
        }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { journal?.fault("UNCAUGHT_CRASH", error); journal?.mark("CRASH_OBSERVED") }
            catch (_: Exception) { /* The previous crash handler must still run. */ }
            if (original != null) original.uncaughtException(thread, error)
            else Process.killProcess(Process.myPid())
        }
    }
}

