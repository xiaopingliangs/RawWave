package com.rawwave.core;

import java.util.*;

/**
 * Builds one raw storage row from an Android platform location fix.
 * Optional fields are null when the platform has no value; they are never replaced by zero.
 * This is platform-processed location, not a raw GNSS measurement.
 */
public final class LocationFix {
    private LocationFix() {}

    public record Fix(
        String provider,
        double latitudeDegrees,
        double longitudeDegrees,
        Double altitudeMeters,
        Double horizontalAccuracyMeters,
        Double verticalAccuracyMeters,
        Double speedMetersPerSecond,
        Double bearingDegrees,
        Double speedAccuracyMetersPerSecond,
        Double bearingAccuracyDegrees,
        Long utcTimeMs,
        long elapsedRealtimeNanos,
        boolean mock) {}

    public static Map<String,Object> row(Fix fix, long receivedUtcMs, long receivedElapsedMs,
                                         long callbackSequence, int callbackIndex, int callbackSize) {
        Objects.requireNonNull(fix, "fix");
        if (fix.provider() == null || fix.provider().isEmpty())
            throw new IllegalArgumentException("LOCATION_PROVIDER_MISSING");
        if (callbackSequence < 1 || callbackIndex < 0 || callbackSize < 1 || callbackIndex >= callbackSize)
            throw new IllegalArgumentException("LOCATION_CALLBACK_METADATA_INVALID");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", fix.elapsedRealtimeNanos());
        row.put("sdkTimestamp", fix.elapsedRealtimeNanos() / 1_000_000L);
        row.put("timestampMeaning",
            "Location.getElapsedRealtimeNanos; sdkTimestamp is derived elapsed-realtime milliseconds; utcTimeMs is Location.getTime platform fix UTC");
        row.put("source", "Android LocationManager " + fix.provider());
        row.put("locationKind", "PLATFORM_LOCATION_FIX_NOT_RAW_GNSS");
        row.put("provider", fix.provider());
        putFinite(row, "GPS_LATITUDE", fix.latitudeDegrees());
        putFinite(row, "GPS_LONGITUDE", fix.longitudeDegrees());
        putOptional(row, "GPS_ALTITUDE_METERS", fix.altitudeMeters());
        putOptional(row, "GPS_HORIZONTAL_ACCURACY_METERS", fix.horizontalAccuracyMeters());
        putOptional(row, "GPS_VERTICAL_ACCURACY_METERS", fix.verticalAccuracyMeters());
        putOptional(row, "GPS_SPEED_METERS_PER_SECOND", fix.speedMetersPerSecond());
        putOptional(row, "GPS_BEARING_DEGREES", fix.bearingDegrees());
        putOptional(row, "GPS_SPEED_ACCURACY_METERS_PER_SECOND", fix.speedAccuracyMetersPerSecond());
        putOptional(row, "GPS_BEARING_ACCURACY_DEGREES", fix.bearingAccuracyDegrees());
        row.put("utcTimeMs", fix.utcTimeMs());
        row.put("mock", fix.mock());
        row.put("receivedUtcMs", receivedUtcMs);
        row.put("receivedElapsedMs", receivedElapsedMs);
        row.put("captureCallbackSequence", callbackSequence);
        row.put("captureCallbackIndex", callbackIndex);
        row.put("captureCallbackSize", callbackSize);
        return Collections.unmodifiableMap(row);
    }

    private static void putFinite(Map<String,Object> row, String key, double value) {
        if (Double.isFinite(value)) row.put(key, value);
        else {
            row.put(key, Double.toString(value));
            row.put(key + "_encoding", "NON_FINITE_AS_STRING");
        }
    }

    private static void putOptional(Map<String,Object> row, String key, Double value) {
        if (value == null) { row.put(key, null); return; }
        putFinite(row, key, value);
    }
}
