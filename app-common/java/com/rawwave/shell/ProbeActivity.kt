package com.rawwave.shell

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import com.rawwave.core.ActivityDataExporter
import com.rawwave.core.DiagnosticReportBuilder
import com.rawwave.core.ReportExporter
import com.rawwave.core.RunDeadlines
import com.rawwave.core.WorkBudget
import com.rawwave.core.SyncStopScope
import java.io.File
import java.util.concurrent.Executors

/** Diagnostic transfer shell. No sensors or automatic retry; sync has one bounded service lease. */
class ProbeActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private val app get() = application as ProbeApplication
    private lateinit var status: TextView
    private lateinit var powerNote: TextView
    private var working = false
    private var exiting = false
    private var operation = 0L
    private var transfer: TransferWindow? = null
    private var guard: SyncGuardService.Lease? = null
    private var background = false
    private var exportChoiceDialog: android.app.AlertDialog? = null
    private val ownerToken = java.util.UUID.randomUUID().toString()
    private val diagnosticPreempt: () -> Unit = { preemptDiagnosticForActivity() }
    private val syncObserver: (String) -> Unit = { reason ->
        if (!exiting && !working) exitNow("BACKGROUND_SYNC_$reason", force = true)
    }
    private val activitySyncObserver: (ProbeApplication.ActivitySyncStatus) -> Unit = { sync ->
        if (!exiting && ::status.isInitialized) status.text = sync.text
        if (sync.running) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!exiting && transfer == null) {
            if (sync.running) holdIdleForActivitySync() else releaseIdleForActivitySync()
        }
    }
    private val activityIdleHold: () -> Boolean = { holdIdleForActivitySync() }
    private val activityIdleRelease: () -> Boolean = { releaseIdleForActivitySync() }
    private var runtime = DeadlineRuntime { reason, evidence -> onIdleDeadline(reason, evidence) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.syncFinished = syncObserver
        app.activitySyncObserver = activitySyncObserver
        app.activityIdleHold = activityIdleHold
        app.activityIdleRelease = activityIdleRelease
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exitNow("USER_BACK") }
        })
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val watch = BuildConfig.DEVICE_ROLE == "watch"
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val inset = if (watch) (resources.displayMetrics.widthPixels * .17f).toInt() else dp(24)
            setPadding(inset, dp(if (watch) 64 else 32), inset, dp(if (watch) 72 else 32))
        }
        fun label(text: String, size: Float): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
        }.also { body.addView(it) }
        label("原波 · RawWave", if (watch) 20f else 28f)
        label((if (watch) "问题报告工具" else "手机数据中心") + "\n${BuildConfig.VERSION_NAME}\n${BuildConfig.BUILD_RUN}", 13f)
        label(if (watch) "问题报告与活动数据分开传输。此页面不传活动原始数据。" else "手表会自动唤醒手机接收活动数据。\n同步完成立即停止；失败后手表会自动重试一次。\n活动原始数据与问题报告分开保存。", 15f)
        status = label(initialStatus(), 15f)
        powerNote = label("", 13f)
        fun button(title: String, action: () -> Unit) {
            body.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 15f; minHeight = dp(52)
                setOnClickListener { if (!exiting && runtime.check("BUTTON")) action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (!watch) button("导出活动原始数据") { exportActivityData() }
        if (!watch) button("导出最近3次活动（一个压缩包）") { exportActivityData(recent = true) }
        button("诊断详情（离线可拍照）") { DiagnosticViewer.show(this, app) }
        button("记录问题现场") { recordDiagnosticCapture() }
        button("导出问题报告") { exportReport() }
        button(if (watch) "发送报告到手机" else "仅接收问题报告（不接收活动数据）") { startTransfer() }
        button("后台同步设置") {
            if (!working && app.activeSyncOwner == null) {
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (error: Exception) { status.text = "请在系统应用设置中查看原波的电池/后台限制。当前设备没有提供此设置入口。" }
            }
        }
        button("退出（停止所有工作）") { exitNow("USER_EXIT") }
        scroll.addView(body)
        setContentView(scroll)
        record("ACTIVITY_CREATED", mapOf("screenRound" to resources.configuration.isScreenRound))
        try { app.journal?.mark("RUNNING") } catch (error: Exception) { showError(error) }
        scheduleIdle()
    }

    private fun recordDiagnosticCapture() {
        if (working || exiting) return
        val symptoms = linkedMapOf(
            "LED_PERSISTENT" to "红/绿灯持续亮",
            "WATCH_NOT_EXITED" to "手表没有退出/没有回表盘",
            "DATA_NOT_ARRIVED" to "活动数据没有到手机",
            "APP_CRASH_OR_FREEZE" to "应用崩溃/卡死/被系统停止",
            "OTHER_OR_UNKNOWN" to "其他/不确定"
        )
        val keys = symptoms.keys.toList()
        val labels = symptoms.values.toTypedArray()
        val checked = BooleanArray(labels.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("记录问题现场（可多选）")
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("保存") { _, _ ->
                val selected = keys.filterIndexed { index, _ -> checked[index] }
                saveDiagnosticCapture(selected)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveDiagnosticCapture(selected: List<String>) {
        val journal = app.journal
        if (journal == null) { status.text = app.journalFailure ?: "诊断存储未初始化，无法记录问题现场。"; return }
        val captureId = java.util.UUID.randomUUID().toString()
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
            "lastStopReason" to app.lastStopReason.orEmpty(),
            "processState" to (try { journal.previousState() } catch (_: Exception) { "UNKNOWN" })
        )
        try {
            journal.observation("DIAGNOSTIC_CAPTURE", details)
            journal.event("DIAGNOSTIC_CAPTURE_RECORDED", mapOf(
                "diagnosticCaptureId" to captureId,
                "symptoms" to selected,
                "led" to led,
                "watchExit" to watchExit,
                "dataArrival" to dataArrival,
                "appCrashOrFreeze" to crash))
            status.text = "已记录问题现场。\nID ${captureId.take(8)}\n症状：${selected.joinToString("、").ifEmpty { "未选择" }}\n" +
                "导出问题报告会包含此记录；系统层证据需按“诊断详情”里的说明另抓 dump。"
        } catch (failure: Exception) {
            showError(failure)
            status.append("\n问题现场没有保存成功；请拍照本页并保留。")
        }
    }

    private fun initialStatus(): String {
        app.journalFailure?.let { return it + "\n请拍照保存本页。" }
        app.lastStopReason?.let { return "上次已请求停止工作（$it）。本次重新打开，不恢复旧同步；详情可导出问题报告。" }
        return when (val previous = app.journal?.previousState()) {
            "NO_PREVIOUS_RECORD" -> "首次运行。问题报告保存到 Downloads/RawWave。"
            "CLEANUP_REQUESTED" -> "上次已请求停止工作；系统资源是否完全释放需另行核验。详情见问题报告。"
            "PROCESS_TERMINATION_REQUESTED" -> "上次已请求结束进程。是否真正退出需真机外部核验，应用不自行宣称通过。"
            "CRASH_OBSERVED" -> "上次捕获到崩溃，详细错误保存在问题报告。"
            else -> "上次运行中断或退出证据不完整（$previous）。具体原因未知，请导出问题报告。"
        }
    }

    private fun startTransfer() {
        if (working || exiting || transfer != null) return
        if (app.activeSyncOwner != null) { status.text = "正在接收手表，请等待本轮完成。"; return }
        app.activeSyncOwner = ownerToken
        if (!runtime.begin(RunDeadlines.Kind.TRANSFER, WorkBudget.SYNC_LEASE_MS)) { app.activeSyncOwner = null; return }
        working = true
        if (BuildConfig.DEVICE_ROLE == "phone") app.preemptDiagnosticReceive = diagnosticPreempt
        record("DEADLINE_ARMED", runtime.evidence())
        status.text = "正在准备传输。本轮截止时间固定，不会无限重试；系统暂停时恢复后先检查超时。"
        transfer = TransferWindow(applicationContext,app,
            { text -> main.post { if (!exiting && runtime.check("TRANSFER_UPDATE")) status.text = text } },
            { success, text -> main.post {
                if (!exiting && runtime.check("TRANSFER_FINISHED")) {
                    working = false
                    SyncGuardService.close(guard); guard = null
                    if (app.preemptDiagnosticReceive === diagnosticPreempt) app.preemptDiagnosticReceive = null
                    if (app.activeSyncOwner == ownerToken) { app.activeSyncOwner = null; app.receiveHello = null }
                    status.text = text + if (success) "\n已确认保存，正在退出。" else "\n本轮截止不变；恢复运行若已超时先退出，下次打开可重试。"
                    exitNow(if (success) "TRANSFER_SUCCESS" else "TRANSFER_FAILED", force = true)
                    // The original deadline is never renewed by failures, UI updates or export.
                }
            } })
        if (BuildConfig.DEVICE_ROLE == "phone") app.receiveHello = { node, bytes -> transfer?.offerHello(node, bytes) }
        try {
            guard = SyncGuardService.open(applicationContext,
                cancel = { transfer?.cancel() },
                ready = { if (!exiting) transfer?.start() },
                ended = { reason -> if (!exiting) exitNow(reason, force = true) })
        } catch (error: Exception) {
            transfer?.cancel(); app.activeSyncOwner = null; app.receiveHello = null; working = false
            if (app.preemptDiagnosticReceive === diagnosticPreempt) app.preemptDiagnosticReceive = null
            record("SYNC_GUARD_UNAVAILABLE", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
            showError(error); exitNow("SYNC_GUARD_UNAVAILABLE", force = true)
        }
    }

    private fun preemptDiagnosticForActivity() {
        if (exiting || app.activeSyncOwner != ownerToken || transfer == null) return
        record("DIAGNOSTIC_RECEIVE_PREEMPTED_BY_ACTIVITY", runtime.evidence())
        val previous = transfer; transfer = null; previous?.cancel()
        SyncGuardService.close(guard); guard = null
        app.receiveHello = null
        if (app.preemptDiagnosticReceive === diagnosticPreempt) app.preemptDiagnosticReceive = null
        if (app.activeSyncOwner == ownerToken) app.activeSyncOwner = null
        working = false
        runtime.finishTransfer()
        status.text = "活动数据已经到达，已停止问题报告等待，改为自动接收活动原始数据。"
        scheduleIdle()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (exiting || !runtime.check("TOUCH")) return true
        if (event.action == MotionEvent.ACTION_DOWN) scheduleIdle()
        return super.dispatchTouchEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (exiting || !runtime.check("KEY")) return true
        scheduleIdle()
        return super.onKeyDown(keyCode, event)
    }
    private fun scheduleIdle() {
        if (!working && !exiting) runtime.touchIdle()
    }
    private fun newIdleRuntime() = DeadlineRuntime { reason, evidence -> onIdleDeadline(reason, evidence) }
    private fun onIdleDeadline(reason: String, evidence: Map<String, Any>) {
        // A watch-initiated activity transfer owns its own lease; the page idle countdown must
        // never cancel that receive just because the screen was untouched.
        if (app.activitySyncStatus.running && transfer == null) {
            record("IDLE_DEADLINE_SUPPRESSED_DURING_ACTIVITY_SYNC", evidence + mapOf(
                "reason" to reason, "syncTrigger" to app.activitySyncStatus.trigger,
                "syncRunning" to true, "stoppedSync" to false))
            return
        }
        exitNow(reason, force = true, deadlineEvidence = evidence)
    }
    private fun holdIdleForActivitySync(): Boolean {
        if (exiting || transfer != null || working) return false
        if (runtime.isClosed()) {
            // The page deadline may already have fired while a previous sync suppressed
            // the exit, leaving this Activity alive with a closed runtime. A genuine
            // watch-initiated transfer is exactly the case that must be able to re-arm
            // one bounded idle window; otherwise the phone rejects every wake until the
            // user manually reopens the app.
            if (exiting || working || transfer != null) return false
            runtime = newIdleRuntime().also { it.touchIdle() }
            record("IDLE_REARMED_FOR_ACTIVITY_SYNC", runtime.evidence() + mapOf("previousState" to "CLOSED"))
        }
        if (runtime.isIdleHeld()) return true
        var held = runtime.holdIdle()
        if (!held) {
            // A backgrounded page parks its idle timer. The absolute deadline can expire
            // while parked, and RunDeadlines correctly refuses to hold an expired idle
            // window. A genuine watch transfer may still start one fresh bounded window;
            // no operation, export or transfer is active here.
            if (exiting || working || transfer != null) return false
            runtime = newIdleRuntime().also { it.touchIdle() }
            record("IDLE_REARMED_FOR_ACTIVITY_SYNC",
                runtime.evidence() + mapOf("previousState" to "EXPIRED_OR_UNHOLDABLE"))
            held = runtime.holdIdle()
        }
        record(if (held) "IDLE_HELD_FOR_ACTIVITY_SYNC" else "IDLE_HOLD_FAILED_FOR_ACTIVITY_SYNC",
            runtime.evidence() + mapOf("trigger" to app.activitySyncStatus.trigger))
        return held
    }
    private fun releaseIdleForActivitySync(): Boolean {
        if (exiting || transfer != null || working) return false
        if (app.activitySyncStatus.running) return false
        if (runtime.isClosed()) {
            runtime = newIdleRuntime().also { it.touchIdle() }
            record("IDLE_REARMED_AFTER_ACTIVITY_SYNC", runtime.evidence())
            return true
        }
        if (runtime.releaseIdle()) {
            record("IDLE_RELEASED_AFTER_ACTIVITY_SYNC", runtime.evidence())
            return true
        }
        runtime.touchIdle()
        return true
    }

    private fun exportActivityData(selected: List<String>? = null, recent: Boolean = false) {
        if (working || exiting) return
        if (app.activeSyncOwner != null) { status.text = "正在接收手表数据；完成后再导出。"; return }
        app.activeSyncOwner = ownerToken; working = true; val owner = ++operation
        status.text = if(recent) "正在按开始时间导出最近3次活动…" else if (selected == null) "正在读取活动列表，请稍候…" else "正在校验并导出所选 ${selected.size} 个数据包…"
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        worker.execute {
            try {
                val exporter = ActivityDataExporter(File(filesDir, "activity-inbox"), DownloadsExportDestination(contentResolver)) { folder ->
                    val fd=android.system.Os.open(folder.absolutePath,android.system.OsConstants.O_RDONLY,0)
                    try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
                }
                if (selected == null && !recent) {
                    val choices = exporter.choices()
                    val rejected = exporter.rejectedFiles()
                    if (rejected.isNotEmpty()) app.journal?.event("ACTIVITY_EXPORT_REJECTED", mapOf("files" to rejected))
                    main.post { if (owner == operation && !exiting) {
                        status.text = if (rejected.isEmpty()) "请选择要导出的活动。" else "${rejected.size} 个异常包已隔离保留；可选择其余活动。"
                        if (choices.isNotEmpty()) {
                            val checked = BooleanArray(choices.size) { it == 0 }
                            val labels = choices.map { choice ->
                                choice.displayName().removePrefix("RawWave-activities-").removeSuffix("-${choice.file()}")
                                    .replace('_', ' ') + "\n${choice.sessions()} 次活动 · ${choice.file().take(8)}"
                            }.toTypedArray()
                            val dialog = android.app.AlertDialog.Builder(this)
                                .setTitle("选择导出（最新在前）")
                                .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value; scheduleIdle() }
                                .setPositiveButton("导出所选") { _, _ ->
                                    val files = choices.filterIndexed { index, _ -> checked[index] }.map { it.file() }
                                    if (files.isEmpty()) status.text = "未选择活动，没有导出。" else exportActivityData(files)
                                }
                                .setNegativeButton("取消", null).create()
                            exportChoiceDialog = dialog
                            dialog.setOnDismissListener { exportChoiceDialog = null; scheduleIdle() }
                            dialog.show()
                        } else status.text = "没有可导出的有效数据包。"
                    } }
                    return@execute
                }
                val results = if(recent) exporter.exportRecent(3) else exporter.exportSelected(selected)
                val rejected = exporter.rejectedFiles()
                if (rejected.isNotEmpty()) app.journal?.event("ACTIVITY_EXPORT_REJECTED", mapOf("files" to rejected,"count" to rejected.size))
                app.journal?.event("ACTIVITY_EXPORT_VERIFIED", mapOf("packages" to results.size,
                    "sessions" to results.sumOf { it.sessions().size }, "bytes" to results.sumOf { it.bytes() }))
                main.post { if (owner == operation && !exiting) {
                    status.text = (if (results.isEmpty()) "没有可导出的有效数据包。" else "导出完成并已回读校验：\nDownloads/RawWave\n共 ${results.sumOf { it.sessions().size }} 次活动，${results.size} 个原始数据包。") +
                        if (rejected.isNotEmpty()) "\n${rejected.size} 个异常包已隔离保留，请导出问题报告；它们未算作成功导出。" else ""
                } }
            } catch (empty: ActivityDataExporter.NoDataException) {
                try { app.journal?.event("ACTIVITY_EXPORT_EMPTY", mapOf("message" to empty.message.orEmpty())) } catch (_: Exception) {}
                main.post { if (owner == operation && !exiting) status.text = "手机还没有收到活动原始数据。\n请先完成一次活动；活动结束后手表会自动发送，不需要在手机点接收。" }
            } catch (failure: Exception) {
                try { app.journal?.fault("ACTIVITY_EXPORT_FAILED", failure) } catch (_: Exception) {}
                main.post { if (owner == operation && !exiting) showError(failure) }
            } finally {
                main.post { if (owner == operation && !exiting) {
                    working = false; if (app.activeSyncOwner == ownerToken) app.activeSyncOwner = null
                    scheduleIdle(); if (background) parkIdle()
                } }
            }
        }
    }

    private fun exportReport() {
        if (working || exiting) return
        if (app.activeSyncOwner != null) { status.text = "正在同步；完成后再导出报告。"; return }
        val journal = app.journal ?: run { status.text = initialStatus(); return }
        if (!runtime.begin(RunDeadlines.Kind.EXPORT, 58_000)) return
        app.activeSyncOwner = ownerToken
        working = true
        val owner = ++operation
        record("DEADLINE_ARMED", runtime.evidence())
        status.text = "正在保存问题报告…本次诊断导出最多 60 秒；不是健康历史导出的时长限制。"
        val exporter = ReportExporter(
            DiagnosticReportBuilder(journal, File(filesDir, "received"), File(cacheDir, "reports"),
                BuildConfig.DEVICE_ROLE, mapOf(
                    "appId" to packageName, "versionCode" to BuildConfig.VERSION_CODE,
                    "versionName" to BuildConfig.VERSION_NAME, "buildRun" to BuildConfig.BUILD_RUN,
                    "debuggable" to BuildConfig.DEBUG, "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL, "sdkInt" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
                    "release" to Build.VERSION.RELEASE, "display" to Build.DISPLAY), File(filesDir, "diagnostic-summary/latest.json")),
            DownloadsExportDestination(contentResolver), journal)
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        worker.execute {
            try {
                val report = exporter.export(owner)
                main.post {
                    if (owner == operation && !exiting && runtime.check("EXPORT_RESULT")) status.text = "已保存并回读校验：\nDownloads/RawWave/${report.file().name}\n含本机日志及已收到且校验通过的最近 ${report.includedWatchReports()} 份手表包。旧附件不代表本轮成功。前台空闲80秒收尾；空闲后台不保活。"
                }
            } catch (failure: Exception) {
                main.post { if (owner == operation && !exiting && runtime.check("EXPORT_FAILURE")) {
                    showError(failure)
                    if (failure is ReportExporter.Failure && failure.published)
                        status.append("\n注意：已校验的导出文件已发布并保留；失败发生在后续诊断记录。")
                } }
            } finally {
                main.post { if (owner == operation && !exiting) {
                    working = false; runtime.finishExport()
                    if (app.activeSyncOwner == ownerToken) app.activeSyncOwner = null
                    if (background) parkIdle()
                } }
            }
        }
    }

    private fun showError(failure: Exception) {
        status.text = "操作失败：${failure.javaClass.simpleName}\n${failure.message}\n未报告成功。请拍照保存此页；本机日志仍在。"
    }
    private fun record(event: String, values: Map<String, Any>) {
        try { app.journal?.event(event, values) } catch (failure: Exception) { if (::status.isInitialized) showError(failure) }
    }
    private fun exitNow(reason: String, force: Boolean = false, deadlineEvidence: Map<String, Any> = emptyMap()) {
        if (exiting) return
        if (working && !force && transfer == null) { status.text = "数据正在安全写出，请等待完成；不会把未校验文件报告为成功。"; return }
        exiting = true; ++operation
        app.lastStopReason = reason
        runtime.close()
        if (app.syncFinished === syncObserver) app.syncFinished = null
        if (app.activitySyncObserver === activitySyncObserver) app.activitySyncObserver = null
        if (app.activityIdleHold === activityIdleHold) app.activityIdleHold = null
        if (app.activityIdleRelease === activityIdleRelease) app.activityIdleRelease = null
        val stopBackground = SyncStopScope.mayStop(
            reason == "USER_EXIT" || reason == "USER_BACK", ownerToken, app.activeSyncOwner)
        record("SYNC_EXIT_SCOPE", mapOf("reason" to reason, "pageOwner" to ownerToken,
            "activeOwner" to app.activeSyncOwner.orEmpty(), "stopBackground" to stopBackground))
        if (stopBackground) app.requestStopBackgroundSync("PROBE_EXIT:$reason")
        SyncGuardService.close(guard); guard = null
        if (app.preemptDiagnosticReceive === diagnosticPreempt) app.preemptDiagnosticReceive = null
        if (app.activeSyncOwner == ownerToken) { app.activeSyncOwner = null; app.receiveHello = null }
        if (deadlineEvidence.isNotEmpty()) record("DEADLINE_EXPIRED", deadlineEvidence)
        transfer?.cancel()
        record("EXIT_REQUESTED", mapOf("reason" to reason))
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        record("CLEANUP_RESULT", mapOf("workerStopRequested" to true, "workerTerminatedAtCheck" to worker.isTerminated,
            "systemResourcesVerified" to false, "forcedProcessKill" to false, "cachedProcessAllowed" to true))
        try { app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        exportChoiceDialog?.setOnDismissListener(null)
        exportChoiceDialog?.dismiss(); exportChoiceDialog = null
        if (isFinishing && !exiting) exitNow("ACTIVITY_FINISHED", force = true)
        ++operation
        runtime.close()
        SyncGuardService.close(guard); guard = null
        if (app.preemptDiagnosticReceive === diagnosticPreempt) app.preemptDiagnosticReceive = null
        if (app.activeSyncOwner == ownerToken) { app.activeSyncOwner = null; app.receiveHello = null }
        if (app.syncFinished === syncObserver) app.syncFinished = null
        if (app.activitySyncObserver === activitySyncObserver) app.activitySyncObserver = null
        if (app.activityIdleHold === activityIdleHold) app.activityIdleHold = null
        if (app.activityIdleRelease === activityIdleRelease) app.activityIdleRelease = null
        transfer?.cancel()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onStop() {
        super.onStop()
        background = true
        if (!exiting && !working && transfer == null) parkIdle()
        if (!exiting && runtime.check("BACKGROUND")) record("ACTIVITY_BACKGROUND",
            runtime.evidence() + mapOf("working" to working,"deadlineRenewed" to false))
    }
    override fun onResume() {
        super.onResume()
        background = false
        if (::powerNote.isInitialized) {
            val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            powerNote.text = if (exempt) "系统电池优化例外：已允许。仍只在本轮同步期间保活。"
                else "系统仍有限制：后台接收或息屏计时可能受影响。可在“后台同步设置”中允许原波；空闲不会保活。"
        }
        // Background time counts toward the same absolute 80-second idle deadline.
        // Returning is not itself a user interaction and cannot renew that deadline.
        if (!exiting && transfer == null && !working) {
            if (app.activitySyncStatus.running) holdIdleForActivitySync()
            else if (runtime.isClosed()) releaseIdleForActivitySync()
        }
        if (!exiting && runtime.resumeIdle()) record("IDLE_PAGE_RETURNED_WITHIN_EXISTING_DEADLINE", runtime.evidence())
        if (!exiting && runtime.check("RESUME")) record("ACTIVITY_RESUMED", runtime.evidence())
    }
    private fun parkIdle() {
        if (runtime.parkIdle()) {
            worker.shutdownNow()
            record("IDLE_BACKGROUND_QUIESCENT", runtime.evidence() + mapOf(
                "workerStopRequested" to true, "scheduledCallbacks" to 0,
                "syncLease" to false, "cachedPageMayRemain" to true))
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}








