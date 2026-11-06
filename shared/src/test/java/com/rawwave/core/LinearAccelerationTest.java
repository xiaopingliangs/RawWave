package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class LinearAccelerationTest {
    @Test public void rowKeepsPlatformLinearAccelerationTimestampAndCallbackMetadata() {
        Map<String,Object> row = LinearAcceleration.row(0.1f, -0.2f, 0.3f,
                987654321L, 3, 1000L, 2000L, 9L, 0, 1);
        assertEquals(0.1f, row.get("LINEAR_ACCELERATION_X"));
        assertEquals(-0.2f, row.get("LINEAR_ACCELERATION_Y"));
        assertEquals(0.3f, row.get("LINEAR_ACCELERATION_Z"));
        assertEquals("m/s^2", row.get("unit"));
        assertEquals("PLATFORM_FUSED_GRAVITY_REMOVED", row.get("kind"));
        assertEquals(987654321L, row.get("sensorTimestampNs"));
        assertEquals(987L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(9L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = LinearAcceleration.row(Float.NaN, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("LINEAR_ACCELERATION_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("LINEAR_ACCELERATION_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LinearAcceleration.row(0f, 0f, 0f,
                1L, 0, 0L, 0L, 0L, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> LinearAcceleration.row(0f, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 1, 1));
    }
}
