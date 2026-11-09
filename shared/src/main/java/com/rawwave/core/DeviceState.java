package com.rawwave.core;

import java.util.*;

/**
 * Builds one low-rate device-state sample (battery, charging, screen, keyguard).
 * These are context metadata, not health measurements. Unknown values stay null.
 */
public final class DeviceState {
    private DeviceState() {}

    public static Map<String,Object> row(Integer batteryPercent, String batteryStatus,
                                         String batteryPlugged, Integer batteryTemperatureTenthsC,
                                         Integer batteryVoltageMv, Boolean screenInteractive,
                                         Boolean keyguardLocked, String displayState,
                                         long utcMs, long elapsedMs, String phase) {
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100))
            throw new IllegalArgumentException("BATTERY_PERCENT_OUT_OF_RANGE");
        if (batteryTemperatureTenthsC != null && batteryTemperatureTenthsC < -1000)
            throw new IllegalArgumentException("BATTERY_TEMPERATURE_OUT_OF_RANGE");
        if (batteryVoltageMv != null && batteryVoltageMv < 0)
            throw new IllegalArgumentException("BATTERY_VOLTAGE_OUT_OF_RANGE");
        if (phase == null || phase.isBlank()) throw new IllegalArgumentException("DEVICE_STATE_PHASE_MISSING");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sensorTimestampNs", elapsedMs * 1_000_000L);
        row.put("sdkTimestamp", elapsedMs);
        row.put("timestampMeaning", "Android elapsedRealtime milliseconds; utcMs is wall-clock milliseconds");
        row.put("source", "Android BatteryManager/PowerManager/KeyguardManager/DisplayManager");
        row.put("kind", "DEVICE_STATE_SAMPLE");
        row.put("batteryPercent", batteryPercent);
        row.put("batteryStatus", batteryStatus);
        row.put("batteryPlugged", batteryPlugged);
        row.put("batteryTemperatureTenthsC", batteryTemperatureTenthsC);
        row.put("batteryTemperatureC", batteryTemperatureTenthsC == null ? null : batteryTemperatureTenthsC / 10.0);
        row.put("batteryVoltageMv", batteryVoltageMv);
        row.put("screenInteractive", screenInteractive);
        row.put("keyguardLocked", keyguardLocked);
        row.put("displayState", displayState);
        row.put("utcMs", utcMs);
        row.put("elapsedMs", elapsedMs);
        row.put("phase", phase);
        return Collections.unmodifiableMap(row);
    }
}
