package com.rawwave.core;

/**
 * Timing policy for the pre/post short measurements that need the user to cooperate
 * (finger on an electrode, watch on the wrist, arm still).
 *
 * The old behaviour used one 80-second budget for both "nobody is here" and "the user is
 * holding the electrode but the SDK has not produced a valid value yet". That ended the whole
 * capture session while the user was still trying (body composition was the usual victim).
 *
 * Two clocks are separated here:
 *   - no-presence clock: runs only until the first real evidence of a person (contact, or a
 *     worn watch for SpO2/PPG). Expiry means the user is not doing the measurement -> stop.
 *   - result clock: armed once presence is confirmed. Expiry means the user is cooperating but
 *     no valid value arrived -> record "no valid result" and advance to the next measurement.
 *     It never stops the session.
 *
 * Pure decisions only; the host owns clocks, sensors, persistence and UI.
 */
public final class CooperationPolicy {
    /** Unknown wear state and no finger: enough time to read the short instructions. */
    public static final long NO_PRESENCE_MS = 45_000;
    /** The platform says the watch is off the wrist: do not wait long for a ghost user. */
    public static final long OFF_BODY_MS = 30_000;
    /** A real touch on the screen proves someone is there; review pages stay readable. */
    public static final long TOUCH_GRACE_MS = 30_000;
    /** Default decision window for a review page once the stage cap still has room. */
    public static final long REVIEW_MS = 60_000;

    public static final long BIA_RESULT_MS = 120_000;
    public static final long ECG_RESULT_MS = 120_000;
    public static final long SPO2_RESULT_MS = 60_000;
    public static final long PPG_RESULT_MS = 45_000;

    public enum Next { WAIT, EXIT, ADVANCE }

    private CooperationPolicy() {}

    /** Only an explicit off-body state shortens the "nobody is here" wait. */
    public static long noPresenceWindowMs(Boolean worn) {
        return Boolean.FALSE.equals(worn) ? OFF_BODY_MS : NO_PRESENCE_MS;
    }

    /** Total time a cooperating user may spend in one stage before it advances without a value. */
    public static long resultWindowMs(String stage) {
        if ("BIA".equals(stage) || "ECG".equals(stage)) return BIA_RESULT_MS;
        if ("SPO2".equals(stage)) return SPO2_RESULT_MS;
        if ("PPG".equals(stage)) return PPG_RESULT_MS;
        throw new IllegalArgumentException("NO_COOPERATION_STAGE:" + stage);
    }

    /**
     * Stages where a worn watch already proves the user is present. Finger contact is still
     * required to produce the measurement, but poor SDK contact must not end the session while
     * the watch is on the wrist. BIA uses this because its status errors (4/9/14/15) are often
     * caused by dry skin or finger position, not by an absent user.
     */
    public static boolean waitsForWear(String stage) {
        return "SPO2".equals(stage) || "PPG".equals(stage) || "BIA".equals(stage);
    }

    public static Next decide(boolean engaged, long noPresenceDeadlineMs, long resultDeadlineMs, long nowMs) {
        if (!engaged) return noPresenceDeadlineMs > 0 && nowMs >= noPresenceDeadlineMs ? Next.EXIT : Next.WAIT;
        return resultDeadlineMs > 0 && nowMs >= resultDeadlineMs ? Next.ADVANCE : Next.WAIT;
    }
}
