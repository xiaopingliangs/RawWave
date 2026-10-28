package com.rawwave.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public final class DiagnosticArchive {
    public static final long MAX_BYTES = 8 * 1024 * 1024;
    private DiagnosticArchive() {}

    public static File create(File output, Map<String, byte[]> payload) throws IOException {
        if (output.exists()) throw new IOException("REPORT_ALREADY_EXISTS");
        TreeMap<String, byte[]> entries = new TreeMap<>(payload);
        if (entries.containsKey("bundle.json")) throw new IOException("RESERVED_BUNDLE_NAME");
        long total = 0;
        Map<String, Object> descriptions = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            validateName(entry.getKey());
            total += entry.getValue().length;
            if (total > MAX_BYTES) throw new IOException("REPORT_LIMIT_EXCEEDED");
            descriptions.put(entry.getKey(), Map.of("bytes", entry.getValue().length,
                "sha256", hash(entry.getValue())));
        }
        entries.put("bundle.json", new Gson().toJson(Map.of("schemaVersion", 1,
            "kind", "DIAGNOSTIC_NOT_HEALTH_DATA", "files", descriptions))
            .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(output.getParentFile().toPath());
        try (FileOutputStream stream = new FileOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue()); zip.closeEntry();
            }
            zip.finish(); zip.flush(); stream.getFD().sync();
        }
        try (InputStream input = new FileInputStream(output)) { verify(input, entries); }
        return output;
    }

    public static void verify(InputStream input, Map<String, byte[]> expected) throws IOException {
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                validateName(entry.getName());
                byte[] content = expected.get(entry.getName());
                if (content == null || !seen.add(entry.getName()) || entry.isDirectory())
                    throw new IOException("UNEXPECTED_OR_DUPLICATE_ENTRY");
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES + 262144 || actual.size() + count > content.length)
                        throw new IOException("ZIP_LIMIT_EXCEEDED");
                    actual.write(buffer, 0, count);
                }
                if (!Arrays.equals(content, actual.toByteArray())) throw new IOException("ENTRY_MISMATCH");
            }
        }
        if (!seen.equals(expected.keySet())) throw new IOException("MISSING_ENTRIES");
    }

    public static String hash(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder text = new StringBuilder();
            for (byte b : digest) text.append(String.format(Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("UNSAFE_ENTRY_PATH");
        for (String segment : name.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IOException("UNSAFE_ENTRY_PATH");
    }
}
