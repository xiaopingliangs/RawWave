package com.rawwave.core;

/** Pure priority rule for a watch-initiated raw activity arrival on the phone. */
public final class SyncAdmission {
    public enum Decision { ACCEPT, PREEMPT_DIAGNOSTIC, REJECT_BUSY }

    private SyncAdmission() {}

    public static Decision activityArrival(boolean busy, boolean diagnosticCanBeStopped) {
        if (!busy) return Decision.ACCEPT;
        if (diagnosticCanBeStopped) return Decision.PREEMPT_DIAGNOSTIC;
        return Decision.REJECT_BUSY;
    }
}
