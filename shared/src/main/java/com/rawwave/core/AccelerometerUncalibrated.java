package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_ACCELEROMETER_UNCALIBRATED callback.
 * Values 0..2 are uncalibrated acceleration (m/s²); values 3..5 are the
 * platform's estimated bias (m/s²). The app performs no calibration or filtering.
 */
public final class AccelerometerUncalibrated {
    private AccelerometerUncalibrated() {}

    public static Map<String,Object> row(float x, float y, float z, float biasX, float biasY, float biasZ,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("ACCELEROMETER_UNCALIBRATED_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_ACCELEROMETER_UNCALIBRATED");
        row.put("uncalibratedKind", "PLATFORM_UNCALIBRATED_WITH_ESTIMATED_BIAS");
        row.put("unit", "m/s^2");
        row.put("accuracy", accuracy);
        put(row, "ACCELEROMETER_UNCALIBRATED_X", x);
        put(row, "ACCELEROMETER_UNCALIBRATED_Y", y);
        put(row, "ACCELEROMETER_UNCALIBRATED_Z", z);
        put(row, "ACCELEROMETER_BIAS_X", biasX);
        put(row, "ACCELEROMETER_BIAS_Y", biasY);
        put(row, "ACCELEROMETER_BIAS_Z", biasZ);
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
