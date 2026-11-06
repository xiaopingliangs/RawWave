package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StepDetectorTest {
    @Test public void rowKeepsRawStepValueTimestampAccuracyAndCallbackMetadata() {
        Map<String,Object> row = StepDetector.row(1f, 1234567890L, 3, 111L, 222L, 7, 0, 1);
        assertEquals(1234567890L, row.get("sensorTimestampNs"));
        assertEquals(1234L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(1f, row.get("STEP_DETECTED"));
        assertEquals("step", row.get("unit"));
        assertEquals("Android SensorManager TYPE_STEP_DETECTOR", row.get("source"));
        assertEquals("PLATFORM_PER_STEP_EVENT_NOT_RAW_MOTION", row.get("stepDetectorKind"));
        assertEquals(111L, row.get("receivedUtcMs"));
        assertEquals(222L, row.get("receivedElapsedMs"));
        assertEquals(7L, row.get("captureCallbackSequence"));
        assertEquals(0, row.get("captureCallbackIndex"));
        assertEquals(1, row.get("captureCallbackSize"));
        assertFalse(row.containsKey("STEP_DETECTED_encoding"));
    }

    @Test public void nonFiniteStepValueIsEncodedNotDropped() {
        Map<String,Object> row = StepDetector.row(Float.NaN, 1L, 0, 2L, 3L, 1, 0, 1);
        assertEquals("NaN", row.get("STEP_DETECTED"));
        assertEquals("NON_FINITE_AS_STRING", row.get("STEP_DETECTED_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> StepDetector.row(1f, 1L, 0, 2L, 3L, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> StepDetector.row(1f, 1L, 0, 2L, 3L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> StepDetector.row(1f, 1L, 0, 2L, 3L, 1, 0, 0));
    }
}
