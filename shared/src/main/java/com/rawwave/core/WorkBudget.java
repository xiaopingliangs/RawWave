package com.rawwave.core;

/** Power policy for idle UI, one sync attempt, and the initial CORE no-data window. */
public final class WorkBudget {
    private WorkBudget() {}
    public static final long IDLE_MS = 80_000;
    public static final long TRANSFER_MS = 75_000;
    public static final long SYNC_LEASE_MS = 77_000;
    public static final long LOCK_CLEANUP_MARGIN_MS = 3_000;
}
