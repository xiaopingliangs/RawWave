package com.rawwave.core;

/** Retry count for one ECG phase. It never blocks another attempt. */
public final class EcgRetryPolicy {
    public static final int FAILURES_BEFORE_SKIP=2;
    private int failures;

    public int recordFailure(){return ++failures;}
    public int failures(){return failures;}
    public boolean skipAllowed(){return failures>=FAILURES_BEFORE_SKIP;}
    public boolean retryAllowed(){return true;}
}