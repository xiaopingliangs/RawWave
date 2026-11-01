package com.rawwave.shell
import com.google.android.gms.tasks.TaskCompletionSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamOwnerTest {
    @Test fun lateStreamAfterCancelIsClosedOnce() {
        val owner=TransferResourceOwner {_,_->}
        val task=TaskCompletionSource<java.io.Closeable>()
        val done=CountDownLatch(1);val count=AtomicInteger()
        owner.pendingStream(task.task,"late-stream");owner.close();owner.close()
        task.setResult(java.io.Closeable {count.incrementAndGet();done.countDown()})
        assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(1,count.get())
    }
    @Test fun blockedStreamCloseDoesNotBlockOwnerCancellation() {
        val owner=TransferResourceOwner {_,_->}
        val task=TaskCompletionSource<java.io.Closeable>()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        owner.pendingStream(task.task,"blocked-stream")
        task.setResult(java.io.Closeable {entered.countDown();try {check(release.await(5,TimeUnit.SECONDS))} finally {done.countDown()}})
        try {owner.close();assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(1L,done.count)}
        finally {release.countDown()}
        assertTrue(done.await(5,TimeUnit.SECONDS))
    }
}
