package com.rawwave.shell

import android.app.Application
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
    @Volatile var activitySyncStatus = ActivitySyncStatus()
    var activitySyncObserver: ((ActivitySyncStatus) -> Unit)? = null
    var activeWatchActivitySync: WatchActivitySyncSession? = null
    val activitySyncAttemptGuard = ActivitySyncAttemptGuard()
    var lastStopReason: String? = null
    var journal: Journal? = null
        private set
    var journalFailure: String? = null
        private set

    /** Explicit UI/capture trigger only. No alarm, worker, receiver or retry loop calls this on watch. */
    fun startWatchActivitySync(trigger: String): Boolean {
        if (BuildConfig.DEVICE_ROLE != "watch" || activeWatchActivitySync != null || activeSyncOwner != null) return false
        val normalized = ActivitySyncPolicy.normalize(trigger)
        activitySyncAttemptGuard.markStarted(normalized)
        WatchActivitySyncSession(this, normalized).also { activeWatchActivitySync = it; it.start() }
        return true
    }

    fun requestStopBackgroundSync(reason: String) {
        val source = reason.take(96)
        try { journal?.event("SYNC_STOP_REQUESTED", mapOf("source" to source,
            "activityTrigger" to activitySyncStatus.trigger,
            "activityRunning" to activitySyncStatus.running,
            "activeOwner" to activeSyncOwner.orEmpty())) } catch (_: Exception) {}
        stopBackgroundSync?.invoke(source)
    }

    override fun onCreate() {
        super.onCreate()
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

