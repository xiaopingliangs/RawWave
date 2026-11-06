package com.rawwave.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, read-only projection of the existing JSONL evidence. Never parses health samples. */
final class DiagnosticEvents {
    private static final Gson STRICT = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private DiagnosticEvents() {}

    static final class Read {
        final List<JsonObject> events = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        int corruptLines;
        boolean rotationObserved;
    }

    static Read read(Map<String, byte[]> snapshot, String role, boolean observations) {
        Read result = new Read();
        List<String> names = observations
            ? List.of("diagnostic-observations.previous.jsonl", "diagnostic-observations.jsonl")
            : List.of("events.previous.jsonl", "events.jsonl");
        for (String name : names) {
            byte[] bytes = snapshot.get(role + "/" + name);
            if (bytes == null) continue;
            result.files.add(role + "/" + name);
            if (name.contains("previous") || name.contains("observation-rotation")) result.rotationObserved = true;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                try {
                    JsonElement parsed = STRICT.fromJson(line, JsonElement.class);
                    if (parsed == null || !parsed.isJsonObject()) { result.corruptLines++; continue; }
                    result.events.add(parsed.getAsJsonObject());
                } catch (RuntimeException bad) {
                    result.corruptLines++;
                }
            }
        }
        if (snapshot.containsKey(role + "/rotation.json") || snapshot.containsKey(role + "/observation-rotation.json"))
            result.rotationObserved = true;
        return result;
    }

    static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    static JsonObject details(JsonObject event) {
        JsonElement value = event == null ? null : event.get("details");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String detail(JsonObject event) {
        return text(details(event), "detail");
    }

    static String eventName(JsonObject event) {
        return text(event, "event");
    }

    static long longValue(JsonObject object, String name) {
        try { return object != null && object.has(name) ? object.get(name).getAsLong() : 0L; }
        catch (RuntimeException bad) { return 0L; }
    }
}
