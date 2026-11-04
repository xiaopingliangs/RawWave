package com.rawwave.shell

import com.rawwave.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Runs two production coordinators, not a copied handshake. Android services/radio excluded. */
class ActivityCoordinatorTest {
    @get:Rule val temp=TemporaryFolder()
    private class Wire(val loseFirstReady: Boolean) {
        var breakOutput=false
        var blockOutput=false
        var onWrite: (Int) -> Unit = {}
        val writeStarted=CountDownLatch(1)
        val releaseWrite=CountDownLatch(1)
        var beforeNodes: () -> Unit = {}
        var nodeResult: CompletableFuture<List<String>>? = null
        val nodesRequested=CountDownLatch(1)
        val readyCount=AtomicInteger()
        val opens=AtomicInteger()
        val input=PipedInputStream(1024*1024)
        val output=PipedOutputStream(input)
        val watch=Port("watch")
        val phone=Port("phone")
        inner class Handle(private val peer: String,private val path: String):TransportPort.Channel {
            override fun getNodeId()=peer
            override fun getPath()=path
        }
        inner class Port(val name: String):TransportPort {
            lateinit var messages:TransportPort.Messages
            lateinit var channels:TransportPort.Channels
            val closes=AtomicInteger()
            private fun other()=if(name=="watch")phone else watch
            override fun registerMessages(consumer:TransportPort.Messages):CompletableFuture<Void> {
                messages=consumer;return CompletableFuture.completedFuture(null)
            }
            override fun registerChannels(consumer:TransportPort.Channels):CompletableFuture<Void> {
                channels=consumer;return CompletableFuture.completedFuture(null)
            }
            override fun nodes():CompletableFuture<List<String>> {
                beforeNodes();nodesRequested.countDown()
                return nodeResult ?: CompletableFuture.completedFuture(listOf(other().name))
            }
            override fun send(node:String,path:String,bytes:ByteArray):CompletableFuture<Int> {
                val type=JSONObject(String(bytes,Charsets.UTF_8)).getString("type")
                val drop=type=="READY" && readyCount.incrementAndGet()==1 && loseFirstReady
                if(!drop)other().messages.received(name,path,bytes.copyOf())
                return CompletableFuture.completedFuture(bytes.size)
            }
            override fun open(node:String,path:String):CompletableFuture<TransportPort.Channel> {
                opens.incrementAndGet();other().channels.opened(Handle(name,path))
                return CompletableFuture.completedFuture(Handle(node,path))
            }
            override fun input(channel:TransportPort.Channel):CompletableFuture<InputStream> = CompletableFuture.completedFuture(this@Wire.input)
            override fun output(channel:TransportPort.Channel):CompletableFuture<OutputStream> = CompletableFuture.completedFuture(
                when {
                    breakOutput -> object:OutputStream(){
                        override fun write(value:Int){throw IOException("injected broken stream")}
                    }
                    blockOutput -> object:OutputStream(){
                        private fun stall() {
                            writeStarted.countDown()
                            try { releaseWrite.await(30,TimeUnit.SECONDS) }
                            catch (e:InterruptedException) { Thread.currentThread().interrupt(); throw IOException("injected stalled write interrupted",e) }
                            throw IOException("injected stalled write released")
                        }
                        override fun write(value:Int)=stall()
                        override fun write(buffer:ByteArray,offset:Int,length:Int)=stall()
                    }
                    else -> object:OutputStream(){
                        override fun write(value:Int){onWrite(1);this@Wire.output.write(value)}
                        override fun write(buffer:ByteArray,offset:Int,length:Int){onWrite(length);this@Wire.output.write(buffer,offset,length)}
                        override fun flush(){this@Wire.output.flush()}
                    }
                })
            override fun adopt(channel:TransportPort.Channel) {}
            override fun reject(channel:TransportPort.Channel) { error("Unexpected rejected channel") }
            override fun close() { closes.incrementAndGet() }
        }
        fun dispose(){output.close();input.close()}
    }
    @Test fun actualCoordinatorsTransferAndPersistBothReceipts()=runPair(false)
    @Test fun actualCoordinatorsRecoverDroppedFirstReady()=runPair(true)
    @Test fun reportCoordinatorsCompleteAfterSummarySaved()=runReport(false)
    @Test fun reportCoordinatorsContinueAfterSummaryFailure()=runReport(true)
    @Test fun activityBrokenStreamNeverCreatesSuccessReceipt()=runPair(false,true)
    @Test fun reportBrokenStreamNeverCreatesSuccessReceipt()=runReport(false,true)
    @Test fun activityExpiredDiscoveryCannotOpenChannel()=runStoppedDiscovery(false)
    @Test fun activityCancelledDiscoveryIgnoresLateResult()=runStoppedDiscovery(true)
    @Test fun reportExpiredDiscoveryCannotOpenChannel()=runStoppedDiscovery(false,true)
    @Test fun reportCancelledDiscoveryIgnoresLateResult()=runStoppedDiscovery(true,true)
    private fun runReport(summaryFails:Boolean,broken:Boolean=false) {
        val watchRoot=temp.newFolder();val phoneRoot=temp.newFolder()
        val sender=FileSendStore(watchRoot,ReceiveStore.Kind.DIAGNOSTIC){}
        val payload=sender.prepare { mapOf("evidence.txt" to "original evidence".toByteArray()) }!!
        val original=payload.file().readBytes()
        val wire=Wire(false).apply {breakOutput=broken};val ready=CountDownLatch(1)
        val watchDone=CompletableFuture<Pair<Boolean,String>>();val phoneDone=CompletableFuture<Pair<Boolean,String>>()
        val summaryCalls=AtomicInteger();val notices=CopyOnWriteArrayList<String>()
        val app=ProbeApplication();val clock={System.nanoTime()/1_000_000}
        val phone=TransferWindow(app,app,{if(it.contains("手机已准备好"))ready.countDown()},
            {ok,text->phoneDone.complete(ok to text)},receiveStore=FileReceiveStore(phoneRoot){},
            transportPort=wire.phone,sendStore=FileSendStore(phoneRoot,ReceiveStore.Kind.DIAGNOSTIC){},
            watchRole=false,monotonicMs=clock,environment={emptyMap()},sendSummary={error("Receiver must not send summary")})
        val watch=TransferWindow(app,app,{notices.add(it)},{ok,text->watchDone.complete(ok to text)},
            receiveStore=FileReceiveStore(watchRoot){},transportPort=wire.watch,sendStore=sender,
            watchRole=true,monotonicMs=clock,environment={emptyMap()},sendSummary={
                summaryCalls.incrementAndGet();assertEquals(0,wire.opens.get())
                if(summaryFails)throw IOException("injected summary failure")
            })
        try {
            phone.start();assertTrue(ready.await(5,TimeUnit.SECONDS));watch.start()
            val w=watchDone.get(10,TimeUnit.SECONDS)
            if(broken)wire.output.close() // disconnected peer sees EOF, not an infinite fake pipe
            val p=phoneDone.get(10,TimeUnit.SECONDS)
            if(broken) {
                assertFalse(w.second,w.first);assertFalse(p.second,p.first)
                assertFalse(File(payload.file().parentFile,"${payload.id()}.ack.json").exists())
                assertFalse(File(phoneRoot,"received/${payload.id()}.zip").exists())
                assertArrayEquals(original,payload.file().readBytes())
                assertEquals(1,wire.watch.closes.get());assertEquals(1,wire.phone.closes.get())
                return
            }
            assertTrue(w.second,w.first);assertTrue(p.second,p.first)
            assertEquals(1,summaryCalls.get())
            assertEquals(summaryFails,notices.any {it.contains("简要诊断未确认送达")})
            assertArrayEquals(original,File(phoneRoot,"received/${payload.id()}.zip").readBytes())
            assertArrayEquals(original,payload.file().readBytes())
            assertTrue(File(payload.file().parentFile,"${payload.id()}.ack.json").isFile)
            assertEquals(1,wire.opens.get());assertEquals(1,wire.watch.closes.get());assertEquals(1,wire.phone.closes.get())
        }finally{watch.cancel();phone.cancel();wire.dispose()}
    }
    private fun runPair(dropReady:Boolean,broken:Boolean=false) {
        val watchRoot=temp.newFolder();val phoneRoot=temp.newFolder()
        val store=ActivityStore(File(watchRoot,"activities")){}
        val session=store.start(store.createTemplate("联动",false),1000,1)
        session.append("CORE-1","HEART_RATE",listOf(mapOf("HEART_RATE" to 73,"sdkTimestamp" to 123L)))
        session.seal("DONE",2000)
        val sender=FileSendStore(watchRoot,ReceiveStore.Kind.RAW_ACTIVITY){}
        val payload=sender.prepare(null)!!
        val original=payload.file().readBytes()
        val wire=Wire(dropReady).apply {breakOutput=broken};val receiverReady=CountDownLatch(1)
        val watchDone=CompletableFuture<Pair<Boolean,String>>()
        val phoneDone=CompletableFuture<Pair<Boolean,String>>()
        val app=ProbeApplication()
        val clock={System.nanoTime()/1_000_000}
        val phone=ActivityTransferWindow(app,app,{if(it.contains("手机已准备接收"))receiverReady.countDown()},
            {ok,text->phoneDone.complete(ok to text)},receiveStore=FileReceiveStore(phoneRoot){},
            transportPort=wire.phone,sendStore=FileSendStore(phoneRoot,ReceiveStore.Kind.RAW_ACTIVITY){},
            watchRole=false,monotonicMs=clock,environment={emptyMap()})
        val watch=ActivityTransferWindow(app,app,{}, {ok,text->watchDone.complete(ok to text)},
            receiveStore=FileReceiveStore(watchRoot){},transportPort=wire.watch,sendStore=sender,
            watchRole=true,monotonicMs=clock,environment={emptyMap()})
        try {
            phone.start();assertTrue(receiverReady.await(5,TimeUnit.SECONDS));watch.start()
            val w=watchDone.get(10,TimeUnit.SECONDS)
            if(broken)wire.output.close()
            val p=phoneDone.get(10,TimeUnit.SECONDS)
            if(broken) {
                assertFalse(w.second,w.first);assertFalse(p.second,p.first)
                assertFalse(File(payload.file().parentFile,"${payload.id()}.ack.json").exists())
                assertFalse(File(phoneRoot,"activity-inbox/${payload.id()}.zip").exists())
                assertArrayEquals(original,payload.file().readBytes())
                assertEquals(1,wire.watch.closes.get());assertEquals(1,wire.phone.closes.get())
                return
            }
            assertTrue(w.second,w.first);assertTrue(p.second,p.first)
            assertArrayEquals(original,File(phoneRoot,"activity-inbox/${payload.id()}.zip").readBytes())
            assertArrayEquals(original,payload.file().readBytes())
            assertTrue(File(payload.file().parentFile,"${payload.id()}.ack.json").isFile)
            assertEquals(1,wire.opens.get());assertEquals(1,wire.watch.closes.get());assertEquals(1,wire.phone.closes.get())
            if(dropReady)assertTrue(wire.readyCount.get()>=2)
        } finally {watch.cancel();phone.cancel();wire.dispose()}
    }
    @Test fun activityLargeSlowTransferUsesSizeAwareBudget() {
        val watchRoot=temp.newFolder();val phoneRoot=temp.newFolder()
        val store=ActivityStore(File(watchRoot,"activities")){}
        val session=store.start(store.createTemplate("大包",false),1000,1)
        val random=java.util.Random(7)
        val encoder=java.util.Base64.getEncoder()
        session.append("CORE-1","HEART_RATE",(0 until 10_000).map { index ->
            // Incompressible padding keeps the test archive large after ZIP compression.
            val raw=ByteArray(512).also(random::nextBytes)
            mapOf("HEART_RATE" to 70,"sdkTimestamp" to index.toLong(),"pad" to encoder.encodeToString(raw))
        })
        session.seal("DONE",2000)
        val sender=FileSendStore(watchRoot,ReceiveStore.Kind.RAW_ACTIVITY){}
        val payload=sender.prepare(null)!!
        assertTrue("archive should exceed the old fixed budget",payload.bytes() > 4_000_000L)
        val original=payload.file().readBytes()
        val now=java.util.concurrent.atomic.AtomicLong(1)
        // Two seconds of simulated link time per 64 KiB written. The old 75-second
        // ceiling would expire before this archive finished.
        val wire=Wire(false).apply { onWrite={ bytes -> now.addAndGet(bytes.toLong()/32L) } }
        val receiverReady=CountDownLatch(1)
        val watchDone=CompletableFuture<Pair<Boolean,String>>()
        val phoneDone=CompletableFuture<Pair<Boolean,String>>()
        val app=ProbeApplication()
        val phone=ActivityTransferWindow(app,app,{if(it.contains("手机已准备接收"))receiverReady.countDown()},
            {ok,text->phoneDone.complete(ok to text)},receiveStore=FileReceiveStore(phoneRoot){},
            transportPort=wire.phone,sendStore=FileSendStore(phoneRoot,ReceiveStore.Kind.RAW_ACTIVITY){},
            watchRole=false,monotonicMs={now.get()},environment={emptyMap()})
        val watch=ActivityTransferWindow(app,app,{}, {ok,text->watchDone.complete(ok to text)},
            receiveStore=FileReceiveStore(watchRoot){},transportPort=wire.watch,sendStore=sender,
            watchRole=true,monotonicMs={now.get()},environment={emptyMap()})
        try {
            phone.start();assertTrue(receiverReady.await(5,TimeUnit.SECONDS));watch.start()
            val w=watchDone.get(30,TimeUnit.SECONDS);val p=phoneDone.get(30,TimeUnit.SECONDS)
            assertTrue(w.second,w.first);assertTrue(p.second,p.first)
            assertTrue("simulated transfer time must exceed the old 75s budget",now.get() > WorkBudget.TRANSFER_MS)
            assertArrayEquals(original,File(phoneRoot,"activity-inbox/${payload.id()}.zip").readBytes())
            assertArrayEquals(original,payload.file().readBytes())
            assertTrue(File(payload.file().parentFile,"${payload.id()}.ack.json").isFile)
        } finally {watch.cancel();phone.cancel();wire.dispose()}
    }
    @Test fun activityStalledWriteAbortsAtStallDeadline() {
        val watchRoot=temp.newFolder();val phoneRoot=temp.newFolder()
        val store=ActivityStore(File(watchRoot,"activities")){}
        val session=store.start(store.createTemplate("阻塞",false),1000,1)
        session.append("CORE-1","HEART_RATE",listOf(mapOf("HEART_RATE" to 73,"sdkTimestamp" to 123L)))
        session.seal("DONE",2000)
        val sender=FileSendStore(watchRoot,ReceiveStore.Kind.RAW_ACTIVITY){}
        val payload=sender.prepare(null)!!
        val now=java.util.concurrent.atomic.AtomicLong(1)
        val wire=Wire(false).apply { blockOutput=true }
        val receiverReady=CountDownLatch(1)
        val watchDone=CompletableFuture<Pair<Boolean,String>>()
        val phoneDone=CompletableFuture<Pair<Boolean,String>>()
        val app=ProbeApplication()
        val phone=ActivityTransferWindow(app,app,{if(it.contains("手机已准备接收"))receiverReady.countDown()},
            {ok,text->phoneDone.complete(ok to text)},receiveStore=FileReceiveStore(phoneRoot){},
            transportPort=wire.phone,sendStore=FileSendStore(phoneRoot,ReceiveStore.Kind.RAW_ACTIVITY){},
            watchRole=false,monotonicMs={now.get()},environment={emptyMap()})
        val watch=ActivityTransferWindow(app,app,{}, {ok,text->watchDone.complete(ok to text)},
            receiveStore=FileReceiveStore(watchRoot){},transportPort=wire.watch,sendStore=sender,
            watchRole=true,monotonicMs={now.get()},environment={emptyMap()})
        try {
            phone.start();assertTrue(receiverReady.await(5,TimeUnit.SECONDS));watch.start()
            assertTrue(wire.writeStarted.await(5,TimeUnit.SECONDS))
            now.addAndGet(ActivityTransferBudget.STALL_MS+1)
            val w=watchDone.get(5,TimeUnit.SECONDS)
            assertFalse(w.second,w.first)
            assertTrue(w.second.contains("没有进展"))
            assertFalse(File(payload.file().parentFile,"${payload.id()}.ack.json").exists())
            assertEquals(1,wire.watch.closes.get())
        } finally {wire.releaseWrite.countDown();watch.cancel();phone.cancel();wire.dispose()}
    }
    private fun runStoppedDiscovery(cancel:Boolean,report:Boolean=false) {
        val root=temp.newFolder();val store=ActivityStore(File(root,"activities")){}
        val session=store.start(store.createTemplate("截止取消",false),1000,1)
        session.append("CORE-1","HEART_RATE",listOf(mapOf("sdkTimestamp" to 1,"HEART_RATE" to 70)))
        session.seal("DONE",2000)
        val sender=FileSendStore(root,if(report)ReceiveStore.Kind.DIAGNOSTIC else ReceiveStore.Kind.RAW_ACTIVITY){}
        val payload=if(report)sender.prepare {mapOf("evidence.txt" to "original".toByteArray())}!! else sender.prepare(null)!!
        val original=payload.file().readBytes()
        val now=java.util.concurrent.atomic.AtomicLong(1)
        val late=CompletableFuture<List<String>>()
        val wire=Wire(false).apply {
            if(cancel)nodeResult=late
            else beforeNodes={now.addAndGet(
                if(report) WorkBudget.TRANSFER_MS+1 else ActivityTransferBudget.INITIAL_MS+1)}
        }
        val done=CompletableFuture<Pair<Boolean,String>>();val app=ProbeApplication()
        val start:()->Unit;val cancelWindow:()->Unit
        if(report) {
            val watch=TransferWindow(app,app,{}, {ok,text->done.complete(ok to text)},
                receiveStore=FileReceiveStore(root){},transportPort=wire.watch,sendStore=sender,
                watchRole=true,monotonicMs={now.get()},environment={emptyMap()},sendSummary={})
            start={watch.start()};cancelWindow={watch.cancel()}
        } else {
            val watch=ActivityTransferWindow(app,app,{}, {ok,text->done.complete(ok to text)},
            receiveStore=FileReceiveStore(root){},transportPort=wire.watch,sendStore=sender,
            watchRole=true,monotonicMs={now.get()},environment={emptyMap()})
            start={watch.start()};cancelWindow={watch.cancel()}
        }
        try {
            start();assertTrue(wire.nodesRequested.await(5,TimeUnit.SECONDS))
            if(cancel) {cancelWindow();cancelWindow();late.complete(listOf("phone"));assertFalse(done.isDone)}
            else {val result=done.get(5,TimeUnit.SECONDS);assertFalse(result.second,result.first)}
            assertEquals(0,wire.opens.get());assertEquals(1,wire.watch.closes.get())
            assertFalse(File(payload.file().parentFile,"${payload.id()}.ack.json").exists())
            assertArrayEquals(original,payload.file().readBytes())
        }finally{cancelWindow();wire.dispose()}
    }
}
