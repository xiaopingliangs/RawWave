package com.rawwave.shell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.rawwave.core.ActivityArchive
import com.rawwave.core.ActivityStore
import com.rawwave.core.ActivitySyncPolicy
import com.rawwave.core.AutoSyncGate
import com.rawwave.core.SensorCatalog
import java.io.File

/** Watch-only capture screen; the diagnostic screen cannot own the capture lifetime. */
class RecordingActivity : ComponentActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var measurementHeader: TextView
    private lateinit var topPane: ScrollView
    
    private lateinit var controls: LinearLayout
    private lateinit var guide: HomeKeyGuide
    private var pending: ActivityStore.Template? = null
    private var stopDialog: android.app.AlertDialog? = null
    private var idle: DeadlineRuntime? = null
    private var mode = ""
    private var resumed = false
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
        if (inActivityManagement() && !ActivityRecordingService.state.active) {
            if (!sync.running) releaseIdleAfterSync()
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
        } else if (sync.running) {
            renderSync(sync)
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
        val inset = (resources.displayMetrics.widthPixels * .16f).toInt()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(40), inset, dp(48)); setBackgroundColor(Color.BLACK)
        }
        measurementHeader = label("")
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controls)
        guide = HomeKeyGuide(this)
        body.addView(guide, LinearLayout.LayoutParams(-1, dp(65)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(4), inset, dp(32))
        }
        status = label("原波 · 活动采集测试", details)
        topPane = ScrollView(this).apply { addView(body) }
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
                if (mode == "EDIT_TEMPLATE") { showActivityManagement(); return }
                if (mode == "MANAGE_ACTIVITIES") { returnHome(); return }
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
        if (consumeSyncExit()) { finishAndRemoveTask(); return }
        if (app.activitySyncStatus.running && !ActivitySyncPolicy.isNonBlockingHomeSync(app.activitySyncStatus.trigger))
            renderSync(app.activitySyncStatus)
        else render(ActivityRecordingService.state)
        scheduleAutoSync("ON_START")
    }
    override fun onStop() {
        resumed = false
        if (ActivityRecordingService.observer === listener) ActivityRecordingService.observer = null
        cancelAutoSync("ON_STOP")
        guide.animateContact = false
        idle?.parkIdle()
        super.onStop()
    }
    override fun onDestroy() {
        if (ActivityRecordingService.idleExitObserver === idleExitListener) ActivityRecordingService.idleExitObserver = null
        if (app.activitySyncObserver === syncListener) app.activitySyncObserver = null
        stopDialog?.dismiss(); stopDialog = null
        cancelAutoSync("ON_DESTROY"); idle?.close(); super.onDestroy()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            idle?.touchIdle()
        }
        return super.dispatchTouchEvent(event)
    }
    private fun render(s: ActivityRecordingService.Companion.State) {
        if (inActivityManagement() && !s.active) return
        if (app.activitySyncStatus.running && !ActivitySyncPolicy.isNonBlockingHomeSync(app.activitySyncStatus.trigger)) {
            renderSync(app.activitySyncStatus); return
        }
        if (s.active) { cancelAutoSync("RECORDING_ACTIVE"); idle?.close(); idle = null }
        else if (app.activitySyncStatus.running) holdIdleForSync()
        else ensureIdle()
        val measuringEcg = s.shortSensor == "ECG" && (s.phase.startsWith("PRE") || s.phase.startsWith("POST"))
        guide.visibility = View.GONE
        guide.animateContact = measuringEcg && resumed
        if (measuringEcg || (s.active && (s.shortSensor == "SPO2" || s.shortSensor == "BIA" || s.shortSensor == "PPG_ON_DEMAND"))) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        measurementHeader.textSize = 19f
        measurementHeader.text = if (s.active) {
            val timer = when {
                s.phase == "DRAINING" -> "正在保存…"
                s.phase.startsWith("REVIEW") -> "本次测量已结束"
                measuringEcg && s.remaining < 0 -> "等待接触"
                s.remaining > 0 -> "${s.remaining} 秒"
                else -> "正在记录"
            }
            val instruction = when {
                s.phase == "DRAINING" -> "正在结束采集，请稍候"
                s.phase.startsWith("REVIEW") -> if (s.shortSensor == "PPG_ON_DEMAND")
                    "可以调整手臂，选择重测或继续" else "可以松开手指，选择重测或继续"
                s.phase == "RESTORE_PRE" -> "恢复平时佩戴位置后继续"
                s.shortSensor == "SPO2" -> "手臂放稳，与心脏大致同高\n测量期间保持静止"
                s.shortSensor == "BIA" -> "两指分别接触两个按键\n手臂抬起，避免双手相碰"
                s.shortSensor == "PPG_ON_DEMAND" -> "正常佩戴，保持贴合\n手臂静止，采集30秒"
                measuringEcg -> "手指轻触 Home 键，不要按下\n保持接触，手臂静止"
                s.phase == "CORE-1" -> "正常活动即可\n结束时点击下方按钮"
                else -> "请稍候"
            }
            "$timer\n$instruction"
        } else "原波"
        status.text = if (s.active) "采集详情\n${s.text}" else s.text
        if (mode == "${s.active}/${s.phase}/${s.skipAllowed}") return
        mode = "${s.active}/${s.phase}/${s.skipAllowed}"
        topPane.post { topPane.scrollTo(0, 0) }
        controls.removeAllViews()
        if (s.active) {
            if (s.phase.startsWith("READY_BIA_")) {
                button("已调整好，开始体成分") { command("START_BIA") }
                button("跳过体成分，继续") { command("SKIP_BIA") }
            } else if (s.phase.startsWith("REVIEW_BIA_")) {
                button("重测体成分") { command("RETRY_BIA") }
                button("跳过体成分，继续") { command("SKIP_BIA") }
            } else if (s.phase.startsWith("READY_SPO2_")) {
                button("已调整好，开始血氧") { command("START_SPO2") }
            } else if (s.phase.startsWith("READY_PPG_")) {
                button("已调整好，开始 PPG 30秒") { command("START_PPG") }
                button("跳过 PPG，继续") { command("SKIP_PPG") }
            } else if (s.phase == "RESTORE_PRE") {
                button("已恢复佩戴，继续") { command("PLACEMENT_READY") }
            } else if (s.phase.startsWith("REVIEW_SPO2")) {
                button("重测血氧") { command("RETRY_SPO2") }
                button("跳过血氧，继续") { command("SKIP_SPO2") }
            } else if (s.phase.startsWith("REVIEW_PPG_")) {
                button("重测 PPG") { command("RETRY_PPG") }
                button("跳过 PPG，继续") { command("SKIP_PPG") }
            } else if (s.phase.startsWith("REVIEW_")) {
                button("继续重测 ECG（不限次数）") { command("RETRY_ECG") }
                if (s.skipAllowed) button("跳过 ECG，继续本次活动") { command("SKIP_ECG") }
            }
            if (measuringEcg && s.skipAllowed)
                button("跳过 ECG，继续本次活动") { command("SKIP_ECG") }
            if (s.phase == "CORE-1") button("结束活动") { command("END") }
            button("中断并保留已有数据") {
                android.app.AlertDialog.Builder(this).setMessage("中断本次记录？已保存的数据保留，并注明用户中断。")
                    .setPositiveButton("中断") { _, _ -> command("CANCEL") }.setNegativeButton("继续记录", null).show()
            }
            button("诊断详情（离线可拍照）") { DiagnosticViewer.show(this, app) }
        } else showHome()
    }
    private fun showHome() {
        try {
            val store = ActivityRecordingService.store(this)
            val pendingSync = ActivityArchive.pendingCount(File(filesDir, "activities"))
            if (pendingSync > 0) {
                label("有 $pendingSync 次活动等待同步。后台补同步不会阻止你直接开始活动。", controls)
                button("立即同步活动数据到手机") { startPendingSync(ActivitySyncPolicy.USER_MANUAL) }
            }
            if (store.history().any { it.contains("未封存/") }) label("发现之前中断的活动。已写入的数据保留，未写入尾部情况未知；不会自动恢复采集。", controls)
            if (filesDir.usableSpace < 1_000_000_000L) label("存储不足 1GB，请清理不需要的软件。原始数据尚未同步，请勿卸载原波。", controls)
            store.templates().forEach { t ->
                button(t.name()) { prepare(t) }
            }
            button("活动管理") { showActivityManagement() }
            button("查看本地记录") {
                val history = store.history().joinToString("\n\n").ifEmpty { "还没有活动记录" }
                android.app.AlertDialog.Builder(this).setMessage(history).setPositiveButton("知道了", null).show()
            }
        } catch (e: Exception) { status.text = "读取活动失败：${e.message}。请保留数据并导出问题报告。" }
        scheduleAutoSync("HOME_RENDERED")
        // Reporting remains reachable even when template/history storage is damaged.
        button("诊断详情（离线可拍照）") { DiagnosticViewer.show(this, app) }
        button("问题报告（仅用于排查）") {
            idle?.close(); idle = null
            startActivity(Intent(this, ProbeActivity::class.java)); finish()
        }
        button("退出") { app.requestStopBackgroundSync("WATCH_EXPLICIT_EXIT_BUTTON"); finishAndRemoveTask() }
    }
    private fun renderSync(sync: ProbeApplication.ActivitySyncStatus) {
        if (inActivityManagement()) return
        cancelAutoSync("SYNC_RUNNING")
        if (sync.running) holdIdleForSync() else ensureIdle()
        guide.visibility = View.GONE; guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "活动数据同步\n${sync.text}\n采集文件在手机完整校验前不会从手表标记为已同步。"
        controls.removeAllViews()
        button("诊断详情（离线可拍照）") { DiagnosticViewer.show(this, app) }
        if (sync.running) button("停止同步（数据仍保留）") {
            app.requestStopBackgroundSync("WATCH_SYNC_STOP_BUTTON")
        }
    }
    private fun startPendingSync(trigger: String) {
        cancelAutoSync("START_$trigger")
        syncCycleSeen = true
        if (ActivityRecordingService.state.active) return
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
    private fun scheduleAutoSync(origin: String) {
        if (inActivityManagement()) return
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
    private fun inActivityManagement() = mode == "EDIT_TEMPLATE" || mode == "MANAGE_ACTIVITIES"
    private fun returnHome() { mode = ""; render(ActivityRecordingService.state) }
    private fun showActivityManagement() {
        if (ActivityRecordingService.state.active) return
        cancelAutoSync("MANAGE_ACTIVITIES")
        ensureIdle()
        mode = "MANAGE_ACTIVITIES"
        guide.visibility = View.GONE; guide.animateContact = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "活动管理"
        controls.removeAllViews()
        button("创建活动") { createForm() }
        try {
            val templates = ActivityRecordingService.store(this).templates()
            if (templates.isEmpty()) label("还没有活动，点击上方创建。", controls)
            templates.forEach { t ->
                val core = t.coreSources().joinToString("、", transform = ::sensorLabel)
                val short = listOfNotNull(if(t.ecg()) "ECG" else null, if(t.spo2()) "血氧" else null, if(t.ppg()) "PPG 100Hz" else null, if(t.bia()) (if(t.mfBia()) "多频阻抗" else "体成分") else null)
                    .joinToString("、").ifEmpty { "无" }
                label("${t.name()}\n活动前后：$short\n活动中：$core", controls)
                button("更改：${t.name()}") { createForm(t) }
                button("删除：${t.name()}") { deleteTemplate(t) }
            }
        } catch(e: Exception) { status.text = "读取活动失败：${e.message}。请保留数据并导出问题报告。" }
        button("返回主页") { returnHome() }
    }
    private fun deleteTemplate(template: ActivityStore.Template) {
        android.app.AlertDialog.Builder(this).setMessage("删除活动模板“${template.name()}”？已采集的历史数据保留。")
            .setPositiveButton("删除") { _, _ ->
                try {
                    ActivityRecordingService.store(this).deleteTemplate(template.id())
                    showActivityManagement()
                } catch(e: Exception) { status.text = "删除失败：${e.message}" }
            }.setNegativeButton("取消", null).show()
    }
    private fun createForm(existing: ActivityStore.Template? = null) {
        cancelAutoSync("EDIT_TEMPLATE")
        mode = "EDIT_TEMPLATE"
        status.text = if (existing == null) "创建活动：选择需要采集的项目" else "编辑：${existing.name()}\n修改仅用于以后的活动，历史记录保留。"
        controls.removeAllViews()
        val name = EditText(this).apply { hint = "活动名称"; setTextColor(Color.WHITE); setSingleLine() }
        val ecg = CheckBox(this).apply { text = "活动前后各测 ECG 30 秒"; isChecked = true; setTextColor(Color.WHITE) }
        val spo2 = CheckBox(this).apply { text = "活动前后各测血氧 SpO₂（三星处理结果，最多30秒）"; setTextColor(Color.WHITE) }
        val ppgContinuous = CheckBox(this).apply { text = "活动中连续 PPG 25Hz（长时间，原始绿/红外/红）"; setTextColor(Color.WHITE) }
        val ppgShort = CheckBox(this).apply { text = "活动前后各测 PPG 100Hz 30秒（原始绿/红外/红）"; setTextColor(Color.WHITE) }
        val ppgTitle = TextView(this).apply { text = "PPG 采集模式（两种可独立选择）"; setTextColor(Color.WHITE); textSize = 14f }
        val ppgPresets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ppgNote = TextView(this).apply {
            text = "测试预设会清空其他采集项，便于最小对照；需要其他源时再手动勾选。先只选连续 PPG 60 秒；灯灭后再选两种都测。结束后观察红绿灯；若仍亮，导出系统 bugreport，不要重复 17 分钟活动。"
            setTextColor(Color.WHITE); textSize = 12f
        }
        val bia = CheckBox(this).apply { text="活动前后各测体成分 BIA（处理结果，双指触键）"; setTextColor(Color.WHITE) }
        val mfBia=CheckBox(this).apply { text="体成分短测改用多频阻抗 MF-BIA（四频幅值/相位，成功即结束）";setTextColor(Color.WHITE);isChecked=existing?.mfBia()==true }
        val profileBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        var openedPad: View? = null
        fun numberField(label:String, decimal:Boolean)=EditText(this).apply {
            id=View.generateViewId();hint=label;setTextColor(Color.WHITE);setSingleLine()
            // App buttons own entry, so no Samsung/Gboard IME or digit-mode switch is involved.
            keyListener=null;isFocusable=false;isCursorVisible=false;showSoftInputOnFocus=false
            profileBox.addView(this)
            val field=this
            val pad=LinearLayout(this@RecordingActivity).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE }
            listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf(".","0","删除"),listOf("清空","完成")).forEach { keys ->
                val row=LinearLayout(this@RecordingActivity)
                keys.forEach { key ->
                    row.addView(Button(this@RecordingActivity).apply {
                        text=key;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
                        isEnabled=key!="." || decimal
                        setOnClickListener {
                            val old=field.text.toString()
                            when(key) {
                                "完成" -> { pad.visibility=View.GONE;openedPad=null }
                                "清空" -> field.setText("")
                                "删除" -> field.setText(old.dropLast(1))
                                "." -> if(decimal && !old.contains('.')) field.setText(if(old.isEmpty()) "0." else "$old.")
                                else -> if(old.length<9) field.setText(old+key)
                            }
                        }
                    },LinearLayout.LayoutParams(0,(48*resources.displayMetrics.density).toInt(),1f))
                }
                pad.addView(row)
            }
            profileBox.addView(pad)
            setOnClickListener { openedPad?.visibility=View.GONE;pad.visibility=View.VISIBLE;openedPad=pad }
        }
        profileBox.addView(TextView(this).apply {
            text="点击数值，用下方数字按钮填写，无需输入法。保存一次后自动记住，新活动也可复用；体重变化时再修改。资料用于三星体成分估算并随记录保存。20岁以下结果可能不准确。";setTextColor(Color.WHITE)
        })
        val height=numberField("身高（厘米）",true)
        val weight=numberField("体重（千克）",true)
        val age=numberField("年龄（岁）",false)
        val gender=RadioGroup(this)
        val female=RadioButton(this).apply { id=android.view.View.generateViewId();text="女（体成分算法）";setTextColor(Color.WHITE) }
        val male=RadioButton(this).apply { id=android.view.View.generateViewId();text="男（体成分算法）";setTextColor(Color.WHITE) }
        gender.addView(female);gender.addView(male);profileBox.addView(gender)
        val savedProfile=getSharedPreferences("bia-profile",MODE_PRIVATE)
        val remembered=if(savedProfile.contains("height")) com.rawwave.core.BiaProfile(
            savedProfile.getFloat("height",170f),savedProfile.getFloat("weight",65f),
            savedProfile.getInt("age",30),savedProfile.getInt("gender",0)) else null
        (existing?.biaProfile() ?: remembered)?.let { p ->
            bia.isChecked=existing?.bia()==true;height.setText(p.heightCm().toString());weight.setText(p.weightKg().toString());age.setText(p.age().toString())
            gender.check(if(p.gender()==0)female.id else male.id)
        }
        profileBox.visibility=if(bia.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        bia.setOnCheckedChangeListener { _,checked -> profileBox.visibility=if(checked) android.view.View.VISIBLE else android.view.View.GONE }
        val choices = linkedMapOf(
            SensorCatalog.HEART_RATE to CheckBox(this).apply { text = "活动中：心率（处理结果）"; isChecked = true; setTextColor(Color.WHITE) },
            SensorCatalog.ACCELEROMETER to CheckBox(this).apply { text = "活动中：加速度（原始）"; isChecked = true; setTextColor(Color.WHITE) },
            SensorCatalog.SKIN_TEMPERATURE to CheckBox(this).apply { text = "活动中：皮肤温度（处理结果，不是体温）"; setTextColor(Color.WHITE) },
            SensorCatalog.EDA to CheckBox(this).apply { text = "活动中：皮肤电导 EDA（原始，部分型号支持）"; setTextColor(Color.WHITE) },
            SensorCatalog.MAGNETIC_FIELD to CheckBox(this).apply { text="活动中：三轴磁场（Android，微特斯拉，目标10Hz）";setTextColor(Color.WHITE) },
            SensorCatalog.PRESSURE to CheckBox(this).apply { text = "活动中：气压（Android，hPa，目标1Hz）"; setTextColor(Color.WHITE) },
            SensorCatalog.GYROSCOPE to CheckBox(this).apply { text = "活动中：陀螺仪角速度（Android，目标25Hz）"; setTextColor(Color.WHITE) })
        fun addPpgPresetRow(vararg presets: Triple<String, Boolean, Boolean>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            presets.forEach { (label, continuous, short) ->
                row.addView(Button(this).apply {
                    text = label; textSize = 11f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        ppgContinuous.isChecked = continuous
                        ppgShort.isChecked = short
                        // These are test presets: clear other capture items so the
                        // comparison changes only the two PPG modes.
                        ecg.isChecked = false
                        spo2.isChecked = false
                        bia.isChecked = false
                        mfBia.isChecked = false
                        choices.values.forEach { it.isChecked = false }
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            ppgPresets.addView(row)
        }
        addPpgPresetRow(Triple("测试：只连续 PPG", true, false), Triple("测试：只前后短测", false, true))
        addPpgPresetRow(Triple("测试：两种都测（LED 复现）", true, true), Triple("测试：都不选", false, false))
        if (existing != null) {
            name.setText(existing.name()); ecg.isChecked = existing.ecg(); spo2.isChecked = existing.spo2(); ppgShort.isChecked = existing.ppg()
            ppgContinuous.isChecked = SensorCatalog.PPG in existing.coreSources()
            choices.forEach { (source, choice) -> choice.isChecked = source in existing.coreSources() }
        }
        controls.addView(name); controls.addView(ppgTitle); controls.addView(ppgContinuous); controls.addView(ppgShort)
        controls.addView(ppgPresets); controls.addView(ppgNote)
        controls.addView(ecg); controls.addView(spo2); controls.addView(bia);controls.addView(mfBia);controls.addView(profileBox); choices.values.forEach { controls.addView(it) }
        button(if (existing == null) "保存活动" else "修改并保存") {
            try {
                val profile=if(bia.isChecked) com.rawwave.core.BiaProfile(
                    height.text.toString().trim().toFloatOrNull() ?: error("请填写身高（厘米）"),
                    weight.text.toString().trim().toFloatOrNull() ?: error("请填写体重（千克）"),
                    age.text.toString().trim().toIntOrNull() ?: error("请填写年龄（岁）"),
                    when(gender.checkedRadioButtonId) {female.id->0;male.id->1;else->error("请选择体成分算法所需性别")}) else null
                val selected = choices.filterValues { it.isChecked }.keys.toMutableSet()
                if (ppgContinuous.isChecked) selected.add(SensorCatalog.PPG)
                val store = ActivityRecordingService.store(this)
                if (existing == null) store.createTemplate(name.text.toString(), ecg.isChecked, selected, spo2.isChecked, profile, bia.isChecked && mfBia.isChecked, ppgShort.isChecked)
                else store.updateTemplate(existing.id(), name.text.toString(), ecg.isChecked, selected, spo2.isChecked, profile, bia.isChecked && mfBia.isChecked, ppgShort.isChecked)
                if(profile!=null) check(savedProfile.edit().putFloat("height",profile.heightCm())
                    .putFloat("weight",profile.weightKg()).putInt("age",profile.age()).putInt("gender",profile.gender()).commit()) {
                    "活动已保存，但个人资料复用设置保存失败，请重试"
                }
                showActivityManagement()
            }
            catch (e: Exception) { status.text = "保存失败：${e.message}" }
        }
        button("返回") { showActivityManagement() }
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
            if (t != null && grantResults.isNotEmpty()) start(t)
            else status.text = "未获得所需权限，本次没有开始。允许传感器权限后可重新开始。"
        }
    }
    private fun start(t: ActivityStore.Template) {
        cancelAutoSync("CAPTURE_START")
        try {
            check((application as ProbeApplication).activeSyncOwner == null) { "请先结束诊断传输，再开始采集" }
            check(filesDir.usableSpace > 50_000_000L) { "存储不足50MB，不能安全开始记录" }
            idle?.close(); idle = null
            status.text = "正在开始，请稍候…"
            controls.removeAllViews()
            startForegroundService(Intent(this, ActivityRecordingService::class.java).setAction("START").putExtra("template", t.id()))
        } catch (e: Exception) { mode = ""; render(ActivityRecordingService.state); status.text = "无法开始：${e.message}" }
    }
    private fun permissionsFor(t: ActivityStore.Template): List<String> {
        val result = linkedSetOf<String>()
        t.coreSources().forEach { sensor ->
            when {
                sensor == SensorCatalog.MAGNETIC_FIELD || sensor == SensorCatalog.PRESSURE || sensor == SensorCatalog.ACCELEROMETER || sensor == SensorCatalog.GYROSCOPE -> result += Manifest.permission.ACTIVITY_RECOGNITION
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
        SensorCatalog.PPG -> "PPG 25Hz 连续"
        SensorCatalog.PPG_ON_DEMAND -> "PPG 100Hz 短测"
        SensorCatalog.SKIN_TEMPERATURE -> "皮肤温度"
        SensorCatalog.EDA -> "皮肤电导"
        SensorCatalog.MAGNETIC_FIELD -> "三轴磁场"
        SensorCatalog.PRESSURE -> "气压"
        SensorCatalog.GYROSCOPE -> "陀螺仪"
        else -> sensor
    }
    private fun confirmStopCapture() {
        if (stopDialog?.isShowing == true) return
        val dialog = android.app.AlertDialog.Builder(this)
            .setMessage("结束并保存已有数据？\n提前结束不会继续活动后短测。若要按模板完成，请选“继续记录”，再点“结束活动”。")
            .setPositiveButton("结束并保存") { _, _ -> command("CANCEL") }
            .setNegativeButton("继续记录", null)
            .create()
        dialog.setOnDismissListener { if (stopDialog === dialog) stopDialog = null }
        stopDialog = dialog
        dialog.show()
    }
    private fun command(action: String) { startService(Intent(this, ActivityRecordingService::class.java).setAction(action)) }
    private fun idleRuntime(): DeadlineRuntime {
        val current = idle
        if (current != null && !current.isClosed()) return current
        return DeadlineRuntime { reason, evidence -> onIdleDeadline(reason, evidence) }
            .also { it.touchIdle(); idle = it }
    }
    private fun ensureIdle() {
        if (ActivityRecordingService.state.active) return
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
    private fun label(text: String, parent: LinearLayout = body) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
        parent.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        controls.addView(Button(this).apply {
            this.text = text; textSize = 14f; isAllCaps = false
            setOnClickListener { cancelAutoSync("BUTTON_${text.take(40)}"); action() }
        })
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}

/** Code-drawn explanatory animation. Side shown is schematic; Home label is authoritative. */
private class HomeKeyGuide(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var animateContact = false
        set(value) { field = value; if (value) invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width * .42f; val y = height * .48f; val r = height * .30f
        paint.color = Color.LTGRAY; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        canvas.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.CYAN
        canvas.drawRoundRect(x+r, y-r*.6f, x+r+12, y-r*.15f, 4f, 4f, paint)
        val pulse = if (animateContact) ((SystemClock.uptimeMillis() % 1200) / 1200f) else 0f
        paint.color = Color.rgb(255, 208, 162)
        canvas.drawRoundRect(x+r+15+pulse*15, y-r*.6f, width*.94f, y-r*.1f, 12f, 12f, paint)
        paint.color = Color.WHITE; paint.textSize = height*.13f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("手指轻触 Home 键（示意）", width*.5f, height*.95f, paint)
        if (animateContact && isShown) postInvalidateDelayed(80)
    }
}
