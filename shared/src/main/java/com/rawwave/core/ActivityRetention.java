package com.rawwave.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Watch-local retention for raw activity sessions.
 *
 * The first 0.12.x builds had no cleanup at all: every session stayed on the watch after the
 * phone acknowledged it, which is how a 20 GB watch reached 15 GB of app data. This class keeps
 * the watch usable without ever deleting data that has not reached the phone.
 *
 * Rules:
 *   - never delete a session without a durable phone acknowledgement;
 *   - never delete an unsealed/incomplete session;
 *   - never delete the newest session, or the newest acknowledged session;
 *   - keep at most the newest {@link #MAX_SYNCED_SESSIONS} acknowledged sessions;
 *   - keep acknowledged sessions younger than {@link #MAX_AGE_MS};
 *   - keep local raw bytes (pending + acknowledged) under {@link #MAX_LOCAL_BYTES}.
 *
 * Pure scan/plan/delete; no Android API. The caller runs it on a background thread.
 */
public final class ActivityRetention {
    /** Recent sessions a user can still inspect on the watch. */
    public static final int MAX_SYNCED_SESSIONS = 30;
    /** Local raw-data budget for pending + acknowledged sessions. */
    public static final long MAX_LOCAL_BYTES = 4L * 1024 * 1024 * 1024;
    /** The original "30 days" rule remains as an outer time bound. */
    public static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    public record Session(String id, long createdUtcMs, long bytes, boolean acknowledged, boolean sealed) {}
    public record Plan(List<String> deleteIds, int keptSynced, int pending,
                       long keptSyncedBytes, long pendingBytes) {}
    public record Summary(int keptSynced, int pending, long keptSyncedBytes, long pendingBytes,
                          int deletedSessions, long freedBytes) {
        public long totalBytes() { return keptSyncedBytes + pendingBytes; }
        public int totalSessions() { return keptSynced + pending; }
    }

    private ActivityRetention() {}

    public static List<Session> scan(File activitiesRoot) {
        List<Session> result = new ArrayList<>();
        File sessionsRoot = new File(activitiesRoot, "sessions");
        File[] folders = sessionsRoot.listFiles(File::isDirectory);
        if (folders == null) return result;
        for (File folder : folders) {
            String id = folder.getName();
            if (!isSessionId(id)) continue;
            boolean acked = isAcknowledged(activitiesRoot, id);
            boolean sealed = new File(folder, "seal.json").isFile();
            result.add(new Session(id, createdUtcMs(folder), folderBytes(folder), acked, sealed));
        }
        result.sort(Comparator.comparingLong(Session::createdUtcMs).reversed()
            .thenComparing(Session::id));
        return result;
    }

    public static Plan plan(List<Session> sessions, long nowMs) {
        List<String> deletes = new ArrayList<>();
        int pending = 0;
        long pendingBytes = 0L;
        String newestId = null;
        String newestAckedId = null;
        for (Session session : sessions) {
            if (newestId == null) newestId = session.id();
            if (session.acknowledged() && session.sealed()) {
                if (newestAckedId == null) newestAckedId = session.id();
            } else {
                pending++;
                pendingBytes += session.bytes();
            }
        }
        long used = pendingBytes;
        int ackedSeen = 0;
        int kept = 0;
        long keptBytes = 0L;
        for (Session session : sessions) {
            if (!session.acknowledged() || !session.sealed()) continue;
            ackedSeen++;
            boolean protectedNewest = session.id().equals(newestId) || session.id().equals(newestAckedId);
            boolean tooMany = ackedSeen > MAX_SYNCED_SESSIONS;
            boolean tooOld = nowMs - session.createdUtcMs() > MAX_AGE_MS;
            boolean tooBig = used + session.bytes() > MAX_LOCAL_BYTES;
            if (!protectedNewest && (tooMany || tooOld || tooBig)) {
                deletes.add(session.id());
                continue;
            }
            kept++;
            keptBytes += session.bytes();
            used += session.bytes();
        }
        return new Plan(List.copyOf(deletes), kept, pending, keptBytes, pendingBytes);
    }

    public static Summary enforce(File activitiesRoot, long nowMs) {
        List<Session> sessions = scan(activitiesRoot);
        Plan plan = plan(sessions, nowMs);
        int deleted = 0;
        long freed = 0L;
        File sessionsRoot = new File(activitiesRoot, "sessions");
        for (String id : plan.deleteIds()) {
            File folder = new File(sessionsRoot, id);
            long bytes = folderBytes(folder);
            if (deleteTree(folder)) {
                deleted++;
                freed += bytes;
            }
        }
        return new Summary(plan.keptSynced(), plan.pending(), plan.keptSyncedBytes(),
            plan.pendingBytes(), deleted, freed);
    }

    public static boolean isSessionId(String value) {
        if (value == null || value.length() != 36) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAcknowledged(File activitiesRoot, String sessionId) {
        File ack = new File(new File(activitiesRoot, "sync"), sessionId + ".phone-ack.json");
        try {
            JsonObject value = JsonParser.parseString(Files.readString(ack.toPath())).getAsJsonObject();
            return value.has("schema") && value.get("schema").getAsInt() == 1
                && value.has("sessionId") && sessionId.equals(value.get("sessionId").getAsString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long createdUtcMs(File folder) {
        try {
            JsonObject plan = JsonParser.parseString(
                Files.readString(new File(folder, "plan.json").toPath())).getAsJsonObject();
            if (plan.has("createdUtcMs") && !plan.get("createdUtcMs").isJsonNull())
                return plan.get("createdUtcMs").getAsLong();
        } catch (Exception ignored) {
        }
        return folder.lastModified();
    }

    static long folderBytes(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isDirectory()) total += folderBytes(file);
            else total += file.length();
        }
        return total;
    }

    private static boolean deleteTree(File file) {
        if (!file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) {
            if (!deleteTree(child)) return false;
        }
        return file.delete() || !file.exists();
    }
}
