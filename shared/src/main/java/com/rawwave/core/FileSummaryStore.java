package com.rawwave.core;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/** One application-owned instance serializes updates to the existing latest-summary slot. */
public final class FileSummaryStore implements SummaryStore {
    private final File folder;
    private final ActivityStore.DirectorySync directorySync;
    public FileSummaryStore(File appRoot, ActivityStore.DirectorySync directorySync) {
        this.folder = new File(appRoot,"diagnostic-summary");
        this.directorySync = directorySync;
    }
    @Override public synchronized void save(byte[] summary) throws IOException {
        byte[] bytes = summary.clone();
        DiagnosticSummary.check(bytes);
        if (!folder.isDirectory()) {
            if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("SUMMARY_DIRECTORY_FAILED");
        }
        // Also on retry: a previous failure may have created but not synced this directory.
        directorySync.sync(folder.getParentFile());
        File temporary = new File(folder,"latest.tmp"), target = new File(folder,"latest.json");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes); output.getFD().sync();
        }
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        directorySync.sync(folder);
        if (!Arrays.equals(Files.readAllBytes(target.toPath()),bytes)) throw new IOException("SUMMARY_READBACK_MISMATCH");
    }
}
