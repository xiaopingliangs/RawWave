package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GravityTest {
    @Test public void rowKeepsPlatformGravityTimestampAndCallbackMetadata() {
        Map<String,Object> row = Gravity.row(0.1f, -0.2f, 9.7f,
                333333333L, 3, 1000L, 2000L, 3L, 0, 1);
        assertEquals(0.1f, row.get("GRAVITY_X"));
        assertEquals(-0.2f, row.get("GRAVITY_Y"));
        assertEquals(9.7f, row.get("GRAVITY_Z"));
        assertEquals("m/s^2", row.get("unit"));
        assertEquals("PLATFORM_FUSED_GRAVITY_VECTOR", row.get("kind"));
        assertEquals(333333333L, row.get("sensorTimestampNs"));
        assertEquals(333L, row.get("sdkTimestamp"));
        assertEquals(3L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = Gravity.row(Float.NaN, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("GRAVITY_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GRAVITY_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Gravity.row(0f, 0f, 0f,
                1L, 0, 0L, 0L, 0L, 0, 1));
    }
}
