package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_GEOMAGNETIC_ROTATION_VECTOR
 * callback. Values 0..3 are the platform's unitless absolute orientation
 * quaternion (x, y, z, w) using magnetometer and accelerometer without a
 * gyroscope. The app stores the platform output unchanged.
 */
public final class GeomagneticRotationVector {
    private GeomagneticRotationVector() {}

    public static Map<String,Object> row(float x, float y, float z, float w,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("GEOMAGNETIC_ROTATION_VECTOR_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_GEOMAGNETIC_ROTATION_VECTOR");
        row.put("kind", "PLATFORM_FUSED_ABSOLUTE_ORIENTATION_MAGNETOMETER_ACCELEROMETER_NO_GYROSCOPE");
        row.put("unit", "unitless quaternion (x,y,z,w)");
        row.put("accuracy", accuracy);
        put(row, "GEOMAGNETIC_ROTATION_VECTOR_X", x);
        put(row, "GEOMAGNETIC_ROTATION_VECTOR_Y", y);
        put(row, "GEOMAGNETIC_ROTATION_VECTOR_Z", z);
        put(row, "GEOMAGNETIC_ROTATION_VECTOR_W", w);
        row.put("receivedUtcMs", receivedUtcMs);
        row.put("receivedElapsedMs", receivedElapsedMs);
        row.put("captureCallbackSequence", callbackSequence);
        row.put("captureCallbackIndex", callbackIndex);
        row.put("captureCallbackSize", callbackSize);
        return Collections.unmodifiableMap(row);
    }

    private static void put(Map<String,Object> row, String name, float value) {
        if (Float.isFinite(value)) row.put(name, value);
        else {
            row.put(name, Float.toString(value));
            row.put(name + "_encoding", "NON_FINITE_AS_STRING");
        }
    }
}
