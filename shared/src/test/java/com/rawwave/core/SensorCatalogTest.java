package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SensorCatalogTest {
    @Test public void normalizationIsStableAndDeduplicated() {
        assertEquals(List.of("HEART_RATE","PPG","EDA"),
            SensorCatalog.normalizeCoreSources(List.of("EDA","PPG","PPG","HEART_RATE")));
    }
    @Test public void unknownAndEmptySelectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->SensorCatalog.normalizeCoreSources(List.of()));
        assertThrows(IllegalArgumentException.class,()->SensorCatalog.normalizeCoreSources(List.of("UNKNOWN")));
    }
    @Test public void legacyNullSelectionKeepsOldBehavior() {
        assertEquals(List.of("HEART_RATE","ACCELEROMETER"),SensorCatalog.normalizeCoreSources(null));
    }
    @Test public void ppgQualityRequiresAllThreeUnmodifiedSdkChannels() {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("sdkTimestamp",1000L);row.put("PPG_GREEN",10);row.put("PPG_IR",20);row.put("PPG_RED",30);
        row.put("GREEN_STATUS",0);row.put("IR_STATUS",1);row.put("RED_STATUS",2);
        String before=row.toString();var tracker=new StreamQualityTracker("PPG");tracker.observe(List.of(row));
        assertEquals(before,row.toString());assertEquals(1L,tracker.summary().get("valueRows"));
        assertEquals(3,((Map<?,?>)tracker.summary().get("statusCounts")).size());
    }
    @Test public void newSensorsHaveIndependentValueChecks() {
        var skin=new StreamQualityTracker("SKIN_TEMPERATURE");
        skin.observe(List.of(Map.of("sdkTimestamp",1,"OBJECT_TEMPERATURE",32.0f,"AMBIENT_TEMPERATURE",24.0f,"STATUS",0)));
        var eda=new StreamQualityTracker("EDA");
        eda.observe(List.of(Map.of("sdkTimestamp",1,"SKIN_CONDUCTANCE",2.5f,"STATUS",0)));
        assertEquals(1L,skin.summary().get("valueRows"));assertEquals(1L,eda.summary().get("valueRows"));
    }
}
