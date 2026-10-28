package com.rawwave.core;

/** Phase decisions only. Host owns clocks, SDK lifecycle, flush/seal and UI.
 * Expiry is evaluated only after the host's existing absolute deadline has elapsed.
 * No IO, retry budget renewal or sensor actions occur here.
 */
public final class CapturePhasePolicy {
    public static final int CONTRACT_VERSION=1;
    public enum Next { CONNECT_TIMEOUT, IDLE_STOP, END_ECG, REVIEW_PRE, REVIEW_POST, CORE, POST_ECG, FINISH }
    private CapturePhasePolicy() {}
    public static Next expired(String phase, boolean measuring) {
        if("CONNECTING".equals(phase)) return Next.CONNECT_TIMEOUT;
        if(phase.startsWith("REVIEW_")) return Next.IDLE_STOP;
        if(phase.startsWith("PRE") || phase.startsWith("POST"))
            return measuring ? Next.END_ECG : Next.IDLE_STOP;
        throw new IllegalArgumentException("NO_TIMED_PHASE:"+phase);
    }
    public static Next ecgEnded(boolean beforeActivity, boolean failed, boolean continuousComplete) {
        if(failed || !continuousComplete) return beforeActivity ? Next.REVIEW_PRE : Next.REVIEW_POST;
        return beforeActivity ? Next.CORE : Next.FINISH;
    }
    public static Next coreEnded(boolean ecgEnabled) { return ecgEnabled ? Next.POST_ECG : Next.FINISH; }
}
