package com.rawwave.core;

/** Pure one-shot gate for the watch home screen's delayed pending-data sync. */
public final class AutoSyncGate {
    private boolean armed;

    public boolean arm(boolean foreground, boolean recording, boolean syncRunning, int pendingCount) {
        if (!eligible(foreground, recording, syncRunning, pendingCount)) {
            armed = false;
            return false;
        }
        if (armed) return false;
        armed = true;
        return true;
    }

    public boolean fire(boolean foreground, boolean recording, boolean syncRunning, int pendingCount) {
        if (!armed) return false;
        armed = false;
        return eligible(foreground, recording, syncRunning, pendingCount);
    }

    public boolean cancel() {
        boolean wasArmed = armed;
        armed = false;
        return wasArmed;
    }

    public boolean armed() { return armed; }

    private static boolean eligible(boolean foreground, boolean recording, boolean syncRunning, int pendingCount) {
        return foreground && !recording && !syncRunning && pendingCount > 0;
    }
}
