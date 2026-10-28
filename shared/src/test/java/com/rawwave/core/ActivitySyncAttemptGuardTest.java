package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivitySyncAttemptGuardTest {
    @Test public void captureFinishedAttemptSuppressesSameScreenAutoRetry() {
        ActivitySyncAttemptGuard guard = new ActivitySyncAttemptGuard();
        assertTrue(guard.allowOpenAutoSync());
        guard.markStarted(ActivitySyncPolicy.CAPTURE_FINISHED);
        assertFalse(guard.allowOpenAutoSync());
    }

    @Test public void freshExplicitOpenAllowsOneNewAttempt() {
        ActivitySyncAttemptGuard guard = new ActivitySyncAttemptGuard();
        guard.markStarted(ActivitySyncPolicy.CAPTURE_FINISHED);
        assertTrue(guard.resetForExplicitOpen());
        assertTrue(guard.allowOpenAutoSync());
        assertFalse(guard.resetForExplicitOpen());
    }

    @Test public void openingOrManualSyncDoesNotSuppressFutureOpeningSync() {
        ActivitySyncAttemptGuard guard = new ActivitySyncAttemptGuard();
        guard.markStarted(ActivitySyncPolicy.OPEN_IDLE_AUTO);
        guard.markStarted(ActivitySyncPolicy.USER_MANUAL);
        assertTrue(guard.allowOpenAutoSync());
    }
}
