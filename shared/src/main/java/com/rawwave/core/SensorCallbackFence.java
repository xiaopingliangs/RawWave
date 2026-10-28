package com.rawwave.core;

/** Linearizes accepted callback copy-and-post against listener shutdown.
 * Does not wait for future SDK callbacks and never executes vendor stop APIs under its lock.
 * The serial dispatcher must be FIFO; completion is posted AFTER close returns. */
public final class SensorCallbackFence {
    private boolean accepting = true;
    public synchronized void accept(Runnable copyAndPost) {
        if (accepting) copyAndPost.run();
    }
    public synchronized void close() { accepting = false; }
}
