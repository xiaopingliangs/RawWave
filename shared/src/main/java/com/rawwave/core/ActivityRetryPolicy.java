package com.rawwave.core;

/**
 * Bounded retry policy for one watch-side raw-activity sync request.
 *
 * <p>The watch is the sender and owns the pending archive. A transport stall or channel failure
 * leaves the archive pending, so one automatic retry is safe: the receiver accepts a new sender
 * window and re-verifies the same archive/transfer id. A user stop, a successful transfer, or an
 * already-exhausted request never retries.</p>
 */
public final class ActivityRetryPolicy {
    /** One first attempt plus two automatic retries; transient phone-wake failures are common. */
    public static final int MAX_ATTEMPTS = 3;
    /**
     * Pause before the next attempt. Long enough for the phone to leave a Doze gap and
     * re-register its Wearable node, short enough to finish inside the sync lease.
     */
    public static final long RETRY_DELAY_MS = 8_000L;

    private ActivityRetryPolicy() {}

    /** True only after a failed attempt when another attempt is still allowed. */
    public static boolean shouldRetry(int failedAttempts) {
        return failedAttempts >= 1 && failedAttempts < MAX_ATTEMPTS;
    }
}
