package com.rawwave.core;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;

/** Production use case. A destination adapter can be replaced without changing report generation. */
public final class ReportExporter {
    private final ReportBuilder builder;
    private final ExportDestination destination;
    private final Journal journal;
    public ReportExporter(ReportBuilder builder, ExportDestination destination, Journal journal) {
        this.builder = builder; this.destination = destination; this.journal = journal;
    }
    public static final class Failure extends IOException {
        public final boolean published;
        Failure(Exception cause, boolean published) { super(cause.getMessage(), cause); this.published = published; }
    }
    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("REPORT_EXPORT_CANCELLED");
    }
    public ReportBuilder.Report export(long operation) throws Failure {
        ExportDestination.Pending pending = null;
        boolean published = false;
        try {
            checkCancelled();
            journal.event("DIAGNOSTIC_EXPORT_STARTED", Map.of("operation", operation));
            ReportBuilder.Report report = builder.build(operation);
            checkCancelled();
            byte[] source = Files.readAllBytes(report.file().toPath());
            String hash = DiagnosticArchive.hash(source);
            pending = destination.create(report.file().getName());
            checkCancelled();
            try (OutputStream output = pending.openOutput()) { output.write(source); output.flush(); }
            checkCancelled();
            try (InputStream input = pending.openInput()) {
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if ((long) actual.size() + count > source.length) throw new IOException("EXPORT_TARGET_OVERSIZE");
                    actual.write(buffer, 0, count);
                }
                if (actual.size() != source.length || !DiagnosticArchive.hash(actual.toByteArray()).equals(hash))
                    throw new IOException("EXPORT_TARGET_MISMATCH");
            }
            checkCancelled();
            pending.publish();
            published = true;
            journal.event("DIAGNOSTIC_EXPORT_VERIFIED", Map.of("file", report.file().getName(), "bytes", source.length, "sha256", hash));
            return report;
        } catch (Exception cause) {
            Failure failure = new Failure(cause, published);
            if (pending != null && !published) {
                try { pending.discard(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            try { journal.fault("DIAGNOSTIC_EXPORT_FAILED", failure); }
            catch (Exception evidence) { failure.addSuppressed(evidence); }
            throw failure;
        }
    }
}
