package com.rawwave.core;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
public class DiagnosticDispatcherTest {
 @Test public void blockedSinkCannotBlockCallerAndOverflowIsCounted()throws Exception {
  var queue=new DiagnosticDispatcher(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  try {
   assertTrue(queue.submit("FIRST",Map.of(),(k,v)->{entered.countDown();release.await(5,TimeUnit.SECONDS);}));
   assertTrue(entered.await(2,TimeUnit.SECONDS));
   assertTrue(queue.submit("SECOND",Map.of(),(k,v)->{}));
   assertFalse(queue.submit("THIRD",Map.of(),(k,v)->fail("must not run")));
   assertEquals(1,queue.rejected());
  }finally{release.countDown();queue.close();assertTrue(queue.awaitClosed(5,TimeUnit.SECONDS));}
 }
 @Test public void writeFailureIsReportedInNextEventAndDoesNotStopQueue()throws Exception {
  var queue=new DiagnosticDispatcher(4);var output=new ArrayList<Map<String,Object>>();
  queue.submit("FAIL",Map.of(),(k,v)->{throw new java.io.IOException("injected");});
  queue.submit("NEXT",Map.of("sensor","PPG"),(k,v)->output.add(v));
  queue.close();assertTrue(queue.awaitClosed(5,TimeUnit.SECONDS));
  assertEquals(1,queue.failed());assertEquals(1L,output.get(0).get("diagnosticWriteFailures"));
  assertTrue(output.get(0).get("diagnosticLastFailure").toString().contains("injected"));
 }
}
