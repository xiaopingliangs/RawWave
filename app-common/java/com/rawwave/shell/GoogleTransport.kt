package com.rawwave.shell

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.*
import com.rawwave.core.TransportPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Data Layer implementation only: wire protocol, hashes, storage and deadlines stay outside. */
class GoogleTransport(context: Context, evidence: (String,String) -> Unit) : TransportPort {
    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val resources = TransferResourceOwner(evidence)
    private val direct = Executor { it.run() }
    private val handles = ConcurrentHashMap<ChannelClient.Channel,Handle>()
    @Volatile private var closed = false
    private inner class Handle(val native: ChannelClient.Channel) : TransportPort.Channel {
        override fun getNodeId() = native.nodeId
        override fun getPath() = native.path
    }
    private fun handle(native: ChannelClient.Channel) = handles.computeIfAbsent(native) { Handle(it) }
    private fun native(value: TransportPort.Channel): ChannelClient.Channel {
        val handle = value as? GoogleTransport.Handle ?: error("FOREIGN_CHANNEL_HANDLE")
        check(handles[handle.native] === handle) { "FOREIGN_CHANNEL_OWNER" }
        return handle.native
    }
    private fun live() { check(!closed) { "TRANSPORT_CLOSED" } }
    private fun <T> future(task: Task<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        task.addOnSuccessListener(direct) { future.complete(it) }
        task.addOnFailureListener(direct) { future.completeExceptionally(it) }
        task.addOnCanceledListener(direct) { future.cancel(false) }
        return future
    }
    override fun registerMessages(consumer: TransportPort.Messages): CompletableFuture<Void> {
        live()
        val listener = MessageClient.OnMessageReceivedListener { consumer.received(it.sourceNodeId,it.path,it.data.copyOf()) }
        return future(resources.pending(messages.addListener(listener),"message-listener") { messages.removeListener(listener) })
    }
    override fun registerChannels(consumer: TransportPort.Channels): CompletableFuture<Void> {
        live()
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onChannelOpened(channel: ChannelClient.Channel) {
                if (closed) channels.close(channel) else consumer.opened(handle(channel))
            }
            override fun onChannelClosed(channel: ChannelClient.Channel,reason: Int,appError: Int) {
                consumer.closed(handle(channel),reason,appError)
            }
        }
        return future(resources.pending(channels.registerChannelCallback(callback),"channel-listener") { channels.unregisterChannelCallback(callback) })
    }
    override fun nodes(): CompletableFuture<List<String>> {
        live(); return future(nodeClient.connectedNodes).thenApply { nodes -> nodes.map { it.id } }
    }
    override fun send(node: String,path: String,bytes: ByteArray): CompletableFuture<Int> {
        live(); return future(messages.sendMessage(node,path,bytes.copyOf()))
    }
    override fun open(node: String,path: String): CompletableFuture<TransportPort.Channel> {
        live()
        return future(resources.pending(channels.openChannel(node,path),"outgoing-channel") { channels.close(it) })
            .thenApply { handle(it) }
    }
    override fun input(channel: TransportPort.Channel): CompletableFuture<java.io.InputStream> {
        live(); return future(channels.getInputStream(native(channel)))
    }
    override fun output(channel: TransportPort.Channel): CompletableFuture<java.io.OutputStream> {
        live(); return future(channels.getOutputStream(native(channel)))
    }
    override fun adopt(channel: TransportPort.Channel) {
        val native = native(channel)
        resources.own(native,"incoming-channel") { channels.close(native) }
    }
    override fun reject(channel: TransportPort.Channel) { channels.close(native(channel)) }
    override fun close() { closed=true; resources.close() }
}
