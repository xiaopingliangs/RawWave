package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.rawwave.core.RunDeadlines.Kind.*;

public class RunDeadlinesTest {
    @Test public void productionSyncBudgetLeavesCleanupWithin80Seconds() {
        assertTrue(WorkBudget.TRANSFER_MS < WorkBudget.SYNC_LEASE_MS);
        assertEquals(80000, WorkBudget.SYNC_LEASE_MS + WorkBudget.LOCK_CLEANUP_MARGIN_MS);
        deadlines.begin(TRANSFER, WorkBudget.SYNC_LEASE_MS);
        now = 76000; assertFalse(deadlines.touchIdle(WorkBudget.IDLE_MS));
        now = 77000; assertEquals(TRANSFER, deadlines.expired().kind());
        assertFalse(deadlines.begin(TRANSFER, WorkBudget.SYNC_LEASE_MS));
    }
    @Test public void productionIdleBudgetAndSuccessNeedNoWait() {
        deadlines.touchIdle(WorkBudget.IDLE_MS);
        now = 79999; assertNull(deadlines.expired());
        now = 80000; assertEquals(IDLE, deadlines.expired().kind());
        deadlines.close(); assertEquals(Long.MAX_VALUE, deadlines.delayMs());
    }
    private long now;
    private final RunDeadlines deadlines = new RunDeadlines(() -> now);
    @Test public void sleepTimeCountsEvenWithoutTimerCallbacks() {
        deadlines.touchIdle(175000); now=4605000;
        assertEquals(IDLE,deadlines.expired().kind());
        assertEquals(4430000,deadlines.expired().lateMs());
    }
    @Test public void resumeTouchCannotResetExpiredDeadline() {
        deadlines.touchIdle(175000); now=175000;
        assertFalse(deadlines.touchIdle(175000)); assertEquals(175000,deadlines.expired().deadlineMs());
    }
    @Test public void foregroundInteractionCanRenewOnlyIdle() {
        deadlines.touchIdle(175000); now=50000; assertTrue(deadlines.touchIdle(175000));
        assertEquals(Long.valueOf(225000),deadlines.snapshot().get(IDLE));
    }
    @Test public void transferIsNotRenewedByTouchesOrRepeatedBegin() {
        deadlines.begin(TRANSFER,175000); now=100000;
        assertFalse(deadlines.touchIdle(175000)); deadlines.begin(TRANSFER,175000);
        assertEquals(Long.valueOf(175000),deadlines.snapshot().get(TRANSFER));
    }
    @Test public void exportCannotExtendExistingTransfer() {
        deadlines.begin(TRANSFER,175000); now=160000; deadlines.begin(EXPORT,58000);
        now=175000; assertEquals(TRANSFER,deadlines.expired().kind());
        assertFalse(deadlines.finishExport(175000));
    }
    @Test public void failedTransferStillKeepsItsDeadlineDuringExport() {
        deadlines.begin(TRANSFER,175000); now=1000; deadlines.begin(EXPORT,58000);
        now=2000; assertTrue(deadlines.finishExport(175000));
        assertEquals(1,deadlines.snapshot().size()); assertEquals(173000,deadlines.delayMs());
    }
    @Test public void completedStandaloneExportStartsIdleBudget() {
        deadlines.touchIdle(175000); deadlines.begin(EXPORT,58000); now=3000;
        assertTrue(deadlines.finishExport(175000)); assertEquals(175000,deadlines.delayMs());
    }
    @Test public void successClosesWithoutAnAdditionalDelay() {
        deadlines.begin(TRANSFER,175000); now=3000; deadlines.close();
        assertTrue(deadlines.snapshot().isEmpty()); assertEquals(Long.MAX_VALUE,deadlines.delayMs());
    }
    @Test public void cancelledTransferReturnsToIdleWithoutKeepingOldDeadline() {
        deadlines.begin(TRANSFER,175000); now=3000;
        assertTrue(deadlines.finishTransfer(175000));
        assertEquals(1,deadlines.snapshot().size());
        assertEquals(Long.valueOf(178000),deadlines.snapshot().get(IDLE));
    }
    @Test public void expiredWindowCannotStartNewWork() {
        deadlines.touchIdle(175000); now=180000;
        assertFalse(deadlines.begin(EXPORT,58000));
    }
    @Test public void closedRuntimeRejectsLateCallbacks() {
        deadlines.begin(TRANSFER,175000); deadlines.close();
        assertFalse(deadlines.begin(TRANSFER,175000)); assertFalse(deadlines.touchIdle(175000));
        assertFalse(deadlines.finishExport(175000)); assertNull(deadlines.expired());
        assertEquals(Long.MAX_VALUE,deadlines.delayMs());
    }
    @Test public void staleTimerHintRecomputesRemainingDelay() {
        deadlines.touchIdle(175000); now=100000; deadlines.touchIdle(175000); now=175000;
        assertNull(deadlines.expired()); assertEquals(100000,deadlines.delayMs());
    }
    @Test public void exactBoundaryAndInvalidBudgets() {
        assertThrows(IllegalArgumentException.class,()->deadlines.begin(EXPORT,0));
        deadlines.begin(EXPORT,58000); now=57999; assertNull(deadlines.expired());
        now=58000; assertEquals(EXPORT,deadlines.expired().kind());
    }
    @Test public void quiescentIdlePageKeepsItsOriginalDeadlineAcrossSleep() {
        deadlines.touchIdle(175000); now=3600000;
        assertFalse(deadlines.resumeIdle(175000)); assertEquals(IDLE,deadlines.expired().kind());
        assertEquals(175000,deadlines.expired().deadlineMs());
    }
    @Test public void returningBeforeIdleDeadlineDoesNotRenewIt() {
        deadlines.touchIdle(175000); now=50000;
        assertTrue(deadlines.resumeIdle(175000)); assertNull(deadlines.expired());
        assertEquals(125000,deadlines.delayMs());
    }
    @Test public void resumeNeverRevivesExpiredWork() {
        deadlines.begin(TRANSFER,175000); now=3600000;
        assertFalse(deadlines.resumeIdle(175000)); assertEquals(TRANSFER,deadlines.expired().kind());
    }
    @Test public void resumeNeverRevivesExportOrClosedRuntime() {
        deadlines.begin(EXPORT,58000); now=60000;
        assertFalse(deadlines.resumeIdle(175000)); deadlines.close();
        assertFalse(deadlines.resumeIdle(175000));
    }
}

