package com.rawwave.core;

/** Power policy for idle UI and one sync attempt, never for an active recording. */
public final class WorkBudget {
    private WorkBudget() {}
    public static final long IDLE_MS = 80_000;
    public static final long TRANSFER_MS = 75_000;
    public static final long SYNC_LEASE_MS = 77_000;
    public static final long LOCK_CLEANUP_MARGIN_MS = 3_000;
}
