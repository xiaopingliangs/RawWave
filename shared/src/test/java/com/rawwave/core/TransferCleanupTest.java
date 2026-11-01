package com.rawwave.core;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class TransferCleanupTest {
    @Test public void channelFailureStillStopsWorker(){
        var stopped=new AtomicBoolean();var error=new IllegalStateException("channel");
        assertSame(error,TransferCleanup.run(()->{throw error;},()->stopped.set(true)));
        assertTrue(stopped.get());
    }
    @Test public void bothFailuresRemainVisible(){
        var a=new IllegalStateException("channel");var b=new IllegalStateException("worker");
        assertSame(a,TransferCleanup.run(()->{throw a;},()->{throw b;}));assertSame(b,a.getSuppressed()[0]);
    }
    @Test public void successHasNoFailure(){assertNull(TransferCleanup.run(()->{},()->{}));}
}
