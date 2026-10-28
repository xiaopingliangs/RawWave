package com.rawwave.core;

import java.util.Objects;

/** Serial execution-protection handoff. Actions must be bounded, non-waiting requests.
 * Identity, not equals(), binds cleanup. No new acquisition during cleanup, including reentry.
 * This does not promise that an OS scheduler runs a deadline on time.
 */
public final class LeaseScope<T> {
    public static final int CONTRACT_VERSION = 1;
    private T current;
    private boolean releasing;
    public synchronized T current() { return current; }
    public synchronized boolean acquire(T owner, Runnable action) {
        Objects.requireNonNull(owner); Objects.requireNonNull(action);
        if(current != null || releasing) return false;
        current=owner;
        // Caller must release partial acquisition on failure; do not hide owned resources.
        action.run();
        return true;
    }
    public synchronized boolean release(T owner, Runnable action) {
        Objects.requireNonNull(action);
        if(owner == null || current != owner || releasing) return false;
        releasing=true;
        try { action.run(); }
        finally { current=null; releasing=false; }
        return true;
    }
}
