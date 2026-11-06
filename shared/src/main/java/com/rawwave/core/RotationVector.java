package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_ROTATION_VECTOR callback.
 * Values 0..3 are the platform's unitless absolute orientation quaternion
 * (x, y, z, w). When the platform provides values[4], it is the estimated
 * heading accuracy in radians. The app stores the platform output unchanged.
 */
public final class RotationVector {
    private RotationVector() {}

    public static Map<String,Object> row(float x, float y, float z, float w,
                                         Float headingAccuracyRad,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("ROTATION_VECTOR_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_ROTATION_VECTOR");
        row.put("kind", "PLATFORM_FUSED_ABSOLUTE_ORIENTATION_WITH_MAGNETOMETER");
        row.put("unit", "unitless quaternion (x,y,z,w)");
        row.put("accuracy", accuracy);
        put(row, "ROTATION_VECTOR_X", x);
        put(row, "ROTATION_VECTOR_Y", y);
        put(row, "ROTATION_VECTOR_Z", z);
        put(row, "ROTATION_VECTOR_W", w);
        if (headingAccuracyRad != null) {
            put(row, "HEADING_ACCURACY_RAD", headingAccuracyRad);
            row.put("HEADING_ACCURACY_UNIT", "radians");
        }
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
