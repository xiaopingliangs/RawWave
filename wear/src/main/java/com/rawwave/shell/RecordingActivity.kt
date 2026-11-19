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
import com.rawwave.core.ActivityRetention
import com.rawwave.core.ActivityStore
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.AutoSyncGate
import com.rawwave.core.SensorCatalog
import com.rawwave.core.SyncStopScope
import com.rawwave.core.WatchLayoutPolicy
import com.rawwave.core.WorkBudget
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
    private lateinit var progressDetail: TextView
    private lateinit var measurementText: TextView
    private lateinit var contentSlot: FrameLayout
    private lateinit var progressBox: LinearLayout
    private lateinit var bottomFooter: LinearLayout
    private lateinit var footerStart: RwPrimaryButton
    private lateinit var footerSkip: RwSecondaryButton
    private lateinit var topPane: ScrollView
    private lateinit var detailsPane: LinearLayout
    private var adaptive: WatchLayoutPolicy.Metrics? = null
    private var pagerPages: List<String> = emptyList()
    private var pagerIndex = 0
    private var pagerKey = ""
    /** The top title alternates the item name and the cooperation status until contact. */
    private var headerName = ""
    private var headerStatus = ""
    private var headerShowStatus = false
    private var headerAltRunning = false
    private var syncExitHold = false
    private var syncExitRunnable: Runnable? = null
    private var reviewDialog: android.app.Dialog? = null
    private var reviewDialogPhase = ""
    
    private lateinit var controls: LinearLayout
    private var pending: ActivityStore.Template? = null
    private var stopDialog: android.app.Dialog? = null
    private var idle: DeadlineRuntime? = null
    private var mode = ""
    private var resumed = false
    private var lastTouchKeepaliveMs = 0L
    private var lastRenderedPhase = ""
    private var lastRenderedActive = false
    private var syncCycleSeen = false
    private var noticeChecked = false
    private var bannerIsNotice = false
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
    /**
     * The sync session is app-owned, but its service can disappear without a callback. A periodic
     * reconcile is the only thing that recovers the visible "同步中" page in that case; the
     * watchdog does not wake the device and stops as soon as the status is no longer running.
     */
    private val syncWatchdog = object : Runnable {
        override fun run() {
            if (!app.activitySyncStatus.running) {
                autoExitAfterSync("WATCHDOG")
                return
            }
            app.reconcileStaleSyncState()
            if (!app.activitySyncStatus.running) {
                if (autoExitAfterSync("WATCHDOG_RECONCILE")) return
                if (!resumed) return
                if (mode == "SYNC_STATUS") showSyncStatus()
                else if (!inWatchPage()) render(ActivityRecordingService.state)
                return
            }
            main.postDelayed(this, SYNC_WATCHDOG_MS)
        }
    }
    private val syncListener: (ProbeApplication.ActivitySyncStatus) -> Unit = syncListener@{ sync ->
        if (sync.running) armSyncWatchdog() else cancelSyncWatchdog()
        val blocking = !ActivitySyncPolicy.isNonBlockingHomeSync(sync.trigger)
        // A capture-finished/manual sync owns the page. On success show a short confirmation
        // and then close; on failure stay on the status page and let the bounded idle exit.
        if (!sync.running && blocking && sync.success && app.pendingActivitySyncExit.get()) {
            app.pendingActivitySyncExit.set(false)
            syncCycleSeen = false
            if (resumed) showSyncSuccessThenExit() else closeWatchTask()
            return@syncListener
        }
        if (inWatchPage() && !ActivityRecordingService.state.active) {
            if (!sync.running) {
                releaseIdleAfterSync()
                if (mode == "SYNC_STATUS") showSyncStatus()
            }
            return@syncListener
        }
        syncCycleSeen = true
        if (!resumed) {
            // The screen may be off while the bounded sync finishes. Do not touch views here;
            // a failed blocking sync still gets its 80 s idle exit when the page returns.
            if (!sync.running && blocking && !sync.success) releaseIdleAfterSync()
            return@syncListener
        }
        if (!blocking) {
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
        } else if (sync.running) {
            // Capture-finished/manual sync: show the sync page and keep it lit while bytes move.
            renderSync(sync)
        } else if (sync.success) {
            showSyncSuccessThenExit()
        } else {
            renderSyncFailure(sync)
        }
    }
    /**
     * Close the watch task only after a successful capture-finished/manual sync. The one-shot
     * is consumed regardless of the page currently shown, so a child page cannot swallow the
     * exit and leave the watch sitting on "同步中" after the phone has committed the data.
     */
    private fun autoExitAfterSync(origin: String): Boolean {
        if (ActivityRecordingService.state.active || app.activitySyncStatus.running) return false
        if (!app.pendingActivitySyncExit.compareAndSet(true, false)) return false
        try { app.journal?.event("ACTIVITY_SYNC_AUTO_EXIT", mapOf(
            "origin" to origin, "mode" to mode, "resumed" to resumed,
            "trigger" to app.activitySyncStatus.trigger,
            "success" to app.activitySyncStatus.success)) } catch (_: Exception) {}
        closeWatchTask()
        return true
    }
    private val listener: (ActivityRecordingService.Companion.State) -> Unit = { render(it) }
    private val successListener: (String, String) -> Unit = { kind, text ->
        if (resumed && ::successBanner.isInitialized) {
            successBanner.text = "✓ $kind 成功 · $text"
            successBanner.setTone(RwTheme.colors(this).primaryGradientEnd, Color.WHITE)
            successBanner.visibility = View.VISIBLE
            bannerIsNotice = false
            main.removeCallbacks(hideSuccess)
            // Keep the result visible long enough after the final short measurement.
            main.postDelayed(hideSuccess, 4500)
        }
    }
    private val hideSuccess = Runnable {
        if (::successBanner.isInitialized) successBanner.visibility = View.GONE
        bannerIsNotice = false
    }
    /**
     * Instruction pages replace each other every 5 seconds. This is deliberately a page
     * replacement, not a scrolling animation: the watch stays still and the text changes.
     */
    private val pagerTick = object : Runnable {
        override fun run() {
            if (pagerPages.isEmpty() || !::measurementText.isInitialized ||
                measurementText.visibility != View.VISIBLE) return
            pagerIndex = (pagerIndex + 1) % pagerPages.size
            measurementText.text = pagerPages[pagerIndex]
            main.postDelayed(this, PAGE_MS)        }
    }
    /**
     * The top title is the only status position: it alternates the item name and the
     * cooperation state every 3 seconds. Once contact/wear is proven, it freezes on the
     * status so the confirmation stays visible.
     */
    private val headerAltTick = object : Runnable {
        override fun run() {
            if (!headerAltRunning) return
            headerShowStatus = !headerShowStatus
            paintHeaderAlt()
            main.postDelayed(this, ALT_MS)
        }
    }
    private fun showPager(pages: List<String>, key: String) {
        if (pages.isEmpty()) { stopPager(); return }
        if (key == pagerKey && measurementText.visibility == View.VISIBLE) return
        pagerKey = key
        pagerPages = pages
        pagerIndex = 0
        main.removeCallbacks(pagerTick)
        measurementText.text = pages[0]
        measurementText.visibility = View.VISIBLE
        if (pages.size > 1) main.postDelayed(pagerTick, PAGE_FIRST_MS)
    }
    private fun stopPager() {
        main.removeCallbacks(pagerTick)
        pagerPages = emptyList()
        pagerKey = ""
        pagerIndex = 0
        if (::measurementText.isInitialized) measurementText.visibility = View.GONE
    }
    private fun updateHeaderAlt(name: String, status: String, freeze: Boolean) {
        headerName = name
        headerStatus = status
        if (status.isEmpty() || name.isEmpty()) {
            stopHeaderAlt()
            if (::measurementHeader.isInitialized) measurementHeader.text = name
            return
        }
        if (freeze) {
            main.removeCallbacks(headerAltTick)
            headerAltRunning = false
            headerShowStatus = true
            paintHeaderAlt()
            return
        }
        if (!headerAltRunning) {
            headerAltRunning = true
            headerShowStatus = false
            main.removeCallbacks(headerAltTick)
            main.postDelayed(headerAltTick, ALT_MS)
        }
        paintHeaderAlt()
    }
    private fun paintHeaderAlt() {
        if (!::measurementHeader.isInitialized) return
        measurementHeader.text = if (headerShowStatus) headerStatus else headerName
    }
    private fun stopHeaderAlt() {
        main.removeCallbacks(headerAltTick)
        headerAltRunning = false
        headerShowStatus = false
    }
    /** A cooperating stage timed out without a value and moved on by itself. Not an error. */
    private val autoAdvanceListener: (String, String) -> Unit = { kind, text ->
        if (resumed && ::successBanner.isInitialized) {
            val colors = RwTheme.colors(this)
            successBanner.text = "↷ $kind · " +
                (if (text.contains("没有")) "没关系，$text" else text)
            successBanner.setTone(colors.surfaceVariant, colors.warning)
            successBanner.visibility = View.VISIBLE
            bannerIsNotice = false
            main.removeCallbacks(hideSuccess)
            main.postDelayed(hideSuccess, 6000)
        }
    }
    private val idleExitListener: () -> Unit = { finishIdle("ECG_IDLE_STOP") }
    private fun finishIdle(reason: String) {
        cancelAutoSync(reason)
        idle?.close(); idle = null
        closeWatchTask()
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
            setPadding(inset, 0, inset, 0); setBackgroundColor(colors.background)
        }
        successBanner = RwStatusPill(this).apply {
            visibility = View.GONE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER
        }
        // Title first: on the measurement pages the user must know which item is running.
        measurementHeader = label("")
        measurementHeader.setTextColor(colors.onSurface)
        measurementHeader.typeface = RwTheme.displayTypeface()
        measurementHeader.setPadding(0, 0, 0, 0)
        // A long title must shrink rather than run under the round bezel. The policy also
        // supplies a chord-safe width; auto-size is the second safety net.
        measurementHeader.setAutoSizeTextTypeUniformWithConfiguration(
            11, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        // One fixed content slot: instruction pages before contact, progress/result after.
        // Keeping both in the same slot prevents the page from jumping when the pager stops.
        contentSlot = FrameLayout(this)
        body.addView(contentSlot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(168),
        ))
        measurementText = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = RwTheme.boldTypeface()
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
        }
        contentSlot.addView(measurementText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentSlot.addView(progressBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        stepCountdown = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = RwTheme.displayTypeface()
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            setTextColor(colors.primary)
            setPadding(0, 0, 0, dp(2))
            visibility = View.GONE
        }
        progressBox.addView(stepCountdown, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        progressDetail = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurfaceVariant)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        progressBox.addView(progressDetail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
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
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls)
        detailsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The body already owns the round-safe horizontal inset. Adding it again here made
            // the home and sync pages half-width, which is what cut their text.
            setPadding(0, dp(4), 0, 0)
        }
        status = label("原波 · 活动采集", detailsPane)
        topPane = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            isVerticalScrollBarEnabled = false
            clipToPadding = true
            addView(body)
        }
        body.addView(detailsPane)
        // A fixed footer keeps the escape/start action near the bottom edge, where it is
        // always reachable without scrolling through the instruction text.
        val root = FrameLayout(this)
        root.addView(topPane, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        bottomFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(inset, 0, inset, dp(10))
            setBackgroundColor(colors.background)
            visibility = View.GONE
        }
        footerStart = RwPrimaryButton(this).apply {
            text = "开始测量"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40)
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener {
                cancelAutoSync("BUTTON_START_SPO2")
                command("START_SPO2")
            }
        }
        bottomFooter.addView(footerStart, LinearLayout.LayoutParams(dp(78), dp(40)).apply {
            marginEnd = dp(6)
        })
        footerSkip = RwSecondaryButton(this).apply {
            text = "跳过"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(36)
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener {
                // A small, always-reachable escape for the current short measurement.
                when (ActivityRecordingService.state.shortSensor) {
                    "BIA" -> command("SKIP_BIA")
                    "ECG" -> command("SKIP_ECG")
                    "SPO2" -> command("SKIP_SPO2")
                    "PPG_ON_DEMAND" -> command("SKIP_PPG")
                }
            }
        }
        bottomFooter.addView(footerSkip, LinearLayout.LayoutParams(dp(78), dp(36)))
        root.addView(bottomFooter, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        // The success pill overlays the top instead of consuming one of the percentage zones.
        root.addView(successBanner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(2) })
        setContentView(root)
        root.post {
            if (isFinishing || isDestroyed) return@post
            applyAdaptiveLayout(root.width, root.height)
            // The first render can run before the window is measured. Re-render once the real
            // 480x480 (or 438x438) size is known so the circle-safe zones are applied.
            if (!inWatchPage()) render(ActivityRecordingService.state)
        }
        ActivityRecordingService.idleExitObserver = idleExitListener
        // Keep the completion observer alive across onStop: a watch screen can turn off during
        // the bounded sync, and the success must still close the task instead of waiting for a
        // later manual launch.
        app.activitySyncObserver = syncListener
        if (autoExitAfterSync("ON_CREATE")) return
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
                if (app.activitySyncStatus.running) moveTaskToBack(true) else closeWatchTask()
            }
        })
    }
    override fun onStart() {
        super.onStart(); resumed = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ActivityRecordingService.observer = listener
        ActivityRecordingService.successObserver = successListener
        ActivityRecordingService.autoAdvanceObserver = autoAdvanceListener
        // A fresh open explains an automatic no-presence exit; the terminal exit path also
        // renders the home once, so it must not consume the notice itself.
        showCaptureNoticeOnce()
        // A child screen (problem-report export, diagnostics) may have replaced the app-level
        // observer and cleared it on exit. Re-register before restoring the page.
        app.activitySyncObserver = syncListener
        if (autoExitAfterSync("ON_START")) return
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
        if (app.activitySyncStatus.running) armSyncWatchdog() else cancelSyncWatchdog()
        scheduleAutoSync("ON_START")
    }
    /**
     * singleTask keeps the launcher from stacking a second RecordingActivity. Tapping the icon
     * again should bring the existing task home instead of leaving the user on a subpage.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ActivityRecordingService.state.active) return
        app.reconcileStaleSyncState()
        if (inWatchPage()) returnHome()
        else if (!app.activitySyncStatus.running) { mode = ""; render(ActivityRecordingService.state) }
        if (app.activitySyncStatus.running) armSyncWatchdog()
        scheduleAutoSync("ON_NEW_INTENT")
    }
    override fun onPause() {
        // The display timeout belongs to the system; clear a flag left by an older build or a
        // previous measurement path as soon as this screen leaves the foreground.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onStop() {
        resumed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ActivityRecordingService.observer === listener) ActivityRecordingService.observer = null
        if (ActivityRecordingService.successObserver === successListener) ActivityRecordingService.successObserver = null
        if (ActivityRecordingService.autoAdvanceObserver === autoAdvanceListener) ActivityRecordingService.autoAdvanceObserver = null
        cancelAutoSync("ON_STOP")
        stopPager()
        stopHeaderAlt()
        dismissReviewDialog()
        // Do not count screen-off time against the UI idle deadline. Closing the activity task
        // here made a screen-on land on the watch face instead of the activity screen.
        idle?.close(); idle = null
        super.onStop()
    }
    override fun onDestroy() {
        if (ActivityRecordingService.idleExitObserver === idleExitListener) ActivityRecordingService.idleExitObserver = null
        if (ActivityRecordingService.successObserver === successListener) ActivityRecordingService.successObserver = null
        if (ActivityRecordingService.autoAdvanceObserver === autoAdvanceListener) ActivityRecordingService.autoAdvanceObserver = null
        if (app.activitySyncObserver === syncListener) app.activitySyncObserver = null
        stopDialog?.dismiss(); stopDialog = null
        dismissReviewDialog()
        stopPager()
        main.removeCallbacks(hideSuccess)
        syncExitRunnable?.let { main.removeCallbacks(it) }
        syncExitRunnable = null
        cancelSyncWatchdog()
        cancelAutoSync("ON_DESTROY"); idle?.close(); super.onDestroy()
    }
    /**
     * The SpO2 measurement is started from the on-screen button near the bottom; the watch's
     * upper side key is deliberately not intercepted because it can lock the screen or close
     * the app on this device.
     */
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
    /**
     * Compute the round-safe zones from the actual window. The same fractions serve the 1.47"
     * 480x480 Watch9 44mm, the 1.34" 438x438 40mm, a smaller legacy panel and a large font scale.
     * Every band is additionally checked against the circle chord, so the round bezel never cuts
     * a title, an instruction line or a button.
     */
    private fun applyAdaptiveLayout(width: Int, height: Int) {
        val m = try {
            WatchLayoutPolicy.measure(width, height, resources.displayMetrics.density,
                resources.configuration.fontScale)
        } catch (_: Exception) { return }
        adaptive = m
        val inset = (width * .16f).toInt()
        body.setPadding(inset, 0, inset, 0)
        measurementHeader.layoutParams = LinearLayout.LayoutParams(
            m.headerWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        measurementHeader.setPadding(0, 0, 0, 0)
        measurementHeader.gravity = Gravity.CENTER
        measurementHeader.includeFontPadding = false
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m.contentWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        contentSlot.minimumHeight = 0
        measurementText.includeFontPadding = false
        (measurementText.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = FrameLayout.LayoutParams.MATCH_PARENT
            measurementText.layoutParams = it
        }
        val minSp = m.instructionMinSp.toInt().coerceAtLeast(1)
        val maxSp = m.instructionMaxSp.toInt().coerceAtLeast(minSp + 1)
        measurementText.setAutoSizeTextTypeUniformWithConfiguration(
            minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, m.percentSp)
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, m.detailSp)
        footerStart.layoutParams = LinearLayout.LayoutParams(m.buttonWidthPx, m.buttonHeightPx)
            .apply { marginEnd = m.buttonGapPx }
        footerSkip.layoutParams = LinearLayout.LayoutParams(m.buttonWidthPx, m.buttonHeightPx)
        footerStart.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, m.buttonSp)
        footerSkip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, m.buttonSp)
        bottomFooter.setPadding(inset, 0, inset, m.bottomGapPx)
        successBanner.maxWidth = m.contentWidthPx
        (successBanner.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = m.contentWidthPx
            it.topMargin = m.roundSafeInsetPx
            successBanner.layoutParams = it
        }
        // Default to the scrollable-page layout; a measurement page switches to its own zones.
        useRoundPageLayout()
    }

    /** Home, forms, sync and other scrollable pages: keep content inside the circle. */
    private fun useRoundPageLayout() {
        val m = adaptive ?: return
        val inset = (resources.displayMetrics.widthPixels * .16f).toInt()
        body.setPadding(inset, 0, inset, 0)
        measurementHeader.layoutParams = LinearLayout.LayoutParams(
            m.headerWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m.contentWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        topPane.setPadding(0, m.roundSafeInsetPx, 0, m.roundSafeInsetPx)
        topPane.clipToPadding = true
    }

    /**
     * The sync page has a wrap-content header above a fixed content slot. Subtract half the
     * header band so the slot ends at the footer's top edge instead of sliding under it.
     */
    private fun roundContentHeight(): Int {
        val m = adaptive ?: return dp(190)
        return (m.contentPx - m.headerPx / 2).coerceAtLeast(dp(110))
    }

    /** Short pre/post measurement pages: fixed title, content and bottom-action bands. */
    private fun useMeasurementLayout() {
        val m = adaptive ?: return
        val inset = (resources.displayMetrics.widthPixels * .16f).toInt()
        body.setPadding(inset, m.topGapPx, inset, 0)
        measurementHeader.layoutParams = LinearLayout.LayoutParams(m.headerWidthPx, m.headerPx)
        contentSlot.layoutParams = LinearLayout.LayoutParams(m.contentWidthPx, m.contentPx)
        topPane.setPadding(0, 0, 0, m.footerPx + m.betweenGapPx + m.bottomGapPx)
        topPane.clipToPadding = true
    }

    /** Restore the measurement actions after a sync page reused the same footer buttons. */
    private fun bindMeasurementFooter() {
        footerStart.text = "开始测量"
        footerStart.setOnClickListener {
            cancelAutoSync("BUTTON_START_SPO2")
            command("START_SPO2")
        }
        footerSkip.setOnClickListener {
            when (ActivityRecordingService.state.shortSensor) {
                "BIA" -> command("SKIP_BIA")
                "ECG" -> command("SKIP_ECG")
                "SPO2" -> command("SKIP_SPO2")
                "PPG_ON_DEMAND" -> command("SKIP_PPG")
            }
        }
    }

    private fun render(s: ActivityRecordingService.Companion.State) {
        if (inWatchPage() && !s.active) return
        // The vendor release hold is a bounded 60-second step: the process is about to
        // remove itself so no sensor handle survives the activity. It must always end;
        // a stale RELEASING state is cleared by resetStaleReleaseState() on a fresh open.
        if (!s.active && s.phase == "RELEASING") {
            useRoundPageLayout()
            measurementHeader.visibility = View.VISIBLE
            measurementHeader.text = "正在释放传感器"
            detailsPane.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = s.text
            status.textSize = 14f
            contentSlot.visibility = View.GONE
            stopPager()
            stopHeaderAlt()
            progressBox.visibility = View.GONE
            timerLabel.visibility = View.GONE
            activityTimer.visibility = View.GONE
            controls.removeAllViews()
            bottomFooter.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        if (app.activitySyncStatus.running && !ActivitySyncPolicy.isNonBlockingHomeSync(app.activitySyncStatus.trigger)) {
            renderSync(app.activitySyncStatus); return
        }
        if (s.active && s.phase == "DRAINING") {
            showDrainingPage(); return
        }
        measurementHeader.visibility = if (s.active) View.VISIBLE else View.GONE
        status.visibility = if (s.active) View.GONE else View.VISIBLE
        // A one-shot "previous activity had no data" notice belongs to the home; do not let it
        // push the measurement title and countdown down after the user starts a new activity.
        if (s.active && bannerIsNotice) {
            successBanner.visibility = View.GONE
            bannerIsNotice = false
            main.removeCallbacks(hideSuccess)
        }
        if (s.active) { cancelAutoSync("RECORDING_ACTIVE"); idle?.close(); idle = null }
        else if (app.activitySyncStatus.running) holdIdleForSync()
        else ensureIdle()
        val shortPhase = s.active && (
            s.phase.startsWith("PRE") || s.phase.startsWith("POST") ||
                s.phase.startsWith("READY_") || s.phase.startsWith("WAIT_BIA_") ||
                s.phase.startsWith("REVIEW_") || s.phase == "DRAINING" || s.phase == "SPO2_DONE"
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
        val ppgStage = guideMode == "PPG" && !s.phase.startsWith("REVIEW_")
        val m = adaptive
        val instructionMode = shortPhase && s.phase != "DRAINING" && !ppgStage &&
            !s.phase.startsWith("REVIEW_") && !instructionFinished(s, guideMode)
        contentSlot.visibility = if (s.active && shortPhase) View.VISIBLE else View.GONE
        if (shortPhase) {
            if (instructionMode) {
                showPager(instructionPagesFor(guideMode, s.phase.startsWith("PRE")),
                    "$guideMode/${if (s.phase.startsWith("PRE")) "PRE" else "POST"}")
                progressBox.visibility = View.GONE
            } else {
                stopPager()
                progressBox.visibility = View.VISIBLE
            }
        } else {
            stopPager()
            progressBox.visibility = View.GONE
        }
        val donePhase = s.phase == "SPO2_DONE"
        val showPercent = shortPhase && s.progressPercent >= 0
        val countdown = if (shortPhase && !instructionMode) countdownText(s, guideMode, contactDetected) else ""
        val primaryText = when {
            donePhase -> "测量成功"
            showPercent -> "${s.progressPercent}%"
            instructionMode -> ""
            else -> countdown
        }
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.percentSp ?: 38f)
        stepCountdown.visibility = if (primaryText.isNotEmpty()) View.VISIBLE else View.GONE
        if (primaryText.isNotEmpty()) {
            stepCountdown.text = primaryText
            // Green = an active measurement; amber = this page is about to end the attempt.
            stepCountdown.setTextColor(
                if (!donePhase && (s.phase.startsWith("REVIEW_") || (!showPercent && !s.engaged)))
                    RwTheme.colors(this).warning
                else RwTheme.colors(this).primary,
            )
        }
        val detailText = when {
            donePhase -> "即将退出…"
            shortPhase && s.phase.startsWith("REVIEW_") && guideMode == "ECG" ->
                "本次未取得连续30秒，可重试或跳过"
            shortPhase && !instructionMode -> s.progressDetail
            else -> ""
        }
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.detailSp ?: 14f)
        progressDetail.text = detailText
        progressDetail.visibility = if (detailText.isNotEmpty()) View.VISIBLE else View.GONE
        val statusInfo = if (ppgStage) "" to RwTheme.colors(this).primary
            else statusText(s, guideMode, contactDetected)
        // A small, fixed escape while a short measurement is running. The failure review
        // itself keeps retry first and skip below, without a duplicate fixed button.
        val biaSkip = s.active && s.shortSensor == "BIA" && s.phase != "CORE-1" &&
            !s.phase.startsWith("REVIEW_")
        val ecgSkip = s.active && s.shortSensor == "ECG" &&
            (s.phase.startsWith("PRE") || s.phase.startsWith("POST"))
        val spo2Skip = s.active && s.shortSensor == "SPO2" &&
            (s.phase.startsWith("PRE") || s.phase.startsWith("POST"))
        val ppgSkip = s.active && s.shortSensor == "PPG_ON_DEMAND" &&
            (s.phase.startsWith("PRE") || s.phase.startsWith("POST"))
        val spo2Start = s.active && s.phase.startsWith("READY_SPO2") && guideMode == "SPO2"
        val skip = biaSkip || ecgSkip || spo2Skip || ppgSkip
        val showFooter = skip || spo2Start
        bottomFooter.visibility = if (showFooter) View.VISIBLE else View.GONE
        footerStart.visibility = if (spo2Start) View.VISIBLE else View.GONE
        footerSkip.visibility = if (skip || spo2Start) View.VISIBLE else View.GONE
        if (spo2Start) footerSkip.text = "跳过血氧"
        else if (skip) footerSkip.text = when {
            biaSkip -> if (s.biaHasNext) "跳过当前" else "跳过体成分"
            ecgSkip -> "跳过 ECG"
            spo2Skip -> "跳过血氧"
            else -> "跳过 PPG"
        }
        // Round-safe measurement zones: title 3-19%, content 19-69%, bottom action 71-85%.
        // The title and buttons sit where the circle is still wide enough; the bottom 15% is
        // deliberately left empty because that is where the bezel closes in.
        val measuringShort = s.active && shortPhase
        if (measuringShort && m != null) {
            useMeasurementLayout()
            detailsPane.visibility = View.GONE
            bindMeasurementFooter()
        } else {
            useRoundPageLayout()
            detailsPane.visibility = if (s.active) View.GONE else View.VISIBLE
        }
        // Pre/post measurements are short, countdown-driven and need the instructions visible.
        // The activity itself keeps the old behaviour (system timeout, capture in the service).
        if (s.active && s.phase != "CORE-1") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val showTimer = s.active && s.phase == "CORE-1"
        timerLabel.visibility = if (showTimer) View.VISIBLE else View.GONE
        activityTimer.visibility = if (showTimer) View.VISIBLE else View.GONE
        if (showTimer) activityTimer.text = formatElapsed(s.coreElapsedSeconds)
        if (s.active && shortPhase && s.phase != "DRAINING" && !ppgStage) {
            // The top title is the only status position: name <-> wear/contact status every
            // 3 s, frozen on the status once contact is proven.
            updateHeaderAlt(itemName(s, guideMode), statusInfo.first,
                freeze = contactDetected || s.engaged || donePhase)
        } else {
            stopHeaderAlt()
            measurementHeader.text = when {
                !s.active -> "原波"
                ppgStage -> "PPG"
                s.phase == "DRAINING" -> "正在保存"
                else -> "活动记录中"
            }
        }
        status.text = if (s.active) "采集详情\n${s.text}" else s.text
        status.textSize = 14f
        maybeShowReviewDialog(s, guideMode)
        if (mode == "${s.active}/${s.phase}/${s.skipAllowed}/${s.biaSucceeded}") return
        mode = "${s.active}/${s.phase}/${s.skipAllowed}/${s.biaSucceeded}"
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
        if (s.active && !donePhase) {
            if (s.phase.startsWith("REVIEW_BIA_")) {
                // Retry first; skip is the second action below it. A failed scan is not a
                // reason to cancel the whole activity.
                smallButton("重试${if (s.biaMode.isBlank()) "体成分" else s.biaMode}", primary = true) { command("RETRY_BIA") }
                smallButton(if (s.biaHasNext) "跳过当前，继续下一种" else "跳过体成分，继续") { command("SKIP_BIA") }
            } else if (s.phase.startsWith("REVIEW_SPO2")) {
                smallButton("重试血氧", primary = true) { command("RETRY_SPO2") }
                smallButton("跳过血氧，继续") { command("SKIP_SPO2") }
            } else if (s.phase.startsWith("REVIEW_PPG_")) {
                smallButton("重试 PPG", primary = true) { command("RETRY_PPG") }
                smallButton("跳过 PPG，继续") { command("SKIP_PPG") }
            } else if (s.phase.startsWith("REVIEW_")) {
                smallButton("重试 ECG", primary = true) { command("RETRY_ECG") }
                smallButton("跳过 ECG，继续本次活动") { command("SKIP_ECG") }
            }
            // During measurement the small fixed footer button is the only skip; the review
            // page above owns the retry/skip pair.
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
        } else if (!s.active) showHome()
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
    /**
     * Saving is a short, non-interactive step. Show it centered and large instead of the tiny
     * title text; no buttons are offered while the sensors stop and the files are flushed.
     */
    private fun showDrainingPage() {
        stopPager()
        stopHeaderAlt()
        useMeasurementLayout()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val m = adaptive
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = "正在保存"
        status.visibility = View.GONE
        detailsPane.visibility = View.GONE
        contentSlot.visibility = View.VISIBLE
        contentSlot.minimumHeight = 0
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m?.contentWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT,
            m?.contentPx ?: dp(150))
        progressBox.visibility = View.VISIBLE
        stepCountdown.visibility = View.VISIBLE
        // "正在停止传感器" is seven characters wide; derive a size that fits the circle-safe
        // content width instead of using the large percentage size.
        val saveSp = (m?.contentWidthPx ?: dp(326)) / resources.displayMetrics.density / 7f * 0.95f
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            saveSp.coerceIn(14f, 24f))
        stepCountdown.text = "正在停止传感器\n并保存数据…"
        stepCountdown.setTextColor(RwTheme.colors(this).primary)
        progressDetail.visibility = View.VISIBLE
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, m?.detailSp ?: 14f)
        progressDetail.text = "请稍候，完成后会自动同步。"
        bottomFooter.visibility = View.GONE
        footerStart.visibility = View.GONE
        footerSkip.visibility = View.GONE
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
        controls.removeAllViews()
    }
    private fun showCaptureNoticeOnce() {
        if (noticeChecked) return
        noticeChecked = true
        try {
            val prefs = getSharedPreferences("capture-notice", MODE_PRIVATE)
            val last = prefs.getString("last", null) ?: return
            prefs.edit().remove("last").apply()
            if (!::successBanner.isInitialized) return
            val colors = RwTheme.colors(this)
            successBanner.text = last
            successBanner.setTone(colors.surfaceVariant, colors.warning)
            successBanner.visibility = View.VISIBLE
            bannerIsNotice = true
            main.removeCallbacks(hideSuccess)
            main.postDelayed(hideSuccess, 8000)
        } catch (_: Exception) {}
    }

    private fun showHome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // showHome() is called from render() and from recovery paths. Always rebuild the
        // list here; appending to a previous list produced a duplicate exit button.
        mode = ""
        useRoundPageLayout()
        // A direct recovery path (for example "retry sync" after the last pending item
        // disappeared) must not leave the sync page's big percent or footer buttons on the home.
        stopPager()
        progressBox.visibility = View.GONE
        contentSlot.visibility = View.GONE
        detailsPane.visibility = View.GONE
        bottomFooter.visibility = View.GONE
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
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
            if (filesDir.usableSpace < 1_000_000_000L) label("存储不足 1 GB：请先同步待同步活动。已同步的旧活动会在后台按“本地记录”页的规则自动清理，待同步数据不会删除。", controls)
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
        useRoundPageLayout()
        val colors = RwTheme.colors(this)
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = title
        measurementHeader.setTextColor(colors.onSurface)
        detailsPane.visibility = View.GONE
        contentSlot.visibility = View.GONE
        stopPager()
        stopHeaderAlt()
        progressBox.visibility = View.GONE
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
        if (sync.running) armSyncWatchdog()
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
        useRoundPageLayout()
        val colors = RwTheme.colors(this)
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = if (existing == null) "新建活动" else "编辑活动"
        measurementHeader.setTextColor(colors.onSurface)
        detailsPane.visibility = View.GONE
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

    private fun armSyncWatchdog() {
        main.removeCallbacks(syncWatchdog)
        main.postDelayed(syncWatchdog, SYNC_WATCHDOG_MS)
    }

    private fun cancelSyncWatchdog() {
        main.removeCallbacks(syncWatchdog)
    }

    /**
     * Close every activity in this task, not just the visible instance. The watch launcher can
     * create another RecordingActivity when the launch mode is standard; finishAffinity() makes
     * "完全退出" a single action even on builds/tasks that already stacked instances.
     */
    private fun closeWatchTask() {
        try { app.journal?.event("ACTIVITY_TASK_CLOSE", mapOf(
            "mode" to mode, "resumed" to resumed,
            "captureActive" to ActivityRecordingService.state.active,
            "syncRunning" to app.activitySyncStatus.running,
            "syncTrigger" to app.activitySyncStatus.trigger,
            "pendingExit" to app.pendingActivitySyncExit.get())) } catch (_: Exception) {}
        cancelSyncWatchdog()
        idle?.close(); idle = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { finishAffinity() } catch (_: Exception) {}
        finishAndRemoveTask()
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
        closeWatchTask()
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
        // Say the watch-local retention rule where the user looks for old records. The phone
        // keeps its received packages; only the watch copy is bounded.
        val pending = ActivityArchive.pendingCount(File(filesDir, "activities"))
        val rule = RwCard(this)
        rule.addView(RwSectionHeader(this).apply {
            text = "本地保存规则"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        rule.addView(RwBodyText(this).apply {
            text = "手表最多保留最近 ${ActivityRetention.MAX_SYNCED_SESSIONS} 次已同步活动，" +
                "本地原始数据总量上限 ${ActivityRetention.MAX_LOCAL_BYTES / (1024L * 1024L * 1024L)} GB，" +
                "超过 ${ActivityRetention.MAX_AGE_MS / (24L * 60L * 60L * 1000L)} 天或超出的已同步活动会自动删除。\n" +
                "手机已接收的数据不受影响；待同步的活动不会自动删除。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        rule.addView(RwBodyText(this).apply {
            val sizeText = app.lastActivityRetention?.let { "约 ${formatStorage(it.totalBytes())}，" } ?: ""
            text = "当前本地：$sizeText${records.size} 次活动（待同步 $pending 次）"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(RwTheme.colors(this@RecordingActivity).primary)
            setPadding(0, dp(6), 0, 0)
        })
        controls.addView(rule, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
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

    /**
     * The success page is shown for 3 s before the task closes, so the user sees that the
     * activity was committed instead of the app disappearing immediately.
     */
    private fun showSyncSuccessThenExit() {
        if (syncExitHold) return
        syncExitHold = true
        stopHeaderAlt()
        stopPager()
        useRoundPageLayout()
        val m = adaptive
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = "同步完成"
        status.visibility = View.GONE
        detailsPane.visibility = View.GONE
        contentSlot.visibility = View.VISIBLE
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m?.contentWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT,
            roundContentHeight())
        progressBox.visibility = View.VISIBLE
        stepCountdown.visibility = View.VISIBLE
        stepCountdown.text = "100%"
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.percentSp ?: 32f)
        stepCountdown.setTextColor(RwTheme.colors(this).primary)
        progressDetail.visibility = View.VISIBLE
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.detailSp ?: 13f)
        progressDetail.text = "已同步到手机并完成校验\n${SYNC_SUCCESS_HOLD_MS / 1000} 秒后自动退出"
        controls.removeAllViews()
        bottomFooter.visibility = View.GONE
        footerStart.visibility = View.GONE
        footerSkip.visibility = View.GONE
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        syncExitRunnable?.let { main.removeCallbacks(it) }
        val exit = Runnable {
            syncExitRunnable = null
            if (!isFinishing && !isDestroyed) closeWatchTask()
        }
        syncExitRunnable = exit
        main.postDelayed(exit, SYNC_SUCCESS_HOLD_MS)
        try { app.journal?.event("ACTIVITY_SYNC_SUCCESS_PAGE", mapOf(
            "holdMs" to SYNC_SUCCESS_HOLD_MS,
            "trigger" to app.activitySyncStatus.trigger)) } catch (_: Exception) {}
    }

    /**
     * Failed capture-finished/manual sync: keep the reason on screen, offer a retry, and let
     * the watch idle budget (80 s, the existing design) close the task to avoid battery drain.
     */
    private fun renderSyncFailure(sync: ProbeApplication.ActivitySyncStatus) {
        syncExitHold = false
        syncExitRunnable?.let { main.removeCallbacks(it) }
        syncExitRunnable = null
        stopHeaderAlt()
        stopPager()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        useRoundPageLayout()
        val m = adaptive
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = "同步未完成"
        status.visibility = View.GONE
        detailsPane.visibility = View.GONE
        contentSlot.visibility = View.VISIBLE
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m?.contentWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT,
            roundContentHeight())
        progressBox.visibility = View.VISIBLE
        stepCountdown.visibility = View.VISIBLE
        stepCountdown.text = "未完成"
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.percentSp ?: 30f)
        stepCountdown.setTextColor(RwTheme.colors(this).warning)
        progressDetail.visibility = View.VISIBLE
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.detailSp ?: 13f)
        // The full transfer text includes a stage id and can be several screens long. The watch
        // needs the reason, the fact that data is kept, and the auto-exit bound; the full log
        // stays in the phone's problem report.
        val reason = sync.text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("原因：") }
            ?.removePrefix("原因：")
            ?.take(36)
            ?.takeIf { it.isNotBlank() }
            ?: "本次同步没有完成"
        progressDetail.text = "$reason\n数据仍保留 · 约 ${WorkBudget.IDLE_MS / 1000} 秒后退出"
        controls.removeAllViews()
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
        bottomFooter.visibility = View.VISIBLE
        footerStart.visibility = View.VISIBLE
        footerStart.text = "重试同步"
        footerStart.setOnClickListener {
            cancelAutoSync("BUTTON_RETRY_SYNC")
            startPendingSync(ActivitySyncPolicy.USER_MANUAL)
        }
        footerSkip.visibility = View.VISIBLE
        footerSkip.text = "返回首页"
        footerSkip.setOnClickListener {
            cancelAutoSync("BUTTON_SYNC_HOME")
            returnHome()
        }
        // Do not hold the screen on here: the idle timer is the battery bound.
        releaseIdleAfterSync()
        try { app.journal?.event("ACTIVITY_SYNC_FAILURE_PAGE", mapOf(
            "trigger" to sync.trigger,
            "exitSeconds" to (WorkBudget.IDLE_MS / 1000))) } catch (_: Exception) {}
    }

    private fun renderSync(sync: ProbeApplication.ActivitySyncStatus) {
        if (inWatchPage()) return
        stopHeaderAlt()
        stopPager()
        syncExitHold = false
        syncExitRunnable?.let { main.removeCallbacks(it) }
        syncExitRunnable = null
        // A watch screen-off starves the bulk channel in practice (the 2026-11-19 transfer
        // stalled ~10-15 s after the last touch). Keep the screen on only for the bounded
        // transfer; the flag is cleared as soon as the sync stops.
        if (sync.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (sync.running) armSyncWatchdog()
        cancelAutoSync("SYNC_RUNNING")
        if (sync.running) holdIdleForSync() else ensureIdle()
        useRoundPageLayout()
        val m = adaptive
        measurementHeader.visibility = View.VISIBLE
        measurementHeader.text = "正在同步"
        status.visibility = View.GONE
        detailsPane.visibility = View.GONE
        timerLabel.visibility = View.GONE
        activityTimer.visibility = View.GONE
        contentSlot.visibility = View.VISIBLE
        contentSlot.layoutParams = LinearLayout.LayoutParams(
            m?.contentWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT,
            roundContentHeight())
        progressBox.visibility = View.VISIBLE
        stepCountdown.visibility = View.VISIBLE
        val percent = syncPercent(sync.text)
        stepCountdown.text = percent?.let { "$it%" } ?: "同步中"
        stepCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.percentSp ?: 32f)
        stepCountdown.setTextColor(RwTheme.colors(this).primary)
        progressDetail.visibility = View.VISIBLE
        progressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
            m?.detailSp ?: 13f)
        progressDetail.text = syncDetailText(sync.text)
        controls.removeAllViews()
        // The top of the page answers "am I syncing?" and "how far?"; the only button is the
        // meaningful one. Diagnostics stay under 更多 instead of competing with this action.
        bottomFooter.visibility = if (sync.running) View.VISIBLE else View.GONE
        footerStart.visibility = View.GONE
        footerSkip.visibility = if (sync.running) View.VISIBLE else View.GONE
        if (sync.running) {
            footerSkip.text = "停止同步"
            footerSkip.contentDescription = "停止同步，已经采集的数据仍保留"
            footerSkip.setOnClickListener {
                cancelAutoSync("BUTTON_STOP_SYNC")
                app.requestStopBackgroundSync("WATCH_SYNC_STOP_BUTTON")
            }
        }
    }

    /** Last percentage in the transfer text, e.g. "正在发送原始数据：42%" -> 42. */
    private fun syncPercent(text: String): Int? {
        val match = Regex("(\\d{1,3})\\s*%").findAll(text).lastOrNull() ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }

    /** Keep the stage line, drop the duplicate percentage, and add the screen-on promise. */
    private fun syncDetailText(text: String): String {
        val stage = text.replace(Regex("\\s*\\d{1,3}\\s*%"), "")
            .trim()
            .trimEnd('：', ':', '，', ',', '。', '.')
        val head = stage.ifBlank { "正在与手机传输数据" }
        return "$head\n保持亮屏，完成后会自动退出"
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
            // A new capture supersedes any pending exit signal from an earlier sync.
            app.pendingActivitySyncExit.set(false)
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
                "没有开始活动",
                "未获得所需权限，本次没有开始。\n允许权限后可以重新点这个活动；如果不再弹出授权，请到系统设置里允许原波。",
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
        if (::progressDetail.isInitialized) progressDetail.visibility = View.GONE
        if (::contentSlot.isInitialized) contentSlot.visibility = View.GONE
        if (::progressBox.isInitialized) progressBox.visibility = View.GONE
        stopPager()
        stopHeaderAlt()
        // The success banner has its own timeout; render() must not erase a final result.
    }
    /**
     * The big line owns progress/auto-advance; the pill above owns "is the user here".
     * This keeps both readable without a paragraph on a 480 px round screen.
     */
    /**
     * The big line only appears after the instruction phase. It shows real progress (percent)
     * or the ECG/retry/auto-advance countdown; the instruction pages own the "before" state.
     */
    private fun countdownText(s: ActivityRecordingService.Companion.State, mode: String, contact: Boolean): String {
        if (!s.engaged) return ""
        return when {
            mode == "ECG" && contact && s.remaining > 0 -> "剩余 ${s.remaining} 秒"
            s.phase.startsWith("WAIT_BIA") && s.phaseRemainingSeconds > 0 -> "${s.phaseRemainingSeconds} 秒后自动重试"
            s.phase.startsWith("REVIEW_") && s.cooperationRemainingSeconds > 0 ->
                "${s.cooperationRemainingSeconds} 秒后自动继续"
            else -> ""
        }
    }

    /** One concise top line: the item being measured, never a long status paragraph. */
    private fun itemName(s: ActivityRecordingService.Companion.State, mode: String): String = when (mode) {
        "BIA" -> {
            val name = s.biaMode.ifBlank { "体成分" }
            if (s.biaModeCount > 1) "$name ${s.biaModeNumber}/${s.biaModeCount}" else name
        }
        "SPO2" -> "血氧"
        "ECG" -> "ECG"
        "PPG" -> "PPG"
        else -> "测量"
    }

    /**
     * The instruction phase ends as soon as the sensor proves contact/data. After that the
     * pager is replaced by progress and a short detail; no instruction text remains.
     */
    private fun instructionFinished(s: ActivityRecordingService.Companion.State, mode: String): Boolean {
        if (s.phase.startsWith("REVIEW_") || s.phase == "DRAINING" || s.phase == "SPO2_DONE") return true
        if (s.progressPercent >= 0) return true
        if (mode == "ECG" && s.contact) return true
        if (mode == "BIA" && s.biaContact) return true
        return false
    }

    /** Large, short pages; the pager replaces one page with the next every 7 seconds. */
    private fun instructionPagesFor(mode: String, pre: Boolean): List<String> = when (mode) {
        "SPO2" -> listOf(
            "请把手表调整到距离腕关节五指的位置",
            "手放到桌上，手表位置与心脏差不多高度",
            "保持静止。准备好后点下方开始测量",
        )
        "BIA" -> listOf(
            "请将中指和无名指分别放到右侧两个按键",
            "保持接触即可，不用按键",
            "保持两个手指分开，两只手不要接触",
        )
        // Post ECG runs on the already-correct normal position, so it must not ask the
        // user to move the strap again; only the pre ECG follows the raised SpO2 position.
        "ECG" -> if (pre) listOf(
            "请将手表调整到距离腕关节大概两指的位置",
            "就是你平常佩戴位置",
            "请将手指放到右上角按键",
            "保持接触即可，不用按键",
        ) else listOf(
            "手表保持现在的佩戴位置，不用移动表带",
            "请将手指放到右上角按键",
            "保持接触即可，不用按键",
        )
        "PPG" -> listOf(
            "正常佩戴，手臂放稳",
            "不要乱动，马上开始",
        )
        else -> emptyList()
    }

    /** Small wear/contact + exit countdown line; no pill, no color-block chrome. */
    private fun statusText(s: ActivityRecordingService.Companion.State, mode: String, contact: Boolean): Pair<String, Int> {
        val c = RwTheme.colors(this)
        if (s.phase == "SPO2_DONE" || mode == "PPG") return "" to c.primary
        if (s.engaged) {
            // Only the real evidence is named here; no "N seconds to continue" countdown,
            // which made the wear state look like an app-generated reassurance.
            val text = when {
                mode == "BIA" && contact -> "✓ 已检测到接触"
                mode == "ECG" && contact -> "✓ 手指接触已确认"
                s.worn == true -> "✓ 已戴在手腕上"
                else -> "✓ 已确认配合"
            }
            return text to c.primary
        }
        val base = when {
            s.worn == false -> "未检测到佩戴"
            mode == "BIA" || mode == "ECG" -> "等待接触"
            s.worn == true -> "已检测到佩戴"
                else -> "✓ 已确认配合"
        }
        val exit = if (s.cooperationRemainingSeconds > 0) " · ${s.cooperationRemainingSeconds}秒后退出" else ""
        return (base + exit) to c.warning
    }

    /**
     * One stage-specific explanation for a failed measurement. The first line is always the
     * same reassurance; the rest explains what this sensor actually needs, what the visible
     * percent means, and whether the partial data counts as a result.
     */
    private fun failureCopy(s: ActivityRecordingService.Companion.State, mode: String): String {
        val head = "没关系，这不是你的错。这类测量本身就可能中断，不代表你哪里做得不对。\n\n"
        val reason = s.progressDetail.ifBlank { "本次没有取得有效结果" }
        val progressText = if (s.progressPercent >= 0)
            "你看到的 ${s.progressPercent}% 只是采集/扫描进度，不是最终有效结果。\n" else ""
        return when (mode) {
            "BIA" -> {
                val name = if (s.biaMode == "MF_BIA") "多频阻抗" else "体成分"
                head + reason + "\n\n" + progressText +
                    "${name}需要完成到 100% 且双指接触稳定，才算一次有效结果。本次已收的原始数据会保存，但不作为有效${name}。"
            }
            "SPO2" -> head + reason + "\n\n" + progressText +
                "血氧依靠稳定的光学信号；本次没有取得稳定读数，原始记录会保存，但不作为有效血氧。"
            "ECG" -> head + reason + "\n\n" +
                "ECG 需要连续 30 秒稳定的电极接触；本次不足 30 秒或接触中断。已记录的片段会保存，但不作为有效 ECG。"
            "PPG" -> head + reason + "\n\n" + progressText +
                "PPG 需要稳定的光学信号和完整采集窗口；本次原始记录会保存，但不作为一次完整 PPG。"
            else -> head + reason
        }
    }

    /**
     * Every failed measurement shows one explanation first, then retry (primary) and skip
     * (secondary). The skip is intentionally below the retry, as the second action.
     */
    private fun maybeShowReviewDialog(s: ActivityRecordingService.Companion.State, mode: String) {
        val failureReview = s.active && s.phase.startsWith("REVIEW_") && !s.biaSucceeded
        if (!failureReview) {
            dismissReviewDialog()
            return
        }
        if (reviewDialogPhase == s.phase) return
        dismissReviewDialog()
        reviewDialogPhase = s.phase
        val (retry, skip) = when (mode) {
            "BIA" -> "RETRY_BIA" to "SKIP_BIA"
            "SPO2" -> "RETRY_SPO2" to "SKIP_SPO2"
            "PPG" -> "RETRY_PPG" to "SKIP_PPG"
            "ECG" -> "RETRY_ECG" to "SKIP_ECG"
            else -> return
        }
        reviewDialog = RwDialogs.actions(
            this,
            "没关系，这次没测出有效结果",
            failureCopy(s, mode),
            listOf(
                RwDialogs.Action("重试", primary = true) { command(retry) },
                RwDialogs.Action("跳过") { command(skip) },
            ),
            cancelLabel = null,
        )
    }

    private fun dismissReviewDialog() {
        reviewDialog?.dismiss()
        reviewDialog = null
        reviewDialogPhase = ""
    }

    private fun formatStorage(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return if (safe >= 1_000_000_000L) "%.1f GB".format(safe / 1_000_000_000.0)
        else "%.0f MB".format(safe / 1_000_000.0)
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
    /** Compact action for the review pages: less chrome, more room for the explanation. */
    private fun smallButton(text: String, primary: Boolean = false, action: () -> Unit) {
        val view = if (primary) RwPrimaryButton(this) else RwSecondaryButton(this)
        val height = adaptive?.buttonHeightPx ?: dp(38)
        view.text = text
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, adaptive?.buttonSp ?: 13f)
        view.minHeight = height
        view.setPadding(dp(8), 0, dp(8), 0)
        view.setOnClickListener { cancelAutoSync("BUTTON_${text.take(24)}"); action() }
        controls.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height,
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

    private companion object {
        const val SYNC_WATCHDOG_MS = 5_000L
        /** First instruction page stays a little longer; a first-time reader needs it. */
        const val PAGE_FIRST_MS = 9_000L
        /** Later instruction page replacement interval. */
        const val PAGE_MS = 7_000L
        /** Top item name / cooperation status alternation interval. */
        const val ALT_MS = 3_000L
        /** Keep the successful sync confirmation visible before closing the task. */
        const val SYNC_SUCCESS_HOLD_MS = 3_000L
    }
}

