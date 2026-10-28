package com.rawwave.core;

/** Pure bounds for repeating HELLO inside one transfer attempt while the phone is waking. */
public final class HelloRetryPolicy {
    public static final long READY_BUDGET_MS = 15_000L;
    public static final long INTERVAL_MS = 2_000L;

    private HelloRetryPolicy() {}

    public static boolean shouldSend(boolean ready, long remainingMs) {
        return !ready && remainingMs > 0L;
    }

    public static long waitSlice(long remainingMs) {
        if (remainingMs <= 0L) return 0L;
        return Math.min(INTERVAL_MS, remainingMs);
    }
}
