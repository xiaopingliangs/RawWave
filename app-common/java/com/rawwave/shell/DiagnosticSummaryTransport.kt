package com.rawwave.shell

import android.content.Context
import android.os.SystemClock
import com.rawwave.core.DiagnosticSummary
import com.rawwave.core.TransportPort
import com.rawwave.core.TransportAwait
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit foreground request. One 15-second budget, no Channel, lease, retry or health owner. */
object DiagnosticSummaryTransport {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun send(context: Context, app: ProbeApplication,
             transportFactory: () -> TransportPort = { GoogleTransport(context) { event,detail ->
                 try { app.journal?.event(event,mapOf("kind" to "DIAGNOSTIC_SUMMARY","detail" to detail)) } catch (_: Exception) {}
             } }, progress: (String) -> Unit = {}): String {
        check(busy.compareAndSet(false,true)) { "SUMMARY_ALREADY_RUNNING" }
        try { return attempt(app,transportFactory,progress) }
        catch (failure: Exception) {
            try { app.journal?.fault("DIAGNOSTIC_SUMMARY_SEND_FAILED",failure) } catch (_: Exception) {}
            throw failure
        } finally { busy.set(false) }
    }
    private fun attempt(app: ProbeApplication, transportFactory: () -> TransportPort, progress: (String) -> Unit): String {
        check(BuildConfig.DEVICE_ROLE == "watch")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        fun remaining(): Long {
            check(!Thread.currentThread().isInterrupted) { "SUMMARY_CANCELLED" }
            return (deadline - SystemClock.elapsedRealtime()).also { check(it > 0) { "SUMMARY_DEADLINE" } }
        }
        fun <T> await(task: CompletableFuture<T>): T {
            val value=TransportAwait.get(task,deadline,remaining()) { SystemClock.elapsedRealtime() }
            remaining(); return value
        }
        val data = DiagnosticSummary.create((app.journal ?: error("DIAGNOSTICS_UNAVAILABLE")).snapshot(), "${BuildConfig.BUILD_RUN} / ${BuildConfig.VERSION_NAME} / API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL}")
        fun record(stage: String) { try { app.journal?.event(stage,mapOf("transferId" to DiagnosticSummary.id(data),"remainingMs" to (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))) } catch (_: Exception) {} }
        record("DIAGNOSTIC_SUMMARY_STARTED")
        progress("正在寻找手机…\n本轮总等待最多15秒。")
        val transport = transportFactory()
        try {
            val node = await(transport.nodes()).firstOrNull() ?: error("NO_CONNECTED_PHONE")
            val ack = CountDownLatch(1)
            val listener = TransportPort.Messages { source,path,bytes ->
                if (source == node && path == DiagnosticSummary.ACK && DiagnosticSummary.acceptsAck(bytes, data)) ack.countDown()
            }
            progress("已找到手机，正在准备摘要确认监听…")
            val registration = transport.registerMessages(listener)
            await(registration)
            progress("正在发送问题摘要…")
            await(transport.send(node, DiagnosticSummary.PATH, data))
            record("DIAGNOSTIC_SUMMARY_QUEUED_NOT_SAVED")
            progress("摘要已交给通信服务，等待手机保存确认…\n此时还不能算成功。")
            check(ack.await(remaining(),TimeUnit.MILLISECONDS)) { "SUMMARY_SAVE_ACK_TIMEOUT" }
            remaining()
            record("DIAGNOSTIC_SUMMARY_SAVE_CONFIRMED")
            return "手机已确认保存问题摘要（不是完整报告，也不代表活动已同步）。请在手机导出问题报告。"
        } finally {
            // The shared owner also releases registrations that arrive after this close.
            transport.close()
        }
    }
}
