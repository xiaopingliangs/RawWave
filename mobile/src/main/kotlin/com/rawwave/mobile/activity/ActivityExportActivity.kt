package com.rawwave.mobile.activity

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
 * Raw activity export by real activity date.
 *
 * The transport package is not the product: one package may contain several activities. This
 * screen lists sessions, lets the user pick latest/today/range, and exports the chosen sessions
 * as one new verified ZIP. It never edits the inbox or the official Samsung Health flow.
 */
class ActivityExportActivity : ComponentActivity() {

    private enum class Mode { LATEST, TODAY, RANGE }

    private val app get() = application as ProbeApplication
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private lateinit var summaryView: TextView
    private lateinit var rangeView: TextView
    private lateinit var statusView: TextView
    private lateinit var exportButton: TextView
    private lateinit var latestButton: TextView
    private lateinit var todayButton: TextView
    private lateinit var startButton: TextView
    private lateinit var endButton: TextView
    private var sessions: List<ActivityDataExporter.SessionChoice> = emptyList()
    private var mode = Mode.LATEST
    private var startDate: LocalDate = LocalDate.now()
    private var endDate: LocalDate = LocalDate.now()
    private var loaded = false
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        loadSessions()
    }

    private fun buildUi(): View {
        val colors = RwTheme.colors(this)
        val screen = RwScreen(this)
        val root = screen.content

        root.addView(RwTopBar(this).apply {
            setTitle("导出原始活动数据")
            setOnBackClickListener { finish() }
        })
        root.addView(RwBodyText(this).apply {
            text = "按活动开始时间选择。导出的是原始 ZIP，不做健康评分，也不会改动手表里的记录。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        })

        val quickCard = RwCard(this)
        quickCard.addView(RwSectionHeader(this).apply {
            text = "快捷选择"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        latestButton = RwPrimaryButton(this).apply {
            text = "最近一次"
            setOnClickListener { mode = Mode.LATEST; refreshSelection() }
        }
        todayButton = RwSecondaryButton(this).apply {
            text = "今天"
            setOnClickListener { mode = Mode.TODAY; refreshSelection() }
        }
        quickRow.addView(latestButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(0, dp(8), dp(4), 0) })
        quickRow.addView(todayButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(dp(4), dp(8), 0, 0) })
        quickCard.addView(quickRow)
        root.addView(quickCard, cardParams(16))

        val rangeCard = RwCard(this)
        rangeCard.addView(RwSectionHeader(this).apply {
            text = "日期范围"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        rangeView = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colors.onSurface)
            setPadding(0, dp(4), 0, dp(6))
        }
        rangeCard.addView(rangeView)
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = RwSecondaryButton(this).apply {
            text = "开始日期"
            setOnClickListener { pickDate(true) }
        }
        endButton = RwSecondaryButton(this).apply {
            text = "结束日期"
            setOnClickListener { pickDate(false) }
        }
        dateRow.addView(startButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(0, 0, dp(4), 0) })
        dateRow.addView(endButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(dp(4), 0, 0, 0) })
        rangeCard.addView(dateRow)
        root.addView(rangeCard, cardParams(12))

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

        root.addView(RwBodyText(this).apply {
            text = "保存位置：Download/RawWave。同名文件不会覆盖；每次导出都会回读校验 SHA-256。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(16), 0, 0)
        })
        return screen
    }

    private fun loadSessions() {
        statusView.text = "正在校验已接收的活动包…"
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
                loaded = true
                statusView.text = "读取失败：${failure.javaClass.simpleName}: ${failure.message}"
                refreshSelection()
                return@launch
            }
            sessions = result
            loaded = true
            if (sessions.any { isToday(it) }) mode = Mode.TODAY else mode = Mode.LATEST
            statusView.text = if (sessions.isEmpty()) "手机还没有收到活动数据。" else ""
            refreshSelection()
        }
    }

    private fun refreshSelection() {
        if (!loaded) return
        val selected = selectedSessions()
        rangeView.text = when (mode) {
            Mode.LATEST -> "最近一次活动"
            Mode.TODAY -> "今天（${LocalDate.now().format(DAY_FORMAT)}）"
            Mode.RANGE -> "${startDate.format(DAY_FORMAT)} → ${endDate.format(DAY_FORMAT)}"
        }
        latestButton.text = if (mode == Mode.LATEST) "● 最近一次" else "最近一次"
        todayButton.text = if (mode == Mode.TODAY) "● 今天" else "今天"
        latestButton.alpha = if (mode == Mode.LATEST) 1f else 0.72f
        todayButton.alpha = if (mode == Mode.TODAY) 1f else 0.72f
        startButton.alpha = if (mode == Mode.RANGE) 1f else 0.72f
        endButton.alpha = if (mode == Mode.RANGE) 1f else 0.72f
        val bytes = selected.map { it.fileBytes() }.distinct().sum()
        summaryView.text = when {
            sessions.isEmpty() -> "没有已接收的活动。请先在手表结束活动并完成同步。"
            selected.isEmpty() -> "这个日期范围内没有活动。"
            mode == Mode.LATEST -> "最近一次：${formatTime(selected.first().createdUtcMs())}\n共 1 次活动"
            else -> "${selected.size} 次活动\n原始包约 ${bytes / 1024 / 1024} MB"
        }
        exportButton.isEnabled = !working && selected.isNotEmpty()
    }

    private fun selectedSessions(): List<ActivityDataExporter.SessionChoice> {
        if (!loaded) return emptyList()
        return when (mode) {
            Mode.LATEST -> sessions.maxByOrNull { it.createdUtcMs() }?.let { listOf(it) }.orEmpty()
            Mode.TODAY -> sessions.filter(::isToday)
            Mode.RANGE -> sessions.filter { inRange(it.createdUtcMs()) }
        }
    }

    private fun isToday(item: ActivityDataExporter.SessionChoice): Boolean {
        val date = Instant.ofEpochMilli(item.createdUtcMs()).atZone(zone).toLocalDate()
        return date == LocalDate.now()
    }

    private fun inRange(createdUtcMs: Long): Boolean {
        val date = Instant.ofEpochMilli(createdUtcMs).atZone(zone).toLocalDate()
        return !date.isBefore(startDate) && !date.isAfter(endDate)
    }

    private fun pickDate(start: Boolean) {
        val initial = if (start) startDate else endDate
        DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                if (start) {
                    startDate = picked
                    if (endDate.isBefore(startDate)) endDate = startDate
                } else {
                    endDate = picked
                    if (startDate.isAfter(endDate)) startDate = endDate
                }
                mode = Mode.RANGE
                refreshSelection()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

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
        statusView.text = "正在导出并回读校验 ${selected.size} 次活动…"
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    exporter().exportSessions(selected.map { it.id() })
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
                            "${first.bytes()} 字节 · ${first.sessions().size} 次活动\n" +
                            "已回读校验 SHA-256：\n${first.sha256()}",
                        confirmLabel = "知道了",
                    )
                }
            } catch (failure: Exception) {
                statusView.text = "导出失败：${failure.javaClass.simpleName}: ${failure.message}"
                RwDialogs.message(
                    this@ActivityExportActivity,
                    "导出失败",
                    "没有生成可用的 ZIP。\n${failure.javaClass.simpleName}: ${failure.message}\n\n手机里的原始活动包仍然保留。",
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
