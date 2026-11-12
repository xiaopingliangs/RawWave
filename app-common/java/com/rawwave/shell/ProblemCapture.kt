package com.rawwave.shell

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.util.UUID

/**
 * One shared "record what I just saw" action for phone and watch.
 *
 * The record is deliberately an observation in the existing diagnostic journal: it is included
 * in the next problem report, and it never touches activity raw data. Wording and keys are shared
 * so a report can be compared across both devices.
 */
object ProblemCapture {

    private val symptomMap = linkedMapOf(
        "LED_PERSISTENT" to "红绿灯持续亮",
        "WATCH_NOT_EXITED" to "软件没有按规定时间退出后台",
        "DATA_NOT_ARRIVED" to "数据无法同步到手机",
        "APP_CRASH_OR_FREEZE" to "应用卡死、崩溃",
        "OTHER_OR_UNKNOWN" to "其他",
    )

    val keys: List<String> = symptomMap.keys.toList()
    val labels: Array<String> = symptomMap.values.toTypedArray()

    data class Recorded(val captureId: String, val selected: List<String>)

    /**
     * Writes the observation now, so a later crash cannot erase what the user reported.
     * Throws when the journal is unavailable; the caller must not claim it was saved.
     */
    fun record(app: ProbeApplication, selected: List<String>): Recorded {
        val journal = app.journal
            ?: throw IllegalStateException(app.journalFailure ?: "诊断存储未初始化，无法记录问题现场。")
        val captureId = UUID.randomUUID().toString()
        val led = if ("LED_PERSISTENT" in selected) "ON" else "NOT_OBSERVED"
        val watchExit = if ("WATCH_NOT_EXITED" in selected) "NOT_EXITED" else "NOT_OBSERVED"
        val dataArrival = if ("DATA_NOT_ARRIVED" in selected) "NOT_ARRIVED" else "NOT_OBSERVED"
        val crash = if ("APP_CRASH_OR_FREEZE" in selected) "REPORTED" else "NOT_OBSERVED"
        val details = linkedMapOf<String, Any>(
            "diagnosticCaptureId" to captureId,
            "symptoms" to selected,
            "led" to led,
            "watchExit" to watchExit,
            "dataArrival" to dataArrival,
            "appCrashOrFreeze" to crash,
            "rebootState" to "NOT_RECORDED",
            "role" to BuildConfig.DEVICE_ROLE,
            "versionCode" to BuildConfig.VERSION_CODE,
            "versionName" to BuildConfig.VERSION_NAME,
            "buildRun" to BuildConfig.BUILD_RUN,
            "utcMs" to System.currentTimeMillis(),
            "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
            "uptimeMs" to SystemClock.uptimeMillis(),
            "activeSyncOwner" to app.activeSyncOwner.orEmpty(),
            "activitySyncRunning" to app.activitySyncStatus.running,
            "activitySyncTrigger" to app.activitySyncStatus.trigger,
            "captureServiceRunning" to captureServiceRunning(app),
            "lastStopReason" to app.lastStopReason.orEmpty(),
            "processState" to (try { journal.previousState() } catch (_: Exception) { "UNKNOWN" }),
        )
        journal.observation("DIAGNOSTIC_CAPTURE", details)
        journal.event(
            "DIAGNOSTIC_CAPTURE_RECORDED",
            mapOf(
                "diagnosticCaptureId" to captureId,
                "symptoms" to selected,
                "led" to led,
                "watchExit" to watchExit,
                "dataArrival" to dataArrival,
                "appCrashOrFreeze" to crash,
            ),
        )
        return Recorded(captureId, selected)
    }

    fun captureServiceRunning(context: Context): Boolean = try {
        context.getSystemService(ActivityManager::class.java)
            ?.getRunningServices(64)
            ?.any { it.service.className == "com.rawwave.shell.ActivityRecordingService" }
            ?: false
    } catch (_: Exception) {
        false
    }
}
