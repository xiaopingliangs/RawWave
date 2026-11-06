package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_WRIST_TILT_GESTURE callback.
 * The platform event value is stored unchanged (normally 1.0). Sparse events
 * are expected; an absence of events is not evidence that the gesture never
 * occurred or that the sensor was unavailable.
 */
public final class WristTiltGesture {
    private WristTiltGesture() {}

    public static Map<String,Object> row(float value,
                                         long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("WRIST_TILT_GESTURE_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_WRIST_TILT_GESTURE");
        row.put("kind", "PLATFORM_ON_CHANGE_WRIST_TILT_EVENT");
        row.put("unit", "platform event value");
        row.put("accuracy", accuracy);
        put(row, "WRIST_TILT_GESTURE", value);
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
