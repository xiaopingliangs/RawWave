package com.rawwave.core;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class DiagnosticManifestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Map<String, byte[]> snapshotWithObservation() throws Exception {
        File dir = temp.newFolder();
        Journal journal = new Journal(dir, "watch");
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "TRACKER_CREATED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "FINAL_UNSET_RETURNED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "DISCONNECT_REQUEST", "sensor", "ALL"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "DISCONNECT_RETURNED", "sensor", "ALL"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "DISCONNECT_RECHECK", "sensor", "ALL", "detail", "trackersRetained=0; deliveriesRetained=0; hardwareStoppedVerified=false"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "OWNER_CLOSED", "sensor", "ALL", "detail", "failure="));
        journal.event("CAPTURE_SESSION_END", Map.of("session", "session-1", "detail", "USER_COMPLETED; counts={CORE-1/PPG=1}"));
        journal.event("CAPTURE_PERSISTENCE_SUMMARY", Map.of("detail", "journalFailure="));
        journal.event("ACTIVITY_WATCH_SYNC_FINISHED", Map.of("success", true, "text", "COMMITTED"));
        journal.observation("DIAGNOSTIC_CAPTURE", Map.of(
            "diagnosticCaptureId", "capture-1",
            "symptoms", List.of("LED_PERSISTENT"),
            "led", "ON",
            "watchExit", "NOT_EXITED",
            "rebootState", "NOT_RECORDED"));
        return journal.snapshot();
    }

    @Test public void manifestStatesEvidenceBoundariesAndObservationWithoutHealthSamples() throws Exception {
        Map<String, byte[]> snapshot = snapshotWithObservation();
        JsonObject manifest = JsonParser.parseString(new String(DiagnosticManifest.create(snapshot, "watch",
            Map.of("versionCode", 66, "buildRun", "test", "model", "SM-L350"), 0, 0, List.of()), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("RAWWAVE_DIAGNOSTIC_MANIFEST", manifest.get("kind").getAsString());
        assertEquals("NOT_COLLECTED", manifest.getAsJsonObject("evidence").get("systemDump").getAsString());
        assertEquals("AVAILABLE_USER_REPORT", manifest.getAsJsonObject("evidence").get("appObservations").getAsString());
        assertEquals(1, manifest.getAsJsonObject("coverage").get("observationCount").getAsInt());
        assertEquals("NOT_COLLECTED", manifest.getAsJsonObject("layers").getAsJsonObject("L3_ANDROID_FRAMEWORK").get("status").getAsString());
        assertFalse(manifest.getAsJsonObject("coverage").get("rawHealthDataIncluded").getAsBoolean());
        assertTrue(manifest.getAsJsonArray("nonClaims").toString().contains("does not prove sensor hardware stopped"));
        assertTrue(manifest.getAsJsonArray("nextEvidence").toString().contains("nanohub"));
    }

    @Test public void postconditionsPassOnlyObservedAppEventsAndKeepHardwareUnknown() throws Exception {
        Map<String, byte[]> snapshot = snapshotWithObservation();
        JsonObject post = JsonParser.parseString(new String(DiagnosticPostconditions.create(snapshot, "watch"), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> checks = new LinkedHashMap<>();
        for (JsonElement element : post.getAsJsonArray("checks"))
            checks.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject().get("status").getAsString());
        assertEquals("PASS", checks.get("tracker_final_unset"));
        assertEquals("PASS", checks.get("vendor_disconnect"));
        assertEquals("PASS", checks.get("reference_recheck"));
        assertEquals("PASS", checks.get("session_end"));
        assertEquals("UNKNOWN", checks.get("hardware_stop"));
        assertEquals("NOT_COLLECTED", checks.get("system_evidence"));
        assertEquals("INCOMPLETE", post.getAsJsonObject("summary").get("overall").getAsString());
    }

    @Test public void missingFinalUnsetAndDisconnectFailureAreReportedAsFailures() throws Exception {
        File dir = temp.newFolder();
        Journal journal = new Journal(dir, "watch");
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "TRACKER_CREATED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "FINAL_UNSET_FAILED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "DISCONNECT_FAILED", "sensor", "ALL"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "DISCONNECT_RECHECK", "sensor", "ALL", "detail", "trackersRetained=1; deliveriesRetained=0"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("stage", "OWNER_CLOSED", "sensor", "ALL", "detail", "failure=binder died"));
        JsonObject post = JsonParser.parseString(new String(DiagnosticPostconditions.create(journal.snapshot(), "watch"), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> checks = new LinkedHashMap<>();
        for (JsonElement element : post.getAsJsonArray("checks"))
            checks.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject().get("status").getAsString());
        assertEquals("FAIL", checks.get("tracker_final_unset"));
        assertEquals("FAIL", checks.get("vendor_disconnect"));
        assertEquals("FAIL", checks.get("reference_recheck"));
        assertEquals("FAIL", checks.get("owner_closed"));
        assertEquals("FAIL", post.getAsJsonObject("summary").get("overall").getAsString());
    }

    @Test public void newestOwnerWithTrackersIsSelectedSoOldCleanupCannotHideNewLeak() throws Exception {
        File dir = temp.newFolder();
        Journal journal = new Journal(dir, "watch");
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("ownerId", "old", "stage", "TRACKER_CREATED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("ownerId", "old", "stage", "FINAL_UNSET_RETURNED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("ownerId", "new", "stage", "TRACKER_CREATED", "sensor", "PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP", Map.of("ownerId", "new", "stage", "DISCONNECT_FAILED", "sensor", "ALL"));
        JsonObject post = JsonParser.parseString(new String(DiagnosticPostconditions.create(journal.snapshot(), "watch"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("new", post.get("selectedCleanupOwner").getAsString());
        Map<String, String> checks = new LinkedHashMap<>();
        for (JsonElement element : post.getAsJsonArray("checks"))
            checks.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject().get("status").getAsString());
        assertEquals("FAIL", checks.get("tracker_final_unset"));
        assertEquals("FAIL", checks.get("vendor_disconnect"));
        assertTrue(post.getAsJsonArray("cleanupOwners").toString().contains("\"ownerId\":\"old\""));
    }

    @Test public void observationsRotateWithoutTouchingHealthEvents() throws Exception {
        File dir = temp.newFolder();
        Journal journal = new Journal(dir, "phone");
        byte[] first = null;
        for (int i = 0; i < 20; i++) {
            journal.observation("DIAGNOSTIC_CAPTURE", Map.of("diagnosticCaptureId", "cap-" + i,
                "symptoms", List.of("OTHER_OR_UNKNOWN"), "note", "x".repeat(5000)));
            if (i == 0) first = journal.snapshot().get("phone/diagnostic-observations.jsonl");
        }
        Map<String, byte[]> snapshot = journal.snapshot();
        assertNotNull(first);
        assertTrue(snapshot.containsKey("phone/diagnostic-observations.jsonl"));
        assertTrue(snapshot.containsKey("phone/diagnostic-observations.previous.jsonl"));
        assertTrue(snapshot.containsKey("phone/events.jsonl"));
        assertTrue(new String(snapshot.get("phone/diagnostic-observations.jsonl"), StandardCharsets.UTF_8).contains("cap-19"));
    }
}
