package com.rawwave.core;

/** Platform scheduling boundary. FIFO serial owner, delayed jobs are not deadline evidence.
 * close drains already due jobs and discards future timers; no work may be accepted afterward.
 * Implementations must not run posts inline. Return false if the owner is gone. */
public interface CaptureQueue {
    boolean post(Runnable action);
    boolean postDelayed(Runnable action, long delayMs);
    void removeCallbacks(Runnable action);
    void close();
}
