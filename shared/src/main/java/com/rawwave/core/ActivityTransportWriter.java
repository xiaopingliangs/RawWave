package com.rawwave.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/**
 * Builds the per-session transport ZIP while capture writes immutable raw blocks.
 *
 * <p>Compression runs on its own single worker, not on the capture disk queue: a slow
 * deflate must never back-pressure sensor persistence. The raw session folder remains the
 * source of truth. If the worker falls behind, fails, or the process dies, the builder is
 * abandoned and {@link ActivityArchive#preparePending} rebuilds from raw.</p>
 */
final class ActivityTransportWriter implements Closeable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    /** References to immutable block files, not payload bytes; ~13 seconds at 14 blocks/s. */
    private static final int MAX_QUEUED_BLOCKS = 256;
    private static final long FINISH_WAIT_MS = 60_000L;
    private final File outgoing;
    private final File building;
    private final File target;
    private final String sessionId;
    private final FileOutputStream stream;
    private final ZipOutputStream zip;
    private final Object zipLock = new Object();
    private final Map<String, Map<String, Object>> files = new ConcurrentSkipListMap<>();
    private final Map<String, Object> session = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "activity-transport");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queued = new AtomicInteger();
    private long rawBytes;
    private volatile boolean finished;
    private final AtomicBoolean failed = new AtomicBoolean();

    ActivityTransportWriter(File activitiesRoot, String sessionId, File plan) throws IOException {
        File parent = activitiesRoot.getParentFile();
        if (parent == null) throw new IOException("ACTIVITY_OUTGOING_PARENT_MISSING");
        this.outgoing = new File(parent, "activity-outgoing");
        Files.createDirectories(outgoing.toPath());
        this.sessionId = sessionId;
        this.building = new File(outgoing, sessionId + ".building");
        this.target = new File(outgoing, sessionId + ".zip");
        if (target.isFile()) throw new IOException("ACTIVITY_TRANSPORT_TARGET_EXISTS");
        this.stream = new FileOutputStream(building);
        this.zip = new ZipOutputStream(stream, StandardCharsets.UTF_8);
        session.put("sessionId", sessionId);
        session.put("sealed", true);
        session.put("integrity", "SEAL_INCLUDED_AND_ARCHIVE_REVERIFIED");
        add("plan.json", plan);
    }

    File outgoing() { return outgoing; }

    /** Queue one completed raw file. Never blocks the capture disk owner. */
    void add(String name, File source) {
        synchronized (this) {
            if (failed.get() || finished) return;
            if (queued.incrementAndGet() > MAX_QUEUED_BLOCKS) { abandon(); return; }
            try {
                worker.execute(() -> {
                    try { writeEntry(name, source); }
                    catch (Exception error) { abandon(); }
                    finally { queued.decrementAndGet(); }
                });
            } catch (RejectedExecutionException error) {
                queued.decrementAndGet();
                abandon();
            }
        }
    }

    private void writeEntry(String name, File source) throws IOException {
        if (failed.get() || finished) return;
        synchronized (zipLock) {
            if (failed.get() || finished) return;
            String entry = "sessions/" + sessionId + "/" + name;
            zip.putNextEntry(new ZipEntry(entry));
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long bytes = 0;
            try (InputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    rawBytes += count;
                    if (rawBytes > ActivityArchive.MAX_UNCOMPRESSED_BYTES)
                        throw new IOException("ACTIVITY_BATCH_TOO_LARGE");
                    digest.update(buffer, 0, count);
                    zip.write(buffer, 0, count);
                }
            }
            zip.closeEntry();
            files.put(entry, Map.of("bytes", bytes, "sha256", hex(digest.digest())));
        }
    }

    /** Queue seal.json, wait for the worker, write bundle.json and publish the ZIP. */
    void finish(File seal) {
        synchronized (this) {
            if (failed.get() || finished) return;
            add("seal.json", seal);
            worker.shutdown();
        }
        try {
            if (!worker.awaitTermination(FINISH_WAIT_MS, TimeUnit.MILLISECONDS)) { abandon(); return; }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            abandon();
            return;
        }
        if (failed.get()) return;
        synchronized (zipLock) {
            try {
                byte[] bundle = JSON.toJson(Map.of(
                    "schemaVersion", 1,
                    "kind", "RAWWAVE_ACTIVITY_RAW_EXPORT",
                    "batchId", sessionId,
                    "createdUtcMs", System.currentTimeMillis(),
                    "sessions", List.of(session),
                    "files", files,
                    "rawPolicy", "Original activity JSON bytes; no sensor value conversion, filtering, interpolation or invented rows",
                    "verification", "Every entry carries SHA-256 and byte length; whole archive is hashed during transport"
                )).getBytes(StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry("bundle.json"));
                zip.write(bundle);
                zip.closeEntry();
                zip.finish();
                zip.flush();
                stream.getFD().sync();
                zip.close();
            } catch (IOException error) {
                abandon();
                return;
            }
        }
        try {
            Files.move(building.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            finished = true;
        } catch (IOException error) {
            abandon();
        }
    }

    private void abandon() {
        if (!failed.compareAndSet(false, true)) return;
        worker.shutdownNow();
        synchronized (zipLock) {
            try { zip.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(building.toPath()); } catch (IOException ignored) {}
        }
    }

    @Override public void close() {
        if (!finished) abandon();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
