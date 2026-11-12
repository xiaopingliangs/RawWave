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
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwGradientTitle
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwScreen
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwTheme
import java.io.File
import java.util.concurrent.Executors

/** Diagnostic transfer shell. No sensors or automatic retry; sync has one bounded service lease. */
class ProbeActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private var reportWorker = Executors.newSingleThreadExecutor()
    private val app get() = application as ProbeApplication
    private lateinit var status: TextView
    private lateinit var powerNote: TextView
    private lateinit var receivedSummary: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var reportStatus: TextView
    private var summaryOperation = 0L
    private var summaryLoading = false
    private var working = false
    private var exiting = false
    private var operation = 0L
    private var transfer: TransferWindow? = null
    private var guard: SyncGuardService.Lease? = null
    private var background = false
    private var sawRunningActivitySync = false
    private var returningToCaller = false
    private val returnOnDone: Boolean by lazy { intent.getBooleanExtra("returnOnDone", false) }
    private val autoExitSettings by lazy { PhoneExitSettings(this) }
    private val autoExit = Runnable {
        if (exiting || working || transfer != null || app.activitySyncStatus.running || background) {
            return@Runnable
        }
        val minutes = autoExitSettings.autoExitMinutes
        if (minutes <= 0) return@Runnable
        record("PHONE_IDLE_AUTO_EXIT_FIRED", mapOf("minutes" to minutes))
        exitNow("PHONE_IDLE_AUTO_EXIT", force = true)
    }
    /** Re-read the persisted last-contact time so "刚刚" does not stay frozen on screen. */
    private val connectionTick = object : Runnable {
        override fun run() {
            if (exiting) return
            updateConnectionStatus()
            main.postDelayed(this, 30_000L)
        }
    }
    private val ownerToken = java.util.UUID.randomUUID().toString()
    private val diagnosticPreempt: () -> Unit = { preemptDiagnosticForActivity() }
    private val syncObserver: (String) -> Unit = { reason ->
        // The phone home is deliberately persistent: a watch-initiated diagnostic receive must
        // update the page, not close it. The watch console still exits after its bounded task.
        if (BuildConfig.DEVICE_ROLE == "watch" && !exiting && !working) exitNow("BACKGROUND_SYNC_$reason", force = true)
    }
    private val activitySyncObserver: (ProbeApplication.ActivitySyncStatus) -> Unit = { sync ->
        if (!exiting && ::status.isInitialized) {
            status.text = currentSyncText()
            updateConnectionStatus()
        }
        // The bounded lease owns a CPU wake lock; receiving must not depend on this page being lit.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!exiting && transfer == null) {
            if (sync.running) holdIdleForActivitySync() else releaseIdleForActivitySync()
        }
        if (!exiting) keepPhoneHomeVisible()
        if (sync.running) {
            sawRunningActivitySync = true
            armAutoExit("SYNC_RUNNING")
        } else if (sawRunningActivitySync) {
            sawRunningActivitySync = false
            refreshReceivedSummary()
            updateConnectionStatus()
            // The configurable inactivity window starts after the terminal result. A failed
            // sync keeps the page visible just like a successful one; the user can read it.
            armAutoExit("SYNC_FINISHED")
        }
    }
    private val activityIdleHold: () -> Boolean = { holdIdleForActivitySync() }
    private val activityIdleRelease: () -> Boolean = { releaseIdleForActivitySync() }
    private var runtime = DeadlineRuntime { reason, evidence -> onIdleDeadline(reason, evidence) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEVICE_ROLE == "phone") {
            // Keep the consumer page visible when the user presses power again. The keyguard
            // is not used to hide RawWave; the page has no sensitive history beyond the data
            // the user explicitly asked to export.
            setShowWhenLocked(true)
        }
        app.syncFinished = syncObserver
        app.activitySyncObserver = activitySyncObserver
        app.activityIdleHold = activityIdleHold
        app.activityIdleRelease = activityIdleRelease
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (returnOnDone) {
                    if (working || transfer != null) {
                        status.text = "正在处理，完成后即可返回；现在离开不会中断。"
                        return
                    }
                    returnToCaller()
                    return
                }
                // The phone home is a persistent receive screen. Back backgrounds it instead of
                // stopping a watch transfer; use 完全退出软件 for an explicit stop.
                if (BuildConfig.DEVICE_ROLE == "phone" && !working && transfer == null) {
                    moveTaskToBack(true)
                    return
                }
                exitNow("USER_BACK")
            }
        })
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val watch = BuildConfig.DEVICE_ROLE == "watch"
        if (watch) buildWatchConsole() else buildPhoneHome()
        record("ACTIVITY_CREATED", mapOf("screenRound" to resources.configuration.isScreenRound))
        try { app.journal?.mark("RUNNING") } catch (error: Exception) { showError(error) }
        if (watch) scheduleIdle() else {
            keepPhoneHomeVisible()
            armAutoExit("CREATE")
            main.postDelayed(connectionTick, 30_000L)
        }
        if (watch) {
            val autoAction = intent.getStringExtra("autoAction")
            if (!autoAction.isNullOrBlank()) main.post {
                if (exiting) return@post
                when (autoAction) {
                    "send" -> startTransfer()
                    "export" -> exportReport()
                }
            }
        }
    }

    /** Watch-side problem-report console; same green language, still separate from activity capture. */
    private fun buildWatchConsole() {
        val colors = RwTheme.colors(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val inset = (resources.displayMetrics.widthPixels * .15f).toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(colors.background); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(36), inset, dp(56))
        }
        body.addView(RwGradientTitle(this).apply {
            text = "RawWave"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        })
        body.addView(RwBodyText(this).apply {
            text = "问题报告工具\n版本 ${BuildConfig.VERSION_NAME}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })
        body.addView(RwBodyText(this).apply {
            text = "问题报告与活动数据分开传输。此页面不传活动原始数据。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setPadding(0, dp(2), 0, dp(4))
        })
        if (captureServiceRunning()) {
            body.addView(RwBodyText(this).apply {
                text = "当前仍有活动在采集：背面红/绿/蓝灯是传感器工作指示。\n请先回到首页结束活动；结束后灯会熄灭。"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(colors.onSurfaceVariant)
                setPadding(0, 0, 0, dp(4))
            })
        }
        status = RwBodyText(this).apply {
            text = initialStatus()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setPadding(0, dp(6), 0, dp(4))
        }
        body.addView(status)
        powerNote = RwBodyText(this).apply {
            text = ""
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        body.addView(powerNote)
        fun action(title: String, primary: Boolean = false, run: () -> Unit) {
            val view = if (primary) RwPrimaryButton(this) else RwSecondaryButton(this)
            view.text = title
            view.setOnClickListener { if (!exiting && runtime.check("BUTTON")) run() }
            body.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
        }
        action("发送报告到手机", primary = true) { startTransfer() }
        action("导出问题报告") { exportReport() }
        action("退出（停止所有工作）") { exitNow("USER_EXIT") }
        scroll.addView(body)
        setContentView(scroll)
    }

    /** Consumer phone home: receive status first, then the four export/entry actions. */
    private fun buildPhoneHome() {
        val colors = RwTheme.colors(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (RwTheme.isDark(this)) {
            window.decorView.systemUiVisibility and lightFlags.inv()
        } else {
            window.decorView.systemUiVisibility or lightFlags
        }
        val screen = RwScreen(this)
        val content = screen.content

        content.addView(
            RwGradientTitle(this).apply {
                text = "RawWave"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 36f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            phoneText("手表数据 · 手机端", 14f, colors.onSurfaceVariant),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            },
        )

        val dataCard = RwCard(this)
        dataCard.addView(
            phoneText("接收状态", 18f, colors.onSurface, medium = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        status = RwBodyText(this).apply {
            text = currentSyncText()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colors.onSurface)
            setPadding(0, dp(6), 0, 0)
        }
        dataCard.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        connectionStatus = RwBodyText(this).apply {
            text = watchConnectionText()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(4), 0, 0)
        }
        dataCard.addView(
            connectionStatus,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        powerNote = RwBodyText(this).apply {
            text = ""
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }
        dataCard.addView(
            powerNote,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        receivedSummary = RwBodyText(this).apply {
            text = "正在读取已接收活动…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, 0)
        }
        dataCard.addView(
            receivedSummary,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dataCard.addView(
            phoneText("同步由手表发起。", 12f, colors.onSurfaceVariant).apply {
                setPadding(0, dp(6), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dataCard.addView(
            RwSecondaryButton(this).apply {
                text = "刷新接收状态"
                setOnClickListener {
                    status.text = currentSyncText()
                    updatePowerNote()
                    refreshReceivedSummary()
                    updateConnectionStatus()
                }
            },
            phoneButtonParams(12, compact = true),
        )
        content.addView(dataCard, cardParams(16))

        // Only the live receive status keeps detail on the home. Everything else is a plain
        // action button; explanations live at the bottom of the second-level pages.
        content.addView(
            RwPrimaryButton(this).apply {
                text = "导出原始活动数据"
                setOnClickListener { openActivityExport() }
            },
            phoneButtonParams(18),
        )
        content.addView(
            RwSecondaryButton(this).apply {
                text = "导出三星健康官方数据"
                setOnClickListener { openOfficialExport() }
            },
            phoneButtonParams(10),
        )
        content.addView(
            RwSecondaryButton(this).apply {
                text = "导出问题报告"
                setOnClickListener { exportReport() }
            },
            phoneButtonParams(10),
        )
        reportStatus = RwBodyText(this).apply {
            text = ""
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(
            reportStatus,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            RwSecondaryButton(this).apply {
                text = "更多"
                setOnClickListener { openMore() }
            },
            phoneButtonParams(16),
        )
        content.addView(
            RwSecondaryButton(this).apply {
                text = "完全退出软件"
                setOnClickListener { exitNow("USER_EXIT") }
            },
            phoneButtonParams(10),
        )
        setContentView(screen)
        refreshReceivedSummary()
        updatePowerNote()
        updateConnectionStatus()
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun currentSyncText(): String {
        val sync = app.activitySyncStatus
        return when {
            sync.running -> "正在同步：${sync.text}"
            sync.text.isNotBlank() -> "上次同步：${sync.text}"
            // The phone has no watch-owned sync status in memory after a restart. The inbox
            // summary below is the durable truth; say we are checking instead of claiming
            // "waiting for the watch" while activities are already saved.
            BuildConfig.DEVICE_ROLE == "phone" -> "数据同步：正在核对已接收活动…"
            else -> "等待手表发送活动数据。"
        }
    }

    private fun phoneText(text: String, size: Float, color: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (medium && size >= 18f) RwTheme.displayTypeface() else RwTheme.boldTypeface()
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun phoneButtonParams(top: Int, compact: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun openActivityExport() {
        if (working || app.activeSyncOwner != null) {
            status.text = "正在处理其他任务，完成后打开原始活动导出。"
            return
        }
        try {
            startActivity(android.content.Intent().setClassName(
                this,
                "com.rawwave.mobile.activity.ActivityExportActivity",
            ))
        } catch (error: Exception) {
            status.text = "无法打开原始活动导出：${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun openOfficialExport() {
        if (working || app.activeSyncOwner != null) {
            status.text = "正在处理其他任务，完成后打开官方健康数据导出。"
            return
        }
        try {
            startActivity(android.content.Intent().setClassName(
                this,
                "com.rawwave.mobile.samsunghealth.SamsungHealthExportActivity",
            ))
        } catch (error: Exception) {
            status.text = "无法打开官方健康数据导出：${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun refreshReceivedSummary() {
        if (!::receivedSummary.isInitialized || working || exiting || summaryLoading) return
        val op = ++summaryOperation
        summaryLoading = true
        receivedSummary.text = "正在读取已接收活动…"
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        worker.execute {
            var dataState: String? = null
            val text = try {
                val exporter = ActivityDataExporter(
                    File(filesDir, "activity-inbox"),
                    DownloadsExportDestination(contentResolver),
                ) { folder ->
                    val fd = android.system.Os.open(folder.absolutePath, android.system.OsConstants.O_RDONLY, 0)
                    try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
                }
                val sessions = exporter.sessionChoices()
                if (sessions.isEmpty()) {
                    dataState = "数据同步：还没有收到活动数据。"
                    "还没有收到活动数据。\n在手表结束活动后，保持两台设备靠近即可自动同步。"
                } else {
                    val latest = sessions.maxByOrNull { it.createdUtcMs() }
                    val bytes = sessions.map { it.fileBytes() }.distinct().sum()
                    val time = latest?.let {
                        java.time.Instant.ofEpochMilli(it.createdUtcMs())
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    } ?: "时间未知"
                    val name = latest?.activityName()?.takeIf { it.isNotBlank() } ?: "未命名活动"
                    dataState = "数据同步：已收到 ${sessions.size} 次活动\n最近：$name · $time"
                    "已接收 ${sessions.size} 次活动\n最近：$name · $time\n原始包约 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
                }
            } catch (_: ActivityDataExporter.NoDataException) {
                dataState = "数据同步：还没有收到活动数据。"
                "还没有收到活动数据。\n在手表结束活动后，保持两台设备靠近即可自动同步。"
            } catch (failure: Exception) {
                "读取活动失败：${failure.javaClass.simpleName}: ${failure.message}"
            }
            main.post {
                if (op == summaryOperation) summaryLoading = false
                if (!exiting && op == summaryOperation && ::receivedSummary.isInitialized) {
                    receivedSummary.text = text
                    if (dataState != null && BuildConfig.DEVICE_ROLE == "phone" &&
                        !app.activitySyncStatus.running && ::status.isInitialized) {
                        status.text = dataState!!
                    }
                }
            }
        }
    }

    /** The home only hints at the background permission; the setting itself lives in 更多. */
    private fun updatePowerNote() {
        if (!::powerNote.isInitialized) return
        val exempt = try {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
        powerNote.text = if (exempt) "后台运行：已允许" else "后台运行：未允许（只影响熄屏接收）"
    }

    private fun watchConnectionText(): String {
        val last = WatchContact.lastUtcMs(this)
        if (last <= 0L) {
            return "手表连接：还没有收到手表消息。\n保持两台设备靠近；同步由手表发起。"
        }
        val elapsed = System.currentTimeMillis() - last
        val stamp = java.time.Instant.ofEpochMilli(last)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return when {
            elapsed < 0L -> "手表连接：刚刚有消息（$stamp）"
            elapsed < 2 * 60_000L -> "手表连接：刚刚有消息（$stamp）"
            elapsed < 60 * 60_000L -> "手表连接：上次消息 ${elapsed / 60_000L} 分钟前（$stamp）"
            else -> "手表连接：上次消息 $stamp。\n这只是最后消息时间，不代表手表此刻一定在线。"
        }
    }

    private fun updateConnectionStatus() {
        if (::connectionStatus.isInitialized) connectionStatus.text = watchConnectionText()
    }

    /**
     * Start the user's inactivity window. It is reset by any touch, by leaving/re-entering the
     * home, and after a transfer finishes; it never counts down while the page is backgrounded
     * or a sync/export is actively using the process.
     */
    private fun armAutoExit(origin: String) {
        main.removeCallbacks(autoExit)
        if (BuildConfig.DEVICE_ROLE != "phone" || exiting || background) return
        val minutes = autoExitSettings.autoExitMinutes
        if (minutes <= 0) return
        if (working || transfer != null || app.activitySyncStatus.running) return
        main.postDelayed(autoExit, minutes * 60_000L)
        record("PHONE_IDLE_AUTO_EXIT_ARMED", mapOf("minutes" to minutes, "origin" to origin))
    }

    private fun openMore() {
        try {
            startActivity(android.content.Intent(this, PhoneMoreActivity::class.java))
        } catch (error: Exception) {
            status.text = "无法打开“更多”：${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun captureServiceRunning(): Boolean = ProblemCapture.captureServiceRunning(this)

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
                    if (success) {
                        status.text = text + "\n已确认保存，正在退出。"
                        // The original deadline is never renewed by failures, UI updates or export.
                        exitNow("TRANSFER_SUCCESS", force = true)
                    } else {
                        // A failed send must keep its reason on screen. Exiting immediately made
                        // the message invisible and gave the user no chance to retry.
                        runtime.finishTransfer()
                        status.text = text + "\n本轮没有完成；数据仍保留，可以重试。"
                        armAutoExit("TRANSFER_FAILED")
                        RwDialogs.message(
                            this,
                            "传输没有完成",
                            text.ifBlank { "本轮没有完成。" } + "\n\n数据仍保留在当前设备，可以重试。",
                            confirmLabel = "知道了",
                        )
                    }
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
        if (event.action == MotionEvent.ACTION_DOWN) {
            scheduleIdle()
            armAutoExit("TOUCH")
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (exiting || !runtime.check("KEY")) return true
        scheduleIdle()
        armAutoExit("KEY")
        return super.onKeyDown(keyCode, event)
    }
    private fun scheduleIdle() {
        if (!working && !exiting) runtime.touchIdle()
    }
    /** Phone home stays alive until the explicit exit button; no idle timeout kills it. */
    private fun keepPhoneHomeVisible() {
        if (BuildConfig.DEVICE_ROLE != "phone" || exiting || working || transfer != null) return
        if (runtime.isIdleHeld()) return
        if (!runtime.holdIdle()) {
            runtime.close()
            runtime = newIdleRuntime()
            runtime.holdIdle()
        }
    }
    private fun newIdleRuntime() = DeadlineRuntime(
        idleBudgetMs = if (BuildConfig.DEVICE_ROLE == "phone") WorkBudget.PHONE_UI_IDLE_MS else WorkBudget.IDLE_MS,
    ) { reason, evidence -> onIdleDeadline(reason, evidence) }
    private fun onIdleDeadline(reason: String, evidence: Map<String, Any>) {
        if (BuildConfig.DEVICE_ROLE == "phone" && reason == "DEADLINE_IDLE") {
            // The consumer home page is not a bounded diagnostic task. Keep it visible for the
            // next screen-on instead of closing the task in the user's pocket.
            record("PHONE_HOME_IDLE_SUPPRESSED", evidence)
            keepPhoneHomeVisible()
            return
        }
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

    private fun exportReport() {
        if (working || exiting) return
        if (app.activeSyncOwner != null) {
            showReportProgress("正在同步；完成后再导出报告。")
            return
        }
        val journal = app.journal ?: run { status.text = initialStatus(); return }
        if (!runtime.begin(RunDeadlines.Kind.EXPORT, 58_000)) return
        app.activeSyncOwner = ownerToken
        working = true
        val owner = ++operation
        record("DEADLINE_ARMED", runtime.evidence())
        showReportProgress("正在保存问题报告…本次诊断导出最多 60 秒。")
        val progress: (String) -> Unit = { text ->
            main.post { if (owner == operation && !exiting) showReportProgress(text) }
        }
        val builder = DiagnosticReportBuilder(
            journal, File(filesDir, "received"), File(cacheDir, "reports"),
            BuildConfig.DEVICE_ROLE, mapOf(
                "appId" to packageName, "versionCode" to BuildConfig.VERSION_CODE,
                "versionName" to BuildConfig.VERSION_NAME, "buildRun" to BuildConfig.BUILD_RUN,
                "debuggable" to BuildConfig.DEBUG, "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL, "sdkInt" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
                "release" to Build.VERSION.RELEASE, "display" to Build.DISPLAY),
            File(filesDir, "diagnostic-summary/latest.json"), progress,
        )
        val exporter = ReportExporter(
            builder, DownloadsExportDestination(contentResolver), journal, progress,
        )
        if (reportWorker.isShutdown) reportWorker = Executors.newSingleThreadExecutor()
        reportWorker.execute {
            try {
                val report = exporter.export(owner)
                main.post {
                    if (owner == operation && !exiting && runtime.check("EXPORT_RESULT")) {
                        val saved = "已保存并回读校验：\nDownload/RawWave/${report.file().name}"
                        showReportProgress(saved)
                        RwDialogs.message(
                            this,
                            "问题报告已保存",
                            "已保存到：\nDownload/RawWave/${report.file().name}\n\n" +
                                "含本机日志及已收到且校验通过的最近 ${report.includedWatchReports()} 份手表包。\n" +
                                "已回读校验；旧附件不代表本轮成功。",
                            confirmLabel = "知道了",
                            onConfirm = { if (returnOnDone && !exiting) returnToCaller() },
                        )
                    }
                }
            } catch (failure: Exception) {
                main.post {
                    if (owner == operation && !exiting && runtime.check("EXPORT_FAILURE")) {
                        val detail = "${failure.javaClass.simpleName}: ${failure.message}"
                        showReportProgress("导出失败：$detail\n本机日志仍保留，可稍后重试。")
                        RwDialogs.message(
                            this,
                            "问题报告导出失败",
                            "没有报告成功。\n$detail\n\n" +
                                if (failure is ReportExporter.Failure && failure.published)
                                    "注意：已校验的导出文件已发布并保留；失败发生在后续诊断记录。"
                                else "本机日志仍保留，可稍后重试。",
                            confirmLabel = "知道了",
                            onConfirm = { if (returnOnDone && !exiting) returnToCaller() },
                        )
                    }
                }
            } finally {
                main.post {
                    if (owner == operation && !exiting) {
                        working = false; runtime.finishExport()
                        if (app.activeSyncOwner == ownerToken) app.activeSyncOwner = null
                        keepPhoneHomeVisible()
                        armAutoExit("REPORT_EXPORT")
                        if (background) parkIdle()
                    }
                }
            }
        }
    }

    private fun showReportProgress(text: String) {
        if (::reportStatus.isInitialized) reportStatus.text = text
        else if (::status.isInitialized) status.text = text
    }

    private fun showError(failure: Exception) {
        status.text = "操作失败：${failure.javaClass.simpleName}\n${failure.message}\n未报告成功。请拍照保存此页；本机日志仍在。"
    }
    private fun record(event: String, values: Map<String, Any>) {
        try { app.journal?.event(event, values) } catch (failure: Exception) { if (::status.isInitialized) showError(failure) }
    }
    private fun returnToCaller() {
        returningToCaller = true
        finish()
    }
    private fun exitNow(reason: String, force: Boolean = false, deadlineEvidence: Map<String, Any> = emptyMap()) {
        if (exiting) return
        if (working && !force && transfer == null) { status.text = "数据正在安全写出，请等待完成；不会把未校验文件报告为成功。"; return }
        exiting = true; ++operation
        app.lastStopReason = reason
        runtime.close()
        main.removeCallbacks(autoExit)
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
        reportWorker.shutdownNow()
        summaryLoading = false
        main.removeCallbacksAndMessages(null)
        record("CLEANUP_RESULT", mapOf("workerStopRequested" to true, "workerTerminatedAtCheck" to worker.isTerminated,
            "systemResourcesVerified" to false, "forcedProcessKill" to false, "cachedProcessAllowed" to true))
        try { app.journal?.mark("CLEANUP_REQUESTED") } catch (_: Exception) {}
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        if (isFinishing && !exiting && !returningToCaller) exitNow("ACTIVITY_FINISHED", force = true)
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
        reportWorker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onStop() {
        super.onStop()
        background = true
        main.removeCallbacks(autoExit)
        main.removeCallbacks(connectionTick)
        if (!exiting && !working && transfer == null) parkIdle()
        if (!exiting && runtime.check("BACKGROUND")) record("ACTIVITY_BACKGROUND",
            runtime.evidence() + mapOf("working" to working,"deadlineRenewed" to false))
    }
    override fun onResume() {
        super.onResume()
        background = false
        // Re-register after a preview/other screen temporarily owned the observer. Without this,
        // returning to the data console would leave its status frozen.
        if (!exiting) app.activitySyncObserver = activitySyncObserver
        updatePowerNote()
        if (!exiting && !working) refreshReceivedSummary()
        updateConnectionStatus()
        // Background time counts toward the same absolute idle deadline (phone: 3 minutes;
        // watch: 80 seconds). Returning is not itself a user interaction and cannot renew it.
        if (!exiting && transfer == null && !working) {
            if (app.activitySyncStatus.running) holdIdleForActivitySync()
            else if (runtime.isClosed()) releaseIdleForActivitySync()
        }
        if (!exiting) keepPhoneHomeVisible()
        armAutoExit("RESUME")
        main.removeCallbacks(connectionTick)
        if (BuildConfig.DEVICE_ROLE == "phone" && !exiting) main.postDelayed(connectionTick, 30_000L)
        if (!exiting && runtime.resumeIdle()) record("IDLE_PAGE_RETURNED_WITHIN_EXISTING_DEADLINE", runtime.evidence())
        if (!exiting && runtime.check("RESUME")) record("ACTIVITY_RESUMED", runtime.evidence())
    }
    private fun parkIdle() {
        if (runtime.parkIdle()) {
            worker.shutdownNow()
            summaryLoading = false
            record("IDLE_BACKGROUND_QUIESCENT", runtime.evidence() + mapOf(
                "workerStopRequested" to true, "scheduledCallbacks" to 0,
                "syncLease" to false, "cachedPageMayRemain" to true))
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}








