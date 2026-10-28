package com.rawwave.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local scientific records, separate from rotating diagnostics. Single writer per session. */
public final class ActivityStore {
    public interface DirectorySync { void sync(File directory) throws IOException; }
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final File root;
    private final DirectorySync directorySync;
    public ActivityStore(File root, DirectorySync directorySync) throws IOException {
        this.root = root; this.directorySync = directorySync;
        Files.createDirectories(root.toPath());
    }
    public record Template(String id, String name, boolean ecg, List<String> coreSources, boolean spo2, BiaProfile biaProfile, boolean mfBia) {
        public Template {
            Objects.requireNonNull(id); Objects.requireNonNull(name);
            coreSources = SensorCatalog.normalizeCoreSources(coreSources);
        }
        public Template(String id,String name,boolean ecg,List<String> sources,boolean spo2,BiaProfile profile) {
            this(id,name,ecg,sources,spo2,profile,false);
        }
        public boolean bia() { return biaProfile != null; }
        public Template(String id, String name, boolean ecg, List<String> sources, boolean spo2) {
            this(id,name,ecg,sources,spo2,null);
        }
        /** Source-compatible constructor and migration default for older templates. */
        public Template(String id, String name, boolean ecg) {
            this(id, name, ecg, SensorCatalog.defaultCoreSources());
        }
        public Template(String id, String name, boolean ecg, List<String> coreSources) {
            this(id, name, ecg, coreSources, false);
        }
    }
    public Template createTemplate(String name, boolean ecg) throws IOException {
        return createTemplate(name, ecg, SensorCatalog.defaultCoreSources());
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources) throws IOException {
        return createTemplate(name, ecg, coreSources, false);
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources, boolean spo2) throws IOException {
        return createTemplate(name,ecg,coreSources,spo2,null);
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources, boolean spo2, BiaProfile profile) throws IOException {
        return createTemplate(name,ecg,coreSources,spo2,profile,false);
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources, boolean spo2, BiaProfile profile, boolean mfBia) throws IOException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("活动名称需要1至40个字");
        final List<String> normalized;
        try { normalized = SensorCatalog.normalizeCoreSources(coreSources); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(),e); }
        Template t = new Template(UUID.randomUUID().toString(), name, ecg, normalized, spo2, profile, mfBia);
        File folder = new File(root,"templates"); Files.createDirectories(folder.toPath());
        write(new File(folder,t.id()+".json"),t); return t;
    }
    /** Replace only this template; existing session plans remain immutable snapshots. */
    public synchronized Template updateTemplate(String id, String name, boolean ecg,
            Collection<String> sources, boolean spo2) throws IOException {
        return updateTemplate(id,name,ecg,sources,spo2,null);
    }
    public synchronized Template updateTemplate(String id, String name, boolean ecg,
            Collection<String> sources, boolean spo2, BiaProfile profile) throws IOException {
        return updateTemplate(id,name,ecg,sources,spo2,profile,false);
    }
    public synchronized Template updateTemplate(String id,String name,boolean ecg,Collection<String> sources,boolean spo2,BiaProfile profile,boolean mfBia) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("INVALID_TEMPLATE_ID");
        name = name.trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("活动名称需要1至40个字");
        final Template updated;
        try { updated = new Template(id, name, ecg, SensorCatalog.normalizeCoreSources(sources), spo2, profile, mfBia); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        File folder = new File(root,"templates"), target = new File(folder,id+".json");
        if (!target.isFile()) throw new IOException("活动已不存在，请返回重新选择");
        File temporary = File.createTempFile("edit-", ".part", folder);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(JSON.toJson(updated).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            directorySync.sync(folder);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return updated;
    }
    public List<Template> templates() throws IOException {
        File[] files = new File(root,"templates").listFiles((d,n)->n.endsWith(".json"));
        List<Template> result = new ArrayList<>();
        if(files != null) for(File f:files) {
            JsonObject object = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
            List<String> coreSources = null;
            if (object.has("coreSources") && !object.get("coreSources").isJsonNull()) {
                coreSources = new ArrayList<>();
                for (JsonElement value : object.getAsJsonArray("coreSources")) coreSources.add(value.getAsString());
            }
            result.add(new Template(object.get("id").getAsString(), object.get("name").getAsString(),
                object.get("ecg").getAsBoolean(), coreSources, object.has("spo2") && object.get("spo2").getAsBoolean(),
                object.has("biaProfile") ? JSON.fromJson(object.get("biaProfile"), BiaProfile.class) : null, object.has("mfBia") && object.get("mfBia").getAsBoolean()));
        }
        result.sort(Comparator.comparing(Template::name)); return result;
    }
    public Session start(Template template, long utcMs, long elapsedMs) throws IOException {
        return new Session(template,utcMs,elapsedMs);
    }
    /** Deletes only a template, never session data. */
    public void deleteTemplate(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("INVALID_TEMPLATE_ID");
        File folder = new File(root,"templates");
        Files.deleteIfExists(new File(folder,id+".json").toPath());
        if(folder.isDirectory()) directorySync.sync(folder);
    }
    private void write(File target,Object value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        File temp = new File(target.getPath()+".part");
        if(target.exists()) throw new IOException("IMMUTABLE_FILE_EXISTS");
        try(FileOutputStream out = new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        directorySync.sync(target.getParentFile());
    }
    public List<String> history() throws IOException {
        List<String> result = new ArrayList<>();
        File[] folders = new File(root,"sessions").listFiles(File::isDirectory);
        if(folders == null) return result;
        Arrays.sort(folders,Comparator.comparingLong(File::lastModified).reversed());
        for(File f:folders) {
            File plan = new File(f,"plan.json");
            String name = plan.isFile() ? JsonParser.parseString(Files.readString(plan.toPath())).getAsJsonObject().getAsJsonObject("template").get("name").getAsString() : "未知活动";
            result.add(name+" · "+(new File(f,"seal.json").isFile()?"已封存（详见记录）":"未封存/曾中断，已有块保留，尾部未知")+"\n"+f.getName());
        }
        return result;
    }
    /** Small diagnostic metadata snapshot. Not an export or re-verification of raw health data. */
    public static byte[] diagnosticSummary(File root) throws IOException {
        List<Map<String,Object>> result = new ArrayList<>();
        File[] folders = new File(root,"sessions").listFiles(File::isDirectory);
        if(folders == null) folders = new File[0];
        Arrays.sort(folders,Comparator.comparingLong(File::lastModified).reversed());
        for(File folder:Arrays.copyOf(folders,Math.min(3,folders.length))) {
            Map<String,Object> item = new LinkedHashMap<>(); item.put("sessionId",folder.getName());
            try {
                File plan = new File(folder,"plan.json"), seal = new File(folder,"seal.json");
                if(plan.length() > 65536 || seal.length() > 4*1024*1024) throw new IOException("METADATA_TOO_LARGE");
                item.put("plan",JsonParser.parseString(Files.readString(plan.toPath())));
                item.put("sealed",seal.isFile());
                if(seal.isFile()) {
                    JsonObject sealed=JsonParser.parseString(Files.readString(seal.toPath())).getAsJsonObject();
                    item.put("reason",sealed.get("reason")); item.put("counts",sealed.get("counts"));
                    item.put("endedUtcMs",sealed.get("endedUtcMs"));
                    item.put("integrityAtSeal",sealed.get("fileIntegrity"));
                    item.put("sealSha256",DiagnosticArchive.hash(Files.readAllBytes(seal.toPath())));
                } else item.put("interruption","UNSEALED_CAUSE_AND_UNCOMMITTED_TAIL_UNKNOWN");
            } catch(Exception e) { item.put("metadataError",e.toString()); }
            result.add(item);
        }
        return JSON.toJson(Map.of("kind","LOCAL_CAPTURE_METADATA_NOT_RAW_DATA", "sessions",result,
            "omittedOlderSessions",Math.max(0,folders.length-3),"rawDataReverifiedAtReportTime",false)).getBytes(StandardCharsets.UTF_8);
    }
    public final class Session {
        public final String id = UUID.randomUUID().toString();
        public final File folder;
        private final Map<String,Object> files = new TreeMap<>();
        private final Map<String,Long> rows = new TreeMap<>();
        private int sequence;
        private boolean sealed;
        private Session(Template t,long utc,long elapsed) throws IOException {
            folder = new File(new File(root,"sessions"),id); Files.createDirectories(folder.toPath());
            Map<String,Object> plan=new LinkedHashMap<>();
            plan.put("schema",4); plan.put("sessionId",id); plan.put("template",t);
            plan.put("createdUtcMs",utc); plan.put("createdElapsedMs",elapsed);
            plan.put("coreSources",t.coreSources());
            plan.put("source","Samsung Health Sensor SDK 1.4.1; GYROSCOPE and PRESSURE use Android SensorManager");
            Map<String,String> kinds = new LinkedHashMap<>();
            if (t.ecg()) kinds.put(SensorCatalog.ECG,SensorCatalog.dataKind(SensorCatalog.ECG));
            if (t.bia()) { String body=t.mfBia()?"MF_BIA":"BIA"; kinds.put(body,SensorCatalog.dataKind(body)); }
            if (t.spo2()) kinds.put(SensorCatalog.SPO2,SensorCatalog.dataKind(SensorCatalog.SPO2));
            for (String sensor : t.coreSources()) kinds.put(sensor,SensorCatalog.dataKind(sensor));
            plan.put("dataKinds",kinds);
            plan.put("rawPolicy","SDK values copied without filtering, interpolation, invented samples or unit conversion; quality metadata is separate");
            plan.put("sampleTime","Samsung SDK timestamp milliseconds retained; Android platform original sensorTimestampNs retained with separately derived sdkTimestamp milliseconds; receipt clocks and captureCallback fields are separate metadata");
            plan.put("durability","Periodic immutable blocks; uncommitted memory and .part tail may be lost on power failure");
            write(new File(folder,"plan.json"),plan);
            directorySync.sync(folder.getParentFile());
        }
        public synchronized void append(String phase,String sensor,List<? extends Map<String,?>> samples) throws IOException {
            if(sealed) throw new IOException("SESSION_SEALED");
            if(!phase.matches("(PRE|CORE|POST)-[0-9]+") || !SensorCatalog.acceptsStream(sensor)) throw new IOException("INVALID_STREAM");
            if(samples.isEmpty()) return;
            String name=String.format(Locale.ROOT,"%08d-%s-%s.json",++sequence,phase,sensor);
            File file=new File(folder,name);
            write(file,Map.of("phase",phase,"sensor",sensor,"samples",samples));
            byte[] bytes=Files.readAllBytes(file.toPath());
            files.put(name,Map.of("bytes",bytes.length,"sha256",DiagnosticArchive.hash(bytes),"rows",samples.size()));
            rows.merge(phase+"/"+sensor,(long)samples.size(),Long::sum);
        }
        public synchronized Map<String,Long> counts(){return Map.copyOf(rows);}
        public synchronized void seal(String reason,long utcMs) throws IOException {
            if(sealed) return;
            Map<String,Object> manifest=new TreeMap<>(files);
            byte[] plan=Files.readAllBytes(new File(folder,"plan.json").toPath());
            manifest.put("plan.json",Map.of("bytes",plan.length,"sha256",DiagnosticArchive.hash(plan)));
            for(var e:manifest.entrySet()) {
                Map<?,?> expected=(Map<?,?>)e.getValue();
                byte[] actual=Files.readAllBytes(new File(folder,e.getKey()).toPath());
                if(actual.length!=((Number)expected.get("bytes")).longValue() || !DiagnosticArchive.hash(actual).equals(expected.get("sha256"))) throw new IOException("SEAL_READBACK_MISMATCH");
            }
            write(new File(folder,"seal.json"),Map.of("schema",1,"sessionId",id,"reason",reason,"endedUtcMs",utcMs,
                "fileIntegrity","SEALED_VERIFIED","coverage","See per-source events; file integrity is not sensor success", "counts",rows,"files",manifest));
            sealed=true;
        }
    }
}
