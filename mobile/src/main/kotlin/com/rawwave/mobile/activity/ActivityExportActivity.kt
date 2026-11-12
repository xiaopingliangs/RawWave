package com.rawwave.mobile.activity

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.rawwave.core.ActivityDataExporter
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwScreen
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwSectionHeader
import com.rawwave.designsystem.RwTheme
import com.rawwave.designsystem.RwTopBar
import com.rawwave.shell.DownloadsExportDestination
import com.rawwave.shell.ProbeApplication
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Raw activity export by activity start date.
 *
 * One package may contain several activities. The screen offers the four choices the user asked
 * for - latest, latest three, today, or one chosen date - and exports exactly those sessions as
 * one verified ZIP. It never edits the inbox or the Samsung Health flow.
 */
class ActivityExportActivity : ComponentActivity() {

    private enum class Mode { LATEST, RECENT3, TODAY, RANGE }

    private val app get() = application as ProbeApplication
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView
    private lateinit var exportButton: TextView
    private lateinit var latestButton: TextView
    private lateinit var recent3Button: TextView
    private lateinit var todayButton: TextView
    private lateinit var startButton: TextView
    private lateinit var endButton: TextView
    private var sessions: List<ActivityDataExporter.SessionChoice> = emptyList()
    private var mode = Mode.LATEST
    private var startDate: LocalDate = LocalDate.now()
    private var endDate: LocalDate = LocalDate.now()
    private var loaded = false
    private var loading = false
    private var working = false
    private var firstResume = true
    private var modeSelectedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val colors = RwTheme.colors(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (RwTheme.isDark(this)) {
            window.decorView.systemUiVisibility and lightFlags.inv()
        } else {
            window.decorView.systemUiVisibility or lightFlags
        }
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closePage() }
        })
        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        // A new activity package may arrive while this screen is in the back stack. Reload the
        // session list when the user returns; otherwise "最近三次" can silently use a stale list.
        if (firstResume) {
            firstResume = false
            return
        }
        if (loaded && !working) loadSessions(initial = false)
    }

    private fun buildUi(): View {
        val colors = RwTheme.colors(this)
        val screen = RwScreen(this)
        val root = screen.content

        root.addView(RwTopBar(this).apply {
            setTitle("导出原始活动数据")
            setOnBackClickListener { closePage() }
        })

        val quickCard = RwCard(this)
        quickCard.addView(RwSectionHeader(this).apply {
            text = "选择活动"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        latestButton = RwSecondaryButton(this).apply {
            text = "最近一次"
            setOnClickListener { selectMode(Mode.LATEST) }
        }
        recent3Button = RwSecondaryButton(this).apply {
            text = "最近三次"
            setOnClickListener { selectMode(Mode.RECENT3) }
        }
        todayButton = RwSecondaryButton(this).apply {
            text = "今天"
            setOnClickListener { selectMode(Mode.TODAY) }
        }
        quickRow.addView(latestButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(0, dp(8), dp(3), 0) })
        quickRow.addView(recent3Button, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(dp(3), dp(8), dp(3), 0) })
        quickRow.addView(todayButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(dp(3), dp(8), 0, 0) })
        quickCard.addView(quickRow)
        startButton = RwSecondaryButton(this).apply {
            setOnClickListener { pickStartDate() }
        }
        quickCard.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        endButton = RwSecondaryButton(this).apply {
            setOnClickListener { pickEndDate() }
        }
        quickCard.addView(endButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        root.addView(quickCard, cardParams(16))

        val exportCard = RwCard(this)
        exportCard.addView(RwSectionHeader(this).apply {
            text = "导出内容"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        summaryView = RwBodyText(this).apply {
            text = "正在读取已接收活动…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colors.onSurface)
        }
        exportCard.addView(summaryView)
        exportButton = RwPrimaryButton(this).apply {
            text = "导出所选活动"
            isEnabled = false
            setOnClickListener { exportSelected() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        exportCard.addView(exportButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        statusView = RwBodyText(this).apply {
            text = ""
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
        }
        exportCard.addView(statusView)
        root.addView(exportCard, cardParams(12))

        // Explanations sit at the bottom, next to the way out, not above the buttons.
        root.addView(RwBodyText(this).apply {
            text = "保存位置：Download/RawWave。导出的是原始活动包，只读，不会改动手表里的记录；同名文件不会覆盖。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(RwSecondaryButton(this).apply {
            text = "返回"
            setOnClickListener { closePage() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        return screen
    }

    private fun selectMode(next: Mode) {
        if (working) return
        mode = next
        modeSelectedByUser = true
        refreshSelection()
        // Re-read the inbox now, not only when the Activity was first created. This makes
        // "最近三次" include a package that arrived while this page was already open.
        if (loaded) loadSessions(initial = false)
    }

    private fun loadSessions(initial: Boolean = true) {
        if (loading) return
        loading = true
        statusView.text = if (initial) "正在读取已接收活动（已校验的包不重复扫描）…"
            else "正在刷新已接收活动…"
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    try {
                        exporter().sessionChoices()
                    } catch (_: ActivityDataExporter.NoDataException) {
                        emptyList()
                    }
                }
            } catch (failure: Exception) {
                loading = false
                loaded = true
                statusView.text = "读取失败：${failure.javaClass.simpleName}: ${failure.message}"
                refreshSelection()
                return@launch
            }
            loading = false
            sessions = result
            loaded = true
            if (initial && !modeSelectedByUser) {
                if (sessions.any { isToday(it) }) mode = Mode.TODAY else mode = Mode.LATEST
            }
            statusView.text = if (sessions.isEmpty()) "手机还没有收到活动数据。" else ""
            refreshSelection()
        }
    }

    private fun refreshSelection() {
        if (!loaded) return
        val selected = selectedSessions()
        val inRange = mode == Mode.RANGE
        latestButton.text = if (mode == Mode.LATEST) "● 最近一次" else "最近一次"
        recent3Button.text = if (mode == Mode.RECENT3) "● 最近三次" else "最近三次"
        todayButton.text = if (mode == Mode.TODAY) "● 今天" else "今天"
        startButton.text = (if (inRange) "● " else "") + "开始日期：${startDate.format(DAY_FORMAT)}"
        endButton.text = (if (inRange) "● " else "") + "结束日期：${endDate.format(DAY_FORMAT)}"
        val bytes = selected.map { it.fileBytes() }.distinct().sum()
        summaryView.text = when {
            sessions.isEmpty() -> "没有已接收的活动。请先在手表结束活动并完成同步。"
            selected.isEmpty() -> when (mode) {
                Mode.TODAY -> "今天还没有活动。"
                Mode.RANGE -> "${startDate.format(DAY_FORMAT)} → ${endDate.format(DAY_FORMAT)} 没有活动。"
                else -> "这个范围内没有活动。"
            }
            mode == Mode.LATEST -> {
                val latest = selected.first()
                "最近一次：${latest.activityName()} · ${formatTime(latest.createdUtcMs())}\n共 1 次活动 · ${sizeText(bytes)}"
            }
            mode == Mode.RECENT3 -> "最近三次活动\n共 ${selected.size} 次活动 · 原始包约 ${sizeText(bytes)}"
            mode == Mode.TODAY -> "今天（${LocalDate.now().format(DAY_FORMAT)}）\n共 ${selected.size} 次活动 · 原始包约 ${sizeText(bytes)}"
            mode == Mode.RANGE -> "${startDate.format(DAY_FORMAT)} → ${endDate.format(DAY_FORMAT)}\n共 ${selected.size} 次活动 · 原始包约 ${sizeText(bytes)}"
            else -> "共 ${selected.size} 次活动 · 原始包约 ${sizeText(bytes)}"
        }
        exportButton.isEnabled = !working && selected.isNotEmpty()
    }

    private fun selectedSessions(): List<ActivityDataExporter.SessionChoice> {
        if (!loaded) return emptyList()
        return when (mode) {
            Mode.LATEST -> sessions.maxByOrNull { it.createdUtcMs() }?.let { listOf(it) }.orEmpty()
            Mode.RECENT3 -> sessions.sortedByDescending { it.createdUtcMs() }.take(3)
            Mode.TODAY -> sessions.filter(::isToday)
            Mode.RANGE -> sessions.filter(::isInRange)
        }
    }

    private fun isToday(item: ActivityDataExporter.SessionChoice): Boolean =
        dateOf(item) == LocalDate.now()

    private fun isInRange(item: ActivityDataExporter.SessionChoice): Boolean =
        ActivityDateSelection.contains(dateOf(item), startDate, endDate)

    private fun dateOf(item: ActivityDataExporter.SessionChoice): LocalDate =
        Instant.ofEpochMilli(item.createdUtcMs()).atZone(zone).toLocalDate()

    private fun closePage() {
        if (working) {
            RwDialogs.message(
                this,
                "正在导出",
                "请等导出完成后再返回。现在离开会中断导出，手机里的原始活动包仍会保留。",
                confirmLabel = "知道了",
            )
            return
        }
        finish()
    }

    private fun pickStartDate() {
        if (working) return
        val initial = startDate
        val dialog = DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                startDate = LocalDate.of(year, month + 1, day)
                if (endDate.isBefore(startDate)) endDate = startDate
                mode = Mode.RANGE
                modeSelectedByUser = true
                refreshSelection()
                if (loaded) loadSessions(initial = false)
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        )
        dialog.datePicker.maxDate = endOfTodayMillis()
        dialog.show()
    }

    private fun pickEndDate() {
        if (working) return
        val initial = endDate
        val dialog = DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                endDate = LocalDate.of(year, month + 1, day)
                if (startDate.isAfter(endDate)) startDate = endDate
                mode = Mode.RANGE
                modeSelectedByUser = true
                refreshSelection()
                if (loaded) loadSessions(initial = false)
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        )
        dialog.datePicker.maxDate = endOfTodayMillis()
        dialog.show()
    }

    private fun endOfTodayMillis(): Long =
        LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

    private fun exportSelected() {
        if (working) return
        val selected = selectedSessions()
        if (selected.isEmpty()) {
            statusView.text = "没有可导出的活动。"
            return
        }
        val owner = app.activeSyncOwner
        if (owner != null) {
            statusView.text = "正在接收手表数据；完成后再导出。"
            return
        }
        val token = "activity-export-${UUID.randomUUID()}"
        app.activeSyncOwner = token
        working = true
        exportButton.isEnabled = false
        statusView.text = "正在导出并确认文件完整（${selected.size} 次活动）…"
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    exporter().exportSessions(selected.map { it.id() }) { copied, total ->
                        val percent = if (total > 0) (copied * 100 / total).toInt() else 0
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                statusView.text = if (total > 0 && copied >= total) {
                                    // Copy is done; the read-back integrity check has no byte
                                    // progress. Say so instead of leaving a misleading 100%.
                                    "导出已写入，正在确认文件完整…（${sizeText(total)}）"
                                } else {
                                    "正在导出… $percent% (${sizeText(copied)} / ${sizeText(total)})"
                                }
                            }
                        }
                    }
                }
                val first = results.firstOrNull()
                if (first == null) {
                    statusView.text = "没有可导出的活动。"
                } else {
                    statusView.text = "已保存并校验：Download/RawWave/${first.name()}"
                    RwDialogs.message(
                        this@ActivityExportActivity,
                        "导出完成",
                        "已保存到：\nDownload/RawWave/${first.name()}\n\n" +
                            "共 ${first.sessions().size} 次活动 · ${sizeText(first.bytes())}\n" +
                            "已确认文件完整。",
                        confirmLabel = "知道了",
                    )
                }
            } catch (failure: Exception) {
                statusView.text = "导出失败。手机里的原始活动包仍然保留。"
                RwDialogs.message(
                    this@ActivityExportActivity,
                    "导出失败",
                    "没有生成可用的 ZIP，请重试。\n手机里的原始活动包仍然保留。",
                    confirmLabel = "知道了",
                )
            } finally {
                if (app.activeSyncOwner == token) app.activeSyncOwner = null
                working = false
                refreshSelection()
            }
        }
    }

    private fun exporter(): ActivityDataExporter =
        ActivityDataExporter(File(filesDir, "activity-inbox"), DownloadsExportDestination(contentResolver)) { folder ->
            val fd = android.system.Os.open(folder.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }

    private fun pickerTheme(): Int =
        if (RwTheme.isDark(this)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun formatTime(utcMs: Long): String =
        Instant.ofEpochMilli(utcMs).atZone(zone).format(TIME_FORMAT)

    /** Small sessions must not look like 0.0 MB; the five-second PRE test is 33 KB. */
    private fun sizeText(bytes: Long): String =
        if (bytes < 1024L * 1024L) "${(bytes + 1023L) / 1024L} KB"
        else "%.1f MB".format(bytes / 1024.0 / 1024.0)

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
