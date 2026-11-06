package com.rawwave.core;

import java.util.*;

/** Stable sensor identifiers and storage descriptions shared by capture, UI and tests. */
public final class SensorCatalog {
    public static final String MAGNETIC_FIELD = "MAGNETIC_FIELD";
    public static final String LIGHT = "LIGHT";
    public static final String GPS = "GPS";
    public static final String STEP_COUNT = "STEP_COUNT";
    public static final String STEP_DETECTOR = "STEP_DETECTOR";
    public static final String OFFBODY = "OFFBODY";
    public static final String MF_BIA = "MF_BIA";
    public static final String BIA = "BIA";
    public static final String PPG_ON_DEMAND = "PPG_ON_DEMAND";
    public static final String PRESSURE = "PRESSURE";
    public static final String ECG = "ECG";
    public static final String SPO2 = "SPO2";
    public static final String GYROSCOPE = "GYROSCOPE";
    public static final String GYROSCOPE_UNCALIBRATED = "GYROSCOPE_UNCALIBRATED";
    public static final String MAGNETIC_FIELD_UNCALIBRATED = "MAGNETIC_FIELD_UNCALIBRATED";
    public static final String HEART_RATE = "HEART_RATE";
    public static final String ACCELEROMETER = "ACCELEROMETER";
    public static final String ACCELEROMETER_UNCALIBRATED = "ACCELEROMETER_UNCALIBRATED";
    public static final String LINEAR_ACCELERATION = "LINEAR_ACCELERATION";
    public static final String GAME_ROTATION_VECTOR = "GAME_ROTATION_VECTOR";
    public static final String PPG = "PPG";
    public static final String SKIN_TEMPERATURE = "SKIN_TEMPERATURE";
    public static final String EDA = "EDA";
    public static final String EVENT = "EVENT";

    private static final List<String> CORE_ORDER = List.of(
        HEART_RATE, ACCELEROMETER, ACCELEROMETER_UNCALIBRATED, LINEAR_ACCELERATION, GAME_ROTATION_VECTOR,
        PPG, SKIN_TEMPERATURE, EDA, GYROSCOPE, GYROSCOPE_UNCALIBRATED,
        PRESSURE, MAGNETIC_FIELD, MAGNETIC_FIELD_UNCALIBRATED, LIGHT, GPS, STEP_COUNT, STEP_DETECTOR, OFFBODY);
    private static final List<String> DEFAULT_CORE = List.of(HEART_RATE, ACCELEROMETER);
    private static final Map<String,String> DATA_KINDS = Map.ofEntries(
        Map.entry(MF_BIA, "Samsung SDK processed multi-frequency impedance magnitude and phase at 5/10/50/250 kHz; original status/progress"),
        Map.entry(PPG_ON_DEMAND, "Samsung SDK raw PPG green, infrared and red values at 100 Hz for an on-demand short measurement; per-channel status; not continuous"),
        Map.entry(MAGNETIC_FIELD, "Android TYPE_MAGNETIC_FIELD calibrated XYZ microtesla; original timestamp and accuracy; no app filtering"),
        Map.entry(LIGHT, "Android TYPE_LIGHT ambient illuminance in lux; original sensorTimestampNs and accuracy; no app filtering or conversion"),
        Map.entry(GPS, "Android LocationManager GPS_PROVIDER platform location fixes; latitude/longitude/altitude/speed/bearing and platform accuracy/time retained; not raw GNSS measurements; no app filtering, interpolation or road snapping; gaps and disabled-provider failures preserved"),
        Map.entry(STEP_COUNT, "Android TYPE_STEP_COUNTER cumulative platform step count since boot; original float value, sensorTimestampNs and accuracy retained; callbacks occur only when the platform counter changes and may be sparse while stationary; platform counter, not raw motion and not a per-step event stream; no app filtering, interpolation or invented steps"),
        Map.entry(STEP_DETECTOR, "Android TYPE_STEP_DETECTOR per-step event; each callback is one platform-detected step (raw value normally 1.0), with original sensorTimestampNs and accuracy; event stream, not raw motion waveform; no app filtering, interpolation, invented steps or step inference"),
        Map.entry(OFFBODY, "Android TYPE_LOW_LATENCY_OFFBODY_DETECT on-body/off-body state; raw value normally 1.0 on-body and 0.0 off-body, with original sensorTimestampNs and accuracy; state events, not raw optical or skin data; no filtering or inference"),
        Map.entry(BIA, "Samsung SDK processed body composition and impedance; original status and progress retained; not raw impedance waveform"),
        Map.entry(PRESSURE, "Android TYPE_PRESSURE ambient pressure hPa, original sensorTimestampNs and accuracy; no altitude conversion or app filtering"),
        Map.entry(SPO2, "Samsung SDK processed oxygen saturation percentage, heart rate and original status; not raw optical waveform"),
        Map.entry(GYROSCOPE, "Android TYPE_GYROSCOPE angular velocity X/Y/Z rad/s, original sensorTimestampNs and accuracy; platform calibrated, no app filtering"),
        Map.entry(GYROSCOPE_UNCALIBRATED, "Android TYPE_GYROSCOPE_UNCALIBRATED uncalibrated angular velocity X/Y/Z rad/s plus estimated bias X/Y/Z rad/s; original sensorTimestampNs and accuracy; platform uncalibrated values, not app bias estimation; no filtering or conversion"),
        Map.entry(MAGNETIC_FIELD_UNCALIBRATED, "Android TYPE_MAGNETIC_FIELD_UNCALIBRATED uncalibrated magnetic field X/Y/Z microtesla plus hard-iron bias X/Y/Z microtesla; original sensorTimestampNs and accuracy; platform uncalibrated values, not app calibration; no filtering or conversion"),
        Map.entry(ECG, "Samsung SDK raw ECG_MV plus documented ECG fields"),
        Map.entry(HEART_RATE, "Samsung SDK processed heart rate, status and IBI; not raw PPG"),
        Map.entry(ACCELEROMETER, "Samsung SDK raw integer X/Y/Z axes; no unit conversion"),
        Map.entry(ACCELEROMETER_UNCALIBRATED, "Android TYPE_ACCELEROMETER_UNCALIBRATED uncalibrated acceleration X/Y/Z m/s² plus platform estimated bias X/Y/Z m/s²; original sensorTimestampNs and accuracy; no filtering or conversion"),
        Map.entry(LINEAR_ACCELERATION, "Android TYPE_LINEAR_ACCELERATION platform-fused linear acceleration X/Y/Z m/s² with gravity removed; original sensorTimestampNs and accuracy; platform output, no app filtering or gravity estimation"),
        Map.entry(GAME_ROTATION_VECTOR, "Android TYPE_GAME_ROTATION_VECTOR platform-fused orientation quaternion X/Y/Z/W using gyroscope and accelerometer without magnetometer; original sensorTimestampNs and accuracy; platform output, no app normalization or conversion"),
        Map.entry(PPG, "Samsung SDK raw green, infrared and red PPG values plus per-channel status"),
        Map.entry(SKIN_TEMPERATURE, "Samsung SDK processed object and ambient temperature; not body temperature"),
        Map.entry(EDA, "Samsung SDK raw skin conductance plus status"));

    private SensorCatalog() {}

    public static List<String> defaultCoreSources() { return DEFAULT_CORE; }
    public static List<String> availableCoreSources() { return CORE_ORDER; }

    /** True when the source is served by Android platform APIs, not the Samsung SDK. */
    public static boolean isAndroidPlatformSource(String sensor) {
        return GYROSCOPE.equals(sensor) || GYROSCOPE_UNCALIBRATED.equals(sensor)
            || ACCELEROMETER_UNCALIBRATED.equals(sensor)
            || LINEAR_ACCELERATION.equals(sensor) || GAME_ROTATION_VECTOR.equals(sensor)
            || PRESSURE.equals(sensor) || MAGNETIC_FIELD.equals(sensor)
            || MAGNETIC_FIELD_UNCALIBRATED.equals(sensor)
            || LIGHT.equals(sensor) || GPS.equals(sensor) || STEP_COUNT.equals(sensor)
            || STEP_DETECTOR.equals(sensor) || OFFBODY.equals(sensor);
    }

    /** Null means a legacy template written before selectable sensors existed. */
    public static List<String> normalizeCoreSources(Collection<String> requested) {
        if (requested == null) return DEFAULT_CORE;
        Set<String> input = new LinkedHashSet<>(requested);
        for (String sensor : input) if (!CORE_ORDER.contains(sensor))
            throw new IllegalArgumentException("UNKNOWN_CORE_SENSOR:" + sensor);
        List<String> result = CORE_ORDER.stream().filter(input::contains).toList();
        if (result.isEmpty()) throw new IllegalArgumentException("至少选择一个活动中传感器");
        return List.copyOf(result);
    }

    public static boolean acceptsStream(String sensor) {
        return MF_BIA.equals(sensor) || EVENT.equals(sensor) || BIA.equals(sensor) || ECG.equals(sensor)
            || SPO2.equals(sensor) || PPG_ON_DEMAND.equals(sensor) || CORE_ORDER.contains(sensor);
    }

    public static String dataKind(String sensor) {
        String value = DATA_KINDS.get(sensor);
        if (value == null) throw new IllegalArgumentException("UNKNOWN_SENSOR:" + sensor);
        return value;
    }
}
