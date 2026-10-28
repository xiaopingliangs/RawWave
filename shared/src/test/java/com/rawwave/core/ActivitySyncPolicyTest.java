package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivitySyncPolicyTest {
    @Test public void openingBacklogSyncNeverOwnsTheCaptureHome() {
        assertTrue(ActivitySyncPolicy.isNonBlockingHomeSync(ActivitySyncPolicy.OPEN_IDLE_AUTO));
        assertFalse(ActivitySyncPolicy.exitAfterSuccessfulSync(ActivitySyncPolicy.OPEN_IDLE_AUTO));
        assertTrue(ActivitySyncPolicy.captureMayPreempt(ActivitySyncPolicy.OPEN_IDLE_AUTO));
    }

    @Test public void completedCaptureSyncExitsOnlyAfterSuccess() {
        assertFalse(ActivitySyncPolicy.isNonBlockingHomeSync(ActivitySyncPolicy.CAPTURE_FINISHED));
        assertTrue(ActivitySyncPolicy.exitAfterSuccessfulSync(ActivitySyncPolicy.CAPTURE_FINISHED));
        assertTrue(ActivitySyncPolicy.captureMayPreempt(ActivitySyncPolicy.CAPTURE_FINISHED));
    }

    @Test public void explicitManualSyncMayExitAfterSuccess() {
        assertTrue(ActivitySyncPolicy.exitAfterSuccessfulSync(ActivitySyncPolicy.USER_MANUAL));
    }

    @Test public void unknownOrForgedTriggerCannotCloseTheUi() {
        assertEquals(ActivitySyncPolicy.UNKNOWN, ActivitySyncPolicy.normalize("CAPTURE_FINISHED_EXTRA"));
        assertFalse(ActivitySyncPolicy.exitAfterSuccessfulSync("CAPTURE_FINISHED_EXTRA"));
        assertTrue(ActivitySyncPolicy.captureMayPreempt("CAPTURE_FINISHED_EXTRA"));
    }
}
