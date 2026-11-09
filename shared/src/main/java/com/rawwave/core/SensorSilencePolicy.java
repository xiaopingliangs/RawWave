package com.rawwave.core;

import java.util.Map;

/**
 * Sensor-specific callback-silence expectations. A single 15-second watchdog is wrong for
 * sparse sensors such as skin temperature (about 60 s) and event streams (steps, off-body,
 * wrist tilt). Event streams return 0: quiet while the state does not change is expected.
 */
public final class SensorSilencePolicy {
    private SensorSilencePolicy() {}

    private static final Map<String,Long> EXPECTED_MS = Map.ofEntries(
        Map.entry(SensorCatalog.HEART_RATE, 1_000L),
        Map.entry(SensorCatalog.PPG, 40L),
        Map.entry(SensorCatalog.EDA, 1_000L),
        Map.entry(SensorCatalog.SKIN_TEMPERATURE, 60_000L),
        Map.entry(SensorCatalog.PLATFORM_ACCELEROMETER, 10L),
        Map.entry(SensorCatalog.GYROSCOPE, 10L),
        Map.entry(SensorCatalog.GYROSCOPE_UNCALIBRATED, 40L),
        Map.entry(SensorCatalog.LINEAR_ACCELERATION, 40L),
        Map.entry(SensorCatalog.GAME_ROTATION_VECTOR, 40L),
        Map.entry(SensorCatalog.ROTATION_VECTOR, 40L),
        Map.entry(SensorCatalog.GEOMAGNETIC_ROTATION_VECTOR, 40L),
        Map.entry(SensorCatalog.GRAVITY, 40L),
        Map.entry(SensorCatalog.MAGNETIC_FIELD, 100L),
        Map.entry(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED, 40L),
        Map.entry(SensorCatalog.PRESSURE, 1_000L),
        Map.entry(SensorCatalog.LIGHT, 500L),
        Map.entry(SensorCatalog.GPS, 1_000L)
    );

    public static long expectedIntervalMs(String sensor) {
        Long value = EXPECTED_MS.get(sensor);
        return value == null ? 0L : value;
    }

    /** 0 means no silence alarm for this source. */
    public static long silenceThresholdMs(String sensor) {
        if (sensor == null) return 0L;
        if (sensor.equals(SensorCatalog.STEP_COUNT) || sensor.equals(SensorCatalog.STEP_DETECTOR)
            || sensor.equals(SensorCatalog.OFFBODY) || sensor.equals(SensorCatalog.WRIST_TILT_GESTURE))
            return 0L;
        long expected = expectedIntervalMs(sensor);
        if (expected <= 0) return 15_000L;
        return Math.max(15_000L, expected * 3L);
    }
}
