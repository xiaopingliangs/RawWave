package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_ACCELEROMETER callback.
 * This is the platform calibrated accelerometer in m/s² and includes gravity.
 * It is a separate source from the Samsung SDK raw integer ACCELEROMETER stream.
 */
public final class PlatformAccelerometer {
    private PlatformAccelerometer() {}

    public static Map<String,Object> row(float x, float y, float z,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("PLATFORM_ACCELEROMETER_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_ACCELEROMETER");
        row.put("kind", "PLATFORM_CALIBRATED_ACCELEROMETER_INCLUDING_GRAVITY");
        row.put("unit", "m/s^2");
        row.put("includesGravity", true);
        row.put("accuracy", accuracy);
        put(row, "PLATFORM_ACCELEROMETER_X", x);
        put(row, "PLATFORM_ACCELEROMETER_Y", y);
        put(row, "PLATFORM_ACCELEROMETER_Z", z);
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
