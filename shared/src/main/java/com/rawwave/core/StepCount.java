package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_STEP_COUNTER callback.
 * The value is the platform's cumulative counter since boot. It is not raw motion,
 * not a per-step event stream, and callbacks may be sparse while stationary.
 */
public final class StepCount {
    private StepCount() {}

    public static Map<String,Object> row(float cumulativeSteps, long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("STEP_COUNT_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_STEP_COUNTER");
        row.put("stepCountKind", "PLATFORM_CUMULATIVE_COUNTER_SINCE_BOOT_NOT_RAW_MOTION");
        row.put("unit", "steps");
        row.put("accuracy", accuracy);
        putValue(row, cumulativeSteps);
        row.put("receivedUtcMs", receivedUtcMs);
        row.put("receivedElapsedMs", receivedElapsedMs);
        row.put("captureCallbackSequence", callbackSequence);
        row.put("captureCallbackIndex", callbackIndex);
        row.put("captureCallbackSize", callbackSize);
        return Collections.unmodifiableMap(row);
    }

    private static void putValue(Map<String,Object> row, float value) {
        if (Float.isFinite(value)) row.put("STEP_COUNT_TOTAL", value);
        else {
            row.put("STEP_COUNT_TOTAL", Float.toString(value));
            row.put("STEP_COUNT_TOTAL_encoding", "NON_FINITE_AS_STRING");
        }
    }
}
