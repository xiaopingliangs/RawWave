package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GeomagneticRotationVectorTest {
    @Test public void rowKeepsQuaternionTimestampAndCallbackMetadata() {
        Map<String,Object> row = GeomagneticRotationVector.row(0.1f, 0.2f, 0.3f, 0.9f,
                444444444L, 3, 1000L, 2000L, 2L, 0, 1);
        assertEquals(0.1f, row.get("GEOMAGNETIC_ROTATION_VECTOR_X"));
        assertEquals(0.2f, row.get("GEOMAGNETIC_ROTATION_VECTOR_Y"));
        assertEquals(0.3f, row.get("GEOMAGNETIC_ROTATION_VECTOR_Z"));
        assertEquals(0.9f, row.get("GEOMAGNETIC_ROTATION_VECTOR_W"));
        assertEquals("unitless quaternion (x,y,z,w)", row.get("unit"));
        assertEquals("PLATFORM_FUSED_ABSOLUTE_ORIENTATION_MAGNETOMETER_ACCELEROMETER_NO_GYROSCOPE", row.get("kind"));
        assertEquals(444444444L, row.get("sensorTimestampNs"));
        assertEquals(444L, row.get("sdkTimestamp"));
    }

    @Test public void nonFiniteValueIsEncodedNotDropped() {
        Map<String,Object> row = GeomagneticRotationVector.row(Float.NaN, 0f, 0f, 1f,
                1L, 0, 0L, 0L, 1L, 0, 1);
        assertEquals("NaN", row.get("GEOMAGNETIC_ROTATION_VECTOR_X"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GEOMAGNETIC_ROTATION_VECTOR_X_encoding"));
    }

    @Test public void invalidCallbackMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeomagneticRotationVector.row(0f, 0f, 0f, 1f,
                1L, 0, 0L, 0L, 0L, 0, 1));
    }
}
