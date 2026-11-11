package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.rawwave.core.CommitExchange
import com.rawwave.core.TransportPort
import com.rawwave.core.TransportAwait
import com.rawwave.core.ActivityArchive
import com.rawwave.core.ActivityTransferBudget
import com.rawwave.core.ActivityTransferFiles
import com.rawwave.core.HelloRetryPolicy
import com.rawwave.core.ReceiveStore
import com.rawwave.core.FileReceiveStore
import com.rawwave.core.SendStore
import com.rawwave.core.FileSendStore
import com.rawwave.core.TransferAttempt
import com.rawwave.core.TransferRateMonitor
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
    /** Packaging gets its own window; it is not the 80-second transfer window. */
    private val prepareBudgetMs: Long = ActivityTransferBudget.INITIAL_MS,
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
    private val environment: () -> Map<String,Any> = { ConnectionEnvironment.inspect(context) },
    /** Called once when packaging or HELLO reveals the exact archive size. */
    private val onArchiveSizeKnown: (Long) -> Unit = {}
) : ActivityTransferHandle {
    private val transport = transportPort ?: GoogleTransport(context) { event, detail -> log(event, mapOf("detail" to detail)) }
    private val executor = Executors.newSingleThreadExecutor()
    private val prepareDeadline = monotonicMs() + prepareBudgetMs
    @Volatile private var deadline = prepareDeadline
    /** Bounds discovery/channel-open separately from the size-aware send budget. */
    @Volatile private var handshakeDeadline = 0L
    private val ready = CountDownLatch(1)
    private val committed = CountDownLatch(1)
    private val confirmed = CountDownLatch(1)
    private val attemptState = TransferAttempt(watchRole, true, prepareDeadline)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var watchdogTask: ScheduledFuture<*>? = null
    @Volatile private var lastProgressMs = monotonicMs()
    @Volatile private var transferActive = false
    /** Hard file-byte window; commit verification is a separate phase. */
    @Volatile private var fileDeadline = 0L
    /** File-byte rate stability only; packaging, handshake and verification are separate phases. */
    private val rateMonitor = TransferRateMonitor()
    @Volatile private var rateState = "STABLE"
    private val closed: Boolean get() = attemptState.isClosed()
    @Volatile private var registered = false
    @Volatile private var peer = ""
    @Volatile private var id = ""
    @Volatile private var hash = ""
    @Volatile private var expected = 0L
    /** Receiver's durable prefix for this archive; returned in READY and skipped by the sender. */
    @Volatile private var resumeOffset = 0L
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
    /** Only ever moves the ceiling later; packaging/handshake/send use separate budgets. */
    private fun extendDeadline(value: Long) {
        synchronized(this) {
            if (value > deadline) {
                deadline = value
                attemptState.extendDeadline(value)
            }
        }
    }
    /**
     * The archive size is now exact. Small archives keep the short initial window; larger ones
     * get one size-based extension. Discovery/channel-open is separately bounded so a stalled
     * handshake cannot consume the send budget.
     */
    private fun noteSizeKnown(bytes: Long) {
        try { onArchiveSizeKnown(bytes) } catch (_: Exception) {}
        extendDeadline(ActivityTransferBudget.sizeAwareDeadline(monotonicMs(), deadline, bytes))
        handshakeDeadline = monotonicMs() + ActivityTransferBudget.HANDSHAKE_MS
    }
    /**
     * The channel can now carry file bytes. The rate window starts here, not during packaging
     * or handshake, so a stable transfer never starts the fluctuation grace countdown.
     */
    private fun beginFileBytes(now: Long) {
        lastProgressMs = now
        transferActive = true
        fileDeadline = minOf(now + ActivityTransferBudget.MAX_TRANSFER_MS, deadline)
        rateMonitor.reset(now)
        rateState = "STABLE"
    }
    @Synchronized private fun startWatchdog() {
        if (watchdogTask != null || watchdog.isShutdown || attemptState.isClosed()) return
        try {
            watchdogTask = watchdog.scheduleWithFixedDelay({
                try {
                    if (attemptState.isClosed()) return@scheduleWithFixedDelay
                    val now = monotonicMs()
                    val handshakeExpired = handshakeDeadline > 0L && !transferActive && now >= handshakeDeadline
                    val fileExpired = transferActive && fileDeadline > 0L && now >= fileDeadline
                    val stalled = transferActive && now - lastProgressMs > ActivityTransferBudget.STALL_MS
                    val rateVerdict = if (transferActive) rateMonitor.check(now)
                        else TransferRateMonitor.Verdict.STABLE
                    when {
                        handshakeExpired -> abort("ACTIVITY_HANDSHAKE_TIMEOUT")
                        fileExpired -> abort("ACTIVITY_WINDOW_EXPIRED")
                        stalled -> abort("ACTIVITY_TRANSFER_STALLED")
                        rateVerdict == TransferRateMonitor.Verdict.EXPIRED -> {
                            try { log("ACTIVITY_TRANSFER_RATE_EXPIRED", rateMonitor.evidence(now)) } catch (_: Exception) {}
                            abort("ACTIVITY_TRANSFER_RATE_UNSTABLE")
                        }
                        now >= deadline -> abort("ACTIVITY_WINDOW_EXPIRED")
                    }
                    if (transferActive) {
                        if (rateVerdict == TransferRateMonitor.Verdict.UNSTABLE && rateState != "UNSTABLE") {
                            rateState = "UNSTABLE"
                            try { log("ACTIVITY_TRANSFER_RATE_UNSTABLE", rateMonitor.evidence(now)) } catch (_: Exception) {}
                        } else if (rateVerdict == TransferRateMonitor.Verdict.STABLE && rateState == "UNSTABLE") {
                            rateState = "STABLE"
                            try { log("ACTIVITY_TRANSFER_RATE_RECOVERED", rateMonitor.evidence(now)) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }, 1_000L, 1_000L, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {}
    }
    /** One watchdog winner closes the transport so a blocked write cannot outlive its budget. */
    private fun abort(reason: String) {
        if (!attemptState.close()) return
        transferActive = false
        failure = reason
        try { log("ACTIVITY_TRANSFER_ABORTED", mapOf("reason" to reason, "lastStage" to lastStage)) } catch (_: Exception) {}
        val cleanupFailure = cleanup()
        val why = when {
            reason == "ACTIVITY_TRANSFER_STALLED" ->
                "传输连续 ${ActivityTransferBudget.STALL_MS / 1000} 秒没有进展（手机或连接可能已断开）"
            reason == "ACTIVITY_HANDSHAKE_TIMEOUT" -> "等待手机连接或建立数据通道的时间已用完"
            reason == "ACTIVITY_WINDOW_EXPIRED" && lastStage == "ACTIVITY_ARCHIVE_PREPARING" ->
                "手表数据打包超过本次上限（${prepareBudgetMs / 60_000} 分钟）"
            reason == "ACTIVITY_TRANSFER_RATE_UNSTABLE" ->
                "文件传输速率持续大幅波动，无法确认本轮能稳定完成"
            else -> "本轮同步时间已用完"
        }
        finished(false, if (cleanupFailure == null)
            "活动数据同步未完成，停在：$lastStage\n原因：$why\n已采集数据仍保留在手表；若手表自动重试仍未完成，下次打开原波可再试。"
        else "同步资源清理异常；数据保留，请查看问题报告。${cleanupFailure.javaClass.simpleName}")
    }
    private fun <T> await(task: CompletableFuture<T>): T {
        checkLive()
        val cap = if (handshakeDeadline > 0L) minOf(deadline, handshakeDeadline) else deadline
        val result = TransportAwait.get(task, cap, 10_000L) { monotonicMs() }
        checkLive()
        if (handshakeDeadline > 0L && monotonicMs() >= handshakeDeadline)
            throw TimeoutException("ACTIVITY_HANDSHAKE_TIMEOUT")
        return result
    }
    private fun waitSignal(signal: CountDownLatch,name: String,maxMs: Long = 15_000) {
        check(signal.await(minOf(maxMs,deadline-monotonicMs()).coerceAtLeast(1),TimeUnit.MILLISECONDS)) { "${name}_TIMEOUT" }
        checkLive(); check(failure.isEmpty()) { failure }
    }
    private fun message(type: String, resumeFrom: Long = 0L): CompletableFuture<Int> {
        val payload = JSONObject().put("protocol",1).put("kind","RAW_ACTIVITY").put("type",type)
            .put("id",id).put("hash",hash).put("bytes",expected).put("senderWindow",senderWindow)
            .put("trigger",trigger)
        if (resumeFrom > 0L) payload.put("offset",resumeFrom)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 4096) { "ACTIVITY_CONTROL_SIZE_LIMIT" }
        return transport.send(peer,if(type == "HELLO") hello else control,bytes)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            checkLive()
            val skipped = input.skip(remaining)
            if (skipped > 0L) { remaining -= skipped; continue }
            if (input.read() < 0) throw EOFException("ACTIVITY_RESUME_SOURCE_TRUNCATED")
            remaining--
        }
    }

    /** Repeat only the idempotent HELLO inside this one bounded transfer attempt while the phone wakes. */
    private fun sendHelloUntilReady() {
        val now = monotonicMs()
        val handshakeCap = if (handshakeDeadline > 0L) minOf(deadline, handshakeDeadline) else deadline
        val readyDeadline = minOf(handshakeCap, now + HelloRetryPolicy.READY_BUDGET_MS)
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
        if (handshakeDeadline > 0L && monotonicMs() >= handshakeDeadline) return
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
                        // HELLO names the exact archive size. Small archives keep the initial
                        // window; only a genuinely large one gets a size-based extension.
                        noteSizeKnown(incomingBytes)
                        resumeOffset = try {
                            receiveStore.resumeOffset(ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,
                                incomingId,incomingHash,incomingBytes))
                        } catch (_: Exception) { 0L }
                        log("ACTIVITY_RESUME_OFFSET",mapOf("offset" to resumeOffset))
                        stage("ACTIVITY_HELLO_ACCEPTED","已找到手表，准备接收活动原始数据…")
                        executor.execute { attempt { await(message("READY",resumeOffset)); log("ACTIVITY_READY_SENT") } }
                    }
                    TransferAttempt.Action.REPEAT_HELLO -> executor.execute { attempt {
                        await(message("READY",resumeOffset)); log("ACTIVITY_READY_REPEATED")
                    } }
                    TransferAttempt.Action.READY -> {
                        resumeOffset = payload.optLong("offset",0L).coerceAtLeast(0L)
                        ready.countDown()
                    }
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
                val now = monotonicMs()
                if (!registered || (handshakeDeadline > 0L && now >= handshakeDeadline) ||
                    !attemptState.acceptChannel(opened.nodeId,opened.path,prefix,now)) {
                    transport.reject(opened); return
                }
                channel=opened
                transport.adopt(opened)
                // The archive size is known from HELLO; the byte-transfer clock starts only
                // now that the channel can carry data. The size-aware deadline already covers
                // the send and commit phases, so opening the channel does not add another slice.
                handshakeDeadline=0L
                beginFileBytes(now)
            }
            executor.execute { attempt { receive(opened) } }
        }
        override fun closed(value: TransportPort.Channel,closeReason: Int,appSpecificErrorCode: Int) {
            if (!closed && value == channel) try { log("ACTIVITY_CHANNEL_CLOSED",mapOf("closeReason" to closeReason,"appError" to appSpecificErrorCode)) } catch (_: Exception) {}
        }
    }

    override fun start() {
        if (attemptState.isClosed()) return
        startWatchdog()
        try {
            executor.execute { attempt {
                stage("ACTIVITY_WINDOW_OPENED",if(watch) "正在整理待同步活动…" else "正在准备接收活动数据…")
                log("ACTIVITY_CONNECTION_ENVIRONMENT",environment())
                await(transport.registerChannels(callback))
                await(transport.registerMessages(listener))
                registered=true
                if(watch) send() else stage("ACTIVITY_RECEIVER_READY","手机已准备接收活动数据；完成即停止，失败后手表会自动重试一次。")
                if(!watch && initialNode != null && initialHello != null) offerHello(initialNode,initialHello)
                synchronized(this) { queuedHello.also { queuedHello=null } }?.let { offerHello(it.first,it.second) }
            } }
        } catch (_: RejectedExecutionException) {}
    }

    private fun send() {
        stage("ACTIVITY_ARCHIVE_PREPARING","正在打包并核验手表已保存的数据…",
            mapOf("prepareBudgetMs" to prepareBudgetMs))
        val prepared=sendStore.prepare(null)
        if(prepared == null) { complete(true,"没有待同步的活动数据。"); return }
        id=prepared.id(); hash=prepared.sha256(); expected=prepared.bytes()
        ActivityTransferFiles.validate(id,hash,expected)
        // Packaging is finished. Small archives keep the initial window; a large one gets one
        // size-based extension. Discovery/channel-open remains separately bounded.
        noteSizeKnown(expected)
        stage("ACTIVITY_DISCOVERY_STARTED","正在寻找已连接的手机…",mapOf("sessions" to prepared.sessions().size))
        val nodes=await(transport.nodes())
        check(nodes.size == 1) { "CONNECTED_NODE_COUNT_${nodes.size}" }
        peer=nodes.single()
        attemptState.bindSender(TransferAttempt.Identity(peer,senderWindow,id,hash,expected),monotonicMs())
        stage("ACTIVITY_HELLO_SENT","已找到手机，等待接收端准备…")
        sendHelloUntilReady()
        // READY ends HELLO retries; opening the channel stays inside the same handshake budget.
        stage("ACTIVITY_CHANNEL_OPEN_REQUESTED","手机已回应，正在建立原始数据通道…")
        val open=transport.open(peer,prefix+senderWindow+"/"+id)
        val opened=await(open); channel=opened
        val output=await(transport.output(opened))
        // The size-aware deadline already covers send and commit; channel-open does not add
        // another slice. From here only the stall watchdog and the absolute ceiling apply.
        handshakeDeadline=0L
        beginFileBytes(monotonicMs())
        stage("ACTIVITY_FILE_SEND_STARTED","正在发送 ${prepared.sessions().size} 次活动的原始数据…")
        val startAt = resumeOffset.coerceIn(0L, expected)
        sendStore.open(prepared).use { input ->
            if (startAt > 0L) {
                skipFully(input, startAt)
                val resumedAt = monotonicMs()
                lastProgressMs = resumedAt
                // The rate window must start at the resumed byte count, not at zero.
                rateMonitor.reset(resumedAt); rateMonitor.progress(resumedAt, startAt)
                stage("ACTIVITY_FILE_RESUMED","从上次中断处继续：${startAt*100/expected}%",
                    mapOf("offset" to startAt))
            }
            val buffer=ByteArray(64*1024); var sent=startAt
            var nextUpdate=((startAt / (1024*1024)) + 1) * 1024*1024; var count: Int
            while(input.read(buffer).also { count=it } != -1) {
                checkLive(); output.write(buffer,0,count); val now=monotonicMs()
                lastProgressMs=now; sent+=count; rateMonitor.progress(now,sent)
                if(sent >= nextUpdate) { update("正在发送原始数据：${sent*100/expected}%"); nextUpdate+=1024*1024 }
            }
            check(sent == expected) { "ACTIVITY_SOURCE_LENGTH_CHANGED" }
        }
        output.flush()
        transferActive=false
        stage("ACTIVITY_FILE_BYTES_WRITTEN","数据已送出，等待手机逐文件校验；现在还不算成功。")
        CommitExchange.sender({
            waitSignal(committed,"ACTIVITY_PHONE_COMMIT",ActivityTransferBudget.commitMs(expected))
        }, {
            stage("ACTIVITY_REMOTE_RECEIPT_RECEIVED","手机已保存，正在保存本地确认记录…")
            sendStore.acknowledge(prepared,SendStore.Confirmation(id,hash,expected,peer,System.currentTimeMillis()))
            // The derived transport ZIP is deleted only after the ack is durable. Raw session
            // files and the ack receipts remain; a delete failure never turns success into failure.
            try { log("ACTIVITY_ZIP_DELETE_RESULT",mapOf("deleted" to !prepared.file().exists(),
                "bytes" to prepared.bytes(),"batchId" to prepared.id())) } catch (_: Exception) {}
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

    /** Bounded progress signal for the receive path; never changes the bytes. */
    private inner class ProgressInput(source: InputStream, startAt: Long = 0L) : FilterInputStream(source) {
        private var received=startAt
        private var nextUpdate=((startAt / (1024*1024)) + 1) * 1024*1024
        private fun record(count: Int) {
            if(count<=0) return
            val now=monotonicMs()
            lastProgressMs=now
            received+=count
            rateMonitor.progress(now,received)
            if(received>=nextUpdate) {
                val percent=(received*100/expected).coerceIn(0,100)
                try { update("正在接收原始数据：$percent%") } catch (_: Exception) {}
                nextUpdate+=1024*1024
            }
        }
        override fun read(): Int { val value=super.read(); if(value>=0) record(1); return value }
        override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
            val count=super.read(buffer,offset,length); record(count); return count
        }
    }

    private fun receive(opened: TransportPort.Channel) {
        stage("ACTIVITY_FILE_RECEIVE_STARTED","正在接收活动原始数据…")
        val input=ProgressInput(await(transport.input(opened)),resumeOffset)
        val verified = CommitExchange.receiver({
            val saved=receiveStore.commit(ReceiveStore.Request(ReceiveStore.Kind.RAW_ACTIVITY,id,hash,expected),input,{
                // Byte transfer is over; deep verification may take longer than the stall
                // window, so only the absolute ceiling applies from here.
                transferActive=false
                stage("ACTIVITY_ARCHIVE_VERIFYING","文件已保存，正在核对内部每个原始数据块…")
        },resumeOffset)
            transferActive=false
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
                complete(false,"活动数据同步未完成，停在：$failedStep\n原因：${error.message?.take(160) ?: error.javaClass.simpleName}\n已采集数据仍保留在手表；若手表自动重试仍未完成，下次打开原波可再试。")
            }
        }
    }
    private fun complete(success: Boolean,text: String) {
        if(!attemptState.close()) return
        val cleanupFailure=cleanup()
        finished(success && cleanupFailure == null, if(cleanupFailure == null) text else
            "同步资源清理异常；数据保留，请查看问题报告。${cleanupFailure.javaClass.simpleName}")
    }
    override fun cancel() {
        if(!attemptState.close()) return
        cleanup()
    }
    private fun cleanup(): RuntimeException? {
        registered=false
        transferActive=false
        rateState="STABLE"
        handshakeDeadline=0L
        watchdogTask?.cancel(false); watchdogTask=null
        ready.countDown(); committed.countDown(); confirmed.countDown()
        var error=com.rawwave.core.TransferCleanup.run({transport.close()},{executor.shutdownNow()})
        try { watchdog.shutdownNow() } catch (failure: RuntimeException) {
            if(error == null) error=failure else error.addSuppressed(failure)
        }
        if(error != null) try {app.journal?.fault("ACTIVITY_TRANSFER_CLEANUP_FAILED",error)} catch(_:Exception) {}
        return error
    }
}
