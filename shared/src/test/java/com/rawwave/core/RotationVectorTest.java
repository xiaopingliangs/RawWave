package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class RotationVectorTest {
    @Test public void rowKeepsAbsoluteQuaternionHeadingAccuracyAndCallbackMetadata() {
        Map<String,Object> row = RotationVector.row(0.1f, 0.2f, 0.3f, 0.9f, 0.05f,
                777777777L, 3, 1000L, 2000L, 5L, 0, 1);
        assertEquals(0.1f, row.get("ROTATION_VECTOR_X"));
        assertEquals(0.2f, row.get("ROTATION_VECTOR_Y"));
        assertEquals(0.3f, row.get("ROTATION_VECTOR_Z"));
        assertEquals(0.9f, row.get("ROTATION_VECTOR_W"));
        assertEquals(0.05f, row.get("HEADING_ACCURACY_RAD"));
        assertEquals("radians", row.get("HEADING_ACCURACY_UNIT"));
        assertEquals("unitless quaternion (x,y,z,w)", row.get("unit"));
        assertEquals("PLATFORM_FUSED_ABSOLUTE_ORIENTATION_WITH_MAGNETOMETER", row.get("kind"));
        assertEquals(777777777L, row.get("sensorTimestampNs"));
        assertEquals(777L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
    }

    @Test public void headingAccuracyIsOptional() {
        Map<String,Object> row = RotationVector.row(0f, 0f, 0f, 1f, null,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertFalse(row.containsKey("HEADING_ACCURACY_RAD"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = RotationVector.row(Float.NaN, 0f, 0f, 1f, Float.POSITIVE_INFINITY,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("ROTATION_VECTOR_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("ROTATION_VECTOR_X_encoding"));
        assertEquals("Infinity", row.get("HEADING_ACCURACY_RAD"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RotationVector.row(0f, 0f, 0f, 1f, null,
                1L, 0, 0L, 0L, 0L, 0, 1));
    }
}
