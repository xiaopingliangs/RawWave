package com.rawwave.core;

/** Attempt both resource releases; preserve errors without skipping termination. */
public final class TransferCleanup {
    private TransferCleanup() {}
    public static RuntimeException run(Runnable channel, Runnable worker) {
        RuntimeException failure=null;
        try {channel.run();} catch(RuntimeException e){failure=e;}
        try {worker.run();} catch(RuntimeException e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        return failure;
    }
}
