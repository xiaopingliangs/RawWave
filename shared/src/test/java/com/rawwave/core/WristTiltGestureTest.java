package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class WristTiltGestureTest {
    @Test public void rowKeepsPlatformEventTimestampAndCallbackMetadata() {
        Map<String,Object> row = WristTiltGesture.row(1f, 888888888L, 3, 1000L, 2000L, 6L, 0, 1);
        assertEquals(1f, row.get("WRIST_TILT_GESTURE"));
        assertEquals("platform event value", row.get("unit"));
        assertEquals("PLATFORM_ON_CHANGE_WRIST_TILT_EVENT", row.get("kind"));
        assertEquals(888888888L, row.get("sensorTimestampNs"));
        assertEquals(888L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(6L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = WristTiltGesture.row(Float.NaN, 1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("WRIST_TILT_GESTURE"));
        assertEquals("NON_FINITE_AS_STRING", row.get("WRIST_TILT_GESTURE_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WristTiltGesture.row(1f,
                1L, 0, 0L, 0L, 0L, 0, 1));
    }
}
