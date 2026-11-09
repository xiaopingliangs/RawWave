package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class EcgTimelineTest {
    private List<Map<String,Object>> rows(int n) {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("sdkTimestamp", 1794102302417L);
            row.put("ECG_MV", 100.0 + i);
            rows.add(row);
        }
        return rows;
    }

    @Test public void sharedCallbackTimestampIsExpandedAtFiveHundredHertz() {
        List<Map<String,Object>> rows = rows(10);
        List<Long> stamps = new ArrayList<>(Collections.nCopies(10, 1794102302417L));
        EcgTimeline.annotate(rows, stamps);
        for (int i = 0; i < 10; i++) {
            assertEquals(1794102302417L + i * 2L, rows.get(i).get("ECG_SAMPLE_TIMESTAMP_MS"));
            assertEquals(i * 2.0, (Double) rows.get(i).get("ECG_SAMPLE_OFFSET_MS"), 0.0);
            assertEquals(EcgTimeline.KIND_RECONSTRUCTED, rows.get(i).get("ECG_SAMPLE_TIMESTAMP_KIND"));
            assertEquals(500.0, (Double) rows.get(i).get("ECG_SAMPLE_RATE_HZ"), 0.0);
            assertEquals(1794102302417L, rows.get(i).get("sdkTimestamp"));
            assertEquals(100.0 + i, (Double) rows.get(i).get("ECG_MV"), 0.0);
        }
    }

    @Test public void distinctIncreasingTimestampsAreKept() {
        List<Map<String,Object>> rows = rows(3);
        EcgTimeline.annotate(rows, List.of(1000L, 1002L, 1004L));
        assertEquals(1000L, rows.get(0).get("ECG_SAMPLE_TIMESTAMP_MS"));
        assertEquals(1002L, rows.get(1).get("ECG_SAMPLE_TIMESTAMP_MS"));
        assertEquals(1004L, rows.get(2).get("ECG_SAMPLE_TIMESTAMP_MS"));
        assertEquals(EcgTimeline.KIND_PER_SAMPLE, rows.get(1).get("ECG_SAMPLE_TIMESTAMP_KIND"));
    }

    @Test public void unprovenTimestampShapeIsNotInvented() {
        List<Map<String,Object>> rows = rows(3);
        EcgTimeline.annotate(rows, List.of(1000L, 999L, 1001L));
        assertNull(rows.get(0).get("ECG_SAMPLE_TIMESTAMP_MS"));
        assertNull(rows.get(1).get("ECG_SAMPLE_OFFSET_MS"));
        assertEquals(EcgTimeline.KIND_UNPROVEN, rows.get(2).get("ECG_SAMPLE_TIMESTAMP_KIND"));
        assertEquals(1000L, rows.get(0).get("ECG_CALLBACK_ANCHOR_MS"));
    }

    @Test public void callbackLeadOffIsExplicitOnEveryRowWithoutMovingRawField() {
        List<Map<String,Object>> rows = rows(10);
        rows.get(0).put("LEAD_OFF", 5);
        EcgTimeline.propagateLeadOff(rows);
        for (Map<String,Object> row : rows) {
            assertEquals(5, row.get("CALLBACK_LEAD_OFF"));
            assertEquals("CALLBACK_LEVEL_VALUE_FROM_INDEX_0", row.get("CALLBACK_LEAD_OFF_KIND"));
        }
        assertTrue(rows.get(0).containsKey("LEAD_OFF"));
        for (int i = 1; i < rows.size(); i++) assertFalse(rows.get(i).containsKey("LEAD_OFF"));
    }

    @Test public void missingAnchorIsNotTurnedInto1970() {
        List<Map<String,Object>> rows = rows(10);
        EcgTimeline.annotate(rows, new ArrayList<>(Collections.nCopies(10, 0L)));
        for (Map<String,Object> row : rows) {
            assertNull(row.get("ECG_SAMPLE_TIMESTAMP_MS"));
            assertEquals(EcgTimeline.KIND_INVALID_ANCHOR, row.get("ECG_SAMPLE_TIMESTAMP_KIND"));
        }
    }

    @Test public void mismatchedTimestampCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> EcgTimeline.annotate(rows(2), List.of(1L)));
        assertThrows(IllegalArgumentException.class,
            () -> EcgTimeline.annotate(rows(2), Arrays.asList(1L, null)));
    }
}
