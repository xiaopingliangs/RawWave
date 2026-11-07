package com.rawwave.mobile.samsunghealth.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class OfficialExportWindowTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val boundary = LocalTime.of(8, 0)

    @Test
    fun octoberLogicalMonthIsEightToEightAndExclusiveAtTheEnd() {
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            boundary,
            shanghai,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
        )

        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 0), window.startLocal)
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), window.endExclusiveLocal)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), window.startInstant)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), window.endExclusiveInstant)
        assertEquals(31 * 24 * 60 * 60L, window.endExclusiveInstant!!.epochSecond - window.startInstant!!.epochSecond)
    }

    @Test
    fun aReadingExactlyAtTheBoundaryBelongsToTheDayThatStarts() {
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            boundary,
            shanghai,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 1),
        )

        assertEquals(
            LocalDate.of(2026, 9, 29),
            window.logicalDayKeyOf(LocalDateTime.of(2026, 9, 30, 7, 59, 59)),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1),
            window.logicalDayKeyOf(LocalDateTime.of(2026, 10, 1, 8, 0)),
        )
    }

    @Test
    fun sleepThatCrossesTheBoundaryIsKeptWholeAndMarked() {
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            boundary,
            shanghai,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 1),
        )

        val startsBefore = Instant.parse("2026-09-30T15:00:00Z") // 23:00 local
        val endsInside = Instant.parse("2026-10-01T01:00:00Z")   // 09:00 local
        assertTrue(window.overlapsInterval(startsBefore, endsInside))
        assertEquals(WindowRelation.OVERLAPS_WINDOW_START, window.relationOf(startsBefore, endsInside))

        val startsInside = Instant.parse("2026-10-01T13:00:00Z") // 21:00 local
        val endsAfter = Instant.parse("2026-10-02T01:00:00Z")    // next day 09:00 local
        assertTrue(window.overlapsInterval(startsInside, endsAfter))
        assertEquals(WindowRelation.OVERLAPS_WINDOW_END, window.relationOf(startsInside, endsAfter))

        assertFalse(window.overlapsInterval(
            Instant.parse("2026-09-30T14:00:00Z"),
            Instant.parse("2026-09-30T23:00:00Z"),
        ))
    }

    @Test
    fun aCrossBoundarySleepTouchesBothLogicalDays() {
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            boundary,
            shanghai,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
        )

        val days = window.logicalDaysTouched(
            LocalDateTime.of(2026, 10, 1, 23, 0),
            LocalDateTime.of(2026, 10, 2, 9, 0),
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), days)
    }

    @Test
    fun paddedQueryWindowWidensTheQueryButNotInclusion() {
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            boundary,
            shanghai,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 1),
        )
        val query = window.paddedQueryWindow(java.time.Duration.ofHours(48))

        assertEquals(window.startInstant!!.minus(java.time.Duration.ofHours(48)), query.startInstant)
        assertEquals(window.endExclusiveInstant!!.plus(java.time.Duration.ofHours(48)), query.endExclusiveInstant)
        assertTrue(window.containsPoint(window.startInstant!!))
        assertFalse(window.containsPoint(window.startInstant!!.minusSeconds(1)))
        assertTrue(query.containsPoint(window.startInstant!!.minusSeconds(1)))
    }

    @Test
    fun daylightSavingGapIsReportedNotGuessed() {
        val newYork = ZoneId.of("America/New_York")
        val window = ExportWindowResolver.resolve(
            ExportMode.DATE_RANGE,
            LocalTime.of(2, 30),
            newYork,
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 3, 8),
        )
        assertEquals(LocalTimeResolution.GAP_SHIFTED_FORWARD, window.startResolution)
    }
}
