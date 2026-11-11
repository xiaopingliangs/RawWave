package com.rawwave.core;

/** Pure policy for keeping backlog sync subordinate to user-requested capture. */
public final class ActivitySyncPolicy {
    public static final String OPEN_IDLE_AUTO = "OPEN_IDLE_AUTO";
    public static final String CAPTURE_FINISHED = "CAPTURE_FINISHED";
    public static final String USER_MANUAL = "USER_MANUAL";
    public static final String UNKNOWN = "UNKNOWN";

    private ActivitySyncPolicy() {}

    public static String normalize(String value) {
        if (OPEN_IDLE_AUTO.equals(value) || CAPTURE_FINISHED.equals(value) || USER_MANUAL.equals(value)) return value;
        return UNKNOWN;
    }

    /**
     * A sync is always a background job. It must never replace the capture home or stop the
     * user from starting a new activity; the home shows its status and capture can preempt it.
     */
    public static boolean isNonBlockingHomeSync(String trigger) {
        return true;
    }

    /** A user choosing an activity may cancel any bounded sync; committed data remains retryable. */
    public static boolean captureMayPreempt(String trigger) {
        return true;
    }

    /** Only an explicit send or the automatic send after capture may close both visible tasks. */
    public static boolean exitAfterSuccessfulSync(String trigger) {
        String normalized = normalize(trigger);
        return CAPTURE_FINISHED.equals(normalized) || USER_MANUAL.equals(normalized);
    }
}
