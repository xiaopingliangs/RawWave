package com.rawwave.mobile.samsunghealth.export

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the user asked for data to be selected.
 *
 * There are exactly two exports and no data-type selection: the Samsung Health Data SDK already
 * decides what is readable, so RawWave does not add a second filter on top of it.
 */
enum class ExportMode {
    /** Logical dates, converted through the user's daily boundary into an exact instant window. */
    DATE_RANGE,

    /** Whatever the SDK can currently give us, with no time filter where the SDK allows none. */
    ALL_AVAILABLE_HISTORY,
}

/**
 * How a requested local wall-clock time was resolved against a real time zone.
 *
 * Recorded rather than assumed, because a daylight-saving transition can make a requested local time
 * either nonexistent or ambiguous, and the user is entitled to see which happened.
 */
enum class LocalTimeResolution {
    /** Exactly one valid offset existed for that local time. */
    EXACT,

    /** That local time does not exist (a DST gap). It was moved forward by the gap length. */
    GAP_SHIFTED_FORWARD,

    /** That local time happened twice (a DST overlap). The earlier offset was chosen. */
    OVERLAP_EARLIER_OFFSET,

    /** No boundary applies: this export is not bounded by a time window at all. */
    UNBOUNDED,
}

/**
 * Where a record stands relative to the resolved query window.
 *
 * This is RawWave's annotation, never a modification of the record: a sleep that crosses the
 * window's start is exported WHOLE and labelled, not clipped.
 */
enum class WindowRelation {
    /** The whole record lies inside `[start, end)`. */
    INSIDE_WINDOW,

    /** The record starts before the window and ends inside it. Exported whole. */
    OVERLAPS_WINDOW_START,

    /** The record starts inside the window and ends after it. Exported whole. */
    OVERLAPS_WINDOW_END,

    /** The record covers the entire window. Exported whole. */
    COVERS_WINDOW,

    /** No part of the record is inside the window. */
    OUTSIDE_WINDOW,

    /** The window is unbounded (all available history), so no relation can be stated. */
    NOT_APPLICABLE,
}

/** How a record earned its place in the export. */
enum class IncludedBy {
    /** A point whose source timestamp fell inside `[start, end)`. */
    POINT_IN_WINDOW,

    /** An interval or session that intersected the window; carried whole, never clipped. */
    INTERVAL_OVERLAP,

    /** Associated data belonging to a parent record that was itself included. */
    ASSOCIATED_OF_INCLUDED_PARENT,

    /** The type carries no time filter at all (for example the user profile). */
    NO_TIME_FILTER,

    /** All-available-history mode: nothing was filtered out by a window. */
    ALL_AVAILABLE_HISTORY,

    /** An aggregate result computed by Samsung Health over the resolved window. */
    AGGREGATE_OVER_WINDOW,

    /**
     * The type's Samsung-side filter is coarser than the resolved window (a local-date-only type
     * cannot express an 08:00 boundary). Samsung selected the records and RawWave deliberately did
     * NOT re-filter them, because dropping rows Samsung returned would lose real data.
     */
    SAMSUNG_COARSER_TIME_FILTER,

    /** Nested inside an included parent record rather than read on its own. */
    NESTED_IN_INCLUDED_PARENT,
}

/** The logical dates the user chose, exactly as they were requested. */
@Serializable
data class RequestedLogicalRange(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("dates_are") val datesAre: String = "USER_LOGICAL_DATES",
)

/** The serializable snapshot of everything RawWave did to turn logical dates into instants. */
@Serializable
data class ResolvedQueryWindow(
    @SerialName("export_mode") val exportMode: ExportMode,
    @SerialName("unbounded_start") val unboundedStart: Boolean,
    @SerialName("unbounded_end") val unboundedEnd: Boolean,
    @SerialName("timezone_id") val timezoneId: String,
    @SerialName("daily_boundary_local_time") val dailyBoundaryLocalTime: String,
    @SerialName("requested_logical_range") val requestedLogicalRange: RequestedLogicalRange? = null,
    @SerialName("requested_start_local") val requestedStartLocal: String? = null,
    @SerialName("requested_end_exclusive_local") val requestedEndExclusiveLocal: String? = null,
    @SerialName("start_local") val startLocal: String? = null,
    @SerialName("start_offset") val startOffset: String? = null,
    @SerialName("start_instant") val startInstant: String? = null,
    @SerialName("start_epoch_ms") val startEpochMs: Long? = null,
    @SerialName("end_exclusive_local") val endExclusiveLocal: String? = null,
    @SerialName("end_exclusive_offset") val endExclusiveOffset: String? = null,
    @SerialName("end_exclusive_instant") val endExclusiveInstant: String? = null,
    @SerialName("end_exclusive_epoch_ms") val endExclusiveEpochMs: Long? = null,
    @SerialName("start_boundary_resolution") val startBoundaryResolution: LocalTimeResolution,
    @SerialName("end_boundary_resolution") val endBoundaryResolution: LocalTimeResolution,
    @SerialName("range_semantics") val rangeSemantics: String = RANGE_SEMANTICS,
    @SerialName("source_timestamps_modified") val sourceTimestampsModified: Boolean = false,
) {
    companion object {
        /** The one range convention used everywhere in this export. No 23:59:59 anywhere. */
        const val RANGE_SEMANTICS: String = "[start_inclusive, end_exclusive)"
    }
}

/**
 * The resolved window, with real `java.time` values for querying and a serializable snapshot for the
 * archive.
 *
 * All time-zone work goes through `java.time`'s own `ZoneRules`. RawWave does not implement any
 * offset arithmetic of its own, so a UTC+8 assumption can never be baked in and a DST transition
 * can never be silently approximated.
 */
data class ResolvedExportWindow(
    val mode: ExportMode,
    val zoneId: ZoneId,
    val dailyBoundary: LocalTime,
    val startInstant: Instant?,
    val endExclusiveInstant: Instant?,
    val startLocal: LocalDateTime?,
    val endExclusiveLocal: LocalDateTime?,
    val startResolution: LocalTimeResolution,
    val endResolution: LocalTimeResolution,
    val requestedStartDate: LocalDate?,
    val requestedEndDate: LocalDate?,
    val requestedStartLocal: LocalDateTime?,
    val requestedEndExclusiveLocal: LocalDateTime?,
) {

    val isUnbounded: Boolean get() = startInstant == null || endExclusiveInstant == null

    /**
     * A wider query window used only to FIND interval records that overlap the logical window.
     *
     * Samsung's server-side time filter is not a guarantee that a session starting before the
     * window but ending inside it will be returned. The padded window is never used for inclusion:
     * [relationOf]/[overlapsInterval] still use this logical window, so records outside it are
     * discarded and records that overlap are kept whole.
     */
    fun paddedQueryWindow(padding: java.time.Duration): ResolvedExportWindow = copy(
        startInstant = startInstant?.minus(padding),
        endExclusiveInstant = endExclusiveInstant?.plus(padding),
        startLocal = startLocal?.minus(padding),
        endExclusiveLocal = endExclusiveLocal?.plus(padding),
    )

    /** True when a point's own timestamp puts it inside `[start, end)`. */
    fun containsPoint(instant: Instant): Boolean {
        val start = startInstant ?: return true
        val end = endExclusiveInstant ?: return true
        return !instant.isBefore(start) && instant.isBefore(end)
    }

    /**
     * True when an interval intersects the window.
     *
     * `start < windowEnd && end > windowStart` - touching the window boundary exactly is NOT an
     * intersection, which is what makes the window properly half-open.
     */
    fun overlapsInterval(recordStart: Instant, recordEnd: Instant): Boolean {
        val start = startInstant ?: return true
        val end = endExclusiveInstant ?: return true
        return recordStart.isBefore(end) && recordEnd.isAfter(start)
    }

    /** Where the record stands relative to the window. Never used to clip anything. */
    fun relationOf(recordStart: Instant, recordEnd: Instant): WindowRelation {
        val start = startInstant
        val end = endExclusiveInstant
        if (start == null || end == null) return WindowRelation.NOT_APPLICABLE

        // No intersection at all: the record is either entirely before the window or entirely
        // after it. This has to be checked first, or an interval that sits wholly after the window
        // would be mislabelled as merely overlapping its end.
        if (!overlapsInterval(recordStart, recordEnd)) return WindowRelation.OUTSIDE_WINDOW

        val startsInside = !recordStart.isBefore(start)
        val endsInside = !recordEnd.isAfter(end)

        return when {
            startsInside && endsInside -> WindowRelation.INSIDE_WINDOW
            !startsInside && !endsInside -> WindowRelation.COVERS_WINDOW
            !startsInside -> WindowRelation.OVERLAPS_WINDOW_START
            else -> WindowRelation.OVERLAPS_WINDOW_END
        }
    }

    /**
     * Which logical day a local reading belongs to, given the user's boundary.
     *
     * A reading exactly at the boundary belongs to the day that is starting, which is what makes
     * "the 1st of September means 08:00 on the 1st" true rather than off by one.
     */
    fun logicalDayKeyOf(local: LocalDateTime): LocalDate =
        if (!local.toLocalTime().isBefore(dailyBoundary)) local.toLocalDate() else local.toLocalDate().minusDays(1)

    /**
     * Every logical day an interval touches.
     *
     * A record that spans several logical days gets all of them, rather than being flattened onto
     * one invented "official date": the source timestamps are the fact, and this is only a label.
     */
    fun logicalDaysTouched(start: LocalDateTime, endInclusive: LocalDateTime): List<LocalDate> {
        // The interval is half-open, so a record ending exactly at the boundary has not touched the
        // next day. Stepping back by one nanosecond keeps that boundary case honest.
        val effectiveEnd = if (endInclusive.toLocalTime() == dailyBoundary) {
            endInclusive.minusNanos(1)
        } else {
            endInclusive
        }
        val first = logicalDayKeyOf(start)
        val last = logicalDayKeyOf(effectiveEnd)
        if (last.isBefore(first)) return listOf(first)
        val days = ArrayList<LocalDate>()
        var cursor = first
        var guard = 0
        while (!cursor.isAfter(last) && guard < MAX_LOGICAL_DAYS) {
            days += cursor
            cursor = cursor.plusDays(1)
            guard++
        }
        return days
    }

    /** The serializable snapshot that goes into `manifest.json` and `export_semantics.json`. */
    fun toDocument(): ResolvedQueryWindow = ResolvedQueryWindow(
        exportMode = mode,
        unboundedStart = startInstant == null,
        unboundedEnd = endExclusiveInstant == null,
        timezoneId = zoneId.id,
        dailyBoundaryLocalTime = dailyBoundary.toString(),
        requestedLogicalRange = if (requestedStartDate != null && requestedEndDate != null) {
            RequestedLogicalRange(requestedStartDate.toString(), requestedEndDate.toString())
        } else {
            null
        },
        requestedStartLocal = requestedStartLocal?.toString(),
        requestedEndExclusiveLocal = requestedEndExclusiveLocal?.toString(),
        startLocal = startLocal?.toString(),
        startOffset = startInstant?.let { zoneId.rules.getOffset(it).id },
        startInstant = startInstant?.toString(),
        startEpochMs = startInstant?.toEpochMilli(),
        endExclusiveLocal = endExclusiveLocal?.toString(),
        endExclusiveOffset = endExclusiveInstant?.let { zoneId.rules.getOffset(it).id },
        endExclusiveInstant = endExclusiveInstant?.toString(),
        endExclusiveEpochMs = endExclusiveInstant?.toEpochMilli(),
        startBoundaryResolution = startResolution,
        endBoundaryResolution = endResolution,
    )

    companion object {
        /** A guard so a pathological interval cannot enumerate millions of days into the archive. */
        const val MAX_LOGICAL_DAYS: Int = 4000
    }
}

/**
 * Turns logical dates plus a daily boundary into an exact instant window.
 *
 * The user picks days; Samsung Health stores instants. This is the only place the two are bridged,
 * and the bridge is recorded in the archive so an old ZIP can always be read back with the rule
 * that was in force when it was written - never with today's setting.
 */
object ExportWindowResolver {

    /**
     * @param startDate logical start day, inclusive.
     * @param endDate logical end day, inclusive. The window ends at the START of the day after it.
     */
    fun resolve(
        mode: ExportMode,
        boundary: LocalTime,
        zoneId: ZoneId,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): ResolvedExportWindow {
        if (mode == ExportMode.ALL_AVAILABLE_HISTORY) {
            return ResolvedExportWindow(
                mode = mode,
                zoneId = zoneId,
                dailyBoundary = boundary,
                startInstant = null,
                endExclusiveInstant = null,
                startLocal = null,
                endExclusiveLocal = null,
                startResolution = LocalTimeResolution.UNBOUNDED,
                endResolution = LocalTimeResolution.UNBOUNDED,
                requestedStartDate = null,
                requestedEndDate = null,
                requestedStartLocal = null,
                requestedEndExclusiveLocal = null,
            )
        }

        requireNotNull(startDate) { "DATE_RANGE export requires a logical start date" }
        requireNotNull(endDate) { "DATE_RANGE export requires a logical end date" }
        require(!endDate.isBefore(startDate)) {
            "the logical end date ($endDate) is before the logical start date ($startDate)"
        }

        val requestedStart = startDate.atTime(boundary)
        // Half-open by construction: the end is the boundary of the day AFTER the last chosen day.
        // No 23:59:59 and no 23:59:59.999 is ever assembled.
        val requestedEnd = endDate.plusDays(1).atTime(boundary)

        val (startZdt, startResolution) = resolveLocal(requestedStart, zoneId)
        val (endZdt, endResolution) = resolveLocal(requestedEnd, zoneId)

        return ResolvedExportWindow(
            mode = mode,
            zoneId = zoneId,
            dailyBoundary = boundary,
            startInstant = startZdt.toInstant(),
            endExclusiveInstant = endZdt.toInstant(),
            startLocal = startZdt.toLocalDateTime(),
            endExclusiveLocal = endZdt.toLocalDateTime(),
            startResolution = startResolution,
            endResolution = endResolution,
            requestedStartDate = startDate,
            requestedEndDate = endDate,
            requestedStartLocal = requestedStart,
            requestedEndExclusiveLocal = requestedEnd,
        )
    }

    /**
     * Resolves one local wall-clock time in a real zone.
     *
     * `LocalDateTime.atZone` already implements the correct `java.time` behaviour: a gap shifts the
     * time forward by the length of the gap, and an overlap selects the earlier of the two offsets.
     * The resolution kind is reported alongside so the archive says what actually happened.
     */
    fun resolveLocal(local: LocalDateTime, zoneId: ZoneId): Pair<ZonedDateTime, LocalTimeResolution> {
        val validOffsets = zoneId.rules.getValidOffsets(local)
        val resolution = when {
            validOffsets.isEmpty() -> LocalTimeResolution.GAP_SHIFTED_FORWARD
            validOffsets.size > 1 -> LocalTimeResolution.OVERLAP_EARLIER_OFFSET
            else -> LocalTimeResolution.EXACT
        }
        return local.atZone(zoneId) to resolution
    }
}
