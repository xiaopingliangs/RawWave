package com.rawwave.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ActivityDataExporterTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void exportsVerifiedArchiveAndReadsDestinationBack() throws Exception {
        File root=temp.newFolder("activities"), outgoing=temp.newFolder("outgoing"), inbox=temp.newFolder("inbox"), visible=temp.newFolder("visible");
        ActivityStore store=new ActivityStore(root,ignored->{}); ActivityStore.Session session=store.start(store.createTemplate("跑步",true),1,2);
        session.append("PRE-1","ECG",List.of(Map.of("ECG_MV",0.1)));
        for(String sensor:SensorCatalog.availableCoreSources()) session.append("CORE-1",sensor,List.of(Map.of("sdkTimestamp",123,"value",-7)));
        session.seal("DONE",3);
        ActivityArchive.Batch batch=ActivityArchive.preparePending(root,outgoing);
        Files.copy(batch.file().toPath(),new File(inbox,batch.file().getName()).toPath());
        List<ActivityDataExporter.Result> result=new ActivityDataExporter(inbox,new FolderDestination(visible),dir -> {}).exportAll();
        assertEquals(1,result.size()); assertEquals(List.of(session.id),result.get(0).sessions());
        File exported=new File(visible,result.get(0).name()); assertTrue(exported.isFile());
        assertEquals(batch.sha256(),ActivityTransferFiles.hashFile(exported));
    }
    @Test public void refusesEmptyInbox() throws Exception {
        ActivityDataExporter.NoDataException failure=assertThrows(ActivityDataExporter.NoDataException.class,
            ()->new ActivityDataExporter(temp.newFolder("empty"),new FolderDestination(temp.newFolder("visible")),dir -> {}).exportAll());
        assertEquals("手机还没有收到活动原始数据",failure.getMessage());
    }
    @Test public void badPackageCannotBlockGoodExportAndEvidenceSurvives() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(),inbox=temp.newFolder(),visible=temp.newFolder();
        ActivityStore store=new ActivityStore(root,dir -> {});
        var session=store.start(store.createTemplate("valid",false),1,2);session.seal("DONE",3);
        var batch=ActivityArchive.preparePending(root,out);
        Files.copy(batch.file().toPath(),new File(inbox,batch.file().getName()).toPath());
        String badId=UUID.randomUUID().toString();byte[] bad={1,2,3};
        Files.write(new File(inbox,badId+".zip").toPath(),bad);
        var exporter=new ActivityDataExporter(inbox,new FolderDestination(visible),dir -> {});
        assertEquals(1,exporter.exportAll().size());assertEquals(List.of(badId+".zip"),exporter.rejectedFiles());
        File attempt=new File(inbox,"rejected").listFiles()[0];
        assertArrayEquals(bad,Files.readAllBytes(new File(attempt,badId+".zip").toPath()));
        assertTrue(new File(attempt,"reason.json").isFile());
        assertEquals(batch.sha256(),ActivityTransferFiles.hashFile(new File(visible,
            ActivityExportName.create(batch.file(),ActivityArchive.verify(batch.file()),java.time.ZoneId.systemDefault()))));
        assertEquals(1,exporter.exportAll().size());assertTrue(exporter.rejectedFiles().isEmpty());
    }
    @Test public void allRejectedIsExplicitAndRepeatedIdDoesNotOverwriteEvidence() throws Exception {
        File inbox=temp.newFolder(),visible=temp.newFolder();String id=UUID.randomUUID().toString();
        var exporter=new ActivityDataExporter(inbox,new FolderDestination(visible),dir -> {});
        for(int value:List.of(1,2)) {
            Files.write(new File(inbox,id+".zip").toPath(),new byte[]{(byte)value});
            assertTrue(exporter.exportAll().isEmpty());assertEquals(1,exporter.rejectedFiles().size());
        }
        assertEquals(2,new File(inbox,"rejected").listFiles().length);assertEquals(0,visible.listFiles().length);
    }
    @Test public void cancellationAndOrdinaryIoAreNotContentFailures() {
        assertFalse(RejectedActivity.isContentFailure(new InterruptedIOException("cancel")));
        assertFalse(RejectedActivity.isContentFailure(new IOException("disk unreadable")));
        assertTrue(RejectedActivity.isContentFailure(new IOException("ACTIVITY_BUNDLE_ENTRY_MISMATCH:x")));
    }
    @Test public void filenameUsesActivityStartNotExportTimeAndPreservesBytes() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder();
        var store=new ActivityStore(root,dir -> {});
        var session=store.start(store.createTemplate("test",false),1792483503220L,2);
        session.seal("DONE",1792483594120L);
        var batch=ActivityArchive.preparePending(root,out);
        var verified=ActivityArchive.verify(batch.file());
        assertEquals("RawWave-activities-2026-10-20_16-05-03_UTCp0800-"+batch.id()+".zip",
            ActivityExportName.create(batch.file(),verified,java.time.ZoneId.of("Asia/Shanghai")));
        assertTrue(ActivityExportName.create(batch.file(),verified,java.time.ZoneOffset.UTC).contains("08-05-03_UTCp0000"));
        assertEquals(batch.sha256(),ActivityTransferFiles.hashFile(batch.file()));
    }
    @Test public void multipleSessionsUseEarliestStartAndMarkCount() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(); var store=new ActivityStore(root,dir -> {});
        store.start(store.createTemplate("later",false),1792483594120L,2).seal("DONE",1792483600000L);
        store.start(store.createTemplate("earlier",false),1792483503220L,3).seal("DONE",1792483600000L);
        var batch=ActivityArchive.preparePending(root,out);
        String name=ActivityExportName.create(batch.file(),ActivityArchive.verify(batch.file()),java.time.ZoneOffset.UTC);
        assertTrue(name.contains("2026-10-20_08-05-03_UTCp0000_2-sessions-"));
    }
    @Test public void unknownStartNeverPretendsToBeExportTime() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(); var store=new ActivityStore(root,dir -> {});
        store.start(store.createTemplate("legacy",false),0,2).seal("DONE",3);
        var batch=ActivityArchive.preparePending(root,out);
        assertTrue(ActivityExportName.create(batch.file(),ActivityArchive.verify(batch.file()),java.time.ZoneOffset.UTC).contains("time-unknown"));
    }
    @Test public void selectionListsNewestButOnlyPublishesChosenArchive() throws Exception {
        File inbox=temp.newFolder(),visible=temp.newFolder();
        List<String> names=new ArrayList<>();
        for(long time:List.of(1792483503220L,1792483594120L)) {
            File root=temp.newFolder(),out=temp.newFolder();var store=new ActivityStore(root,dir -> {});
            store.start(store.createTemplate("test",false),time,2).seal("DONE",time+1000);
            var batch=ActivityArchive.preparePending(root,out);String name=batch.file().getName();
            Files.copy(batch.file().toPath(),new File(inbox,name).toPath());names.add(name);
        }
        var exporter=new ActivityDataExporter(inbox,new FolderDestination(visible),dir -> {});
        var choices=exporter.choices();assertEquals(names.get(1),choices.get(0).file());
        assertEquals(0,visible.listFiles().length);
        assertTrue(exporter.exportSelected(List.of()).isEmpty());
        var results=exporter.exportSelected(List.of(names.get(1)));
        assertEquals(1,results.size());assertEquals(1,visible.listFiles().length);
        assertEquals(ActivityTransferFiles.hashFile(new File(inbox,names.get(1))),results.get(0).sha256());
        assertEquals(2,inbox.listFiles().length);
        assertThrows(IOException.class,()->exporter.exportSelected(List.of("../outside.zip")));
    }
    @Test public void selectedArchiveIsReverifiedAfterListing() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(),inbox=temp.newFolder(),visible=temp.newFolder();
        var store=new ActivityStore(root,dir -> {});
        store.start(store.createTemplate("test",false),1792483503220L,2).seal("DONE",3);
        var batch=ActivityArchive.preparePending(root,out);File source=new File(inbox,batch.file().getName());
        Files.copy(batch.file().toPath(),source.toPath());
        var exporter=new ActivityDataExporter(inbox,new FolderDestination(visible),dir -> {});
        var choices=exporter.choices();Files.write(source.toPath(),new byte[]{1,2,3});
        assertTrue(exporter.exportSelected(List.of(choices.get(0).file())).isEmpty());
        assertEquals(1,exporter.rejectedFiles().size());assertEquals(0,visible.listFiles().length);
    }
    @Test public void recentThreeSelectsSessionsAcrossOldMultiSessionPackageAndPreservesOriginalBytes() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(),inbox=temp.newFolder(),visible=temp.newFolder();
        var store=new ActivityStore(root,dir->{});List<ActivityStore.Session> sessions=new ArrayList<>();
        for(int i=0;i<4;i++) {
            var session=store.start(store.createTemplate("session "+i,false,List.of("GYROSCOPE"),true),1000L+i*1000,1);
            session.append("PRE-100","SPO2",List.of(Map.of("STATUS",2,"SPO2",95+i)));
            session.append("CORE-1","GYROSCOPE",List.of(Map.of("GYROSCOPE_X",0.1*i,"sensorTimestampNs",1234567L)));
            session.seal("DONE",6000);sessions.add(session);
        }
        var batch=ActivityArchive.preparePending(root,out);Files.copy(batch.file().toPath(),new File(inbox,batch.file().getName()).toPath());
        var exporter=new ActivityDataExporter(inbox,new FolderDestination(visible),dir->{});
        var results=exporter.exportRecent(3);assertEquals(1,results.size());
        assertEquals(List.of(sessions.get(3).id,sessions.get(2).id,sessions.get(1).id),results.get(0).sessions());
        assertEquals(1,inbox.listFiles().length);assertEquals(batch.sha256(),ActivityTransferFiles.hashFile(new File(inbox,batch.file().getName())));
        try(var zip=new java.util.zip.ZipFile(new File(visible,results.get(0).name()))) {
            assertNull(zip.getEntry("sessions/"+sessions.get(0).id+"/plan.json"));
            for(var session:sessions.subList(1,4)) for(File original:Objects.requireNonNull(session.folder.listFiles())) {
                try(var input=zip.getInputStream(zip.getEntry("sessions/"+session.id+"/"+original.getName()))) {
                    assertArrayEquals(Files.readAllBytes(original.toPath()),input.readAllBytes());
                }
            }
        }
    }
    @Test public void recentWithFewerThanThreeExportsAvailableAndDuplicateSessionsOnlyOnce() throws Exception {
        File root=temp.newFolder(),out=temp.newFolder(),inbox=temp.newFolder(),visible=temp.newFolder();
        var store=new ActivityStore(root,dir->{});var session=store.start(store.createTemplate("one",false),1000,1);session.seal("DONE",2000);
        for(int i=0;i<2;i++) {
            String id=UUID.randomUUID().toString();File archive=new File(inbox,id+".zip");ActivityArchive.create(archive,id,List.of(session.folder));
        }
        var results=new ActivityDataExporter(inbox,new FolderDestination(visible),dir->{}).exportRecent(3);
        assertEquals(1,results.size());assertEquals(List.of(session.id),results.get(0).sessions());
        assertEquals(2,inbox.listFiles().length);
    }
    private static final class FolderDestination implements ExportDestination {
        private final File root; FolderDestination(File root){this.root=root;}
        public Pending create(String name){
            File pending=new File(root,name+".part"), target=new File(root,name);
            return new Pending(){
                public OutputStream openOutput() throws IOException{return new FileOutputStream(pending);}
                public InputStream openInput() throws IOException{return new FileInputStream(pending);}
                public void publish() throws IOException{Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
                public void discard() throws IOException{Files.deleteIfExists(pending.toPath());}
            };
        }
    }
}

