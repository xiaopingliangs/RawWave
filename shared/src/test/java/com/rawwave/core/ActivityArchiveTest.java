package com.rawwave.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ActivityArchiveTest {
    @Test public void senderContractRestartAckAndSourcePreservation() throws Exception {
        File root=temp.newFolder(),activities=new File(root,"activities");
        var source=session(activities,true);
        SendStore port=new FileSendStore(root,ReceiveStore.Kind.RAW_ACTIVITY,dir -> {});
        var payload=port.prepare(null);
        SendStore replacement=new FileSendStore(root,ReceiveStore.Kind.RAW_ACTIVITY,dir -> {});
        assertEquals(payload,replacement.prepare(null));
        try(InputStream in=replacement.open(payload)) {assertEquals(payload.sha256(),DiagnosticArchive.hash(in.readAllBytes()));}
        assertThrows(IOException.class,() -> replacement.acknowledge(payload,new SendStore.Confirmation(payload.id(),payload.sha256(),payload.bytes()+1,"phone",1)));
        assertEquals(1,ActivityArchive.pendingCount(activities));
        replacement.acknowledge(payload,new SendStore.Confirmation(payload.id(),payload.sha256(),payload.bytes(),"phone",1));
        assertNull(replacement.prepare(null)); assertTrue(source.folder.isDirectory()); assertTrue(payload.file().isFile());
    }
    @Test public void verificationCallbackCancellationDoesNotQuarantineSavedBytes() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(),phone=temp.newFolder();session(root,true);
        var batch=ActivityArchive.preparePending(root,out);
        ReceiveStore port=new FileReceiveStore(phone,dir -> {});
        var request=new ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,batch.id(),batch.sha256(),batch.bytes());
        try(InputStream input=new FileInputStream(batch.file())) {
            assertThrows(InterruptedIOException.class,() -> port.commit(request,input,() -> {throw new InterruptedIOException("cancel");}));
        }
        assertTrue(new File(phone,"activity-inbox/"+batch.id()+".zip").isFile());
        assertFalse(new File(phone,"activity-inbox/rejected").exists());
    }
    @Test public void receiveContractActivityPreservesAllCatalogStreamsAcrossRestart() throws Exception {
        for (boolean sealed : List.of(true, false)) {
            File root=temp.newFolder(), outgoing=temp.newFolder(), phone=temp.newFolder();
            ActivityStore value=store(root);
            ActivityStore.Session source=value.start(value.createTemplate("contract",false),123,456);
            List<String> streams=new ArrayList<>(SensorCatalog.availableCoreSources());
            streams.add(SensorCatalog.MF_BIA); streams.add(SensorCatalog.BIA); streams.add(SensorCatalog.ECG); streams.add(SensorCatalog.SPO2); streams.add(SensorCatalog.EVENT);
            for(String stream:streams) source.append("CORE-1",stream,List.of(Map.of("sdkTimestamp",123,"value",-7)));
            if(sealed) source.seal("USER_COMPLETED",789);
            ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
            List<String> steps=new ArrayList<>();
            ReceiveStore port=new FileReceiveStore(phone,dir -> steps.add(dir.getName()));
            var request=new ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,batch.id(),batch.sha256(),batch.bytes());
            try(InputStream in=new FileInputStream(batch.file())) {
                var receipt=port.commit(request,in,() -> steps.add("verify"));
                assertEquals(request,receipt.request()); assertEquals(List.of(source.id),receipt.sessionIds());
            }
            assertEquals(List.of("verify","activity-inbox",phone.getName()),steps);
            assertArrayEquals(Files.readAllBytes(batch.file().toPath()),Files.readAllBytes(new File(phone,"activity-inbox/"+batch.id()+".zip").toPath()));
            // A replacement instance reads the same disk format and accepts the same immutable transfer.
            ReceiveStore replacement=new FileReceiveStore(phone,dir -> {});
            try(InputStream in=new FileInputStream(batch.file())) {
                assertEquals(List.of(source.id),replacement.commit(request,in,() -> {}).sessionIds());
            }
            assertEquals(1,ActivityArchive.pendingCount(root)); // receive never acknowledges/deletes the watch source
        }
    }
    @Test public void receiveContractDiagnosticIsByteExactAndWaitsForBothDirectorySyncs() throws Exception {
        File phone=temp.newFolder(); byte[] bytes=TransferFiles.fixture();
        var request=new ReceiveStore.Request(ReceiveStore.Kind.DIAGNOSTIC,UUID.randomUUID().toString(),DiagnosticArchive.hash(bytes),bytes.length);
        List<String> steps=new ArrayList<>(); ReceiveStore port=new FileReceiveStore(phone,dir -> steps.add(dir.getName()));
        assertEquals(request,port.commit(request,new ByteArrayInputStream(bytes),() -> fail("No activity verification for diagnostic")).request());
        assertEquals(List.of("received",phone.getName()),steps);
        assertArrayEquals(bytes,Files.readAllBytes(new File(phone,"received/"+request.id()+".zip").toPath()));
    }
    @Test public void receiveContractDirectoryFailureCannotReturnSuccess() throws Exception {
        for(int failingStep:List.of(1,2)) {
            File phone=temp.newFolder(); byte[] bytes=TransferFiles.fixture(); int[] step={0};
            ReceiveStore port=new FileReceiveStore(phone,dir -> { if(++step[0]==failingStep) throw new IOException("SYNC_FAILED"); });
            var request=new ReceiveStore.Request(ReceiveStore.Kind.DIAGNOSTIC,UUID.randomUUID().toString(),DiagnosticArchive.hash(bytes),bytes.length);
            try { port.commit(request,new ByteArrayInputStream(bytes),() -> {}); fail("Must not return a receipt"); }
            catch(IOException expected) { assertEquals("SYNC_FAILED",expected.getMessage()); }
            assertTrue(new File(phone,"received/"+request.id()+".zip").isFile());
        }
    }
    @Test public void receiveContractDeepVerificationFailureCannotReturnSuccess() throws Exception {
        File phone=temp.newFolder(); byte[] invalid=TransferFiles.fixture();
        ReceiveStore port=new FileReceiveStore(phone,dir -> {});
        var request=new ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,UUID.randomUUID().toString(),DiagnosticArchive.hash(invalid),invalid.length);
        try { port.commit(request,new ByteArrayInputStream(invalid),() -> {}); fail("Invalid ZIP"); }
        catch(IOException expected) {
            assertFalse(new File(phone,"activity-inbox/"+request.id()+".verified.json").exists());
            assertFalse(new File(phone,"activity-inbox/"+request.id()+".zip").exists());
            File[] attempts=new File(phone,"activity-inbox/rejected").listFiles();
            assertEquals(1,attempts.length);
            assertArrayEquals(invalid,Files.readAllBytes(new File(attempts[0],request.id()+".zip").toPath()));
        }
    }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ActivityStore store(File root) throws Exception { return new ActivityStore(root, ignored -> {}); }
    private ActivityStore.Session session(File root, boolean seal) throws Exception {
        ActivityStore value = store(root);
        ActivityStore.Session session = value.start(value.createTemplate("步行", true), 123, 456);
        session.append("PRE-1", "ECG", List.of(Map.of("ECG_MV", -0.25, "sdkTimestamp", 10)));
        session.append("CORE-1", "ACCELEROMETER", List.of(Map.of("ACCELEROMETER_X", 9, "ACCELEROMETER_Y", -3)));
        if (seal) session.seal("USER_COMPLETED", 789);
        return session;
    }

    @Test public void sealedSessionIsBytePreservedAndVerified() throws Exception {
        File root = temp.newFolder("activities"), outgoing = temp.newFolder("outgoing");
        ActivityStore.Session source = session(root, true);
        byte[] original = Files.readAllBytes(new File(source.folder,"00000001-PRE-1-ECG.json").toPath());
        ActivityArchive.Batch batch = ActivityArchive.preparePending(root,outgoing);
        ActivityArchive.Verified verified = ActivityArchive.verify(batch.file());
        assertEquals(List.of(source.id),verified.sessionIds()); assertTrue(verified.allSealed());
        assertArrayEquals(original,entry(batch.file(),"sessions/"+source.id+"/00000001-PRE-1-ECG.json"));
    }

    @Test public void unsealedSessionIsExportedWithoutPartTail() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing");
        ActivityStore.Session source=session(root,false);
        Files.writeString(new File(source.folder,"00000099-CORE-1-ECG.json.part").toPath(),"unfinished");
        ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
        ActivityArchive.Verified verified=ActivityArchive.verify(batch.file());
        assertFalse(verified.allSealed()); assertNull(entryOrNull(batch.file(),"sessions/"+source.id+"/00000099-CORE-1-ECG.json.part"));
    }

    @Test public void everyCaptureStreamSurvivesSealedAndInterruptedRecovery() throws Exception {
        for (boolean sealed : List.of(true, false)) {
            File root=temp.newFolder(), outgoing=temp.newFolder(), inbox=temp.newFolder();
            ActivityStore value=store(root);
            ActivityStore.Session source=value.start(value.createTemplate("恢复五传感器",false),123,456);
            List<String> streams=new ArrayList<>(SensorCatalog.availableCoreSources());
            streams.add(SensorCatalog.MF_BIA); streams.add(SensorCatalog.BIA); streams.add(SensorCatalog.ECG); streams.add(SensorCatalog.EVENT);
            for(String stream:streams) source.append("CORE-1",stream,List.of(Map.of("sdkTimestamp",123,"value",-7)));
            if(sealed) source.seal("USER_COMPLETED",789);
            Map<String,byte[]> originals=new TreeMap<>();
            for(File file:Objects.requireNonNull(source.folder.listFiles())) originals.put(file.getName(),Files.readAllBytes(file.toPath()));
            // A normal application restart must recover existing files, without resampling or resealing.
            ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
            assertEquals(originals.size(),ActivityArchive.verify(batch.file()).rawFiles());
            for(var original:originals.entrySet()) {
                assertArrayEquals(original.getValue(),entry(batch.file(),"sessions/"+source.id+"/"+original.getKey()));
                assertArrayEquals(original.getValue(),Files.readAllBytes(new File(source.folder,original.getKey()).toPath()));
            }
            try(InputStream input=new FileInputStream(batch.file())) {
                File received=ActivityTransferFiles.commit(inbox,batch.id(),batch.sha256(),batch.bytes(),input);
                ActivityArchive.Verified verified=ActivityArchive.verify(received);
                assertEquals(sealed,verified.allSealed());
                ActivityTransferFiles.markVerified(inbox,batch.id(),batch.sha256(),batch.bytes(),verified.sessionIds());
            }
            assertEquals(batch.sha256(),ActivityArchive.preparePending(root,outgoing).sha256());
            ActivityArchive.acknowledge(root,outgoing,batch,999);
            assertEquals(0,ActivityArchive.pendingCount(root));
            for(var original:originals.entrySet()) assertArrayEquals(original.getValue(),Files.readAllBytes(new File(source.folder,original.getKey()).toPath()));
        }
    }

    @Test public void retryReusesIdenticalBatch() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing"); session(root,true);
        ActivityArchive.Batch first=ActivityArchive.preparePending(root,outgoing), second=ActivityArchive.preparePending(root,outgoing);
        assertEquals(first.id(),second.id()); assertEquals(first.sha256(),second.sha256()); assertEquals(first.bytes(),second.bytes());
    }

    @Test public void newSensorCorruptionStillFailsAndRemainsPending() throws Exception {
        File root=temp.newFolder(), outgoing=temp.newFolder();
        ActivityStore value=store(root);
        ActivityStore.Session source=value.start(value.createTemplate("PPG",false),1,2);
        source.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",17)));
        source.seal("DONE",3);
        Files.writeString(new File(source.folder,"00000001-CORE-1-PPG.json").toPath(),"corrupt");
        IOException failure=assertThrows(IOException.class,()->ActivityArchive.preparePending(root,outgoing));
        assertTrue(failure.getMessage().startsWith("SOURCE_SEAL_ENTRY_MISMATCH"));
        assertEquals(1,ActivityArchive.pendingCount(root));
    }

    @Test public void unknownStreamsAndUnsafePathsRemainRejected() throws Exception {
        String prefix="sessions/12345678-1234-1234-1234-123456789abc/";
        for(String name:List.of(prefix+"00000001-CORE-1-UNKNOWN.json",prefix+"00000001-CORE-1-PPG.json.part",
                prefix+"../plan.json",prefix+"00000001-OTHER-1-EDA.json")) {
            File archive=new File(temp.newFolder(),"12345678-1234-1234-1234-123456789abc.zip");
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry(name)); zip.write("{}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            assertEquals("UNSAFE_ACTIVITY_ENTRY",assertThrows(IOException.class,()->ActivityArchive.verify(archive)).getMessage());
        }
    }

    @Test public void durableAckSuppressesSession() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing"); session(root,true);
        ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
        ActivityArchive.acknowledge(root,outgoing,batch,999);
        assertEquals(0,ActivityArchive.pendingCount(root)); assertNull(ActivityArchive.preparePending(root,outgoing));
        assertTrue(new File(outgoing,batch.id()+".ack.json").isFile());
    }

    @Test public void changedEntryFailsDeepVerification() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing"); session(root,true);
        ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
        File changed=new File(outgoing,"11111111-1111-1111-1111-111111111111.zip");
        rewrite(batch.file(),changed);
        assertThrows(IOException.class,()->ActivityArchive.verify(changed));
    }

    @Test public void streamingCommitRejectsTruncationAndHashMismatch() throws Exception {
        File inbox=temp.newFolder("inbox"); String id="12345678-1234-1234-1234-123456789abc";
        assertThrows(EOFException.class,()->ActivityTransferFiles.commit(inbox,id,"0".repeat(64),5,new ByteArrayInputStream(new byte[]{1})));
        assertThrows(IOException.class,()->ActivityTransferFiles.commit(inbox,id,"0".repeat(64),1,new ByteArrayInputStream(new byte[]{1})));
        assertFalse(new File(inbox,id+".receipt.json").exists());
    }

    @Test public void streamingCommitIsIdempotentButNeverOverwritesConflict() throws Exception {
        File inbox=temp.newFolder("inbox"); String id="12345678-1234-1234-1234-123456789abc"; byte[] data={1,2,3};
        String hash=DiagnosticArchive.hash(data);
        File file=ActivityTransferFiles.commit(inbox,id,hash,data.length,new ByteArrayInputStream(data));
        assertEquals(file,ActivityTransferFiles.commit(inbox,id,hash,data.length,new ByteArrayInputStream(data)));
        Files.write(file.toPath(),new byte[]{9});
        assertThrows(IOException.class,()->ActivityTransferFiles.commit(inbox,id,hash,data.length,new ByteArrayInputStream(data)));
    }

    @Test public void wholeArchiveAndEntryHashesAreBothRequired() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing"); session(root,true);
        ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
        assertEquals(batch.sha256(),ActivityTransferFiles.hashFile(batch.file()));
        assertTrue(ActivityArchive.verify(batch.file()).rawFiles() >= 4);
    }

    @Test public void sealedSourceTamperCannotBeHiddenByNewBundle() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing");
        ActivityStore.Session source=session(root,true);
        Files.writeString(new File(source.folder,"00000001-PRE-1-ECG.json").toPath(),"changed after seal");
        assertThrows(IOException.class,()->ActivityArchive.preparePending(root,outgoing));
        assertEquals(0,Objects.requireNonNull(outgoing.listFiles((dir,name)->name.endsWith(".zip"))).length);
    }

    private static byte[] entry(File archive,String wanted) throws Exception {
        byte[] value=entryOrNull(archive,wanted); assertNotNull(value); return value;
    }
    private static byte[] entryOrNull(File archive,String wanted) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive),StandardCharsets.UTF_8)){
            ZipEntry entry; while((entry=zip.getNextEntry())!=null) if(entry.getName().equals(wanted)) return zip.readAllBytes();
        } return null;
    }
    private static void rewrite(File source,File target) throws Exception {
        try(ZipInputStream input=new ZipInputStream(new FileInputStream(source),StandardCharsets.UTF_8);
            ZipOutputStream output=new ZipOutputStream(new FileOutputStream(target),StandardCharsets.UTF_8)){
            ZipEntry entry; while((entry=input.getNextEntry())!=null){
                output.putNextEntry(new ZipEntry(entry.getName())); byte[] bytes=input.readAllBytes();
                if(!entry.getName().equals("bundle.json") && bytes.length>0) bytes[0]^=1;
                output.write(bytes); output.closeEntry();
            }
        }
    }
}
