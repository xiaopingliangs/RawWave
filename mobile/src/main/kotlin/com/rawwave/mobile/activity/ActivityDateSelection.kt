package com.rawwave.mobile.activity

import java.time.LocalDate

/**
 * Inclusive activity-date selection. A chosen day range includes both the first and last day,
 * matching what the two date buttons show on screen.
 */
internal object ActivityDateSelection {
    fun contains(date: LocalDate, start: LocalDate, end: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(end)
}
