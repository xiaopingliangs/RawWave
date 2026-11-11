package com.rawwave.core;

import static org.junit.Assert.assertEquals;
import java.util.List;
import org.junit.Test;

public class ActivityOrderTest {
    @Test public void savedOrderWinsAndNewIdsStayAtTheEnd() {
        assertEquals(List.of("b", "a", "c"), ActivityOrder.apply(List.of("a", "b", "c"), List.of("b")));
        assertEquals(List.of("c", "a", "b"), ActivityOrder.apply(List.of("a", "b", "c"), List.of("c", "gone")));
    }

    @Test public void duplicateOrMissingSavedIdsAreIgnored() {
        assertEquals(List.of("a", "b"), ActivityOrder.apply(List.of("a", "b"), List.of("a", "a", "x")));
        assertEquals(List.of("a", "b"), ActivityOrder.apply(List.of("a", "b"), List.of()));
    }

    @Test public void moveClampsAtBothEnds() {
        assertEquals(List.of("b", "a", "c"), ActivityOrder.move(List.of("a", "b", "c"), "b", -1));
        assertEquals(List.of("a", "c", "b"), ActivityOrder.move(List.of("a", "b", "c"), "b", 1));
        assertEquals(List.of("a", "b", "c"), ActivityOrder.move(List.of("a", "b", "c"), "a", -5));
        assertEquals(List.of("a", "b", "c"), ActivityOrder.move(List.of("a", "b", "c"), "c", 5));
        assertEquals(List.of("c", "a", "b"), ActivityOrder.move(List.of("a", "b", "c"), "c", -2));
    }
}
