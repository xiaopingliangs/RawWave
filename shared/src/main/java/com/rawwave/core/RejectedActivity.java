package com.rawwave.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Retains rejected bytes and evidence without overwriting earlier attempts. Never acknowledges them. */
public final class RejectedActivity {
    private RejectedActivity() {}
    public static boolean isContentFailure(Exception error) {
        if (error instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) return false;
        if (error instanceof java.util.zip.ZipException || error instanceof EOFException) return true;
        if (error instanceof RuntimeException) return error instanceof IllegalStateException || error instanceof IllegalArgumentException
            || error instanceof NullPointerException || error instanceof com.google.gson.JsonParseException;
        String message=String.valueOf(error.getMessage());
        return List.of("INVALID_ACTIVITY_ARCHIVE_SIZE","DUPLICATE_OR_DIRECTORY_ENTRY","ACTIVITY_ARCHIVE_EXPANSION_LIMIT",
            "ACTIVITY_BUNDLE_","INVALID_ACTIVITY_BUNDLE_MANIFEST","INVALID_ACTIVITY_SESSION_ID","ACTIVITY_SESSION_METADATA_MISSING",
            "ACTIVITY_ENTRY_OUTSIDE_SESSION","ACTIVITY_SEAL_BYTES_MISSING","INVALID_ARCHIVED_SEAL","ARCHIVED_SEAL_",
            "UNSAFE_ACTIVITY_ENTRY","ACTIVITY_ARCHIVE_ID_CHANGED").stream().anyMatch(message::startsWith);
    }
    public static File isolate(File source, Exception reason, FileReceiveStore.DirectorySync sync) throws IOException {
        File inbox=source.getParentFile();
        File rejected=new File(inbox,"rejected");
        Files.createDirectories(rejected.toPath());
        File attempt=new File(rejected,UUID.randomUUID().toString());
        Files.createDirectory(attempt.toPath());
        String id=source.getName().replaceFirst("\\.zip$", "");
        byte[] evidence=new Gson().toJson(Map.of("source",source.getName(),"utc",java.time.Instant.now().toString(),
            "error",reason.toString(),"kind","ACTIVITY_DEEP_VERIFY_REJECTED" )).getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(new File(attempt,"reason.json"))) {out.write(evidence);out.getFD().sync();}
        for(String suffix:List.of(".receipt.json",".verified.json")) {
            File sidecar=new File(inbox,id+suffix);
            if(sidecar.isFile()) Files.copy(sidecar.toPath(),new File(attempt,sidecar.getName()).toPath());
        }
        // Atomic move preserves the original bytes. No automatic deletion of evidence.
        Files.move(source.toPath(),new File(attempt,source.getName()).toPath(),StandardCopyOption.ATOMIC_MOVE);
        sync.sync(attempt);sync.sync(rejected);sync.sync(inbox);
        return attempt;
    }
}
