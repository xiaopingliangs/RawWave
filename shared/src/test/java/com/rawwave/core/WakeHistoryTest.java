package com.rawwave.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class WakeHistoryTest {
    @Test public void duplicateDoesNotRenewOrReorder() {
        WakeHistory h = new WakeHistory(List.of("a","b"));
        assertFalse(h.accept("a")); assertEquals(List.of("a","b"),h.snapshot());
    }
    @Test public void moreThan64SessionsNeverPermanentlyDisablesReception() {
        WakeHistory h = new WakeHistory(List.of());
        for(int i=0;i<1000;i++) assertTrue(h.accept("window-"+i));
        assertEquals(64,h.snapshot().size()); assertEquals("window-936",h.snapshot().get(0));
    }
    @Test public void restartRetainsRecentDeduplication() {
        WakeHistory h = new WakeHistory(List.of("first")); h.accept("second");
        WakeHistory restored = new WakeHistory(h.snapshot());
        assertFalse(restored.accept("second")); assertTrue(restored.accept("third"));
    }
    @Test public void snapshotCannotMutateLedger() {
        WakeHistory h = new WakeHistory(List.of("first"));
        assertThrows(UnsupportedOperationException.class,()->h.snapshot().clear());
        assertFalse(h.accept("first"));
    }
}
