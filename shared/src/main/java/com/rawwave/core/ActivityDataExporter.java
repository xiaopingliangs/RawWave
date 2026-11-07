package com.rawwave.core;

import java.io.*;
import java.security.*;
import java.util.*;

/** Copies verified raw activity archives to a user-visible destination without loading them into memory. */
public final class ActivityDataExporter {
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
    /** One activity session with its real start time; transport packages may contain several. */
    public record SessionChoice(String id, long createdUtcMs, String file, boolean sealed, long fileBytes) {}
    /** Select individual sessions, not transport packages: old multi-session packages are supported. */
    public List<Result> exportRecent(int limit) throws IOException {
        if(limit < 1 || limit > 3) throw new IllegalArgumentException("RECENT_LIMIT");
        var chosen=sessionChoices().stream().limit(limit).toList();
        if(chosen.isEmpty()) throw new IOException("没有开始时间可确认的活动，请使用选择导出");
        return exportSessionChoices(chosen);
    }

    /**
     * Lists every sealed or unsealed session with a real start time. The list never publishes
     * an export; it is safe for date selection and for showing "received N activities".
     */
    public List<SessionChoice> sessionChoices() throws IOException {
        rejected.clear();
        Map<String,SessionChoice> sessions = new LinkedHashMap<>();
        for(File file:sources()) {
            var verified=verifyOrIsolate(file); if(verified==null) continue;
            try(var zip=new java.util.zip.ZipFile(file)) {
                for(String id:verified.sessionIds()) {
                    var entry=zip.getEntry("sessions/"+id+"/plan.json");
                    if(entry==null || entry.getSize()>65536) continue;
                    long time;
                    try(var in=zip.getInputStream(entry); var reader=new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)) {
                        var value=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().get("createdUtcMs");
                        if(value==null || value.isJsonNull()) continue;
                        time=value.getAsLong();
                    } catch(RuntimeException malformed) { continue; }
                    if(time<=0) continue; // Unknown start times cannot claim to be the latest.
                    boolean sealed=zip.getEntry("sessions/"+id+"/seal.json")!=null;
                    var prior=sessions.get(id);
                    if(prior==null || (!prior.sealed() && sealed)) sessions.put(id,
                        new SessionChoice(id,time,file.getName(),sealed,file.length()));
                }
            }
        }
        var list=new ArrayList<>(sessions.values());
        list.sort(Comparator.comparingLong(SessionChoice::createdUtcMs).reversed()
            .thenComparing(SessionChoice::id));
        return List.copyOf(list);
    }

    /** Export exactly the chosen session ids into one new verified archive. */
    public List<Result> exportSessions(Collection<String> ids) throws IOException {
        if(ids==null || ids.isEmpty()) return List.of();
        Set<String> selected=new LinkedHashSet<>();
        for(String id:ids) {
            if(id==null || !id.matches("[0-9a-f-]{36}")) throw new IOException("INVALID_SESSION_SELECTION");
            selected.add(id);
        }
        var chosen=sessionChoices().stream().filter(item -> selected.contains(item.id())).toList();
        if(chosen.size()!=selected.size()) throw new IOException("EXPORT_SESSION_MISSING");
        return exportSessionChoices(chosen);
    }

    private List<Result> exportSessionChoices(List<SessionChoice> chosen) throws IOException {
        java.nio.file.Path scratch=java.nio.file.Files.createTempDirectory(inbox.toPath(),"session-export-");
        try {
            List<File> folders=new ArrayList<>();
            for(var item:chosen) {
                File folder=new File(scratch.toFile(),item.id());
                java.nio.file.Files.createDirectories(folder.toPath());folders.add(folder);
                try(var zip=new java.util.zip.ZipFile(new File(inbox,item.file()))) {
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
            return List.of(exportOne(output,ActivityArchive.verify(output)));
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
            var verified = verifyOrIsolate(source);
            if (verified != null) choices.add(new Choice(source.getName(),
                ActivityExportName.create(source, verified, java.time.ZoneId.systemDefault()), verified.sessionIds().size()));
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
    private ActivityArchive.Verified verifyOrIsolate(File source) throws IOException {
        try { return ActivityArchive.verify(source); }
        catch (IOException | RuntimeException invalid) {
            if (!RejectedActivity.isContentFailure(invalid)) throw invalid;
            RejectedActivity.isolate(source, invalid, durability);
            rejected.add(source.getName());
            return null;
        }
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
            ActivityArchive.Verified verified = verifyOrIsolate(source);
            if (verified == null) continue;
            results.add(exportOne(source,verified));
        }
        return List.copyOf(results);
    }

    private Result exportOne(File source, ActivityArchive.Verified verified) throws IOException {
        String hash = ActivityTransferFiles.hashFile(source);
        String name = ActivityExportName.create(source,verified,java.time.ZoneId.systemDefault());
        ExportDestination.Pending pending = destination.create(name);
        boolean published = false;
        try {
            copy(source, pending.openOutput());
            FileVerification actual = inspect(pending.openInput());
            if (actual.bytes != source.length() || !actual.sha256.equals(hash)) throw new IOException("ACTIVITY_EXPORT_READBACK_MISMATCH");
            pending.publish(); published = true;
            return new Result(name, source.length(), hash, verified.sessionIds());
        } finally {
            if (!published) try { pending.discard(); } catch (Exception ignored) {}
        }
    }
    private static void copy(File source, OutputStream raw) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = raw) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_EXPORT_CANCELLED");
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }
    private static FileVerification inspect(InputStream raw) throws IOException {
        try (InputStream input = raw) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            byte[] buffer = new byte[64 * 1024]; int count; long bytes = 0;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_EXPORT_CANCELLED");
                digest.update(buffer, 0, count); bytes += count;
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            return new FileVerification(bytes, hash.toString());
        }
    }    private record FileVerification(long bytes, String sha256) {}
}


