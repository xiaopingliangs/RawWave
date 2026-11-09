package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DeviceStateTest {
    @Test public void rowRetainsBatteryScreenAndClockContext() {
        Map<String,Object> row = DeviceState.row(87, "discharging", "none", 312, 4012,
            true, false, "on", 111L, 222L, "CORE-1");
        assertEquals("DEVICE_STATE_SAMPLE", row.get("kind"));
        assertEquals(87, row.get("batteryPercent"));
        assertEquals("discharging", row.get("batteryStatus"));
        assertEquals("none", row.get("batteryPlugged"));
        assertEquals(312, row.get("batteryTemperatureTenthsC"));
        assertEquals(31.2, (Double) row.get("batteryTemperatureC"), 0.0);
        assertEquals(4012, row.get("batteryVoltageMv"));
        assertEquals(true, row.get("screenInteractive"));
        assertEquals(false, row.get("keyguardLocked"));
        assertEquals("on", row.get("displayState"));
        assertEquals(111L, row.get("utcMs"));
        assertEquals(222L, row.get("elapsedMs"));
        assertEquals("CORE-1", row.get("phase"));
    }

    @Test public void unknownValuesRemainNull() {
        Map<String,Object> row = DeviceState.row(null, "unknown", "none", null, null,
            null, null, null, 1L, 2L, "PRE-1");
        assertTrue(row.containsKey("batteryPercent"));
        assertNull(row.get("batteryPercent"));
        assertNull(row.get("batteryTemperatureC"));
        assertNull(row.get("screenInteractive"));
        assertNull(row.get("displayState"));
    }

    @Test public void invalidBatteryOrPhaseIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> DeviceState.row(101, "full", "ac", null, null, true, false, "on", 1L, 2L, "CORE-1"));
        assertThrows(IllegalArgumentException.class,
            () -> DeviceState.row(50, "full", "ac", null, null, true, false, "on", 1L, 2L, ""));
    }
}
