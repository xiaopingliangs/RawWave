package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoreDataBudgetTest {
    @Test public void noDataWindowMatchesTheExistingEightySecondIdlePolicy() {
        assertEquals(80_000L, CoreDataBudget.NO_DATA_MS);
        assertEquals(WorkBudget.IDLE_MS, CoreDataBudget.NO_DATA_MS);
        assertEquals(81_000L, CoreDataBudget.initialDeadline(1_000L));
    }

    @Test public void stopsOnlyAfterTheDeadlineWhenNoRowArrived() {
        long deadline = CoreDataBudget.initialDeadline(1_000L);
        assertFalse(CoreDataBudget.mustStop(deadline, deadline - 1L, false));
        assertTrue(CoreDataBudget.mustStop(deadline, deadline, false));
        assertFalse(CoreDataBudget.mustStop(deadline, deadline + 1L, true));
    }

    @Test public void disabledDeadlineNeverStops() {
        assertFalse(CoreDataBudget.mustStop(0L, Long.MAX_VALUE, false));
    }
}
