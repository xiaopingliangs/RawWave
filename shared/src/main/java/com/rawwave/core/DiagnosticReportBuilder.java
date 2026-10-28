package com.rawwave.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Diagnostic content policy, separate from UI, platform publication and transfer protocol. */
public final class DiagnosticReportBuilder implements ReportBuilder {
    private final Journal journal;
    private final File receivedFolder, reportFolder;
    private final String role;
    private final File summaryFile;
    private final Map<String, Object> build;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public DiagnosticReportBuilder(Journal journal, File receivedFolder, File reportFolder,
                                   String role, Map<String, Object> build) {
        this(journal, receivedFolder, reportFolder, role, build, null);
    }
    public DiagnosticReportBuilder(Journal journal, File receivedFolder, File reportFolder,
                                   String role, Map<String, Object> build, File summaryFile) {
        this.summaryFile = summaryFile;
        this.journal = journal; this.receivedFolder = receivedFolder; this.reportFolder = reportFolder;
        this.role = role; this.build = Map.copyOf(build);
        if (!role.equals("phone") && !role.equals("watch")) throw new IllegalArgumentException("INVALID_REPORT_ROLE");
    }
    private void json(Map<String, byte[]> payload, String name, Object value) {
        payload.put(name, gson.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
    private List<File> zipFiles(File folder, boolean cache) throws IOException {
        if (!folder.exists()) return new ArrayList<>();
        File[] files = folder.listFiles(f -> f.isFile() && f.getName().endsWith(".zip") && (!cache || f.getName().startsWith("rawwave-")));
        if (files == null) throw new IOException("REPORT_DIRECTORY_UNREADABLE: " + folder.getName());
        List<File> result = new ArrayList<>(Arrays.asList(files));
        result.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        return result;
    }
    @Override public Report build(long operation) throws IOException {
        ReportExporter.checkCancelled();
        Map<String, byte[]> payload = new HashMap<>(journal.snapshot());
        payload.put("diagnostic-feedback.txt", DiagnosticFeedback.report(payload, role));
        String summaryStatus = "NOT_COLLECTED";
        if (summaryFile != null && summaryFile.isFile()) {
            try {
                if (summaryFile.length() > DiagnosticSummary.MAX_BYTES) throw new IOException("SUMMARY_TOO_LARGE");
                byte[] bytes = Files.readAllBytes(summaryFile.toPath());
                DiagnosticSummary.validate(bytes);
                payload.put("received-summary/latest.json", bytes);
                summaryStatus = "CACHED_PARTIAL_SUMMARY_CHECK_CREATED_UTC_NOT_CURRENT_WINDOW_PROOF";
            } catch (Exception failure) { summaryStatus = "CORRUPT_OR_UNREADABLE: " + failure.getClass().getSimpleName(); }
        }
        json(payload, "summary-collection.json", Map.of("status", summaryStatus, "retention", "LATEST_ONLY", "notFullWatchReport", true));
        payload.put("summary.md", ("# 原波 G2a 问题报告\n\n设备角色：" + role + "\n构建：" + build.get("buildRun") + "\n" +
            "活动数据与问题报告独立打包和传输；本报告不含活动原始数据，不能单独证明活动完整性。\n上次状态：" + journal.previousState() +
            "\n应用日志不能证明退出后无耗电；系统观察未收集。\n查找 FAULT 事件及 first-fault/last-fault；TEST_FIXTURE 为人工测试，不是真实健康数据。\n").getBytes(StandardCharsets.UTF_8));
        json(payload, "build.json", build);
        json(payload, "module-contracts.json", ModuleVersions.contracts());
        List<File> received = zipFiles(receivedFolder, false);
        List<String> included = new ArrayList<>(), rejected = new ArrayList<>();
        for (File file : received.subList(0, Math.min(3, received.size()))) {
            ReportExporter.checkCancelled();
            String id = file.getName().substring(0, file.getName().length() - 4);
            File receipt = new File(receivedFolder, id + ".receipt.json");
            try {
                if (file.length() > TransferFiles.MAX_BYTES || receipt.length() > 65536) throw new IOException("ATTACHMENT_LIMIT_EXCEEDED");
                byte[] proofBytes = Files.readAllBytes(receipt.toPath());
                JsonObject proof = JsonParser.parseString(new String(proofBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                byte[] content = Files.readAllBytes(file.toPath());
                if (content.length > TransferFiles.MAX_BYTES || !proof.get("id").getAsString().equals(id) ||
                    proof.get("bytes").getAsLong() != content.length || !proof.get("sha256").getAsString().equals(DiagnosticArchive.hash(content)))
                    throw new IOException("ATTACHMENT_RECEIPT_MISMATCH");
                payload.put("received-watch/" + file.getName(), content);
                payload.put("received-watch/" + receipt.getName(), proofBytes);
                included.add(file.getName());
            } catch (Exception failure) { rejected.add(file.getName() + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        }
        json(payload, "collection-status.json", Map.of("localLogs", "AVAILABLE", "otherDevice", included.isEmpty() ?
            "NOT_COLLECTED_OR_NOT_VERIFIED" : "CACHED_RECEIVED_LAST_3_NOT_CURRENT_WINDOW_PROOF", "receivedCount", received.size(),
            "included", included, "rejected", rejected, "omittedOlderCount", Math.max(0, received.size()-3),
            "systemPowerEvidence", "NOT_COLLECTED", "healthData", "NOT_INCLUDED_IN_THIS_REPORT_EXPORT_SEPARATELY"));
        json(payload, "assessment.json", Map.of("facts", List.of("此报告仅含诊断，不含手表活动原始数据；CAPTURE事件记录采集阶段与失败", "同步有一次性限时服务和有超时的CPU锁；活动采集由独立health服务持锁，无Job/Alarm/自动重试"),
            "unknown", List.of("实际退出后的PID与系统资源", "整机耗电根因"),
            "nextCheck", "核对双端transferId、接收字节、COMMITTED、回执和退出；旧手表附件不代表本轮成功"));
        json(payload, "file-audit.json", Map.of("healthSessions", "NOT_VERIFIED_BY_THIS_REPORT", "reportValidation", "ZIP_REOPEN_AND_TARGET_SHA256"));
        payload.put("resources.jsonl", (new Gson().toJson(Map.of("owner", operation, "resource", "diagnostic_export_worker",
            "state", "ACTIVE_AT_SNAPSHOT", "systemVerification", "NOT_COLLECTED")) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(reportFolder.toPath());
        List<File> cached = zipFiles(reportFolder, true);
        for (File old : cached.subList(Math.min(2, cached.size()), cached.size())) Files.delete(old.toPath());
        ReportExporter.checkCancelled();
        File output = DiagnosticArchive.create(new File(reportFolder, "rawwave-" + role + "-" + UUID.randomUUID() + ".zip"), payload);
        return new Report(output, included.size());
    }
}
