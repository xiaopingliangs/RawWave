package com.rawwave.mobile.samsunghealth

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwGradientTitle
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwTheme
import com.rawwave.designsystem.RwTopBar
import com.rawwave.mobile.samsunghealth.export.DailyBoundarySettings
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The dedicated "my day starts at" page.
 *
 * The explanation is long enough to scroll, but save/cancel sit outside the ScrollView so they
 * are always reachable. Back, cancel and closing the page never write a new boundary.
 */
class DailyBoundaryActivity : ComponentActivity() {

    private lateinit var settings: DailyBoundarySettings
    private var selected: LocalTime = DailyBoundarySettings.DEFAULT_BOUNDARY
    private lateinit var timeValue: TextView
    private lateinit var explanationView: TextView

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DailyBoundarySettings(this)
        selected = settings.dailyBoundaryOrNull() ?: DailyBoundarySettings.DEFAULT_BOUNDARY
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
    }

    private fun buildUi(): View {
        val colors = RwTheme.colors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(dp(20), dp(8) + bars.top, dp(20), dp(8) + bars.bottom)
            insets
        }

        root.addView(RwTopBar(this).apply {
            setTitle("我的一天从几点开始")
            setOnBackClickListener { finish() }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }

        val timeCard = RwCard(this)
        timeCard.addView(RwBodyText(this).apply {
            text = "当前设置"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colors.onSurfaceVariant)
        })
        timeValue = RwGradientTitle(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        timeCard.addView(timeValue)
        timeCard.addView(RwBodyText(this).apply {
            text = "时区：${zoneName()}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colors.onSurfaceVariant)
            gravity = Gravity.CENTER
        })
        timeCard.addView(RwSecondaryButton(this).apply {
            text = "更改时间"
            setOnClickListener { pickTime() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        body.addView(timeCard)

        val explanation = RwCard(this)
        explanationView = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colors.onSurface)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        explanation.addView(explanationView)
        body.addView(explanation, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        scroll.addView(body, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(RwPrimaryButton(this).apply {
            text = "保存"
            setOnClickListener {
                settings.setDailyBoundary(selected)
                finish()
            }
        })
        footer.addView(RwSecondaryButton(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        root.addView(footer)

        updateTime()
        return root
    }

    private fun updateTime() {
        timeValue.text = selected.format(TIME_FORMAT)
        explanationView.text = explanationText()
    }

    private fun explanationText(): String {
        val day = java.time.LocalDate.now(zone)
        val start = day.atTime(selected).format(EXAMPLE_FORMAT)
        val end = day.plusDays(1).atTime(selected).format(EXAMPLE_FORMAT)
        return """
            这个设置不会修改 Samsung Health 原始记录的时间。

            它只决定 RawWave 在你选择日期时，怎样把「某一天」转换成实际查询时间。

            例如设置为 ${selected.format(TIME_FORMAT)}：
            ${day.format(DATE_FORMAT)} 表示 $start
            到 $end（不包含）

            睡眠、运动等跨越这个边界的完整记录不会被人为切断，整条记录会完整导出，并在文件里标明它与查询窗口的关系。

            导出结束时间是「不含」的：结束日期当天整天都包含在内，不会使用 23:59:59 这类人为拼接的时间。

            时间用导出时手机当前时区解析（当前：${zoneName()}）。夏令时造成的「不存在」或「重复」的本地时间由系统时区规则处理，并把实际解析结果写进导出文件。

            导出的文件会同时保存：
            · Samsung Health 原始时间
            · 本次 RawWave 使用的日期边界规则（快照）
        """.trimIndent()
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            if (RwTheme.isDark(this)) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
            { _, hour, minute ->
                selected = LocalTime.of(hour, minute)
                updateTime()
                // Rebuild only the explanation's example line by refreshing the page text on
                // the next open; the time card and saved value are already accurate.
            },
            selected.hour,
            selected.minute,
            true,
        ).show()
    }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)

    private fun zoneName(): String = zone.getDisplayName(
        java.time.format.TextStyle.FULL,
        java.util.Locale.getDefault(),
    )

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日")
        val EXAMPLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")
    }
}
