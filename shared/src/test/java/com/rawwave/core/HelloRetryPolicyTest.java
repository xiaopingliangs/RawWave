package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class HelloRetryPolicyTest {
    @Test public void helloRepeatsOnlyWhileNotReadyAndWithinBudget() {
        assertTrue(HelloRetryPolicy.shouldSend(false, 1L));
        assertFalse(HelloRetryPolicy.shouldSend(true, 10_000L));
        assertFalse(HelloRetryPolicy.shouldSend(false, 0L));
    }

    @Test public void waitSliceIsBoundedAndNeverNegative() {
        assertEquals(2_000L, HelloRetryPolicy.waitSlice(9_000L));
        assertEquals(350L, HelloRetryPolicy.waitSlice(350L));
        assertEquals(0L, HelloRetryPolicy.waitSlice(-1L));
    }
}
