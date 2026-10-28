package com.rawwave.core;

/** Final commit handshake, shared by raw activities and reports. Executes on the caller's
 * worker; steps must enforce the same owner/deadline. No retries, new budgets or rollback.
 * Any step failure propagates and prevents later steps. A received/persisted commit remains
 * valid evidence even if final acknowledgement fails; returning means the whole exchange ran.
 */
public final class CommitExchange {
    public static final int CONTRACT_VERSION=1;
    private CommitExchange() {}
    @FunctionalInterface public interface Step { void run() throws Exception; }
    @FunctionalInterface public interface Save<T> { T run() throws Exception; }
    public static void sender(Step awaitCommitted,Step saveLocalReceipt,Step sendConfirm,Step awaitFinished)
            throws Exception {
        awaitCommitted.run(); saveLocalReceipt.run(); sendConfirm.run(); awaitFinished.run();
    }
    public static <T> T receiver(Save<T> durableSave,Step sendCommitted,Step awaitConfirm,Step sendFinished)
            throws Exception {
        T receipt=durableSave.run(); sendCommitted.run(); awaitConfirm.run(); sendFinished.run();
        return receipt;
    }
}
