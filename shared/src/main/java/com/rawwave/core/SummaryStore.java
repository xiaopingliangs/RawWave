package com.rawwave.core;

import java.io.IOException;

/** Independent small-diagnostic storage, never an activity receipt or full-report store.
 * Return only after exact bytes are durable and read back. Throw on any failure.
 * No messaging, UI, transfer lease, retry, sensor ownership or source deletion. */
public interface SummaryStore {
    int CONTRACT_VERSION = 1;
    void save(byte[] validatedSummary) throws IOException;
}
