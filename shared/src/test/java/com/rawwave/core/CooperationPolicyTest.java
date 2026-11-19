package com.rawwave.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The separation between "nobody is here" and "the user is trying" is the whole point. */
public class CooperationPolicyTest {
    @Test public void noContactExitsAfterTheNoPresenceWindow() {
        assertEquals(CooperationPolicy.Next.WAIT,
            CooperationPolicy.decide(false, 45_000L, 0L, 44_999L));
        assertEquals(CooperationPolicy.Next.EXIT,
            CooperationPolicy.decide(false, 45_000L, 0L, 45_000L));
    }

    @Test public void confirmedContactNeverExitsAndAdvancesAtTheStageCap() {
        // Engagement arms the result clock; a failed attempt or a review must not end the session.
        assertEquals(CooperationPolicy.Next.WAIT,
            CooperationPolicy.decide(true, 0L, 120_000L, 119_999L));
        assertEquals(CooperationPolicy.Next.ADVANCE,
            CooperationPolicy.decide(true, 0L, 120_000L, 120_000L));
        // The no-presence deadline is irrelevant once engaged.
        assertEquals(CooperationPolicy.Next.WAIT,
            CooperationPolicy.decide(true, 1L, 120_000L, 60_000L));
    }

    @Test public void anUnknownPresenceDeadlineCannotExitBeforeItIsArmed() {
        assertEquals(CooperationPolicy.Next.WAIT,
            CooperationPolicy.decide(false, 0L, 0L, Long.MAX_VALUE));
    }

    @Test public void offBodyShortensTheWaitButUnknownWearKeepsTheFullWindow() {
        assertEquals(30_000L, CooperationPolicy.noPresenceWindowMs(Boolean.FALSE));
        assertEquals(45_000L, CooperationPolicy.noPresenceWindowMs(Boolean.TRUE));
        assertEquals(45_000L, CooperationPolicy.noPresenceWindowMs(null));
    }

    @Test public void stageCapsMatchTheMeasurementCost() {
        assertEquals(120_000L, CooperationPolicy.resultWindowMs("BIA"));
        assertEquals(120_000L, CooperationPolicy.resultWindowMs("ECG"));
        assertEquals(60_000L, CooperationPolicy.resultWindowMs("SPO2"));
        assertEquals(45_000L, CooperationPolicy.resultWindowMs("PPG"));
        assertTrue(CooperationPolicy.waitsForWear("SPO2"));
        assertTrue(CooperationPolicy.waitsForWear("PPG"));
        assertTrue(CooperationPolicy.waitsForWear("BIA"));
        assertFalse(CooperationPolicy.waitsForWear("ECG"));
    }
}
