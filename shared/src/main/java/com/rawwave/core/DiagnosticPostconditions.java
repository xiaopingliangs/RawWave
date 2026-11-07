package com.rawwave.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * App-level postcondition projection. It answers only what the journal can answer.
 * Hardware stop, vendor service exit and sensor-hub state remain UNKNOWN without a system dump.
 */
public final class DiagnosticPostconditions {
    public static final int SCHEMA = 1;
    private DiagnosticPostconditions() {}

    public static byte[] create(Map<String, byte[]> snapshot, String role) {
        DiagnosticEvents.Read read = DiagnosticEvents.read(snapshot, role, false);
        Map<String, CleanupLedger> ledgers = new LinkedHashMap<>();
        boolean exitRequested = false;
        String sessionEnd = "", sessionEndUtc = "", persistence = "", persistenceUtc = "";
        Boolean syncSuccess = null;
        String syncText = "", syncUtc = "";
        Boolean phoneSyncSuccess = null;
        String phoneSyncText = "";
        for (JsonObject event : read.events) {
            String name = DiagnosticEvents.eventName(event);
            JsonObject details = DiagnosticEvents.details(event);
            String detail = DiagnosticEvents.text(details, "detail");
            if ("CAPTURE_VENDOR_CLEANUP".equals(name)) {
                String owner = DiagnosticEvents.text(details, "ownerId");
                if (owner.isEmpty()) owner = "LEGACY";
                CleanupLedger ledger = ledgers.computeIfAbsent(owner, key -> new CleanupLedger(key));
                ledger.lastSequence = Math.max(ledger.lastSequence, DiagnosticEvents.longValue(event, "eventSeq"));
                String eventUtc = DiagnosticEvents.text(event, "utc");
                if (!eventUtc.isEmpty() && eventUtc.compareTo(ledger.lastUtc) > 0) ledger.lastUtc = eventUtc;
                String stage = DiagnosticEvents.text(details, "stage");
                String sensor = DiagnosticEvents.text(details, "sensor");
                if ("TRACKER_CREATED".equals(stage) && !sensor.isEmpty()) ledger.created.add(sensor);
                if ("FINAL_UNSET_RETURNED".equals(stage) && !sensor.isEmpty()) ledger.finalReturned.add(sensor);
                if (("FINAL_UNSET_FAILED".equals(stage) || "FINAL_UNSET_RETRY_FAILED".equals(stage)) && !sensor.isEmpty())
                    ledger.finalFailed.add(sensor);
                if ("UNSET_FAILED".equals(stage) && !sensor.isEmpty()) ledger.intermediateFailed.add(sensor);
                if ("DISCONNECT_REQUEST".equals(stage)) ledger.disconnectRequested = true;
                if ("DISCONNECT_RETURNED".equals(stage)) ledger.disconnectReturned = true;
                if ("DISCONNECT_FAILED".equals(stage)) ledger.disconnectFailed = true;
                if ("DISCONNECT_RECHECK".equals(stage)) ledger.recheckDetail = detail;
                if ("OWNER_CLOSED".equals(stage)) { ledger.ownerClosed = true; ledger.ownerClosedDetail = detail; }
            }
            if ("CAPTURE_SESSION_END".equals(name)) { sessionEnd = detail; sessionEndUtc = DiagnosticEvents.text(event, "utc"); }
            if ("CAPTURE_PERSISTENCE_SUMMARY".equals(name)) { persistence = detail; persistenceUtc = DiagnosticEvents.text(event, "utc"); }
            if ("EXIT_REQUESTED".equals(name)) exitRequested = true;
            if ("ACTIVITY_WATCH_SYNC_FINISHED".equals(name)) {
                if (details.has("success")) syncSuccess = details.get("success").getAsBoolean();
                syncText = DiagnosticEvents.text(details, "text");
                syncUtc = DiagnosticEvents.text(event, "utc");
            }
            if ("ACTIVITY_PHONE_RECEIVE_RESULT".equals(name)) {
                if (details.has("success")) phoneSyncSuccess = details.get("success").getAsBoolean();
                String reason = DiagnosticEvents.text(details, "reason");
                String text = DiagnosticEvents.text(details, "text");
                String bytes = details.has("bytesExpected") ? details.get("bytesExpected").getAsString() : "";
                phoneSyncText = "reason=" + reason + "; bytesExpected=" + bytes
                    + (text.isEmpty() ? "" : "; " + text);
            }
        }
        CleanupLedger selected = ledgers.values().stream()
            .filter(ledger -> !ledger.created.isEmpty() && !ledger.lastUtc.isEmpty())
            .max(Comparator.comparing(ledger -> ledger.lastUtc))
            .orElseGet(() -> ledgers.values().stream()
                .filter(ledger -> !ledger.created.isEmpty())
                .max(Comparator.comparingLong(ledger -> ledger.lastSequence))
                .orElseGet(() -> ledgers.values().stream()
                    .max(Comparator.comparingLong(ledger -> ledger.lastSequence)).orElse(null)));
        LinkedHashSet<String> created = selected == null ? new LinkedHashSet<>() : selected.created;
        LinkedHashSet<String> finalReturned = selected == null ? new LinkedHashSet<>() : selected.finalReturned;
        LinkedHashSet<String> finalFailed = selected == null ? new LinkedHashSet<>() : selected.finalFailed;
        LinkedHashSet<String> intermediateFailed = selected == null ? new LinkedHashSet<>() : selected.intermediateFailed;
        String recheckDetail = selected == null ? "" : selected.recheckDetail;
        String ownerClosedDetail = selected == null ? "" : selected.ownerClosedDetail;
        boolean ownerClosed = selected != null && selected.ownerClosed;
        boolean disconnectRequested = selected != null && selected.disconnectRequested;
        boolean disconnectReturned = selected != null && selected.disconnectReturned;
        boolean disconnectFailed = selected != null && selected.disconnectFailed;

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean sessionNear = selected == null || near(selected.lastUtc, sessionEndUtc);
        checks.add(check("session_end", sessionEnd.isEmpty() ? "UNKNOWN" : !sessionNear ? "UNKNOWN" :
                sessionEnd.contains("NOT_SEALED") ? "FAIL" : "PASS",
            "CAPTURE_SESSION_END", sessionEnd.isEmpty() ? "No CAPTURE_SESSION_END event in the retained journal."
                : bounded(sessionEnd, 320) + (sessionNear ? "" : "; outside selected cleanup owner time window")));
        if (created.isEmpty()) {
            checks.add(check("tracker_final_unset", "NOT_APPLICABLE", "CAPTURE_VENDOR_CLEANUP",
                "No vendor tracker creation event for the latest cleanup owner."));
        } else {
            LinkedHashSet<String> missing = new LinkedHashSet<>(created);
            missing.removeAll(finalReturned);
            String status = finalFailed.isEmpty() && missing.isEmpty() ? "PASS" : "FAIL";
            checks.add(check("tracker_final_unset", status, "CAPTURE_VENDOR_CLEANUP",
                "owner=" + selected.ownerId + "; created=" + created + "; finalUnsetReturned=" + finalReturned + "; failed=" + finalFailed
                    + (missing.isEmpty() ? "" : "; missing=" + missing)
                    + (intermediateFailed.isEmpty() ? "" : "; intermediateFailures=" + intermediateFailed)));
        }
        if (disconnectFailed) checks.add(check("vendor_disconnect", "FAIL", "CAPTURE_VENDOR_CLEANUP", "DISCONNECT_FAILED observed."));
        else if (disconnectReturned) checks.add(check("vendor_disconnect", "PASS", "CAPTURE_VENDOR_CLEANUP", "DISCONNECT_RETURNED observed; this is not a hardware receipt."));
        else if (disconnectRequested) checks.add(check("vendor_disconnect", "UNKNOWN", "CAPTURE_VENDOR_CLEANUP", "DISCONNECT_REQUEST observed without a return."));
        else checks.add(check("vendor_disconnect", "NOT_APPLICABLE", "CAPTURE_VENDOR_CLEANUP", "No disconnect request for the latest cleanup owner."));

        if (recheckDetail.isEmpty()) checks.add(check("reference_recheck", "UNKNOWN", "CAPTURE_VENDOR_CLEANUP", "No DISCONNECT_RECHECK event."));
        else {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(?:trackersRetained|deliveriesRetained|retained(?:Types)?)\\s*=\\s*(\\d+|\\[\\]|\\{\\})")
                .matcher(recheckDetail);
            boolean found = false, retainedZero = true;
            while (matcher.find()) {
                found = true;
                String value = matcher.group(1);
                if (!value.equals("0") && !value.equals("[]") && !value.equals("{}")) retainedZero = false;
            }
            checks.add(check("reference_recheck", found ? (retainedZero ? "PASS" : "FAIL") : "UNKNOWN",
                "CAPTURE_VENDOR_CLEANUP", bounded(recheckDetail, 320)));
        }
        if (!ownerClosed) checks.add(check("owner_closed", "UNKNOWN", "CAPTURE_VENDOR_CLEANUP", "No OWNER_CLOSED event."));
        else {
            boolean failed = ownerClosedDetail.matches(".*failure=(?!\\s*(?:$|;)).*");
            checks.add(check("owner_closed", failed ? "FAIL" : "PASS", "CAPTURE_VENDOR_CLEANUP", bounded(ownerClosedDetail, 320)));
        }
        boolean persistenceNear = selected == null || near(selected.lastUtc, persistenceUtc);
        if (!persistence.isEmpty() && !persistenceNear) {
            checks.add(check("diagnostic_journal", "UNKNOWN", "CAPTURE_PERSISTENCE_SUMMARY",
                bounded(persistence, 320) + "; outside selected cleanup owner time window"));
        } else if (!persistence.isEmpty() && persistence.matches("(?s).*journalFailure=(?!\\s*(?:[,}]|$)).+")) {
            checks.add(check("diagnostic_journal", "FAIL", "CAPTURE_PERSISTENCE_SUMMARY", bounded(persistence, 320)));
        } else {
            checks.add(check("diagnostic_journal", persistence.isEmpty() ? "UNKNOWN" : "PASS", "CAPTURE_PERSISTENCE_SUMMARY",
                persistence.isEmpty() ? "No persistence summary in the retained journal." : bounded(persistence, 320)));
        }
        if (phoneSyncSuccess == null) checks.add(check("phone_sync_result", "NOT_APPLICABLE",
            "ACTIVITY_PHONE_RECEIVE_RESULT", "No phone-side receive result in the retained journal."));
        else checks.add(check("phone_sync_result", phoneSyncSuccess ? "PASS" : "FAIL",
            "ACTIVITY_PHONE_RECEIVE_RESULT", bounded(phoneSyncText, 320)));
        boolean syncNear = selected == null || near(selected.lastUtc, syncUtc);
        if (syncSuccess == null) checks.add(check("sync_result", "NOT_APPLICABLE", "ACTIVITY_WATCH_SYNC_FINISHED", "No activity sync result in the retained journal."));
        else if (!syncNear) checks.add(check("sync_result", "UNKNOWN", "ACTIVITY_WATCH_SYNC_FINISHED",
            bounded(syncText, 320) + "; outside selected cleanup owner time window"));
        else checks.add(check("sync_result", syncSuccess ? "PASS" : "FAIL", "ACTIVITY_WATCH_SYNC_FINISHED", bounded(syncText, 320)));
        checks.add(check("exit_request", exitRequested ? "PASS" : "NOT_APPLICABLE", "EXIT_REQUESTED",
            exitRequested ? "Exit was requested; actual process/task disappearance still needs external evidence." : "No exit request event."));
        checks.add(check("hardware_stop", "UNKNOWN", "SYSTEM_DUMP_REQUIRED",
            "App journal cannot prove the PPG LED/stream stopped. Requires dumpsys + nanohub + user observation."));
        checks.add(check("system_evidence", "NOT_COLLECTED", "SYSTEM_DUMP_REQUIRED",
            "No system dump in this report. Capture via Galaxy Wearable hidden menu or adb bugreport."));

        int pass = 0, fail = 0, unknown = 0;
        for (Map<String, Object> item : checks) {
            String status = String.valueOf(item.get("status"));
            if ("PASS".equals(status)) pass++;
            else if ("FAIL".equals(status)) fail++;
            else if ("UNKNOWN".equals(status)) unknown++;
        }
        Map<String, Object> process = readProcess(snapshot, role, "process.json");
        Map<String, Object> previous = readProcess(snapshot, role, "previous-process.json");
        List<Map<String, Object>> owners = new ArrayList<>();
        for (CleanupLedger ledger : ledgers.values()) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(ledger.created);
            missing.removeAll(ledger.finalReturned);
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("ownerId", ledger.ownerId);
            owner.put("lastEventSeq", ledger.lastSequence);
            owner.put("lastUtc", ledger.lastUtc);
            owner.put("created", ledger.created);
            owner.put("finalUnsetReturned", ledger.finalReturned);
            owner.put("finalUnsetFailed", ledger.finalFailed);
            owner.put("missingFinalUnset", missing);
            owner.put("disconnectReturned", ledger.disconnectReturned);
            owner.put("disconnectFailed", ledger.disconnectFailed);
            owner.put("ownerClosed", ledger.ownerClosed);
            owner.put("clean", missing.isEmpty() && ledger.finalFailed.isEmpty() && !ledger.disconnectFailed);
            owners.add(owner);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA);
        result.put("kind", "APP_LEVEL_POSTCONDITIONS");
        result.put("generatedUtc", Instant.now().toString());
        result.put("role", role);
        result.put("source", "APP_JOURNAL_ONLY");
        result.put("process", process);
        result.put("previousProcess", previous);
        result.put("selectedCleanupOwner", selected == null ? "NONE" : selected.ownerId);
        result.put("cleanupOwners", owners);
        result.put("checks", checks);
        result.put("summary", Map.of("pass", pass, "fail", fail, "unknown", unknown,
            "overall", fail > 0 ? "FAIL" : unknown > 0 ? "INCOMPLETE" : "APP_LEVEL_CHECKS_PASS"));
        result.put("nonClaims", List.of(
            "PASS means the app observed the expected event; it does not prove the vendor service or hardware stopped.",
            "UNKNOWN means required evidence is missing, not that the system is healthy.",
            "A system dump and user observation are required for L3-L7 conclusions."));
        return new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8);
    }

    private static final class CleanupLedger {
        final String ownerId;
        long lastSequence;
        String lastUtc = "";
        final LinkedHashSet<String> created = new LinkedHashSet<>();
        final LinkedHashSet<String> finalReturned = new LinkedHashSet<>();
        final LinkedHashSet<String> finalFailed = new LinkedHashSet<>();
        final LinkedHashSet<String> intermediateFailed = new LinkedHashSet<>();
        boolean disconnectRequested, disconnectReturned, disconnectFailed, ownerClosed;
        String recheckDetail = "", ownerClosedDetail = "";
        CleanupLedger(String ownerId) { this.ownerId = ownerId; }
    }

    private static Map<String, Object> check(String id, String status, String source, String evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("status", status);
        item.put("source", source);
        item.put("evidence", evidence);
        return item;
    }

    private static boolean near(String referenceUtc, String eventUtc) {
        if (referenceUtc == null || referenceUtc.isEmpty() || eventUtc == null || eventUtc.isEmpty()) return true;
        try {
            return Math.abs(Duration.between(Instant.parse(referenceUtc), Instant.parse(eventUtc)).getSeconds()) <= 600;
        } catch (RuntimeException bad) {
            return true;
        }
    }

    private static Map<String, Object> readProcess(Map<String, byte[]> snapshot, String role, String name) {
        byte[] bytes = snapshot.get(role + "/" + name);
        if (bytes == null) return Map.of("status", "NOT_COLLECTED");
        try {
            JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            return Map.of("status", "AVAILABLE", "state", DiagnosticEvents.text(object, "state"),
                "utc", DiagnosticEvents.text(object, "utc"), "processRunId", DiagnosticEvents.text(object, "processRunId"));
        } catch (RuntimeException bad) {
            return Map.of("status", "CORRUPT_OR_UNREADABLE");
        }
    }

    private static String bounded(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
