package com.rawwave.core;

import java.io.*;
import java.util.*;

/** Sender storage v1. No networking, retry, UI or source deletion.
 * Protocol caller authenticates the current peer/window COMMITTED before acknowledge.
 */
public interface SendStore {
    int CONTRACT_VERSION=1;
    record Payload(File file,String id,String sha256,long bytes,List<String> sessions) {
        public Payload { sessions=List.copyOf(sessions); }
    }
    record Confirmation(String id,String sha256,long bytes,String peer,long utcMs) {}
    @FunctionalInterface interface ReportPayload { Map<String,byte[]> create(String id) throws IOException; }
    /** null means no pending activities; report factory is called only when no cached report exists. */
    Payload prepare(ReportPayload report) throws IOException;
    /** Caller owns and closes the returned stream. */
    InputStream open(Payload payload) throws IOException;
    /** Revalidates local bytes, saves legacy ACK files and syncs directories before returning. */
    void acknowledge(Payload payload,Confirmation confirmation) throws IOException;
}
