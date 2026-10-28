package com.rawwave.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Locale;

/** Streaming transport storage for immutable activity archives. Separate from diagnostics. */
public final class ActivityTransferFiles {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    private ActivityTransferFiles() {}

    public static void validate(String id, String hash, long bytes) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("INVALID_ACTIVITY_TRANSFER_ID");
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("INVALID_ACTIVITY_TRANSFER_HASH");
        if (bytes <= 0 || bytes > MAX_BYTES) throw new IOException("INVALID_ACTIVITY_TRANSFER_SIZE");
    }

    public static boolean sameAttempt(String window, String id, String hash, long bytes,
                                      String remoteWindow, String remoteId, String remoteHash, long remoteBytes) {
        return window != null && !window.isEmpty() && window.equals(remoteWindow) && id.equals(remoteId)
            && hash.equals(remoteHash) && bytes == remoteBytes;
    }

    /** Consumes exactly expected bytes, verifies the stream, atomically commits, then verifies disk again. */
    public static File commit(File folder, String id, String hash, long expected, InputStream input) throws IOException {
        validate(id, hash, expected);
        Files.createDirectories(folder.toPath());
        if (folder.getUsableSpace() < expected + 100L * 1024 * 1024) throw new IOException("LOW_PHONE_STORAGE");
        File target = new File(folder, id + ".zip");
        File part = new File(folder, id + ".part");
        MessageDigest incoming = digest();
        OutputStream sink = null;
        boolean existing = target.isFile();
        try {
            if (!existing) sink = new FileOutputStream(part);
            byte[] buffer = new byte[64 * 1024];
            long remaining = expected;
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_RECEIVE_CANCELLED");
                int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("ACTIVITY_PAYLOAD_TRUNCATED");
                if (count == 0) continue;
                incoming.update(buffer, 0, count);
                if (sink != null) sink.write(buffer, 0, count);
                remaining -= count;
            }
            if (sink instanceof FileOutputStream file) { file.flush(); file.getFD().sync(); }
        } catch (IOException failure) {
            try { if (sink != null) sink.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (!existing) Files.deleteIfExists(part.toPath());
            throw failure;
        } finally {
            if (sink != null) try { sink.close(); } catch (IOException ignored) {}
        }
        if (!hex(incoming.digest()).equals(hash)) {
            if (!existing) Files.deleteIfExists(part.toPath());
            throw new IOException("ACTIVITY_STREAM_HASH_MISMATCH");
        }
        if (!existing) Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        if (target.length() != expected || !hashFile(target).equals(hash)) throw new IOException("ACTIVITY_DISK_READBACK_FAILED");
        byte[] receipt = ("{\"kind\":\"ACTIVITY_ARCHIVE_BYTES_COMMITTED_PENDING_DEEP_VERIFY\",\"id\":\"" + id
            + "\",\"sha256\":\"" + hash + "\",\"bytes\":" + expected + "}").getBytes(StandardCharsets.UTF_8);
        atomic(new File(folder, id + ".receipt.part"), new File(folder, id + ".receipt.json"), receipt);
        return target;
    }

    public static void markVerified(File folder, String id, String hash, long bytes, java.util.List<String> sessionIds) throws IOException {
        validate(id, hash, bytes);
        if (sessionIds == null || sessionIds.isEmpty() || sessionIds.stream().anyMatch(value -> value == null || !value.matches("[0-9a-f-]{36}")))
            throw new IOException("INVALID_VERIFIED_ACTIVITY_SESSIONS");
        byte[] receipt = new Gson().toJson(java.util.Map.of("kind", "ACTIVITY_ARCHIVE_DEEP_VERIFIED",
            "id", id, "sha256", hash, "bytes", bytes, "sessions", sessionIds)).getBytes(StandardCharsets.UTF_8);
        atomic(new File(folder, id + ".verified.part"), new File(folder, id + ".verified.json"), receipt);
    }
    public static String hashFile(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void atomic(File temporary, File target, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes); output.flush(); output.getFD().sync();
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
