package com.rawwave.core;

import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Absolute owner deadline survives an already-completed or late future. Does not renew budget.
 * A blocked/suspended OS cannot be forced awake by this helper; expired results are rejected
 * when execution resumes. Canceling an owner remains responsible for releasing resources.
 */
public final class TransportAwait {
    private TransportAwait() {}
    public static <T> T get(CompletableFuture<T> future,long deadline,long capMs,LongSupplier clock)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining=deadline-clock.getAsLong();
        if(remaining<=0 || capMs<=0) throw new TimeoutException("TRANSPORT_DEADLINE_EXPIRED");
        T value=future.get(Math.min(capMs,remaining),TimeUnit.MILLISECONDS);
        if(clock.getAsLong()>=deadline) throw new TimeoutException("TRANSPORT_RESULT_AFTER_DEADLINE");
        return value;
    }
}
