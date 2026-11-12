package com.rawwave.shell

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.rawwave.core.ActivityArchive
import com.rawwave.core.ActivityOrder
import com.rawwave.core.ActivityStore
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.AutoSyncGate
import com.rawwave.core.SensorCatalog
import com.rawwave.core.SyncStopScope
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwCheckBox
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwGradientTitle
import com.rawwave.designsystem.RwInputField
import com.rawwave.designsystem.RwKeypadButton
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwRadioButton
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwSectionHeader
import com.rawwave.designsystem.RwStackedButton
import com.rawwave.designsystem.RwStatusPill
import com.rawwave.designsystem.RwTheme
import java.io.File

/** Watch-only capture screen; the diagnostic screen cannot own the capture lifetime. */
class RecordingActivity : ComponentActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var measurementHeader: TextView
    private lateinit var timerLabel: TextView
    private lateinit var activityTimer: TextView
    private lateinit var successBanner: RwStatusPill
    private lateinit var stepCountdown: TextView
    private lateinit var stepInstruction: TextView
    private lateinit var topPane: ScrollView
    
    private lateinit var controls: LinearLayout
    private lateinit var guide: HomeKeyGuide
    private var pending: ActivityStore.Template? = null
    private var stopDialog: android.app.Dialog? = null
    private var idle: DeadlineRuntime? = null
    private var mode = ""
    private var resumed = false
    private var lastTouchKeepaliveMs = 0L
    private var lastRenderedPhase = ""
    private var lastRenderedActive = false
    private var syncCycleSeen = false
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as ProbeApplication
    private val autoSyncGate = AutoSyncGate()
    private val autoSync = Runnable {
        val pendingCount = pendingSyncCount()
        val start = autoSyncGate.fire(resumed, ActivityRecordingService.state.active,
            app.activitySyncStatus.running, pendingCount)
        try { app.journal?.event("ACTIVITY_AUTO_SYNC_TIMER_FIRED", mapOf("started" to start,
            "pendingCount" to pendingCount, "foreground" to resumed)) } catch (_: Exception) {}
        if (start) { syncCycleSeen = true; startPendingSync(ActivitySyncPolicy.OPEN_IDLE_AUTO) }
    }
    private val syncListener: (ProbeApplication.ActivitySyncStatus) -> Unit = syncListener@{ sync ->
        if (inWatchPage() && !ActivityRecordingService.state.active) {
            if (!sync.running) releaseIdleAfterSync()
            if (mode == "SYNC_STATUS") showSyncStatus()
            return@syncListener
        }
        syncCycleSeen = true
        if (!resumed) {
            // The screen may be off while the bounded sync finishes. Do not touch views here;
            // only consume the one-shot completion signal and close the task.
            if (!sync.running && sync.success && consumeSyncExit()) {
                main.post { if (!isFinishing) finishAndRemoveTask() }
            }
            return@syncListener
        }
        if (ActivitySyncPolicy.isNonBlockingHomeSync(sync.trigger)) {
            if (sync.running) holdIdleForSync() else releaseIdleAfterSync()
            if (sync.running) {
                status.text = "正在后台补同步上次活动；你仍可直接选择活动开始采集。\n${sync.text}"
            } else {
                mode = ""
                render(ActivityRecordingService.state)
                if (!ActivityRecordingService.state.active) status.text = if (sync.success)
                    "上次活动已经同步完成。现在可以开始新的活动。"
                else "上次活动本轮未同步成功，数据仍保留；现在仍可开始新的活动。"
            }
        } else if (sync.running && !ActivitySyncPolicy.isNonBlockingHomeSync(sync.trigger)) {
            renderSync(sync)
        } else if (sync.running) {
            // Background sync owns no page. Keep the home startable; progress stays in the
            // sync button/status and never replaces capture.
            if (!ActivityRecordingService.state.active) {
                status.text = "正在后台同步：${sync.text}\n可以先开始新的活动。"
            }
        } else if (sync.success && ActivitySyncPolicy.exitAfterSuccessfulSync(sync.trigger)) {
            consumeSyncExit()
            idle?.close(); idle = null
            main.post { if (!isFinishing) finishAndRemoveTask() }
        } else {
            releaseIdleAfterSync()
            mode = ""
            render(ActivityRecordingService.state)
            if (!ActivityRecordingService.state.active) status.text =
                "本轮同步未完成，数据仍保留。你可以开始活动；无操作满80秒后停止所有工作。"
        }
    }
    private fun consumeSyncExit(): Boolean =
        (application as ProbeApplication).pendingActivitySyncExit.getAndSet(false)
    private val listener: (ActivityRecordingService.Companion.State) -> Unit = { render(it) }
    private val successListener: (String, String) -> Unit = { kind, text ->
        if (resumed && ::successBanner.isInitialized) {
            successBanner.text = "✓ $kind 成功 · $text"
            successBanner.setTone(RwTheme.colors(this).primaryGradientEnd, Color.WHITE)
            successBanner.visibility = View.VISIBLE
            main.removeCallbacks(hideSuccess)
            // Keep the result visible long enough after the final short measurement.
            main.postDelayed(hideSuccess, 4500)
        }
    }
    private val hideSuccess = Runnable {
        if (::successBanner.isInitialized) successBanner.visibility = View.GONE
    }
    private val idleExitListener: () -> Unit = { finishIdle("ECG_IDLE_STOP") }
    private fun finishIdle(reason: String) {
        cancelAutoSync(reason)
        idle?.close(); idle = null
        guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishAndRemoveTask()
    }
    /** The UI countdown is never allowed to stop a bounded transfer that already owns the app. */
    private fun onIdleDeadline(reason: String, evidence: Map<String, Any>) {
        if (app.activitySyncStatus.running) {
            try { app.journal?.event("ACTIVITY_UI_IDLE_EXPIRED_DURING_SYNC_SUPPRESSED",
                evidence + mapOf("reason" to reason, "syncRunning" to true,
                    "syncTrigger" to app.activitySyncStatus.trigger, "stoppedSync" to false)) }
            catch (_: Exception) {}
            idle = null
            return
        }
        try { app.journal?.event("ACTIVITY_UI_IDLE_EXPIRED", evidence + mapOf("reason" to reason,
            "syncRunning" to false, "syncTrigger" to app.activitySyncStatus.trigger)) }
        catch (_: Exception) {}
        app.requestStopBackgroundSync("WATCH_RECORDING_IDLE_DEADLINE")
        finishIdle("WATCH_RECORDING_IDLE_DEADLINE")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && app.activitySyncAttemptGuard.resetForExplicitOpen()) {
            try { app.journal?.event("ACTIVITY_OPEN_AUTO_SYNC_REENABLED", mapOf("reason" to "FRESH_LAUNCHER_ACTIVITY")) }
            catch (_: Exception) {}
        }
        val colors = RwTheme.colors(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (RwTheme.isDark(this)) {
            window.decorView.systemUiVisibility and lightFlags.inv()
        } else {
            window.decorView.systemUiVisibility or lightFlags
        }
        val inset = (resources.displayMetrics.widthPixels * .16f).toInt()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(32), inset, dp(48)); setBackgroundColor(colors.background)
        }
        successBanner = RwStatusPill(this).apply {
            visibility = View.GONE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER
        }
        body.addView(successBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) })
        timerLabel = RwBodyText(this).apply {
            text = "活动时长"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setTextColor(colors.primary)
            visibility = View.GONE
        }
        body.addView(timerLabel)
        activityTimer = RwGradientTitle(this).apply {
            text = "00:00"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
            visibility = View.GONE
        }
        body.addView(activityTimer)
        stepCountdown = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = RwTheme.displayTypeface()
            gravity = Gravity.CENTER
            setTextColor(colors.primary)
            setPadding(0, dp(2), 0, dp(2))
            visibility = View.GONE
        }
        body.addView(stepCountdown, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        guide = HomeKeyGuide(this)
        // 84 dp leaves room for the two-line instruction below it on the 480 px round screen;
        // 120 dp pushed the last instruction line off-screen on the SpO2/BIA placement pages.
        body.addView(guide, LinearLayout.LayoutParams(-1, dp(84)))
        stepInstruction = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(2), 0, dp(4))
            visibility = View.GONE
        }
        body.addView(stepInstruction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        measurementHeader = label("")
        measurementHeader.setTextColor(colors.onSurface)
        measurementHeader.typeface = RwTheme.displayTypeface()
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(4), inset, dp(32))
        }
        status = label("原波 · 活动采集", details)
        topPane = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            addView(body)
        }
        body.addView(details)
        setContentView(topPane)
        ActivityRecordingService.idleExitObserver = idleExitListener
        // Keep the completion observer alive across onStop: a watch screen can turn off during
        // the bounded sync, and the success must still close the task instead of waiting for a
        // later manual launch.
        app.activitySyncObserver = syncListener
        if (consumeSyncExit()) { finishAndRemoveTask(); return }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (mode) {
                    "EDIT_TEMPLATE" -> { confirmDiscardForm(); return }
                    "MANAGE_ACTIVITIES" -> { showMorePage(); return }
                    "DELETE_ACTIVITIES" -> { showActivityManagement(); return }
                    "HOME_MORE" -> { returnHome(); return }
                    "REPORT_TOOLS" -> { showMorePage(); return }
                    "LOCAL_RECORDS" -> { showMorePage(); return }
                    "SYNC_STATUS" -> { returnHome(); return }
                }
                if (ActivityRecordingService.state.active) {
                    // Back is ambiguous while capture is running: make the user choose
                    // between a saved early stop and continuing in the background.
                    if (ActivityRecordingService.state.phase == "DRAINING") { moveTaskToBack(true); return }
                    confirmStopCapture(); return
                }
                // Leaving the screen during sync is not a request to stop the transfer.
                if (app.activitySyncStatus.running) moveTaskToBack(true) else finishAndRemoveTask()
            }
        })
    }
    override fun onStart() {
        super.onStart(); resumed = true
        ActivityRecordingService.observer = listener
        ActivityRecordingService.successObserver = successListener
        // A child screen (problem-report export, diagnostics) may have replaced the app-level
        // observer and cleared it on exit. Re-register before restoring the page.
        app.activitySyncObserver = syncListener
        if (consumeSyncExit()) { finishAndRemoveTask(); return }
        // A fresh Activity is the user's recovery path: never keep a RELEASING screen that
        // outlived its hold, and never keep a sync page whose session/lease is already gone.
        (application as ProbeApplication).reconcileStaleSyncState()
        ActivityRecordingService.resetStaleReleaseState()
        if (inWatchPage()) {
            // Returning from a child screen (problem-report export, diagnostics) must restore
            // the page the user came from, not silently replace it with the home list.
            when (mode) {
                "HOME_MORE" -> showMorePage()
                "MANAGE_ACTIVITIES" -> showActivityManagement()
                "DELETE_ACTIVITIES" -> showDeleteActivitiesPage()
                "REPORT_TOOLS" -> showReportToolsPage()
                "LOCAL_RECORDS" -> showLocalRecords()
                "SYNC_STATUS" -> showSyncStatus()
                // EDIT_TEMPLATE keeps the form exactly as the user left it.
            }
        } else if (app.activitySyncStatus.running && !ActivitySyncPolicy.isNonBlockingHomeSync(app.activitySyncStatus.trigger))
            renderSync(app.activitySyncStatus)
        else render(ActivityRecordingService.state)
        scheduleAutoSync("ON_START")
    }
    override fun onStop() {
        resumed = false
        if (ActivityRecordingService.observer === listener) ActivityRecordingService.observer = null
        if (ActivityRecordingService.successObserver === successListener) ActivityRecordingService.successObserver = null
        cancelAutoSync("ON_STOP")
        guide.animateContact = false
        // Do not count screen-off time against the UI idle deadline. Closing the activity task
        // here made a screen-on land on the watch face instead of the activity screen.
        idle?.close(); idle = null
        super.onStop()
    }
    override fun onDestroy() {
        if (ActivityRecordingService.idleExitObserver === idleExitListener) ActivityRecordingService.idleExitObserver = null
        if (ActivityRecordingService.successObserver === successListener) ActivityRecordingService.successObserver = null
        if (app.activitySyncObserver === syncListener) app.activitySyncObserver = null
        stopDialog?.dismiss(); stopDialog = null
        main.removeCallbacks(hideSuccess)
        cancelAutoSync("ON_DESTROY"); idle?.close(); super.onDestroy()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            idle?.touchIdle()
            // The short-phase 80-second safety is an idle guard, not a deadline for a user
            // who is still retrying or scrolling to skip. Throttle the service ping.
            if (ActivityRecordingService.state.active) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastTouchKeepaliveMs >= 3_000L) {
                    lastTouchKeepaliveMs = now
                    try {
                        startService(Intent(this, ActivityRecordingService::class.java).setAction("TOUCH"))
                    } catch (_: Exception) {}
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
    private fun render(s: ActivityRecordingService.Companion.State) {
        if (inWatchPage() && !s.active) return
        // The vendor release hold is a bounded 60-second step: the process is about to
        // remove itself so no sensor handle survives the activity. It must always end;
        // a stale RELEASING state is cleared by resetStaleReleaseState() on a fresh open.
        if (!s.active && s.phase == "RELEASING") {
            measurementHeader.visibility = View.VISIBLE
            measurementHeader.text = "正在释放传感器"
            measurementHeader.textSize = 20f
            status.visibility = View.VISIBLE
            status.text = s.text
            status.textSize = 14f
            guide.visibility = View.GONE
            stepCountdown.visibility = View.GONE
            stepInstruction.visibility = View.GONE
            timerLabel.visibility = View.GONE
            activityTimer.visibility = View.GONE
            controls.removeAllViews()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        if (app.activitySyncStatus.running && !ActivitySyncPolicy.isNonBlockingHomeSync(app.activitySyncStatus.trigger)) {
            renderSync(app.activitySyncStatus); return
        }
        measurementHeader.visibility = if (s.active) View.VISIBLE else View.GONE
        status.visibility = if (s.active) View.VISIBLE else View.GONE
        if (s.active) { cancelAutoSync("RECORDING_ACTIVE"); idle?.close(); idle = null }
        else if (app.activitySyncStatus.running) holdIdleForSync()
        else ensureIdle()
        val measuringEcg = s.shortSensor == "ECG" && (s.phase.startsWith("PRE") || s.phase.startsWith("POST"))
        val waitingBiaContact = s.shortSensor == "BIA" && !s.biaContact &&
            (s.phase.startsWith("PRE") || s.phase.startsWith("POST") || s.phase.startsWith("WAIT_BIA_"))
        val waitingEcgContact = measuringEcg && (s.remaining < 0 || !s.contact)
        val waitingContact = waitingBiaContact || waitingEcgContact
        val shortPhase = s.active && (
            s.phase.startsWith("PRE") || s.phase.startsWith("POST") ||
                s.phase.startsWith("READY_") || s.phase.startsWith("WAIT_BIA_")
            )
        val guideMode = when {
            s.shortSensor == "BIA" || s.phase.startsWith("WAIT_BIA_") -> "BIA"
            s.shortSensor == "SPO2" -> "SPO2"
            s.shortSensor == "PPG_ON_DEMAND" -> "PPG"
            else -> "ECG"
        }
        val contactDetected = if (guideMode == "BIA") s.biaContact
            else if (guideMode == "ECG") s.contact
            else false
        guide.visibility = if (shortPhase) View.VISIBLE else View.GONE
        guide.mode = guideMode
        guide.biaMode = guideMode == "BIA"
        guide.contactDetected = contactDetected
        guide.animateContact = shortPhase && !contactDetected && resumed && waitingContact
        val countdown = if (shortPhase) countdownText(s, guideMode, contactDetected) else ""
        stepCountdown.visibility = if (countdown.isNotEmpty()) View.VISIBLE else View.GONE
        if (countdown.isNotEmpty()) stepCountdown.text = countdown
        stepInstruction.visibility = if (shortPhase) View.VISIBLE else View.GONE
        if (shortPhase) stepInstruction.text = instructionsFor(s, guideMode, contactDetected)
        if (measuringEcg || (s.active && (s.shortSensor == "SPO2" || s.shortSensor == "BIA" || s.shortSensor == "PPG_ON_DEMAND"))) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val showTimer = s.active && s.phase == "CORE-1"
        timerLabel.visibility = if (showTimer) View.VISIBLE else View.GONE
        activityTimer.visibility = if (showTimer) View.VISIBLE else View.GONE
        if (showTimer) activityTimer.text = formatElapsed(s.coreElapsedSeconds)
        measurementHeader.textSize = if (s.active) {
            if (shortPhase) 17f else 19f
        } else 34f
        measurementHeader.text = if (s.active) {
            if (shortPhase) {
                val title = when (guideMode) {
                    "ECG" -> if (contactDetected) "ECG · 接触已确认" else if (measuringEcg) "ECG · 等待接触" else "ECG · 准备"
                    "BIA" -> {
                        val name = s.biaMode.ifBlank { "体成分" }
                        if (s.biaModeCount > 0) "$name ${s.biaModeNumber}/${s.biaModeCount}" else name
                    }
                    "SPO2" -> if (s.phase.startsWith("READY_")) "血氧 · 准备" else "血氧 · 测量中"
                    "PPG" -> "PPG · 测量中"
                    else -> "测量"
                }
                "$title\n${if (contactDetected) "接触已确认，保持不动" else "按下方说明操作"}"
            } else {
                val timer = when {
                    s.phase == "DRAINING" -> "正在保存…"
                    s.phase.startsWith("REVIEW") -> "本次测量未完成"
                    s.shortSensor == "BIA" && !s.biaContact && s.biaRetrying -> "测量中断 · 正在重试"
                    s.shortSensor == "BIA" && !s.biaContact -> "等待检测接触"
                    s.shortSensor == "BIA" -> "测量中 · 剩余 ${s.remaining} 秒"
                    measuringEcg && (s.remaining < 0 || !s.contact) -> "等待检测接触"
                    measuringEcg -> "ECG 测量中 · 剩余 ${s.remaining} 秒"
                    s.remaining > 0 -> "${s.remaining} 秒"
                    else -> "正在记录"
                }
                val instruction = when {
                    s.phase == "DRAINING" -> "正在结束采集，请稍候"
                    s.phase.startsWith("REVIEW") -> if (s.shortSensor == "PPG_ON_DEMAND")
                        "可以调整手臂后重测，或跳过继续" else "可以松开手指后重测，或跳过继续"
                    s.shortSensor == "SPO2" -> "手表上移到腕骨上方\n表带贴合，手肘放桌\n静止、不说话"
                    s.shortSensor == "BIA" && !s.biaContact && s.biaRetrying ->
                        "上次测量${if(s.biaProgress > 0) "在 ${s.biaProgress}% " else ""}中断（${s.biaHint}）\n请保持双指接触，稍后自动重试"
                    s.shortSensor == "BIA" && !s.biaContact ->
                        "先把手表戴回正常位置\n双臂抬到胸口，腋下张开\n两指轻触上下键，不要相碰"
                    s.shortSensor == "BIA" ->
                        "保持双指接触，双手不要相碰\n双臂抬到胸口，直至测量完成"
                    s.shortSensor == "PPG_ON_DEMAND" -> "正常佩戴即可，不用调表带\n无倒数，手臂静止采集30秒"
                    measuringEcg -> "前臂放桌上，指尖盖住上键，不要按\n保持不动，不要说话"
                    s.phase == "CORE-1" -> "正常活动即可\n结束时点击下方按钮"
                    else -> "请稍候"
                }
                "$timer\n$instruction"
            }
        } else "原波"
        status.text = if (s.active) "采集详情\n${s.text}" else s.text
        status.textSize = 14f
        if (mode == "${s.active}/${s.phase}/${s.skipAllowed}") return
        mode = "${s.active}/${s.phase}/${s.skipAllowed}"
        val previousPhase = lastRenderedPhase
        val phaseChanged = s.phase != previousPhase
        val wasActive = lastRenderedActive
        lastRenderedPhase = s.phase
        lastRenderedActive = s.active
        // BIA's automatic retry keeps exactly the same buttons (skip / cancel). Rebuilding
        // them resets the ScrollView and throws the user away from the skip button every
        // five seconds. Only the status text above needs to change during this retry loop.
        val keepBiaRetryControls = s.active && s.shortSensor == "BIA" &&
            !s.phase.startsWith("REVIEW_") &&
            (previousPhase.startsWith("PRE-2") || previousPhase.startsWith("WAIT_BIA_") ||
                previousPhase == "DRAINING" || s.phase == "DRAINING")
        if (keepBiaRetryControls && controls.childCount > 0) return
        val previousScroll = topPane.scrollY
        controls.removeAllViews()
        if (s.active) {
            if (s.phase.startsWith("REVIEW_BIA_")) {
                val mode=if(s.biaMode.isEmpty()) "体成分" else s.biaMode
                // Keep skip first: a failed second stage must never force a scroll hunt.
                button(if(s.biaHasNext) "跳过当前，继续下一种" else "跳过体成分，继续", primary = true) { command("SKIP_BIA") }
                button("重测$mode") { command("RETRY_BIA") }
            } else if (s.shortSensor == "BIA") {
                button("跳过体成分，继续") { command("SKIP_BIA") }
            } else if (s.phase.startsWith("READY_SPO2_")) {
                button("手已放好，开始血氧", primary = true) { command("START_SPO2") }
            } else if (s.phase.startsWith("REVIEW_SPO2")) {
                // Skip first: a failed retry must not trap the user in the review page.
                button("跳过血氧，继续", primary = true) { command("SKIP_SPO2") }
                button("重测血氧") { command("RETRY_SPO2") }
            } else if (s.phase.startsWith("REVIEW_PPG_")) {
                button("跳过 PPG，继续", primary = true) { command("SKIP_PPG") }
                button("重测 PPG") { command("RETRY_PPG") }
            } else if (s.phase.startsWith("REVIEW_")) {
                button("继续重测 ECG（不限次数）") { command("RETRY_ECG") }
                if (s.skipAllowed) button("跳过 ECG，继续本次活动") { command("SKIP_ECG") }
            }
            if (measuringEcg && s.skipAllowed)
                button("跳过 ECG，继续本次活动") { command("SKIP_ECG") }
            if (s.phase == "CORE-1") {
                button("结束活动", primary = true) { command("END") }
            }
            button("中断并保留已有数据") {
                RwDialogs.confirm(
                    this,
                    "中断本次记录",
                    "已保存的数据会保留，并注明用户中断。",
                    confirmLabel = "中断",
                    cancelLabel = "继续记录",
                ) { command("CANCEL") }
            }
        } else showHome()
        // A progress tick or background sync update must not throw the user back to the top.
        // Entering a new capture page, entering home from capture, or the first home build
        // starts at the top; otherwise keep the user's scroll position.
        val enteringHome = !s.active && (wasActive || previousPhase.isEmpty())
        if ((s.active && phaseChanged) || enteringHome) {
            topPane.post { topPane.scrollTo(0, 0) }
        } else {
            topPane.post { topPane.scrollTo(0, previousScroll) }
        }
    }
    private fun showHome() {
        // showHome() is called from render() and from recovery paths. Always rebuild the
        // list here; appending to a previous list produced a duplicate exit button.
        mode = ""
        controls.removeAllViews()
        try {
            val store = ActivityRecordingService.store(this)
            val pendingSync = ActivityArchive.pendingCount(File(filesDir, "activities"))
            val sync = app.activitySyncStatus
            val latestUnsealed = try {
                store.history().firstOrNull()?.contains("未封存/") == true
            } catch (_: Exception) { false }
            hideHomeChrome()
            val templates = orderedTemplates(store.templates())
            if (templates.isEmpty()) {
                button("新建活动", primary = true) { createForm() }
            } else {
                if (templates.size > 1) {
                    label("长按活动可拖动排序", controls).apply {
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
                    }
                }
                templates.forEachIndexed { index, template ->
                    activityButton(template, primary = index == 0, reorderable = templates.size > 1)
                }
            }
            if (filesDir.usableSpace < 1_000_000_000L) label("存储不足1GB，请清理不需要的软件。原始数据尚未同步，请勿卸载原波。", controls)
            button("更多") { showMorePage() }
            button("发送问题报告到手机") { sendReportToPhone() }
            stackedButton("同步状态", syncButtonText(sync, pendingSync, latestUnsealed)) { showSyncStatus() }
            button("完全退出") { exitWatchCompletely() }
        } catch (e: Exception) {
            status.visibility = View.VISIBLE
            status.text = "读取活动失败：${e.message}。请保留数据并导出问题报告。"
        }
        scheduleAutoSync("HOME_RENDERED")
    }

    private fun startActivityDrag(source: View, template: ActivityStore.Template) {
        cancelAutoSync("DRAG_ORDER")
        val clip = ClipData.newPlainText("activity-order", template.id())
        try {
            source.startDragAndDrop(clip, View.DragShadowBuilder(source), null, 0)
        } catch (_: Exception) {
            // A device that refuses drag-and-drop keeps the tap-to-start action intact.
        }
    }

    private fun dragListenerFor(target: ActivityStore.Template) = View.OnDragListener { view, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                view.alpha = 0.55f
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                view.alpha = 1f
                true
            }
            DragEvent.ACTION_DROP -> {
                view.alpha = 1f
                val dragged = event.clipData?.getItemAt(0)?.text?.toString()
                if (!dragged.isNullOrBlank() && dragged != target.id()) {
                    val ids = try {
                        orderedTemplates(ActivityRecordingService.store(this).templates()).map { it.id() }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val from = ids.indexOf(dragged)
                    val to = ids.indexOf(target.id())
                    if (from >= 0 && to >= 0) {
                        val moved = ActivityOrder.move(ids, dragged, to - from)
                        orderPrefs().edit().putString("ids", moved.joinToString(",")).apply()
                        try {
                            app.journal?.event("ACTIVITY_ORDER_DRAGGED",
                                mapOf("from" to from, "to" to to, "count" to moved.size))
                        } catch (_: Exception) {}
                        view.post { returnHome() }
                    }
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                view.alpha = 1f
                true
            }
            else -> true
        }
    }

    private fun syncButtonText(sync: ProbeApplication.ActivitySyncStatus, pendingSync: Int, latestUnsealed: Boolean): String = when {
        sync.running -> "同步中…"
        latestUnsealed && pendingSync > 0 -> "待同步 $pendingSync 次"
        latestUnsealed -> "上次未完整封存"
        pendingSync > 0 -> "待同步 $pendingSync 次"
        sync.text.isNotBlank() && !sync.success -> "上次同步未完成"
        else -> "已全部同步"
    }

    /** The first screen shows only user activities; no app name and no persistent explanation. */
    private fun hideHomeChrome() {
        measurementHeader.visibility = View.GONE
        status.visibility = View.GONE
    }

    private fun activityButton(template: ActivityStore.Template, primary: Boolean, reorderable: Boolean) {
        val view = if (primary) RwPrimaryButton(this) else RwSecondaryButton(this)
        view.text = template.name()
        view.contentDescription = if (reorderable)
            "${template.name()}，点击开始，长按后拖动可调整顺序"
        else "${template.name()}，点击开始"
        view.setOnClickListener { cancelAutoSync("OPEN_ACTIVITY"); prepare(template) }
        if (reorderable) {
            // Long-press starts a real drag; the old "move up/down" dialog is gone so the
            // list itself is the order editor the user asked for.
            view.setOnLongClickListener {
                startActivityDrag(it, template)
                true
            }
            view.setOnDragListener(dragListenerFor(template))
        }
        controls.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
    }

    private fun orderPrefs() = getSharedPreferences("activity-order", MODE_PRIVATE)

    private fun savedOrder(): List<String> =
        orderPrefs().getString("ids", "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun orderedTemplates(all: List<ActivityStore.Template>): List<ActivityStore.Template> {
        val byId = all.associateBy { it.id() }
        return ActivityOrder.apply(all.map { it.id() }, savedOrder()).mapNotNull { byId[it] }
    }

    private fun pageHeader(title: String) {
        // Child pages are for browsing and editing. The 80-second home idle deadline must not
        // close the task while the user is reading a page or filling in the activity form.
        idle?.close(); idle = null
        hideTimer()
        val colors = RwTheme.colors(this)
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = title
        measurementHeader.textSize = 27f
        measurementHeader.setTextColor(colors.onSurface)
        guide.visibility = View.GONE
        guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.visibility = View.GONE
        controls.removeAllViews()
        // A secondary page opened from a scrolled home kept the old offset and started halfway
        // down, hiding its title. Every page starts at the top.
        topPane.post { topPane.scrollTo(0, 0) }
    }

    private fun showMorePage() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("MORE_PAGE")
        ensureIdle()
        mode = "HOME_MORE"
        pageHeader("更多")
        button("活动管理") { showActivityManagement() }
        button("问题报告工具") { showReportToolsPage() }
        button("本地记录") { showLocalRecords() }
        button("返回首页") { returnHome() }
    }

    private fun showSyncStatus() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("SYNC_STATUS_PAGE")
        ensureIdle()
        mode = "SYNC_STATUS"
        pageHeader("同步状态")
        val sync = app.activitySyncStatus
        val pending = pendingSyncCount()
        val latestUnsealed = try {
            ActivityRecordingService.store(this).history().firstOrNull()?.contains("未封存/") == true
        } catch (_: Exception) { false }
        val summary = buildString {
            append("当前：")
            append(when {
                sync.running -> "同步中"
                pending > 0 -> "待同步 $pending 次"
                latestUnsealed -> "上次未完整封存"
                sync.text.isNotBlank() && !sync.success -> "上次同步未完成"
                else -> "已全部同步"
            })
            if (sync.text.isNotBlank()) append("\n${sync.text}")
        }
        val summaryCard = RwCard(this)
        summaryCard.addView(RwBodyText(this).apply {
            text = summary
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(RwTheme.colors(this@RecordingActivity).onSurface)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        controls.addView(summaryCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        val actionLabel: String
        val actionPrimary: Boolean
        val action: () -> Unit
        if (sync.running) {
            actionLabel = "停止同步"
            actionPrimary = true
            action = { app.requestStopBackgroundSync("WATCH_SYNC_STOP_BUTTON") }
        } else if (pending > 0) {
            actionLabel = "立即同步"
            actionPrimary = true
            action = { startPendingSync(ActivitySyncPolicy.USER_MANUAL) }
        } else {
            actionLabel = "重新检查"
            actionPrimary = false
            action = { showSyncStatus() }
        }
        // Two stacked buttons plus the summary never fit on the first screen; the row keeps the
        // action and the way back visible without hiding the explanation below the fold.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val actionView = if (actionPrimary) RwPrimaryButton(this) else RwSecondaryButton(this)
        actionView.text = actionLabel
        actionView.setSingleLine(true)
        // Half-width buttons: the default 16-24 dp side padding clipped the last character.
        actionView.setPadding(dp(8), dp(6), dp(8), dp(6))
        actionView.setOnClickListener { cancelAutoSync("SYNC_ROW_$actionLabel"); action() }
        val backView = RwSecondaryButton(this).apply {
            text = "返回首页"
            setSingleLine(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { cancelAutoSync("SYNC_ROW_BACK"); returnHome() }
        }
        row.addView(actionView, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(backView, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        controls.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        if (!sync.running && pending > 0 && !isBatteryExempt())
            button("允许后台运行") { showBackgroundPermission() }
        // The explanation is secondary: keep it below the actions so the first screen always
        // shows what can be done, even when the sync line above grows.
        label("手机校验后，手表才会标记已同步。", controls).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
        }
    }

    private fun showActivityManagement() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("MANAGE_ACTIVITIES")
        ensureIdle()
        mode = "MANAGE_ACTIVITIES"
        pageHeader("活动管理")
        button("新增活动", primary = true) { createForm() }
        button("修改活动") { showEditPicker() }
        button("删除活动") { showDeleteActivitiesPage() }
        try {
            val templates = orderedTemplates(ActivityRecordingService.store(this).templates())
            if (templates.isEmpty()) {
                label("还没有活动。请先点“新增活动”。", controls)
            } else {
                label("现有活动", controls).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
                }
                templates.forEach { template ->
                    val card = RwCard(this)
                    card.addView(RwBodyText(this).apply {
                        text = template.name()
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = RwTheme.boldTypeface()
                        setTextColor(RwTheme.colors(this@RecordingActivity).onSurface)
                    })
                    card.addView(RwBodyText(this).apply {
                        text = summaryFor(template)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
                        setPadding(0, dp(4), 0, 0)
                    })
                    controls.addView(card, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) })
                }
            }
        } catch (e: Exception) {
            status.visibility = View.VISIBLE
            status.text = "读取活动失败：${e.message}。请保留数据并导出问题报告。"
        }
        button("返回上一页") { showMorePage() }
    }

    private fun showEditPicker() {
        if (ActivityRecordingService.state.active) return
        val templates = try {
            orderedTemplates(ActivityRecordingService.store(this).templates())
        } catch (e: Exception) {
            status.visibility = View.VISIBLE
            status.text = "读取活动失败：${e.message}"
            return
        }
        if (templates.isEmpty()) {
            RwDialogs.message(this, "修改活动", "还没有活动。请先新增一个活动。", confirmLabel = "知道了")
            return
        }
        RwDialogs.actions(
            this,
            "修改活动",
            "选择要修改的活动",
            templates.map { template ->
                RwDialogs.Action(template.name()) { createForm(template) }
            },
            cancelLabel = "返回",
        )
    }

    private fun showDeleteActivitiesPage() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("DELETE_ACTIVITIES")
        ensureIdle()
        mode = "DELETE_ACTIVITIES"
        pageHeader("删除活动")
        val templates = try {
            orderedTemplates(ActivityRecordingService.store(this).templates())
        } catch (e: Exception) {
            status.visibility = View.VISIBLE
            status.text = "读取活动失败：${e.message}"
            button("返回上一页") { showActivityManagement() }
            return
        }
        if (templates.isEmpty()) {
            label("还没有活动。", controls)
            button("返回上一页", primary = true) { showActivityManagement() }
            return
        }
        label("勾选要删除的活动。已采集的历史数据会保留。", controls).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
        }
        val boxes = mutableListOf<Pair<ActivityStore.Template, RwCheckBox>>()
        templates.forEach { template ->
            val box = RwCheckBox(this).apply {
                text = template.name()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = dp(52)
                setPadding(0, dp(6), 0, dp(6))
            }
            boxes += template to box
            controls.addView(box)
        }
        button("删除所选并返回", primary = true) {
            val chosen = boxes.filter { it.second.isChecked }.map { it.first }
            if (chosen.isEmpty()) {
                RwDialogs.message(this, "还没有选择", "请先勾选要删除的活动。", confirmLabel = "知道了")
                return@button
            }
            RwDialogs.confirm(
                this,
                "删除活动",
                "删除 ${chosen.size} 个活动？已采集的历史数据保留。",
                confirmLabel = "删除",
                cancelLabel = "取消",
            ) {
                try {
                    val store = ActivityRecordingService.store(this)
                    chosen.forEach { store.deleteTemplate(it.id()) }
                    showActivityManagement()
                } catch (e: Exception) {
                    status.visibility = View.VISIBLE
                    status.text = "删除失败：${e.message}"
                }
            }
        }
        button("返回上一页") { showActivityManagement() }
    }

    private fun summaryFor(t: ActivityStore.Template): String {
        // Listed in execution order: SpO2 first (watch above the wrist bone), then everything
        // that runs on the normal position, so the listed order matches what the user does.
        val pre = listOfNotNull(
            if (t.preSpo2()) "血氧" else null,
            if (t.bia()) (when { t.biaBoth() -> "体成分+多频阻抗"; t.mfBia() -> "多频阻抗"; else -> "体成分" }) else null,
            if (t.preEcg()) "ECG" else null,
            if (t.prePpg()) "PPG 100Hz" else null,
        ).joinToString("、").ifEmpty { "无" }
        val during = t.coreSources().joinToString("、", transform = ::sensorLabel).ifEmpty { "无" }
        // Post runs ECG -> PPG -> SpO2: SpO2 is last because it is the only post measurement
        // that needs the watch moved above the wrist bone, and the session ends afterwards.
        val post = listOfNotNull(
            if (t.postEcg()) "ECG" else null,
            if (t.postPpg()) "PPG 100Hz" else null,
            if (t.postSpo2()) "血氧" else null,
        ).joinToString("、").ifEmpty { "无" }
        return "活动前：$pre\n活动中：$during\n活动后：$post"
    }

    private fun confirmDiscardForm() {
        RwDialogs.confirm(
            this,
            "放弃这次填写？",
            "返回后内容不会保存。",
            confirmLabel = "放弃",
            cancelLabel = "继续编辑",
        ) { showActivityManagement() }
    }

    private fun createForm(existing: ActivityStore.Template? = null) {
        cancelAutoSync("EDIT_TEMPLATE")
        idle?.close(); idle = null
        mode = "EDIT_TEMPLATE"
        hideTimer()
        val colors = RwTheme.colors(this)
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = if (existing == null) "新建活动" else "编辑活动"
        measurementHeader.textSize = 26f
        measurementHeader.setTextColor(colors.onSurface)
        status.visibility = View.GONE
        controls.removeAllViews()
        fun section(text: String) {
            controls.addView(RwSectionHeader(this).apply {
                this.text = text
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(colors.primary)
                setPadding(0, dp(16), 0, dp(2))
            })
        }
        fun check(text: String, checked: Boolean = false) = RwCheckBox(this).apply {
            this.text = text
            isChecked = checked
        }
        val name = RwInputField(this).apply { hint = "活动名称"; setSingleLine() }
        val preEcg = check("ECG 30秒", existing?.preEcg() ?: true)
        val preSpo2 = check("血氧 SpO₂（最多30秒）", existing?.preSpo2() ?: false)
        val prePpg = check("PPG 100Hz 30秒（活动中也选连续 PPG 时自动跳过）", existing?.prePpg() ?: false)
        val bia = check("体成分 BIA（双指触键）", existing?.bia() == true)
        val mfBia = check("多频阻抗 MF-BIA", existing?.mfBia() == true)
        val biaBoth = check("两种都测：先 BIA 再 MF-BIA", existing?.biaBoth() == true)
        val profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileBox.addView(RwBodyText(this).apply {
            text = "个人资料（体成分估算）"
            setTextColor(colors.primary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(2))
        })
        var openedPad: View? = null
        fun numberField(label: String, decimal: Boolean) = RwInputField(this).apply {
            id = View.generateViewId(); hint = label; setSingleLine()
            // App buttons own entry, so no Samsung/Gboard IME or digit-mode switch is involved.
            keyListener = null; isFocusable = false; isCursorVisible = false; showSoftInputOnFocus = false
            profileBox.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) })
            val field = this
            val pad = LinearLayout(this@RecordingActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf(".","0","删除"),listOf("清空","完成")).forEach { keys ->
                val row = LinearLayout(this@RecordingActivity)
                keys.forEach { key ->
                    row.addView(RwKeypadButton(this@RecordingActivity).apply {
                        text = key
                        isEnabled = key != "." || decimal
                        if (!isEnabled) alpha = 0.35f
                        setOnClickListener {
                            val old = field.text.toString()
                            when(key) {
                                "完成" -> { pad.visibility = View.GONE; openedPad = null }
                                "清空" -> field.setText("")
                                "删除" -> field.setText(old.dropLast(1))
                                "." -> if (decimal && !old.contains('.')) field.setText(if (old.isEmpty()) "0." else "$old.")
                                else -> if (old.length < 9) field.setText(old + key)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(1), dp(2), dp(1), dp(2)) })
                }
                pad.addView(row)
            }
            profileBox.addView(pad, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) })
            setOnClickListener { openedPad?.visibility = View.GONE; pad.visibility = View.VISIBLE; openedPad = pad }
        }
        val height = numberField("身高（厘米）", true)
        val weight = numberField("体重（千克）", true)
        val age = numberField("年龄（岁）", false)
        val gender = RadioGroup(this)
        val female = RwRadioButton(this).apply { id = View.generateViewId(); text = "女" }
        val male = RwRadioButton(this).apply { id = View.generateViewId(); text = "男" }
        gender.addView(female); gender.addView(male); profileBox.addView(gender)
        val savedProfile = getSharedPreferences("bia-profile", MODE_PRIVATE)
        val remembered = if (savedProfile.contains("height")) com.rawwave.core.BiaProfile(
            savedProfile.getFloat("height",170f), savedProfile.getFloat("weight",65f),
            savedProfile.getInt("age",30), savedProfile.getInt("gender",0)) else null
        (existing?.biaProfile() ?: remembered)?.let { p ->
            height.setText(p.heightCm().toString()); weight.setText(p.weightKg().toString()); age.setText(p.age().toString())
            gender.check(if (p.gender() == 0) female.id else male.id)
        }
        fun refreshProfile() { profileBox.visibility = if (bia.isChecked) View.VISIBLE else View.GONE }
        if (mfBia.isChecked || biaBoth.isChecked) bia.isChecked = true
        refreshProfile()
        bia.setOnCheckedChangeListener { _, _ -> refreshProfile() }
        mfBia.setOnCheckedChangeListener { _, checked -> if (checked) bia.isChecked = true }
        biaBoth.setOnCheckedChangeListener { _, checked -> if (checked) bia.isChecked = true }
        val choices = linkedMapOf(
            SensorCatalog.HEART_RATE to check("心率（处理结果）", true),
            SensorCatalog.ACCELEROMETER to check("加速度（原始）", true),
            SensorCatalog.PLATFORM_ACCELEROMETER to check("平台加速度（m/s²，100Hz，含重力）", true),
            SensorCatalog.LINEAR_ACCELERATION to check("线性加速度（去重力，目标25Hz）"),
            SensorCatalog.GAME_ROTATION_VECTOR to check("姿态四元数（目标25Hz）"),
            SensorCatalog.ROTATION_VECTOR to check("绝对姿态（含磁力计，目标25Hz）"),
            SensorCatalog.GEOMAGNETIC_ROTATION_VECTOR to check("地磁姿态（无陀螺仪，目标25Hz）"),
            SensorCatalog.GRAVITY to check("重力矢量（目标25Hz）"),
            SensorCatalog.WRIST_TILT_GESTURE to check("抬腕手势（仅变化时回调）"),
            SensorCatalog.PPG to check("PPG 连续 25Hz（原始绿/红外/红）"),
            SensorCatalog.SKIN_TEMPERATURE to check("皮肤温度（不是体温）"),
            SensorCatalog.EDA to check("皮肤电导 EDA（部分型号支持）"),
            SensorCatalog.MAGNETIC_FIELD to check("三轴磁场（微特斯拉，目标10Hz）"),
            SensorCatalog.LIGHT to check("环境光（lux，目标5Hz）"),
            SensorCatalog.GPS to check("GPS 轨迹（需定位权限）"),
            SensorCatalog.STEP_COUNT to check("步数（平台累计）"),
            SensorCatalog.STEP_DETECTOR to check("每步事件"),
            SensorCatalog.OFFBODY to check("佩戴状态"),
            SensorCatalog.GYROSCOPE_UNCALIBRATED to check("未校准陀螺仪"),
            SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED to check("未校准磁场"),
            SensorCatalog.PRESSURE to check("气压（hPa，目标1Hz）"),
            SensorCatalog.GYROSCOPE to check("陀螺仪角速度（目标25Hz）"))
        choices.forEach { (source, choice) ->
            if (!platformSensorSupported(source)) {
                choice.isChecked = false
                choice.isEnabled = false
                choice.text = choice.text.toString() + "（此手表不支持）"
            }
        }
        if (existing != null) {
            name.setText(existing.name())
            choices.forEach { (source, choice) -> choice.isChecked = source in existing.coreSources() && choice.isEnabled }
        }
        val postEcg = check("ECG 30秒", existing?.postEcg() ?: true)
        val postSpo2 = check("血氧 SpO₂（最多30秒）", existing?.postSpo2() ?: false)
        val postPpg = check("PPG 100Hz 30秒", existing?.postPpg() ?: false)
        val allChecks = listOf(
            preEcg, preSpo2, prePpg, bia, mfBia, biaBoth,
            postEcg, postSpo2, postPpg,
        ) + choices.values
        val selectAll = RwCheckBox(this).apply {
            text = "全选"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                allChecks.forEach { if (it.isEnabled) it.isChecked = checked }
            }
        }
        // The form-level "全选" sits above every field so it selects the whole form,
        // not just the sensor list that happens to be under it.
        controls.addView(selectAll)
        section("活动名称")
        controls.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        section("活动前")
        // Execution order: SpO2 first because it is the only pre measurement that needs the
        // watch moved above the wrist bone. The user restores the normal position once, then
        // BIA/MF-BIA, ECG and PPG run without any further watch adjustment, so the activity
        // starts from the normal position.
        controls.addView(preSpo2)
        controls.addView(bia); controls.addView(mfBia); controls.addView(biaBoth)
        controls.addView(profileBox)
        controls.addView(preEcg)
        controls.addView(prePpg)
        label("顺序：血氧 → 体成分 → ECG → PPG。血氧要先把表上移到腕骨上方，测完戴回正常位置，之后不用再动表。", controls).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
        }
        section("活动中")
        choices.values.forEach { controls.addView(it) }
        section("活动后")
        // Execution order: ECG -> PPG (normal position) -> SpO2 (watch above the wrist bone).
        // SpO2 is last so the one strap change happens at the very end of the session.
        controls.addView(postEcg); controls.addView(postPpg); controls.addView(postSpo2)
        label("顺序：ECG → PPG → 血氧。血氧把手表上移到腕骨上方，测完直接结束，不用再戴回。", controls).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
        }
        button("保存并返回", primary = true) {
            try {
                val profile = if (bia.isChecked) com.rawwave.core.BiaProfile(
                    height.text.toString().trim().toFloatOrNull() ?: error("请填写身高（厘米）"),
                    weight.text.toString().trim().toFloatOrNull() ?: error("请填写体重（千克）"),
                    age.text.toString().trim().toIntOrNull() ?: error("请填写年龄（岁）"),
                    when(gender.checkedRadioButtonId) { female.id -> 0; male.id -> 1; else -> error("请选择性别") }) else null
                val selected = choices.filterValues { it.isChecked && it.isEnabled }.keys.toMutableSet()
                val store = ActivityRecordingService.store(this)
                if (existing == null) store.createTemplate(name.text.toString(),
                    preEcg.isChecked, postEcg.isChecked, selected,
                    preSpo2.isChecked, postSpo2.isChecked, profile,
                    bia.isChecked && mfBia.isChecked, bia.isChecked && biaBoth.isChecked,
                    prePpg.isChecked, postPpg.isChecked)
                else store.updateTemplate(existing.id(), name.text.toString(),
                    preEcg.isChecked, postEcg.isChecked, selected,
                    preSpo2.isChecked, postSpo2.isChecked, profile,
                    bia.isChecked && mfBia.isChecked, bia.isChecked && biaBoth.isChecked,
                    prePpg.isChecked, postPpg.isChecked)
                if (profile != null) {
                    val remembered = savedProfile.edit().putFloat("height", profile.heightCm())
                        .putFloat("weight", profile.weightKg()).putInt("age", profile.age())
                        .putInt("gender", profile.gender()).commit()
                    if (!remembered) try {
                        app.journal?.event("BIA_PROFILE_REMEMBER_FAILED", mapOf("reason" to "commit_failed"))
                    } catch (_: Exception) {}
                }
                showActivityManagement()
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "请检查填写内容后重试。"
                RwDialogs.message(this, "没有保存", reason, confirmLabel = "继续编辑")
            }
        }
        if (existing != null) button("删除活动") { deleteTemplate(existing) }
        button("返回上一页") { confirmDiscardForm() }
    }

    private fun showReportToolsPage() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("REPORT_TOOLS")
        ensureIdle()
        mode = "REPORT_TOOLS"
        pageHeader("问题报告工具")
        button("记录问题现场", primary = true) { recordProblemScene() }
        button("离线诊断详情") { DiagnosticViewer.show(this, app) }
        button("导出问题报告到手表") { exportReportToWatch() }
        button("返回上一页") { showMorePage() }
    }

    /** Save the same symptom checklist on the watch, so both devices contribute comparable evidence. */
    private fun recordProblemScene() {
        RwDialogs.checklist(
            this,
            "记录问题现场",
            "可多选；保存后会写进问题报告，不会改动活动原始数据",
            ProblemCapture.labels,
            BooleanArray(ProblemCapture.labels.size),
            confirmLabel = "保存到问题报告",
        ) { checked ->
            val selected = ProblemCapture.keys.filterIndexed { index, _ -> checked[index] }
            try {
                val recorded = ProblemCapture.record(app, selected)
                RwDialogs.message(
                    this,
                    "已记录问题现场",
                    "ID ${recorded.captureId.take(8)}\n" +
                        "症状：${selected.joinToString("、").ifEmpty { "未选择" }}\n\n" +
                        "发送问题报告到手机或导出问题报告时会包含此记录。",
                    confirmLabel = "知道了",
                )
            } catch (e: Exception) {
                status.visibility = View.VISIBLE
                status.text = "问题现场没有保存成功：${e.message}\n请拍照保留此页。"
            }
        }
    }

    private fun sendReportToPhone() {
        idle?.close(); idle = null
        startActivity(Intent(this, ProbeActivity::class.java).putExtra("autoAction", "send"))
        finish()
    }

    /**
     * Full exit: stop the active capture (which seals data and releases the vendor sensor /
     * LED through the existing background release) and stop any sync, then close the task.
     */
    private fun exitWatchCompletely() {
        val captureActive = ActivityRecordingService.state.active
        try {
            app.journal?.event("WATCH_FULL_EXIT", mapOf(
                "captureActive" to captureActive,
                "syncRunning" to app.activitySyncStatus.running,
            ))
        } catch (_: Exception) {}
        if (captureActive) command("CANCEL")
        app.requestStopBackgroundSync("WATCH_FULL_EXIT")
        cancelAutoSync("FULL_EXIT")
        idle?.close(); idle = null
        guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishAndRemoveTask()
    }

    /**
     * Export on the watch and come back here. ProbeActivity finishes itself after the result
     * dialog when returnOnDone is set; onStart() restores this page instead of the home list.
     */
    private fun exportReportToWatch() {
        idle?.close(); idle = null
        startActivity(
            Intent(this, ProbeActivity::class.java)
                .putExtra("autoAction", "export")
                .putExtra("returnOnDone", true),
        )
    }

    private fun showLocalRecords() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("LOCAL_RECORDS")
        ensureIdle()
        mode = "LOCAL_RECORDS"
        pageHeader("本地记录")
        val records = try {
            ActivityRecordingService.store(this).historyRecords()
        } catch (e: Exception) {
            status.visibility = View.VISIBLE
            status.text = "读取活动失败：${e.message}"
            emptyList()
        }
        if (records.isEmpty()) {
            label("还没有活动记录。", controls)
        } else {
            label("按活动开始时间倒序", controls).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
            }
            records.forEach { record ->
                val card = RwCard(this)
                card.addView(RwBodyText(this).apply {
                    text = record.activityName
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = RwTheme.boldTypeface()
                    setTextColor(RwTheme.colors(this@RecordingActivity).onSurface)
                })
                card.addView(RwBodyText(this).apply {
                    // The date and the seal state do not fit on one 122 dp line; a forced break
                    // keeps "已封存" from splitting into "… · 已" + "封存" on every row.
                    text = recordTimeText(record.createdUtcMs) +
                        if (record.sealed) "\n已封存" else "\n未封存"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(RwTheme.colors(this@RecordingActivity).onSurfaceVariant)
                    setPadding(0, dp(4), 0, 0)
                })
                controls.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) })
            }
        }
        button("返回上一页") { showMorePage() }
    }

    private fun recordTimeText(utcMs: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.Instant.ofEpochMilli(utcMs).atZone(zone)
        val clock = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return if (time.toLocalDate() == java.time.LocalDate.now(zone)) {
            "今天 $clock"
        } else {
            time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }

    private fun renderSync(sync: ProbeApplication.ActivitySyncStatus) {
        if (inWatchPage()) return
        cancelAutoSync("SYNC_RUNNING")
        if (sync.running) holdIdleForSync() else ensureIdle()
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = "同步"
        measurementHeader.textSize = 28f
        status.visibility = View.VISIBLE
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
        guide.visibility = View.GONE; guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "活动数据同步\n${sync.text}\n同步由前台服务保活，不依赖亮屏；完成或失败后立即释放。\n采集文件在手机完整校验前不会从手表标记为已同步。"
        controls.removeAllViews()
        button("诊断详情（离线可拍照）") { DiagnosticViewer.show(this, app) }
        if (sync.running) button("停止同步（数据仍保留）") {
            app.requestStopBackgroundSync("WATCH_SYNC_STOP_BUTTON")
        }
    }
    private fun startPendingSync(trigger: String, retry: Int = 0) {
        cancelAutoSync("START_$trigger")
        if (ActivityRecordingService.state.active) return
        if (pendingSyncCount() == 0) {
            status.text = "没有待同步活动。"
            showHome()
            return
        }
        app.reconcileStaleSyncState()
        if (app.activeSyncOwner != null && retry < 3) {
            // A previous bounded sync may still be stopping. Ask it to stop and retry
            // instead of silently doing nothing when the user taps sync.
            app.requestStopBackgroundSync("WATCH_SYNC_RESTART")
            status.text = "正在结束上次同步，随后重新开始…"
            main.postDelayed({ startPendingSync(trigger, retry + 1) }, 600L)
            return
        }
        syncCycleSeen = true
        if (!app.startWatchActivitySync(trigger)) {
            if (!app.activitySyncStatus.running) status.text = "当前有其他工作正在进行，活动数据稍后再同步。"
        } else if (ActivitySyncPolicy.isNonBlockingHomeSync(trigger)) {
            holdIdleForSync()
            status.text = "正在后台补同步上次活动；你仍可直接选择活动开始采集。"
        } else renderSync(app.activitySyncStatus)
    }
    private fun pendingSyncCount() = try {
        ActivityArchive.pendingCount(File(filesDir, "activities"))
    } catch (_: Exception) { 0 }

    private fun isBatteryExempt(): Boolean = try {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun showBackgroundPermission() {
        RwDialogs.confirm(
            this,
            "允许后台运行",
            "手表熄屏后，系统可能暂停同步。允许原波后台运行后，大活动包可以在熄屏时继续传输。\n\n原波只在本轮同步期间保活，完成后会自行退出。",
            confirmLabel = "去设置",
            cancelLabel = "稍后",
        ) { openBackgroundSettings() }
    }

    private fun openBackgroundSettings() {
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"),
            ))
        } catch (_: Exception) {
            try {
                startActivity(Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"),
                ))
            } catch (_: Exception) {
                status.text = "当前设备没有提供后台运行设置入口，请在系统设置中查看原波。"
            }
        }
    }
    private fun scheduleAutoSync(origin: String) {
        if (inWatchPage()) return
        if (syncCycleSeen) return
        if (!app.activitySyncAttemptGuard.allowOpenAutoSync()) {
            try { app.journal?.event("ACTIVITY_OPEN_AUTO_SYNC_SUPPRESSED", mapOf(
                "reason" to "CAPTURE_FINISHED_ALREADY_ATTEMPTED")) } catch (_: Exception) {}
            return
        }
        val count = pendingSyncCount()
        if (autoSyncGate.arm(resumed, ActivityRecordingService.state.active,
                app.activitySyncStatus.running, count)) {
            main.removeCallbacks(autoSync)
            main.postDelayed(autoSync, 3000)
            try { app.journal?.event("ACTIVITY_AUTO_SYNC_ARMED", mapOf("origin" to origin,
                "pendingCount" to count)) } catch (_: Exception) {}
        }
    }
    private fun cancelAutoSync(reason: String) {
        main.removeCallbacks(autoSync)
        if (autoSyncGate.cancel()) try {
            app.journal?.event("ACTIVITY_AUTO_SYNC_CANCELLED", mapOf("reason" to reason))
        } catch (_: Exception) {}
    }
    private fun inWatchPage() = mode in setOf(
        "HOME_MORE", "MANAGE_ACTIVITIES", "EDIT_TEMPLATE", "DELETE_ACTIVITIES",
        "REPORT_TOOLS", "LOCAL_RECORDS", "SYNC_STATUS",
    )
    private fun inActivityManagement() =
        mode in setOf("EDIT_TEMPLATE", "MANAGE_ACTIVITIES", "DELETE_ACTIVITIES")
    private fun returnHome() { mode = ""; render(ActivityRecordingService.state) }
    private fun deleteTemplate(template: ActivityStore.Template) {
        RwDialogs.confirm(
            this,
            "删除活动模板",
            "删除“${template.name()}”？已采集的历史数据保留。",
            confirmLabel = "删除",
        ) {
            try {
                ActivityRecordingService.store(this).deleteTemplate(template.id())
                showActivityManagement()
            } catch(e: Exception) { status.text = "删除失败：${e.message}" }
        }
    }
    private fun prepare(t: ActivityStore.Template) {
        if (ActivityRecordingService.state.active) return
        if (app.activitySyncStatus.running && ActivitySyncPolicy.captureMayPreempt(app.activitySyncStatus.trigger)) {
            try { app.journal?.event("ACTIVITY_SYNC_PREEMPTED_BY_CAPTURE", mapOf("trigger" to app.activitySyncStatus.trigger)) }
            catch (_: Exception) {}
            app.requestStopBackgroundSync("CAPTURE_PREEMPTS_OPEN_SYNC")
        }
        val permissions = permissionsFor(t)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pending = t; requestPermissions(missing.toTypedArray(), 42) }
        else start(t)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            val t = pending; pending = null
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (t != null && allGranted) start(t)
            else RwDialogs.message(
                this,
                "没有开始活动",
                "未获得所需权限，本次没有开始。\n允许权限后可以重新点这个活动；如果不再弹出授权，请到系统设置里允许原波。",
                confirmLabel = "知道了",
            )
        }
    }
    private fun start(t: ActivityStore.Template, retry: Int = 0) {
        cancelAutoSync("CAPTURE_START")
        try {
            val app = application as ProbeApplication
            app.reconcileStaleSyncState()
            if (app.activeSyncOwner != null) {
                // Stopping a running sync is asynchronous. Give it a bounded moment to
                // release the owner instead of failing the start while cleanup is in flight.
                if (retry < 3 && app.activitySyncStatus.running &&
                    ActivitySyncPolicy.captureMayPreempt(app.activitySyncStatus.trigger)) {
                    app.requestStopBackgroundSync("CAPTURE_PREEMPTS_OPEN_SYNC")
                    status.text = "正在结束上次同步，随后开始采集…"
                    main.postDelayed({ start(t, retry + 1) }, 600L)
                    return
                }
                check(false) { "请先结束诊断传输，再开始采集" }
            }
            check(filesDir.usableSpace > 50_000_000L) { "存储不足50MB，不能安全开始记录" }
            idle?.close(); idle = null
            status.text = "正在开始，请稍候…"
            controls.removeAllViews()
            startForegroundService(Intent(this, ActivityRecordingService::class.java).setAction("START").putExtra("template", t.id()))
        } catch (e: Exception) { mode = ""; render(ActivityRecordingService.state); RwDialogs.message(
            this, "没有开始活动",
            e.message?.takeIf { it.isNotBlank() } ?: "请稍后重试；现有数据不会受影响。",
            confirmLabel = "知道了",
        ) }
    }
    private fun permissionsFor(t: ActivityStore.Template): List<String> {
        val result = linkedSetOf<String>()
        t.coreSources().forEach { sensor ->
            when {
                sensor == SensorCatalog.GPS -> {
                    result += Manifest.permission.ACCESS_FINE_LOCATION
                    result += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                sensor == SensorCatalog.LIGHT -> Unit
                sensor == SensorCatalog.OFFBODY -> Unit
                sensor == SensorCatalog.PLATFORM_ACCELEROMETER -> Unit
                sensor == SensorCatalog.GYROSCOPE_UNCALIBRATED || sensor == SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED -> Unit
                sensor == SensorCatalog.MAGNETIC_FIELD || sensor == SensorCatalog.PRESSURE || sensor == SensorCatalog.ACCELEROMETER || sensor == SensorCatalog.GYROSCOPE || sensor == SensorCatalog.STEP_COUNT || sensor == SensorCatalog.STEP_DETECTOR -> result += Manifest.permission.ACTIVITY_RECOGNITION
                Build.VERSION.SDK_INT < 36 -> result += Manifest.permission.BODY_SENSORS
                sensor == SensorCatalog.HEART_RATE -> result += "android.permission.health.READ_HEART_RATE"
                sensor == SensorCatalog.SKIN_TEMPERATURE -> result += "android.permission.health.READ_SKIN_TEMPERATURE"
                sensor == SensorCatalog.PPG || sensor == SensorCatalog.EDA ->
                    result += "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
            }
        }
        if (t.ecg() || t.bia() || t.ppg()) result += if (Build.VERSION.SDK_INT < 36) Manifest.permission.BODY_SENSORS
            else "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        if (t.spo2()) result += if (Build.VERSION.SDK_INT < 36) Manifest.permission.BODY_SENSORS
            else "android.permission.health.READ_OXYGEN_SATURATION"
        return result.toList()
    }
    private fun sensorLabel(sensor: String) = when (sensor) {
        SensorCatalog.HEART_RATE -> "心率"
        SensorCatalog.ACCELEROMETER -> "加速度"
        SensorCatalog.PLATFORM_ACCELEROMETER -> "平台加速度"
        SensorCatalog.ACCELEROMETER_UNCALIBRATED -> "未校准加速度"
        SensorCatalog.LINEAR_ACCELERATION -> "线性加速度"
        SensorCatalog.GAME_ROTATION_VECTOR -> "姿态四元数"
        SensorCatalog.ROTATION_VECTOR -> "绝对姿态"
        SensorCatalog.GEOMAGNETIC_ROTATION_VECTOR -> "地磁姿态"
        SensorCatalog.GRAVITY -> "重力矢量"
        SensorCatalog.WRIST_TILT_GESTURE -> "抬腕手势"
        SensorCatalog.PPG -> "PPG 25Hz 连续"
        SensorCatalog.PPG_ON_DEMAND -> "PPG 100Hz 短测"
        SensorCatalog.SKIN_TEMPERATURE -> "皮肤温度"
        SensorCatalog.EDA -> "皮肤电导"
        SensorCatalog.MAGNETIC_FIELD -> "三轴磁场"
        SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED -> "未校准磁场"
        SensorCatalog.LIGHT -> "环境光"
        SensorCatalog.GPS -> "GPS 轨迹"
        SensorCatalog.STEP_COUNT -> "步数（平台累计）"
        SensorCatalog.STEP_DETECTOR -> "每步事件"
        SensorCatalog.OFFBODY -> "佩戴状态"
        SensorCatalog.PRESSURE -> "气压"
        SensorCatalog.GYROSCOPE -> "陀螺仪"
        SensorCatalog.GYROSCOPE_UNCALIBRATED -> "未校准陀螺仪"
        else -> sensor
    }
    private fun confirmStopCapture() {
        if (stopDialog?.isShowing == true) return
        val dialog = RwDialogs.confirm(
            this,
            "结束并保存",
            "结束并保存已有数据？\n提前结束不会继续活动后短测。若要按模板完成，请选“继续记录”，再点“结束活动”。",
            confirmLabel = "结束并保存",
            cancelLabel = "继续记录",
        ) { command("CANCEL") }
        dialog.setOnDismissListener { if (stopDialog === dialog) stopDialog = null }
        stopDialog = dialog
    }
    private fun command(action: String) {
        startService(Intent(this, ActivityRecordingService::class.java).setAction(action))
    }
    private fun idleRuntime(): DeadlineRuntime {
        val current = idle
        if (current != null && !current.isClosed()) return current
        return DeadlineRuntime { reason, evidence -> onIdleDeadline(reason, evidence) }
            .also { it.touchIdle(); idle = it }
    }
    private fun ensureIdle() {
        if (ActivityRecordingService.state.active) return
        // Home may close itself after inactivity; a child page must not disappear while the
        // user is reading it or editing a form. The explicit 完全退出 button remains available.
        if (inWatchPage()) { idle?.close(); idle = null; return }
        if (app.activitySyncStatus.running) { holdIdleForSync(); return }
        val current = idle
        if (current == null || current.isClosed()) idleRuntime() else current.resumeIdle()
    }
    /** A transfer owns the app: suspend only the UI idle countdown, never its own lease. */
    private fun holdIdleForSync() {
        if (ActivityRecordingService.state.active || !app.activitySyncStatus.running) return
        val runtime = idleRuntime()
        if (runtime.isIdleHeld()) return
        if (runtime.holdIdle()) {
            try { app.journal?.event("ACTIVITY_UI_IDLE_HELD_FOR_SYNC",
                runtime.evidence() + mapOf("trigger" to app.activitySyncStatus.trigger)) } catch (_: Exception) {}
        } else {
            try { app.journal?.event("ACTIVITY_UI_IDLE_HOLD_FAILED_FOR_SYNC",
                runtime.evidence() + mapOf("trigger" to app.activitySyncStatus.trigger)) } catch (_: Exception) {}
        }
    }
    /** Re-arm one fresh idle window only after the transfer is no longer running. */
    private fun releaseIdleAfterSync() {
        if (ActivityRecordingService.state.active) { idle?.close(); idle = null; return }
        if (app.activitySyncStatus.running) return
        val runtime = idle
        if (runtime == null || runtime.isClosed()) {
            idleRuntime()
            try { app.journal?.event("ACTIVITY_UI_IDLE_REARMED_AFTER_SYNC", idle?.evidence().orEmpty()) } catch (_: Exception) {}
            return
        }
        if (runtime.releaseIdle()) {
            try { app.journal?.event("ACTIVITY_UI_IDLE_RELEASED_AFTER_SYNC", runtime.evidence()) } catch (_: Exception) {}
        } else {
            runtime.touchIdle()
        }
    }
    private fun hideTimer() {
        if (::timerLabel.isInitialized) timerLabel.visibility = View.GONE
        if (::activityTimer.isInitialized) activityTimer.visibility = View.GONE
        if (::stepCountdown.isInitialized) stepCountdown.visibility = View.GONE
        if (::stepInstruction.isInitialized) stepInstruction.visibility = View.GONE
        // The success banner has its own timeout; render() must not erase a final result.
    }
    private fun countdownText(s: ActivityRecordingService.Companion.State, mode: String, contact: Boolean): String {
        val seconds = if (mode == "ECG" && contact && s.remaining > 0) s.remaining else s.phaseRemainingSeconds
        if (seconds <= 0) return ""
        return when {
            s.phase.startsWith("READY_SPO2") -> "准备血氧 · ${seconds} 秒后自动开始"
            s.phase.startsWith("WAIT_BIA") -> "接触中断 · ${seconds} 秒后自动重试"
            mode == "ECG" && !contact -> "等待接触 · ${seconds} 秒后停止"
            else -> "剩余 ${seconds} 秒"
        }
    }

    // Keep every guide instruction to two short lines: on the 480 px screen the countdown and
    // the 84 dp animation leave room for exactly two lines before the round edge cuts text.
    private fun instructionsFor(s: ActivityRecordingService.Companion.State, mode: String, contact: Boolean): String =
        when (mode) {
            "ECG" -> if (contact)
                "已检测到接触\n手臂放稳，不要说话"
            else
                "前臂放桌，指尖盖住上键\n手指不要碰表框，保持不动"
            "BIA" -> {
                // Two modes add an item line above; keep the instruction to one line then, so the
                // guide still fits inside the round screen (the finger hint is the canvas caption).
                val item = if (s.biaModeCount > 1) "第 ${s.biaModeNumber}/${s.biaModeCount} 项：${s.biaMode.ifBlank { "体成分" }}\n" else ""
                item + if (contact)
                    if (s.biaModeCount > 1) "双指已接触，保持不动" else "双指接触已确认\n保持不动，不要说话"
                else
                    if (s.biaModeCount > 1) "戴回正常位置，双臂抬到胸口" else "手表戴回正常位置\n双臂抬到胸口，两指触键"
            }
            "SPO2" -> "手表上移到腕骨上方\n表带贴合，手肘放桌"
            "PPG" -> "正常佩戴，手臂放稳\n无倒数，采集30秒后继续"
            else -> ""
        }

    private fun formatElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%02d:%02d".format(minutes, secs)
    }
    private fun label(text: String, parent: LinearLayout = body) = RwBodyText(this).apply {
        this.text = text; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
        parent.addView(this)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) {
        val view = if (primary) RwPrimaryButton(this) else RwSecondaryButton(this)
        view.text = text
        view.setOnClickListener { cancelAutoSync("BUTTON_${text.take(40)}"); action() }
        controls.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
    }
    /** Action plus a second line of live state; the watch is too narrow for "名称 · 状态". */
    private fun stackedButton(title: String, detail: String, action: () -> Unit) {
        val view = RwStackedButton(this).apply {
            text = title
            detailText = detail
            contentDescription = "$title，$detail"
        }
        view.setOnClickListener { cancelAutoSync("BUTTON_$title"); action() }
        controls.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
    }
    private fun platformSensorType(sensor: String): Int? = when(sensor) {
        SensorCatalog.GYROSCOPE -> android.hardware.Sensor.TYPE_GYROSCOPE
        SensorCatalog.GYROSCOPE_UNCALIBRATED -> android.hardware.Sensor.TYPE_GYROSCOPE_UNCALIBRATED
        SensorCatalog.PLATFORM_ACCELEROMETER -> android.hardware.Sensor.TYPE_ACCELEROMETER
        SensorCatalog.ACCELEROMETER_UNCALIBRATED -> android.hardware.Sensor.TYPE_ACCELEROMETER_UNCALIBRATED
        SensorCatalog.LINEAR_ACCELERATION -> android.hardware.Sensor.TYPE_LINEAR_ACCELERATION
        SensorCatalog.GAME_ROTATION_VECTOR -> android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorCatalog.ROTATION_VECTOR -> android.hardware.Sensor.TYPE_ROTATION_VECTOR
        SensorCatalog.GEOMAGNETIC_ROTATION_VECTOR -> android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        SensorCatalog.GRAVITY -> android.hardware.Sensor.TYPE_GRAVITY
        SensorCatalog.WRIST_TILT_GESTURE -> 26 // TYPE_WRIST_TILT_GESTURE is Wear OS, not a public SDK constant
        SensorCatalog.MAGNETIC_FIELD -> android.hardware.Sensor.TYPE_MAGNETIC_FIELD
        SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED -> android.hardware.Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
        SensorCatalog.PRESSURE -> android.hardware.Sensor.TYPE_PRESSURE
        SensorCatalog.LIGHT -> android.hardware.Sensor.TYPE_LIGHT
        SensorCatalog.STEP_COUNT -> android.hardware.Sensor.TYPE_STEP_COUNTER
        SensorCatalog.STEP_DETECTOR -> android.hardware.Sensor.TYPE_STEP_DETECTOR
        SensorCatalog.OFFBODY -> android.hardware.Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT
        else -> null
    }
    private fun platformSensorSupported(sensor: String): Boolean {
        val type = platformSensorType(sensor) ?: return true
        return try {
            getSystemService(android.hardware.SensorManager::class.java)?.getDefaultSensor(type) != null
        } catch (_: Exception) { false }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}

/** Code-drawn measurement guidance: schematic watch, contact state and concise captions. */
private class HomeKeyGuide(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = RwTheme.boldTypeface() }
    private val colors = RwTheme.colors(context)
    private val skin = Color.rgb(242, 197, 155)
    var animateContact = false
        set(value) { field = value; if (value) invalidate() }
    var biaMode = false
    var mode = "ECG"
        set(value) { if (field != value) { field = value; invalidate() } }
    var contactDetected = false
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            "BIA" -> drawBia(canvas)
            "SPO2" -> drawSpo2(canvas)
            "PPG" -> drawPpg(canvas)
            else -> drawEcg(canvas)
        }
        if ((animateContact || contactDetected) && isShown) postInvalidateDelayed(90)
    }

    private fun caption(canvas: Canvas, text: String, color: Int = colors.onSurface) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = height * .125f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, width * .5f, height * .96f, paint)
    }

    private fun contactBadge(canvas: Canvas) {
        if (!contactDetected) return
        paint.style = Paint.Style.FILL
        paint.color = colors.success
        paint.textSize = height * .105f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("✓ 已检测到接触", width * .5f, height * .15f, paint)
    }

    private fun drawEcg(canvas: Canvas) {
        val x = width * .42f; val y = height * .48f; val r = height * .26f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = colors.outline
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL; paint.color = colors.primary
        canvas.drawRoundRect(x+r, y-r*.6f, x+r+12, y-r*.15f, 4f, 4f, paint)
        val pulse = if (animateContact) ((SystemClock.uptimeMillis() % 1200) / 1200f) else 0f
        paint.color = skin
        canvas.drawRoundRect(x+r+15+pulse*15, y-r*.6f, width*.94f, y-r*.1f, 12f, 12f, paint)
        if (contactDetected) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = colors.success
            canvas.drawCircle(x, y, r + 7f, paint)
            paint.style = Paint.Style.FILL
        }
        contactBadge(canvas)
        caption(canvas, if (contactDetected) "保持接触，不要说话" else "指尖盖住上键，不要按",
            if (contactDetected) colors.success else colors.onSurface)
    }

    /** Watch body, two side keys and two pulsing fingertips: middle finger upper, ring finger lower. */
    private fun drawBia(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bodyLeft = w*.05f; val bodyRight = w*.33f
        val bodyTop = h*.22f; val bodyBottom = h*.84f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = colors.outline
        canvas.drawRoundRect(bodyLeft, bodyTop, bodyRight, bodyBottom, 12f, 12f, paint)
        canvas.drawLine(0f, h*.5f, bodyLeft, h*.5f, paint)
        canvas.drawLine(bodyRight, h*.5f, bodyRight + w*.02f, h*.5f, paint)
        paint.style = Paint.Style.FILL; paint.color = colors.primary
        val keyTop = h*.36f; val keyBottom = h*.67f; val keyHalf = h*.09f
        val keyRight = bodyRight + w*.03f
        canvas.drawRoundRect(bodyRight, keyTop-keyHalf, keyRight, keyTop+keyHalf, 4f, 4f, paint)
        canvas.drawRoundRect(bodyRight, keyBottom-keyHalf, keyRight, keyBottom+keyHalf, 4f, 4f, paint)
        val pulse = if (animateContact) ((SystemClock.uptimeMillis() % 1200) / 1200f) else 0f
        val fingerX = keyRight + w*.075f + pulse*w*.018f
        paint.color = skin
        canvas.drawOval(fingerX-w*.055f, keyTop-h*.13f, fingerX+w*.055f, keyTop+h*.13f, paint)
        canvas.drawOval(fingerX-w*.045f, keyBottom-h*.12f, fingerX+w*.065f, keyBottom+h*.12f, paint)
        if (animateContact || contactDetected) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            paint.color = if (contactDetected) colors.success else Color.argb(
                (210*(1f-pulse)).toInt().coerceIn(0,255),
                Color.red(colors.primary), Color.green(colors.primary), Color.blue(colors.primary),
            )
            canvas.drawCircle(keyRight, keyTop, h*.11f + pulse*h*.12f, paint)
            canvas.drawCircle(keyRight, keyBottom, h*.11f + pulse*h*.12f, paint)
            paint.style = Paint.Style.FILL
        }
        contactBadge(canvas)
        caption(canvas, if (contactDetected) "双指接触已确认，保持不动" else "中指上键 · 无名指下键，两指不相触",
            if (contactDetected) colors.success else colors.onSurfaceVariant)
    }

    private fun drawSpo2(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Arm resting at heart height.
        paint.style = Paint.Style.FILL; paint.color = skin
        canvas.drawRoundRect(w*.16f, h*.28f, w*.48f, h*.86f, 26f, 26f, paint)
        canvas.drawCircle(w*.32f, h*.52f, h*.13f, paint)
        paint.color = colors.primary
        canvas.drawCircle(w*.32f, h*.52f, h*.085f, paint)
        paint.color = colors.surface
        canvas.drawCircle(w*.32f, h*.52f, h*.045f, paint)
        // Heart symbol.
        paint.color = colors.primary
        canvas.drawCircle(w*.69f, h*.36f, h*.075f, paint)
        canvas.drawCircle(w*.80f, h*.36f, h*.075f, paint)
        val path = android.graphics.Path()
        path.moveTo(w*.615f, h*.40f); path.lineTo(w*.745f, h*.62f); path.lineTo(w*.875f, h*.40f); path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = colors.success
        canvas.drawLine(w*.47f, h*.55f, w*.63f, h*.55f, paint)
        canvas.drawLine(w*.60f, h*.50f, w*.63f, h*.55f, paint)
        canvas.drawLine(w*.60f, h*.60f, w*.63f, h*.55f, paint)
        caption(canvas, "手臂放稳，与心脏同高")
    }

    private fun drawPpg(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.color = skin
        canvas.drawRoundRect(w*.20f, h*.28f, w*.48f, h*.86f, 26f, 26f, paint)
        paint.color = colors.primary
        canvas.drawCircle(w*.34f, h*.53f, h*.14f, paint)
        paint.color = colors.surface
        canvas.drawCircle(w*.34f, h*.53f, h*.075f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        for (i in 0..3) {
            val y = h * (.40f + i * .08f)
            paint.color = Color.argb(190 - i*25, Color.red(colors.success), Color.green(colors.success), Color.blue(colors.success))
            canvas.drawCircle(w*.58f, y, h*.07f, paint)
            canvas.drawCircle(w*.70f, y, h*.07f, paint)
        }
        paint.style = Paint.Style.FILL
        caption(canvas, "正常佩戴即可，不用调表带")
    }
}
