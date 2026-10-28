package com.rawwave.core;

import java.io.*;

/** A private pending copy becomes visible only after verification. One handle per attempt. */
public interface ExportDestination {
    Pending create(String name) throws IOException;
    interface Pending {
        OutputStream openOutput() throws IOException;
        InputStream openInput() throws IOException;
        void publish() throws IOException;
        void discard() throws IOException;
    }
}
