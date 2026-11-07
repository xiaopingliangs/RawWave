package com.rawwave.mobile.samsunghealth.export

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

/**
 * The user's "my day starts at" setting, and the last date range they used.
 *
 * Stored in `SharedPreferences` rather than DataStore on purpose: this is one `LocalTime` and two
 * dates, and the whole feature must not pull a new dependency into `:mobile` for that.
 *
 * The setting has exactly one job - converting a chosen logical date into a query window. It never
 * touches a Samsung Health timestamp. Because a user can change it later, every export also stores a
 * snapshot of the value that was in force, so an old archive is always read back with its own rule
 * rather than with today's.
 */
class DailyBoundarySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The confirmed daily boundary, or `null` when the user has never set one.
     *
     * `null` is not defaulted to midnight or to 08:00: RawWave must not silently assume the user's
     * sleeping pattern. The screen requires one confirmation before the first export.
     */
    fun dailyBoundaryOrNull(): LocalTime? =
        prefs.getString(KEY_BOUNDARY, null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    fun dailyBoundaryOrDefault(): LocalTime = dailyBoundaryOrNull() ?: DEFAULT_BOUNDARY

    val hasConfirmedBoundary: Boolean get() = dailyBoundaryOrNull() != null

    fun setDailyBoundary(time: LocalTime) {
        prefs.edit().putString(KEY_BOUNDARY, time.toString()).apply()
    }

    fun lastStartDateOrNull(): LocalDate? =
        prefs.getString(KEY_START_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun lastEndDateOrNull(): LocalDate? =
        prefs.getString(KEY_END_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setLastRange(start: LocalDate, end: LocalDate) {
        prefs.edit()
            .putString(KEY_START_DATE, start.toString())
            .putString(KEY_END_DATE, end.toString())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "rawwave_samsung_health_export"
        private const val KEY_BOUNDARY = "daily_boundary_local_time"
        private const val KEY_START_DATE = "last_start_date"
        private const val KEY_END_DATE = "last_end_date"

        /** Only a starting suggestion for the picker. Never applied without the user confirming it. */
        val DEFAULT_BOUNDARY: LocalTime = LocalTime.of(8, 0)
    }
}
