package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class AccelerometerUncalibratedTest {
    @Test public void rowKeepsRawAccelerationBiasTimestampAndCallbackMetadata() {
        Map<String,Object> row = AccelerometerUncalibrated.row(0.1f, 0.2f, 9.8f, 0.01f, 0.02f, 0.03f,
                123456789L, 3, 1000L, 2000L, 7L, 0, 1);
        assertEquals(0.1f, row.get("ACCELEROMETER_UNCALIBRATED_X"));
        assertEquals(0.2f, row.get("ACCELEROMETER_UNCALIBRATED_Y"));
        assertEquals(9.8f, row.get("ACCELEROMETER_UNCALIBRATED_Z"));
        assertEquals(0.01f, row.get("ACCELEROMETER_BIAS_X"));
        assertEquals(0.02f, row.get("ACCELEROMETER_BIAS_Y"));
        assertEquals(0.03f, row.get("ACCELEROMETER_BIAS_Z"));
        assertEquals(123456789L, row.get("sensorTimestampNs"));
        assertEquals(123L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(7L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = AccelerometerUncalibrated.row(Float.NaN, 0f, 0f, 0f, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("ACCELEROMETER_UNCALIBRATED_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("ACCELEROMETER_UNCALIBRATED_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccelerometerUncalibrated.row(0f, 0f, 0f, 0f, 0f, 0f,
                1L, 0, 0L, 0L, 0L, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> AccelerometerUncalibrated.row(0f, 0f, 0f, 0f, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 1, 1));
    }
}
