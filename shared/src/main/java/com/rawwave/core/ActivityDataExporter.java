package com.rawwave.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Copies verified raw activity archives to a user-visible destination without loading them into
 * memory. Deep verification is paid once per immutable received package: the receive path already
 * writes {@code <id>.verified.json}, and older packages get the same sidecar after their first
 * successful deep verification. Listing never re-decompresses an unchanged package.
 */
public final class ActivityDataExporter {
    /** Optional byte-level copy progress; called from the export worker thread. */
    public interface Progress { void update(long copied, long total); }
    private static final Progress NO_PROGRESS = (copied, total) -> {};
    private static final int COPY_BUFFER = 256 * 1024;
    private static final int PROGRESS_STEP = 4 * 1024 * 1024;
    private static final int MAX_PLAN_BYTES = 65536;

    private final File inbox;
    private final ExportDestination destination;
    private final FileReceiveStore.DirectorySync durability;
    private final List<String> rejected = new ArrayList<>();
    public List<String> rejectedFiles() { return List.copyOf(rejected); }
    public ActivityDataExporter(File inbox, ExportDestination destination, FileReceiveStore.DirectorySync durability) {
        this.inbox = inbox; this.destination = destination; this.durability = Objects.requireNonNull(durability);
    }
    public record Result(String name, long bytes, String sha256, List<String> sessions) {}
    public record Choice(String file, String displayName, int sessions) {}
    /** One activity session with its real start time and recorded template name; packages may contain several. */
    public record SessionChoice(String id, long createdUtcMs, String activityName, String file,
                                boolean sealed, long fileBytes) {}
    private record Cached(String id, String sha256, long bytes, List<String> sessions) {}
    private record Listing(ActivityArchive.Verified verified, Cached cached) {}

    /** Select individual sessions, not transport packages: old multi-session packages are supported. */
    public List<Result> exportRecent(int limit) throws IOException {
        return exportRecent(limit, NO_PROGRESS);
    }
    public List<Result> exportRecent(int limit, Progress progress) throws IOException {
        if(limit < 1 || limit > 3) throw new IllegalArgumentException("RECENT_LIMIT");
        var chosen=sessionChoices().stream().limit(limit).toList();
        if(chosen.isEmpty()) throw new IOException("没有开始时间可确认的活动，请使用选择导出");
        return exportSessionChoices(chosen, progress);
    }

    /**
     * Lists every sealed or unsealed session with a real start time. The list never publishes
     * an export; it is safe for date selection and for showing "received N activities".
     */
    public List<SessionChoice> sessionChoices() throws IOException {
        rejected.clear();
        Map<String,SessionChoice> sessions = new LinkedHashMap<>();
        for(File file:sources()) {
            var listing=verifiedForListing(file); if(listing==null) continue;
            try {
                addSessions(file, listing.verified(), sessions);
            } catch(IOException | RuntimeException invalid) {
                if(listing.cached()==null) { reject(file,invalid); continue; }
                // The sidecar claimed this exact package was verified. If the central
                // directory or plan bytes no longer read, re-verify from disk once.
                var reverified=verifyOrIsolate(file); if(reverified==null) continue;
                try { addSessions(file, reverified, sessions); }
                catch(IOException | RuntimeException stillInvalid) { reject(file,stillInvalid); }
            }
        }
        var list=new ArrayList<>(sessions.values());
        list.sort(Comparator.comparingLong(SessionChoice::createdUtcMs).reversed()
            .thenComparing(SessionChoice::id));
        return List.copyOf(list);
    }

    private void addSessions(File file, ActivityArchive.Verified verified,
                             Map<String,SessionChoice> sessions) throws IOException {
        try(ZipFile zip=new ZipFile(file)) {
            for(String id:verified.sessionIds()) {
                ZipEntry entry=zip.getEntry("sessions/"+id+"/plan.json");
                if(entry==null || entry.getSize()>MAX_PLAN_BYTES)
                    throw new IOException("ACTIVITY_SESSION_METADATA_MISSING:"+id);
                long time; String activityName;
                try {
                    byte[] planBytes=readBounded(zip,entry);
                    JsonObject plan=JsonParser.parseString(new String(planBytes,StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement value=plan.get("createdUtcMs");
                    if(value==null || value.isJsonNull()) continue;
                    time=value.getAsLong();
                    activityName=activityName(plan);
                } catch(RuntimeException malformed) {
                    // Unknown or malformed start times cannot claim to be the latest.
                    continue;
                }
                if(time<=0) continue;
                boolean sealed=zip.getEntry("sessions/"+id+"/seal.json")!=null;
                var prior=sessions.get(id);
                if(prior==null || (!prior.sealed() && sealed)) sessions.put(id,
                    new SessionChoice(id,time,activityName,file.getName(),sealed,file.length()));
            }
        } catch(ZipException invalid) {
            throw new IOException("ACTIVITY_ARCHIVE_UNREADABLE",invalid);
        }
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry) throws IOException {
        try(InputStream in=zip.getInputStream(entry)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096]; int count;
            while(out.size()<MAX_PLAN_BYTES+1 && (count=in.read(buffer,0,Math.min(buffer.length,MAX_PLAN_BYTES+1-out.size())))!=-1) {
                out.write(buffer,0,count);
            }
            if(out.size()>MAX_PLAN_BYTES) throw new IOException("ACTIVITY_PLAN_TOO_LARGE:"+entry.getName());
            return out.toByteArray();
        }
    }

    /** The recorded template name is the activity label; never inferred from sensor data. */
    private static String activityName(JsonObject plan) {
        var template=plan.getAsJsonObject("template");
        if(template==null) return "未命名活动";
        var name=template.get("name");
        if(name==null || name.isJsonNull()) return "未命名活动";
        String value=name.getAsString().trim();
        return value.isEmpty()?"未命名活动":value;
    }

    /** Export exactly the chosen session ids into one new verified archive. */
    public List<Result> exportSessions(Collection<String> ids) throws IOException {
        return exportSessions(ids, NO_PROGRESS);
    }
    public List<Result> exportSessions(Collection<String> ids, Progress progress) throws IOException {
        if(ids==null || ids.isEmpty()) return List.of();
        Set<String> selected=new LinkedHashSet<>();
        for(String id:ids) {
            if(id==null || !id.matches("[0-9a-f-]{36}")) throw new IOException("INVALID_SESSION_SELECTION");
            selected.add(id);
        }
        var chosen=sessionChoices().stream().filter(item -> selected.contains(item.id())).toList();
        if(chosen.size()!=selected.size()) throw new IOException("EXPORT_SESSION_MISSING");
        return exportSessionChoices(chosen, progress);
    }

    private List<Result> exportSessionChoices(List<SessionChoice> chosen, Progress progress) throws IOException {
        // If the selected sessions already live in one immutable received package and the
        // selection covers that package exactly, copy the verified ZIP as-is. This avoids
        // extracting 677 MB of raw JSON and re-compressing it just to re-create bytes that
        // already exist. It also avoids a second deep verification of unchanged data.
        Set<String> selectedIds=chosen.stream().map(SessionChoice::id).collect(java.util.stream.Collectors.toSet());
        Set<String> sourceFiles=chosen.stream().map(SessionChoice::file).collect(java.util.stream.Collectors.toSet());
        if(sourceFiles.size()==1) {
            File source=new File(inbox,sourceFiles.iterator().next());
            Cached cached=cached(source);
            if(cached!=null && new HashSet<>(cached.sessions()).equals(selectedIds)) {
                var verified=new ActivityArchive.Verified(cached.id(),cached.sessions(),0,0,false);
                return List.of(exportOne(source,verified,cached.sha256(),progress));
            }
        }
        java.nio.file.Path scratch=java.nio.file.Files.createTempDirectory(inbox.toPath(),"session-export-");
        try {
            List<File> folders=new ArrayList<>();
            for(var item:chosen) {
                File folder=new File(scratch.toFile(),item.id());
                java.nio.file.Files.createDirectories(folder.toPath());folders.add(folder);
                try(var zip=new ZipFile(new File(inbox,item.file()))) {
                    String prefix="sessions/"+item.id()+"/";
                    boolean found=false;
                    for(var entry:Collections.list(zip.entries())) if(entry.getName().startsWith(prefix)) {
                        String name=entry.getName().substring(prefix.length());
                        if(name.isEmpty()) continue;
                        if(name.contains("/") || name.contains("\\") || name.equals("..")) throw new IOException("UNSAFE_RECENT_ENTRY");
                        found=true;
                        try(var in=zip.getInputStream(entry)) {java.nio.file.Files.copy(in,new File(folder,name).toPath());}
                    }
                    if(!found) throw new IOException("EXPORT_SESSION_MISSING:"+item.id());
                }
            }
            String id=UUID.randomUUID().toString();File output=new File(scratch.toFile(),id+".zip");
            ActivityArchive.create(output,id,folders);
            // create() already hashed every raw entry while writing. A full ActivityArchive.verify()
            // here would re-read and re-parse the whole 8x-larger raw payload for no new evidence.
            var ids=chosen.stream().map(SessionChoice::id).toList();
            var verified=new ActivityArchive.Verified(id,ids,0,0,true);
            return List.of(exportOne(output,verified,ActivityTransferFiles.hashFile(output),progress));
        } finally {
            try(var paths=java.nio.file.Files.walk(scratch)) {
                for(var path:paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
    /** Listing never publishes a Downloads copy. Old multi-session packages remain intact. */
    public List<Choice> choices() throws IOException {
        rejected.clear();
        List<Choice> choices = new ArrayList<>();
        for (File source : sources()) {
            var listing = verifiedForListing(source);
            if (listing == null) continue;
            try {
                choices.add(new Choice(source.getName(),
                    ActivityExportName.create(source, listing.verified(), java.time.ZoneId.systemDefault()),
                    listing.verified().sessionIds().size()));
            } catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException | RuntimeException invalid) {
                if (listing.cached() == null) { reject(source, invalid); continue; }
                var reverified = verifyOrIsolate(source);
                if (reverified == null) continue;
                try {
                    choices.add(new Choice(source.getName(),
                        ActivityExportName.create(source, reverified, java.time.ZoneId.systemDefault()),
                        reverified.sessionIds().size()));
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (IOException | RuntimeException stillInvalid) { reject(source, stillInvalid); }
            }
        }
        choices.sort(Comparator.comparing((Choice c) -> c.displayName().contains("time-unknown"))
            .thenComparing(Choice::displayName, Comparator.reverseOrder()));
        return List.copyOf(choices);
    }
    private File[] sources() throws IOException {
        File[] files = inbox.listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f-]{36}\\.zip"));
        if (files == null || files.length == 0) throw new NoDataException();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return files;
    }

    /**
     * The receive path writes a deep-verification sidecar for every committed package. Reuse it
     * only when the exact bytes are still present; a missing sidecar is verified once and then
     * sealed for the next listing.
     */
    private Listing verifiedForListing(File source) throws IOException {
        Cached cached = cached(source);
        if (cached != null)
            return new Listing(new ActivityArchive.Verified(cached.id(), cached.sessions(), 0, 0, false), cached);
        ActivityArchive.Verified verified = verifyOrIsolate(source);
        if (verified == null) return null;
        Cached fresh = new Cached(verified.id(), ActivityTransferFiles.hashFile(source),
            source.length(), verified.sessionIds());
        try { ActivityTransferFiles.markVerified(inbox, fresh.id(), fresh.sha256(), fresh.bytes(), fresh.sessions()); }
        catch (IOException ignored) { /* A cache write failure must not hide a valid archive. */ }
        return new Listing(verified, fresh);
    }

    private Cached cached(File source) {
        String base = source.getName().substring(0, source.getName().length() - 4);
        File sidecar = new File(inbox, base + ".verified.json");
        if (!sidecar.isFile() || sidecar.length() > 65536) return null;
        try {
            JsonObject object = JsonParser.parseString(Files.readString(sidecar.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!base.equals(object.get("id").getAsString())) return null;
            if (object.get("bytes").getAsLong() != source.length()) return null;
            String hash = object.get("sha256").getAsString();
            if (!hash.matches("[0-9a-f]{64}")) return null;
            List<String> sessions = new ArrayList<>();
            for (JsonElement item : object.getAsJsonArray("sessions")) {
                String id = item.getAsString();
                if (!id.matches("[0-9a-f-]{36}") || sessions.contains(id)) return null;
                sessions.add(id);
            }
            if (sessions.isEmpty()) return null;
            return new Cached(base, hash, source.length(), List.copyOf(sessions));
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private ActivityArchive.Verified verifyOrIsolate(File source) throws IOException {
        try { return ActivityArchive.verify(source); }
        catch (IOException | RuntimeException invalid) {
            if (!RejectedActivity.isContentFailure(invalid)) throw invalid;
            RejectedActivity.isolate(source, invalid, durability);
            rejected.add(source.getName());
            return null;
        }
    }
    private void reject(File source, Exception invalid) throws IOException {
        if (!RejectedActivity.isContentFailure(invalid)) {
            if (invalid instanceof IOException error) throw error;
            throw new IOException("ACTIVITY_LISTING_FAILED", invalid);
        }
        RejectedActivity.isolate(source, invalid, durability);
        rejected.add(source.getName());
    }
    public static final class NoDataException extends IOException {
        public NoDataException() { super("手机还没有收到活动原始数据"); }
    }

    public List<Result> exportAll() throws IOException {
        return exportSelected(null);
    }
    /** Null is the explicit legacy all path; an empty selection exports nothing. */
    public List<Result> exportSelected(Collection<String> selection) throws IOException {
        rejected.clear();
        Set<String> selected = selection == null ? null : Set.copyOf(selection);
        if (selected != null) {
            for (String name : selected) {
                if (!name.matches("[0-9a-f-]{36}\\.zip")) throw new IOException("INVALID_EXPORT_SELECTION");
                if (!new File(inbox, name).isFile()) throw new IOException("EXPORT_SELECTION_MISSING:" + name);
            }
            if (selected.isEmpty()) return List.of();
        }
        File[] sources = sources();
        List<Result> results = new ArrayList<>();
        for (File source : sources) {
            if (selected != null && !selected.contains(source.getName())) continue;
            var listing = verifiedForListing(source);
            if (listing == null) continue;
            results.add(exportOne(source, listing.verified(), listing.cached().sha256(), NO_PROGRESS));
        }
        return List.copyOf(results);
    }

    private Result exportOne(File source, ActivityArchive.Verified verified, String knownHash,
                             Progress progress) throws IOException {
        String name = ActivityExportName.create(source,verified,java.time.ZoneId.systemDefault());
        ExportDestination.Pending pending = destination.create(name);
        boolean published = false;
        try {
            String copiedHash = copyAndHash(source, pending.openOutput(), progress);
            if (knownHash != null && !knownHash.equals(copiedHash))
                throw new IOException("ACTIVITY_SOURCE_CHANGED_DURING_EXPORT");
            String hash = knownHash != null ? knownHash : copiedHash;
            FileVerification actual = inspect(pending.openInput());
            if (actual.bytes != source.length() || !actual.sha256.equals(hash)) throw new IOException("ACTIVITY_EXPORT_READBACK_MISMATCH");
            pending.publish(); published = true;
            progress.update(source.length(), source.length());
            return new Result(name, source.length(), hash, verified.sessionIds());
        } finally {
            if (!published) try { pending.discard(); } catch (Exception ignored) {}
        }
    }
    private static String copyAndHash(File source, OutputStream raw, Progress progress) throws IOException {
        MessageDigest digest = digest();
        long total = source.length(), copied = 0, next = PROGRESS_STEP;
        try (InputStream input = new FileInputStream(source); OutputStream output = raw) {
            byte[] buffer = new byte[COPY_BUFFER]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_EXPORT_CANCELLED");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                copied += count;
                if (copied >= next || copied == total) { progress.update(copied, total); next += PROGRESS_STEP; }
            }
            output.flush();
        }
        return hex(digest.digest());
    }
    private static FileVerification inspect(InputStream raw) throws IOException {
        try (InputStream input = raw) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[COPY_BUFFER]; int count; long bytes = 0;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_EXPORT_CANCELLED");
                digest.update(buffer, 0, count); bytes += count;
            }
            return new FileVerification(bytes, hex(digest.digest()));
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
    private record FileVerification(long bytes, String sha256) {}
}
