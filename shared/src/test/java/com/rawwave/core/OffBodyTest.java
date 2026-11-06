package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OffBodyTest {
    @Test public void rowKeepsRawStateTimestampAccuracyAndCallbackMetadata() {
        Map<String,Object> row = OffBody.row(1f, 1234567890L, 3, 111L, 222L, 7, 0, 1);
        assertEquals(1234567890L, row.get("sensorTimestampNs"));
        assertEquals(1234L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(1f, row.get("OFF_BODY_STATE"));
        assertEquals("state", row.get("unit"));
        assertEquals("Android SensorManager TYPE_LOW_LATENCY_OFFBODY_DETECT", row.get("source"));
        assertEquals("PLATFORM_OFF_BODY_STATE_NOT_RAW_OPTICAL_OR_SKIN", row.get("offBodyKind"));
        assertEquals(111L, row.get("receivedUtcMs"));
        assertEquals(222L, row.get("receivedElapsedMs"));
        assertEquals(7L, row.get("captureCallbackSequence"));
        assertEquals(0, row.get("captureCallbackIndex"));
        assertEquals(1, row.get("captureCallbackSize"));
        assertFalse(row.containsKey("OFF_BODY_STATE_encoding"));
    }

    @Test public void offBodyZeroIsPreserved() {
        assertEquals(0f, OffBody.row(0f, 1L, 0, 2L, 3L, 1, 0, 1).get("OFF_BODY_STATE"));
    }

    @Test public void nonFiniteStateIsEncodedNotDropped() {
        Map<String,Object> row = OffBody.row(Float.NaN, 1L, 0, 2L, 3L, 1, 0, 1);
        assertEquals("NaN", row.get("OFF_BODY_STATE"));
        assertEquals("NON_FINITE_AS_STRING", row.get("OFF_BODY_STATE_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> OffBody.row(1f, 1L, 0, 2L, 3L, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> OffBody.row(1f, 1L, 0, 2L, 3L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> OffBody.row(1f, 1L, 0, 2L, 3L, 1, 0, 0));
    }
}
