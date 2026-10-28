package com.rawwave.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Independent v1 message contract. Never a health-data receipt or a complete report. */
public final class DiagnosticSummary {
    public static final int MAX_BYTES = 4096;
    public static final String PATH = "/rawwave/diagnostic/v1/summary", ACK = "/rawwave/diagnostic/v1/ack";
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private DiagnosticSummary() {}
    public static byte[] create(Map<String, byte[]> snapshot, String build) {
        List<String> pages = DiagnosticFeedback.pages(snapshot, "watch");
        String text = "有限摘要；不证明健康数据已同步；完整日志留在手表。\n";
        // Latest entered events before old process/fault files; do not imply old fault is current.
        for (int i=1; i<pages.size(); i++) text += pages.get(i) + "\n---\n";
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion",1); o.addProperty("kind","DIAGNOSTIC_SUMMARY");
        o.addProperty("id",UUID.randomUUID().toString()); o.addProperty("createdUtc",Instant.now().toString());
        o.addProperty("role","watch"); o.addProperty("build",build);
        o.addProperty("partial",true); o.addProperty("coverage",pages.get(0));
        int budget = 2300;
        byte[] bytes;
        do { o.addProperty("text",DiagnosticFeedback.bounded(text,budget)); bytes=GSON.toJson(o).getBytes(StandardCharsets.UTF_8); budget-=128; }
        while(bytes.length>MAX_BYTES && budget>=128);
        if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("SUMMARY_METADATA_TOO_LARGE");
        return bytes;
    }
    public static JsonObject validate(byte[] data) {
        if (data.length>MAX_BYTES) throw new IllegalArgumentException("SUMMARY_TOO_LARGE");
        JsonObject o=GSON.fromJson(new String(data,StandardCharsets.UTF_8),JsonObject.class);
        if(!o.get("schemaVersion").getAsString().equals("1") || !o.get("kind").getAsString().equals("DIAGNOSTIC_SUMMARY") ||
            !o.get("role").getAsString().equals("watch") || !o.get("partial").getAsBoolean()) throw new IllegalArgumentException("SUMMARY_SCHEMA");
        UUID.fromString(o.get("id").getAsString()); Instant.parse(o.get("createdUtc").getAsString());
        for(String k:List.of("text","build","coverage")) if(!o.get(k).isJsonPrimitive() || !o.get(k).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("SUMMARY_FIELD");
        return o;
    }
    public static void check(byte[] data) { validate(data); }
    public static String id(byte[] data) { return validate(data).get("id").getAsString(); }
    public static byte[] ack(byte[] data) {
        return GSON.toJson(Map.of("schemaVersion",1,"id",validate(data).get("id").getAsString(),"sha256",DiagnosticArchive.hash(data),"state","SAVED" )).getBytes(StandardCharsets.UTF_8);
    }
    public static boolean acceptsAck(byte[] ack, byte[] sent) {
        try {
            if(ack.length>512) return false;
            JsonObject o=JsonParser.parseString(new String(ack,StandardCharsets.UTF_8)).getAsJsonObject();
            return o.get("schemaVersion").getAsInt()==1 && o.get("state").getAsString().equals("SAVED") &&
                o.get("id").getAsString().equals(validate(sent).get("id").getAsString()) &&
                o.get("sha256").getAsString().equals(DiagnosticArchive.hash(sent));
        }
        catch(RuntimeException bad) { return false; }
    }
}
