package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_STEP_DETECTOR callback.
 * Each callback is one platform-detected step event. It is not raw motion and
 * the app does not infer cadence, gait or missing steps.
 */
public final class StepDetector {
    private StepDetector() {}

    public static Map<String,Object> row(float detectedValue, long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("STEP_DETECTOR_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_STEP_DETECTOR");
        row.put("stepDetectorKind", "PLATFORM_PER_STEP_EVENT_NOT_RAW_MOTION");
        row.put("unit", "step");
        row.put("accuracy", accuracy);
        putValue(row, detectedValue);
        row.put("receivedUtcMs", receivedUtcMs);
        row.put("receivedElapsedMs", receivedElapsedMs);
        row.put("captureCallbackSequence", callbackSequence);
        row.put("captureCallbackIndex", callbackIndex);
        row.put("captureCallbackSize", callbackSize);
        return Collections.unmodifiableMap(row);
    }

    private static void putValue(Map<String,Object> row, float value) {
        if (Float.isFinite(value)) row.put("STEP_DETECTED", value);
        else {
            row.put("STEP_DETECTED", Float.toString(value));
            row.put("STEP_DETECTED_encoding", "NON_FINITE_AS_STRING");
        }
    }
}
