package com.rawwave.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActivityRetryPolicyTest {
    @Test public void firstFailureAllowsOneRetry() {
        assertTrue(ActivityRetryPolicy.shouldRetry(1));
    }

    @Test public void secondFailureIsTerminal() {
        assertFalse(ActivityRetryPolicy.shouldRetry(2));
    }

    @Test public void noRetryBeforeAnyAttempt() {
        assertFalse(ActivityRetryPolicy.shouldRetry(0));
    }

    @Test public void retryDelayIsShortAndPositive() {
        assertTrue(ActivityRetryPolicy.RETRY_DELAY_MS > 0L);
        assertTrue(ActivityRetryPolicy.RETRY_DELAY_MS <= 10_000L);
    }

    @Test public void maxAttemptsIsExactlyTwo() {
        assertTrue(ActivityRetryPolicy.MAX_ATTEMPTS == 2);
    }
}
