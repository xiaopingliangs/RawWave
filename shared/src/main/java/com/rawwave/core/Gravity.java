package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_GRAVITY callback.
 * Values are the platform's fused gravity vector in m/s². The app stores the
 * platform output unchanged and performs no filtering or gravity estimation.
 */
public final class Gravity {
    private Gravity() {}

    public static Map<String,Object> row(float x, float y, float z,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("GRAVITY_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_GRAVITY");
        row.put("kind", "PLATFORM_FUSED_GRAVITY_VECTOR");
        row.put("unit", "m/s^2");
        row.put("accuracy", accuracy);
        put(row, "GRAVITY_X", x);
        put(row, "GRAVITY_Y", y);
        put(row, "GRAVITY_Z", z);
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
