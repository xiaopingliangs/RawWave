package com.rawwave.core;

/**
 * Power policy for the initial CORE window before any raw row arrives.
 *
 * <p>This is not a clinical validity judgment. Any non-empty row from a core source is enough
 * to show that the user did not simply forget to end an idle activity; after that, the normal
 * activity lifecycle owns the session.</p>
 */
public final class CoreDataBudget {
    public static final int CONTRACT_VERSION = 1;
    public static final long NO_DATA_MS = WorkBudget.IDLE_MS;

    private CoreDataBudget() {}

    public static long initialDeadline(long nowMs) {
        return nowMs + NO_DATA_MS;
    }

    public static boolean mustStop(long deadlineMs, long nowMs, boolean dataObserved) {
        return !dataObserved && deadlineMs > 0L && nowMs >= deadlineMs;
    }
}
