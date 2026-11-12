package com.rawwave.shell

import android.content.Context

/**
 * User-chosen inactivity limit for the phone home.
 *
 * Zero means "do not exit by itself". The value is stored in whole minutes so the picker can be
 * exact to the hour and minute without a second unit. Any touch, data reception, transfer or
 * export restarts the window; a busy transfer is never interrupted by it.
 */
class PhoneExitSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoExitMinutes: Int
        get() = prefs.getInt(KEY_MINUTES, DEFAULT_MINUTES).coerceIn(0, MAX_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_MINUTES, value.coerceIn(0, MAX_MINUTES)).apply()
        }

    fun label(): String = labelFor(autoExitMinutes)

    companion object {
        const val DEFAULT_MINUTES = 30
        const val MAX_MINUTES = 24 * 60 - 1
        private const val PREFS_NAME = "phone-exit"
        private const val KEY_MINUTES = "autoExitMinutes"

        /** "不自动退出" or "1 小时 30 分钟"; shared by the settings page and tests. */
        fun labelFor(minutes: Int): String {
            val safe = minutes.coerceIn(0, MAX_MINUTES)
            if (safe == 0) return "不自动退出"
            val hours = safe / 60
            val mins = safe % 60
            return when {
                hours > 0 && mins > 0 -> "$hours 小时 $mins 分钟"
                hours > 0 -> "$hours 小时"
                else -> "$mins 分钟"
            }
        }
    }
}
