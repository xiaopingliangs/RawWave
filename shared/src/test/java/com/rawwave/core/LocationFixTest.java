package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LocationFixTest {
    @Test public void platformFixFieldsAreRetainedAndMissingOptionalsStayNull() {
        var fix = new LocationFix.Fix("gps", 31.2304, 121.4737, 4.5, 3.0, null,
            null, null, null, null, 123456789L, 987654321L, true);
        Map<String,Object> row = LocationFix.row(fix, 111L, 222L, 7, 0, 1);
        assertEquals("gps", row.get("provider"));
        assertEquals(31.2304, (Double) row.get("GPS_LATITUDE"), 0.0);
        assertEquals(121.4737, (Double) row.get("GPS_LONGITUDE"), 0.0);
        assertEquals(4.5, (Double) row.get("GPS_ALTITUDE_METERS"), 0.0);
        assertEquals(3.0, (Double) row.get("GPS_HORIZONTAL_ACCURACY_METERS"), 0.0);
        assertTrue(row.containsKey("GPS_VERTICAL_ACCURACY_METERS"));
        assertNull(row.get("GPS_VERTICAL_ACCURACY_METERS"));
        assertNull(row.get("GPS_SPEED_METERS_PER_SECOND"));
        assertNull(row.get("GPS_BEARING_DEGREES"));
        assertEquals(987654321L, row.get("sensorTimestampNs"));
        assertEquals(987654321L / 1_000_000L, row.get("sdkTimestamp"));
        assertEquals(123456789L, row.get("utcTimeMs"));
        assertEquals(111L, row.get("receivedUtcMs"));
        assertEquals(222L, row.get("receivedElapsedMs"));
        assertEquals(7L, row.get("captureCallbackSequence"));
        assertEquals(true, row.get("mock"));
        assertEquals("PLATFORM_LOCATION_FIX_NOT_RAW_GNSS", row.get("locationKind"));
    }

    @Test public void nonFiniteValuesAreEncodedNotDroppedOrRepaired() {
        var fix = new LocationFix.Fix("gps", Double.NaN, 121.0, null, null,
            Double.POSITIVE_INFINITY, null, null, null, null, null, 1L, false);
        Map<String,Object> row = LocationFix.row(fix, 1L, 2L, 1, 0, 1);
        assertEquals("NaN", row.get("GPS_LATITUDE"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GPS_LATITUDE_encoding"));
        assertEquals("Infinity", row.get("GPS_VERTICAL_ACCURACY_METERS"));
        assertEquals("NON_FINITE_AS_STRING", row.get("GPS_VERTICAL_ACCURACY_METERS_encoding"));
        assertNull(row.get("utcTimeMs"));
    }

    @Test public void invalidProviderOrCallbackMetadataIsRejected() {
        var valid = new LocationFix.Fix("gps", 1.0, 2.0, null, null, null,
            null, null, null, null, null, 1L, false);
        assertThrows(IllegalArgumentException.class, () -> LocationFix.row(
            new LocationFix.Fix("", 1.0, 2.0, null, null, null, null, null, null, null, null, 1L, false),
            1L, 2L, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> LocationFix.row(valid, 1L, 2L, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> LocationFix.row(valid, 1L, 2L, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> LocationFix.row(valid, 1L, 2L, 1, 0, 0));
    }
}
