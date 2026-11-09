package com.rawwave.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Machine-readable index of what a diagnostic report does and does not contain.
 * It is a navigation aid, not a root-cause conclusion and not proof that hardware stopped.
 */
public final class DiagnosticManifest {
    public static final int SCHEMA = 1;
    private DiagnosticManifest() {}

    public static byte[] create(Map<String, byte[]> snapshot, String role, Map<String, Object> build,
                                int includedWatchReports, int receivedWatchReports,
                                List<String> includedWatchFiles) {
        DiagnosticEvents.Read events = DiagnosticEvents.read(snapshot, role, false);
        DiagnosticEvents.Read observations = DiagnosticEvents.read(snapshot, role, true);
        String processRunId = "";
        String latestUtc = "";
        LinkedHashSet<String> sessions = new LinkedHashSet<>();
        LinkedHashSet<String> captureIds = new LinkedHashSet<>();
        JsonObject capability = null;
        for (JsonObject event : events.events) {
            String run = DiagnosticEvents.text(event, "processRunId");
            if (!run.isEmpty()) processRunId = run;
            String utc = DiagnosticEvents.text(event, "utc");
            if (!utc.isEmpty()) latestUtc = utc;
            JsonObject details = DiagnosticEvents.details(event);
            String session = DiagnosticEvents.text(details, "session");
            if (session.isEmpty()) session = DiagnosticEvents.text(details, "sessionId");
            if (!session.isEmpty() && !"none".equals(session)) sessions.add(session);
            String capture = DiagnosticEvents.text(details, "diagnosticCaptureId");
            if (!capture.isEmpty()) captureIds.add(capture);
            if ("SDK_CAPABILITY".equals(DiagnosticEvents.eventName(event))) capability = details;
        }
        for (JsonObject event : observations.events) {
            JsonObject details = DiagnosticEvents.details(event);
            String capture = DiagnosticEvents.text(details, "diagnosticCaptureId");
            if (!capture.isEmpty()) captureIds.add(capture);
        }
        boolean sdkLogs = snapshot.keySet().stream().anyMatch(name ->
            name.startsWith(role + "/sensor-sdk-") && name.endsWith(".txt"));
        boolean sensorProbes = snapshot.keySet().stream().anyMatch(name ->
            name.contains("/sensor-probes/"));
        boolean rotation = events.rotationObserved || observations.rotationObserved;

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("processRunId", processRunId);
        coverage.put("latestEventUtc", latestUtc);
        coverage.put("latestSessionIds", tail(sessions, 5));
        coverage.put("latestDiagnosticCaptureIds", tail(captureIds, 5));
        coverage.put("eventFiles", events.files);
        coverage.put("observationFiles", observations.files);
        coverage.put("corruptEventLines", events.corruptLines);
        coverage.put("corruptObservationLines", observations.corruptLines);
        coverage.put("rotationObserved", rotation);
        coverage.put("rawHealthDataIncluded", false);
        coverage.put("sensorProbeDataIncluded", sensorProbes);
        coverage.put("activityArchivesIncluded", false);
        coverage.put("systemDumpIncluded", false);
        coverage.put("includedWatchReports", includedWatchReports);
        coverage.put("receivedWatchReports", receivedWatchReports);
        coverage.put("includedWatchFiles", includedWatchFiles);
        coverage.put("sensorSdkLogIncluded", sdkLogs);
        coverage.put("observationCount", observations.events.size());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("appJournal", events.events.isEmpty() ? "EMPTY_OR_NOT_COLLECTED" : "AVAILABLE_APP_SELF_REPORT");
        evidence.put("appObservations", observations.events.isEmpty() ? "NOT_COLLECTED" : "AVAILABLE_USER_REPORT");
        evidence.put("sensorSdkLog", sdkLogs ? "AVAILABLE_APP_SIDE_LOG" : "NOT_COLLECTED");
        evidence.put("sensorProbeData", sensorProbes ? "INCLUDED_AS_DIAGNOSTIC_TEST_DATA" : "NONE");
        evidence.put("sensorCapability", capability == null ? "NOT_COLLECTED" : capability);
        evidence.put("activityArchive", "NOT_INCLUDED_IN_THIS_REPORT");
        evidence.put("systemDump", "NOT_COLLECTED");
        evidence.put("otherDeviceReports", includedWatchReports == 0 ? "NOT_COLLECTED_OR_NOT_VERIFIED" : "CACHED_INCLUDED");

        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("L0_USER_UI", layer(observations.events.isEmpty() ? "NOT_COLLECTED" : "USER_REPORTED",
            observations.events.isEmpty() ? "No structured problem-site record in this report."
                : "User observation is present; it is not a system trace."));
        layers.put("L1_APP_STATE", layer(events.events.isEmpty() ? "NOT_COLLECTED" : "AVAILABLE",
            "App journal only; absence of an event is not proof that nothing happened."));
        layers.put("L2_APP_RESOURCES", layer(events.events.isEmpty() ? "NOT_COLLECTED" : "APP_SELF_REPORT",
            "Tracker unset/disconnect events are requests and returns, not hardware-stop receipts.",
            Map.of("hardwareStoppedVerified", false)));
        layers.put("L3_ANDROID_FRAMEWORK", layer("NOT_COLLECTED",
            "A system dump is required for sensorservice, activity services, power and process state."));
        layers.put("L4_VENDOR_SDK", layer(capability == null && !sdkLogs ? "NOT_COLLECTED" : "PARTIAL",
            "SDK logs and capability are app-side; they do not prove vendor service or hub state.",
            capability == null ? Map.of() : Map.of("capability", capability)));
        layers.put("L5_VENDOR_SERVICE", layer("NOT_COLLECTED",
            "Requires dumpsys activity services / ps / logcat from a system dump."));
        layers.put("L6_SENSOR_HUB", layer("NOT_COLLECTED",
            "Requires nanohub-log / sec-sensors-hal evidence from a system dump."));
        layers.put("L7_HARDWARE", layer("UNKNOWN",
            "Only user observation and reboot behavior can support this layer; App logs cannot."));
        layers.put("L8_TRANSPORT_STORAGE", layer("PARTIAL",
            "Sync events may be present, but activity ZIPs are exported separately and are not in this report."));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA);
        manifest.put("kind", "RAWWAVE_DIAGNOSTIC_MANIFEST");
        manifest.put("generatedUtc", Instant.now().toString());
        manifest.put("role", role);
        manifest.put("build", build);
        manifest.put("coverage", coverage);
        manifest.put("evidence", evidence);
        manifest.put("layers", layers);
        manifest.put("nonClaims", List.of(
            "This manifest does not prove sensor hardware stopped.",
            "App cleanup events are requests/returns, not hardware receipts.",
            "The absence of a fault in this report is not proof of correctness.",
            "A system dump must be captured separately by the user and analysed on a PC.",
            "Activity raw health data and system logs are not included in this report.",
            "Sensor-registration probe samples may be included; they are diagnostic test data, not activity raw data."));
        List<String> nextEvidence = new ArrayList<>();
        nextEvidence.add("system dump: dumpsys sensorservice / activity services / ps / logcat");
        nextEvidence.add("watch nanohub logs: ppg_multi_25hz / ppg_multi_100hz start, stop and LED markers");
        if (observations.events.isEmpty()) nextEvidence.add("user observation: record LED/exit/reboot state at the time of the problem");
        if (includedWatchReports == 0) nextEvidence.add("the other device's report, if this is a two-device problem");
        manifest.put("nextEvidence", nextEvidence);
        return new GsonBuilder().setPrettyPrinting().create().toJson(manifest).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> layer(String status, String note) {
        return layer(status, note, Map.of());
    }

    private static Map<String, Object> layer(String status, String note, Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("evidenceNote", note);
        result.putAll(extra);
        return result;
    }

    private static List<String> tail(LinkedHashSet<String> values, int limit) {
        List<String> all = new ArrayList<>(values);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }
}
