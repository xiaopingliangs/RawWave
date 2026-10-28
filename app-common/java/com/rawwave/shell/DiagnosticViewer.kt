package com.rawwave.shell

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.*
import com.rawwave.core.DiagnosticFeedback
import java.util.concurrent.Executors

/** Explicit snapshot only; no polling, service, radio, wake lock or lifecycle handoff. */
object DiagnosticViewer {
    fun show(activity: Activity, app: ProbeApplication) {
        val dialog = Dialog(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (activity.resources.displayMetrics.widthPixels * .16f).toInt()
            val vertical = if (activity.resources.configuration.isScreenRound) inset else (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, vertical, inset, vertical)
            setBackgroundColor(Color.BLACK)
        }
        val text = TextView(activity).apply { textSize = 14f; setTextColor(Color.WHITE); setTextIsSelectable(true) }
        // Scroll controls with the evidence so fixed buttons cannot consume the watch viewport.
        text.minimumHeight = (100 * activity.resources.displayMetrics.density).toInt()
        body.addView(text, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(activity).apply { setBackgroundColor(Color.BLACK); addView(body) }
        var pages = listOf("正在读取本机日志快照…")
        var quick = pages
        var full = pages
        var showingFull = false
        var index = 0
        fun render() { text.text = "${index + 1}/${pages.size}\n${pages[index]}"; scroll.scrollTo(0, 0) }
        fun button(parent: LinearLayout, title: String, action: () -> Unit): Button {
            return Button(activity).apply { this.text = title; textSize = 11f; setOnClickListener { action() } }.also {
                parent.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val navigation = LinearLayout(activity)
        button(navigation,"上页") { if (index > 0) { index--; render() } }
        button(navigation,"下页") { if (index + 1 < pages.size) { index++; render() } }
        body.addView(navigation)
        val actions = LinearLayout(activity)
        lateinit var mode: Button
        mode = button(actions,"完整详情") {
            showingFull = !showingFull
            pages = if (showingFull) full else quick
            mode.text = if (showingFull) "快速5页" else "完整详情"
            index = 0; render()
        }
        button(actions,"关闭") { dialog.dismiss() }
        body.addView(actions)
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.BLACK; navigationBarColor = Color.BLACK
            decorView.systemUiVisibility = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        render()
        val worker = Executors.newSingleThreadExecutor()
        dialog.setOnDismissListener { worker.shutdownNow() }
        worker.execute {
            val loaded = try {
                val journal = app.journal ?: error(app.journalFailure ?: "日志未初始化")
                val version = "原波诊断 · 只读快照\n${BuildConfig.DEVICE_ROLE}\n${BuildConfig.VERSION_NAME}\n${BuildConfig.BUILD_RUN}\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n系统 ${Build.DISPLAY}\n查看时刻 UTC ${java.time.Instant.now()}\n重新打开此页才更新。"
                val snapshot = journal.snapshot()
                Pair(listOf(version) + DiagnosticFeedback.quickPages(snapshot,BuildConfig.DEVICE_ROLE),
                    listOf(version) + DiagnosticFeedback.pages(snapshot, BuildConfig.DEVICE_ROLE))
            } catch (e: Exception) {
                val failure = listOf("读取诊断失败，请拍照\n${e.javaClass.simpleName}\n${e.message}")
                Pair(failure,failure)
            }
            activity.runOnUiThread { if (!activity.isFinishing && dialog.isShowing) {
                quick = loaded.first; full = loaded.second
                pages = quick; showingFull=false; mode.text="完整详情"; index = 0; render()
            } }
            worker.shutdown()
        }
    }
}
