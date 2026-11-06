package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StepCountTest {
    @Test public void rowKeepsPlatformCumulativeFloatAndMetadata() {
        Map<String,Object> row = StepCount.row(1234.5f, 987_654_321_000L, 3,
            111L, 222L, 7, 0, 1);
        assertEquals(987_654_321_000L, row.get("sensorTimestampNs"));
        assertEquals(987_654L, row.get("sdkTimestamp"));
        assertEquals("Android SensorManager TYPE_STEP_COUNTER", row.get("source"));
        assertEquals("PLATFORM_CUMULATIVE_COUNTER_SINCE_BOOT_NOT_RAW_MOTION", row.get("stepCountKind"));
        assertEquals(1234.5f, row.get("STEP_COUNT_TOTAL"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(7L, row.get("captureCallbackSequence"));
        assertThrows(UnsupportedOperationException.class, () -> row.put("x", 1));
    }

    @Test public void nonFinitePlatformValueIsExplicitlyEncoded() {
        Map<String,Object> row = StepCount.row(Float.NaN, 1L, 0, 1L, 2L, 1, 0, 1);
        assertEquals("NaN", row.get("STEP_COUNT_TOTAL"));
        assertEquals("NON_FINITE_AS_STRING", row.get("STEP_COUNT_TOTAL_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StepCount.row(1f, 1L, 0, 1L, 2L, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> StepCount.row(1f, 1L, 0, 1L, 2L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> StepCount.row(1f, 1L, 0, 1L, 2L, 1, 0, 0));
    }

    @Test public void qualityTracksIncrementalCounterAsPrimaryValue() {
        var tracker = new StreamQualityTracker(SensorCatalog.STEP_COUNT);
        tracker.observe(List.of(StepCount.row(100f, 1_000_000_000L, 3, 1L, 2L, 1, 0, 1)));
        tracker.observe(List.of(StepCount.row(110f, 2_000_000_000L, 3, 3L, 4L, 2, 0, 1)));
        assertEquals(2L, tracker.summary().get("valueRows"));
        assertEquals(1, ((Map<?,?>)tracker.summary().get("statusCounts")).size());
        assertEquals(0L, tracker.summary().get("largeCallbackGapCount"));
    }

    @Test public void stationaryIntervalBelowStepThresholdIsNotALargeGap() {
        var tracker = new StreamQualityTracker(SensorCatalog.STEP_COUNT);
        tracker.observe(List.of(StepCount.row(100f, 1_000_000_000L, 3, 1L, 2L, 1, 0, 1)));
        tracker.observe(List.of(StepCount.row(101f, 31_000_000_000L, 3, 3L, 4L, 2, 0, 1)));
        assertEquals(0L, tracker.summary().get("largeCallbackGapCount"));
        tracker.observe(List.of(StepCount.row(102f, 120_000_000_000L, 3, 5L, 6L, 3, 0, 1)));
        assertEquals(1L, tracker.summary().get("largeCallbackGapCount"));
    }
}
