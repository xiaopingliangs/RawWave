package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HeartRateQualityTest {
    @Test public void plausibleValueKeepsRawStatus() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("HEART_RATE", 72);
        row.put("HEART_RATE_STATUS", 1);
        HeartRateQuality.annotate(row);
        assertEquals(true, row.get("HR_VALUE_PLAUSIBLE"));
        assertEquals(72, row.get("HEART_RATE"));
        assertEquals(1, row.get("HEART_RATE_STATUS"));
        assertEquals("UNKNOWN; raw status retained without interpretation", row.get("HR_STATUS_SEMANTICS"));
    }

    @Test public void implausibleValuesAreFlaggedNotDropped() {
        Map<String,Object> low = new LinkedHashMap<>();
        low.put("HEART_RATE", 0);
        HeartRateQuality.annotate(low);
        assertEquals(false, low.get("HR_VALUE_PLAUSIBLE"));
        assertEquals(0, low.get("HEART_RATE"));

        Map<String,Object> high = new LinkedHashMap<>();
        high.put("HEART_RATE", 300);
        HeartRateQuality.annotate(high);
        assertEquals(false, high.get("HR_VALUE_PLAUSIBLE"));
    }

    @Test public void unknownStatusIsNeverRenamed() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("HEART_RATE", 80);
        row.put("HEART_RATE_STATUS", -10);
        HeartRateQuality.annotate(row);
        assertEquals(-10, row.get("HEART_RATE_STATUS"));
        assertFalse(row.containsKey("motionArtifact"));
    }

    @Test public void missingHeartRateStaysUnknown() {
        Map<String,Object> row = new LinkedHashMap<>();
        HeartRateQuality.annotate(row);
        assertTrue(row.containsKey("HR_VALUE_PLAUSIBLE"));
        assertNull(row.get("HR_VALUE_PLAUSIBLE"));
    }
}
