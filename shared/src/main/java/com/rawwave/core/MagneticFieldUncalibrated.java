package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_MAGNETIC_FIELD_UNCALIBRATED callback.
 * Values 0..2 are the uncalibrated magnetic field (microtesla); values 3..5 are
 * the platform's hard-iron bias estimate (microtesla). The app performs no calibration.
 */
public final class MagneticFieldUncalibrated {
    private MagneticFieldUncalibrated() {}

    public static Map<String,Object> row(float x, float y, float z, float biasX, float biasY, float biasZ,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("MAGNETIC_FIELD_UNCALIBRATED_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_MAGNETIC_FIELD_UNCALIBRATED");
        row.put("uncalibratedKind", "PLATFORM_UNCALIBRATED_WITH_HARD_IRON_BIAS");
        row.put("unit", "uT");
        row.put("accuracy", accuracy);
        put(row, "MAGNETIC_FIELD_UNCALIBRATED_X", x);
        put(row, "MAGNETIC_FIELD_UNCALIBRATED_Y", y);
        put(row, "MAGNETIC_FIELD_UNCALIBRATED_Z", z);
        put(row, "MAGNETIC_FIELD_BIAS_X", biasX);
        put(row, "MAGNETIC_FIELD_BIAS_Y", biasY);
        put(row, "MAGNETIC_FIELD_BIAS_Z", biasZ);
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
