package com.rawwave.core;

import java.io.*;
import java.util.List;
import java.util.Objects;

/** Existing disk formats behind ReceiveStore v1; Android supplies the directory durability adapter. */
public final class FileReceiveStore implements ReceiveStore {
    @FunctionalInterface public interface DirectorySync { void sync(File directory) throws IOException; }
    private final File root;
    private final DirectorySync durability;
    public FileReceiveStore(File root, DirectorySync durability) {
        this.root = Objects.requireNonNull(root);
        this.durability = Objects.requireNonNull(durability);
    }
    @Override public Receipt commit(Request request, InputStream input, Checkpoint verifying) throws IOException {
        return commit(request, input, verifying, 0L);
    }

    @Override public long resumeOffset(Request request) throws IOException {
        Objects.requireNonNull(request);
        if (request.kind() != Kind.RAW_ACTIVITY) return 0L;
        ActivityTransferFiles.validate(request.id(), request.sha256(), request.bytes());
        File folder = new File(root, "activity-inbox");
        // If the final archive already exists, the receiver consumes the full stream and
        // discards it; telling the sender to skip a stale .part would break the hash check.
        if (new File(folder, request.id() + ".zip").isFile()) return 0L;
        File part = new File(folder, request.id() + ".part");
        long length = part.isFile() ? part.length() : 0L;
        // A full or oversized part cannot be resumed safely; let commit truncate and restart.
        return length > 0L && length < request.bytes() ? length : 0L;
    }

    @Override public Receipt commit(Request request, InputStream input, Checkpoint verifying, long resumeFrom) throws IOException {
        Objects.requireNonNull(request); Objects.requireNonNull(input); Objects.requireNonNull(verifying);
        if (resumeFrom < 0L) throw new IOException("INVALID_ACTIVITY_RESUME_OFFSET");
        File folder;
        Receipt receipt;
        if (request.kind() == Kind.DIAGNOSTIC) {
            if (resumeFrom != 0L) throw new IOException("RESUME_NOT_SUPPORTED");
            TransferFiles.validate(request.id(), request.sha256(), request.bytes());
            folder = new File(root, "received");
            byte[] bytes = TransferFiles.readExact(input, (int) request.bytes());
            TransferFiles.commit(folder, request.id(), request.sha256(), bytes);
            receipt = new Receipt(request, List.of(), 0, 0);
        } else if (request.kind() == Kind.RAW_ACTIVITY) {
            folder = new File(root, "activity-inbox");
            File file = ActivityTransferFiles.commit(folder, request.id(), request.sha256(), request.bytes(), input, resumeFrom);
            verifying.run();
            ActivityArchive.Verified verified;
            try {
                verified = ActivityArchive.verify(file);
                if (!verified.id().equals(request.id())) throw new IOException("ACTIVITY_ARCHIVE_ID_CHANGED");
            } catch (IOException | RuntimeException invalid) {
                if (!RejectedActivity.isContentFailure(invalid)) throw invalid;
                try { RejectedActivity.isolate(file,invalid,durability); }
                catch(IOException isolation) { invalid.addSuppressed(isolation); }
                throw invalid;
            }
            ActivityTransferFiles.markVerified(folder, request.id(), request.sha256(), request.bytes(), verified.sessionIds());
            receipt = new Receipt(request, verified.sessionIds(), verified.rawFiles(), verified.rawBytes());
        } else throw new IOException("UNKNOWN_RECEIVE_KIND");
        durability.sync(folder);
        durability.sync(root);
        return receipt;
    }
}
