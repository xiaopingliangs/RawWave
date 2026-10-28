package com.rawwave.core;

import java.util.*;

/** Stable sensor identifiers and storage descriptions shared by capture, UI and tests. */
public final class SensorCatalog {
    public static final String MAGNETIC_FIELD = "MAGNETIC_FIELD";
    public static final String MF_BIA = "MF_BIA";
    public static final String BIA = "BIA";
    public static final String PRESSURE = "PRESSURE";
    public static final String ECG = "ECG";
    public static final String SPO2 = "SPO2";
    public static final String GYROSCOPE = "GYROSCOPE";
    public static final String HEART_RATE = "HEART_RATE";
    public static final String ACCELEROMETER = "ACCELEROMETER";
    public static final String PPG = "PPG";
    public static final String SKIN_TEMPERATURE = "SKIN_TEMPERATURE";
    public static final String EDA = "EDA";
    public static final String EVENT = "EVENT";

    private static final List<String> CORE_ORDER = List.of(
        HEART_RATE, ACCELEROMETER, PPG, SKIN_TEMPERATURE, EDA, GYROSCOPE, PRESSURE, MAGNETIC_FIELD);
    private static final List<String> DEFAULT_CORE = List.of(HEART_RATE, ACCELEROMETER);
    private static final Map<String,String> DATA_KINDS = Map.ofEntries(
        Map.entry(MF_BIA, "Samsung SDK processed multi-frequency impedance magnitude and phase at 5/10/50/250 kHz; original status/progress"),
        Map.entry(MAGNETIC_FIELD, "Android TYPE_MAGNETIC_FIELD calibrated XYZ microtesla; original timestamp and accuracy; no app filtering"),
        Map.entry(BIA, "Samsung SDK processed body composition and impedance; original status and progress retained; not raw impedance waveform"),
        Map.entry(PRESSURE, "Android TYPE_PRESSURE ambient pressure hPa, original sensorTimestampNs and accuracy; no altitude conversion or app filtering"),
        Map.entry(SPO2, "Samsung SDK processed oxygen saturation percentage, heart rate and original status; not raw optical waveform"),
        Map.entry(GYROSCOPE, "Android TYPE_GYROSCOPE angular velocity X/Y/Z rad/s, original sensorTimestampNs and accuracy; platform calibrated, no app filtering"),
        Map.entry(ECG, "Samsung SDK raw ECG_MV plus documented ECG fields"),
        Map.entry(HEART_RATE, "Samsung SDK processed heart rate, status and IBI; not raw PPG"),
        Map.entry(ACCELEROMETER, "Samsung SDK raw integer X/Y/Z axes; no unit conversion"),
        Map.entry(PPG, "Samsung SDK raw green, infrared and red PPG values plus per-channel status"),
        Map.entry(SKIN_TEMPERATURE, "Samsung SDK processed object and ambient temperature; not body temperature"),
        Map.entry(EDA, "Samsung SDK raw skin conductance plus status"));

    private SensorCatalog() {}

    public static List<String> defaultCoreSources() { return DEFAULT_CORE; }
    public static List<String> availableCoreSources() { return CORE_ORDER; }

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
        return MF_BIA.equals(sensor) || EVENT.equals(sensor) || BIA.equals(sensor) || ECG.equals(sensor) || SPO2.equals(sensor) || CORE_ORDER.contains(sensor);
    }

    public static String dataKind(String sensor) {
        String value = DATA_KINDS.get(sensor);
        if (value == null) throw new IllegalArgumentException("UNKNOWN_SENSOR:" + sensor);
        return value;
    }
}
