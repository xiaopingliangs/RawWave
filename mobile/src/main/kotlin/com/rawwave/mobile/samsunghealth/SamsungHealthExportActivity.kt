package com.rawwave.mobile.samsunghealth

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import java.time.LocalTime
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
    private lateinit var rangeButton: TextView
    private lateinit var allHistoryButton: TextView
    private lateinit var cancelButton: TextView

    private var startDate: LocalDate = LocalDate.now()
    private var endDate: LocalDate = LocalDate.now()

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
            setStatus("已取消保存 / save cancelled。ZIP 已生成但未保存。")
            archive?.delete()
            pendingArchive = null
            return@registerForActivityResult
        }
        if (archive == null || !archive.isFile) {
            setStatus("没有可保存的文件 / nothing to save。")
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                archive.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法打开目标位置 / could not open the destination")
            setStatus("完成 / DONE。已保存 ${archive.length()} 字节。\n${pendingName ?: ""}")
            toast("Samsung Health 导出已保存")
            RwDialogs.message(
                this,
                "官方数据已保存",
                "已保存到系统文件选择器指定的位置。\n\n${pendingName ?: "Samsung Health 导出"}\n" +
                    "${archive.length()} 字节\n\n已回读写入；只读导出，不会写回 Samsung Health。",
                confirmLabel = "知道了",
            )
        } catch (t: Throwable) {
            setStatus("保存失败 / save failed: ${t.message}")
        } finally {
            archive.delete()
            pendingArchive = null
            pendingName = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DailyBoundarySettings(this)
        gateway = AndroidSamsungHealthGateway(applicationContext, zone)

        val savedStart = settings.lastStartDateOrNull()
        val savedEnd = settings.lastEndDateOrNull()
        if (savedStart != null && savedEnd != null) {
            startDate = savedStart
            endDate = savedEnd
        } else {
            // A sensible starting point: the last week, ending today. The user can change it.
            startDate = LocalDate.now(zone).minusDays(6)
            endDate = LocalDate.now(zone)
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
        setContentView(buildUi())

        // The very first use must confirm the user's own day boundary rather than inheriting one.
        if (!settings.hasConfirmedBoundary) {
            showBoundaryPicker(firstRun = true)
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
            setOnBackClickListener { finish() }
        })
        root.addView(RwBodyText(this).apply {
            text = "官方健康基线导出"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colors.primary)
            setPadding(0, 0, 0, dp(2))
        })
        root.addView(RwBodyText(this).apply {
            text = "只读 Data SDK 官方允许读取、聚合、关联读取的数据类型，不做类型筛选，也不会向 Samsung Health 写入任何数据。\n" +
                "读取前需在 Samsung Health 中开启 Developer mode（Data Read）。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(4), 0, dp(4))
        })

        // ---- daily boundary ----
        val boundaryCard = RwCard(this)
        boundaryCard.addView(RwSectionHeader(this).apply {
            text = "数据日界线"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        boundaryCard.addView(RwBodyText(this).apply {
            text = "“我的一天”从几点开始。它只改变查询窗口，不修改 Samsung Health 原始记录。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        })
        boundaryRow = RwListItemButton(this).apply {
            setOnClickListener { showBoundaryPicker(firstRun = false) }
        }
        boundaryCard.addView(boundaryRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        boundaryCard.addView(
            RwSecondaryButton(this).apply {
                text = "这个设置是什么意思？"
                setOnClickListener { showBoundaryExplanation() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        root.addView(boundaryCard, cardParams(16))

        // ---- date range ----
        val rangeCard = RwCard(this)
        rangeCard.addView(RwSectionHeader(this).apply {
            text = "日期范围"
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
        val dateButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateButtons.addView(
            RwSecondaryButton(this).apply {
                text = "选择开始日期"
                setOnClickListener { pickDate(isStart = true) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) },
        )
        dateButtons.addView(
            RwSecondaryButton(this).apply {
                text = "选择结束日期"
                setOnClickListener { pickDate(isStart = false) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) },
        )
        rangeCard.addView(dateButtons)
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
            text = "导出所选日期"
            setOnClickListener { startExport(ExportMode.DATE_RANGE) }
        }
        actionCard.addView(rangeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        allHistoryButton = RwSecondaryButton(this).apply {
            text = "导出全部可用历史"
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
                setStatus("正在取消… / cancelling…")
            }
        }
        actionCard.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        statusView = RwBodyText(this).apply {
            text = "空闲 / idle"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colors.onSurface)
            setPadding(0, dp(12), 0, 0)
        }
        actionCard.addView(statusView)
        root.addView(actionCard, cardParams(12))

        root.addView(RwBodyText(this).apply {
            text = "日期语义为左闭右开；睡眠、运动等跨边界记录整条导出，并在文件里注明与查询窗口的关系。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(16), 0, 0)
        })

        return screen
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)

    private fun render() {
        val boundary = settings.dailyBoundaryOrNull()
        boundaryRow.text = buildString {
            append("我的一天从：")
            append(boundary?.format(TIME_FORMAT) ?: "（尚未设置，必须首次确认）/ not set yet")
            append("   ·   时区 ")
            append(zone.id)
        }

        rangeRow.text = "开始日期：$startDate      结束日期：$endDate"
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
                startDate = startDate,
                endDate = endDate,
            )
            buildString {
                append("实际导出时间（含开始，不含结束）：\n  ")
                append(window.startLocal?.format(DATE_TIME_FORMAT))
                append("  →  ")
                append(window.endExclusiveLocal?.format(DATE_TIME_FORMAT))
                append("\n  区间语义 [start_inclusive, end_exclusive)，不使用 23:59:59")
                if (window.startResolution.name != "EXACT" || window.endResolution.name != "EXACT") {
                    append("\n  时区解析：开始=")
                    append(window.startResolution.name)
                    append("，结束=")
                    append(window.endResolution.name)
                }
            }
        } catch (t: Throwable) {
            "日期范围无效：${t.message}"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------------------------------

    private fun showBoundaryPicker(firstRun: Boolean) {
        val current = settings.dailyBoundaryOrNull() ?: DailyBoundarySettings.DEFAULT_BOUNDARY
        TimePickerDialog(
            this,
            pickerTheme(),
            { _, hour, minute ->
                settings.setDailyBoundary(LocalTime.of(hour, minute))
                setStatus(
                    if (firstRun) {
                        "已设置日界线。现在可以导出。"
                    } else {
                        "日界线已更新。已生成的 ZIP 不受影响——它们保存了当时的设置快照。"
                    },
                )
                render()
            },
            current.hour,
            current.minute,
            true,
        ).apply {
            setTitle(if (firstRun) "请先确认：我的一天从几点开始？" else "我的一天从几点开始？")
            setOnCancelListener {
                if (firstRun && !settings.hasConfirmedBoundary) {
                    setStatus("必须先确认日界线才能导出。")
                }
            }
        }.show()
    }

    private fun showBoundaryExplanation() {
        RwDialogs.message(
            this,
            "我的一天从几点开始",
            """
            这个设置不会修改 Samsung Health 原始记录的时间。

            它只决定 RawWave 在你选择日期范围时，怎样把「某一天」转换成实际查询时间。

            例如设置为 08:00：
            9 月 1 日 表示 9 月 1 日 08:00
            到 9 月 2 日 08:00（不包含）。

            睡眠、运动等跨越这个边界的完整记录不会因为这个设置被人为切断，
            整条记录会被完整导出，并在文件里标明它与查询窗口的关系。

            导出结束时间是「不含」的：结束日期当天整天都包含在内，
            不会使用 23:59:59 这类人为拼接的时间。

            时间用导出时手机当前时区解析（当前：$zone），
            夏令时造成的「不存在」或「重复」的本地时间由系统时区规则处理，
            并把实际解析结果写进导出文件。

            导出的文件会同时保存：
            · Samsung Health 原始时间
            · 本次 RawWave 使用的日期边界规则（快照）
            """.trimIndent(),
        )
    }

    /** Keep the platform pickers in the same light/dark family as the page. */
    private fun pickerTheme(): Int =
        if (RwTheme.isDark(this)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun pickDate(isStart: Boolean) {
        val initial = if (isStart) startDate else endDate
        DatePickerDialog(
            this,
            pickerTheme(),
            { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                if (isStart) {
                    startDate = picked
                    if (endDate.isBefore(startDate)) endDate = startDate
                } else {
                    endDate = picked
                    if (startDate.isAfter(endDate)) startDate = endDate
                }
                settings.setLastRange(startDate, endDate)
                render()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

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
                        if (granted < total) "（导出时会请求剩余权限；拒绝的类型会单独标记为 PERMISSION_DENIED，不影响其他类型）" else ""
                }

                is GatewayRead.Failed ->
                    permissionView.text = "权限状态：无法查询（${state.message ?: state.status.name}）" +
                        "\n导出时仍会尝试读取，未授权的类型会被单独标记。"
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
            showBoundaryPicker(firstRun = true)
            return
        }
        if (mode == ExportMode.DATE_RANGE && endDate.isBefore(startDate)) {
            setStatus("结束日期早于开始日期，请重新选择。")
            return
        }

        cancelRequested = false
        setStatus("准备中… / preparing…")
        try {
            app?.journal?.event(
                "OFFICIAL_EXPORT_STARTED",
                mapOf(
                    "mode" to mode.name,
                    "boundary" to boundary.toString(),
                    "startDate" to startDate.toString(),
                    "endDate" to endDate.toString(),
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
                    setStatus("正在请求 Samsung Health 读取权限… / requesting permissions…")
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
                        startDate = if (mode == ExportMode.DATE_RANGE) startDate else null,
                        endDate = if (mode == ExportMode.DATE_RANGE) endDate else null,
                        stagingRoot = stagingRoot,
                        appVersion = BuildConfig.VERSION_NAME,
                        onProgress = { progress -> liveStatus = describeProgress(progress) },
                        isCancelled = { cancelRequested },
                    )
                }

                if (run.status == ExportRunStatus.CANCELLED) {
                    stagingRoot.deleteRecursively()
                    setStatus("已取消 / cancelled。未生成任何 ZIP。")
                    return@launch
                }

                setStatus(describeProgress(ExportProgress("正在生成 ZIP", null, 0, run.summaries.size, run.totalRecordsExported, run.summaries.size)))

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
                setStatus("导出失败 / export failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                job = null
                render()
            }
        }
    }

    private fun describeProgress(progress: ExportProgress): String = buildString {
        append("阶段：")
        append(progress.phase)
        progress.typeId?.let {
            append("\n当前数据类型：")
            append(it)
            append("  (")
            append(progress.typeIndex)
            append(" / ")
            append(progress.typeTotal)
            append(")")
        }
        append("\n已完成类型：")
        append(progress.completedTypes)
        append(" / ")
        append(progress.typeTotal)
        append("\n当前记录数：")
        append(progress.records)
        if (cancelRequested) append("\n（正在取消…）")
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
                append("导出完成，部分数据类型存在问题 / completed with type problems")
            } else {
                append("导出完成 / completed")
            }
            append("\n数据类型：")
            append(run.summaries.size)
            append(" / 官方 ")
            append(run.officialDataTypeCount)
            append("\n记录总数：")
            append(run.totalRecordsExported)
            append("\nZIP 大小：")
            append(zipBytes)
            append(" 字节")
            if (problems.isNotEmpty()) {
                append("\n\n存在问题的数据类型（详情见 ZIP 内 manifest.json）：")
                for (summary in problems.take(8)) {
                    append("\n  · ")
                    append(summary.typeId)
                    append(": ")
                    append(summary.status)
                    summary.errorMessage?.let { append(" — ").append(it.take(80)) }
                }
                if (problems.size > 8) append("\n  … 其余 ").append(problems.size - 8).append(" 个见 manifest")
            }
        }
    }

    private fun setStatus(message: String) {
        liveStatus = message
        statusView.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
