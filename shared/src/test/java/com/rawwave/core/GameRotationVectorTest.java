package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GameRotationVectorTest {
    @Test public void rowKeepsQuaternionTimestampAndCallbackMetadata() {
        Map<String,Object> row = GameRotationVector.row(0.1f, 0.2f, 0.3f, 0.9f,
                555555555L, 3, 1000L, 2000L, 4L, 0, 1);
        assertEquals(0.1f, row.get("GAME_ROTATION_VECTOR_X"));
        assertEquals(0.2f, row.get("GAME_ROTATION_VECTOR_Y"));
        assertEquals(0.3f, row.get("GAME_ROTATION_VECTOR_Z"));
        assertEquals(0.9f, row.get("GAME_ROTATION_VECTOR_W"));
        assertEquals("unitless quaternion (x,y,z,w)", row.get("unit"));
        assertEquals("PLATFORM_FUSED_GYROSCOPE_ACCELEROMETER_NO_MAGNETOMETER", row.get("kind"));
        assertEquals(555555555L, row.get("sensorTimestampNs"));
        assertEquals(555L, row.get("sdkTimestamp"));
        assertEquals(3, row.get("accuracy"));
        assertEquals(4L, row.get("captureCallbackSequence"));
    }

    @Test public void nonFiniteComponentsAreEncodedNotDropped() {
        Map<String,Object> row = GameRotationVector.row(Float.NaN, 0f, 0f, Float.POSITIVE_INFINITY,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("GAME_ROTATION_VECTOR_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GAME_ROTATION_VECTOR_X_encoding"));
        assertEquals("Infinity", row.get("GAME_ROTATION_VECTOR_W"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GAME_ROTATION_VECTOR_W_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GameRotationVector.row(0f, 0f, 0f, 0f,
                1L, 0, 0L, 0L, 0L, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GameRotationVector.row(0f, 0f, 0f, 0f,
                1L, 0, 0L, 0L, 1L, 1, 1));
    }
}
