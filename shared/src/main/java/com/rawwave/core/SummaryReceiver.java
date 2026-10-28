package com.rawwave.core;

import java.io.IOException;

/** Platform passes copied bytes and keeps peer/routing responsibility. No ack before storage. */
public final class SummaryReceiver {
    private final SummaryStore store;
    public SummaryReceiver(SummaryStore store) { this.store = java.util.Objects.requireNonNull(store); }
    public byte[] receive(byte[] input) throws IOException {
        byte[] bytes = input.clone();
        DiagnosticSummary.check(bytes);
        // A replacement cannot mutate the envelope used to construct its receipt.
        store.save(bytes.clone());
        return DiagnosticSummary.ack(bytes);
    }
}
