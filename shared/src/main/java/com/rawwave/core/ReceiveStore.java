package com.rawwave.core;

import java.io.*;
import java.util.List;

/** Receive handoff v1. Caller owns input lifetime and peer/window authentication.
 * Return only after byte verification, required deep verification, receipts and directory sync.
 * Throw on any failure; no network, UI, retry, source deletion or success acknowledgement here.
 */
public interface ReceiveStore {
    int CONTRACT_VERSION = 1;
    enum Kind { DIAGNOSTIC, RAW_ACTIVITY }
    record Request(Kind kind, String id, String sha256, long bytes) {}
    record Receipt(Request request, List<String> sessionIds, int rawFiles, long rawBytes) {
        public Receipt { sessionIds = List.copyOf(sessionIds); }
    }
    /** RAW_ACTIVITY only: after byte commit, before deep verification. Never invoked for DIAGNOSTIC.
     * This is a stage notification, not a universal cancellation hook. Caller checks its window
     * before and after commit on both paths. Exceptions from this callback never quarantine data.
     */
    @FunctionalInterface interface Checkpoint { void run() throws IOException; }
    Receipt commit(Request request, InputStream input, Checkpoint verifying) throws IOException;
}
