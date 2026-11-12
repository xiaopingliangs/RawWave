package com.rawwave.mobile.activity

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDateSelectionTest {

    private val start = LocalDate.of(2026, 11, 11)
    private val end = LocalDate.of(2026, 11, 13)

    @Test fun rangeIncludesBothEndpoints() {
        assertTrue(ActivityDateSelection.contains(start, start, end))
        assertTrue(ActivityDateSelection.contains(LocalDate.of(2026, 11, 12), start, end))
        assertTrue(ActivityDateSelection.contains(end, start, end))
    }

    @Test fun rangeExcludesDaysOutsideIt() {
        assertFalse(ActivityDateSelection.contains(start.minusDays(1), start, end))
        assertFalse(ActivityDateSelection.contains(end.plusDays(1), start, end))
    }

    @Test fun singleDayRangeContainsOnlyThatDay() {
        assertTrue(ActivityDateSelection.contains(start, start, start))
        assertFalse(ActivityDateSelection.contains(start.plusDays(1), start, start))
    }
}
