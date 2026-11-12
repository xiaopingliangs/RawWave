package com.rawwave.shell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwCard
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwListItemButton
import com.rawwave.designsystem.RwScreen
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwSectionHeader
import com.rawwave.designsystem.RwTheme
import com.rawwave.designsystem.RwTopBar

/**
 * Phone "更多": the two settings that are not part of the daily receive/export flow.
 *
 * Keeping them here removes the background-permission block from the home without hiding it:
 * a user who needs it still has a named, findable page.
 */
class PhoneMoreActivity : ComponentActivity() {

    private lateinit var batteryState: TextView
    private lateinit var batteryButton: TextView
    private lateinit var exitRow: RwListItemButton
    private lateinit var exitHint: TextView
    private lateinit var settings: PhoneExitSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = PhoneExitSettings(this)
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

    override fun onResume() {
        super.onResume()
        updateBatteryState()
        updateExitRow()
    }

    private fun buildUi(): View {
        val colors = RwTheme.colors(this)
        val screen = RwScreen(this)
        val content = screen.content

        content.addView(RwTopBar(this).apply {
            setTitle("更多")
            setOnBackClickListener { finish() }
        })

        val background = RwCard(this)
        background.addView(RwSectionHeader(this).apply {
            text = "后台运行"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        batteryState = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colors.onSurface)
            setPadding(0, dp(2), 0, 0)
        }
        background.addView(batteryState)
        batteryButton = RwSecondaryButton(this).apply {
            text = "允许后台一直运行"
            setOnClickListener { requestBatteryExemption() }
        }
        background.addView(batteryButton, buttonParams(10))
        content.addView(background, cardParams(16))

        val exit = RwCard(this)
        exit.addView(RwSectionHeader(this).apply {
            text = "自动退出"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        exitRow = RwListItemButton(this).apply {
            setOnClickListener { showExitDurationPicker() }
        }
        exit.addView(exitRow, buttonParams(10))
        exitHint = RwBodyText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
        }
        exit.addView(exitHint)
        content.addView(exit, cardParams(12))

        content.addView(RwSecondaryButton(this).apply {
            text = "关于与版本"
            setOnClickListener {
                RwDialogs.message(
                    this@PhoneMoreActivity,
                    "关于 RawWave",
                    "版本 ${BuildConfig.VERSION_NAME}\n\n" +
                        "RawWave 只读取、保存和导出数据，不会写回 Samsung Health，也不会修改手表原始记录。",
                    confirmLabel = "知道了",
                )
            }
        }, buttonParams(18))

        // Explanations live at the bottom of the second-level page, next to the way out.
        // The phone home stays a list of buttons plus the live receive status.
        val notes = RwCard(this)
        notes.addView(RwSectionHeader(this).apply {
            text = "说明"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(2))
        })
        notes.addView(
            RwBodyText(this).apply {
                text = "手机只负责接收手表数据并导出，采集在手表上进行。\n\n" +
                    "原始活动数据：只读导出，不会修改手表里的记录。\n" +
                    "Samsung Health 官方数据：只读，不会写回 Samsung Health。\n" +
                    "问题报告：与活动原始数据分开保存、分开传输。\n\n" +
                    "自动退出只在首页无操作、无数据接收、无导出时计时；正在接收或导出时不会退出。\n" +
                    "允许后台运行只影响熄屏时能否继续接收，不会让 RawWave 永久驻留。"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(colors.onSurfaceVariant)
                setLineSpacing(dp(2).toFloat(), 1f)
            },
        )
        content.addView(notes, cardParams(12))

        content.addView(RwSecondaryButton(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, buttonParams(12))

        return screen
    }

    private fun updateExitRow() {
        val label = settings.label()
        exitRow.text = "软件自动退出时间 · $label"
        if (::exitHint.isInitialized) {
            exitHint.text = if (settings.autoExitMinutes <= 0) {
                "当前：不会自动退出。"
            } else {
                "当前：无操作、无数据接收、无导出时，${label}后退出。"
            }
        }
    }

    private fun showExitDurationPicker() {
        val total = settings.autoExitMinutes
        RwDialogs.duration(
            this,
            "软件自动退出时间",
            "按小时、分钟设置。0 小时 0 分钟表示不自动退出。",
            hours = total / 60,
            minutes = total % 60,
        ) { hours, minutes ->
            val totalMinutes = hours * 60 + minutes
            settings.autoExitMinutes = totalMinutes
            updateExitRow()
            // Keep a persisted trace so a later problem report can confirm what was chosen.
            try {
                (application as? ProbeApplication)?.journal?.event(
                    "PHONE_AUTO_EXIT_SETTING_SET",
                    mapOf("minutes" to totalMinutes, "label" to settings.label()),
                )
            } catch (_: Exception) {}
        }
    }

    private fun updateBatteryState() {
        val exempt = isBatteryExempt()
        batteryState.text = if (exempt) {
            "已允许后台一直运行。熄屏后仍可完成本轮数据接收。"
        } else {
            "未允许。手机熄屏后系统可能暂停接收；大包同步需要更长时间。"
        }
        // Keep the action visible after it succeeds: tapping it again explains that it is
        // already allowed instead of removing the setting from the page.
        batteryButton.visibility = View.VISIBLE
    }

    private fun requestBatteryExemption() {
        if (isBatteryExempt()) {
            RwDialogs.message(this, "后台运行", "原波已经允许不受电池优化限制。")
            return
        }
        RwDialogs.confirm(
            this,
            "允许后台一直运行",
            "为保证手机熄屏时仍能接收手表的大活动包，请允许原波不受电池优化限制。\n\n" +
                "RawWave 只在同步期间保活，完成后会按“自动退出时间”退出。",
            confirmLabel = "去设置",
            cancelLabel = "稍后",
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Exception) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: Exception) {
                    RwDialogs.message(this, "后台运行", "当前设备没有提供后台运行设置入口，请在系统应用设置中查看原波。")
                }
            }
        }
    }

    private fun isBatteryExempt(): Boolean = try {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) {
        false
    }

    private fun buttonParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)
}
