package com.rawwave.shell

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.system.Os
import android.system.OsConstants
import com.rawwave.core.ActivityStore
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.EcgBatchQuality
import com.rawwave.core.EcgContinuityGate
import com.rawwave.core.EcgRetryPolicy
import com.rawwave.core.StreamQualityTracker
import com.rawwave.core.SensorCatalog
import com.rawwave.core.SensorPort
import com.rawwave.core.EcgWaitBudget
import com.rawwave.core.CapturePhasePolicy
import com.rawwave.core.CaptureRuns
import com.rawwave.core.CaptureDrain
import com.rawwave.core.CaptureQueue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Single-writer capture owner. Never automatically restarts sensors after process death. */
class ActivityRecordingService : Service() {
    companion object {
        data class State(val active: Boolean = false, val phase: String = "",
            val text: String = "", val remaining: Int = 0, val contact: Boolean = false,
            val skipAllowed: Boolean = false, val failedAttempts: Int = 0, val shortSensor: String = "ECG")
        @Volatile var state = State(); private set
        var observer: ((State) -> Unit)? = null // main thread; removed on Activity stop
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
    private var session: ActivityStore.Session? = null
    private var adapter: SensorPort? = null
    private var lock: PowerManager.WakeLock? = null
    private var phase = "PRE-1"
    private var ecg = true
    private var bodySource = "BIA"
    private var bia = false
    private var biaPre = true
    private var preBiaAttempt = 199
    private var postBiaAttempt = 199
    private var biaPhase = "PRE-200"
    private val biaResults = linkedMapOf<String,String>()
    private var biaSummary = ""
    private var spo2 = false
    private var spo2Pre = true
    private var shortSensor = "ECG"
    private var shortFailure = false
    private var preSpo2Attempt = 99
    private var postSpo2Attempt = 99
    private var spo2Phase = "PRE-100"
    private val spo2Results = linkedMapOf<String, String>()
    private var spo2Value: Int? = null
    private var coreSources = SensorCatalog.defaultCoreSources()
    private var deadline = 0L
    private var sessionStartedMs = 0L
    private var stopping = false
    private var draining: CaptureDrain? = null
    private var pendingFinish: Pair<String, Boolean>? = null
    private var incompleteTail = ""
    @Volatile private var destroyed = false
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
    private val buffers = mutableMapOf<String, MutableList<Map<String, Any?>>>()
    private val qualityTrackers = mutableMapOf<String, StreamQualityTracker>()
    private val tick = object : Runnable {
        override fun run() = guarded {
            if (stopping || draining != null) return@guarded
            // Review is bounded too: keep the deadline runnable executing while the screen sleeps.
            renewCaptureLock()
            flush()
            running.filter { it != "SPO2" && it != "BIA" && it != "MF_BIA" }.forEach { sensor ->
                if (clock() - (lastData[sensor] ?: 0) > 15_000 && silent.add(sensor)) {
                    event("DATA_SILENCE_OBSERVED", "$sensor: no callback for 15s; SDK buffering versus missing data unknown")
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
                if (phase.startsWith("ARM_BIA_")) { beginBia(); return@guarded }
                if (placementWaiting()) { finish("PLACEMENT_IDLE_TIMEOUT_80S", true); return@guarded }
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
        super.onCreate(); thread.start()
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
            state = State(true, "CONNECTING", "正在连接三星传感器…")
            try {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("capture", "活动采集", NotificationManager.IMPORTANCE_LOW))
                val pending = PendingIntent.getActivity(this, 0, Intent(this, RecordingActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startForeground(410, Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_rawwave)
                    .setContentTitle("原波正在记录活动").setContentText("返回原波结束活动；已采集数据持续保存")
                    .setContentIntent(pending).setOngoing(true).build(),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
                lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawWave:Capture").apply {
                    setReferenceCounted(false); acquire(60_000)
                }
                lockRenewed = clock()
                val id = intent.getStringExtra("template")
                writer.post { guarded {
                    val store = store(this)
                    val template = store.templates().single { it.id() == id }
                    beginSession(store.start(template, System.currentTimeMillis(), clock()),template,
                        CaptureAssembly.sensors(this, ::enqueue,
                            template.ecg() || template.spo2() || template.bia() || template.coreSources().any { it != "GYROSCOPE" && it != "PRESSURE" && it != "MAGNETIC_FIELD" }, template.biaProfile()))
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
        session = value; ecg = template.ecg(); coreSources = template.coreSources().toList()
        spo2 = template.spo2(); bia = template.bia(); bodySource=if(template.mfBia()) "MF_BIA" else "BIA"
        sessionStartedMs = clock()
        event("CAPTURE_START", "build=${BuildConfig.BUILD_RUN}; template=${template.id()}")
        phase = "CONNECTING"; deadline = clock() + 20_000
        adapter = SensorPort.requireCompatible(sensors)
        event("SENSOR_CONTRACT_BOUND", "contract=${adapter!!.contractVersion()}; implementation=${adapter!!.implementationVersion()}")
        adapter!!.connect({
            if (draining != null || stopping || destroyed || fatalPending.get() != null) return@connect
            if (phase != "CONNECTING") return@connect
            if (clock() >= deadline) { finish("SDK_CONNECT_TIMEOUT"); return@connect }
            writer.removeCallbacks(tick)
            if (spo2) prepareSpo2(true) else if (bia) prepareBia(true) else if (ecg) beginEcg("PRE-1") else beginCore()
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
                if (placementWaiting() && clock() >= deadline) {
                    finish("PLACEMENT_IDLE_TIMEOUT_80S", true); return@guarded
                }
                if ((shortSensor == "SPO2" || shortSensor == "BIA") && phase.startsWith("REVIEW_") && clock() >= deadline) {
                    finish("${shortSensor}_REVIEW_IDLE_TIMEOUT_80S", true); return@guarded
                }
                when (action) {
                    "END" -> if (phase == "CORE-1") {
                        event("USER_ENDED_CORE", "Post measurements follow the template, not a new choice")
                        drainSources(allowPartialPost = ecg || spo2 || bia) {
                        when(CapturePhasePolicy.coreEnded(ecg)) {
                            CapturePhasePolicy.Next.POST_ECG -> beginEcg("POST-1")
                            else -> beginPostShorts()
                        }
                        }
                    }
                    "CANCEL" -> finish("USER_INTERRUPTED")
                      "START_BIA" -> if(phase.startsWith("READY_BIA_")) armBia()
                      "RETRY_BIA" -> if(phase.startsWith("REVIEW_BIA_")) prepareBia(biaPre)
                      "SKIP_BIA" -> if(phase.startsWith("REVIEW_BIA_") || phase.startsWith("READY_BIA_")) {
                          shortFailure=true
                          if(phase.startsWith("REVIEW_")) biaResults[biaPhase]="未取得有效结果，已跳过"
                          event("USER_SKIPPED_BIA","pre=$biaPre"); afterBia()
                      }
                      "START_SPO2" -> if (phase.startsWith("READY_SPO2_")) beginSpo2(spo2Pre)
                      "PLACEMENT_READY" -> if (phase == "RESTORE_PRE") {
                          event("NORMAL_PLACEMENT_CONFIRMED", "next=${if(ecg) "ECG" else "CORE"}")
                          if(ecg) beginEcg("PRE-1") else beginCore()
                      }
                      "RETRY_SPO2" -> if (phase.startsWith("REVIEW_SPO2")) prepareSpo2(spo2Pre)
                      "SKIP_SPO2" -> if (phase.startsWith("REVIEW_SPO2")) {
                          shortFailure = true; spo2Results[spo2Phase] = "未取得有效结果，已跳过"
                          event("USER_SKIPPED_SPO2", "pre=$spo2Pre")
                          afterSpo2()
                      }
                    "RETRY_ECG" -> if (shortSensor == "ECG" && phase.startsWith("REVIEW_")) {
                        event("USER_RETRY_ECG", ecgPhase)
                        beginEcg(if (phase == "REVIEW_PRE") "PRE-${++preAttempt}" else "POST-${++postAttempt}")
                    }
                    "SKIP_ECG" -> if (shortSensor == "ECG" && (phase.startsWith("REVIEW_") || phase.startsWith("PRE") || phase.startsWith("POST"))) skipEcg()
                }
            } }
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
        notice = ""; contact = false; ecgTotalRows = 0
        lastContactMs = 0; ecgGate = EcgContinuityGate(); ecgUpdate = null
        // Wait for a valid batch. A later invalid batch resets the strict continuous segment.
        deadline = EcgWaitBudget.initialDeadline(next == "PRE-1" && !spo2 && !bia,sessionStartedMs,clock())
        event("PHASE_START", next); startSource("ECG"); restartTick()
    }
    private fun beginCore() {
        shortSensor = ""
        phase = "CORE-1"; notice = ""; deadline = 0
        event("PHASE_START", phase)
        startingSources = true
        coreSources.forEach(::startSource)
        startingSources = false
        if (running.isEmpty()) finish("ALL_CORE_SOURCES_FAILED") else restartTick()
    }
    private fun placementWaiting() = phase.startsWith("READY_BIA_") || phase.startsWith("READY_SPO2_") || phase == "RESTORE_PRE"
    private fun beginPostShorts() {
        if(bia) prepareBia(false) else if(spo2) prepareSpo2(false) else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun prepareBia(pre: Boolean) {
        shortSensor="BIA"; biaPre=pre
        phase=if(pre) "READY_BIA_PRE" else "READY_BIA_POST"
        notice="手表向肘部上移并贴紧。中指轻触上键、无名指轻触下键；双手不要相碰，抬起手臂使腋下张开。保持触键直到测量结束。\n确认活动管理中的身高、体重、年龄和性别正确；调整时间不计入测量。80秒不操作则停止。"
        deadline=clock()+80_000
        event("BIA_PLACEMENT_PREPARATION","pre=$pre; sensorsNotStarted=true")
        holdDecisionWindow();restartTick()
    }
    private fun armBia() {
        phase=if(biaPre) "ARM_BIA_PRE" else "ARM_BIA_POST"
        deadline=clock()+5_000
        notice="5秒后自动开始。现在将双指轻放两键，不要按下；双手和手指不要碰表框或另一只手。"
        event("BIA_CONTACT_COUNTDOWN", "sensorsNotStarted=true; preparationMs=5000")
        holdDecisionWindow();restartTick()
    }
    private fun beginBia() {
        shortSensor="BIA"
        phase=if(biaPre) "PRE-${++preBiaAttempt}" else "POST-${++postBiaAttempt}"
        biaPhase=phase;biaSummary="";biaResults[phase]="测量中，尚无完整有效结果"
        deadline=clock()+60_000
        notice="保持双指轻触两键，双手不要相碰。有效结果收到后立即保存；最多等待60秒。"
        event("BIA_MEASUREMENT_STARTED","source=$bodySource; pre=$biaPre; deadlineMs=$deadline; profileInPlan=true")
        startSource(bodySource);restartTick()
    }
    private fun endBia(success:Boolean, reason:String) {
        if(draining!=null || stopping)return
        drainSources {
            biaResults[biaPhase]=if(success) "成功：$biaSummary" else "未成功，未取得完整有效结果"
            event("BIA_MEASUREMENT_SAVED","success=$success; reason=$reason; pre=$biaPre")
            if(success) afterBia() else {
                phase=if(biaPre) "REVIEW_BIA_PRE" else "REVIEW_BIA_POST"
                val guide=when(reason) {
                    "SDK_STATUS_4", "SDK_STATUS_10" -> "检查手表背面贴紧皮肤"
                    "SDK_STATUS_7", "SDK_STATUS_8", "SDK_STATUS_9" -> "中指和无名指分别轻触两键"
                    "SDK_STATUS_11" -> "手指过干，请改善接触后重试"
                    "SDK_STATUS_14", "SDK_STATUS_15" -> "双手不要相碰，手指不要接触表框"
                    "SDK_STATUS_18" -> "请在活动管理中检查个人资料"
                    else -> "请检查姿势和接触，也可跳过继续活动"
                }
                notice="体成分未完成：$guide（$reason）。已有记录已保存；80秒不操作则停止。"
                deadline=clock()+80_000;holdDecisionWindow();restartTick()
            }
        }
    }
    private fun afterBia() {
        if(biaPre) restorePlacement()
        else if(spo2) prepareSpo2(false)
        else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun prepareSpo2(pre: Boolean) {
        shortSensor = "SPO2"; spo2Pre = pre
        phase = if(pre) "READY_SPO2_PRE" else "READY_SPO2_POST"
        notice = "请将手表向肘部上移并贴合皮肤，手肘放在桌上，手腕靠近心脏，保持静止。\n调整好后点开始；准备时间不计入测量。80秒不操作则停止。"
        deadline = clock() + 80_000
        event("SPO2_PLACEMENT_PREPARATION", "pre=$pre; sensorsNotStarted=true")
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
            if(success) afterSpo2() else {
                phase = if(spo2Pre) "REVIEW_SPO2_PRE" else "REVIEW_SPO2_POST"
                val explanation = when(reason) {
                    "SDK_STATUS_-4" -> "检测到移动，请重新调整并保持静止"
                    "SDK_STATUS_-5" -> "信号质量不足，请检查佩戴位置和贴合"
                    "SDK_STATUS_-6", "MEASUREMENT_TIMEOUT_30S" -> "30秒内未取得有效结果"
                    else -> "测量未完成"
                }
                notice = "血氧未成功：$explanation（$reason）。已有数据已保存，可重测或跳过；80秒不操作则退出。"
                deadline=clock()+80_000; holdDecisionWindow(); restartTick()
            }
        }
    }
    private fun afterSpo2() {
        if(spo2Pre) { if(bia) prepareBia(true) else restorePlacement() }
        else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
    }
    private fun restorePlacement() {
            phase = "RESTORE_PRE"; shortSensor = ""
            notice = "请将手表调回平时运动位置，保持贴合。\n确认后${if(ecg) "测量 ECG，再开始活动" else "开始活动采集"}；调整期间不采集。80秒不操作则停止。"
            deadline = clock() + 80_000
            event("NORMAL_PLACEMENT_REQUESTED", "sensorsNotStarted=true")
            holdDecisionWindow(); restartTick()
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
            if (phase != origin || !captureRuns.current(run)) {
                // Preserve late delivered samples under their originating phase, never relabel them.
                session?.append(origin, sensor, rows)
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
                    val result = rows.firstOrNull { if(bodySource=="MF_BIA") com.rawwave.core.BiaResult.multiFrequencyComplete(it) else com.rawwave.core.BiaResult.complete(it) }
                    val failure = rows.firstOrNull { ((it["STATUS"] as? Number)?.toInt() ?: 0).let { status -> status > 0 && !(bodySource=="MF_BIA" && status==23) } }
                    if(result != null) {
                        biaSummary=if(bodySource=="MF_BIA") "四频阻抗及相位已取得" else "体脂率 ${result["BODY_FAT_RATIO"]}%，骨骼肌 ${result["SKELETAL_MUSCLE_MASS"]} kg"
                        biaResults[biaPhase]="已取得结果，正在保存"
                        endBia(true,"SDK_STATUS_0_VALID_RESULT")
                    } else if(failure != null) endBia(false,"SDK_STATUS_${failure["STATUS"]}")
                    else {
                        val progress=(rows.lastOrNull()?.get("PROGRESS") as? Number)?.toDouble()
                        val percent=progress?.let { if(bodySource=="BIA") it*100 else it }
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
                            deadline = clock() + 45_000
                            notice = "接触有效，正在连续采集；中途断开会从头开始。"
                            event("ECG_CONTINUOUS_SEGMENT_STARTED", "segment=${update.segment()}; firstSdkTimestampMs=${update.firstTimestampMs()}")
                        }
                        EcgContinuityGate.Status.RESET -> {
                            val failures = retryPolicy(phase.startsWith("PRE")).recordFailure()
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
                } else {
                    notice = "$sensor 失败：$error；其他数据保留"
                    if (phase.startsWith("CORE") && running.isEmpty() && !startingSources) finish("ALL_CORE_SOURCES_FAILED")
                }
            } else event("STALE_SENSOR_FAILURE", "$origin/$sensor:$error")
        })
    }
    private fun endEcg() {
        if (draining != null || stopping) return
        drainSources { completeEcg() }
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
            if (!wasPre) beginPostShorts()
            else if (next == CapturePhasePolicy.Next.CORE) beginCore() else finish("USER_COMPLETED_SEE_SENSOR_EVENTS")
        }
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
            drainSources { completeSkip(wasPre, policy.failures()) }
        } else completeSkip(wasPre, policy.failures())
    }
    private fun completeSkip(wasPre: Boolean, failures: Int) {
        if (phase.startsWith("PRE") || phase.startsWith("POST")) {
            deadline = 0
            event("ECG_ATTEMPT_END", "userSkipped=true; rawRows=$ecgTotalRows; failures=$failures; invalid fragments retained")
        }
        event("USER_SKIPPED_FAILED_ECG", "$ecgPhase; failures=$failures")
        shortFailure = true
        if (wasPre) beginCore() else beginPostShorts()
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
    private fun stopSources() {
        val origin = phase
        var failure: Exception? = null
        running.toList().forEach { sensor ->
            captureRuns.endSensor(sensor)
            try { adapter?.stop(sensor) } catch (e: Exception) { failure = e }
            // A diagnostic write failure must not skip stopping the remaining sensors.
            try { event("SENSOR_STOP_REQUEST", sensor); emitQuality(origin, sensor) }
            catch (e: Exception) { failure = e }
        }
        running.clear()
        failure?.let { throw it }
    }
    /** Keep the originating phase and data delivery alive until every source hands off. */
    private fun drainSources(allowPartialPost: Boolean = false, next: () -> Unit) {
        if (draining != null || stopping) return
        val sources = running.toList()
        val drain = CaptureDrain(sources)
        draining = drain
        writer.removeCallbacks(tick)
        val expires = clock() + 5_000
        val timeout = Runnable {
            if (draining === drain) guarded {
                drain.timeout()
                // Emergency cleanup is not evidence of a complete tail.
                try { stopSources() }
                finally { writer.post { guarded { completeDrain(drain, next, allowPartialPost) } } }
            }
        }
        // Separate bounded cleanup budget, not a restarted 80-second user wait.
        writer.postDelayed(timeout, 5_000)
        renewCaptureLock()
        event("DRAIN_REQUESTED", "phase=$phase; sources=$sources; cleanupBudgetMs=5000")
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
                        completeDrain(drain, next, allowPartialPost)
                    }
                }
            } catch (e: Exception) {
                drain.complete(sensor, SensorPort.DrainStatus.FAILED, "DRAIN_THROW:${e.message}")
            }
        }
        // Also covers no active sources and inline completion/throw from a replacement.
        writer.post { guarded { completeDrain(drain, next, allowPartialPost) } }
    }
    private fun completeDrain(drain: CaptureDrain, next: () -> Unit, allowPartialPost: Boolean) {
        if (draining !== drain) return
        drain.persist({
            // Data callbacks preceding each completion have already run on this writer.
            stopSources()
            flush()
            event("DRAIN_ROWS_PERSISTED", "phase=$phase; counts=${session?.counts()}; sourceFailures=${drain.failures()}; SDK atomic stop not guaranteed")
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
                  event("CORE_TAIL_UNCONFIRMED_POST_CONTINUES", "$failure; accepted rows persisted; sources stopped; post ECG follows")
                  next()
              }
            failure != null -> finishNow(failure, terminal?.second ?: false)
            terminal != null -> finishNow(terminal.first, terminal.second)
            else -> next()
        }
        })
    }
    private fun emitQuality(origin: String, sensor: String) {
        qualityTrackers.remove("$origin/$sensor")?.let {
            event("STREAM_QUALITY", "$origin/$sensor:${it.summary()}; computed metadata only; raw rows unchanged")
        }
    }
    private fun flush() {
        buffers.forEach { (sensor, rows) -> if (rows.isNotEmpty()) { session!!.append(streamPhase(), sensor, rows); rows.clear() } }
    }
    private fun streamPhase() = when (phase) {
        "ARM_BIA_PRE", "CONNECTING", "READY_BIA_PRE", "READY_SPO2_PRE", "RESTORE_PRE" -> "PRE-1"
        "ARM_BIA_POST", "READY_BIA_POST", "READY_SPO2_POST" -> "POST-1"
        "REVIEW_PRE", "REVIEW_POST" -> ecgPhase
        "REVIEW_BIA_PRE", "REVIEW_BIA_POST" -> biaPhase
        "REVIEW_SPO2_PRE", "REVIEW_SPO2_POST" -> spo2Phase
        else -> phase
    }
    private fun event(kind: String, detail: String) {
        session?.append(streamPhase(), "EVENT", listOf(mapOf("kind" to kind, "detail" to detail,
            "utcMs" to System.currentTimeMillis(), "elapsedMs" to clock())))
        (application as? ProbeApplication)?.journal?.event("CAPTURE_$kind", mapOf("session" to (session?.id ?: "none"), "detail" to detail))
    }
    private fun publish() {
        val count = countText()
        val title = when {
            phase == "RESTORE_PRE" -> "恢复运动佩戴位置"
            shortSensor == "BIA" -> if(biaPre) "活动前 $bodySource" else "活动后 $bodySource"
            shortSensor == "SPO2" -> if(spo2Pre) "活动前血氧 SpO₂" else "活动后血氧 SpO₂"
            phase == "CONNECTING" -> "正在连接三星传感器"
            phase.startsWith("PRE") -> "活动前 ECG"
            phase.startsWith("POST") -> "活动后 ECG"
            phase == "CORE-1" -> "活动记录中\n${coreSources.joinToString("、", transform = ::sensorLabel)}同时采集" +
                (if (ecg) "\n结束后还会测 ECG" else "") + (if(spo2) "\n结束后还会测血氧" else "") + (if(bia) "\n结束后还会测体成分" else "")
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
        val next = State(true, if (draining != null) "DRAINING" else phase,
            if (draining != null) "正在收尾并保存各传感器数据，请稍候（最多5秒收尾等待）\n$count"
            else "$title\n$notice$idleHint" + (if(shortSensor == "BIA" || shortSensor == "SPO2" || placementWaiting()) "\n${if(placementWaiting() || phase.startsWith("REVIEW_")) "等待操作" else if(phase.startsWith("ARM_BIA_")) "距离开始" else "最多等待"} $remaining 秒" else "") +
                (if (incompleteTail.isEmpty()) "" else "\n部分活动传感器尾部未确认，已收数据已保存；继续活动后短测。") + "\n采集记录：\n$count", remaining,
            contact && clock() - lastContactMs < 2000,
            policy.skipAllowed(), policy.failures(), shortSensor)
        state = next; main.post { observer?.invoke(next) }
    }
    private fun countText(): String {
        val raw = session?.counts()?.filterKeys { !it.endsWith("/EVENT") && !it.endsWith("/SPO2") && !it.endsWith("/BIA") && !it.endsWith("/MF_BIA") }
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
            "${if(attempt.startsWith("PRE")) "活动前" else "活动后"} 体成分第${attempt.substringAfter('-').toInt()-199}次：$result"
        }
        return (body + oxygen + raw).joinToString("\n").ifEmpty { "暂无采集记录" }
    }
    private fun sensorLabel(sensor: String) = when (sensor) {
        "ECG" -> "ECG"
        "SPO2" -> "血氧"
        "BIA" -> "体成分"
        "MAGNETIC_FIELD" -> "三轴磁场"
        "PRESSURE" -> "气压"
        "GYROSCOPE" -> "陀螺仪"
        "HEART_RATE" -> "心率"
        "ACCELEROMETER" -> "加速度"
        "PPG" -> "PPG"
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
    private fun finish(reason: String, idleExit: Boolean = false) {
        if (stopping) return
        if (pendingFinish == null) pendingFinish = reason to idleExit
        if (draining == null) try { drainSources { } } catch (e: Exception) {
            fatalPending.compareAndSet(null,"DRAIN_SETUP_FAILED:${e.message}")
            // Once draining is set, its timeout is armed before lock/diagnostic work.
            if (draining == null) finishNow(fatalPending.get()!!,idleExit)
        }
    }
    private fun finishNow(reason: String, idleExit: Boolean = false) {
        if (stopping) return
        stopping = true; writer.removeCallbacks(tick)
        biaResults.replaceAll { _,result ->
            if(result.contains("正在保存")) "已取得结果，保存是否完成请查看记录"
            else if(result.contains("测量中")) "已停止，未取得完整有效结果" else result
        }
        spo2Results.replaceAll { _, result ->
            if(result.contains("测量中") || result.contains("计算中") || result.contains("等待测量完成"))
                "已停止，未取得有效结果"
            else if(result.contains("正在保存")) "已获得数值，保存是否完成请查看记录" else result
        }
        var result = reason + (if (incompleteTail.isEmpty()) "" else ";CORE_TAIL_UNCONFIRMED:$incompleteTail") +
            if(shortFailure) ";SHORT_MEASUREMENT_SKIPPED" else ""
        try { stopSources() } catch (e: Exception) { result += ";STOP_FAILURE:${e.message}" }
        try { adapter?.close() } catch (e: Exception) { result += ";CLEANUP_FAILURE:${e.message}" }
        var sealed = false
        try {
            flush()
            event("SESSION_END", "$result; counts=${session?.counts()}; SDK undelivered tail unknown; no zero-loss claim")
            checkNotNull(session) { "NO_CAPTURE_SESSION" }.seal(result, System.currentTimeMillis())
            sealed = true
        }
        catch (e: Exception) { result += ";NOT_SEALED:${e.message};已有块保留" }
        try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { result += ";LOCK_RELEASE_FAILURE:${e.message}" }
        if (destroyed) writer.close()
        if (idleExit) {
            val next = State(false, "DONE", "短测等待或选择超时，已停止；已有数据保留，下次打开可同步。")
            state = next
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                val app = application as ProbeApplication
                try { app.journal?.event("CAPTURE_IDLE_STOP_COMPLETED",mapOf(
                    "reason" to result,"sealed" to sealed,"autoSyncStarted" to false,
                    "wakeLockHeld" to (lock?.isHeld == true),"deadlineMs" to deadline)) } catch (_: Exception) {}
                idleExitObserver?.invoke()
            }
            return
        }
        val outcome = when {
            result.contains("NOT_SEALED") -> "保存未完整完成，已有块保留；请发送问题报告"
            result == "USER_INTERRUPTED" -> "你已中断，已有数据保留"
            result.startsWith("USER_COMPLETED") -> if (incompleteTail.isEmpty()) "记录已结束并封存；传感器失败/无效数据会单独注明" else "记录已保存，但部分活动传感器尾部未确认；详情见问题报告"
            else -> "记录因异常停止，请发送问题报告\n$result"
        }
        val restoreHint = if(spo2) "\n请将手表恢复到平时佩戴位置。" else ""
        val counts = countText()
        val preparing = State(false, if (sealed) "SYNC_PENDING" else "DONE",
            "$outcome$restoreHint\n$counts\n" + if (sealed) "正在结束采集服务并准备自动同步。"
            else "数据保留在手表；下次打开原波可重新同步。")
        state = preparing
        main.post {
            observer?.invoke(preparing)
            // End the sensor foreground-service lifecycle before opening the separate bounded sync owner.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            val app = application as ProbeApplication
            val ownerBefore = app.activeSyncOwner.orEmpty()
            val syncStarted = sealed && !destroyed && app.startWatchActivitySync(ActivitySyncPolicy.CAPTURE_FINISHED)
            try { app.journal?.event("ACTIVITY_CAPTURE_FINISH_SYNC_DECISION", mapOf(
                "sealed" to sealed,"started" to syncStarted,"activeOwnerBefore" to ownerBefore,
                "trigger" to ActivitySyncPolicy.CAPTURE_FINISHED)) } catch (_: Exception) {}
            val syncText = if (syncStarted) "正在同步活动原始数据到手机；成功后立即停止，失败也不会自动重试。"
                else "自动同步没有启动；数据仍保留，下次打开原波可补同步。"
            val next = State(false, if (syncStarted) "SYNC" else "DONE", "$outcome$restoreHint\n$counts\n$syncText")
            state = next
            observer?.invoke(next)
        }
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
