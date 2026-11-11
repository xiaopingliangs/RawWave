package com.rawwave.core;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

public class ReportModulesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File logs, received, reports;
    private Journal journal;
    private DiagnosticReportBuilder builder;
    @Before public void setup() throws Exception {
        logs=temp.newFolder(); received=temp.newFolder(); reports=temp.newFolder();
        journal=new Journal(logs,"phone");
        builder=new DiagnosticReportBuilder(journal,received,reports,"phone",Map.of("buildRun","test"));
    }
    @After public void clearInterrupt() { Thread.interrupted(); }
    private byte[] entry(File report, String name) throws Exception {
        try (ZipFile zip=new ZipFile(report)) { return zip.getInputStream(zip.getEntry(name)).readAllBytes(); }
    }
    private JsonObject status(File report) throws Exception {
        return JsonParser.parseString(new String(entry(report,"collection-status.json"),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private File attachment() throws Exception {
        File archive=DiagnosticArchive.create(new File(temp.newFolder(),"watch.zip"),Map.of("fixture.bin",TransferFiles.fixture()));
        byte[] data=Files.readAllBytes(archive.toPath());
        return TransferFiles.commit(received,UUID.randomUUID().toString(),DiagnosticArchive.hash(data),data);
    }
    private final class Destination implements ExportDestination, ExportDestination.Pending {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        boolean published, discarded, corrupt, oversize, failPublish, interruptWrite, failCleanup, failLog;
        List<String> calls=new ArrayList<>();
        public Pending create(String name) { calls.add("create"); return this; }
        public OutputStream openOutput() {
            calls.add("write");
            return new FilterOutputStream(bytes) {
                @Override public void close() throws IOException { super.close(); if (interruptWrite) Thread.currentThread().interrupt(); }
            };
        }
        public InputStream openInput() {
            calls.add("read");
            byte[] value=bytes.toByteArray();
            if (corrupt) value[0]^=1;
            if (oversize) value=Arrays.copyOf(value,value.length+1);
            return new ByteArrayInputStream(value);
        }
        public void publish() throws IOException {
            calls.add("publish");
            if (failPublish) throw new IOException("INJECTED_PUBLISH_FAILURE");
            published=true;
            if (failLog) {
                Files.delete(new File(logs,"events.jsonl").toPath());
                Files.createDirectory(new File(logs,"events.jsonl").toPath());
            }
        }
        public void discard() throws IOException {
            calls.add("discard"); discarded=true;
            if (failCleanup) throw new IOException("INJECTED_CLEANUP_FAILURE");
        }
    }
    @Test public void emptyReportStatesEvidenceMissing() throws Exception {
        ReportBuilder.Report report=builder.build(1);
        assertEquals(0,report.includedWatchReports());
        assertEquals("NOT_COLLECTED_OR_NOT_VERIFIED",status(report.file()).get("otherDevice").getAsString());
        assertEquals("NOT_COLLECTED",status(report.file()).get("systemPowerEvidence").getAsString());
        assertEquals("NOT_COLLECTED",status(report.file()).get("systemDump").getAsString());
        assertEquals("INCLUDED_APP_JOURNAL_ONLY",status(report.file()).get("appLevelPostconditions").getAsString());
        assertTrue(new String(entry(report.file(),"diagnostic-feedback.txt"),StandardCharsets.UTF_8).contains("仅本机证据"));
        assertFalse(new String(entry(report.file(),"summary.md"),StandardCharsets.UTF_8).contains("尚未接入同步"));
    }
    @Test public void reportIncludesManifestPostconditionsAndUserObservation() throws Exception {
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","TRACKER_CREATED","sensor","PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","FINAL_UNSET_RETURNED","sensor","PPG"));
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","DISCONNECT_REQUEST","sensor","ALL"));
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","DISCONNECT_RETURNED","sensor","ALL"));
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","DISCONNECT_RECHECK","sensor","ALL","detail","retained=0"));
        journal.event("CAPTURE_VENDOR_CLEANUP",Map.of("stage","OWNER_CLOSED","sensor","ALL","detail","failure="));
        journal.event("CAPTURE_SESSION_END",Map.of("session","s-1","detail","USER_COMPLETED"));
        journal.observation("DIAGNOSTIC_CAPTURE",Map.of("diagnosticCaptureId","cap-1",
            "symptoms",List.of("LED_PERSISTENT"),"led","ON","watchExit","NOT_EXITED","rebootState","NOT_RECORDED"));
        ReportBuilder.Report report=builder.build(1);
        JsonObject manifest=JsonParser.parseString(new String(entry(report.file(),"diagnostic-manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject post=JsonParser.parseString(new String(entry(report.file(),"postconditions.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("RAWWAVE_DIAGNOSTIC_MANIFEST",manifest.get("kind").getAsString());
        assertEquals(1,manifest.getAsJsonObject("coverage").get("observationCount").getAsInt());
        assertEquals("APP_LEVEL_POSTCONDITIONS",post.get("kind").getAsString());
        assertTrue(post.getAsJsonArray("checks").toString().contains("tracker_final_unset"));
        assertTrue(new String(entry(report.file(),"phone/diagnostic-observations.jsonl"),StandardCharsets.UTF_8).contains("cap-1"));
        assertTrue(new String(entry(report.file(),"summary.md"),StandardCharsets.UTF_8).contains("postconditions.json"));
    }
    @Test public void independentSummaryIsExportedAndMarkedAsCachedPartial() throws Exception {
        File summary = new File(temp.newFolder(),"latest.json");
        byte[] bytes=DiagnosticSummary.create(Map.of(),"watch-test");
        Files.write(summary.toPath(),bytes);
        File report=new DiagnosticReportBuilder(journal,received,reports,"phone",Map.of("buildRun","test"),summary).build(1).file();
        assertArrayEquals(bytes,entry(report,"received-summary/latest.json"));
        assertTrue(new String(entry(report,"summary-collection.json"),StandardCharsets.UTF_8).contains("CACHED_PARTIAL_SUMMARY"));
        Files.writeString(summary.toPath(),"broken");
        File corruptReport=new DiagnosticReportBuilder(journal,received,reports,"phone",Map.of("buildRun","test"),summary).build(2).file();
        assertTrue(new String(entry(corruptReport,"summary-collection.json"),StandardCharsets.UTF_8).contains("CORRUPT_OR_UNREADABLE"));
    }
    @Test public void watchProblemRecordIsExtractedToTheReportTopLevel() throws Exception {
        byte[] observation = ("{\"schemaVersion\":1,\"deviceRole\":\"watch\",\"event\":\"DIAGNOSTIC_CAPTURE\","
            + "\"utc\":\"2026-11-10T01:03:32Z\",\"details\":{\"diagnosticCaptureId\":\"watch-cap-1\","
            + "\"symptoms\":[\"LED_PERSISTENT\"],\"led\":\"ON\",\"captureServiceRunning\":false}}\n")
            .getBytes(StandardCharsets.UTF_8);
        File archive=DiagnosticArchive.create(new File(temp.newFolder(),"watch.zip"),Map.of(
            "watch/diagnostic-observations.jsonl",observation,
            "fixture.bin",TransferFiles.fixture()));
        byte[] data=Files.readAllBytes(archive.toPath());
        File watch=TransferFiles.commit(received,UUID.randomUUID().toString(),DiagnosticArchive.hash(data),data);
        ReportBuilder.Report report=builder.build(1);
        String id=watch.getName().replace(".zip","");
        String extracted=new String(entry(report.file(),"watch-observations/"+id+".jsonl"),StandardCharsets.UTF_8);
        assertTrue(extracted.contains("watch-cap-1"));
        JsonObject manifest=JsonParser.parseString(new String(entry(report.file(),"diagnostic-manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1,manifest.getAsJsonObject("coverage").get("watchObservationCount").getAsInt());
        assertEquals(1,manifest.getAsJsonObject("coverage").get("problemSiteRecordCount").getAsInt());
        assertEquals("USER_REPORTED",manifest.getAsJsonObject("layers").getAsJsonObject("L0_USER_UI").get("status").getAsString());
        assertTrue(manifest.getAsJsonObject("coverage").getAsJsonArray("latestDiagnosticCaptureIds").toString().contains("watch-cap-1"));
    }
    @Test public void attachmentsKeepExactOriginalBytesAndReceipt() throws Exception {
        File original=attachment();
        ReportBuilder.Report report=builder.build(1);
        assertEquals(1,report.includedWatchReports());
        assertArrayEquals(Files.readAllBytes(original.toPath()),entry(report.file(),"received-watch/"+original.getName()));
        String receipt=original.getName().replace(".zip",".receipt.json");
        assertArrayEquals(Files.readAllBytes(new File(received,receipt).toPath()),entry(report.file(),"received-watch/"+receipt));
    }
    @Test public void missingReceiptIsRejectedNotInvented() throws Exception {
        File file=attachment();
        Files.delete(new File(received,file.getName().replace(".zip",".receipt.json")).toPath());
        ReportBuilder.Report report=builder.build(1);
        assertEquals(0,report.includedWatchReports());
        assertEquals(1,status(report.file()).getAsJsonArray("rejected").size());
        assertTrue(file.isFile());
    }
    @Test public void corruptAttachmentDoesNotHideGoodAttachment() throws Exception {
        File bad=attachment(); attachment(); Files.write(bad.toPath(),new byte[]{0});
        ReportBuilder.Report report=builder.build(1);
        assertEquals(1,report.includedWatchReports());
        assertEquals(1,status(report.file()).getAsJsonArray("rejected").size());
        assertArrayEquals(new byte[]{0},Files.readAllBytes(bad.toPath()));
    }
    @Test public void onlyNewestThreeAttachedOlderDataUntouched() throws Exception {
        File oldest=attachment(); assertTrue(oldest.setLastModified(1));
        attachment(); attachment(); attachment();
        ReportBuilder.Report report=builder.build(1);
        assertEquals(3,report.includedWatchReports());
        assertEquals(1,status(report.file()).get("omittedOlderCount").getAsInt());
        assertTrue(oldest.isFile());
    }
    @Test public void productionBuilderAndExporterCanUseDifferentDestination() throws Exception {
        attachment(); Destination destination=new Destination();
        ReportBuilder.Report result=new ReportExporter(builder,destination,journal).export(1);
        assertEquals(List.of("create","write","read","publish"),destination.calls);
        assertTrue(destination.published); assertFalse(destination.discarded);
        assertEquals(1,result.includedWatchReports());
        assertArrayEquals(Files.readAllBytes(result.file().toPath()),destination.bytes.toByteArray());
    }
    @Test public void corruptTargetNeverPublished() {
        Destination d=new Destination(); d.corrupt=true;
        ReportExporter.Failure failure=assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertFalse(failure.published); assertFalse(d.published); assertTrue(d.discarded);
    }
    @Test public void oversizedTargetNeverPublished() {
        Destination d=new Destination(); d.oversize=true;
        assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertFalse(d.published); assertTrue(d.discarded);
    }
    @Test public void publishFailureDiscardsOnlyPendingCopy() throws Exception {
        File original=attachment(); Destination d=new Destination(); d.failPublish=true;
        assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertTrue(d.discarded); assertTrue(original.isFile());
    }
    @Test public void cancellationBeforeStartDoesNotCreateDestination() {
        Destination d=new Destination(); Thread.currentThread().interrupt();
        assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertTrue(d.calls.isEmpty());
    }
    @Test public void cancellationAfterWriteDiscardsWithoutPublishing() {
        Destination d=new Destination(); d.interruptWrite=true;
        assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertEquals(List.of("create","write","discard"),d.calls);
        assertFalse(d.published);
    }
    @Test public void cleanupFailureIsRetainedAlongsideOriginalFailure() {
        Destination d=new Destination(); d.corrupt=true; d.failCleanup=true;
        ReportExporter.Failure failure=assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertEquals("EXPORT_TARGET_MISMATCH",failure.getMessage());
        assertEquals("INJECTED_CLEANUP_FAILURE",failure.getSuppressed()[0].getMessage());
    }
    @Test public void publishedCopySurvivesSubsequentLogFailure() {
        Destination d=new Destination(); d.failLog=true;
        ReportExporter.Failure failure=assertThrows(ReportExporter.Failure.class,()->new ReportExporter(builder,d,journal).export(1));
        assertTrue(failure.published); assertTrue(d.published); assertFalse(d.discarded);
    }
    @Test public void builderFailureDoesNotTouchDestination() {
        Destination d=new Destination();
        ReportBuilder broken=operation->{throw new IOException("INJECTED_BUILD_FAILURE");};
        assertThrows(ReportExporter.Failure.class,()->new ReportExporter(broken,d,journal).export(1));
        assertTrue(d.calls.isEmpty());
    }
}
