package com.rawwave.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Low-rate operational evidence only. Never use this rotating log for health samples. */
public final class Journal {
    public static final int MAX_LOG_BYTES = 262144;
    private final File directory;
    private final Gson gson = new Gson();
    private final String runId = UUID.randomUUID().toString();
    private final String role;
    private long sequence;
    private String previousState = "NO_PREVIOUS_RECORD";

    public Journal(File directory, String role) throws IOException {
        this.directory = directory;
        this.role = role;
        Files.createDirectories(directory.toPath());
        File marker = new File(directory, "process.json");
        if (marker.isFile()) {
            try {
                Map<String, Object> previous = gson.fromJson(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
                previousState = Objects.toString(previous.get("state"), "UNKNOWN");
                durableReplace(new File(directory, "previous-process.json"), gson.toJson(previous));
            } catch (Exception failure) {
                previousState = "CORRUPT_PROCESS_MARKER";
            }
        }
        mark("RUNNING");
        event("PROCESS_STARTED", Map.of("previousState", previousState,
            "previousPidAbsence", "NOT_EXTERNALLY_OBSERVED"));
    }

    public synchronized void event(String kind, Map<String, ?> details) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", 1);
        event.put("processRunId", runId);
        event.put("deviceRole", role);
        event.put("eventSeq", ++sequence);
        event.put("utc", Instant.now().toString());
        event.put("monotonicNanos", System.nanoTime());
        event.put("event", kind);
        event.put("details", details);
        byte[] data = (gson.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_LOG_BYTES / 2) throw new IOException("EVENT_TOO_LARGE");
        File current = new File(directory, "events.jsonl");
        if (current.length() + data.length > MAX_LOG_BYTES) {
            Files.move(current.toPath(), new File(directory, "events.previous.jsonl").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            durableReplace(new File(directory, "rotation.json"), gson.toJson(Map.of(
                "lastRotationUtc", Instant.now().toString(), "historicalEvidenceMayBeMissing", true)));
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    public synchronized void fault(String kind, Throwable error) throws IOException {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String stack = trace.toString();
        boolean truncated = stack.length() > 24000;
        Map<String, Object> fault = Map.of("kind", kind, "processRunId", runId,
            "utc", Instant.now().toString(), "type", error.getClass().getName(),
            "message", Objects.toString(error.getMessage(), ""),
            "stack", stack.substring(0, Math.min(stack.length(), 24000)), "truncated", truncated);
        event("FAULT", fault);
        File first = new File(directory, "first-fault.json");
        if (!first.exists()) durableReplace(first, gson.toJson(fault));
        durableReplace(new File(directory, "last-fault.json"), gson.toJson(fault));
    }

    public synchronized void mark(String state) throws IOException {
        durableReplace(new File(directory, "process.json"), gson.toJson(Map.of(
            "processRunId", runId, "state", state, "utc", Instant.now().toString())));
    }

    public synchronized Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (String name : List.of("events.jsonl", "events.previous.jsonl", "process.json",
                "previous-process.json", "rotation.json", "first-fault.json", "last-fault.json")) {
            File file = new File(directory, name);
            if (file.isFile()) files.put(role + "/" + name, Files.readAllBytes(file.toPath()));
        }
        return files;
    }

    public String previousState() { return previousState; }
    public String runId() { return runId; }

    private static void durableReplace(File target, String value) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("ATOMIC_REPLACE_UNSUPPORTED", failure);
        }
        // Directory durability and sudden-power-loss behaviour require device testing.
    }
}
