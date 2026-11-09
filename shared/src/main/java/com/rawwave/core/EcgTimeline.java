package com.rawwave.core;

import java.util.*;

/**
 * ECG callback timing evidence. The Samsung SDK returns 10 ECG samples per callback and
 * puts one millisecond timestamp on the callback, not on each sample. Verified from captured
 * data: all samples in a callback share the same sdkTimestamp and callbacks arrive about
 * every 20 ms. The original sdkTimestamp field is never modified; this class only adds
 * clearly named derived fields.
 */
public final class EcgTimeline {
    private EcgTimeline() {}

    public static final double SAMPLE_RATE_HZ = 500.0;
    public static final long SAMPLE_PERIOD_MS = 2L;
    public static final String KIND_RECONSTRUCTED =
        "RECONSTRUCTED_CALLBACK_ANCHOR_PLUS_INDEX_AT_500_HZ";
    public static final String KIND_PER_SAMPLE =
        "SDK_PER_SAMPLE_TIMESTAMP";
    public static final String KIND_UNPROVEN =
        "NOT_RECONSTRUCTED_TIMESTAMP_SHAPE_UNPROVEN";
    public static final String KIND_INVALID_ANCHOR =
        "NOT_RECONSTRUCTED_INVALID_CALLBACK_ANCHOR";

    /**
     * Adds derived per-sample timing in milliseconds. All rows must belong to one callback,
     * in index order. A shared callback timestamp is expanded by the documented 500 Hz sample
     * period. Strictly increasing timestamps are kept as-is. Any other shape stays null.
     */
    public static void annotate(List<? extends Map<String, Object>> rows, List<Long> timestampsMs) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(timestampsMs, "timestampsMs");
        if (rows.size() != timestampsMs.size())
            throw new IllegalArgumentException("ECG_TIMESTAMP_COUNT_MISMATCH");
        if (rows.isEmpty()) return;

        boolean allEqual = true;
        for (Long ts : timestampsMs) {
            if (ts == null) throw new IllegalArgumentException("ECG_TIMESTAMP_NULL");
            if (!ts.equals(timestampsMs.get(0))) allEqual = false;
        }
        boolean strictlyIncreasing = true;
        for (int i = 1; i < timestampsMs.size(); i++)
            if (timestampsMs.get(i) <= timestampsMs.get(i - 1)) strictlyIncreasing = false;

        long anchor = timestampsMs.get(0);
        if (anchor <= 0) {
            for (Map<String, Object> row : rows) {
                row.put("ECG_CALLBACK_ANCHOR_MS", anchor);
                row.put("ECG_SAMPLE_RATE_HZ", SAMPLE_RATE_HZ);
                row.put("ECG_SAMPLE_TIMESTAMP_MS", null);
                row.put("ECG_SAMPLE_OFFSET_MS", null);
                row.put("ECG_SAMPLE_TIMESTAMP_KIND", KIND_INVALID_ANCHOR);
                row.put("ECG_SAMPLE_TIMESTAMP_NOTE",
                    "Callback anchor is missing or non-positive; no sample time invented");
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            row.put("ECG_CALLBACK_ANCHOR_MS", anchor);
            row.put("ECG_SAMPLE_RATE_HZ", SAMPLE_RATE_HZ);
            if (allEqual && rows.size() > 1) {
                row.put("ECG_SAMPLE_TIMESTAMP_MS", anchor + i * SAMPLE_PERIOD_MS);
                row.put("ECG_SAMPLE_OFFSET_MS", i * (double) SAMPLE_PERIOD_MS);
                row.put("ECG_SAMPLE_TIMESTAMP_KIND", KIND_RECONSTRUCTED);
                row.put("ECG_SAMPLE_TIMESTAMP_NOTE",
                    "SDK gives one timestamp per callback; per-sample time is anchor + index * 2 ms at 500 Hz");
            } else if (strictlyIncreasing) {
                row.put("ECG_SAMPLE_TIMESTAMP_MS", timestampsMs.get(i));
                row.put("ECG_SAMPLE_OFFSET_MS", (double) (timestampsMs.get(i) - anchor));
                row.put("ECG_SAMPLE_TIMESTAMP_KIND", KIND_PER_SAMPLE);
                row.put("ECG_SAMPLE_TIMESTAMP_NOTE", "SDK supplied a distinct timestamp for this sample");
            } else {
                row.put("ECG_SAMPLE_TIMESTAMP_MS", null);
                row.put("ECG_SAMPLE_OFFSET_MS", null);
                row.put("ECG_SAMPLE_TIMESTAMP_KIND", KIND_UNPROVEN);
                row.put("ECG_SAMPLE_TIMESTAMP_NOTE",
                    "Timestamp order is neither one shared callback time nor strictly increasing; no sample time invented");
            }
        }
    }

    /**
     * Copies the callback-level LEAD_OFF value from row 0 to every row as CALLBACK_LEAD_OFF.
     * The raw LEAD_OFF field stays only where the SDK placed it (index 0).
     */
    public static void propagateLeadOff(List<? extends Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        Object leadOff = rows.get(0).get("LEAD_OFF");
        for (Map<String, Object> row : rows) {
            row.put("CALLBACK_LEAD_OFF", leadOff);
            row.put("CALLBACK_LEAD_OFF_KIND", "CALLBACK_LEVEL_VALUE_FROM_INDEX_0");
            row.put("CALLBACK_LEAD_OFF_NOTE",
                "Raw LEAD_OFF remains stored only on callback index 0; this field makes the callback flag explicit on every sample");
        }
    }
}
