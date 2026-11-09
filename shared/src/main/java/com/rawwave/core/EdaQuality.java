package com.rawwave.core;

import java.util.Map;

/**
 * Adds a conservative review flag to raw EDA rows. The Samsung EDA STATUS value is not
 * treated as a quality filter: values and status stay untouched, and this class never
 * edits, drops or replaces a measurement.
 */
public final class EdaQuality {
    private EdaQuality() {}

    public static final double TYPICAL_UPPER_BOUND_US = 20.0;

    public static void annotate(Map<String, Object> row) {
        Object raw = row.get("SKIN_CONDUCTANCE");
        Boolean outside = null;
        if (raw instanceof Number value) {
            double v = value.doubleValue();
            if (Double.isFinite(v)) outside = v <= 0.0 || v > TYPICAL_UPPER_BOUND_US;
        }
        row.put("EDA_OUTSIDE_TYPICAL_RANGE", outside);
        row.put("EDA_TYPICAL_RANGE_RULE",
            "0 < raw <= 20 microsiemens; heuristic for later review only, raw value unchanged");
        row.put("EDA_STATUS_ROLE", "SDK_STATUS_STORED_RAW_NOT_USED_AS_QUALITY_FILTER");
    }
}
