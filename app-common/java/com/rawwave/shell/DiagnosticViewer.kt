package com.rawwave.shell

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rawwave.core.DiagnosticFeedback
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwTheme
import com.rawwave.designsystem.RwTopBar
import java.util.concurrent.Executors

/** Explicit snapshot only; no polling, service, radio, wake lock or lifecycle handoff. */
object DiagnosticViewer {
    fun show(activity: Activity, app: ProbeApplication) {
        val colors = RwTheme.colors(activity)
        val dialog = Dialog(activity)
        val round = activity.resources.configuration.isScreenRound
        val inset = if (round) {
            (activity.resources.displayMetrics.widthPixels * .15f).toInt()
        } else {
            (20 * activity.resources.displayMetrics.density).toInt()
        }
        val vertical = if (round) (32 * activity.resources.displayMetrics.density).toInt()
        else (28 * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, vertical, inset, vertical)
            setBackgroundColor(colors.background)
        }
        body.addView(RwTopBar(activity).apply {
            setTitle("诊断详情")
            setOnBackClickListener { dialog.dismiss() }
        })
        body.addView(RwBodyText(activity).apply {
            text = "只读快照 · 不启动采集或同步 · 重新打开才更新"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, (8 * activity.resources.displayMetrics.density).toInt())
        })
        val text = TextView(activity).apply {
            textSize = 12.5f
            typeface = RwTheme.boldTypeface()
            setTextColor(colors.onSurface)
            setTextIsSelectable(true)
            setLineSpacing((3 * activity.resources.displayMetrics.density).toFloat(), 1f)
            minimumHeight = (120 * activity.resources.displayMetrics.density).toInt()
        }
        body.addView(text, LinearLayout.LayoutParams(-1, -2))

        var pages = listOf("正在读取本机日志快照…")
        var quick = pages
        var full = pages
        var showingFull = false
        var index = 0
        fun render() {
            text.text = "${index + 1}/${pages.size}\n${pages[index]}"
        }
        fun styledButton(title: String, primary: Boolean = false, action: () -> Unit): TextView {
            val view = if (primary) RwPrimaryButton(activity) else RwSecondaryButton(activity)
            view.text = title
            view.setOnClickListener { action() }
            return view
        }
        fun addRow(views: List<TextView>) {
            if (round) {
                views.forEach { view ->
                    body.addView(view, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (6 * activity.resources.displayMetrics.density).toInt() })
                }
            } else {
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                views.forEach { view ->
                    row.addView(view, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply { setMargins((3 * activity.resources.displayMetrics.density).toInt(), 0, 0, 0) })
                }
                body.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * activity.resources.displayMetrics.density).toInt() })
            }
        }

        addRow(listOf(
            styledButton("上页") { if (index > 0) { index--; render() } },
            styledButton("下页") { if (index + 1 < pages.size) { index++; render() } },
        ))
        lateinit var mode: TextView
        mode = styledButton("完整详情") {
            showingFull = !showingFull
            pages = if (showingFull) full else quick
            mode.text = if (showingFull) "快速5页" else "完整详情"
            index = 0; render()
        }
        addRow(listOf(mode, styledButton("关闭", primary = true) { dialog.dismiss() }))

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(colors.background)
            addView(body)
        }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(colors.background))
            statusBarColor = colors.background
            navigationBarColor = colors.background
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility = if (RwTheme.isDark(activity)) {
                decorView.systemUiVisibility and light.inv()
            } else {
                decorView.systemUiVisibility or light
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        render()
        val worker = Executors.newSingleThreadExecutor()
        dialog.setOnDismissListener { worker.shutdownNow() }
        worker.execute {
            val loaded = try {
                val journal = app.journal ?: error(app.journalFailure ?: "日志未初始化")
                val version = "原波诊断 · 只读快照\n${BuildConfig.DEVICE_ROLE}\n${BuildConfig.VERSION_NAME}\n${BuildConfig.BUILD_RUN}\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n系统 ${Build.DISPLAY}\n查看时刻 UTC ${java.time.Instant.now()}\n重新打开此页才更新。"
                val snapshot = journal.snapshot()
                Pair(listOf(version) + DiagnosticFeedback.quickPages(snapshot, BuildConfig.DEVICE_ROLE),
                    listOf(version) + DiagnosticFeedback.pages(snapshot, BuildConfig.DEVICE_ROLE))
            } catch (e: Exception) {
                val failure = listOf("读取诊断失败，请拍照\n${e.javaClass.simpleName}\n${e.message}")
                Pair(failure, failure)
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && dialog.isShowing) {
                    quick = loaded.first; full = loaded.second
                    pages = quick; showingFull = false; mode.text = "完整详情"; index = 0; render()
                }
            }
            worker.shutdown()
        }
    }
}
