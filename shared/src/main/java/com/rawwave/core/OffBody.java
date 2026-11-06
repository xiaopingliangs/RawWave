package com.rawwave.core;

import java.util.*;

/**
 * Builds one storage row from an Android TYPE_LOW_LATENCY_OFFBODY_DETECT callback.
 * The platform value is normally 1.0 on-body and 0.0 off-body. It is a state event,
 * not raw optical or skin data, and the app does not infer a state.
 */
public final class OffBody {
    private OffBody() {}

    public static Map<String,Object> row(float state, long sensorTimestampNs, int accuracy,
                                         long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("OFFBODY_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", sensorTimestampNs);
        row.put("sdkTimestamp", sensorTimestampNs / 1_000_000L);
        row.put("timestampMeaning", "Android boot nanoseconds; sdkTimestamp is derived milliseconds");
        row.put("source", "Android SensorManager TYPE_LOW_LATENCY_OFFBODY_DETECT");
        row.put("offBodyKind", "PLATFORM_OFF_BODY_STATE_NOT_RAW_OPTICAL_OR_SKIN");
        row.put("unit", "state");
        row.put("accuracy", accuracy);
        putValue(row, state);
        row.put("receivedUtcMs", receivedUtcMs);
        row.put("receivedElapsedMs", receivedElapsedMs);
        row.put("captureCallbackSequence", callbackSequence);
        row.put("captureCallbackIndex", callbackIndex);
        row.put("captureCallbackSize", callbackSize);
        return Collections.unmodifiableMap(row);
    }

    private static void putValue(Map<String,Object> row, float value) {
        if (Float.isFinite(value)) row.put("OFF_BODY_STATE", value);
        else {
            row.put("OFF_BODY_STATE", Float.toString(value));
            row.put("OFF_BODY_STATE_encoding", "NON_FINITE_AS_STRING");
        }
    }
}
