package com.rawwave.shell

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.view.Display
import com.rawwave.core.ActivityStore
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.DeviceState
import com.rawwave.core.EcgBatchQuality
import com.rawwave.core.EcgContinuityGate
import com.rawwave.core.EcgRetryPolicy
import com.rawwave.core.StreamQualityTracker
import com.rawwave.core.SensorCatalog
import com.rawwave.core.SensorPort
import com.rawwave.core.SensorSilencePolicy
import com.rawwave.core.EcgWaitBudget
import com.rawwave.core.CapturePhasePolicy
import com.rawwave.core.CaptureRuns
import com.rawwave.core.CaptureDrain
import com.rawwave.core.CaptureQueue
import com.rawwave.core.CoreDataBudget
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Single-writer capture owner. Never automatically restarts sensors after process death. */
class ActivityRecordingService : Service() {
    companion object {
        private const val SHORT_BUDGET_MS = 80_000L
        private const val NO_HAND_AUTOSTART_MS = 15_000L
        /** Let the stopped-service notification render before the process removes itself. */
        private const val VENDOR_RELEASE_KILL_DELAY_MS = 1_500L
        /** Rest between BIA/MF-BIA attempts; the electrode/skin state needs to settle. */
        private const val BIA_CONTACT_RETRY_MS = 5_000L
        data class State(val active: Boolean = false, val phase: String = "",
            val text: String = "", val remaining: Int = 0, val contact: Boolean = false,
            val skipAllowed: Boolean = false, val failedAttempts: Int = 0, val shortSensor: String = "ECG",
            val biaMode: String = "", val biaHasNext: Boolean = false, val biaContact: Boolean = false,
            val biaRetrying: Boolean = false, val biaAttempt: Int = 0, val biaProgress: Int = -1,
            val biaHint: String = "", val coreElapsedSeconds: Long = 0L,
            val phaseRemainingSeconds: Int = 0, val biaModeNumber: Int = 0, val biaModeCount: Int = 0)
        @Volatile var state = State(); private set
        /**
         * Absolute end of the vendor release hold. A fresh Activity uses it to clear a
         * RELEASING state left behind by a process that survived the hold (for example a
         * manual sync or a lost timer). Zero means no release is pending.
         */
        @Volatile var releaseDeadlineMs = 0L; private set
        /** Clear a stale RELEASING screen after the hold deadline or a fresh process. */
        fun resetStaleReleaseState() {
            if (state.phase == "RELEASING" && (releaseDeadlineMs == 0L || SystemClock.elapsedRealtime() > releaseDeadlineMs)) {
                state = State(false, "DONE", "上次传感器释放已结束；数据保留，可手动同步。")
                releaseDeadlineMs = 0L
            }
        }
        var observer: ((State) -> Unit)? = null // main thread; removed on Activity stop
        var successObserver: ((String, String) -> Unit)? = null // main thread; short measurement success
        var idleExitObserver: (() -> Unit)? = null // main thread; retained only until Activity destroy
        fun store(context: Context) = ActivityStore(File(context.filesDir, "activities")) { dir ->
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("rawwave-capture")
    internal lateinit var writer: CaptureQueue
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val queued = AtomicInteger()
    private val callbackAdmission = com.rawwave.core.CallbackAdmission()
    private var session: ActivityStore.Session? = null
    private var persistence: com.rawwave.core.CapturePersistence? = null
    internal var useDiskOwner = false
    /** Production default: drain PPG before the SDK HR client so the vendor stop can apply. */
    internal var drainPpgFirstForLed = true
    private fun appendRows(phase: String, sensor: String, rows: List<Map<String, Any?>>) {
        persistence?.append(phase, sensor, rows) ?: session?.append(phase, sensor, rows)
    }
    private fun persistedCounts() = persistence?.counts() ?: session?.counts()
    private var adapter: SensorPort? = null
    private var lock: PowerManager.WakeLock? = null
    private var phase = "PRE-1"
    private var preEcg = false
    private var postEcg = false
    private var bodySource = "BIA"
    private var bia = false
    private var biaPre = true
    private var preBiaAttempt = 199
    private var postBiaAttempt = 199
    private var biaPhase = "PRE-200"
    private val biaResults = linkedMapOf<String,String>()
    private val biaModeByPhase = linkedMapOf<String,String>()
    private var biaModes: List<String> = emptyList()
    private var biaModeIndex = 0
    private var biaContactDetected = false
    private var biaContactStartMs = 0L
    private var shortBudgetStartMs = 0L
    private var biaRetrying = false
    private var biaAttempt = 0
    private var biaProgress = -1
    private var biaHint = ""
    private var biaSummary = ""
    private var preSpo2 = false
    private var postSpo2 = false
    /** Which phase the current short measurement belongs to; not a template choice. */
    private var spo2Pre = true
    /** Continuous CORE PPG plus pre short PPG uses the same vendor 25 Hz client; skip pre to avoid the known LED leak. */
    private var skipPrePpgForVendorLed = false
    private var prePpg = false
    private var postPpg = false
    private var ppgPre = true
    private var shortSensor = "ECG"
    private var shortFailure = false
    private var preSpo2Attempt = 99
    private var postSpo2Attempt = 99
    private var spo2Phase = "PRE-100"
    private val spo2Results = linkedMapOf<String, String>()
    private var spo2Value: Int? = null
    private var prePpgAttempt = 299
    private var postPpgAttempt = 299
    private var ppgPhase = "PRE-300"
    private val ppgResults = linkedMapOf<String, String>()
    private var ppgRows = 0L
    private var coreSources = SensorCatalog.defaultCoreSources()
    private var deadline = 0L
    private var sessionStartedMs = 0L
    private var coreStartedMs = 0L
    private var coreEndedMs = 0L
    @Volatile private var coreDataObserved = false
    internal var terminalReason = ""; private set
    private var stopping = false
    private var draining: CaptureDrain? = null
    private var pendingFinish: Pair<String, Boolean>? = null
    private var incompleteTail = ""
    @Volatile private var destroyed = false
    @Volatile private var taskRemoved = false
    /**
     * The transport ZIP is prebuilt during capture and the transfer is resumable/bounded,
     * so capture can start the normal sync immediately. The 60-second vendor release runs
     * independently and still ends the process after the sync (or the 5-minute pause).
     */
    internal var autoSyncAfterCapture = true
    internal fun shouldStartFinishSync(sealed: Boolean) =
        autoSyncAfterCapture && sealed && !destroyed && !taskRemoved && coreDataObserved
    private val fatalPending = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var started = false
    private var contact = false
    private var ecgTotalRows = 0L
    private var lastContactMs = 0L
    private var ecgGate = EcgContinuityGate()
    private var ecgUpdate: EcgContinuityGate.Update? = null
    private var ecgFailed = false
    private var ecgFailureReason = ""
    private var ecgPhase = "PRE-1"
    private var preAttempt = 1
    private var postAttempt = 1
    private val preRetryPolicy = EcgRetryPolicy()
    private val postRetryPolicy = EcgRetryPolicy()
    private var notice = ""
    private var startingSources = false
    private var lockRenewed = 0L
    private val running = mutableSetOf<String>()
    private val captureRuns = CaptureRuns()
    private val lastData = mutableMapOf<String, Long>()
    private val silent = mutableSetOf<String>()
    private var lastDeviceStateMs = 0L
    private val buffers = mutableMapOf<String, MutableList<Map<String, Any?>>>()
    private val qualityTrackers = mutableMapOf<String, StreamQualityTracker>()
    private val tick = object : Runnable {
        override fun run() = guarded {
            if (stopping || draining != null) return@guarded
            // Review is bounded too: keep the deadline runnable executing while the screen sleeps.
            renewCaptureLock()
            persistence?.check()
            flush()
            if (lastDeviceStateMs == 0L || clock() - lastDeviceStateMs >= 60_000L) sampleDeviceState()
            // Review pages have their own 80-second deadline set at review entry. The
            // measurement budget must not leak into the review and cut it short.
            if (shortBudgetStartMs > 0L && shortSensor.isNotEmpty() && phase != "CORE-1" &&
                !phase.startsWith("REVIEW_") &&
                !(shortSensor == "BIA" && biaContactDetected) &&
                !(shortSensor == "ECG" && ecgGate.measuring() && (phase.startsWith("PRE") || phase.startsWith("POST"))) &&
                clock() >= shortBudgetStartMs + SHORT_BUDGET_MS) {
                event("SHORT_MEASUREMENT_TIMEOUT_80S", "sensor=$shortSensor; phase=$phase; elapsedMs=${clock()-shortBudgetStartMs}")
                finish("SHORT_MEASUREMENT_TIMEOUT_80S", true)
                return@guarded
            }
            // STEP_COUNT and STEP_DETECTOR are on-change: quiet while stationary is expected and is not a data alarm.
            running.filter { it != "SPO2" && it != "BIA" && it != "MF_BIA" && it != "PPG_ON_DEMAND" && it != "STEP_COUNT" && it != "STEP_DETECTOR" && it != "OFFBODY" }.forEach { sensor ->
                val thresholdMs = SensorSilencePolicy.silenceThresholdMs(sensor)
                if (thresholdMs > 0 && clock() - (lastData[sensor] ?: 0) > thresholdMs && silent.add(sensor)) {
                    event("DATA_SILENCE_OBSERVED", "$sensor: no callback for ${thresholdMs / 1000}s; expectedIntervalMs=${SensorSilencePolicy.expectedIntervalMs(sensor)}; SDK buffering versus missing data unknown")
                    notice = "$sensor 暂无新数据，原因未确认；其他采集继续。"
                }
            }
            if (shortSensor == "ECG" && ecgGate.measuring() && running.contains("ECG") &&
                clock() - (lastData["ECG"] ?: clock()) >= 15_000) {
                ecgFailed = true; ecgFailureReason = "ECG_CALLBACK_SILENCE_15S"
                event("ECG_CALLBACK_TIMEOUT", "No ECG callbacks for 15s; partial rows retained; not a successful measurement")
                endEcg(); return@guarded
            }
            if (deadline > 0 && clock() >= deadline) {
                event("CAPTURE_DEADLINE_OBSERVED", "phase=$phase; deadlineMs=$deadline; observedMs=${clock()}; lateMs=${clock()-deadline}; wakeLockHeld=${lock?.isHeld == true}; uptimeMs=${SystemClock.uptimeMillis()}")
                if (phase.startsWith("READY_SPO2_")) { beginSpo2(spo2Pre); return@guarded }
                if (phase.startsWith("READY_PPG_")) { beginPpg(ppgPre); return@guarded }
                if (phase == "RESTORE_PRE") {
                    event("NORMAL_PLACEMENT_CONFIRMED", "auto=${NO_HAND_AUTOSTART_MS / 1000}s; next=${if(preEcg) "ECG" else "CORE"}")
                    if(preEcg) beginEcg("PRE-1") else beginCore()
                    return@guarded
                }
                if (phase.startsWith("WAIT_BIA_")) {
                    if (clock() >= biaContactStartMs + SHORT_BUDGET_MS) {
                        event("BIA_CONTACT_TIMEOUT_80S", "mode=$bodySource; pre=$biaPre")
                        finish("SHORT_MEASUREMENT_TIMEOUT_80S", true)
                    } else beginBia()
                    return@guarded
                }
                if (placementWaiting()) { finish("PLACEMENT_IDLE_TIMEOUT_80S", true); return@guarded }
                if (phase == "CORE-1") {
                    // No non-empty row from any core source arrived inside the initial window.
                    // This is a power/lifecycle timeout, not a clinical validity judgment.
                    if (CoreDataBudget.mustStop(deadline, clock(), coreDataObserved)) {
                        event("CORE_NO_DATA_TIMEOUT_80S", "No raw row from any core source; ending without post measurements or sync")
                        finish("CORE_NO_DATA_TIMEOUT_80S", true); return@guarded
                    }
                    deadline = 0
                    publish(); writer.postDelayed(this, 1000); return@guarded
                }
                if (shortSensor == "BIA") {
                    if(phase.startsWith("REVIEW_")) finish("BIA_REVIEW_IDLE_TIMEOUT_80S",true)
                    else endBia(false,"MEASUREMENT_TIMEOUT_60S")
                    return@guarded
                }
                if (shortSensor == "SPO2") {
                    if (phase.startsWith("REVIEW_")) finish("SPO2_REVIEW_IDLE_TIMEOUT_80S", true)
                    else endSpo2(false, "MEASUREMENT_TIMEOUT_30S")
                    return@guarded
                }
                if (shortSensor == "PPG_ON_DEMAND") {
                    if (phase.startsWith("REVIEW_")) finish("PPG_REVIEW_IDLE_TIMEOUT_80S", true)
                    else endPpg(ppgRows > 0, if (ppgRows > 0) "MEASUREMENT_WINDOW_COMPLETE_30S" else "NO_DATA_30S")
                    return@guarded
                }
                val next = CapturePhasePolicy.expired(phase, ecgGate.measuring())
                if (next == CapturePhasePolicy.Next.CONNECT_TIMEOUT) { finish("SDK_CONNECT_TIMEOUT"); return@guarded }
                if (next == CapturePhasePolicy.Next.IDLE_STOP && phase.startsWith("REVIEW_")) { finish("ECG_IDLE_TIMEOUT_80S", true); return@guarded }
                if (phase.startsWith("PRE") || phase.startsWith("POST")) {
                    if (next == CapturePhasePolicy.Next.IDLE_STOP) {
                        event("ECG_CONTACT_WAIT_TIMEOUT", "No valid ECG segment; stopping without another review or sync budget")
                        finish("ECG_IDLE_TIMEOUT_80S", true); return@guarded
                    }
                    ecgFailed = true
                    ecgFailureReason = "ECG_CONTINUITY_TIMEOUT"
                    if (ecgGate.measuring())
                        event("ECG_CONTINUITY_TIMEOUT", "Continuous segment did not meet the 30-second sample and timestamp contract")
                    else event("ECG_CONTACT_WAIT_TIMEOUT", "No valid ECG segment before the 80-second placement deadline")
                }
                endEcg(); return@guarded
            }
            publish()
            writer.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); useDiskOwner = true; thread.start()
        val handler = Handler(thread.looper)
        writer = object : CaptureQueue {
            override fun post(action: Runnable) = handler.post(action)
            override fun postDelayed(action: Runnable, delayMs: Long) = handler.postDelayed(action, delayMs)
            override fun removeCallbacks(action: Runnable) = handler.removeCallbacks(action)
            override fun close() { thread.quitSafely() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START" && !started) {
            started = true
            state = State(true, "CONNECTING", "正在准备传感器…")
            try {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("capture", "活动采集", NotificationManager.IMPORTANCE_LOW))
                val pending = PendingIntent.getActivity(this, 0, Intent(this, RecordingActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val stop = PendingIntent.getService(this, 1,
                    Intent(this, ActivityRecordingService::class.java).setAction("CANCEL"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_rawwave)
                    .setContentTitle("原波正在记录活动")
                    .setContentText("返回原波正常结束；也可停止采集并保存（不做活动后短测）")
                    .setContentIntent(pending).setOngoing(true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止采集（保存）", stop).build()
                startForeground(410, notification,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
                lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawWave:Capture").apply {
                    setReferenceCounted(false); acquire(60_000)
                }
                lockRenewed = clock()
                val id = intent.getStringExtra("template")
                val store = store(this)
                val template = store.templates().single { it.id() == id }
                if (Build.VERSION.SDK_INT >= 34 && template.coreSources().contains(SensorCatalog.GPS) &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Location foreground type is added only for a selected, granted GPS source.
                    startForeground(410, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                writer.post { guarded {
                    beginSession(store.start(template, System.currentTimeMillis(), clock()),template,
                        CaptureAssembly.sensors(this, ::enqueue,
                            template.ecg() || template.spo2() || template.ppg() || template.bia() || template.coreSources().any { !SensorCatalog.isAndroidPlatformSource(it) }, template.biaProfile(), ::enqueueRows))
                } }
            } catch (e: Exception) { writer.post { fail(e) } }
        } else if (started) {
            command(intent?.action)
        } else stopSelf()
        return START_NOT_STICKY
    }
    /** Runs on the serial owner after platform permission/foreground setup. Also the host-test seam. */
    internal fun beginSession(value: ActivityStore.Session, template: ActivityStore.Template, sensors: SensorPort) {
        check(session == null && !destroyed && !stopping) { "CAPTURE_SESSION_ALREADY_USED" }
        session = value
        if (useDiskOwner) persistence = com.rawwave.core.CapturePersistence(value)
        coreSources = template.coreSources().toList()
        preEcg = template.preEcg(); postEcg = template.postEcg()
        preSpo2 = template.preSpo2(); postSpo2 = template.postSpo2()
        bia = template.bia()
        prePpg = template.prePpg(); postPpg = template.postPpg()
        skipPrePpgForVendorLed = prePpg && coreSources.contains("PPG")
        if (skipPrePpgForVendorLed) {
            ppgResults["PRE-300"] = "已跳过：连续 PPG 与短测 PPG 同时开启时，活动前 100Hz 可能与背面灯常亮冲突"
            event("PRE_PPG_SKIPPED_FOR_VENDOR_LED",
                "reason=CORE_AND_SHORT_PPG_SHARE_VENDOR_25HZ_CLIENT; postPpgRemains=true")
        }
        biaModes = when {
            !bia -> emptyList()
            template.biaBoth() -> listOf("BIA","MF_BIA")
            template.mfBia() -> listOf("MF_BIA")
            else -> listOf("BIA")
        }
        biaModeIndex = 0
        bodySource = biaModes.firstOrNull() ?: "BIA"
        sessionStartedMs = clock()
        coreStartedMs = 0L
        coreEndedMs = 0L
        event("CAPTURE_START", "build=${BuildConfig.BUILD_RUN}; template=${template.id()}",
            mapOf("activityName" to template.name(), "activityTemplateId" to template.id()))
        lastDeviceStateMs = 0L
        sampleDeviceState()
        phase = "CONNECTING"; deadline = clock() + 20_000
        adapter = SensorPort.requireCompatible(sensors)
        event("SENSOR_CONTRACT_BOUND", "contract=${adapter!!.contractVersion()}; implementation=${adapter!!.implementationVersion()}")
        emitPlatformSensorInventory()
        adapter!!.connect({
            if (draining != null || stopping || destroyed || fatalPending.get() != null) return@connect
            if (phase != "CONNECTING") return@connect
            if (clock() >= deadline) { finish("SDK_CONNECT_TIMEOUT"); return@connect }
            writer.removeCallbacks(tick)
            // PPG needs no placement; run it first unless continuous CORE PPG is also enabled.
            // In that combination the vendor 25 Hz client can be left behind and keep the back
            // LEDs on; skip only the pre short PPG and keep the post short PPG after CORE stops.
            // BIA only waits for finger contact. SpO2 still needs the elbow/wrist position, and
            // ECG is the only later placement change.
            if (prePpg && !skipPrePpgForVendorLed) beginPpg(true) else if (bia) startBia(true) else if (preSpo2) prepareSpo2(true) else if (preEcg) beginEcg("PRE-1") else beginCore()
        }, { finish(it) })
        writer.post(tick)
    }
    internal fun command(action: String?) {
        writer.post { guarded {
                if (stopping) return@guarded
                if (destroyed || fatalPending.get() != null) { finish(fatalPending.get() ?: "SERVICE_DESTROYED_INTERRUPTION"); return@guarded }
                if (draining != null) {
                    if (action == "CANCEL") finish("USER_INTERRUPTED")
                    return@guarded
                }
                if ((shortSensor == "SPO2" || shortSensor == "BIA" || shortSensor == "PPG_ON_DEMAND") && phase.startsWith("REVIEW_") && clock() >= deadline) {
                    finish("${shortSensor}_REVIEW_IDLE_TIMEOUT_80S", true); return@guarded
                }
                when (action) {
                    "END" -> if (phase == "CORE-1") {
                        if (coreEndedMs == 0L) coreEndedMs = clock()
                        event("USER_ENDED_CORE", "Post measurements follow the template, not a new choice")
                        // Keep the continuous PPG tracker alive through post ECG. Samsung's
                        // post-ECG HRM start re-enables the shared 25 Hz stream; stopping PPG
                        // before ECG makes our later final unset a second, ineffective unset.
                        // Holding PPG makes it the last 25 Hz client and gives the vendor stop
                        // request one real chance after ECG has ended.
                        val holdPpg = postEcg && coreSources.contains("PPG")
                        val postFlow: () -> Unit = {
                            when(CapturePhasePolicy.coreEnded(postEcg)) {
                                CapturePhasePolicy.Next.POST_ECG -> beginEcg("POST-1")
                                else -> beginPostShorts()
                            }
                        }
                        // 2026-11-05 evidence: the vendor 25 Hz stop is applied when PPG is
                        // unset while the SDK HEART_RATE client is still active. Drain PPG
                        // first, then the remaining sources; do not drain both together.
                        val ppgFirstForLed = drainPpgFirstForLed && !postEcg &&
                            coreSources.contains(SensorCatalog.PPG) &&
                            coreSources.contains(SensorCatalog.HEART_RATE)
                        if (ppgFirstForLed) {
                            event("LED_RELEASE_DRAIN_PPG_FIRST",
                                "keep=HEART_RATE; reason=PPG_25HZ_STOP_NOT_VERIFIABLE")
                            drainSources(keep = setOf(SensorCatalog.HEART_RATE), allowPartialPost = true,
                                only = setOf(SensorCatalog.PPG)) {
                                drainSources(allowPartialPost = postEcg || postSpo2 || postPpg) { postFlow() }
                            }
                        } else {
                            drainSources(keep = if (holdPpg) setOf("PPG") else emptySet(),
                                allowPartialPost = postEcg || postSpo2 || postPpg) { postFlow() }
                        }
                    }
                    "CANCEL" -> finish("USER_INTERRUPTED")
                    "TOUCH" -> {
                        // A user who is still trying or reaching for skip is not idle. Reset
                        // the short-phase deadline so the 80-second safety cannot close the
                        // page in the middle of their interaction.
                        if (shortBudgetStartMs > 0L && phase != "CORE-1") {
                            shortBudgetStartMs = clock()
                            if (shortSensor == "BIA") biaContactStartMs = clock()
                            // Extend only decision/idle windows. A BIA retry wait already
                            // has a 5-second deadline; touching to reach skip must not
                            // postpone the automatic retry by another 80 seconds.
                            if (phase.startsWith("REVIEW_"))
                                deadline = maxOf(deadline, clock() + SHORT_BUDGET_MS)
                            else if (shortSensor == "ECG" && !ecgGate.measuring())
                                deadline = maxOf(deadline, clock() + SHORT_BUDGET_MS)
                        }
                    }
                      "RETRY_BIA" -> if(phase.startsWith("REVIEW_BIA_")) retryBia()
                      "SKIP_BIA" -> if(shortSensor == "BIA" && phase != "CORE-1") skipBia()
                      "START_SPO2" -> if (phase.startsWith("READY_SPO2_")) beginSpo2(spo2Pre)
                      "START_PPG" -> if (phase.startsWith("READY_PPG_")) beginPpg(ppgPre)
                      "PLACEMENT_READY" -> if (phase == "RESTORE_PRE") {
                          event("NORMAL_PLACEMENT_CONFIRMED", "next=${if(preEcg) "ECG" else "CORE"}")
                          if(preEcg) beginEcg("PRE-1") else beginCore()
                      }
                      // A retry is a measurement restart, not a new placement decision.
                      // SpO2/PPG have no contact gate, so start immediately.
                      "RETRY_SPO2" -> if (phase.startsWith("REVIEW_SPO2")) beginSpo2(spo2Pre)
                      "SKIP_SPO2" -> if (phase.startsWith("REVIEW_SPO2")) {
                          shortFailure = true; spo2Results[spo2Phase] = "未取得有效结果，已跳过"
                          event("USER_SKIPPED_SPO2", "pre=$spo2Pre")
                          afterSpo2()
                      }
                      "RETRY_PPG" -> if (phase.startsWith("REVIEW_PPG_")) beginPpg(ppgPre)
                      "SKIP_PPG" -> if (phase.startsWith("REVIEW_PPG_") || phase.startsWith("READY_PPG_")) {
                          shortFailure = true
                          if (phase.startsWith("REVIEW_")) ppgResults[ppgPhase] = "未采集，已跳过"
                          event("USER_SKIPPED_PPG", "pre=$ppgPre")
                          afterPpg()
                      }
                    "RETRY_ECG" -> if (shortSensor == "ECG" && phase.startsWith("REVIEW_")) {
                        event("USER_RETRY_ECG", ecgPhase)
                        beginEcg(if (phase == "REVIEW_PRE") "PRE-${++preAttempt}" else "POST-${++postAttempt}")
                    }
                    "SKIP_ECG" -> if (shortSensor == "ECG" && (phase.startsWith("REVIEW_") || phase.startsWith("PRE") || phase.startsWith("POST"))) skipEcg()
                    // Diagnostic-only escape hatch for the CORE+post-ECG LED test:
                    // pre-ECG contact can be skipped even before two failed attempts.
                    "TEST_SKIP_PRE_ECG" -> if (shortSensor == "ECG" && (phase.startsWith("PRE") || phase == "REVIEW_PRE")) forceSkipPreEcg()
                }
            } }
    }
    // Raw payloads use a retained-size budget; controls keep their separate small bound.
    // Both post to the SAME owner FIFO, preserving data/flush/stop ordering.
    internal fun enqueueRows(rows: List<Map<String, Any?>>, action: () -> Unit) {
        val lease = callbackAdmission.reserve(rows)
        if (lease == null) {
            fatalPending.compareAndSet(null,"CALLBACK_PAYLOAD_LIMIT_UNCOMMITTED_TAIL_UNKNOWN")
            if (overflow.compareAndSet(false, true)) writer.post { guarded { finish(fatalPending.get()!!) } }
            return
        }
        if (!writer.post {
            try { if (!stopping) guarded(action) } finally { lease.close() }
        }) {
            lease.close()
            fatalPending.compareAndSet(null,"CALLBACK_OWNER_CLOSED_TAIL_UNKNOWN")
        }
    }
    internal fun enqueue(action: () -> Unit) {
        if (queued.incrementAndGet() > 256) {
            queued.decrementAndGet()
            fatalPending.compareAndSet(null,"CALLBACK_QUEUE_OVERFLOW_UNCOMMITTED_TAIL_UNKNOWN")
            // One failure message, not an unbounded queue of dropped callbacks.
            if (overflow.compareAndSet(false, true)) writer.post { guarded { finish("CALLBACK_QUEUE_OVERFLOW_UNCOMMITTED_TAIL_UNKNOWN") } }
            return
        }
        if (!writer.post { queued.decrementAndGet(); if (!stopping) guarded(action) }) {
            queued.decrementAndGet()
            fatalPending.compareAndSet(null,"CALLBACK_OWNER_CLOSED_TAIL_UNKNOWN")
        }
    }
    private val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun beginEcg(next: String) {
        shortSensor = "ECG"
        phase = next; ecgPhase = next; ecgFailed = false; ecgFailureReason = ""
        notice = "请把手指轻放在 Home 键；检测到稳定接触后立即开始30秒。80秒没有接触才会自动退出。"
        contact = false; ecgTotalRows = 0
        lastContactMs = 0; ecgGate = EcgContinuityGate(); ecgUpdate = null
        // The no-contact safety starts with this ECG phase, not with the session. Pre-BIA/
        // SpO2/PPG work must not consume the ECG contact window before ECG even begins.
        shortBudgetStartMs = clock()
        deadline = clock() + EcgWaitBudget.IDLE_MS
        event("PHASE_START", next); startSource("ECG"); restartTick()
    }
    private fun beginCore() {
        shortSensor = ""; shortBudgetStartMs = 0L
        if (coreStartedMs == 0L) coreStartedMs = clock()
        coreEndedMs = 0L
        phase = "CORE-1"; notice = ""; coreDataObserved = false
        // If the user starts an activity and is then interrupted before any raw row arrives,
        // end the session and exit instead of holding the watch awake indefinitely.
        deadline = CoreDataBudget.initialDeadline(clock())
        event("PHASE_START", phase)
        startingSources = true
        coreSources.forEach(::startSource)
        startingSources = false
        if (running.isEmpty()) finish("ALL_CORE_SOURCES_FAILED") else restartTick()
    }
    private fun placementWaiting() = phase.startsWith("READY_SPO2_") || phase.startsWith("READY_PPG_") || phase == "RESTORE_PRE"
    private fun beginPostShorts() {
        // Body composition is intentionally pre-activity only. Post-exercise BIA/MF-BIA
        // is dominated by acute fluid shifts and is not comparable to the pre measurement.
        // PPG starts immediately after post ECG; only the final SpO2 needs a placement pause.
        if(postPpg) beginPpg(false) else if(postSpo2) prepareSpo2(false) else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun startBia(pre: Boolean) {
        biaModeIndex=0
        startBiaMode(pre)
    }
    private fun startBiaMode(pre: Boolean) {
        if(biaModes.isEmpty()) { afterBiaPhase(); return }
        if(biaModeIndex !in biaModes.indices) biaModeIndex=0
        bodySource=biaModes[biaModeIndex]
        biaPre=pre
        biaContactStartMs=clock()
        shortBudgetStartMs=biaContactStartMs
        biaContactDetected=false
        biaRetrying=false; biaAttempt=0; biaProgress=-1; biaHint=""
        beginBia()
    }
    private fun biaModeLabel()=when(bodySource){"MF_BIA"->"多频阻抗";"BIA"->"体成分";else->"体成分"}
    private fun beginBia() {
        shortSensor="BIA"
        phase=if(biaPre) "PRE-${++preBiaAttempt}" else "POST-${++postBiaAttempt}"
        biaPhase=phase;biaSummary="";biaContactDetected=false
        biaRetrying=false;biaHint="";biaProgress=-1;biaAttempt++
        biaResults[phase]="正在等待双指接触，尚无完整有效结果";biaModeByPhase[phase]=bodySource
        deadline=clock()+60_000
        notice="准备：${biaModeLabel()}。保持手表背面贴紧皮肤即可，不需要调整到肘部。中指轻触上键、无名指轻触下键；双手不要相碰，抬起手臂使腋下张开。\n正在等待双指接触；检测到后自动开始；接触错误会立即自动重试；最多80秒。"
        event("BIA_CONTACT_WAIT_STARTED","source=$bodySource; pre=$biaPre; mode=$bodySource; deadlineMs=$deadline; profileInPlan=true")
        startSource(bodySource);restartTick()
    }
    private fun retryBia() {
        biaContactStartMs=clock(); shortBudgetStartMs=clock(); biaContactDetected=false
        biaRetrying=false; biaHint=""; biaProgress=-1; biaAttempt=0
        beginBia()
    }
    private fun isBiaRetryable(reason: String)=reason in setOf(
        "SDK_STATUS_4","SDK_STATUS_7","SDK_STATUS_8","SDK_STATUS_9","SDK_STATUS_10",
        "SDK_STATUS_11","SDK_STATUS_13","SDK_STATUS_14","SDK_STATUS_15","SDK_STATUS_17","SDK_STATUS_19")
    private fun biaRetryHint(reason: String)=when(reason) {
        "SDK_STATUS_4","SDK_STATUS_10" -> "请检查手表背面贴紧皮肤"
        "SDK_STATUS_7","SDK_STATUS_8","SDK_STATUS_9" -> "中指、无名指分别轻触上下键"
        "SDK_STATUS_11" -> "手指过干，请稍微改善接触"
        "SDK_STATUS_13" -> "接触不稳定，请保持手臂不动"
        "SDK_STATUS_14","SDK_STATUS_15" -> "双手不要相碰，手指不要碰表框"
        "SDK_STATUS_17" -> "请保持双臂自然张开"
        "SDK_STATUS_19" -> "信号质量低，请贴紧手表、抬高手臂"
        else -> "请调整姿势和接触"
    }
    private fun endBia(success:Boolean, reason:String) {
        if(draining!=null || stopping)return
        if(!success) biaContactDetected=false
        drainSources {
            biaResults[biaPhase]=if(success) "成功：$biaSummary" else "未成功，未取得完整有效结果"
            event("BIA_MEASUREMENT_SAVED","success=$success; reason=$reason; pre=$biaPre; mode=$bodySource; attempt=$biaAttempt; progress=$biaProgress")
            if(success) {
                biaRetrying=false; biaHint=""
                publishSuccess("${biaModeLabel()} ${biaModeIndex + 1}/${biaModes.size.coerceAtLeast(1)}", biaSummary)
                afterBia()
            } else if(isBiaRetryable(reason) && clock() < biaContactStartMs + SHORT_BUDGET_MS) {
                biaContactDetected=false
                biaRetrying=true
                biaHint=biaRetryHint(reason)
                phase=if(biaPre) "WAIT_BIA_PRE" else "WAIT_BIA_POST"
                deadline=minOf(biaContactStartMs + SHORT_BUDGET_MS, clock() + BIA_CONTACT_RETRY_MS)
                val reached=if(biaProgress>0) "已到 $biaProgress%" else "未检测到稳定接触"
                val extra=if(!biaPre && bodySource=="MF_BIA" && biaAttempt>=2 && biaProgress>=80)
                    "\n活动后 MF-BIA 多次在最后一步信号不足；可继续保持接触重试，或点“跳过体成分，继续”。" else ""
                notice="测量中断：$reached（$biaHint）。请保持双指接触，${BIA_CONTACT_RETRY_MS/1000}秒后自动重试（第 ${biaAttempt+1} 次，最多80秒）。$extra"
                event("BIA_CONTACT_RETRY_WAIT","reason=$reason; attempt=$biaPhase; nextAttempt=${biaAttempt+1}; progress=$biaProgress; mode=$bodySource")
                holdDecisionWindow();restartTick()
            } else if(isBiaRetryable(reason)) {
                biaRetrying=false
                finish("SHORT_MEASUREMENT_TIMEOUT_80S", true)
            } else {
                biaRetrying=false
                phase=if(biaPre) "REVIEW_BIA_PRE" else "REVIEW_BIA_POST"
                val guide=when(reason) {
                    "SDK_STATUS_18" -> "请在活动管理中检查个人资料"
                    else -> "请检查姿势和接触，也可跳过继续活动"
                }
                notice="体成分未完成：$guide（$reason）。已有记录已保存；80秒不操作则停止。"
                deadline=clock()+80_000;holdDecisionWindow();restartTick()
            }
        }
    }
    private fun skipBia() {
        if(draining!=null || stopping) return
        shortFailure=true; biaContactDetected=false; biaRetrying=false
        event("USER_SKIPPED_BIA","pre=$biaPre; mode=$bodySource; phase=$phase")
        drainSources {
            biaResults[biaPhase]="未取得有效结果，已跳过"
            if(biaModeIndex + 1 < biaModes.size) {
                biaModeIndex++
                startBiaMode(biaPre)
            } else afterBiaPhase()
        }
    }
    private fun afterBia() {
        if(biaModeIndex + 1 < biaModes.size) {
            biaModeIndex++
            startBiaMode(biaPre)
            return
        }
        afterBiaPhase()
    }
    private fun afterBiaPhase() {
        if(biaPre) {
            if(preSpo2) prepareSpo2(true) else restorePlacement()
        } else if(postSpo2) prepareSpo2(false)
        else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun prepareSpo2(pre: Boolean) {
        shortSensor = "SPO2"; spo2Pre = pre
        phase = if(pre) "READY_SPO2_PRE" else "READY_SPO2_POST"
        shortBudgetStartMs = clock()
        notice = "请将手表向肘部上移并贴合皮肤，手肘放在桌上，手腕靠近心脏，保持静止。\n${NO_HAND_AUTOSTART_MS / 1000}秒后自动开始；也可提前点开始。80秒内没有有效结果会保存并退出。"
        deadline = clock() + NO_HAND_AUTOSTART_MS
        event("SPO2_PLACEMENT_PREPARATION", "pre=$pre; autostartMs=$NO_HAND_AUTOSTART_MS; sensorsNotStarted=true")
        holdDecisionWindow(); restartTick()
    }
    private fun beginSpo2(pre: Boolean) {
        shortSensor = "SPO2"; spo2Pre = pre
        phase = if(pre) "PRE-${++preSpo2Attempt}" else "POST-${++postSpo2Attempt}"
        spo2Phase = phase
        spo2Value = null
        spo2Results[phase] = "测量中，尚无有效结果"
        deadline = clock() + 30_000
        notice = "血氧测量中，请保持静止。获得有效结果即结束，最多等待30秒；没有连续回调也可能是正常计算。"
        event("SPO2_MEASUREMENT_STARTED", "pre=$pre; deadlineMs=$deadline")
        startSource("SPO2"); restartTick()
    }
    private fun endSpo2(success: Boolean, reason: String) {
        if(draining != null || stopping) return
        drainSources {
            spo2Results[spo2Phase] = if(success) "成功 ${spo2Value}%" else "未成功，未取得有效结果"
            event("SPO2_MEASUREMENT_SAVED", "success=$success; reason=$reason; pre=$spo2Pre; value=$spo2Value")
            if(success) { publishSuccess("血氧", spo2Value?.let { "$it%" } ?: "已成功"); afterSpo2() } else {
                phase = if(spo2Pre) "REVIEW_SPO2_PRE" else "REVIEW_SPO2_POST"
                val explanation = when(reason) {
                    "SDK_STATUS_-4" -> "检测到移动，请重新调整并保持静止"
                    "SDK_STATUS_-5" -> "信号质量不足，请检查佩戴位置和贴合"
                    "SDK_STATUS_-6", "MEASUREMENT_TIMEOUT_30S" -> "30秒内未取得有效结果"
                    else -> "测量未完成"
                }
                notice = "血氧未成功：$explanation（$reason）。已有数据已保存；点重测会立即开始，或跳过继续；80秒不操作则退出。"
                deadline=clock()+80_000; holdDecisionWindow(); restartTick()
            }
        }
    }
    private fun afterSpo2() {
        // Pre PPG/BIA already ran before SpO2. After SpO2 the only remaining placement
        // change is ECG (or a normal-position restore before CORE).
        if(spo2Pre) restorePlacement()
        else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    /** PPG has no placement gate; start immediately instead of showing a 15 s wait screen. */
    private fun preparePpg(pre: Boolean) = beginPpg(pre)
    private fun beginPpg(pre: Boolean) {
        shortSensor = "PPG_ON_DEMAND"; ppgPre = pre
        phase = if(pre) "PRE-${++prePpgAttempt}" else "POST-${++postPpgAttempt}"
        shortBudgetStartMs = clock()
        ppgPhase = phase
        ppgRows = 0
        ppgResults[phase] = "测量中，尚无原始记录"
        deadline = clock() + 30_000
        notice = "PPG 测量中，请保持静止；最多采集30秒，原始绿/红外/红记录会原样保存。"
        event("PPG_MEASUREMENT_STARTED", "pre=$pre; deadlineMs=$deadline")
        startSource("PPG_ON_DEMAND"); restartTick()
    }
    private fun endPpg(success: Boolean, reason: String) {
        if(draining != null || stopping) return
        drainSources {
            ppgResults[ppgPhase] = if(success) "已采集 $ppgRows 条原始记录" else "未采集到原始记录"
            event("PPG_MEASUREMENT_SAVED", "success=$success; reason=$reason; pre=$ppgPre; rawRows=$ppgRows")
            if(success) { publishSuccess("PPG", "已采集 $ppgRows 条原始记录"); afterPpg() } else {
                phase = if(ppgPre) "REVIEW_PPG_PRE" else "REVIEW_PPG_POST"
                notice = "PPG 未取得原始记录（$reason）。已有数据已保存；点重测会立即开始，或跳过继续；80秒不操作则退出。"
                deadline = clock() + 80_000; holdDecisionWindow(); restartTick()
            }
        }
    }
    private fun afterPpg() {
        if(ppgPre) {
            if(bia) startBia(true)
            else if(preSpo2) prepareSpo2(true)
            else restorePlacement()
        } else if(postSpo2) prepareSpo2(false)
        else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun restorePlacement() {
            phase = "RESTORE_PRE"; shortSensor = ""; shortBudgetStartMs = 0L
            notice = "请将手表调回平时运动位置，保持贴合。\n${NO_HAND_AUTOSTART_MS / 1000}秒后自动继续；也可点“已恢复佩戴”。"
            deadline = clock() + NO_HAND_AUTOSTART_MS
            event("NORMAL_PLACEMENT_REQUESTED", "autostartMs=$NO_HAND_AUTOSTART_MS; sensorsNotStarted=true")
            holdDecisionWindow(); restartTick()
    }
    /** Any non-empty raw row from a core source disarms the no-data watchdog. Not a clinical validity judgment. */
    private fun noteCoreData(origin: String, sensor: String, rows: List<Map<String, Any?>>) {
        if (origin != "CORE-1" || rows.isEmpty()) return
        val first = !coreDataObserved
        coreDataObserved = true
        if (phase == "CORE-1") deadline = 0
        if (first && phase == "CORE-1") {
            event("CORE_DATA_OBSERVED", "first non-empty row from $sensor; no-data watchdog disarmed; not a clinical validity judgment")
        }
    }
    /** Low-rate context metadata; never inferred from sensor batch sizes. */
    private fun sampleDeviceState() {
        try {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level in 0..100 && scale > 0) level * 100 / scale else null
            val status = batteryStatusName(battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
            val plugged = pluggedName(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it > -1000 }
            val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it >= 0 }
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            val display = getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val row = DeviceState.row(percent, status, plugged, temperature, voltage,
                power?.isInteractive, keyguard?.isKeyguardLocked, displayStateName(display?.state),
                System.currentTimeMillis(), clock(), streamPhase())
            appendRows(streamPhase(), SensorCatalog.DEVICE_STATE, listOf(row))
            lastDeviceStateMs = clock()
        } catch (e: Exception) {
            event("DEVICE_STATE_FAILED", "${e.javaClass.simpleName}:${e.message}")
        }
    }
    private fun batteryStatusName(value: Int) = when (value) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }
    private fun pluggedName(value: Int) = when (value) {
        0 -> "none"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "other_$value"
    }
    private fun displayStateName(value: Int?): String? = when (value) {
        null -> null
        Display.STATE_OFF -> "off"
        Display.STATE_ON -> "on"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze_suspend"
        Display.STATE_VR -> "vr"
        Display.STATE_ON_SUSPEND -> "on_suspend"
        else -> "unknown_$value"
    }
    private fun startSource(sensor: String) {
        val origin = phase
        val qualityKey = "$origin/$sensor"
        val run = captureRuns.start(origin,sensor)
        running.add(sensor)
        qualityTrackers[qualityKey] = StreamQualityTracker(sensor)
        lastData[sensor] = clock(); silent.remove(sensor)
        event("SENSOR_START_REQUEST", sensor)
        adapter!!.start(sensor, { rows ->
            noteCoreData(origin, sensor, rows)
            if (phase != origin || !captureRuns.current(run)) {
                // Preserve late delivered samples under their originating phase, never relabel them.
                appendRows(origin, sensor, rows)
                // A held core PPG keeps delivering after CORE-1 ends; that is still live
                // data, not silence. Stops that already removed the source from running
                // do not get this refresh.
                if (rows.isNotEmpty() && running.contains(sensor)) lastData[sensor] = clock()
                event("LATE_CALLBACK_SAVED", "$origin/$sensor:${rows.size}")
            } else {
                if (rows.isNotEmpty()) {
                    lastData[sensor] = clock()
                    if (silent.remove(sensor)) event("DATA_RESUMED", sensor)
                }
                qualityTrackers[qualityKey]?.observe(rows)
                buffers.getOrPut(sensor) { mutableListOf() }.addAll(rows)
                if (sensor == bodySource && rows.isNotEmpty() && draining == null) {
                    if(clock() >= deadline) { endBia(false,"MEASUREMENT_TIMEOUT_60S"); return@start }
                    val progress=(rows.lastOrNull()?.get("PROGRESS") as? Number)?.toDouble()
                    val percent=progress?.let { if(bodySource=="BIA") it*100 else it }
                    val result = rows.firstOrNull { if(bodySource=="MF_BIA") com.rawwave.core.BiaResult.multiFrequencyComplete(it) else com.rawwave.core.BiaResult.complete(it) }
                    val failure = rows.firstOrNull { ((it["STATUS"] as? Number)?.toInt() ?: 0).let { status -> status > 0 && !(bodySource=="MF_BIA" && status==23) } }
                    if(percent!=null && percent.isFinite() && percent in 0.0..100.0 && (failure==null || percent>0.0))
                        biaProgress=percent.toInt().coerceIn(0,100)
                    if(result != null) {
                        biaSummary=if(bodySource=="MF_BIA") "四频阻抗及相位已取得" else "体脂率 ${result["BODY_FAT_RATIO"]}%，骨骼肌 ${result["SKELETAL_MUSCLE_MASS"]} kg"
                        biaResults[biaPhase]="已取得结果，正在保存"
                        endBia(true,"SDK_STATUS_0_VALID_RESULT")
                    } else if(failure != null) endBia(false,"SDK_STATUS_${failure["STATUS"]}")
                    else {
                        if(!biaContactDetected) {
                            val contactStatus=rows.mapNotNull { (it["STATUS"] as? Number)?.toInt() }
                                .firstOrNull { it==0 || (bodySource=="MF_BIA" && it==23) }
                            if(contactStatus!=null) {
                                biaContactDetected=true
                                biaRetrying=false; biaHint=""
                                notice="已检测到接触，测量中；保持双指轻触两键，双手不要相碰。"
                                event("BIA_CONTACT_DETECTED","mode=$bodySource; status=$contactStatus")
                            }
                        }
                        biaResults[biaPhase]=if(percent!=null && percent.isFinite() && percent in 0.0..100.0)
                            "测量中：${percent.toInt()}%，请保持接触" else "等待完整有效结果，请保持接触"
                        publish()
                    }
                }
                if (sensor == "SPO2" && rows.isNotEmpty() && draining == null) {
                    if(clock() >= deadline) { endSpo2(false,"MEASUREMENT_TIMEOUT_30S"); return@start }
                    val completed = rows.firstOrNull { (it["STATUS"] as? Number)?.toInt() == 2 &&
                        (it["SPO2"] as? Number)?.toDouble()?.let { v -> v.isFinite() && v > 0.0 && v <= 100.0 } == true }
                    val failed = rows.firstOrNull { ((it["STATUS"] as? Number)?.toInt() ?: 0) < 0 }
                    if(completed != null) {
                        spo2Value = (completed["SPO2"] as Number).toInt()
                        spo2Results[spo2Phase] = "已获得 ${spo2Value}%，正在保存"
                        endSpo2(true,"SDK_STATUS_2")
                    }
                    else if(failed != null) endSpo2(false,"SDK_STATUS_${failed["STATUS"]}")
                    else {
                        spo2Results[spo2Phase] = if(rows.any { (it["STATUS"] as? Number)?.toInt() == 0 })
                            "计算中，尚无有效结果" else "尚无有效结果，等待测量完成"
                        publish()
                    }
                }
                if (sensor == "PPG_ON_DEMAND" && rows.isNotEmpty() && draining == null) {
                    ppgRows += rows.size
                    ppgResults[ppgPhase] = "已接收 $ppgRows 条原始记录"
                    if (clock() >= deadline) {
                        endPpg(true, "MEASUREMENT_WINDOW_COMPLETE_30S")
                        return@start
                    }
                    notice = "PPG 测量中，已接收 $ppgRows 条原始记录；保持静止至30秒结束。"
                    publish()
                }
                if (sensor == "ECG" && rows.isNotEmpty() && draining == null) {
                    // A late SDK batch cannot revive an expired no-contact wait.
                    if (EcgWaitBudget.mustStop(deadline,clock(),ecgGate.measuring(),false)) {
                        finish("ECG_IDLE_TIMEOUT_80S", true)
                        return@start
                    }
                    val batch = EcgBatchQuality.inspect(rows)
                    ecgTotalRows += rows.size
                    contact = batch.contactValid()
                    lastContactMs = clock()
                    val update = ecgGate.observe(batch)
                    ecgUpdate = update
                    when (update.status()) {
                        EcgContinuityGate.Status.STARTED -> {
                            // The 80-second guard is "no contact", not "seconds since the phase
                            // began". Contact just arrived, so the retry/idle budget restarts.
                            shortBudgetStartMs = clock()
                            deadline = clock() + 45_000
                            notice = "接触有效，正在连续采集；中途断开会从头开始。"
                            event("ECG_CONTINUOUS_SEGMENT_STARTED", "segment=${update.segment()}; firstSdkTimestampMs=${update.firstTimestampMs()}")
                        }
                        EcgContinuityGate.Status.RESET -> {
                            val failures = retryPolicy(phase.startsWith("PRE")).recordFailure()
                            // A reset is not "no contact"; the user is still holding the
                            // electrode and may be retrying. Renew both idle timers.
                            shortBudgetStartMs = clock()
                            deadline = clock() + 80_000
                            notice = "接触或数据连续性中断（第${failures}次），本次已作废。重新放好后从30秒开始。" +
                                if (failures >= 2) "你也可以选择跳过 ECG，继续本次活动。" else ""
                            event("ECG_CONTINUITY_RESET", "segment=${update.segment()}; failures=$failures; reason=${update.reason()}; retainedRawRows=$ecgTotalRows")
                        }
                        EcgContinuityGate.Status.WAITING -> {
                            notice = if (update.reason().startsWith("LEAD_OFF"))
                                "请把手指轻放在 Home 键，检测到稳定接触后开始30秒。"
                            else "当前 ECG 数据不满足连续测量条件：${update.reason()}"
                        }
                        EcgContinuityGate.Status.COMPLETE -> {
                            event("ECG_CONTINUOUS_30S_VERIFIED", "segment=${update.segment()}; samples=${update.continuousSamples()}; coverageMs=${update.coverageMs()}; firstSdkTimestampMs=${update.firstTimestampMs()}; lastSdkTimestampMs=${update.lastTimestampMs()}")
                            endEcg()
                        }
                        EcgContinuityGate.Status.CONTINUING -> Unit
                    }
                }
                if ((buffers[sensor]?.size ?: 0) >= 1000) flush()
            }
        }, { error ->
            if (draining != null && phase == origin && captureRuns.current(run)) {
                event("SENSOR_FAILED_DURING_DRAIN", "$sensor:$error")
                finish("SENSOR_FAILURE_DURING_DRAIN:$sensor:$error")
                return@start
            }
            if (phase == origin && captureRuns.end(run)) {
                running.remove(sensor)
                try { adapter?.stop(sensor) } catch (e: Exception) { event("SENSOR_STOP_FAILED", "${e.message}") }
                emitQuality(origin, sensor)
                event("SENSOR_FAILED", "$sensor:$error")
                if (sensor == "ECG") {
                    ecgFailed = true
                    ecgFailureReason = "SDK_FAILURE:$error"
                    endEcg()
                } else if(sensor == bodySource) {
                    endBia(false,"SDK_FAILURE:$error")
                } else if(sensor == "SPO2") {
                    endSpo2(false,"SDK_FAILURE:$error")
                } else if(sensor == "PPG_ON_DEMAND") {
                    endPpg(false,"SDK_FAILURE:$error")
                } else {
                    notice = "$sensor 失败：$error；其他数据保留"
                    if (phase.startsWith("CORE") && running.isEmpty() && !startingSources) finish("ALL_CORE_SOURCES_FAILED")
                }
            } else event("STALE_SENSOR_FAILURE", "$origin/$sensor:$error")
        })
    }
    private fun endEcg() {
        if (draining != null || stopping) return
        // A held core PPG must survive the ECG drain; it is stopped only after post ECG.
        drainSources(keep = setOf("PPG")) { completeEcg() }
    }
    private fun completeEcg() {
        deadline = 0
        val update = ecgUpdate
        event("ECG_ATTEMPT_END", "continuousVerified=${ecgGate.complete()}; rawRows=$ecgTotalRows; segment=${update?.segment()}; continuousSamples=${update?.continuousSamples()}; coverageMs=${update?.coverageMs()}; invalid fragments retained; no diagnostic interpretation")
        val wasPre = phase.startsWith("PRE")
        val next = CapturePhasePolicy.ecgEnded(wasPre, ecgFailed, ecgGate.complete())
        if (next == CapturePhasePolicy.Next.REVIEW_PRE || next == CapturePhasePolicy.Next.REVIEW_POST) {
            val policy = retryPolicy(wasPre)
            val failures = policy.recordFailure()
            event("ECG_FAILED", "failures=$failures; rawRows=$ecgTotalRows; failureReason=$ecgFailureReason; lastBatchReason=${update?.reason()}; incomplete=$ecgFailed")
            phase = next.name
            notice = "没有取得连续有效的30秒 ECG（累计失败${failures}次）。\n" +
                if (policy.skipAllowed()) "可以不限次数继续重测，也可以跳过 ECG 后继续活动；80秒无选择则停止。"
                else "请继续重测；连续失败两次后才会提供跳过选项。80秒无选择则停止。"
            deadline = clock() + 80_000
            if (ecgFailureReason == "ECG_CALLBACK_SILENCE_15S") notice = "ECG 已连续15秒没有新数据，本次未完成；已有片段已保存。\n$notice"
            holdDecisionWindow()
            // Keep only this bounded decision window awake; finish() releases the lock.
            writer.removeCallbacks(tick); writer.post(tick)
        } else {
            if (update != null && ecgTotalRows > update.continuousSamples())
                event("ECG_RAW_INCLUDES_INVALID_SEGMENTS", "rawRows=$ecgTotalRows; acceptedContinuousSamples=${update.continuousSamples()}; all rows retained")
            publishSuccess("ECG", "已取得连续 30 秒记录")
            if (!wasPre) continueAfterPostEcg { beginPostShorts() }
            else if (next == CapturePhasePolicy.Next.CORE) beginCore() else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
        }
    }
    private fun publishSuccess(kind: String, text: String) {
        main.post { successObserver?.invoke(kind, text.ifBlank { "已成功" }) }
    }
    private fun retryPolicy(pre: Boolean) = if (pre) preRetryPolicy else postRetryPolicy
    private fun skipEcg() {
        val wasPre = phase.startsWith("PRE") || phase == "REVIEW_PRE" || ecgPhase.startsWith("PRE")
        val policy = retryPolicy(wasPre)
        if (!policy.skipAllowed()) {
            notice = "连续失败两次后才可跳过；你仍可继续重测。"
            event("USER_SKIP_ECG_REJECTED", "failures=${policy.failures()}")
            publish()
            return
        }
        if (phase.startsWith("PRE") || phase.startsWith("POST")) {
            // Keep a held core PPG alive until the post-ECG skip path releases it last.
            drainSources(keep = setOf("PPG")) { completeSkip(wasPre, policy.failures()) }
        } else completeSkip(wasPre, policy.failures())
    }
    /** Test-only: skip a failing pre ECG and proceed to CORE while keeping post ECG enabled. */
    private fun forceSkipPreEcg() {
        if (!(phase.startsWith("PRE") || phase == "REVIEW_PRE")) return
        event("TEST_FORCED_SKIP_PRE_ECG", "phase=$phase; rawRows=$ecgTotalRows")
        if (phase.startsWith("PRE")) {
            drainSources { completeForcedSkipPreEcg() }
        } else completeForcedSkipPreEcg()
    }
    private fun completeForcedSkipPreEcg() {
        deadline = 0
        event("ECG_ATTEMPT_END", "userSkipped=true; rawRows=$ecgTotalRows; failures=${preRetryPolicy.failures()}; TEST_FORCED_SKIP_PRE_ECG")
        event("USER_SKIPPED_FAILED_ECG", "$ecgPhase; failures=${preRetryPolicy.failures()}; TEST_FORCED_SKIP_PRE_ECG")
        shortFailure = true
        beginCore()
    }
    private fun completeSkip(wasPre: Boolean, failures: Int) {
        if (phase.startsWith("PRE") || phase.startsWith("POST")) {
            deadline = 0
            event("ECG_ATTEMPT_END", "userSkipped=true; rawRows=$ecgTotalRows; failures=$failures; invalid fragments retained")
        }
        event("USER_SKIPPED_FAILED_ECG", "$ecgPhase; failures=$failures")
        shortFailure = true
        if (wasPre) beginCore() else continueAfterPostEcg { beginPostShorts() }
    }
    /** Release a core PPG held through post ECG before starting the remaining post shorts. */
    private fun continueAfterPostEcg(next: () -> Unit) {
        if (running.contains("PPG")) drainSources { next() } else next()
    }
    private fun restartTick() {
        renewCaptureLock()
        writer.removeCallbacks(tick); writer.post(tick)
    }
    /** Cover the whole decision deadline in one bounded acquisition, including screen-off. */
    private fun holdDecisionWindow() {
        val duration = (deadline - clock()).coerceIn(1, 80_000) + 5_000
        lock?.acquire(duration)
        lockRenewed = clock()
        event("CAPTURE_DECISION_WINDOW_ARMED", "phase=$phase; deadlineMs=$deadline; lockTimeoutMs=$duration; wakeLockHeld=${lock?.isHeld == true}; uptimeMs=${SystemClock.uptimeMillis()}")
    }
    private fun renewCaptureLock() {
        val now = clock()
        lock?.let {
            if (!it.isHeld || now - lockRenewed >= 30_000) { it.acquire(60_000); lockRenewed = now }
        }
    }
    private fun stopSources(sources: List<String>) {
        val origin = phase
        var failure: Exception? = null
        // Keep the continuous PPG as the last 25 Hz client when both it and ECG are
        // being torn down together (for example a user cancel during post ECG).
        sources.sortedBy { if (it == "PPG") 1 else 0 }.forEach { sensor ->
            if (!running.remove(sensor)) return@forEach
            captureRuns.endSensor(sensor)
            try { adapter?.stop(sensor) } catch (e: Exception) { failure = e }
            // A diagnostic write failure must not skip stopping the remaining sensors.
            try { event("SENSOR_STOP_REQUEST", sensor); emitQuality(origin, sensor) }
            catch (e: Exception) { failure = e }
        }
        failure?.let { throw it }
    }
    /**
     * Keep the originating phase and data delivery alive until every drained source hands off.
     * [keep] is used only for the post-ECG LED case: the core PPG stays live while the ECG
     * drains, so its final unset is a real first unset after the vendor HRM re-open.
     */
    private fun drainSources(keep: Set<String> = emptySet(), allowPartialPost: Boolean = false,
                             only: Set<String>? = null, next: () -> Unit) {
        if (draining != null || stopping) return
        val sources = running.toList().filterNot { it in keep }.filter { only == null || it in only }
        val drain = CaptureDrain(sources)
        draining = drain
        writer.removeCallbacks(tick)
        val expires = clock() + 5_000
        val timeout = Runnable {
            if (draining === drain) guarded {
                drain.timeout()
                // Emergency cleanup is not evidence of a complete tail.
                try { stopSources(sources) }
                finally { writer.post { guarded { completeDrain(drain, sources, next, allowPartialPost) } } }
            }
        }
        // Separate bounded cleanup budget, not a restarted 80-second user wait.
        writer.postDelayed(timeout, 5_000)
        renewCaptureLock()
        event("DRAIN_REQUESTED", "phase=$phase; sources=$sources; keep=$keep; cleanupBudgetMs=5000")
        publish()
        for (sensor in sources) {
            try {
                adapter!!.drain(sensor) { status, detail ->
                      if (draining === drain && clock() >= expires) {
                          timeout.run()
                      } else if (draining === drain && drain.complete(sensor, status, detail)) {
                        captureRuns.endSensor(sensor); running.remove(sensor)
                        event("SOURCE_DRAIN_COMPLETED", "$sensor; status=$status; detail=$detail")
                        emitQuality(phase, sensor)
                        completeDrain(drain, sources, next, allowPartialPost)
                    }
                }
            } catch (e: Exception) {
                drain.complete(sensor, SensorPort.DrainStatus.FAILED, "DRAIN_THROW:${e.message}")
            }
        }
        // Also covers no active sources and inline completion/throw from a replacement.
        writer.post { guarded { completeDrain(drain, sources, next, allowPartialPost) } }
    }
    private fun completeDrain(drain: CaptureDrain, sources: List<String>, next: () -> Unit, allowPartialPost: Boolean) {
        if (draining !== drain) return
        drain.persist({
            // Data callbacks preceding each completion have already run on this writer.
            stopSources(sources)
            persistence?.barrier()
            flush()
            persistence?.barrier()
            event("DRAIN_ROWS_PERSISTED", "phase=$phase; counts=${persistedCounts()}; sourceFailures=${drain.failures()}; SDK atomic stop not guaranteed")
        }, { failure ->
        draining = null
        val terminal = pendingFinish
        pendingFinish = null
        when {
            fatalPending.get() != null -> finishNow(fatalPending.get()!!, terminal?.second ?: false)
            destroyed -> finishNow("SERVICE_DESTROYED_INTERRUPTION", terminal?.second ?: false)
              failure != null && allowPartialPost && terminal == null &&
                  failure.startsWith("DRAIN_INCOMPLETE:") && !failure.contains("DRAIN_SAVE_FAILED:") &&
                  drain.failures().values.all { it == "DRAIN_TIMEOUT_TAIL_UNKNOWN" } -> {
                  incompleteTail = failure
                  event("CORE_TAIL_UNCONFIRMED_POST_CONTINUES", "$failure; accepted rows persisted; sources stopped; post measurements follow")
                  next()
              }
            failure != null -> finishNow(failure, terminal?.second ?: false)
            terminal != null -> finishNow(terminal.first, terminal.second)
            else -> next()
        }
        })
    }
    private fun emitPlatformSensorInventory() {
        try {
            val manager = getSystemService(android.hardware.SensorManager::class.java)
            if (manager == null) { event("PLATFORM_SENSOR_INVENTORY_FAILED", "SensorManager unavailable"); return }
            val sensors = manager.getSensorList(android.hardware.Sensor.TYPE_ALL)
            val detail = sensors.joinToString("|") { s ->
                "${s.type}:${s.stringType}:${s.name}:${s.vendor}:v${s.version}:minDelayUs=${s.minDelay}:wakeUp=${s.isWakeUpSensor}:maxRange=${s.maximumRange}:resolution=${s.resolution}:powerMa=${s.power}:fifoMax=${s.fifoMaxEventCount}:fifoReserved=${s.fifoReservedEventCount}:reportingMode=${s.reportingMode}"
            }
            event("PLATFORM_SENSOR_INVENTORY", "count=${sensors.size}; fields=type:stringType:name:vendor:version:minDelayUs:wakeUp:maxRange:resolution:powerMa:fifoMax:fifoReserved:reportingMode; data=$detail")
        } catch (e: Exception) {
            event("PLATFORM_SENSOR_INVENTORY_FAILED", "${e.javaClass.simpleName}:${e.message}")
        }
    }
    private fun emitQuality(origin: String, sensor: String) {
        qualityTrackers.remove("$origin/$sensor")?.let {
            event("STREAM_QUALITY", "$origin/$sensor:${it.summary()}; computed metadata only; raw rows unchanged")
        }
    }
    private fun flush() {
        buffers.forEach { (sensor, rows) -> if (rows.isNotEmpty()) { appendRows(streamPhase(), sensor, rows); rows.clear() } }
    }
    private fun streamPhase() = when (phase) {
        "CONNECTING", "WAIT_BIA_PRE", "READY_SPO2_PRE", "READY_PPG_PRE", "RESTORE_PRE" -> "PRE-1"
        "WAIT_BIA_POST", "READY_SPO2_POST", "READY_PPG_POST" -> "POST-1"
        "REVIEW_PRE", "REVIEW_POST" -> ecgPhase
        "REVIEW_BIA_PRE", "REVIEW_BIA_POST" -> biaPhase
        "REVIEW_SPO2_PRE", "REVIEW_SPO2_POST" -> spo2Phase
        "REVIEW_PPG_PRE", "REVIEW_PPG_POST" -> ppgPhase
        else -> phase
    }
    private fun event(kind: String, detail: String, extra: Map<String, Any?> = emptyMap()) {
        val row = LinkedHashMap<String, Any?>()
        row["kind"] = kind; row["detail"] = detail
        row["utcMs"] = System.currentTimeMillis(); row["elapsedMs"] = clock()
        row.putAll(extra)
        appendRows(streamPhase(), "EVENT", listOf(row))
        (application as? ProbeApplication)?.journal?.let { journal ->
            val fields = LinkedHashMap<String, Any?>()
            fields["session"] = session?.id ?: "none"; fields["detail"] = detail
            fields.putAll(extra)
            persistence?.journalEvent(journal,"CAPTURE_$kind",fields) ?: journal.event("CAPTURE_$kind",fields)
        }
    }
    private fun publish() {
        val count = countText()
        val title = when {
            phase == "RESTORE_PRE" -> "恢复运动佩戴位置"
            shortSensor == "BIA" -> if(biaPre) "活动前 ${biaModeLabel()}" else "活动后 ${biaModeLabel()}"
            shortSensor == "SPO2" -> if(spo2Pre) "活动前血氧 SpO₂" else "活动后血氧 SpO₂"
            shortSensor == "PPG_ON_DEMAND" -> if(ppgPre) "活动前 PPG 100Hz" else "活动后 PPG 100Hz"
            phase == "CONNECTING" -> "正在连接三星传感器"
            phase.startsWith("PRE") -> "活动前 ECG"
            phase.startsWith("POST") -> "活动后 ECG"
            phase == "CORE-1" -> "活动记录中\n${coreSources.joinToString("、", transform = ::sensorLabel)}同时采集" +
                (if (postEcg) "\n结束后还会测 ECG" else "") + (if(postSpo2) "\n结束后还会测血氧" else "") + (if(postPpg) "\n结束后还会测 PPG" else "")
            else -> "ECG 本次测量结束"
        }
        val ecgPhaseActive = shortSensor == "ECG" && (phase.startsWith("PRE") || phase.startsWith("POST"))
        val remaining = when {
            ecgPhaseActive && !ecgGate.measuring() -> -1
            ecgPhaseActive -> ecgGate.remainingSeconds()
            else -> ((deadline - clock()).coerceAtLeast(0) / 1000).toInt()
        }
        val policy = retryPolicy(phase.startsWith("PRE") || phase == "REVIEW_PRE" || ecgPhase.startsWith("PRE"))
        val idleHint = if (ecgPhaseActive && !ecgGate.measuring())
            "\n等待有效接触，${((deadline-clock()).coerceAtLeast(0)+999)/1000}秒后停止并退出；已有数据保留。"
            else ""
        val postLedHint = if (phase.contains("POST"))
            "\n活动后短测仍在进行，传感器灯亮着是正常的。短测结束后会停止采集；若灯仍长时间亮着，请发送问题报告。"
            else ""
        val coreElapsedSeconds = when {
            coreStartedMs <= 0L -> 0L
            coreEndedMs > 0L -> ((coreEndedMs - coreStartedMs).coerceAtLeast(0L)) / 1000L
            else -> ((clock() - coreStartedMs).coerceAtLeast(0L)) / 1000L
        }
        val phaseRemainingSeconds = ((deadline - clock()).coerceAtLeast(0L) / 1000L).toInt()
        val next = State(true, if (draining != null) "DRAINING" else phase,
            if (draining != null) "正在收尾并保存各传感器数据，请稍候（最多5秒收尾等待）\n$count"
            else "$title\n$notice$idleHint$postLedHint" + (if(shortSensor == "BIA" || shortSensor == "SPO2" || shortSensor == "PPG_ON_DEMAND" || placementWaiting()) "\n${when { shortSensor == "BIA" && phase.startsWith("WAIT_BIA_") && biaRetrying -> "测量中断，正在重试"; shortSensor == "BIA" && phase.startsWith("WAIT_BIA_") -> "等待双指接触"; shortSensor == "BIA" && !phase.startsWith("REVIEW_") -> "测量中，请保持接触"; placementWaiting() || phase.startsWith("REVIEW_") -> "等待操作 $remaining 秒"; else -> "最多等待 $remaining 秒" }}" else "") +
                (if (incompleteTail.isEmpty()) "" else "\n部分活动传感器尾部未确认，已收数据已保存；继续活动后短测。") + "\n采集记录：\n$count", remaining,
            contact && clock() - lastContactMs < 2000,
            policy.skipAllowed(), policy.failures(), shortSensor,
            if(shortSensor == "BIA") biaModeLabel() else "", biaModeIndex + 1 < biaModes.size, biaContactDetected,
            biaRetrying && shortSensor == "BIA" && phase.startsWith("WAIT_BIA_"), biaAttempt, biaProgress,
            if(shortSensor == "BIA") biaHint else "", coreElapsedSeconds,
            phaseRemainingSeconds, if (biaModes.isEmpty()) 0 else biaModeIndex + 1, biaModes.size)
        state = next; main.post { observer?.invoke(next) }
    }
    private fun countText(): String {
        val raw = persistedCounts()?.filterKeys { !it.endsWith("/EVENT") && !it.endsWith("/SPO2") && !it.endsWith("/BIA") && !it.endsWith("/MF_BIA") && !it.endsWith("/PPG_ON_DEMAND") }
            ?.entries?.sortedBy { it.key }?.map {
                val phaseName = when { it.key.startsWith("PRE") -> "活动前"; it.key.startsWith("POST") -> "活动后"; else -> "活动中" }
                "$phaseName ${sensorLabel(it.key.substringAfter('/'))}：${it.value} 条原始记录"
            }.orEmpty()
        val oxygen = spo2Results.map { (attempt, result) ->
            val label = if(attempt.startsWith("PRE")) "活动前" else "活动后"
            val number = attempt.substringAfter('-').toInt() - 99
            "$label 血氧第${number}次：$result"
        }
        val body = biaResults.map { (attempt,result) ->
            val mode=if(biaModeByPhase[attempt]=="MF_BIA") "多频阻抗" else "体成分"
            "${if(attempt.startsWith("PRE")) "活动前" else "活动后"} ${mode}第${attempt.substringAfter('-').toInt()-199}次：$result"
        }
        val ppgLines = ppgResults.map { (attempt,result) ->
            "${if(attempt.startsWith("PRE")) "活动前" else "活动后"} PPG 100Hz第${attempt.substringAfter('-').toInt()-299}次：$result"
        }
        return (body + oxygen + ppgLines + raw).joinToString("\n").ifEmpty { "暂无采集记录" }
    }    private fun sensorLabel(sensor: String) = when (sensor) {
        "ECG" -> "ECG"
        "SPO2" -> "血氧"
        "BIA" -> "体成分"
        "MF_BIA" -> "多频阻抗"
        "MAGNETIC_FIELD" -> "三轴磁场"
        "MAGNETIC_FIELD_UNCALIBRATED" -> "未校准磁场"
        "LIGHT" -> "环境光"
        "GPS" -> "GPS 轨迹"
        "STEP_COUNT" -> "步数（平台累计）"
        "STEP_DETECTOR" -> "每步事件"
        "OFFBODY" -> "佩戴状态"
        "PRESSURE" -> "气压"
        "GYROSCOPE" -> "陀螺仪"
        "GYROSCOPE_UNCALIBRATED" -> "未校准陀螺仪"
        "HEART_RATE" -> "心率"
        "ACCELEROMETER" -> "加速度"
        "PLATFORM_ACCELEROMETER" -> "平台加速度"
        "ACCELEROMETER_UNCALIBRATED" -> "未校准加速度"
        "LINEAR_ACCELERATION" -> "线性加速度"
        "GAME_ROTATION_VECTOR" -> "姿态四元数"
        "ROTATION_VECTOR" -> "绝对姿态"
        "GEOMAGNETIC_ROTATION_VECTOR" -> "地磁姿态"
        "GRAVITY" -> "重力矢量"
        "WRIST_TILT_GESTURE" -> "抬腕手势"
        "PPG" -> "PPG"
        "PPG_ON_DEMAND" -> "PPG 100Hz"
        "SKIN_TEMPERATURE" -> "皮肤温度"
        "EDA" -> "皮肤电导"
        else -> sensor
    }
    private fun guarded(action: () -> Unit) { try { action() } catch (e: Exception) { fail(e) } }
    private fun fail(error: Exception) {
        fatalPending.compareAndSet(null,"FAILURE:${error.javaClass.simpleName}:${error.message}")
        try { (application as ProbeApplication).journal?.fault("CAPTURE_FAILURE", error) } catch (_: Exception) { }
        finish("FAILURE:${error.javaClass.simpleName}:${error.message}")
    }
    /**
     * After sealing, keep the vendor connector alive for the adapter's release hold, then
     * release it, stop the service and remove this process. A dead process is the only
     * guarantee that no RawWave vendor handle or file descriptor survives the activity.
     * Main thread only.
     */
    private fun releaseVendorThenExit(text: String) {
        val app = application as ProbeApplication
        val holdMs = try { adapter?.releaseHoldMs() ?: 0L } catch (_: Exception) { 0L }
        if (holdMs <= 0L) {
            try { adapter?.releaseConnection() } catch (_: Exception) {}
            try { observer?.invoke(State(false, "DONE", text)) } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val adapterRef = adapter
        val lockRef = lock
        val done = State(false, "DONE",
            "$text\n传感器已停止，正在后台释放 ${holdMs / 1000} 秒；现在可以开始新的活动。")
        state = done
        releaseDeadlineMs = 0L
        try { observer?.invoke(done) } catch (_: Exception) {}
        try { app.journal?.event("WATCH_VENDOR_RELEASE_BACKGROUND",
            mapOf("holdMs" to holdMs, "reason" to terminalReason)) } catch (_: Exception) {}
        // Stop the capture service immediately. The application-level keeper holds the
        // vendor connector for the LED release, so the home is usable and a new activity
        // can start without waiting for the 60-second hold.
        app.scheduleSensorRelease(holdMs, lockRef) {
            try { adapterRef?.releaseConnection() } catch (e: Exception) {
                try { app.journal?.event("WATCH_VENDOR_RELEASE_FAILED",
                    mapOf("error" to e.toString())) } catch (_: Exception) {}
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Kill this process after the release hold unless a manual sync is still using it.
     * Every deferred check rewrites the state, so the UI never stays on RELEASING.
     */
    private fun scheduleProcessExitWhenIdle(app: ProbeApplication, holdMs: Long, attempt: Int) {
        if (state.active) {
            try { app.journal?.event("WATCH_PROCESS_EXIT_SKIPPED",
                mapOf("captureActive" to true, "deferredChecks" to attempt)) } catch (_: Exception) {}
            return
        }
        if (app.activitySyncStatus.running && attempt < 120) {
            if (state.phase == "RELEASING") {
                val done = State(false, "DONE", "传感器已释放；同步结束后 App 会自动关闭。")
                state = done
                releaseDeadlineMs = 0L
                // Repaint now: render() will show the sync page while it is running.
                try { observer?.invoke(done) } catch (_: Exception) {}
            }
            main.postDelayed({ scheduleProcessExitWhenIdle(app, holdMs, attempt + 1) }, 5_000L)
            return
        }
        releaseDeadlineMs = 0L
        try { app.journal?.event("WATCH_PROCESS_EXIT",
            mapOf("afterReleaseMs" to holdMs, "deferredChecks" to attempt,
                "reason" to terminalReason)) } catch (_: Exception) {}
        main.postDelayed({
            // Re-check at the last moment: the user may have started a new capture in the
            // short gap between scheduling and the kill.
            if (state.active) {
                try { app.journal?.event("WATCH_PROCESS_EXIT_SKIPPED",
                    mapOf("captureActive" to true, "atKill" to true)) } catch (_: Exception) {}
                return@postDelayed
            }
            Process.killProcess(Process.myPid())
        }, VENDOR_RELEASE_KILL_DELAY_MS)
    }

    private fun finish(reason: String, idleExit: Boolean = false) {
        if (stopping) return
        if (pendingFinish == null) pendingFinish = reason to idleExit
        if (phase != "DRAINING") try { sampleDeviceState() } catch (_: Exception) {}
        if (draining == null) try { drainSources { } } catch (e: Exception) {
            fatalPending.compareAndSet(null,"DRAIN_SETUP_FAILED:${e.message}")
            // Once draining is set, its timeout is armed before lock/diagnostic work.
            if (draining == null) finishNow(fatalPending.get()!!,idleExit)
        }
    }
    private fun finishNow(reason: String, idleExit: Boolean = false) {
        if (stopping) return
        stopping = true; shortBudgetStartMs = 0L; writer.removeCallbacks(tick)
        if (reason.startsWith("SHORT_") || reason.contains("CONTACT_TIMEOUT") || reason.contains("PLACEMENT_IDLE_TIMEOUT_80S")) {
            val stage = when(shortSensor) {
                "BIA" -> "体成分"
                "SPO2" -> "血氧"
                "PPG_ON_DEMAND" -> "PPG 100Hz"
                "ECG" -> "ECG"
                else -> "前置/后置短测"
            }
            try {
                getSharedPreferences("capture-notice",MODE_PRIVATE).edit()
                    .putString("last","上次活动在${stage}阶段未检测到有效数据，已保存并退出。").commit()
            } catch (_: Exception) {}
        }
        biaResults.replaceAll { _,result ->
            if(result.contains("正在保存")) "已取得结果，保存是否完成请查看记录"
            else if(result.contains("测量中")) "已停止，未取得完整有效结果" else result
        }
        spo2Results.replaceAll { _, result ->
            if(result.contains("测量中") || result.contains("计算中") || result.contains("等待测量完成"))
                "已停止，未取得有效结果"
            else if(result.contains("正在保存")) "已获得数值，保存是否完成请查看记录" else result
        }
        ppgResults.replaceAll { _, result ->
            if(result.contains("测量中")) "已停止，原始记录保存是否完成请查看记录" else result
        }
        var result = reason + (if (incompleteTail.isEmpty()) "" else ";CORE_TAIL_UNCONFIRMED:$incompleteTail") +
            if(shortFailure) ";SHORT_MEASUREMENT_SKIPPED" else ""
        try { stopSources(running.toList()) } catch (e: Exception) { result += ";STOP_FAILURE:${e.message}" }
        try { adapter?.close() } catch (e: Exception) { result += ";CLEANUP_FAILURE:${e.message}" }
        var sealed = false
        try {
            // Sensors are stopped: empty the admitted disk backlog before submitting
            // the final in-memory buffers, including after a backlog-limit failure.
            persistence?.barrier()
            flush()
            persistence?.barrier()
            val diskEvidence = persistence?.evidence()
            if (diskEvidence?.get("journalFailure")?.toString()?.isNotEmpty() == true)
                result += ";DIAGNOSTIC_JOURNAL_FAILED"
            event("PERSISTENCE_SUMMARY", diskEvidence.toString())
            event("SESSION_END", "$result; counts=${persistedCounts()}; SDK undelivered tail unknown; no zero-loss claim")
            persistence?.barrier()
            checkNotNull(session) { "NO_CAPTURE_SESSION" }.seal(result, System.currentTimeMillis())
            sealed = true
        }
        catch (e: Exception) { result += ";NOT_SEALED:${e.message};已有块保留" }
        terminalReason = result
        persistence?.close()
        // The release hold keeps the capture wake lock alive until the vendor connector is
        // unbound; otherwise the CPU can sleep before the queued unset/disconnect runs.
        val pendingReleaseHoldMs = try { adapter?.releaseHoldMs() ?: 0L } catch (_: Exception) { 0L }
        if (pendingReleaseHoldMs <= 0L) {
            try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { result += ";LOCK_RELEASE_FAILURE:${e.message}" }
        }
        if (destroyed) writer.close()
        if (idleExit) {
            val text = if (reason.startsWith("CORE_NO_DATA_TIMEOUT"))
                "没有收到活动数据，已停止并退出；本次不会自动同步。" else
                "短测等待或选择超时，已停止；已有数据保留，下次打开可同步。"
            val next = State(false, "DONE", text)
            state = next
            main.post {
                try { observer?.invoke(next) } catch (_: Exception) {}
                val app = application as ProbeApplication
                try { app.journal?.event("CAPTURE_IDLE_STOP_COMPLETED",mapOf(
                    "reason" to result,"sealed" to sealed,"autoSyncStarted" to false,
                    "wakeLockHeld" to (lock?.isHeld == true),"deadlineMs" to deadline)) } catch (_: Exception) {}
                releaseVendorThenExit(text)
                try { idleExitObserver?.invoke() } catch (_: Exception) {}
            }
            return
        }
        val outcome = when {
            result.contains("NOT_SEALED") -> "保存未完整完成，已有块保留；请发送问题报告"
            result == "USER_INTERRUPTED" -> "你已中断，已有数据保留"
            result.startsWith("USER_COMPLETED") -> if (incompleteTail.isEmpty()) "记录已结束并封存；传感器失败/无效数据会单独注明" else "记录已保存，但部分活动传感器尾部未确认；详情见问题报告"
            else -> "记录因异常停止，请发送问题报告\n$result"
        }
        val restoreHint = if(preSpo2 || postSpo2) "\n请将手表恢复到平时佩戴位置。" else ""
        val counts = countText()
        val preparing = State(false, "DONE",
            "$outcome$restoreHint\n$counts\n" + if (sealed) "正在结束采集服务并释放传感器。"
            else "数据保留在手表；下次打开原波可重新同步。")
        state = preparing
        main.post {
            try { observer?.invoke(preparing) } catch (_: Exception) {}
            val app = application as ProbeApplication
            val ownerBefore = app.activeSyncOwner.orEmpty()
            val syncStarted = shouldStartFinishSync(sealed) && app.startWatchActivitySync(ActivitySyncPolicy.CAPTURE_FINISHED)
            try { app.journal?.event("ACTIVITY_CAPTURE_FINISH_SYNC_DECISION", mapOf(
                "sealed" to sealed,"started" to syncStarted,"taskRemoved" to taskRemoved,"activeOwnerBefore" to ownerBefore,
                "autoSyncAfterCapture" to autoSyncAfterCapture,
                "trigger" to ActivitySyncPolicy.CAPTURE_FINISHED)) } catch (_: Exception) {}
            // The vendor release hold is independent of the sync service. Always run it;
            // auto-sync must not skip the 60-second LED release before the process exits.
            releaseVendorThenExit("$outcome$restoreHint\n$counts")
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Explicit removal from Recents ends acquisition; screen-off/onStop does not.
        taskRemoved = true
        command("CANCEL")
        (application as? ProbeApplication)?.requestStopBackgroundSync("WATCH_TASK_REMOVED")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        destroyed = true
        writer.post {
            if (!stopping) guarded { finish("SERVICE_DESTROYED_INTERRUPTION") }
            else writer.close()
        }
        super.onDestroy()
    }
}
