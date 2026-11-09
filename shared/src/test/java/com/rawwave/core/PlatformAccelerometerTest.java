package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PlatformAccelerometerTest {
    @Test public void rowRetainsPlatformValuesUnitsAndCallbackMetadata() {
        Map<String,Object> row = PlatformAccelerometer.row(0.5f, -1.25f, 9.81f, 123456789L, 3,
            111L, 222L, 7, 0, 1);
        assertEquals("Android SensorManager TYPE_ACCELEROMETER", row.get("source"));
        assertEquals("PLATFORM_CALIBRATED_ACCELEROMETER_INCLUDING_GRAVITY", row.get("kind"));
        assertEquals("m/s^2", row.get("unit"));
        assertEquals(true, row.get("includesGravity"));
        assertEquals(0.5f, (Float) row.get("PLATFORM_ACCELEROMETER_X"), 0.0f);
        assertEquals(-1.25f, (Float) row.get("PLATFORM_ACCELEROMETER_Y"), 0.0f);
        assertEquals(9.81f, (Float) row.get("PLATFORM_ACCELEROMETER_Z"), 0.0f);
        assertEquals(123456789L, row.get("sensorTimestampNs"));
        assertEquals(123456789L / 1_000_000L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(111L, row.get("receivedUtcMs"));
        assertEquals(222L, row.get("receivedElapsedMs"));
        assertEquals(7L, row.get("captureCallbackSequence"));
        assertEquals(0, row.get("captureCallbackIndex"));
        assertEquals(1, row.get("captureCallbackSize"));
    }

    @Test public void nonFiniteValuesAreEncodedNotDropped() {
        Map<String,Object> row = PlatformAccelerometer.row(Float.NaN, Float.POSITIVE_INFINITY, 0f,
            1L, 0, 2L, 3L, 1, 0, 1);
        assertEquals("NaN", row.get("PLATFORM_ACCELEROMETER_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("PLATFORM_ACCELEROMETER_X_encoding"));
        assertEquals("Infinity", row.get("PLATFORM_ACCELEROMETER_Y"));
        assertEquals(0f, (Float) row.get("PLATFORM_ACCELEROMETER_Z"), 0.0f);
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> PlatformAccelerometer.row(0f, 0f, 0f, 1L, 3, 1L, 2L, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> PlatformAccelerometer.row(0f, 0f, 0f, 1L, 3, 1L, 2L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> PlatformAccelerometer.row(0f, 0f, 0f, 1L, 3, 1L, 2L, 1, 0, 0));
    }
}
