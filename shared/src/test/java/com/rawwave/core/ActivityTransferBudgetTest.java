package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityTransferBudgetTest {
    @Test public void smallArchivesKeepTheInitialWindowAndDoNotExtend() {
        assertEquals(ActivityTransferBudget.INITIAL_MS, ActivityTransferBudget.sizeAwareDeadline(
            1_000L, ActivityTransferBudget.INITIAL_MS, 1L));
        assertFalse(ActivityTransferBudget.needsExtension(1L));
        assertEquals(0L, ActivityTransferBudget.leaseExtensionMs(1L));
        assertTrue(ActivityTransferBudget.leaseMs(1L) <= ActivityTransferBudget.initialLeaseMs());
    }

    @Test public void smallArchiveStillFitsHandshakeSendAndCommitInsideTheInitialWindow() {
        long bytes = 512L * 1024L;
        assertFalse(ActivityTransferBudget.needsExtension(bytes));
        assertTrue(ActivityTransferBudget.requiredMs(bytes) <= ActivityTransferBudget.INITIAL_MS);
        assertEquals(ActivityTransferBudget.BASE_TRANSFER_MS,
            ActivityTransferBudget.transferMs(ActivityTransferBudget.MIN_RATE_BYTES_PER_SECOND));
    }

    @Test public void observedLargeArchiveGetsOneSizeAwareExtension() {
        long bytes = 16_860_445L;
        long transfer = ActivityTransferBudget.transferMs(bytes);
        assertEquals(514_540L, transfer);
        assertTrue(transfer > WorkBudget.TRANSFER_MS);
        assertEquals(transfer + ActivityTransferBudget.COMMIT_MS, ActivityTransferBudget.sendMs(bytes));
        assertTrue(ActivityTransferBudget.receiveMs(bytes) > ActivityTransferBudget.sendMs(bytes));
        assertTrue(ActivityTransferBudget.needsExtension(bytes));
        long now = 10_000L;
        long extended = ActivityTransferBudget.sizeAwareDeadline(now, ActivityTransferBudget.INITIAL_MS, bytes);
        assertEquals(now + ActivityTransferBudget.requiredMs(bytes), extended);
        assertTrue(extended > ActivityTransferBudget.INITIAL_MS);
        assertTrue(ActivityTransferBudget.leaseMs(bytes) > WorkBudget.SYNC_LEASE_MS);
        assertTrue(ActivityTransferBudget.leaseMs(bytes) <= ActivityTransferBudget.WATCH_LEASE_MS);
        assertEquals(ActivityTransferBudget.leaseMs(bytes), ActivityTransferBudget.leaseExtensionMs(bytes));
    }

    @Test public void extensionNeverShortensAnExistingLaterDeadline() {
        long bytes = 16_860_445L;
        long later = ActivityTransferBudget.INITIAL_MS * 100L;
        assertEquals(later, ActivityTransferBudget.sizeAwareDeadline(1_000L, later, bytes));
    }

    @Test public void ceilingIsBoundedAndLeaseNeverExceedsTheWatchCeiling() {
        assertEquals(ActivityTransferBudget.MAX_TRANSFER_MS,
            ActivityTransferBudget.transferMs(ActivityTransferFiles.MAX_BYTES));
        long lease = ActivityTransferBudget.leaseMs(ActivityTransferFiles.MAX_BYTES);
        assertTrue(lease <= ActivityTransferBudget.WATCH_LEASE_MS);
        assertTrue(lease > ActivityTransferBudget.MAX_TRANSFER_MS);
    }

    @Test public void longPackagingGetsItsOwnBudgetBeforeTheTransferWindow() {
        long raw = 432_055_330L;
        long packaging = ActivityTransferBudget.packagingMs(raw);
        assertTrue(packaging > ActivityTransferBudget.INITIAL_MS);
        assertTrue(packaging <= ActivityTransferBudget.PACKAGING_MAX_MS);
        assertEquals(packaging, ActivityTransferBudget.prepareBudgetMs(raw));
        assertEquals(packaging + ActivityTransferBudget.PACKAGING_MARGIN_MS,
            ActivityTransferBudget.prepareLeaseMs(raw));
        assertTrue(ActivityTransferBudget.prepareLeaseMs(raw) < ActivityTransferBudget.WATCH_LEASE_MS);
        // A small activity keeps the short 80-second initial window.
        assertEquals(ActivityTransferBudget.INITIAL_MS, ActivityTransferBudget.prepareBudgetMs(1L));
        assertEquals(ActivityTransferBudget.INITIAL_MS + ActivityTransferBudget.PACKAGING_MARGIN_MS,
            ActivityTransferBudget.prepareLeaseMs(1L));
    }

    @Test public void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ActivityTransferBudget.transferMs(0));
        assertThrows(IllegalArgumentException.class,
            () -> ActivityTransferBudget.transferMs(ActivityTransferFiles.MAX_BYTES + 1));
    }
}
