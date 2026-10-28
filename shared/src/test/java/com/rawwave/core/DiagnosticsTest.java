package com.rawwave.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class DiagnosticsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void restartDistinguishesUncleanAndRequestedExit() throws Exception {
        File dir = temp.newFolder();
        Journal first = new Journal(dir, "watch");
        assertEquals("NO_PREVIOUS_RECORD", first.previousState());
        Journal second = new Journal(dir, "watch");
        assertEquals("RUNNING", second.previousState());
        second.mark("PROCESS_TERMINATION_REQUESTED");
        assertEquals("PROCESS_TERMINATION_REQUESTED", new Journal(dir, "watch").previousState());
    }
    @Test public void corruptMarkerIsNotTreatedAsClean() throws Exception {
        File dir = temp.newFolder();
        Files.writeString(new File(dir, "process.json").toPath(), "broken");
        assertEquals("CORRUPT_PROCESS_MARKER", new Journal(dir, "phone").previousState());
    }
    @Test public void logsRotateWithoutRemovingFirstFault() throws Exception {
        File dir = temp.newFolder();
        Journal journal = new Journal(dir, "watch");
        journal.fault("FIRST", new IOException("首个错误\nwith quote \""));
        byte[] first = journal.snapshot().get("watch/first-fault.json");
        for (int i = 0; i < 160; i++) journal.event("TEST_FIXTURE", Map.of("text", "x".repeat(6000)));
        journal.fault("LAST", new IOException("latest"));
        assertArrayEquals(first, journal.snapshot().get("watch/first-fault.json"));
        assertTrue(new File(dir, "events.jsonl").length() <= Journal.MAX_LOG_BYTES);
        assertTrue(new File(dir, "events.previous.jsonl").length() <= Journal.MAX_LOG_BYTES);
        assertTrue(journal.snapshot().containsKey("watch/rotation.json"));
    }
    @Test public void archiveIsVerifiedAndCannotOverwrite() throws Exception {
        File output = new File(temp.newFolder(), "report.zip");
        DiagnosticArchive.create(output, Map.of("summary.md", "真实报告".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(output.length() > 0);
        assertThrows(IOException.class, () -> DiagnosticArchive.create(output, Map.of()));
    }
    @Test public void badPathsRejected() throws Exception {
        for (String path : List.of("../out", "/absolute", "C:/out", "a\\b", "a//b", "a/./b")) {
            File output = new File(temp.newFolder(), "report.zip");
            assertThrows(IOException.class, () -> DiagnosticArchive.create(output, Map.of(path, new byte[0])));
        }
    }
    @Test public void missingEntryRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.finish(); }
        assertThrows(IOException.class, () -> DiagnosticArchive.verify(new ByteArrayInputStream(bytes.toByteArray()), Map.of("a", new byte[0])));
    }
    @Test public void excessivePayloadRejected() throws Exception {
        File file = new File(temp.newFolder(), "report.zip");
        assertThrows(IOException.class, () -> DiagnosticArchive.create(file, Map.of("large", new byte[(int)DiagnosticArchive.MAX_BYTES + 1])));
    }
    @Test public void reservedManifestRejected() throws Exception {
        File file = new File(temp.newFolder(), "report.zip");
        assertThrows(IOException.class, () -> DiagnosticArchive.create(file, Map.of("bundle.json", new byte[0])));
    }
    @Test public void hashHasStableKnownValue() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DiagnosticArchive.hash(new byte[0]));
    }
}
