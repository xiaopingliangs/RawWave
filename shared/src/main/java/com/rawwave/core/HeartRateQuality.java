package com.rawwave.core;

import java.util.Map;

/**
 * Adds a plausibility flag to processed heart-rate rows. The SDK status codes observed so
 * far (1, 0, -10) have no public mapping in this project, so the raw status is preserved
 * and never renamed as motion artifact or invalid.
 */
public final class HeartRateQuality {
    private HeartRateQuality() {}

    public static final int PLAUSIBLE_MIN_BPM = 20;
    public static final int PLAUSIBLE_MAX_BPM = 250;

    public static void annotate(Map<String, Object> row) {
        Object raw = row.get("HEART_RATE");
        Boolean plausible = null;
        if (raw instanceof Number value) {
            double v = value.doubleValue();
            if (Double.isFinite(v)) plausible = v >= PLAUSIBLE_MIN_BPM && v <= PLAUSIBLE_MAX_BPM;
        }
        row.put("HR_VALUE_PLAUSIBLE", plausible);
        row.put("HR_PLAUSIBLE_RULE", "20 <= bpm <= 250; range check only, not a clinical validity judgment");
        row.put("HR_STATUS_SEMANTICS", "UNKNOWN; raw status retained without interpretation");
    }
}
