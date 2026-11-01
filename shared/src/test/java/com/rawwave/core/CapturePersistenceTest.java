package com.rawwave.core;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class CapturePersistenceTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void diagnosticFileFailureDoesNotDiscardSubsequentRawSamples() throws Exception {
  var store=new ActivityStore(temp.newFolder(),dir->{});
  var session=store.start(store.createTemplate("test",false),1000,1);
  var folder=temp.newFolder();var journal=new Journal(folder,"watch");
  var log=new java.io.File(folder,"events.jsonl");assertTrue(log.delete());assertTrue(log.mkdir());
  try(var writer=new CapturePersistence(session)) {
   writer.journalEvent(journal,"FAIL_TEST",Map.of("detail","test"));
   writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",-5)));writer.barrier();
   assertEquals(Long.valueOf(1),writer.counts().get("CORE-1/PPG"));
   assertFalse(writer.evidence().get("journalFailure").toString().isEmpty());
   assertEquals(1L,writer.evidence().get("committedRows"));assertEquals(0L,writer.evidence().get("uncommittedRows"));
  }
 }
 @Test public void journalWorkWaitsBehindBlockedDiskWithoutBlockingCaptureCaller() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var armed=new AtomicBoolean();
  var store=new ActivityStore(temp.newFolder(),dir->{if(armed.get()){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}}});
  var session=store.start(store.createTemplate("test",false),1000,1);
  var journal=new Journal(temp.newFolder(),"watch");
  try(var writer=new CapturePersistence(session)) {
   armed.set(true);writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",7)));
   assertTrue(entered.await(3,TimeUnit.SECONDS));
   try {writer.journalEvent(journal,"CAPTURE_TEST_QUEUED",Map.of("detail","retained"));}
   finally{release.countDown();}
   writer.barrier();assertEquals(0,writer.pendingEstimatedBytes());
   String log=new String(journal.snapshot().get("watch/events.jsonl"),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(log.contains("CAPTURE_TEST_QUEUED"));assertTrue(log.contains("retained"));
  }
 }
 @Test public void oversizedNestedPayloadIsRejectedWithoutPoisoningAcceptedWork() throws Exception {
  var store=new ActivityStore(temp.newFolder(),dir->{});
  var session=store.start(store.createTemplate("test",false),1000,1);
  try(var writer=new CapturePersistence(session)) {
   String chunk="x".repeat(1024*1024);
   var large=Collections.nCopies(40,chunk);
   try{writer.append("CORE-1","PPG",List.of(Map.of("raw",large)));fail("missing limit");}
   catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("BACKLOG_LIMIT"));}
   assertEquals(0,writer.pendingEstimatedBytes());
   writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",-1)));writer.barrier();
   assertEquals(Long.valueOf(1),writer.counts().get("CORE-1/PPG"));assertEquals(0,writer.pendingEstimatedBytes());
  }
 }
 @Test public void blockedDiskDoesNotBlockAdmissionAndBarrierPreservesAllRows() throws Exception {
  var armed=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var store=new ActivityStore(temp.newFolder(),dir->{if(armed.get()){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}}});
  var session=store.start(store.createTemplate("test",false),1000,1);
  try(var writer=new CapturePersistence(session)) {
   armed.set(true);writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",-1)));
   assertTrue(entered.await(3,TimeUnit.SECONDS));
   try {for(int i=0;i<1000;i++)writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",i)));assertTrue(writer.counts().isEmpty());}
   finally{release.countDown();}
   writer.barrier();assertEquals(Long.valueOf(1001),writer.counts().get("CORE-1/PPG"));
   session.seal("test",2000);
  }
 }
 @Test public void diskFailureReachesBarrierAndNeverClaimsPersistedRows() throws Exception {
  var fail=new AtomicBoolean();var store=new ActivityStore(temp.newFolder(),dir->{if(fail.get())throw new java.io.IOException("injected disk fault");});
  var session=store.start(store.createTemplate("test",false),1000,1);
  try(var writer=new CapturePersistence(session)) {
   fail.set(true);writer.append("CORE-1","PPG",List.of(Map.of("PPG_GREEN",1)));
   try{writer.barrier();fail("missing error");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("PERSISTENCE_FAILED"));}
   assertTrue(writer.counts().isEmpty());
  }
 }
}
