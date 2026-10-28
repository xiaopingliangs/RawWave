package com.rawwave.core;

/** No-contact waiting is bounded separately from genuine 30-second ECG measurement. */
public final class EcgWaitBudget {
    public static final long IDLE_MS=80_000;
    private EcgWaitBudget() {}
    public static long initialDeadline(boolean firstPre, long sessionStartedMs, long nowMs) {
        return firstPre ? sessionStartedMs+IDLE_MS : nowMs+IDLE_MS;
    }
    /** A waiting timeout ends the session, never starts another 80-second review or sync. */
    public static boolean mustStop(long deadlineMs,long nowMs,boolean measuring,boolean review) {
        return deadlineMs>0 && nowMs>=deadlineMs && (review || !measuring);
    }
}
