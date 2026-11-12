package com.rawwave.mobile.samsunghealth

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwGradientTitle
import com.rawwave.designsystem.RwListItemButton
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwScreen
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwSectionHeader
import com.rawwave.designsystem.RwTheme
import com.rawwave.designsystem.RwTopBar
import com.rawwave.shell.BuildConfig
import com.rawwave.shell.ProbeApplication
import com.rawwave.mobile.samsunghealth.export.DailyBoundarySettings
import com.rawwave.mobile.samsunghealth.export.ExportMode
import com.rawwave.mobile.samsunghealth.export.ExportProgress
import com.rawwave.mobile.samsunghealth.export.ExportRunResult
import com.rawwave.mobile.samsunghealth.export.ExportRunStatus
import com.rawwave.mobile.samsunghealth.export.ExportStatus
import com.rawwave.mobile.samsunghealth.export.ExportWindowResolver
import com.rawwave.mobile.samsunghealth.export.SamsungHealthExportCoordinator
import com.rawwave.mobile.samsunghealth.export.SamsungHealthFullExporter
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The formal Samsung Health export screen.
 *
 * It is a second, independent entry point next to the RawWave recording app. It never touches a
 * RawWave watch session, and a session export never touches Samsung Health.
 *
 * There are exactly two actions - export the chosen logical days, or export everything Samsung
 * Health can currently give us - because the SDK already decides what is readable. A data-type
 * picker would only add a second place for data to go missing.
 */
class SamsungHealthExportActivity : ComponentActivity() {

    private val app get() = application as? ProbeApplication
    private lateinit var settings: DailyBoundarySettings
    private lateinit var gateway: SamsungHealthGateway

    private lateinit var boundaryRow: RwListItemButton
    private lateinit var rangeRow: TextView
    private lateinit var windowPreview: TextView
    private lateinit var permissionView: TextView
    private lateinit var statusView: TextView
    private lateinit var startDateButton: TextView
    private lateinit var endDateButton: TextView
    private lateinit var rangeButton: TextView
    private lateinit var allHistoryButton: TextView
    private lateinit var cancelButton: TextView

    private var selectedStartDate: LocalDate = LocalDate.now()
    private var selectedEndDate: LocalDate = LocalDate.now()

    private val ui = Handler(Looper.getMainLooper())
    private val renderer = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 400L)
        }
    }

    /** Written from the export thread, read by the UI ticker. */
    @Volatile
    private var liveStatus: String = ""

    @Volatile
    private var cancelRequested: Boolean = false

    private var job: Job? = null

    /** The finished archive on disk, held until the user picks a destination. */
    private var pendingArchive: File? = null
    private var pendingName: String? = null

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val saveArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val archive = pendingArchive
        if (uri == null) {
            setStatus("已取消保存。导出文件没有写入你选择的位置。")
            archive?.delete()
            pendingArchive = null
            return@registerForActivityResult
        }
        if (archive == null || !archive.isFile) {
            setStatus("没有可保存的导出文件。")
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                archive.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法打开保存位置")
            val name = pendingName ?: "Samsung Health 导出"
            setStatus("已保存：$name")
            RwDialogs.message(
                this,
                "官方数据已保存",
                "已保存到你选择的位置：\n$name\n\n" +
                    "${sizeText(archive.length())}\n\n已确认写入；只读导出，不会写回 Samsung Health。",
                confirmLabel = "知道了",
            )
        } catch (t: Throwable) {
            setStatus("保存失败。请换一个保存位置再试。")
        } finally {
            archive.delete()
            pendingArchive = null
            pendingName = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        settings = DailyBoundarySettings(this)
        gateway = AndroidSamsungHealthGateway(applicationContext, zone)

        val savedStart = settings.lastStartDateOrNull()
        val savedEnd = settings.lastEndDateOrNull()
        selectedStartDate = savedStart ?: LocalDate.now(zone)
        selectedEndDate = savedEnd ?: selectedStartDate
        if (selectedEndDate.isBefore(selectedStartDate)) selectedEndDate = selectedStartDate

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

        // The very first use must confirm the user's own day boundary rather than inheriting one.
        if (!settings.hasConfirmedBoundary) {
            openBoundaryPage()
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(renderer)
        refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(renderer)
    }

    // ------------------------------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val colors = RwTheme.colors(this)
        val screen = RwScreen(this)
        val root = screen.content

        root.addView(RwTopBar(this).apply {
            setTitle("Samsung Health")
            setOnBackClickListener { closePage() }
        })
        root.addView(RwBodyText(this).apply {
            text = "官方健康基线导出"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colors.primary)
            setPadding(0, 0, 0, dp(2))
        })
        // ---- daily boundary ----
        val boundaryCard = RwCard(this)
        boundaryCard.addView(RwSectionHeader(this).apply {
            text = "我的一天从几点开始"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        boundaryRow = RwListItemButton(this).apply {
            setOnClickListener { openBoundaryPage() }
        }
        boundaryCard.addView(boundaryRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        root.addView(boundaryCard, cardParams(16))

        // ---- date range ----
        val rangeCard = RwCard(this)
        rangeCard.addView(RwSectionHeader(this).apply {
            text = "选择日期范围"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        rangeRow = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = RwTheme.boldTypeface()
            setTextColor(colors.onSurface)
            setPadding(0, dp(4), 0, dp(6))
        }
        rangeCard.addView(rangeRow)
        startDateButton = RwSecondaryButton(this).apply {
            setOnClickListener { pickStartDate() }
        }
        rangeCard.addView(startDateButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        endDateButton = RwSecondaryButton(this).apply {
            setOnClickListener { pickEndDate() }
        }
        rangeCard.addView(endDateButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        windowPreview = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(10), 0, 0)
        }
        rangeCard.addView(windowPreview)
        root.addView(rangeCard, cardParams(12))

        // ---- permissions ----
        val permissionCard = RwCard(this)
        permissionCard.addView(RwSectionHeader(this).apply {
            text = "权限状态"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        permissionView = RwBodyText(this).apply {
            text = "权限状态：查询中…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurfaceVariant)
        }
        permissionCard.addView(permissionView)
        root.addView(permissionCard, cardParams(12))

        // ---- actions ----
        val actionCard = RwCard(this)
        actionCard.addView(RwSectionHeader(this).apply {
            text = "导出"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        rangeButton = RwPrimaryButton(this).apply {
            text = "导出所选日期的数据"
            setOnClickListener { startExport(ExportMode.DATE_RANGE) }
        }
        actionCard.addView(rangeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        allHistoryButton = RwSecondaryButton(this).apply {
            text = "导出全部可用数据"
            setOnClickListener { startExport(ExportMode.ALL_AVAILABLE_HISTORY) }
        }
        actionCard.addView(allHistoryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        cancelButton = RwSecondaryButton(this).apply {
            text = "取消导出"
            isEnabled = false
            setOnClickListener {
                cancelRequested = true
                setStatus("正在取消…")
            }
        }
        actionCard.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        statusView = RwBodyText(this).apply {
            text = "还没有开始导出。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colors.onSurface)
            setPadding(0, dp(12), 0, 0)
        }
        actionCard.addView(statusView)
        root.addView(actionCard, cardParams(12))

        // Explanations sit at the bottom, next to the way out, not above the buttons.
        root.addView(RwBodyText(this).apply {
            text = "说明：只读取 Samsung Health 已授权的记录，不会写回或修改原始数据。\n" +
                "日期范围包含开始日；“我的一天从几点开始”决定每天的分界时刻。睡眠、运动等跨边界记录会整条导出，并在文件里注明与查询窗口的关系。\n" +
                "首次使用前，请在 Samsung Health 中允许原波读取健康数据。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colors.onSurfaceVariant)
            setLineSpacing(dp(2).toFloat(), 1f)
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

    private fun closePage() {
        if (job != null) {
            RwDialogs.message(
                this,
                "正在导出",
                "请等导出完成后再返回。现在离开会中断导出，不会生成可用的导出文件。",
                confirmLabel = "知道了",
            )
            return
        }
        finish()
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)

    private fun render() {
        val boundary = settings.dailyBoundaryOrNull()
        boundaryRow.text = if (boundary == null) {
            "我的一天从：尚未设置 · 点此设置"
        } else {
            "我的一天从：${boundary.format(TIME_FORMAT)} · 点此修改"
        }

        rangeRow.text = "${selectedStartDate.format(DATE_FORMAT)}  →  ${selectedEndDate.format(DATE_FORMAT)}"
        startDateButton.text = "开始日期：${selectedStartDate.format(DATE_FORMAT)}"
        endDateButton.text = "结束日期：${selectedEndDate.format(DATE_FORMAT)}"
        windowPreview.text = describeResolvedWindow()

        val exportable = boundary != null && job == null
        rangeButton.isEnabled = exportable
        allHistoryButton.isEnabled = job == null
        cancelButton.isEnabled = job != null
        if (liveStatus.isNotEmpty()) statusView.text = liveStatus
    }

    /**
     * Shows the user exactly what their chosen days will turn into, before they export.
     *
     * This is the whole visible contract of the daily boundary: the dates are logical, and the
     * instants on the right are what will actually be requested.
     */
    private fun describeResolvedWindow(): String {
        val boundary = settings.dailyBoundaryOrNull() ?: return "设置日界线后显示实际导出时间。"
        return try {
            val window = ExportWindowResolver.resolve(
                mode = ExportMode.DATE_RANGE,
                boundary = boundary,
                zoneId = zone,
                startDate = selectedStartDate,
                endDate = selectedEndDate,
            )
            buildString {
                append("实际导出时间：\n  ")
                append(window.startLocal?.format(DATE_TIME_FORMAT))
                append("  →  ")
                append(window.endExclusiveLocal?.format(DATE_TIME_FORMAT))
                if (window.startResolution.name != "EXACT" || window.endResolution.name != "EXACT") {
                    append("\n边界靠近时区切换，已按手机当前时区调整。")
                }
            }
        } catch (t: Throwable) {
            "日期范围无效：${t.message}"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------------------------------

    private fun openBoundaryPage() {
        startActivity(Intent(this, DailyBoundaryActivity::class.java))
    }

    /** Keep the platform pickers in the same light/dark family as the page. */
    private fun pickerTheme(): Int =
        if (RwTheme.isDark(this)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun pickStartDate() {
        val initial = selectedStartDate
        val dialog = DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                selectedStartDate = LocalDate.of(year, month + 1, day)
                if (selectedEndDate.isBefore(selectedStartDate)) selectedEndDate = selectedStartDate
                settings.setLastRange(selectedStartDate, selectedEndDate)
                render()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        )
        dialog.datePicker.maxDate = endOfTodayMillis()
        dialog.show()
    }

    private fun pickEndDate() {
        val initial = selectedEndDate
        val dialog = DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                selectedEndDate = LocalDate.of(year, month + 1, day)
                if (selectedStartDate.isAfter(selectedEndDate)) selectedStartDate = selectedEndDate
                settings.setLastRange(selectedStartDate, selectedEndDate)
                render()
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

    // ------------------------------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------------------------------

    private fun refreshPermissions() {
        lifecycleScope.launch {
            permissionView.text = "权限状态：查询中…"
            when (val state = gateway.grantedReadPermissions()) {
                is GatewayRead.Ok -> {
                    val total = gateway.dataTypes().size
                    val granted = state.value.size
                    permissionView.text = "权限状态：已授权 $granted / $total 个数据类型" +
                        if (granted < total) "（导出时会请求剩余权限；拒绝的类型会跳过，不影响其他类型）" else ""
                }

                is GatewayRead.Failed ->
                    permissionView.text = "权限状态：暂时无法查询。" +
                        "\n导出时仍会重试；未授权的类型会被跳过。"
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------

    private fun startExport(mode: ExportMode) {
        val boundary = settings.dailyBoundaryOrNull()
        if (boundary == null) {
            setStatus("请先确认「我的一天从几点开始」，再导出。")
            openBoundaryPage()
            return
        }
        if (mode == ExportMode.DATE_RANGE && !settings.hasConfirmedBoundary) {
            setStatus("请先确认“我的一天从几点开始”，再导出。")
            openBoundaryPage()
            return
        }

        cancelRequested = false
        setStatus("准备中…")
        try {
            app?.journal?.event(
                "OFFICIAL_EXPORT_STARTED",
                mapOf(
                    "mode" to mode.name,
                    "boundary" to boundary.toString(),
                    "startDate" to selectedStartDate.toString(),
                    "endDate" to selectedEndDate.toString(),
                    "timezone" to zone.id,
                ),
            )
        } catch (_: Exception) {}

        job = lifecycleScope.launch {
            try {
                // Permissions are requested for every official type up front. A type the user
                // refuses is reported as PERMISSION_DENIED and the rest still export; RawWave does
                // not quietly drop a type because its permission was declined.
                val allTypes = gateway.dataTypes().map { it.typeId }.toSet()
                val grantedNow = when (val state = gateway.grantedReadPermissions()) {
                    is GatewayRead.Ok -> state.value
                    is GatewayRead.Failed -> emptySet()
                }
                if (!grantedNow.containsAll(allTypes)) {
                    setStatus("正在请求 Samsung Health 读取权限…")
                    gateway.requestReadPermissions(this@SamsungHealthExportActivity, allTypes)
                    refreshPermissions()
                }

                val stamp = System.currentTimeMillis()
                val stagingRoot = File(cacheDir, "samsunghealth-export-$stamp")
                val coordinator = SamsungHealthExportCoordinator(gateway)

                val run = withContext(Dispatchers.IO) {
                    coordinator.run(
                        mode = mode,
                        boundary = boundary,
                        startDate = if (mode == ExportMode.DATE_RANGE) selectedStartDate else null,
                        endDate = if (mode == ExportMode.DATE_RANGE) selectedEndDate else null,
                        stagingRoot = stagingRoot,
                        appVersion = BuildConfig.VERSION_NAME,
                        onProgress = { progress -> liveStatus = describeProgress(progress) },
                        isCancelled = { cancelRequested },
                    )
                }

                if (run.status == ExportRunStatus.CANCELLED) {
                    stagingRoot.deleteRecursively()
                    setStatus("已取消，没有生成导出文件。")
                    return@launch
                }

                setStatus(describeProgress(ExportProgress("zip", null, 0, run.summaries.size, run.totalRecordsExported, run.summaries.size)))

                val archiveName = SamsungHealthFullExporter.archiveNameFor(mode, run.window, stamp, zone)
                val archive = withContext(Dispatchers.IO) {
                    val temp = File(cacheDir, archiveName)
                    temp.outputStream().use { out ->
                        SamsungHealthFullExporter(appVersion = BuildConfig.VERSION_NAME).export(out, run, zone)
                    }
                    temp
                }

                withContext(Dispatchers.IO) { stagingRoot.deleteRecursively() }

                try {
                    app?.journal?.event(
                        "OFFICIAL_EXPORT_BUILT",
                        mapOf(
                            "status" to run.status.name,
                            "types" to run.summaries.size,
                            "records" to run.totalRecordsExported,
                            "bytes" to archive.length(),
                            "archive" to archiveName,
                        ),
                    )
                } catch (_: Exception) {}

                pendingArchive = archive
                pendingName = archiveName
                setStatus(summarise(run, archive.length()) + "\n正在选择保存位置…")
                saveArchive.launch(archiveName)
            } catch (t: Throwable) {
                cancelRequested = false
                try { app?.journal?.fault("OFFICIAL_EXPORT_FAILED", t) } catch (_: Exception) {}
                setStatus("导出失败。请稍后重试；没有生成可用的导出文件。")
            } finally {
                job = null
                render()
            }
        }
    }

    private fun describeProgress(progress: ExportProgress): String = buildString {
        when (progress.phase) {
            "zip" -> append("正在生成导出文件…")
            "type_done" -> append("正在整理已读取的数据…")
            else -> append("正在读取 Samsung Health 数据…")
        }
        if (progress.typeTotal > 0) {
            append("\n已完成 ")
            append(progress.completedTypes)
            append(" / ")
            append(progress.typeTotal)
            append(" 类")
        }
        append("\n当前记录：")
        append(progress.records)
        append(" 条")
        if (cancelRequested) append("\n正在取消…")
    }

    /** The end-of-run message. It never says "success" when a type reported a problem. */
    private fun summarise(run: ExportRunResult, zipBytes: Long): String {
        val problems = run.summaries.filter {
            it.status != ExportStatus.OK &&
                it.status != ExportStatus.NO_DATA &&
                it.status != ExportStatus.NESTED_IN_PARENT_TYPE
        }
        return buildString {
            if (run.status == ExportRunStatus.COMPLETED_WITH_TYPE_PROBLEMS) {
                append("导出完成，部分数据类型没有完整读取")
            } else {
                append("导出完成")
            }
            append("\n共 ")
            append(run.summaries.size)
            append(" 类 · ")
            append(run.totalRecordsExported)
            append(" 条记录")
            append("\n文件约 ")
            append(sizeText(zipBytes))
            if (problems.isNotEmpty()) {
                append("\n\n有 ")
                append(problems.size)
                append(" 类数据没有完整导出。原因已写进导出文件内的说明；其余数据不受影响。")
            }
        }
    }

    private fun setStatus(message: String) {
        liveStatus = message
        statusView.text = message
    }

    private fun sizeText(bytes: Long): String =
        if (bytes < 1024L * 1024L) "${(bytes + 1023L) / 1024L} KB"
        else "%.1f MB".format(bytes / 1024.0 / 1024.0)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
