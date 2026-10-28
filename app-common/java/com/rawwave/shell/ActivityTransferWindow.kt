package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.rawwave.core.CommitExchange
import com.rawwave.core.TransportPort
import com.rawwave.core.TransportAwait
import com.rawwave.core.ActivityArchive
import com.rawwave.core.ActivityTransferFiles
import com.rawwave.core.HelloRetryPolicy
import com.rawwave.core.WorkBudget
import com.rawwave.core.ReceiveStore
import com.rawwave.core.FileReceiveStore
import com.rawwave.core.SendStore
import com.rawwave.core.FileSendStore
import com.rawwave.core.TransferAttempt
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.concurrent.*

/** One bounded raw-activity transfer. This module never handles diagnostic reports or sensor collection. */
class ActivityTransferWindow(
    private val context: Context,
    private val app: ProbeApplication,
    private val update: (String) -> Unit,
    private val finished: (Boolean, String) -> Unit,
    private val initialNode: String? = null,
    private val initialHello: ByteArray? = null,
    private val trigger: String = "",
    private val receiveStore: ReceiveStore = FileReceiveStore(context.filesDir) { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    },
    private val transportPort: TransportPort? = null,
    private val sendStore: SendStore = FileSendStore(context.filesDir, ReceiveStore.Kind.RAW_ACTIVITY) { directory ->
        val fd=Os.open(directory.absolutePath,OsConstants.O_RDONLY,0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    },
    private val watchRole: Boolean = BuildConfig.DEVICE_ROLE == "watch",
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val environment: () -> Map<String,Any> = { ConnectionEnvironment.inspect(context) }
) {
    private val transport = transportPort ?: GoogleTransport(context) { event, detail -> log(event, mapOf("detail" to detail)) }
    private val executor = Executors.newSingleThreadExecutor()
    private val deadline = monotonicMs() + WorkBudget.TRANSFER_MS
    private val ready = CountDownLatch(1)
    private val committed = CountDownLatch(1)
    private val confirmed = CountDownLatch(1)
    private val attemptState = TransferAttempt(watchRole, true, deadline)
    private val closed: Boolean get() = attemptState.isClosed()
    @Volatile private var registered = false
    @Volatile private var peer = ""
    @Volatile private var id = ""
    @Volatile private var hash = ""
    @Volatile private var expected = 0L
    @Volatile private var senderWindow = if (watchRole) UUID.randomUUID().toString() else ""
    @Volatile private var channel: TransportPort.Channel? = null
    @Volatile private var failure = ""
    @Volatile private var lastStage = "NOT_STARTED"
    private var queuedHello: Pair<String,ByteArray>? = null
    private val watch = watchRole
    private val control = "/rawwave/activity/v1/control"
    private val hello = "/rawwave/activity/v1/hello"
    private val prefix = "/rawwave/activity/v1/file/"

    private fun log(event: String, extra: Map<String,Any> = emptyMap()) {
        app.journal?.event(event, mapOf("kind" to "RAW_ACTIVITY", "windowId" to windowId,
            "senderWindow" to senderWindow, "transferId" to id, "peer" to peer,
            "sha256" to hash, "bytesExpected" to expected,
            "remainingMs" to (deadline-monotonicMs()).coerceAtLeast(0)) + extra)
    }
    private val windowId = UUID.randomUUID().toString()
    private fun stage(event: String, text: String, extra: Map<String,Any> = emptyMap()) {
        checkLive(); lastStage = event; log(event,extra); update(text)
    }
    private fun checkLive() { check(attemptState.isLive(monotonicMs())) { "ACTIVITY_WINDOW_EXPIRED_OR_CANCELLED" } }
    private fun <T> await(task: CompletableFuture<T>): T {
        checkLive()
        val result = TransportAwait.get(task,deadline,10_000L) { monotonicMs() }
        checkLive(); return result
    }
    private fun waitSignal(signal: CountDownLatch,name: String,maxMs: Long = 15_000) {
        check(signal.await(minOf(maxMs,deadline-monotonicMs()).coerceAtLeast(1),TimeUnit.MILLISECONDS)) { "${name}_TIMEOUT" }
        checkLive(); check(failure.isEmpty()) { failure }
    }
    private fun message(type: String): CompletableFuture<Int> {
        val payload = JSONObject().put("protocol",1).put("kind","RAW_ACTIVITY").put("type",type)
            .put("id",id).put("hash",hash).put("bytes",expected).put("senderWindow",senderWindow)
            .put("trigger",trigger)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 4096) { "ACTIVITY_CONTROL_SIZE_LIMIT" }
        return transport.send(peer,if(type == "HELLO") hello else control,bytes)
    }

    /** Repeat only the idempotent HELLO inside this one bounded transfer attempt while the phone wakes. */
    private fun sendHelloUntilReady() {
        val readyDeadline = minOf(deadline,monotonicMs()+HelloRetryPolicy.READY_BUDGET_MS)
        var attempt = 0
        while(HelloRetryPolicy.shouldSend(ready.count == 0L,readyDeadline-monotonicMs())) {
            checkLive(); attempt++
            val sendBudget = minOf(10_000L,readyDeadline-monotonicMs(),
                deadline-monotonicMs()).coerceAtLeast(1L)
            TransportAwait.get(message("HELLO"),readyDeadline,sendBudget) { monotonicMs() }
            log("ACTIVITY_HELLO_TRANSMITTED",mapOf("attempt" to attempt))
            if(ready.count == 0L) break
            val wait = HelloRetryPolicy.waitSlice(readyDeadline-monotonicMs())
            if(wait > 0L) ready.await(wait,TimeUnit.MILLISECONDS)
        }
        check(ready.count == 0L) { "ACTIVITY_PHONE_READY_TIMEOUT" }
        checkLive(); check(failure.isEmpty()) { failure }
    }

    private val listener = TransportPort.Messages { node, path, bytes -> acceptControl(node, path, bytes) }
    fun offerHello(node: String,bytes: ByteArray) {
        if (watch || bytes.size > 4096) return
        synchronized(this) {
            if (closed) return
            if (!registered) { queuedHello = node to bytes.copyOf(); return }
        }
        acceptControl(node,hello,bytes)
    }
    private fun acceptControl(source: String,path: String,bytes: ByteArray) {
        if (closed || !registered || (path != control && path != hello) || bytes.size > 4096) return
        try {
            val payload = JSONObject(String(bytes,Charsets.UTF_8))
            check(payload.getInt("protocol") == 1 && payload.getString("kind") == "RAW_ACTIVITY") { "ACTIVITY_PROTOCOL_MISMATCH" }
            val incomingId=payload.getString("id"); val incomingHash=payload.getString("hash")
            val incomingBytes=payload.getLong("bytes"); val incomingWindow=payload.getString("senderWindow")
            ActivityTransferFiles.validate(incomingId,incomingHash,incomingBytes)
            ActivityTransferFiles.validate(incomingWindow,incomingHash,incomingBytes)
            checkLive()
            synchronized(this) {
                when(attemptState.accept(payload.getString("type"),
                    TransferAttempt.Identity(source,incomingWindow,incomingId,incomingHash,incomingBytes),
                    monotonicMs())) {
                    TransferAttempt.Action.ACCEPT_HELLO -> {
                        id=incomingId; hash=incomingHash; expected=incomingBytes; peer=source; senderWindow=incomingWindow
                        stage("ACTIVITY_HELLO_ACCEPTED","已找到手表，准备接收活动原始数据…")
                        executor.execute { attempt { await(message("READY")); log("ACTIVITY_READY_SENT") } }
                    }
                    TransferAttempt.Action.REPEAT_HELLO -> executor.execute { attempt {
                        await(message("READY")); log("ACTIVITY_READY_REPEATED")
                    } }
                    TransferAttempt.Action.READY -> ready.countDown()
                    TransferAttempt.Action.COMMITTED -> committed.countDown()
                    TransferAttempt.Action.CONFIRM, TransferAttempt.Action.FINISHED -> confirmed.countDown()
                    else -> {}
                }
            }
        } catch (error: Exception) {
            try { log("ACTIVITY_CONTROL_REJECTED",mapOf("error" to (error.message ?: error.javaClass.simpleName))) } catch (_: Exception) {}
        }
    }
    private val callback = object: TransportPort.Channels {
        override fun opened(opened: TransportPort.Channel) {
            if (watch) return
            synchronized(this@ActivityTransferWindow) {
                if (!registered || !attemptState.acceptChannel(opened.nodeId,opened.path,prefix,monotonicMs())) {
                    transport.reject(opened); return
                }
                channel=opened
                transport.adopt(opened)
            }
            executor.execute { attempt { receive(opened) } }
        }
        override fun closed(value: TransportPort.Channel,closeReason: Int,appSpecificErrorCode: Int) {
            if (!closed && value == channel) try { log("ACTIVITY_CHANNEL_CLOSED",mapOf("closeReason" to closeReason,"appError" to appSpecificErrorCode)) } catch (_: Exception) {}
        }
    }

    fun start() {
        executor.execute { attempt {
            stage("ACTIVITY_WINDOW_OPENED",if(watch) "正在整理待同步活动…" else "正在准备接收活动数据…")
            log("ACTIVITY_CONNECTION_ENVIRONMENT",environment())
            await(transport.registerChannels(callback))
            await(transport.registerMessages(listener))
            registered=true
            if(watch) send() else stage("ACTIVITY_RECEIVER_READY","手机已准备接收活动数据；本轮最多80秒。")
            if(!watch && initialNode != null && initialHello != null) offerHello(initialNode,initialHello)
            synchronized(this) { queuedHello.also { queuedHello=null } }?.let { offerHello(it.first,it.second) }
        } }
    }

    private fun send() {
        stage("ACTIVITY_ARCHIVE_PREPARING","正在打包并核验手表已保存的数据…")
        val prepared=sendStore.prepare(null)
        if(prepared == null) { complete(true,"没有待同步的活动数据。"); return }
        id=prepared.id(); hash=prepared.sha256(); expected=prepared.bytes()
        ActivityTransferFiles.validate(id,hash,expected)
        stage("ACTIVITY_DISCOVERY_STARTED","正在寻找已连接的手机…",mapOf("sessions" to prepared.sessions().size))
        val nodes=await(transport.nodes())
        check(nodes.size == 1) { "CONNECTED_NODE_COUNT_${nodes.size}" }
        peer=nodes.single()
        attemptState.bindSender(TransferAttempt.Identity(peer,senderWindow,id,hash,expected),monotonicMs())
        stage("ACTIVITY_HELLO_SENT","已找到手机，等待接收端准备…")
        sendHelloUntilReady()
        stage("ACTIVITY_CHANNEL_OPEN_REQUESTED","手机已回应，正在建立原始数据通道…")
        val open=transport.open(peer,prefix+senderWindow+"/"+id)
        val opened=await(open); channel=opened
        val output=await(transport.output(opened))
        stage("ACTIVITY_FILE_SEND_STARTED","正在发送 ${prepared.sessions().size} 次活动的原始数据…")
        sendStore.open(prepared).use { input ->
            val buffer=ByteArray(64*1024); var sent=0L; var nextUpdate=1024*1024L; var count: Int
            while(input.read(buffer).also { count=it } != -1) {
                checkLive(); output.write(buffer,0,count); sent+=count
                if(sent >= nextUpdate) { update("正在发送原始数据：${sent*100/expected}%"); nextUpdate+=1024*1024 }
            }
            check(sent == expected) { "ACTIVITY_SOURCE_LENGTH_CHANGED" }
        }
        output.flush()
        stage("ACTIVITY_FILE_BYTES_WRITTEN","数据已送出，等待手机逐文件校验；现在还不算成功。")
        CommitExchange.sender({
            waitSignal(committed,"ACTIVITY_PHONE_COMMIT",35_000)
        }, {
            stage("ACTIVITY_REMOTE_RECEIPT_RECEIVED","手机已保存，正在保存本地确认记录…")
            sendStore.acknowledge(prepared,SendStore.Confirmation(id,hash,expected,peer,System.currentTimeMillis()))
            stage("ACTIVITY_LOCAL_ACK_SAVED","本地确认已保存，正在通知手机…")
        }, {
            await(message("CONFIRM"))
            stage("ACTIVITY_CONFIRM_SENT","已发送确认，等待手机完成回应…")
        }, {
            waitSignal(confirmed,"ACTIVITY_PHONE_FINISHED")
        })
        stage("ACTIVITY_REMOTE_COMMIT_CONFIRMED","手机已完整保存并校验活动数据。")
        complete(true,"活动数据已同步到手机并完成校验。")
    }

    private fun receive(opened: TransportPort.Channel) {
        stage("ACTIVITY_FILE_RECEIVE_STARTED","正在接收活动原始数据…")
        val input=await(transport.input(opened))
        val verified = CommitExchange.receiver({
            val saved=receiveStore.commit(ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,id,hash,expected),input) {
                stage("ACTIVITY_ARCHIVE_VERIFYING","文件已保存，正在核对内部每个原始数据块…")
        }
            checkLive()
            stage("ACTIVITY_FILE_COMMITTED","已保存并校验 ${saved.sessionIds().size} 次活动，正在通知手表。",
                mapOf("sessions" to saved.sessionIds().size,"rawFiles" to saved.rawFiles(),"rawBytes" to saved.rawBytes()))
            check(attemptState.receiptSaved(monotonicMs())) { "ACTIVITY_RECEIPT_OUTSIDE_WINDOW" }
            saved
        }, { await(message("COMMITTED")); Unit }, { waitSignal(confirmed,"ACTIVITY_WATCH_CONFIRM",15_000) }, { await(message("FINISHED")); Unit })
        stage("ACTIVITY_WATCH_CONFIRM_RECEIVED","双端均已确认，正在停止同步。")
        complete(true,"已接收并校验 ${verified.sessionIds().size} 次活动的原始数据。")
    }

    private fun syncDirectory(folder: File) {
        check(folder.isDirectory) { "ACTIVITY_SYNC_TARGET_NOT_DIRECTORY" }
        val fd=Os.open(folder.absolutePath,OsConstants.O_RDONLY,0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun attempt(action: () -> Unit) {
        try { action() } catch(error: Exception) {
            if(!closed) {
                failure="${error.javaClass.simpleName}: ${error.message}"
                try { app.journal?.fault("ACTIVITY_TRANSFER_FAILED",error); log("ACTIVITY_TRANSFER_FAILED",mapOf("lastStage" to lastStage,"error" to failure)) } catch (_: Exception) {}
                val failedStep=if(lastStage == "ACTIVITY_ARCHIVE_PREPARING") "手表数据打包与校验（尚未发送）" else lastStage
                complete(false,"活动数据同步未完成，停在：$failedStep\n原因：${error.message?.take(160) ?: error.javaClass.simpleName}\n已采集数据仍保留在手表；本轮不再重试，下次打开原波可再试。")
            }
        }
    }
    private fun complete(success: Boolean,text: String) {
        if(!attemptState.close()) return
        cleanup(); finished(success,text)
    }
    fun cancel() {
        if(!attemptState.close()) return
        cleanup()
    }
    private fun cleanup() {
        registered=false
        ready.countDown(); committed.countDown(); confirmed.countDown()
        transport.close()
        executor.shutdownNow()
    }
}
