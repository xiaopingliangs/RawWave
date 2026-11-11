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
    /**
     * Pre/activity/post choices are independent. The legacy {@code ecg}/{@code spo2}/{@code ppg}
     * accessors remain "selected in any phase" so old plans, permissions and summaries keep
     * working; new writes always include the per-phase flags.
     */
    public record Template(String id, String name,
                           boolean preEcg, boolean postEcg,
                           List<String> coreSources,
                           boolean preSpo2, boolean postSpo2,
                           BiaProfile biaProfile, boolean mfBia, boolean biaBoth,
                           boolean prePpg, boolean postPpg) {
        public Template {
            Objects.requireNonNull(id); Objects.requireNonNull(name);
            coreSources = SensorCatalog.normalizeCoreSources(coreSources);
        }
        /** Source-compatible constructor for templates written before phases were split. */
        public Template(String id, String name, boolean ecg, List<String> coreSources, boolean spo2,
                        BiaProfile biaProfile, boolean mfBia, boolean ppg, boolean biaBoth) {
            this(id, name, ecg, ecg, coreSources, spo2, spo2, biaProfile, mfBia, biaBoth, ppg, ppg);
        }
        /** Source-compatible constructor for templates written before the two-BIA-mode option existed. */
        public Template(String id, String name, boolean ecg, List<String> coreSources, boolean spo2,
                        BiaProfile biaProfile, boolean mfBia, boolean ppg) {
            this(id, name, ecg, coreSources, spo2, biaProfile, mfBia, ppg, false);
        }
        /** Source-compatible constructor for templates written before the PPG short measurement existed. */
        public Template(String id, String name, boolean ecg, List<String> coreSources, boolean spo2, BiaProfile biaProfile, boolean mfBia) {
            this(id, name, ecg, coreSources, spo2, biaProfile, mfBia, false);
        }
        public Template(String id,String name,boolean ecg,List<String> sources,boolean spo2,BiaProfile profile) {
            this(id,name,ecg,sources,spo2,profile,false);
        }
        public boolean bia() { return biaProfile != null; }
        /** Legacy aggregate: true when ECG is selected before or after the activity. */
        public boolean ecg() { return preEcg || postEcg; }
        /** Legacy aggregate: true when SpO2 is selected before or after the activity. */
        public boolean spo2() { return preSpo2 || postSpo2; }
        /** Legacy aggregate: true when the 100 Hz PPG short measurement is selected in any phase. */
        public boolean ppg() { return prePpg || postPpg; }
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
        return createTemplate(name, ecg, coreSources, spo2, profile, mfBia, false);
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources, boolean spo2, BiaProfile profile, boolean mfBia, boolean ppg) throws IOException {
        return createTemplate(name,ecg,coreSources,spo2,profile,mfBia,ppg,false);
    }
    public Template createTemplate(String name, boolean ecg, Collection<String> coreSources, boolean spo2, BiaProfile profile, boolean mfBia, boolean ppg, boolean biaBoth) throws IOException {
        return createTemplate(name, ecg, ecg, coreSources, spo2, spo2, profile, mfBia, biaBoth, ppg, ppg);
    }
    /** Explicit pre/activity/post choices; the editor always uses this overload. */
    public Template createTemplate(String name, boolean preEcg, boolean postEcg, Collection<String> coreSources,
            boolean preSpo2, boolean postSpo2, BiaProfile profile, boolean mfBia, boolean biaBoth,
            boolean prePpg, boolean postPpg) throws IOException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("活动名称需要1至40个字");
        final List<String> normalized;
        try { normalized = SensorCatalog.normalizeCoreSources(coreSources); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(),e); }
        Template t = new Template(UUID.randomUUID().toString(), name, preEcg, postEcg, normalized,
            preSpo2, postSpo2, profile, mfBia, biaBoth, prePpg, postPpg);
        File folder = new File(root,"templates"); Files.createDirectories(folder.toPath());
        write(new File(folder,t.id()+".json"),templateJson(t)); return t;
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
        return updateTemplate(id, name, ecg, sources, spo2, profile, mfBia, false);
    }
    public synchronized Template updateTemplate(String id,String name,boolean ecg,Collection<String> sources,boolean spo2,BiaProfile profile,boolean mfBia,boolean ppg) throws IOException {
        return updateTemplate(id,name,ecg,sources,spo2,profile,mfBia,ppg,false);
    }
    public synchronized Template updateTemplate(String id,String name,boolean ecg,Collection<String> sources,boolean spo2,BiaProfile profile,boolean mfBia,boolean ppg,boolean biaBoth) throws IOException {
        return updateTemplate(id, name, ecg, ecg, sources, spo2, spo2, profile, mfBia, biaBoth, ppg, ppg);
    }
    /** Explicit pre/activity/post choices; the editor always uses this overload. */
    public synchronized Template updateTemplate(String id, String name, boolean preEcg, boolean postEcg,
            Collection<String> sources, boolean preSpo2, boolean postSpo2, BiaProfile profile,
            boolean mfBia, boolean biaBoth, boolean prePpg, boolean postPpg) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("INVALID_TEMPLATE_ID");
        name = name.trim();
        if (name.isEmpty() || name.length() > 40) throw new IOException("活动名称需要1至40个字");
        final Template updated;
        try { updated = new Template(id, name, preEcg, postEcg, SensorCatalog.normalizeCoreSources(sources),
            preSpo2, postSpo2, profile, mfBia, biaBoth, prePpg, postPpg); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        File folder = new File(root,"templates"), target = new File(folder,id+".json");
        if (!target.isFile()) throw new IOException("活动已不存在，请返回重新选择");
        File temporary = File.createTempFile("edit-", ".part", folder);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(JSON.toJson(templateJson(updated)).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            directorySync.sync(folder);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return updated;
    }
    /**
     * Template JSON keeps the legacy aggregate keys for older readers and adds the exact
     * per-phase flags used by the capture service.
     */
    static Map<String,Object> templateJson(Template t) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", t.id()); value.put("name", t.name());
        value.put("preEcg", t.preEcg()); value.put("postEcg", t.postEcg()); value.put("ecg", t.ecg());
        value.put("coreSources", t.coreSources());
        value.put("preSpo2", t.preSpo2()); value.put("postSpo2", t.postSpo2()); value.put("spo2", t.spo2());
        value.put("biaProfile", t.biaProfile()); value.put("mfBia", t.mfBia()); value.put("biaBoth", t.biaBoth());
        value.put("prePpg", t.prePpg()); value.put("postPpg", t.postPpg()); value.put("ppg", t.ppg());
        return value;
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
            boolean legacyEcg = flag(object, "ecg", false);
            boolean legacySpo2 = flag(object, "spo2", false);
            boolean legacyPpg = flag(object, "ppg", false);
            result.add(new Template(object.get("id").getAsString(), object.get("name").getAsString(),
                flag(object, "preEcg", legacyEcg), flag(object, "postEcg", legacyEcg), coreSources,
                flag(object, "preSpo2", legacySpo2), flag(object, "postSpo2", legacySpo2),
                object.has("biaProfile") && !object.get("biaProfile").isJsonNull()
                    ? JSON.fromJson(object.get("biaProfile"), BiaProfile.class) : null,
                flag(object, "mfBia", false), flag(object, "biaBoth", false),
                flag(object, "prePpg", legacyPpg), flag(object, "postPpg", legacyPpg)));
        }
        result.sort(Comparator.comparing(Template::name)); return result;
    }
    private static boolean flag(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
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
        private ActivityTransportWriter transport;
        private File transportOutgoing;
        private final String activityName;
        private final String activityTemplateId;
        private int sequence;
        private boolean sealed;
        private Session(Template t,long utc,long elapsed) throws IOException {
            activityName = t.name(); activityTemplateId = t.id();
            folder = new File(new File(root,"sessions"),id); Files.createDirectories(folder.toPath());
            Map<String,Object> plan=new LinkedHashMap<>();
            plan.put("schema",5); plan.put("sessionId",id); plan.put("template",templateJson(t));
            plan.put("createdUtcMs",utc); plan.put("createdElapsedMs",elapsed);
            plan.put("coreSources",t.coreSources());
            plan.put("source","Samsung Health Sensor SDK 1.4.1; GYROSCOPE, GYROSCOPE_UNCALIBRATED, PLATFORM_ACCELEROMETER, PRESSURE, MAGNETIC_FIELD, MAGNETIC_FIELD_UNCALIBRATED, LIGHT, STEP_COUNT, STEP_DETECTOR and OFFBODY use Android SensorManager; GPS uses Android LocationManager with a GnssStatus snapshot and is platform location, not raw GNSS measurements");
            Map<String,String> kinds = new LinkedHashMap<>();
            kinds.put(SensorCatalog.DEVICE_STATE,SensorCatalog.dataKind(SensorCatalog.DEVICE_STATE));
            if (t.ecg()) kinds.put(SensorCatalog.ECG,SensorCatalog.dataKind(SensorCatalog.ECG));
            if (t.bia()) {
                if (t.biaBoth()) {
                    kinds.put(SensorCatalog.BIA,SensorCatalog.dataKind(SensorCatalog.BIA));
                    kinds.put(SensorCatalog.MF_BIA,SensorCatalog.dataKind(SensorCatalog.MF_BIA));
                } else {
                    String body=t.mfBia()?"MF_BIA":"BIA";
                    kinds.put(body,SensorCatalog.dataKind(body));
                }
            }
            if (t.spo2()) kinds.put(SensorCatalog.SPO2,SensorCatalog.dataKind(SensorCatalog.SPO2));
            if (t.ppg()) kinds.put(SensorCatalog.PPG_ON_DEMAND,SensorCatalog.dataKind(SensorCatalog.PPG_ON_DEMAND));
            for (String sensor : t.coreSources()) kinds.put(sensor,SensorCatalog.dataKind(sensor));
            plan.put("dataKinds",kinds);
            plan.put("rawPolicy","SDK values copied without filtering, interpolation, invented samples or unit conversion; quality metadata is separate");
            plan.put("sampleTime","Samsung SDK timestamp milliseconds retained; Android platform original sensorTimestampNs retained with separately derived sdkTimestamp milliseconds; GPS Location.getElapsedRealtimeNanos and Location.getTime UTC retained; receipt clocks and captureCallback fields are separate metadata");
            if (t.coreSources().contains(SensorCatalog.GPS)) plan.put("gpsPolicy",
                "LocationManager GPS_PROVIDER platform fixes only; not raw GNSS measurements; no filtering, interpolation, smoothing, invented points or road snapping; provider-disabled, permission and start failures are explicit; no delivered tail is provable after stop because LocationManager has no flush API; Location.extras are not captured; a GnssStatus snapshot (satellite/used/C/N0/constellation counts) is attached to each delivered fix when the platform provides it");
            plan.put("deviceStatePolicy",
                "Battery, charging, screen, keyguard and display state are sampled about once per minute and at session start/end; context metadata only, not a health measurement; unknown values stay null; screen state is recorded directly, never inferred from sensor callback batch sizes");
            if (t.coreSources().contains(SensorCatalog.STEP_COUNT)) plan.put("stepCountPolicy",
                "TYPE_STEP_COUNTER cumulative platform counter since boot; callbacks occur only when the platform counter changes, so long gaps while stationary are expected and are not missing samples or zero steps; original float value and sensorTimestampNs retained; platform counter, not raw motion and not a per-step event stream; no filtering, interpolation, invented steps or step inference");
            if (t.coreSources().contains(SensorCatalog.STEP_DETECTOR)) plan.put("stepDetectorPolicy",
                "TYPE_STEP_DETECTOR per-step event; each callback is one platform-detected step (raw value normally 1.0), with original sensorTimestampNs and accuracy; event stream, not raw motion waveform; sparse callbacks while stationary are expected and are not missing steps; no filtering, interpolation, invented steps, cadence or gait inference");
            if (t.coreSources().contains(SensorCatalog.OFFBODY)) plan.put("offBodyPolicy",
                "TYPE_LOW_LATENCY_OFFBODY_DETECT platform on-body/off-body state; raw value normally 1.0 on-body and 0.0 off-body, with original sensorTimestampNs and accuracy; state events, not raw optical or skin data; sparse callbacks while state is unchanged are expected; no filtering or inference");
            if (t.coreSources().contains(SensorCatalog.GYROSCOPE_UNCALIBRATED)) plan.put("gyroscopeUncalibratedPolicy",
                "TYPE_GYROSCOPE_UNCALIBRATED uncalibrated angular velocity XYZ rad/s plus platform estimated bias XYZ rad/s; original sensorTimestampNs and accuracy retained; platform uncalibrated values, not app bias estimation; no filtering, calibration or unit conversion");
            if (t.coreSources().contains(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED)) plan.put("magneticFieldUncalibratedPolicy",
                "TYPE_MAGNETIC_FIELD_UNCALIBRATED uncalibrated magnetic field XYZ microtesla plus platform hard-iron bias XYZ microtesla; original sensorTimestampNs and accuracy retained; platform uncalibrated values, not app calibration; no filtering or unit conversion");
            plan.put("durability","Periodic immutable blocks; uncommitted memory and .part tail may be lost on power failure");
            write(new File(folder,"plan.json"),plan);
            directorySync.sync(folder.getParentFile());
            // Build the transport ZIP while blocks are written. This is a derived copy; if it
            // fails, the raw session remains and preparePending rebuilds it later.
            try {
                transport = new ActivityTransportWriter(root, id, new File(folder,"plan.json"));
                transportOutgoing = transport.outgoing();
            } catch (IOException ignored) { transport = null; }
        }
        public synchronized void append(String phase,String sensor,List<? extends Map<String,?>> samples) throws IOException {
            if(sealed) throw new IOException("SESSION_SEALED");
            if(!phase.matches("(PRE|CORE|POST)-[0-9]+") || !SensorCatalog.acceptsStream(sensor)) throw new IOException("INVALID_STREAM");
            if(samples.isEmpty()) return;
            String name=String.format(Locale.ROOT,"%08d-%s-%s.json",++sequence,phase,sensor);
            File file=new File(folder,name);
            // The activity name is header metadata, stored once per block. Samples stay byte-for-byte
            // the sensor values and never repeat or rewrite the activity label.
            Map<String,Object> block=new LinkedHashMap<>();
            block.put("activityName",activityName); block.put("activityTemplateId",activityTemplateId);
            block.put("phase",phase); block.put("sensor",sensor); block.put("samples",samples);
            write(file,block);
            byte[] bytes=Files.readAllBytes(file.toPath());
            files.put(name,Map.of("bytes",bytes.length,"sha256",DiagnosticArchive.hash(bytes),"rows",samples.size()));
            rows.merge(phase+"/"+sensor,(long)samples.size(),Long::sum);
            ActivityTransportWriter writer=transport;
            if(writer!=null) writer.add(name,file);
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
            ActivityTransportWriter writer=transport;
            if(writer!=null) {
                writer.finish(new File(folder,"seal.json"));
                try { if(transportOutgoing!=null) directorySync.sync(transportOutgoing); } catch (IOException ignored) {}
                writer.close();
                transport=null;
            }
        }
        /** Abandon an unfinished derived transport ZIP; raw blocks stay authoritative. */
        public synchronized void close() {
            ActivityTransportWriter writer=transport; transport=null;
            if(writer!=null) writer.close();
        }
    }
}
