package com.rawwave.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only, bounded evidence projection. Never infers completion from an entered stage. */
public final class DiagnosticFeedback {
    private static final Gson STRICT = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private DiagnosticFeedback() {}
    public static String bounded(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return text;
        String suffix = "\n[已截断；完整证据请看原始日志]";
        int budget = maxBytes - suffix.getBytes(StandardCharsets.UTF_8).length;
        if (budget < 0) throw new IllegalArgumentException("BOUND_TOO_SMALL");
        int end = 0, used = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + n > budget) break;
            used += n; end += Character.charCount(cp);
        }
        return text.substring(0, end) + suffix;
    }
    private static String value(JsonElement v) {
        if (v == null) return "[字段缺失]";
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }
    private static String label(String event) {
        return switch (event.replaceFirst("^ACTIVITY_", "")) {
            case "WINDOW_OPENED" -> "同步窗口已建立";
            case "ARCHIVE_PREPARING" -> "开始打包与核验本地数据";
            case "DISCOVERY_STARTED" -> "开始寻找另一端";
            case "HELLO_SENT", "HELLO_TRANSMITTED" -> "握手阶段：等待另一端准备";
            case "CHANNEL_OPEN_REQUESTED" -> "已进入建立文件通道阶段";
            case "FILE_SEND_STARTED" -> "开始发送文件";
            case "FILE_BYTES_WRITTEN" -> "已写出字节；尚不代表手机保存成功";
            case "REMOTE_COMMIT_CONFIRMED" -> "已收到手机保存校验确认";
            case "RECEIVER_READY" -> "接收端已准备";
            case "HELLO_ACCEPTED" -> "已接受握手";
            case "FILE_RECEIVE_STARTED" -> "开始接收文件";
            case "ARCHIVE_VERIFYING" -> "开始逐文件核验";
            case "FILE_COMMITTED" -> "本端已保存并校验，尚需对端确认";
            case "WATCH_CONFIRM_RECEIVED" -> "已收到手表确认";
            case "TRANSFER_FAILED", "FAULT" -> "记录到异常，原因见下方";
            default -> "操作事件（保留原始代码）";
        };
    }
    private static String shortValue(JsonObject d, String key, int limit) {
        if (!d.has(key)) return "";
        String value = value(d.get(key));
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
    private static String processPage(Map<String, byte[]> snapshot, String role) {
        StringBuilder out = new StringBuilder("运行状态\n");
        for (String name : List.of("previous-process.json", "process.json")) {
            byte[] bytes = snapshot.get(role + "/" + name);
            if (bytes == null) { out.append(name).append(": 无记录\n"); continue; }
            try {
                JsonObject p = STRICT.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
                out.append(name.startsWith("previous") ? "上一次" : "当前").append(": ")
                    .append(value(p.get("state"))).append("\nUTC ").append(value(p.get("utc")))
                    .append("\n进程 ").append(shortValue(p,"processRunId",13)).append("\n");
            } catch (RuntimeException bad) { out.append(name).append(": 无法解析，完整报告保留原文\n"); }
        }
        out.append("RUNNING只表示最后记录仍在运行，不等于已证明崩溃。\nCRASH_OBSERVED仅表示应用捕获到崩溃；无标记不能排除系统终止。");
        return bounded(out.toString(), 2300);
    }
    /** Five-photo default: the viewer prepends its version page to these four evidence pages. */
    public static List<String> quickPages(Map<String, byte[]> snapshot, String role) {
        List<String> latestWindow = new ArrayList<>(), latestCapture = new ArrayList<>(), latestDiagnostic = new ArrayList<>();
        String latestWindowKey = "", latestActivityResult = "未观察到", latestCaptureSession = "未观察到";
        int corrupt = 0, silence = 0, resumed = 0;
        for (String name : List.of("events.previous.jsonl", "events.jsonl")) {
            byte[] bytes = snapshot.get(role + "/" + name);
            if (bytes == null) continue;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonObject e;
                try {
                    JsonElement parsed = STRICT.fromJson(line, JsonElement.class);
                    if (parsed == null || !parsed.isJsonObject()) { corrupt++; continue; }
                    e = parsed.getAsJsonObject();
                } catch (RuntimeException bad) { corrupt++; continue; }
                String kind = value(e.get("event"));
                JsonElement raw = e.get("details");
                JsonObject d = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
                String compact = kind + "\nUTC " + value(e.get("utc"));
                for (String key : List.of("transferId","success","trigger","lastStage","error","text","detail","reason","remainingMs")) {
                    String v = shortValue(d,key,key.equals("text") || key.equals("detail") ? 180 : 72);
                    if (!v.isEmpty()) compact += "\n" + key + ": " + v;
                }
                // The independent diagnostic-summary channel has its own identity. Never let it
                // replace the latest activity-transfer window in the five-photo summary.
                if (d.has("windowId") && !kind.startsWith("DIAGNOSTIC_SUMMARY_")) {
                    String key = value(d.get("windowId"));
                    if (!key.equals(latestWindowKey)) { latestWindowKey = key; latestWindow.clear(); }
                    latestWindow.add(compact);
                    if (latestWindow.size() > 7) latestWindow.remove(0);
                }
                if (kind.startsWith("CAPTURE_")) {
                    if (kind.equals("CAPTURE_DATA_SILENCE_OBSERVED")) { silence++; continue; }
                    if (kind.equals("CAPTURE_DATA_RESUMED")) { resumed++; continue; }
                    latestCapture.add(compact);
                    if (latestCapture.size() > 5) latestCapture.remove(0);
                    String session = shortValue(d,"session",13);
                    if (!session.isEmpty()) latestCaptureSession = session;
                }
                if (kind.equals("ACTIVITY_WATCH_SYNC_FINISHED")) latestActivityResult = compact;
                if (kind.startsWith("DIAGNOSTIC_SUMMARY_")) {
                    latestDiagnostic.add(compact);
                    if (latestDiagnostic.size() > 5) latestDiagnostic.remove(0);
                }
            }
        }
        String overview = "快速诊断 · " + role + "\n默认共5页，适合依次拍照；完整详情仍可查看。\n" +
            "只显示本机已观察证据，不把步骤开始当成功。\n字节写完不等于手机已保存；必须看到 REMOTE_COMMIT_CONFIRMED / FILE_COMMITTED 或摘要 SAVE_CONFIRMED。\n" +
            "历史日志已轮转：" + snapshot.containsKey(role + "/rotation.json") + "\n无法解析事件：" + corrupt +
            "\n批量回调静默/恢复已聚合：" + silence + "/" + resumed +
            "\n这些提示不等于已证实丢样，完整原始事件仍在ZIP。";
        String capture = "最近采集与活动同步\n采集session: " + latestCaptureSession + "\n" +
            (latestCapture.isEmpty() ? "无采集事件\n" : String.join("\n---\n",latestCapture)) +
            "\n\n最近活动同步结果\n" + latestActivityResult;
        String transfer = "最近传输窗口\nwindowId: " + (latestWindowKey.isEmpty() ? "无" : latestWindowKey) + "\n" +
            (latestWindow.isEmpty() ? "无传输阶段" : String.join("\n→\n", latestWindow));
        StringBuilder diagnostic = new StringBuilder("独立摘要与最后故障\n");
        diagnostic.append(latestDiagnostic.isEmpty() ? "无独立摘要事件\n" : String.join("\n---\n", latestDiagnostic)).append("\n");
        byte[] fault = snapshot.get(role + "/last-fault.json");
        if (fault == null) diagnostic.append("最后故障：无记录");
        else try {
            JsonObject f = STRICT.fromJson(new String(fault,StandardCharsets.UTF_8),JsonObject.class);
            diagnostic.append("最后保存故障（可能是旧操作）\nUTC ").append(value(f.get("utc")))
                .append("\nkind: ").append(value(f.get("kind"))).append("\ntype: ").append(value(f.get("type")))
                .append("\nmessage: ").append(bounded(value(f.get("message")),640));
        } catch (RuntimeException bad) { diagnostic.append("最后故障无法解析，完整报告保留原文"); }
        return List.of(bounded(overview,2300), processPage(snapshot,role), bounded(capture,3000),
            bounded(transfer + "\n\n" + diagnostic,3900));
    }
    public static List<String> pages(Map<String, byte[]> snapshot, String role) {
        List<String> events = new ArrayList<>();
        int corrupt = 0, malformed = 0, omitted = 0;
        Map<String,Integer> batching = new TreeMap<>();
        for (String name : List.of("events.previous.jsonl", "events.jsonl")) {
            byte[] bytes = snapshot.get(role + "/" + name);
            if (bytes == null) continue;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonElement parsed;
                try { parsed = STRICT.fromJson(line, JsonElement.class); }
                catch (RuntimeException invalid) { corrupt++; continue; }
                if (parsed == null || !parsed.isJsonObject()) { malformed++; events.add(bounded("事件结构异常，保留原文\n" + line, 2048)); }
                else {
                    JsonObject e = parsed.getAsJsonObject();
                    String kind = value(e.get("event"));
                    JsonElement rawDetails = e.get("details");
                    JsonObject d = rawDetails != null && rawDetails.isJsonObject() ? rawDetails.getAsJsonObject() : new JsonObject();
                    if (!(kind.contains("ACTIVITY_") || kind.startsWith("DIAGNOSTIC_") || kind.contains("FAULT") || kind.contains("SYNC") ||
                        kind.contains("CAPTURE_") || kind.equals("PROCESS_STARTED") || kind.contains("DEADLINE") || d.has("windowId") || kind.contains("CLEANUP"))) continue;
                    if (kind.equals("CAPTURE_DATA_SILENCE_OBSERVED") || kind.equals("CAPTURE_DATA_RESUMED")) {
                        String detail = shortValue(d,"detail",96);
                        String sensor = detail.contains(":") ? detail.substring(0,detail.indexOf(':')) : "UNKNOWN";
                        batching.merge(sensor + (kind.endsWith("RESUMED") ? " 恢复" : " 静默"),1,Integer::sum);
                        continue;
                    }
                    String text = label(kind) + "\n" + kind + "\nUTC " + value(e.get("utc")) +
                        "\n进程 " + value(e.get("processRunId")) + "\n事件 #" + value(e.get("eventSeq"));
                    if (rawDetails == null || !rawDetails.isJsonObject()) { malformed++; text += "\ndetails结构异常: " + value(rawDetails); }
                    for (String key : List.of("windowId", "transferId", "text", "detail", "success", "trigger", "previousState", "session", "sessionId", "sensor", "phase", "senderWindow", "lastStage", "error", "kind", "type", "message", "reason", "source", "remainingMs", "bytesExpected", "bytesWritten", "bytesReceived", "bytesCommitted"))
                        if (d.has(key)) text += "\n" + key + ": " + bounded(value(d.get(key)), 640);
                    events.add(bounded(text, 2048));
                }
                if (events.size() > 40) { events.remove(0); omitted++; }
            }
        }
        List<String> result = new ArrayList<>();
        result.add("诊断快照 v2 · " + role + "\n仅本机证据；按日志写入顺序倒序，最多40条；不按UTC跨进程重排。\n" +
            "步骤开始不代表成功；字节发完不代表手机已保存。\n" +
            "REMOTE_COMMIT_CONFIRMED：本端收到手机保存确认。\nFILE_COMMITTED：本端保存校验完成。\n" +
            "不同 windowId / 进程不能拼成一次同步。\n无记录不代表没有故障。\n历史可能已轮转：" +
            snapshot.containsKey(role + "/rotation.json") + "\n损坏事件行：" + corrupt +
            "\n结构异常（保留可读证据）：" + malformed + "\n超出40条省略：" + omitted +
            "\n只读，不启动传输、不延长同步期限。请拍下版本页、失败页和相邻步骤。时间为 UTC。" );
        Collections.reverse(events);
        for (String name : List.of("previous-process.json", "process.json")) {
            byte[] content = snapshot.get(role + "/" + name);
            if (content != null) result.add(bounded("运行状态 " + name + "\nCRASH_OBSERVED仅表示应用捕获到崩溃；无标记不能排除系统终止。\n" + new String(content, StandardCharsets.UTF_8),4096));
        }
        if (!batching.isEmpty()) {
            StringBuilder aggregate = new StringBuilder("批量回调提示聚合\n这些提示不等于已证实丢样；原始事件仍在events.jsonl。\n");
            batching.forEach((key,count) -> aggregate.append(key).append(": ").append(count).append("\n"));
            result.add(bounded(aggregate.toString(),2048));
        }
        result.addAll(events);
        for (String name : List.of("first-fault.json", "last-fault.json")) {
            byte[] content = snapshot.get(role + "/" + name);
            if (content != null) result.add(bounded("运行/故障证据 " + name + "（可能属于旧操作，不代表当前仍失败）\nCRASH_OBSERVED表示应用捕获到崩溃；无该标记不能排除系统终止。\n" + new String(content, StandardCharsets.UTF_8), 4096));
        }
        return List.copyOf(result);
    }
    public static byte[] report(Map<String, byte[]> snapshot, String role) {
        return bounded(String.join("\n\n---\n\n", pages(snapshot, role)),104448).getBytes(StandardCharsets.UTF_8);
    }
}
