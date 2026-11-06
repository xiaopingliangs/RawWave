package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GyroscopeUncalibratedTest {
    @Test public void rowKeepsRawAngularVelocityBiasTimestampAndCallbackMetadata() {
        Map<String,Object> row = GyroscopeUncalibrated.row(0.1f,0.2f,0.3f,0.01f,0.02f,0.03f,
            1234567890L,3,111L,222L,7,0,1);
        assertEquals(0.1f, row.get("GYROSCOPE_UNCALIBRATED_X"));
        assertEquals(0.2f, row.get("GYROSCOPE_UNCALIBRATED_Y"));
        assertEquals(0.3f, row.get("GYROSCOPE_UNCALIBRATED_Z"));
        assertEquals(0.01f, row.get("GYROSCOPE_BIAS_X"));
        assertEquals(0.02f, row.get("GYROSCOPE_BIAS_Y"));
        assertEquals(0.03f, row.get("GYROSCOPE_BIAS_Z"));
        assertEquals("rad/s", row.get("unit"));
        assertEquals("Android SensorManager TYPE_GYROSCOPE_UNCALIBRATED", row.get("source"));
        assertEquals("PLATFORM_UNCALIBRATED_WITH_ESTIMATED_BIAS", row.get("uncalibratedKind"));
        assertEquals(1234L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(7L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = GyroscopeUncalibrated.row(Float.NaN,0f,0f,0f,0f,0f,1L,0,2L,3L,1,0,1);
        assertEquals("NaN", row.get("GYROSCOPE_UNCALIBRATED_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GYROSCOPE_UNCALIBRATED_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> GyroscopeUncalibrated.row(0f,0f,0f,0f,0f,0f,1L,0,2L,3L,0,0,1));
    }
}
