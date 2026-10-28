package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.rawwave.core.CommitExchange
import com.rawwave.core.TransportPort
import com.rawwave.core.TransportAwait
import com.rawwave.core.DiagnosticArchive
import com.rawwave.core.TransferFiles
import com.rawwave.core.WorkBudget
import com.rawwave.core.HelloRetryPolicy
import com.rawwave.core.ReceiveStore
import com.rawwave.core.FileReceiveStore
import com.rawwave.core.SendStore
import com.rawwave.core.FileSendStore
import com.rawwave.core.TransferAttempt
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.*

/** One attempt under an external bounded sync lease. No reconnect loop or automatic retry. */
class TransferWindow(
    private val context: Context,
    private val app: ProbeApplication,
    private val update: (String) -> Unit,
    private val finished: (Boolean, String) -> Unit,
    private val initialNode: String? = null,
    private val initialHello: ByteArray? = null,
    private val receiveStore: ReceiveStore = FileReceiveStore(context.filesDir) { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    },
    private val transportPort: TransportPort? = null,
    private val sendStore: SendStore = FileSendStore(context.filesDir, ReceiveStore.Kind.DIAGNOSTIC) { directory ->
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    },
    private val watchRole: Boolean = BuildConfig.DEVICE_ROLE == "watch",
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val environment: () -> Map<String,Any> = { ConnectionEnvironment.inspect(context) },
    private val sendSummary: ((String) -> Unit) -> Unit = { progress -> DiagnosticSummaryTransport.send(context,app,progress=progress); Unit }
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
    @Volatile private var expected = 0
    @Volatile private var channel: TransportPort.Channel? = null
    @Volatile private var failure = ""
    private var summarySaved = false
    private var queuedHello: Pair<String, ByteArray>? = null
    private val control = "/rawwave/g2a/control"
    private val prefix = "/rawwave/g2a/file/"
    private val watch = watchRole

    private fun log(event: String, extra: Map<String, Any> = emptyMap()) {
        app.journal?.event(event, mapOf("windowId" to windowId, "transferId" to id,
            "peer" to peer, "senderWindow" to senderWindow, "sha256" to hash, "bytesExpected" to expected,
            "remainingMs" to (deadline-monotonicMs()).coerceAtLeast(0)) + extra)
    }
    private val windowId = UUID.randomUUID().toString()
    @Volatile private var senderWindow = if (watch) windowId else ""
    @Volatile private var lastStage = "NOT_STARTED"
    private fun stage(event: String, text: String, extra: Map<String, Any> = emptyMap()) {
        checkLive(); lastStage = event; log(event, extra); update(text)
    }
    private fun checkLive() { check(attemptState.isLive(monotonicMs())) { "WINDOW_EXPIRED_OR_CANCELLED" } }
    private fun <T> await(task: CompletableFuture<T>): T {
        checkLive()
        val result = TransportAwait.get(task,deadline,10_000L) { monotonicMs() }
        checkLive(); return result
    }
    private fun waitSignal(signal: CountDownLatch, name: String, maxMs: Long = 12_000) {
        check(signal.await(minOf(maxMs, deadline-monotonicMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)) { "${name}_TIMEOUT" }
        checkLive(); check(failure.isEmpty()) { failure }
    }
    private fun message(type: String): CompletableFuture<Int> {
        val data = JSONObject().put("protocol", 1).put("type", type).put("id", id).put("hash", hash)
            .put("bytes", expected).put("senderWindow",senderWindow)
        if (type == "HELLO") {
            val lastFault = app.journal?.snapshot()?.get("watch/last-fault.json")
            val fault = if (lastFault == null) null else JSONObject(String(lastFault,Charsets.UTF_8))
            data.put("summary", JSONObject().put("build",BuildConfig.BUILD_RUN)
                .put("previousState",app.journal?.previousState()).put("lastFaultKind",fault?.optString("kind"))
                .put("lastFaultMessage",fault?.optString("message")?.take(240)).toString())
        }
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 4096) { "CONTROL_SIZE_LIMIT" }
        return transport.send(peer, if (type == "HELLO") "/rawwave/g2a/hello" else control, bytes)
    }

    private val listener = TransportPort.Messages { node, path, bytes ->
        acceptControl(node, path, bytes)
    }
    /** Retain one bounded HELLO while the manual receiver is still registering its consumers. */
    fun offerHello(node: String, data: ByteArray) {
        if (watch || data.size > 4096) return
        synchronized(this) {
            if (closed) return
            if (!registered) { queuedHello = node to data.copyOf(); return }
        }
        acceptControl(node, "/rawwave/g2a/hello", data)
    }
    private fun acceptControl(sourceNodeId: String, path: String, bytes: ByteArray) {
        if (!closed && registered && (path == control || path == "/rawwave/g2a/hello") && bytes.size <= 4096) {
            try {
                val data = JSONObject(String(bytes, Charsets.UTF_8))
                check(data.getInt("protocol") == 1) { "PROTOCOL_MISMATCH" }
                val incomingId = data.getString("id")
                val incomingHash = data.getString("hash")
                val size = data.getInt("bytes")
                val incomingWindow = data.getString("senderWindow")
                TransferFiles.validate(incomingId, incomingHash, size.toLong())
                TransferFiles.validate(incomingWindow, incomingHash, size.toLong())
                checkLive()
                synchronized(this) {
                    when(attemptState.accept(data.getString("type"),
                        TransferAttempt.Identity(sourceNodeId,incomingWindow,incomingId,incomingHash,size.toLong()),
                        monotonicMs())) {
                        TransferAttempt.Action.ACCEPT_HELLO -> {
                            id = incomingId; hash = incomingHash; expected = size; peer = sourceNodeId; senderWindow = incomingWindow
                            stage("HELLO_ACCEPTED", "已找到手表，准备接收…",mapOf("watchSummary" to data.optString("summary").take(1400)))
                            executor.execute { attempt { await(message("READY")); log("READY_SENT") } }
                        }
                        TransferAttempt.Action.REPEAT_HELLO -> executor.execute { attempt { await(message("READY")); log("READY_REPEATED") } }
                        TransferAttempt.Action.READY -> ready.countDown()
                        TransferAttempt.Action.COMMITTED -> committed.countDown()
                        TransferAttempt.Action.CONFIRM, TransferAttempt.Action.FINISHED -> confirmed.countDown()
                        else -> {}
                    }
                }
            } catch (error: Exception) {
                // Malformed/unrelated input does not reserve a window or create a new deadline.
                try { log("CONTROL_REJECTED", mapOf("error" to (error.message ?: error.javaClass.simpleName))) } catch (_: Exception) {}
            }
        }
    }
    private val callback = object : TransportPort.Channels {
        override fun opened(opened: TransportPort.Channel) {
            if (watch) return // The sender owns the result of openChannel, not this callback.
            synchronized(this@TransferWindow) {
                if (!registered || !attemptState.acceptChannel(opened.nodeId,opened.path,prefix,monotonicMs())) {
                    transport.reject(opened); return
                }
                channel = opened
                transport.adopt(opened)
            }
            executor.execute { attempt { receive(opened) } }
        }
        override fun closed(c: TransportPort.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            if (!closed && c == channel) {
                try { log("CHANNEL_CLOSED", mapOf("closeReason" to closeReason, "appError" to appSpecificErrorCode)) } catch (_: Exception) {}
            }
        }
    }

    fun start() {
        executor.execute { attempt {
            stage("WINDOW_OPENED", if (watch) "正在准备手表报告…" else "正在准备接收…")
            log("CONNECTION_ENVIRONMENT", environment())
            // One user action: protect a small evidence copy before ZIP preparation can fail.
            // This runs inside the existing lease/deadline and is cancelled with this executor.
            if (watch) {
                try {
                    sendSummary { update("正在发送问题报告：$it") }
                    summarySaved = true
                    checkLive()
                    log("REPORT_SUMMARY_SAVE_CONFIRMED")
                } catch (error: Exception) {
                    checkLive()
                    log("REPORT_SUMMARY_UNCONFIRMED", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
                    update("简要诊断未确认送达，继续尝试完整问题报告…")
                }
            }
            // Register both consumers before accepting a HELLO or initiating discovery.
            stage("CHANNEL_LISTENER_REGISTERING", "正在连接本机手表通信服务…")
            val channelTask = transport.registerChannels(callback)
            await(channelTask)
            stage("MESSAGE_LISTENER_REGISTERING", "通信服务已回应，正在准备接收消息…")
            val messageTask = transport.registerMessages(listener)
            await(messageTask)
            registered = true
            if (watch) send() else stage("RECEIVER_READY", "手机已准备好。请在手表点“发送报告到手机”。\n本轮最多80秒；失败停止，下次启动再试。")
            if (!watch && initialNode != null && initialHello != null) offerHello(initialNode, initialHello)
            val pending = synchronized(this) { queuedHello.also { queuedHello = null } }
            if (pending != null) offerHello(pending.first, pending.second)
        } }
    }
    private fun send() {
        val prepared = checkNotNull(sendStore.prepare { reportId ->
            id = reportId
            val fixture = TransferFiles.fixture()
            val payload = (app.journal ?: error("DIAGNOSTICS_UNAVAILABLE")).snapshot().toMutableMap()
            payload["module-contracts.json"] = JSONObject(com.rawwave.core.ModuleVersions.contracts()).toString().toByteArray(Charsets.UTF_8)
            payload["diagnostic-feedback.txt"] = com.rawwave.core.DiagnosticFeedback.report(payload, BuildConfig.DEVICE_ROLE)
            payload["capture-summary.json"] = com.rawwave.core.ActivityStore.diagnosticSummary(File(context.filesDir, "activities"))
            payload["TEST_FIXTURE_10KB.bin"] = fixture
            payload["transfer-info.json"] = JSONObject().put("kind", "G2A_DIAGNOSTIC_NOT_HEALTH_DATA")
                .put("id",id).put("buildRun", BuildConfig.BUILD_RUN).put("model",android.os.Build.MODEL)
                .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                .put("androidRelease", android.os.Build.VERSION.RELEASE).put("sdkInt", android.os.Build.VERSION.SDK_INT)
                .put("fingerprint", android.os.Build.FINGERPRINT).put("feedbackSchemaVersion", 1)
                .put("fixtureBytes",fixture.size).put("fixtureSha256",DiagnosticArchive.hash(fixture)).toString(2).toByteArray()
            payload
        })
        id = prepared.id(); expected = prepared.bytes().toInt(); hash = prepared.sha256()
        val bytes = sendStore.open(prepared).use { it.readBytes() }
        check(bytes.size == expected && DiagnosticArchive.hash(bytes) == hash) { "SEND_SOURCE_CHANGED" }
        TransferFiles.validate(id,hash,expected.toLong())
        stage("DISCOVERY_STARTED", "正在寻找已连接的手机…")
        val nodes = await(transport.nodes())
        check(nodes.size == 1) { "CONNECTED_NODE_COUNT_${nodes.size}: 请确认手机和手表已连接，并在手机点等待接收" }
        peer = nodes.single()
        attemptState.bindSender(TransferAttempt.Identity(peer,senderWindow,id,hash,expected.toLong()),monotonicMs())
        stage("HELLO_SENT", "已找到设备，等待手机回应…")
        val readyDeadline = minOf(deadline, monotonicMs() + HelloRetryPolicy.READY_BUDGET_MS)
        var helloAttempt = 0
        while (HelloRetryPolicy.shouldSend(ready.count == 0L, readyDeadline-monotonicMs())) {
            checkLive()
            val budget = minOf(10_000L, readyDeadline-monotonicMs()).coerceAtLeast(1L)
            TransportAwait.get(message("HELLO"),readyDeadline,budget) { monotonicMs() }
            log("HELLO_TRANSMITTED", mapOf("attempt" to ++helloAttempt))
            val wait = HelloRetryPolicy.waitSlice(readyDeadline-monotonicMs())
            if (ready.count != 0L && wait > 0) ready.await(wait, TimeUnit.MILLISECONDS)
        }
        check(ready.count == 0L) { "PHONE_READY_TIMEOUT: 请打开手机原波后重新开始本轮报告发送" }
        checkLive()
        stage("CHANNEL_OPEN_REQUESTED", "手机已回应，正在建立文件通道…")
        val openTask = transport.open(peer,prefix+senderWindow+"/"+id)
        val opened = await(openTask); channel = opened
        val output = await(transport.output(opened))
        // Keep stream/channel open until durable receiver acknowledgement, not just write/flush.
        stage("FILE_SEND_STARTED", "正在发送报告和 10 KB 测试文件…")
        output.write(bytes); output.flush()
        stage("FILE_BYTES_WRITTEN", "已送出 $expected 字节，等待手机保存校验；还不算成功。", mapOf("bytesWritten" to expected))
        CommitExchange.sender({
            waitSignal(committed,"PHONE_COMMIT", 30_000)
        }, {
            stage("REMOTE_RECEIPT_RECEIVED", "手机已保存，正在保存本地确认记录…")
            sendStore.acknowledge(prepared, SendStore.Confirmation(id,hash,expected.toLong(),peer,System.currentTimeMillis()))
            stage("LOCAL_ACK_SAVED", "本地确认已保存，正在通知手机…")
        }, {
            await(message("CONFIRM"))
            stage("CONFIRM_SENT", "已发送确认，等待手机完成回应…")
        }, {
            waitSignal(confirmed,"PHONE_FINISHED")
        })
        stage("REMOTE_COMMIT_CONFIRMED", "手机已保存并校验，正在退出。")
        complete(true,"手机已保存并校验，手表已保存回执。")
    }
    private fun receive(opened: TransportPort.Channel) {
        stage("FILE_RECEIVE_STARTED", "正在接收手表报告…")
        val input = await(transport.input(opened))
        val bytes = TransferFiles.readExact(input,expected)
        stage("FILE_RECEIVED", "收到 ${bytes.size} 字节，正在校验和保存…", mapOf("bytesReceived" to bytes.size))
        checkLive()
        CommitExchange.receiver({
            receiveStore.commit(ReceiveStore.Request(ReceiveStore.Kind.DIAGNOSTIC,id,hash,expected.toLong()),
                java.io.ByteArrayInputStream(bytes)) { checkLive() }
            checkLive()
            stage("FILE_COMMITTED", "文件已保存并校验，正在通知手表…", mapOf("bytesCommitted" to bytes.size))
            check(attemptState.receiptSaved(monotonicMs())) { "RECEIPT_OUTSIDE_WINDOW" }
            Unit
        }, { await(message("COMMITTED")); Unit }, { waitSignal(confirmed,"WATCH_CONFIRM",15_000) }, { await(message("FINISHED")); Unit })
        stage("WATCH_CONFIRM_RECEIVED", "手表已确认，正在退出；重新打开手机可导出双端报告。")
        complete(true,"已保存手表报告；重新打开手机，点导出问题报告。")
    }
    private fun attempt(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            if (!closed) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                try { app.journal?.fault("G2A_TRANSFER_FAILED",error); log("TRANSFER_FAILED",mapOf("error" to failure,"lastStage" to lastStage)) } catch (_: Exception) {}
                val explanation = if (failure.contains("API_UNAVAILABLE"))
                    "本机手表通信接口未能连接，还没有开始传文件。服务识别信息已写入报告，请导出给我分析。"
                    else "传输未完成，停在：$lastStage\n$failure"
                val partial = if (summarySaved) "\n简要诊断已保存在手机，可先导出问题报告；完整报告未完成。" else ""
                complete(false, "$explanation$partial\n已保存的数据保留，不会自动重试。")
            }
        }
    }
    private fun complete(success: Boolean, text: String) {
        if (!attemptState.close()) return
        cleanup(); finished(success,text)
    }
    fun cancel() {
        if (!attemptState.close()) return
        cleanup()
    }
    private fun cleanup() {
        registered = false
        ready.countDown(); committed.countDown(); confirmed.countDown()
        transport.close()
        executor.shutdownNow()
    }
}
