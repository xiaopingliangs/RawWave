package com.rawwave.core;

/**
 * Process-local guard that prevents a failed capture-finished transfer from being retried by the
 * same already-open home screen. A fresh launcher Activity explicitly resets the guard.
 */
public final class ActivitySyncAttemptGuard {
    private boolean openAutoSyncSuppressed;

    public synchronized void markStarted(String trigger) {
        if (ActivitySyncPolicy.CAPTURE_FINISHED.equals(ActivitySyncPolicy.normalize(trigger))) {
            openAutoSyncSuppressed = true;
        }
    }

    public synchronized boolean allowOpenAutoSync() {
        return !openAutoSyncSuppressed;
    }

    /** Returns whether a suppression was cleared. */
    public synchronized boolean resetForExplicitOpen() {
        boolean wasSuppressed = openAutoSyncSuppressed;
        openAutoSyncSuppressed = false;
        return wasSuppressed;
    }
}
