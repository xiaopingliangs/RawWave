package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class EdaQualityTest {
    @Test public void typicalValueIsNotFlagged() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("SKIN_CONDUCTANCE", 5.5);
        row.put("STATUS", 0);
        EdaQuality.annotate(row);
        assertEquals(false, row.get("EDA_OUTSIDE_TYPICAL_RANGE"));
        assertEquals(5.5, (Double) row.get("SKIN_CONDUCTANCE"), 0.0);
        assertEquals(0, row.get("STATUS"));
        assertEquals("SDK_STATUS_STORED_RAW_NOT_USED_AS_QUALITY_FILTER", row.get("EDA_STATUS_ROLE"));
    }

    @Test public void zeroAndHighValuesAreFlaggedButKept() {
        Map<String,Object> zero = new LinkedHashMap<>();
        zero.put("SKIN_CONDUCTANCE", 0.0);
        EdaQuality.annotate(zero);
        assertEquals(true, zero.get("EDA_OUTSIDE_TYPICAL_RANGE"));
        assertEquals(0.0, (Double) zero.get("SKIN_CONDUCTANCE"), 0.0);

        Map<String,Object> high = new LinkedHashMap<>();
        high.put("SKIN_CONDUCTANCE", 25.0);
        EdaQuality.annotate(high);
        assertEquals(true, high.get("EDA_OUTSIDE_TYPICAL_RANGE"));
        assertEquals(25.0, (Double) high.get("SKIN_CONDUCTANCE"), 0.0);
    }

    @Test public void missingValueStaysUnknown() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("STATUS", 0);
        EdaQuality.annotate(row);
        assertTrue(row.containsKey("EDA_OUTSIDE_TYPICAL_RANGE"));
        assertNull(row.get("EDA_OUTSIDE_TYPICAL_RANGE"));
        assertNull(row.get("SKIN_CONDUCTANCE"));
    }
}
