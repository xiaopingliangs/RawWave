package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DiagnosticFeedbackTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();
    @Test public void summaryStoragePersistsExactBytesBeforeReceiptAndReplacesLatest() throws Exception {
        var folder=temp.newFolder();var store=new FileSummaryStore(folder,d->{});
        var receiver=new SummaryReceiver(store);
        byte[] first=DiagnosticSummary.create(Map.of(),"first");
        assertTrue(DiagnosticSummary.acceptsAck(receiver.receive(first),first));
        byte[] second=DiagnosticSummary.create(Map.of(),"second");
        assertTrue(DiagnosticSummary.acceptsAck(receiver.receive(second),second));
        assertArrayEquals(second,java.nio.file.Files.readAllBytes(new java.io.File(folder,"diagnostic-summary/latest.json").toPath()));
        assertTrue(DiagnosticSummary.acceptsAck(receiver.receive(second),second));
    }
    @Test public void summaryDurabilityFailureCannotReturnReceiptAndCanRetry() throws Exception {
        var folder=temp.newFolder();byte[] bytes=DiagnosticSummary.create(Map.of(),"test");
        assertThrows(java.io.IOException.class,()->new SummaryReceiver(new FileSummaryStore(folder,d->{
            throw new java.io.IOException("directory sync failed");
        })).receive(bytes));
        assertTrue(DiagnosticSummary.acceptsAck(new SummaryReceiver(new FileSummaryStore(folder,d->{})).receive(bytes),bytes));
    }
    @Test public void summaryReadbackFailureCannotReturnReceipt() throws Exception {
        var folder=temp.newFolder();byte[] bytes=DiagnosticSummary.create(Map.of(),"test");
        var receiver=new SummaryReceiver(new FileSummaryStore(folder,d->{
            if(d.getName().equals("diagnostic-summary"))java.nio.file.Files.write(new java.io.File(d,"latest.json").toPath(),new byte[]{1});
        }));
        assertThrows(java.io.IOException.class,()->receiver.receive(bytes));
    }
    @Test public void invalidSummaryDoesNotReachReplacementAndFailedSaveCannotAck() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var receiver=new SummaryReceiver(bytes->{calls.incrementAndGet();throw new java.io.IOException("replacement failed");});
        assertThrows(RuntimeException.class,()->receiver.receive(new byte[4097]));assertEquals(0,calls.get());
        assertThrows(java.io.IOException.class,()->receiver.receive(DiagnosticSummary.create(Map.of(),"test")));
        assertEquals(1,calls.get());
    }
    @Test public void summaryReplacementReceivesCopyAndAckStillBindsOriginalBytes() throws Exception {
        byte[] bytes=DiagnosticSummary.create(Map.of(),"test"), original=bytes.clone();
        var receiver=new SummaryReceiver(saved->java.util.Arrays.fill(saved,(byte)0));
        assertTrue(DiagnosticSummary.acceptsAck(receiver.receive(bytes),original));assertArrayEquals(original,bytes);
    }
    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private String event(int n, String kind, String window) {
        return "{\"event\":\"" + kind + "\",\"utc\":\"2026-10-19T00:00:00Z\",\"processRunId\":\"p\",\"eventSeq\":" + n + ",\"details\":{\"windowId\":\"" + window + "\"}}\n";
    }
    @Test public void emptyIsUnknownNotSuccess() {
        String report = new String(DiagnosticFeedback.report(Map.of(), "watch"), StandardCharsets.UTF_8);
        assertTrue(report.contains("无记录不代表没有故障"));
    }
    @Test public void reverseOrderPreservesWindowIdentity() {
        var pages = DiagnosticFeedback.pages(Map.of("watch/events.jsonl", bytes(event(1,"ACTIVITY_TRANSFER_FAILED","old") + event(2,"ACTIVITY_WINDOW_OPENED","new"))), "watch");
        assertTrue(pages.get(1).contains("windowId: new"));
        assertTrue(pages.get(2).contains("windowId: old"));
    }
    @Test public void damagedLineDoesNotHideLaterEvidence() {
        var pages = DiagnosticFeedback.pages(Map.of("watch/events.jsonl", bytes("broken\n" + event(2,"ACTIVITY_FILE_BYTES_WRITTEN","w"))), "watch");
        assertTrue(pages.get(0).contains("损坏事件行：1"));
        assertTrue(pages.get(1).contains("ACTIVITY_FILE_BYTES_WRITTEN"));
        assertFalse(pages.get(1).contains("REMOTE_COMMIT_CONFIRMED"));
    }
    @Test public void rotationAndBoundAreVisible() {
        StringBuilder lines = new StringBuilder();
        for (int i=0;i<100;i++) lines.append(event(i,"ACTIVITY_WINDOW_OPENED","w"));
        var pages = DiagnosticFeedback.pages(Map.of("watch/events.jsonl", bytes(lines.toString()), "watch/rotation.json", bytes("{}")), "watch");
        assertEquals(41,pages.size());
        assertTrue(pages.get(0).contains("历史可能已轮转：true"));
        assertTrue(pages.get(1).contains("事件 #99"));
    }
    @Test public void previousLogAndStaleFaultRemainSeparate() {
        var pages = DiagnosticFeedback.pages(Map.of("watch/events.previous.jsonl", bytes(event(1,"ACTIVITY_TRANSFER_FAILED","old")), "watch/last-fault.json", bytes("历史错误")), "watch");
        assertEquals(3,pages.size());
        assertTrue(pages.get(2).contains("可能属于旧操作"));
        assertTrue(pages.get(2).contains("历史错误"));
    }
    @Test public void captureAndProcessFactsAreVisible() {
        String line = event(1,"CAPTURE_SOURCE_ERROR","w").replace("\"windowId\":\"w\"", "\"detail\":\"EDA失败\",\"session\":\"s\",\"text\":\"停在打包\",\"success\":false,\"trigger\":\"USER\"");
        String report = new String(DiagnosticFeedback.report(Map.of("watch/events.jsonl",bytes(line+event(2,"PROCESS_STARTED","w")),"watch/previous-process.json",bytes("{\"state\":\"CRASH_OBSERVED\"}")),"watch"),StandardCharsets.UTF_8);
        for(String text:List.of("EDA失败","停在打包","success: false","trigger: USER","PROCESS_STARTED","previous-process.json","CRASH_OBSERVED","session: s")) assertTrue(text,report.contains(text));
    }
    @Test public void structuredValuesDoNotDiscardEvidence() {
        String line = event(1,"FAULT","w").replace("\"windowId\":\"w\"", "\"error\":{\"code\":3},\"detail\":[1,2],\"text\":null");
        String report = new String(DiagnosticFeedback.report(Map.of("watch/events.jsonl",bytes(line)),"watch"),StandardCharsets.UTF_8);
        assertTrue(report.contains("error: {\"code\":3}")); assertTrue(report.contains("detail: [1,2]"));
        assertTrue(report.contains("text: null")); assertTrue(report.contains("损坏事件行：0"));
    }
    @Test public void invalidStructureIsNotMisreportedAsBrokenJson() {
        String report = new String(DiagnosticFeedback.report(Map.of("watch/events.jsonl",bytes("[]\n")),"watch"),StandardCharsets.UTF_8);
        assertTrue(report.contains("结构异常（保留可读证据）：1")); assertTrue(report.contains("损坏事件行：0"));
    }
    @Test public void utf8BoundNeverSplitsSupplementaryCharacter() {
        String bounded=DiagnosticFeedback.bounded("😀中文".repeat(500),640);
        assertTrue(bytes(bounded).length<=640); assertTrue(bounded.contains("已截断"));
        assertEquals(bounded,new String(bytes(bounded),StandardCharsets.UTF_8));
    }
    @Test public void summaryIsBoundedAndRequiresMatchingSaveReceipt() {
        byte[] payload=DiagnosticSummary.create(Map.of("watch/events.jsonl",bytes(event(1,"FAULT","w").replace("\"windowId\":\"w\"","\"error\":\""+"错误😀".repeat(5000)+"\""))),"test");
        assertTrue(payload.length<=4096); assertTrue(DiagnosticSummary.validate(payload).get("partial").getAsBoolean());
        assertTrue(DiagnosticSummary.acceptsAck(DiagnosticSummary.ack(payload),payload));
        assertFalse(DiagnosticSummary.acceptsAck(DiagnosticSummary.ack(DiagnosticSummary.create(Map.of(),"other")),payload));
        assertFalse(DiagnosticSummary.acceptsAck(bytes("{}"),payload));
    }
    @Test public void summaryRejectsOversizeAndWrongVersion() {
        try { DiagnosticSummary.validate(new byte[4097]); fail(); } catch(IllegalArgumentException expected) {}
        byte[] payload=DiagnosticSummary.create(Map.of(),"test");
        try { DiagnosticSummary.validate(bytes(new String(payload,StandardCharsets.UTF_8).replace("\"schemaVersion\":1","\"schemaVersion\":2"))); fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void quickViewIsFourEvidencePagesForFivePhotosWithVersion() {
        String events = event(1,"CAPTURE_START","capture").replace("\"windowId\":\"capture\"","\"session\":\"session-123456789\",\"detail\":\"start\"") +
            event(2,"ACTIVITY_FILE_BYTES_WRITTEN","transfer-window").replace("\"windowId\":\"transfer-window\"","\"windowId\":\"transfer-window\",\"transferId\":\"transfer-123\"") +
            event(3,"ACTIVITY_REMOTE_COMMIT_CONFIRMED","transfer-window").replace("\"windowId\":\"transfer-window\"","\"windowId\":\"transfer-window\",\"transferId\":\"transfer-123\"") +
            event(4,"ACTIVITY_WATCH_SYNC_FINISHED","result").replace("\"windowId\":\"result\"","\"success\":true,\"text\":\"保存成功\"") +
            event(5,"DIAGNOSTIC_SUMMARY_SAVE_CONFIRMED","diagnostic");
        var quick=DiagnosticFeedback.quickPages(Map.of("watch/events.jsonl",bytes(events),
            "watch/previous-process.json",bytes("{\"state\":\"RUNNING\",\"utc\":\"old\",\"processRunId\":\"p-old\"}")),"watch");
        assertEquals(4,quick.size());
        assertTrue(quick.get(0).contains("默认共5页"));
        assertTrue(quick.get(1).contains("上一次: RUNNING"));
        assertTrue(quick.get(2).contains("保存成功"));
        assertTrue(quick.get(3).contains("ACTIVITY_REMOTE_COMMIT_CONFIRMED"));
        assertTrue(quick.get(3).contains("DIAGNOSTIC_SUMMARY_SAVE_CONFIRMED"));
    }
    @Test public void batchingNoiseIsAggregatedWithoutConsumingFortyEvidenceSlots() {
        StringBuilder lines=new StringBuilder();
        for(int i=0;i<70;i++) lines.append(event(i,i%2==0?"CAPTURE_DATA_SILENCE_OBSERVED":"CAPTURE_DATA_RESUMED","noise")
            .replace("\"windowId\":\"noise\"","\"detail\":\"ACCELEROMETER: SDK buffering unknown\""));
        lines.append(event(71,"ACTIVITY_REMOTE_COMMIT_CONFIRMED","important"));
        var full=DiagnosticFeedback.pages(Map.of("watch/events.jsonl",bytes(lines.toString())),"watch");
        String joined=String.join("\n",full);
        assertTrue(joined.contains("批量回调提示聚合"));
        assertTrue(joined.contains("ACCELEROMETER 静默: 35"));
        assertTrue(joined.contains("ACCELEROMETER 恢复: 35"));
        assertTrue(joined.contains("ACTIVITY_REMOTE_COMMIT_CONFIRMED"));
        assertTrue(joined.contains("超出40条省略：0"));
        assertFalse(joined.contains("CAPTURE_DATA_SILENCE_OBSERVED"));
    }
}
