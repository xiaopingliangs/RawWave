package com.rawwave.core;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The watch must never delete data that has not reached the phone, or a session still in progress. */
public class ActivityRetentionTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long MB = 1024L * 1024;
    private static final long NOW = 1000L * DAY;

    private static ActivityRetention.Session s(String id, long ageDays, long mb,
                                               boolean acked, boolean sealed) {
        return new ActivityRetention.Session(id, NOW - ageDays * DAY, mb * MB, acked, sealed);
    }

    @Test public void thirtyNewestSyncedSessionsAreKeptAndOlderOnesAreDeleted() {
        List<ActivityRetention.Session> sessions = new ArrayList<>();
        for (int i = 0; i < 35; i++) sessions.add(s("s" + i, i, 10, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        assertEquals(30, plan.keptSynced());
        assertEquals(5, plan.deleteIds().size());
        assertEquals(java.util.Set.of("s30", "s31", "s32", "s33", "s34"),
            new java.util.HashSet<>(plan.deleteIds()));
    }

    @Test public void pendingAndUnsealedSessionsAreNeverDeletedAndDoNotCountAsSynced() {
        List<ActivityRetention.Session> sessions = new ArrayList<>();
        for (int i = 0; i < 35; i++) sessions.add(s("s" + i, i, 10, true, true));
        sessions.add(s("pending-new", 0, 10, false, true));
        sessions.add(s("pending-old", 50, 10, false, true));
        sessions.add(s("unsealed", 60, 10, true, false));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        assertEquals(30, plan.keptSynced());
        assertEquals(3, plan.pending());
        assertEquals(5, plan.deleteIds().size());
        assertFalse(plan.deleteIds().contains("pending-new"));
        assertFalse(plan.deleteIds().contains("pending-old"));
        assertFalse(plan.deleteIds().contains("unsealed"));
    }

    @Test public void newestAndNewestAcknowledgedAreAlwaysKept() {
        // The only pending session is the newest; the newest acknowledged session is 40 days old
        // and larger than the whole budget. Both must survive.
        List<ActivityRetention.Session> sessions = List.of(
            s("new-live", 0, 1024, false, true),
            s("new-acked", 40, 5120, true, true),
            s("older-acked", 41, 100, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        assertFalse(plan.deleteIds().contains("new-live"));
        assertFalse(plan.deleteIds().contains("new-acked"));
        assertTrue(plan.deleteIds().contains("older-acked"));
        assertEquals(1, plan.keptSynced());
        assertEquals(1, plan.pending());
    }

    @Test public void localByteBudgetDeletesOldestAcknowledgedSessions() {
        List<ActivityRetention.Session> sessions = new ArrayList<>();
        for (int i = 0; i < 30; i++) sessions.add(s("s" + i, i, 200, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        // 4 GiB / 200 MB = 20 sessions; the older 10 are outside the budget.
        assertEquals(20, plan.keptSynced());
        assertEquals(10, plan.deleteIds().size());
        assertTrue(plan.keptSyncedBytes() <= ActivityRetention.MAX_LOCAL_BYTES);
    }

    @Test public void acknowledgedSessionsOlderThanThirtyDaysAreDeleted() {
        List<ActivityRetention.Session> sessions = List.of(
            s("fresh2", 5, 10, true, true),
            s("fresh1", 10, 10, true, true),
            s("old31", 31, 10, true, true),
            s("old35", 35, 10, true, true),
            s("old40", 40, 10, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        assertEquals(java.util.Set.of("old31", "old35", "old40"),
            new java.util.HashSet<>(plan.deleteIds()));
        assertEquals(2, plan.keptSynced());
    }

    @Test public void newestAcknowledgedSessionSurvivesTheAgeRule() {
        List<ActivityRetention.Session> sessions = List.of(
            s("pending-live", 0, 10, false, true),
            s("old-newest-acked", 40, 10, true, true),
            s("old50", 50, 10, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        assertFalse(plan.deleteIds().contains("old-newest-acked"));
        assertTrue(plan.deleteIds().contains("old50"));
        assertEquals(1, plan.keptSynced());
        assertEquals(1, plan.pending());
    }

    @Test public void pendingBytesConsumeTheLocalBudgetButAreNeverDeleted() {
        List<ActivityRetention.Session> sessions = new ArrayList<>();
        sessions.add(s("pending", 0, 2048, false, true));
        for (int i = 0; i < 6; i++) sessions.add(s("s" + i, i + 1, 400, true, true));
        ActivityRetention.Plan plan = ActivityRetention.plan(sessions, NOW);
        // 2 GiB pending + five 400 MiB synced = 4 GiB; the sixth synced is outside the budget.
        assertEquals(5, plan.keptSynced());
        assertEquals(1, plan.deleteIds().size());
        assertEquals("s5", plan.deleteIds().get(0));
        assertTrue(plan.keptSyncedBytes() + plan.pendingBytes() <= ActivityRetention.MAX_LOCAL_BYTES);
    }

    @Test public void invalidAckIsTreatedAsPending() throws Exception {
        File root = Files.createTempDirectory("retention-ack").toFile();
        String id = UUID.randomUUID().toString();
        File session = new File(new File(root, "sessions"), id);
        assertTrue(session.mkdirs());
        Files.writeString(new File(session, "plan.json").toPath(), "{\"createdUtcMs\":" + NOW + "}");
        Files.writeString(new File(session, "seal.json").toPath(), "{}");
        File sync = new File(root, "sync");
        assertTrue(sync.mkdirs());
        Files.writeString(new File(sync, id + ".phone-ack.json").toPath(),
            "{\"schema\":2,\"sessionId\":\"" + id + "\"}");
        List<ActivityRetention.Session> sessions = ActivityRetention.scan(root);
        assertEquals(1, sessions.size());
        assertFalse(sessions.get(0).acknowledged());
    }

    @Test public void enforceDeletesOnlyAcknowledgedOldSessionsAndReportsFreedBytes() throws Exception {
        File root = Files.createTempDirectory("retention-enforce").toFile();
        File sessionsRoot = new File(root, "sessions");
        File sync = new File(root, "sync");
        assertTrue(sessionsRoot.mkdirs());
        assertTrue(sync.mkdirs());
        String old = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        String pending = UUID.randomUUID().toString();
        writeSession(sessionsRoot, old, NOW - 40 * DAY);
        writeSession(sessionsRoot, fresh, NOW - DAY);
        writeSession(sessionsRoot, pending, NOW - 50 * DAY);
        writeAck(sync, old);
        writeAck(sync, fresh);
        ActivityRetention.Summary summary = ActivityRetention.enforce(root, NOW);
        assertEquals(1, summary.deletedSessions());
        assertTrue(summary.freedBytes() >= 64 * 1024);
        assertFalse(new File(sessionsRoot, old).exists());
        assertTrue(new File(sessionsRoot, fresh).exists());
        assertTrue(new File(sessionsRoot, pending).exists());
        assertEquals(1, summary.keptSynced());
        assertEquals(1, summary.pending());
    }

    private static void writeSession(File sessionsRoot, String id, long created) throws Exception {
        File folder = new File(sessionsRoot, id);
        assertTrue(folder.mkdirs());
        Files.writeString(new File(folder, "plan.json").toPath(), "{\"createdUtcMs\":" + created + "}");
        Files.writeString(new File(folder, "seal.json").toPath(), "{}");
        Files.write(new File(folder, "00000001-CORE-1-ACCELEROMETER.json").toPath(), new byte[64 * 1024]);
    }

    private static void writeAck(File sync, String id) throws Exception {
        Files.writeString(new File(sync, id + ".phone-ack.json").toPath(),
            "{\"schema\":1,\"sessionId\":\"" + id + "\"}");
    }
}
