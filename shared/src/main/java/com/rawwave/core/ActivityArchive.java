package com.rawwave.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Builds and verifies byte-preserving raw activity archives. It never interprets sensor values. */
public final class ActivityArchive {
    /** Raw session payload cap. bundle.json is derived metadata and has its own 16 MiB limit. */
    public static final long MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern BLOCK = Pattern.compile("[0-9]{8}-(?:PRE|CORE|POST)-[0-9]+-([A-Z0-9_]+)\\.json");
    private ActivityArchive() {}

    public record Batch(File file, String id, String sha256, long bytes, List<String> sessionIds) {}
    public record Verified(String id, List<String> sessionIds, int rawFiles, long rawBytes, boolean allSealed) {}

    /** Reuses an unfinished immutable batch; otherwise snapshots every currently unacknowledged session. */
    public static Batch preparePending(File activitiesRoot, File outgoing) throws IOException {
        Files.createDirectories(outgoing.toPath());
        File[] reusable = outgoing.listFiles((dir, name) -> name.endsWith(".zip")
            && !new File(dir, name.substring(0, name.length() - 4) + ".ack.json").isFile());
        if (reusable != null) {
            Arrays.sort(reusable, Comparator.comparingLong(File::lastModified));
            for (File file : reusable) {
                try {
                    Verified verified = verify(file);
                    if (verified.sessionIds().stream().anyMatch(id -> !isAcknowledged(activitiesRoot, id)))
                        return batch(file, verified);
                } catch (IOException ignored) { /* A bad generated copy is never sent; source sessions remain. */ }
            }
        }

        File sessionsRoot = new File(activitiesRoot, "sessions");
        File[] folders = sessionsRoot.listFiles(file -> file.isDirectory() && UUID.matcher(file.getName()).matches()
            && !isAcknowledged(activitiesRoot, file.getName()));
        if (folders == null || folders.length == 0) return null;
        Arrays.sort(folders, Comparator.comparingLong(ActivityArchive::createdUtc));

        String id = java.util.UUID.randomUUID().toString();
        File building = new File(outgoing, id + ".building");
        File target = new File(outgoing, id + ".zip");
        create(building, id, Arrays.asList(folders));
        Files.move(building.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        Verified verified = verify(target);
        return batch(target, verified);
    }

    /** Raw bytes that a pending archive would read; used only to size the packaging budget. */
    public static long pendingRawBytes(File activitiesRoot) throws IOException {
        File sessionsRoot = new File(activitiesRoot, "sessions");
        File[] folders = sessionsRoot.listFiles(file -> file.isDirectory()
            && UUID.matcher(file.getName()).matches() && !isAcknowledged(activitiesRoot, file.getName()));
        if (folders == null) return 0L;
        long total = 0L;
        for (File folder : folders) for (File file : sessionFiles(folder)) total += file.length();
        return total;
    }

    public static int pendingCount(File activitiesRoot) {
        File[] folders = new File(activitiesRoot, "sessions").listFiles(file -> file.isDirectory()
            && UUID.matcher(file.getName()).matches() && !isAcknowledged(activitiesRoot, file.getName()));
        return folders == null ? 0 : folders.length;
    }
    /** Sessions left without seal.json after a process death/force-stop. */
    public static int unsealedCount(File activitiesRoot) {
        File[] folders = new File(activitiesRoot, "sessions").listFiles(file -> file.isDirectory()
            && UUID.matcher(file.getName()).matches() && !new File(file, "seal.json").isFile());
        return folders == null ? 0 : folders.length;
    }

    public static void acknowledge(File activitiesRoot, File outgoing, Batch batch, long utcMs) throws IOException {
        Verified verified = verify(batch.file());
        if (!verified.id().equals(batch.id()) || !ActivityTransferFiles.hashFile(batch.file()).equals(batch.sha256()))
            throw new IOException("ACTIVITY_ACK_SOURCE_CHANGED");
        File sync = new File(activitiesRoot, "sync");
        Files.createDirectories(sync.toPath());
        for (String sessionId : verified.sessionIds()) {
            File target = new File(sync, sessionId + ".phone-ack.json");
            if (!target.isFile()) atomic(target, JSON.toJson(Map.of("schema", 1, "sessionId", sessionId,
                "batchId", batch.id(), "batchSha256", batch.sha256(), "phoneCommittedUtcMs", utcMs)).getBytes(StandardCharsets.UTF_8));
        }
        File batchAck = new File(outgoing, batch.id() + ".ack.json");
        if (!batchAck.isFile()) atomic(batchAck, JSON.toJson(Map.of("schema", 1, "batchId", batch.id(),
            "sha256", batch.sha256(), "bytes", batch.bytes(), "sessions", verified.sessionIds(),
            "phoneCommittedUtcMs", utcMs)).getBytes(StandardCharsets.UTF_8));
    }

    public static Verified verify(File archive) throws IOException {
        return verify(archive, MAX_UNCOMPRESSED_BYTES);
    }

    /** Package-private seam so a regression test can prove the bundle manifest is not raw payload. */
    static Verified verify(File archive, long maxRawBytes) throws IOException {
        if (!archive.isFile() || archive.length() <= 0 || archive.length() > ActivityTransferFiles.MAX_BYTES)
            throw new IOException("INVALID_ACTIVITY_ARCHIVE_SIZE");
        Map<String, Map<String,Object>> actual = new TreeMap<>();
        byte[] bundleBytes = null;
        Map<String,byte[]> seals = new HashMap<>();
        Map<String,byte[]> plans = new HashMap<>();
        long rawTotal = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName(); validateEntryName(name);
                if (entry.isDirectory() || actual.containsKey(name) || (bundleBytes != null && name.equals("bundle.json")))
                    throw new IOException("DUPLICATE_OR_DIRECTORY_ENTRY");
                MessageDigest digest = digest();
                boolean captureMetadata = name.equals("bundle.json") || name.endsWith("/seal.json") || name.endsWith("/plan.json");
                ByteArrayOutputStream captured = captureMetadata ? new ByteArrayOutputStream() : null;
                long bytes = 0; int count;
                while ((count = zip.read(buffer)) != -1) {
                    bytes += count;
                    if (!name.equals("bundle.json")) {
                        rawTotal += count;
                        if (rawTotal > maxRawBytes) throw new IOException("ACTIVITY_ARCHIVE_EXPANSION_LIMIT");
                    }
                    long metadataLimit = name.equals("bundle.json") ? 16L * 1024 * 1024 : 8L * 1024 * 1024;
                    if (captured != null && bytes > metadataLimit)
                        throw new IOException("ACTIVITY_METADATA_LIMIT:" + name + ":" + bytes);
                    digest.update(buffer, 0, count);
                    if (captured != null) captured.write(buffer, 0, count);
                }
                if (name.equals("bundle.json")) bundleBytes = captured.toByteArray();
                else {
                    actual.put(name, Map.of("bytes", bytes, "sha256", hex(digest.digest())));
                    if (name.endsWith("/seal.json")) seals.put(name, captured.toByteArray());
                    else if (name.endsWith("/plan.json")) plans.put(name, captured.toByteArray());
                }
            }
        }
        if (bundleBytes == null) throw new IOException("ACTIVITY_BUNDLE_MANIFEST_MISSING");
        JsonObject bundle;
        try { bundle = JsonParser.parseString(new String(bundleBytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception error) { throw new IOException("INVALID_ACTIVITY_BUNDLE_MANIFEST", error); }
        if (bundle.get("schemaVersion").getAsInt() != 1 || !"RAWWAVE_ACTIVITY_RAW_EXPORT".equals(bundle.get("kind").getAsString()))
            throw new IOException("ACTIVITY_BUNDLE_PROTOCOL_MISMATCH");
        String id = bundle.get("batchId").getAsString();
        if (!UUID.matcher(id).matches() || !archive.getName().equals(id + ".zip")) throw new IOException("ACTIVITY_BUNDLE_ID_MISMATCH");
        JsonObject expected = bundle.getAsJsonObject("files");
        if (expected == null || expected.size() != actual.size()) throw new IOException("ACTIVITY_BUNDLE_FILE_COUNT_MISMATCH");
        for (Map.Entry<String,Map<String,Object>> item : actual.entrySet()) {
            JsonObject declared = expected.getAsJsonObject(item.getKey());
            if (declared == null || declared.get("bytes").getAsLong() != ((Number)item.getValue().get("bytes")).longValue()
                || !declared.get("sha256").getAsString().equals(item.getValue().get("sha256")))
                throw new IOException("ACTIVITY_BUNDLE_ENTRY_MISMATCH:" + item.getKey());
        }
        List<String> sessions = new ArrayList<>(); boolean allSealed = true;
        JsonArray listed = bundle.getAsJsonArray("sessions");
        if (listed == null || listed.isEmpty()) throw new IOException("ACTIVITY_BUNDLE_EMPTY");
        for (JsonElement element : listed) {
            JsonObject session = element.getAsJsonObject();
            String sessionId = session.get("sessionId").getAsString();
            if (!UUID.matcher(sessionId).matches() || sessions.contains(sessionId)) throw new IOException("INVALID_ACTIVITY_SESSION_ID");
            boolean sealed = session.get("sealed").getAsBoolean();
            if (!actual.containsKey("sessions/" + sessionId + "/plan.json")
                || (sealed && !actual.containsKey("sessions/" + sessionId + "/seal.json")))
                throw new IOException("ACTIVITY_SESSION_METADATA_MISSING");
            verifyDeclaredSources(sessionId, plans.get("sessions/" + sessionId + "/plan.json"), actual);
            if (sealed) verifySealManifest(sessionId, seals.get("sessions/" + sessionId + "/seal.json"), actual);
            sessions.add(sessionId); allSealed &= sealed;
        }
        for (String path : actual.keySet()) {
            String[] pieces = path.split("/");
            if (pieces.length != 3 || !pieces[0].equals("sessions") || !sessions.contains(pieces[1]))
                throw new IOException("ACTIVITY_ENTRY_OUTSIDE_SESSION");
        }
        return new Verified(id, List.copyOf(sessions), actual.size(), rawTotal, allSealed);
    }

    private static Batch batch(File file, Verified verified) throws IOException {
        String hash = ActivityTransferFiles.hashFile(file);
        ActivityTransferFiles.validate(verified.id(), hash, file.length());
        return new Batch(file, verified.id(), hash, file.length(), verified.sessionIds());
    }

    static void create(File output, String batchId, List<File> sessions) throws IOException {
        if (output.exists()) throw new IOException("ACTIVITY_BATCH_ALREADY_EXISTS");
        Map<String,Object> files = new TreeMap<>();
        List<Map<String,Object>> sessionList = new ArrayList<>();
        long total = 0;
        try (FileOutputStream stream = new FileOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            for (File folder : sessions) {
                List<File> sourceFiles = sessionFiles(folder);
                boolean sealed = new File(folder, "seal.json").isFile();
                if (sealed) verifySealedSource(folder, sourceFiles);
                if (sourceFiles.stream().noneMatch(file -> file.getName().equals("plan.json")))
                    throw new IOException("ACTIVITY_PLAN_MISSING:" + folder.getName());
                for (File source : sourceFiles) {
                    String name = "sessions/" + folder.getName() + "/" + source.getName();
                    zip.putNextEntry(new ZipEntry(name));
                    MessageDigest digest = digest(); long bytes = 0;
                    try (InputStream input = new FileInputStream(source)) {
                        byte[] buffer = new byte[64 * 1024]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            bytes += count; total += count;
                            if (total > MAX_UNCOMPRESSED_BYTES) throw new IOException("ACTIVITY_BATCH_TOO_LARGE");
                            digest.update(buffer, 0, count); zip.write(buffer, 0, count);
                        }
                    }
                    zip.closeEntry();
                    files.put(name, Map.of("bytes", bytes, "sha256", hex(digest.digest())));
                }
                sessionList.add(Map.of("sessionId", folder.getName(), "sealed", sealed,
                    "integrity", sealed ? "SEAL_INCLUDED_AND_ARCHIVE_REVERIFIED" : "UNSEALED_IMMUTABLE_BLOCK_SNAPSHOT_TAIL_UNKNOWN"));
            }
            byte[] bundle = JSON.toJson(Map.of("schemaVersion", 1, "kind", "RAWWAVE_ACTIVITY_RAW_EXPORT",
                "batchId", batchId, "createdUtcMs", System.currentTimeMillis(), "sessions", sessionList,
                "files", files, "rawPolicy", "Original activity JSON bytes; no sensor value conversion, filtering, interpolation or invented rows",
                "verification", "Every entry carries SHA-256 and byte length; whole archive is hashed during transport")).getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("bundle.json")); zip.write(bundle); zip.closeEntry();
            zip.finish(); zip.flush(); stream.getFD().sync();
        } catch (IOException failure) {
            Files.deleteIfExists(output.toPath()); throw failure;
        }
    }

    private static void verifySealedSource(File folder, List<File> sourceFiles) throws IOException {
        JsonObject seal;
        try { seal = JsonParser.parseString(Files.readString(new File(folder,"seal.json").toPath())).getAsJsonObject(); }
        catch (Exception error) { throw new IOException("INVALID_SOURCE_SEAL:" + folder.getName(), error); }
        if (!folder.getName().equals(seal.get("sessionId").getAsString())) throw new IOException("SOURCE_SEAL_SESSION_MISMATCH");
        JsonObject declared = seal.getAsJsonObject("files");
        List<File> content = sourceFiles.stream().filter(file -> !file.getName().equals("seal.json")).toList();
        if (declared == null || declared.size() != content.size()) throw new IOException("SOURCE_SEAL_FILE_COUNT_MISMATCH");
        for (File file : content) {
            JsonObject expected = declared.getAsJsonObject(file.getName());
            if (expected == null || expected.get("bytes").getAsLong() != file.length()
                || !expected.get("sha256").getAsString().equals(ActivityTransferFiles.hashFile(file)))
                throw new IOException("SOURCE_SEAL_ENTRY_MISMATCH:" + file.getName());
        }
    }

    private static void verifySealManifest(String sessionId, byte[] sealBytes,
                                           Map<String,Map<String,Object>> actual) throws IOException {
        if (sealBytes == null) throw new IOException("ACTIVITY_SEAL_BYTES_MISSING");
        JsonObject seal;
        try { seal = JsonParser.parseString(new String(sealBytes,StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception error) { throw new IOException("INVALID_ARCHIVED_SEAL:" + sessionId, error); }
        if (!sessionId.equals(seal.get("sessionId").getAsString())) throw new IOException("ARCHIVED_SEAL_SESSION_MISMATCH");
        JsonObject declared = seal.getAsJsonObject("files");
        String prefix = "sessions/" + sessionId + "/";
        long actualCount = actual.keySet().stream().filter(path -> path.startsWith(prefix) && !path.endsWith("/seal.json")).count();
        if (declared == null || declared.size() != actualCount) throw new IOException("ARCHIVED_SEAL_FILE_COUNT_MISMATCH");
        for (Map.Entry<String,JsonElement> item : declared.entrySet()) {
            Map<String,Object> value = actual.get(prefix + item.getKey());
            JsonObject expected = item.getValue().getAsJsonObject();
            if (value == null || expected.get("bytes").getAsLong() != ((Number)value.get("bytes")).longValue()
                || !expected.get("sha256").getAsString().equals(value.get("sha256")))
                throw new IOException("ARCHIVED_SEAL_ENTRY_MISMATCH:" + item.getKey());
        }
    }
    private static List<File> sessionFiles(File folder) throws IOException {
        File[] files = folder.listFiles(file -> file.isFile() && (file.getName().equals("plan.json")
            || file.getName().equals("seal.json") || isBlockName(file.getName())));
        if (files == null) throw new IOException("ACTIVITY_SESSION_UNREADABLE");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return Arrays.asList(files);
    }
    private static long createdUtc(File folder) {
        try { return JsonParser.parseString(Files.readString(new File(folder,"plan.json").toPath())).getAsJsonObject().get("createdUtcMs").getAsLong(); }
        catch (Exception ignored) { return folder.lastModified(); }
    }
    private static boolean isAcknowledged(File root, String sessionId) {
        File file = new File(new File(root, "sync"), sessionId + ".phone-ack.json");
        try {
            JsonObject value = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            return value.get("schema").getAsInt() == 1 && sessionId.equals(value.get("sessionId").getAsString());
        } catch (Exception ignored) { return false; }
    }
    private static void atomic(File target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        File part = new File(target.getPath() + ".part");
        try (FileOutputStream output = new FileOutputStream(part)) { output.write(bytes); output.flush(); output.getFD().sync(); }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void validateEntryName(String name) throws IOException {
        if (name.equals("bundle.json")) return;
        if (name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException("UNSAFE_ACTIVITY_ENTRY");
        String[] pieces = name.split("/", -1);
        if (pieces.length != 3 || !pieces[0].equals("sessions") || !UUID.matcher(pieces[1]).matches()
            || !(pieces[2].equals("plan.json") || pieces[2].equals("seal.json") || isBlockNamePattern(pieces[2])))
            throw new IOException("UNSAFE_ACTIVITY_ENTRY");
    }
    private static boolean isBlockName(String name) {
        var match = BLOCK.matcher(name);
        return match.matches() && SensorCatalog.acceptsStream(match.group(1));
    }
    /** Archive receive accepts a block shape from a newer watch; the plan must declare the source. */
    private static boolean isBlockNamePattern(String name) {
        return BLOCK.matcher(name).matches();
    }
    /**
     * Forward-compatible receive: the phone may not know a newer watch source yet, but it still
     * accepts the raw block when the session plan declares that source. Known sources remain
     * accepted for legacy plans. Unknown, undeclared sources are rejected before storage.
     */
    private static void verifyDeclaredSources(String sessionId, byte[] planBytes,
                                              Map<String,Map<String,Object>> actual) throws IOException {
        if (planBytes == null) throw new IOException("ACTIVITY_PLAN_BYTES_MISSING:" + sessionId);
        JsonObject plan;
        try { plan = JsonParser.parseString(new String(planBytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception error) { throw new IOException("INVALID_ACTIVITY_PLAN:" + sessionId, error); }
        Set<String> declared = new HashSet<>();
        JsonElement kinds = plan.get("dataKinds");
        if (kinds != null && kinds.isJsonObject()) declared.addAll(kinds.getAsJsonObject().keySet());
        JsonElement core = plan.get("coreSources");
        if (core != null && core.isJsonArray()) for (JsonElement item : core.getAsJsonArray())
            if (item.isJsonPrimitive()) declared.add(item.getAsString());
        String prefix = "sessions/" + sessionId + "/";
        for (String path : actual.keySet()) {
            if (!path.startsWith(prefix)) continue;
            String file = path.substring(prefix.length());
            var match = BLOCK.matcher(file);
            if (!match.matches()) continue;
            String sensor = match.group(1);
            if (sensor.equals(SensorCatalog.EVENT) || SensorCatalog.acceptsStream(sensor) || declared.contains(sensor))
                continue;
            throw new IOException("UNDECLARED_ACTIVITY_SENSOR:" + sessionId + ":" + sensor);
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
