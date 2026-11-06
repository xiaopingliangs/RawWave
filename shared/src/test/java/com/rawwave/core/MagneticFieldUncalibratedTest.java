package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MagneticFieldUncalibratedTest {
    @Test public void rowKeepsRawFieldBiasTimestampAndCallbackMetadata() {
        Map<String,Object> row = MagneticFieldUncalibrated.row(1f,2f,3f,0.1f,0.2f,0.3f,
            1234567890L,3,111L,222L,7,0,1);
        assertEquals(1f, row.get("MAGNETIC_FIELD_UNCALIBRATED_X"));
        assertEquals(2f, row.get("MAGNETIC_FIELD_UNCALIBRATED_Y"));
        assertEquals(3f, row.get("MAGNETIC_FIELD_UNCALIBRATED_Z"));
        assertEquals(0.1f, row.get("MAGNETIC_FIELD_BIAS_X"));
        assertEquals(0.2f, row.get("MAGNETIC_FIELD_BIAS_Y"));
        assertEquals(0.3f, row.get("MAGNETIC_FIELD_BIAS_Z"));
        assertEquals("uT", row.get("unit"));
        assertEquals("Android SensorManager TYPE_MAGNETIC_FIELD_UNCALIBRATED", row.get("source"));
        assertEquals("PLATFORM_UNCALIBRATED_WITH_HARD_IRON_BIAS", row.get("uncalibratedKind"));
        assertEquals(1234L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(7L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = MagneticFieldUncalibrated.row(Float.NaN,0f,0f,0f,0f,0f,1L,0,2L,3L,1,0,1);
        assertEquals("NaN", row.get("MAGNETIC_FIELD_UNCALIBRATED_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("MAGNETIC_FIELD_UNCALIBRATED_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> MagneticFieldUncalibrated.row(0f,0f,0f,0f,0f,0f,1L,0,2L,3L,0,0,1));
    }
}
