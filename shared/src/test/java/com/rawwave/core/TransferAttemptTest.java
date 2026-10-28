package com.rawwave.core;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.rawwave.core.TransferAttempt.Action.*;

public class TransferAttemptTest {
    @Test public void lostReadyCanRecoverWithinSameActivityWindowWithoutSecondChannel() {
        var phone=receiver(true);var watch=new TransferAttempt(true,true,100);
        watch.bindSender(id,1);
        // The first READY is dropped; same HELLO can request another, not another attempt.
        assertEquals(REPEAT_HELLO,phone.accept("HELLO",id,2));
        assertEquals(READY,watch.accept("READY",id,3));
        assertTrue(phone.acceptChannel(id.peer(),"/file/"+id.senderWindow()+"/"+id.id(),"/file/",4));
        assertEquals(IGNORE,phone.accept("HELLO",id,5));
        assertFalse(phone.acceptChannel(id.peer(),"/file/"+id.senderWindow()+"/"+id.id(),"/file/",6));
        assertEquals(IGNORE,phone.accept("HELLO",id,100));
    }
    @Test public void oldLeaseCallbackCannotReleaseNewOwner() {
        var scope=new LeaseScope<Object>();Object old=new Object(), next=new Object();
        var releases=new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(scope.acquire(old,()->{}));assertTrue(scope.release(old,releases::incrementAndGet));
        assertTrue(scope.acquire(next,()->{}));assertFalse(scope.release(old,releases::incrementAndGet));
        assertSame(next,scope.current());assertEquals(1,releases.get());
    }
    @Test public void leaseIdentityIsNotValueEquality() {
        var scope=new LeaseScope<String>();String owner=new String("same"),other=new String("same");
        assertTrue(scope.acquire(owner,()->{}));assertFalse(scope.release(other,()->fail("wrong owner")));
        assertSame(owner,scope.current());
    }
    @Test public void leaseCleanupRejectsReentryAndRecoversAfterException() {
        var scope=new LeaseScope<Object>();Object owner=new Object(),next=new Object();
        scope.acquire(owner,()->{});
        assertThrows(IllegalStateException.class,()->scope.release(owner,()->{
            assertFalse(scope.release(owner,()->fail("duplicate")));
            assertFalse(scope.acquire(next,()->fail("overlap")));
            throw new IllegalStateException("cleanup failure");
        }));
        assertNull(scope.current());assertTrue(scope.acquire(next,()->{}));
    }
    @Test public void concurrentLeaseCloseRunsCleanupOnce() throws Exception {
        var scope=new LeaseScope<Object>();Object owner=new Object();scope.acquire(owner,()->{});
        var count=new java.util.concurrent.atomic.AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->scope.release(owner,count::incrementAndGet));
            var b=pool.submit(()->scope.release(owner,count::incrementAndGet));
            assertNotEquals(a.get(2,TimeUnit.SECONDS),b.get(2,TimeUnit.SECONDS));assertEquals(1,count.get());
        } finally {pool.shutdownNow();}
    }
    @Test public void closeCannotRunBeforeAcquisitionFinishes() throws Exception {
        var scope=new LeaseScope<Object>();Object owner=new Object();
        var started=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        var steps=new java.util.concurrent.CopyOnWriteArrayList<String>();var pool=Executors.newFixedThreadPool(2);
        try {
            var acquire=pool.submit(()->scope.acquire(owner,()->{
                steps.add("start");started.countDown();
                try {assertTrue(proceed.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                steps.add("acquired");
            }));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var close=pool.submit(()->scope.release(owner,()->steps.add("released")));
            proceed.countDown();assertTrue(acquire.get(2,TimeUnit.SECONDS));assertTrue(close.get(2,TimeUnit.SECONDS));
            assertEquals(List.of("start","acquired","released"),steps);
        } finally {proceed.countDown();pool.shutdownNow();}
    }
    @Test public void failedPartialAcquisitionRemainsOwnedUntilExplicitCleanup() {
        var scope=new LeaseScope<Object>();Object owner=new Object();
        assertThrows(IllegalStateException.class,()->scope.acquire(owner,()->{throw new IllegalStateException();}));
        assertSame(owner,scope.current());assertTrue(scope.release(owner,()->{}));assertNull(scope.current());
    }
    @Test public void commitExchangePairsBothSidesThroughReplaceableSignals() throws Exception {
        var committed=new CountDownLatch(1);var confirm=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var pool=Executors.newSingleThreadExecutor();
        try {
            var phone=pool.submit(()->CommitExchange.receiver(()->{calls.add("durable-phone");return "receipt";},
                ()->{calls.add("COMMITTED");committed.countDown();},
                ()->{assertTrue(confirm.await(2,TimeUnit.SECONDS));calls.add("confirm-received");},
                ()->{calls.add("FINISHED");finished.countDown();}));
            CommitExchange.sender(()->assertTrue(committed.await(2,TimeUnit.SECONDS)),
                ()->calls.add("durable-watch"),()->{calls.add("CONFIRM");confirm.countDown();},
                ()->assertTrue(finished.await(2,TimeUnit.SECONDS)));
            assertEquals("receipt",phone.get(2,TimeUnit.SECONDS));
            assertEquals(List.of("durable-phone","COMMITTED","durable-watch","CONFIRM","confirm-received","FINISHED"),calls);
        } finally {pool.shutdownNow();}
    }
    @Test public void everySenderFailureStopsAllLaterHandshakeSteps() {
        for(int failAt=0;failAt<4;failAt++) {
            final int target=failAt;var steps=new java.util.ArrayList<Integer>();
            CommitExchange.Step[] run=new CommitExchange.Step[4];
            for(int i=0;i<4;i++){final int index=i;run[i]=()->{steps.add(index);if(index==target)throw new java.io.IOException("injected");};}
            assertThrows(java.io.IOException.class,()->CommitExchange.sender(run[0],run[1],run[2],run[3]));
            assertEquals(target+1,steps.size());
        }
    }
    @Test public void durableReceiveFailureCannotSendCommitted() {
        var sent=new java.util.ArrayList<String>();
        assertThrows(java.io.IOException.class,()->CommitExchange.receiver(()->{throw new java.io.IOException("disk");},
            ()->sent.add("COMMITTED"),()->sent.add("CONFIRM"),()->sent.add("FINISHED")));
        assertTrue(sent.isEmpty());
    }
    @Test public void lostConfirmLeavesPhoneDataButCannotClaimFinished() {
        var saved=new java.util.concurrent.atomic.AtomicBoolean();var sent=new java.util.ArrayList<String>();
        assertThrows(TimeoutException.class,()->CommitExchange.receiver(()->{saved.set(true);return "saved";},
            ()->sent.add("COMMITTED"),()->{throw new TimeoutException("CONFIRM");},()->sent.add("FINISHED")));
        assertTrue(saved.get());assertEquals(List.of("COMMITTED"),sent);
    }
    @Test public void lostFinishedPreservesWatchReceiptWithoutClaimingWholeExchangeSuccess() {
        var saved=new java.util.concurrent.atomic.AtomicBoolean();var sent=new java.util.ArrayList<String>();
        assertThrows(TimeoutException.class,()->CommitExchange.sender(()->{},()->saved.set(true),
            ()->sent.add("CONFIRM"),()->{throw new TimeoutException("FINISHED");}));
        assertTrue(saved.get());assertEquals(List.of("CONFIRM"),sent);
    }
    @Test public void cancelledCommitExchangeNeverPersistsOrSendsFurtherWork() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(InterruptedException.class,()->CommitExchange.sender(()->{throw new InterruptedException();},
            calls::incrementAndGet,calls::incrementAndGet,calls::incrementAndGet));
        assertEquals(0,calls.get());
    }
    @Test public void moduleContractInventoryIsImmutableAndUsesActualConstants() {
        var versions=ModuleVersions.contracts();assertEquals(10,versions.size());
        assertEquals(Integer.valueOf(SummaryStore.CONTRACT_VERSION),versions.get("summaryStore"));
        assertEquals(Integer.valueOf(LeaseScope.CONTRACT_VERSION),versions.get("executionLease"));
        assertEquals(Integer.valueOf(TransportPort.CONTRACT_VERSION),versions.get("transport"));
        assertEquals(Integer.valueOf(CommitExchange.CONTRACT_VERSION),versions.get("commitExchange"));
        assertThrows(UnsupportedOperationException.class,()->versions.put("transport",999));
    }
    @Test public void completedTransportResultCannotBypassExpiredDeadline() {
        assertThrows(TimeoutException.class,()->TransportAwait.get(CompletableFuture.completedFuture("ready"),100,10,()->100));
    }
    @Test public void resultCompletedDuringSuspensionIsRejectedOnResume() {
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(TimeoutException.class,()->TransportAwait.get(CompletableFuture.completedFuture("late"),100,10,
                ()->reads.getAndIncrement()==0?90:150));
        assertEquals(2,reads.get());
    }
    @Test public void failedAndCancelledTransportNeverBecomeSuccess() {
        var failed=new CompletableFuture<String>();failed.completeExceptionally(new java.io.IOException("offline"));
        assertThrows(ExecutionException.class,()->TransportAwait.get(failed,100,10,()->1));
        var cancelled=new CompletableFuture<String>();cancelled.cancel(false);
        assertThrows(CancellationException.class,()->TransportAwait.get(cancelled,100,10,()->1));
    }
    @Test public void pendingTransportWaitIsBoundedWithoutCancellingLateCleanup() {
        var pending=new CompletableFuture<String>();
        assertThrows(TimeoutException.class,()->TransportAwait.get(pending,100,1,()->1));
        assertFalse(pending.isCancelled());
        // Resource ownership receives late success even though the protocol stopped waiting.
        var releases=new java.util.concurrent.atomic.AtomicInteger();
        var owner=new ResourceHandoff(e->fail(e.toString())); owner.close();
        pending.thenAccept(value->owner.accept(value,releases::incrementAndGet));
        pending.complete("channel");assertEquals(1,releases.get());
    }
    /** Controllable transport replacement; no device APIs and no automatic protocol replies. */
    private static class ScriptedTransport implements TransportPort {
        Messages messages; final CompletableFuture<Void> registered=new CompletableFuture<>();
        final CompletableFuture<Integer> sent=new CompletableFuture<>();
        public CompletableFuture<Void> registerMessages(Messages consumer){messages=consumer;return registered;}
        public CompletableFuture<Void> registerChannels(Channels consumer){return new CompletableFuture<>();}
        public CompletableFuture<List<String>> nodes(){return CompletableFuture.completedFuture(List.of("phone"));}
        public CompletableFuture<Integer> send(String node,String path,byte[] bytes){return sent;}
        public CompletableFuture<Channel> open(String node,String path){return new CompletableFuture<>();}
        public CompletableFuture<java.io.InputStream> input(Channel c){return new CompletableFuture<>();}
        public CompletableFuture<java.io.OutputStream> output(Channel c){return new CompletableFuture<>();}
        public void adopt(Channel c){} public void reject(Channel c){} public void close(){}
    }
    @Test public void delayedReplacementMessageCannotAdvanceCancelledAttempt() throws Exception {
        var fake=new ScriptedTransport();TransportPort port=fake;var state=sender();
        var accepted=new java.util.concurrent.atomic.AtomicInteger();
        var registration=port.registerMessages((node,path,bytes)->{
            if(state.accept(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),id,5)!=IGNORE) accepted.incrementAndGet();
        });
        assertFalse(registration.isDone());fake.registered.complete(null);
        TransportAwait.get(registration,100,10,()->1);
        state.close();fake.messages.received("phone","control","COMMITTED".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fake.messages.received("phone","control","FINISHED".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0,accepted.get());
    }
    @Test public void sendApiSuccessIsNotRemoteReceiptAndDeadlineRejectsLateFinish() throws Exception {
        var fake=new ScriptedTransport();TransportPort port=fake;var state=sender();
        var sent=port.send("phone","control",new byte[]{1});assertFalse(sent.isDone());
        fake.sent.complete(42);assertEquals(Integer.valueOf(42),TransportAwait.get(sent,100,10,()->1));
        assertEquals(COMMITTED,state.accept("COMMITTED",id,50)); // first actual protocol receipt
        assertEquals(IGNORE,state.accept("FINISHED",id,100));
        assertFalse(state.isLive(100));
    }
    @Test public void resourceCloseAndLateSuccessBothReleaseExactlyOnce() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var owner=new ResourceHandoff(e->fail(e.toString()));
        Object early=new Object(),late=new Object();
        owner.accept(early,calls::incrementAndGet);owner.accept(early,calls::incrementAndGet);
        assertEquals(0,calls.get());owner.close();owner.close();
        owner.accept(late,calls::incrementAndGet);owner.accept(late,calls::incrementAndGet);
        assertEquals(2,calls.get());
    }
    @Test public void cleanupFailureCannotPreventOtherResourcesOrLeakToOtherOwner() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var errors=new java.util.concurrent.atomic.AtomicInteger();
        var old=new ResourceHandoff(e->{errors.incrementAndGet();throw new IllegalStateException("journal failed");});
        var next=new ResourceHandoff(e->fail(e.toString()));
        old.accept(new Object(),()->{throw new IllegalStateException("release failed");});
        old.accept(new Object(),calls::incrementAndGet);
        next.accept(new Object(),calls::incrementAndGet);
        old.close();assertEquals(1,calls.get());assertEquals(1,errors.get());
        next.close();assertEquals(2,calls.get());
    }
    @Test public void resourceSuccessRacingCancellationNeverLeaks() throws Exception {
        for(int i=0;i<100;i++) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var owner=new ResourceHandoff(e->fail(e.toString()));
            var go=new CountDownLatch(1);
            Thread late=new Thread(()->{try{go.await();owner.accept(new Object(),calls::incrementAndGet);}catch(InterruptedException e){throw new AssertionError(e);}});
            late.start();go.countDown();owner.close();late.join(2000);
            assertFalse(late.isAlive());assertEquals(1,calls.get());
        }
    }
    @Test public void cleanupMayReenterOwnerWithoutLockingOrSkippingLateResource() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var owner=new ResourceHandoff(e->fail(e.toString()));
        owner.accept(new Object(),()->{owner.close();owner.accept(new Object(),calls::incrementAndGet);});
        owner.close();assertEquals(1,calls.get());
    }
    @Test public void unattendedFirstEcgIncludesConnectionTimeAndStopsAt80Seconds() {
        long deadline=EcgWaitBudget.initialDeadline(true,1000,21000);
        assertEquals(81000,deadline);
        assertFalse(EcgWaitBudget.mustStop(deadline,80999,false,false));
        assertTrue(EcgWaitBudget.mustStop(deadline,81000,false,false));
        // Invalid SDK rows and repeated observations never extend the same deadline.
        assertTrue(EcgWaitBudget.mustStop(deadline,161000,false,false));
    }
    @Test public void ecgNoContactRetryAndPostRemainBoundedButRealMeasurementIsSeparate() {
        long deadline=EcgWaitBudget.initialDeadline(false,1000,100000);
        assertEquals(180000,deadline);
        assertTrue(EcgWaitBudget.mustStop(deadline,180000,false,false));
        assertFalse(EcgWaitBudget.mustStop(deadline,180000,true,false));
        assertTrue(EcgWaitBudget.mustStop(deadline,180000,false,true));
        assertFalse(EcgWaitBudget.mustStop(0,180000,false,false)); // active CORE has no idle deadline
    }
    @Test public void oldPageDeadlineCannotStopNewBackgroundOwner() {
        assertFalse(SyncStopScope.mayStop(false,"old-page","new-sync"));
        assertFalse(SyncStopScope.mayStop(false,"old-page",null));
        assertFalse(SyncStopScope.mayStop(false,"",""));
        assertTrue(SyncStopScope.mayStop(false,"page","page"));
    }
    @Test public void explicitStopStillStopsAllWork() {
        assertTrue(SyncStopScope.mayStop(true,"page","background"));
        assertTrue(SyncStopScope.mayStop(true,"page",null));
    }
    private final TransferAttempt.Identity id=new TransferAttempt.Identity("phone","window","id","hash",42);
    private TransferAttempt sender() {
        var state=new TransferAttempt(true,false,100); state.bindSender(id,1); return state;
    }
    private TransferAttempt receiver(boolean repeat) {
        var state=new TransferAttempt(false,repeat,100);
        assertEquals(ACCEPT_HELLO,state.accept("HELLO",id,1)); return state;
    }
    @Test public void mismatchedIdentityNeverAcknowledges() {
        for(var foreign:List.of(
                new TransferAttempt.Identity("other","window","id","hash",42),
                new TransferAttempt.Identity("phone","old-window","id","hash",42),
                new TransferAttempt.Identity("phone","window","other","hash",42),
                new TransferAttempt.Identity("phone","window","id","other",42),
                new TransferAttempt.Identity("phone","window","id","hash",43))) {
            var state=sender();
            for(String type:List.of("READY","COMMITTED","FINISHED")) assertEquals(IGNORE,state.accept(type,foreign,2));
            assertEquals(COMMITTED,state.accept("COMMITTED",id,3));
        }
    }
    @Test public void duplicateCallbacksDeliverOnce() {
        var state=sender();
        for(String type:List.of("READY","COMMITTED","FINISHED")) {
            assertNotEquals(IGNORE,state.accept(type,id,2));
            assertEquals(IGNORE,state.accept(type,id,3));
        }
    }
    @Test public void confirmNeedsSuccessfulStoreHandoff() {
        var state=receiver(false);
        assertEquals(IGNORE,state.accept("CONFIRM",id,2));
        assertFalse(state.receiptSaved(2));
        assertTrue(state.acceptChannel("phone","/file/window/id","/file/",3));
        assertEquals(IGNORE,state.accept("CONFIRM",id,4));
        assertTrue(state.receiptSaved(5));
        assertEquals(CONFIRM,state.accept("CONFIRM",id,6));
        assertEquals(IGNORE,state.accept("CONFIRM",id,7));
    }
    @Test public void deadlineCannotBeRenewedByMessagesOrReceipt() {
        var state=receiver(true);
        assertEquals(REPEAT_HELLO,state.accept("HELLO",id,99));
        assertEquals(IGNORE,state.accept("HELLO",id,100));
        assertFalse(state.acceptChannel("phone","/file/window/id","/file/",100));
        assertFalse(state.receiptSaved(101));
        assertFalse(state.isLive(100));
    }
    @Test public void cancelledAttemptRejectsAllLateWork() {
        var state=receiver(true);
        assertTrue(state.close()); assertFalse(state.close());
        assertEquals(IGNORE,state.accept("HELLO",id,2));
        assertFalse(state.acceptChannel("phone","/file/window/id","/file/",2));
        assertFalse(state.receiptSaved(2));
        var sender=sender(); sender.close();
        for(String type:List.of("READY","COMMITTED","FINISHED")) assertEquals(IGNORE,sender.accept(type,id,2));
    }
    @Test public void repeatHelloPolicyOnlyAllowsRepliesBeforeChannel() {
        var state=receiver(true);
        assertEquals(REPEAT_HELLO,state.accept("HELLO",id,2));
        assertFalse(state.acceptChannel("other","/file/window/id","/file/",3));
        assertFalse(state.acceptChannel("phone","/file/old/id","/file/",3));
        assertTrue(state.acceptChannel("phone","/file/window/id","/file/",3));
        assertFalse(state.acceptChannel("phone","/file/window/id","/file/",4));
        assertEquals(IGNORE,state.accept("HELLO",id,4));
        assertEquals(IGNORE,receiver(false).accept("HELLO",id,2));
    }
    @Test public void roleAndBindingCannotBeChanged() {
        var sender=sender();
        assertThrows(IllegalStateException.class,() -> sender.bindSender(id,2));
        assertEquals(IGNORE,sender.accept("CONFIRM",id,2));
        assertFalse(sender.receiptSaved(2));
        var receiver=new TransferAttempt(false,false,100);
        assertEquals(IGNORE,receiver.accept("COMMITTED",id,2));
        assertThrows(IllegalStateException.class,() -> receiver.bindSender(id,2));
        assertEquals(ACCEPT_HELLO,receiver.accept("HELLO",id,3));
        assertEquals(IGNORE,receiver.accept("READY",id,4));
    }
    @Test public void concurrentFinishAndCancelHaveOneCleanupOwner() throws Exception {
        var state=sender(); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Boolean> close=() -> {start.await(); return state.close();};
            var a=pool.submit(close); var b=pool.submit(close); start.countDown();
            assertNotEquals(a.get(2,TimeUnit.SECONDS),b.get(2,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
}
