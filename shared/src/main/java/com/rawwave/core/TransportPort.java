package com.rawwave.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Transport v1: one instance per transfer. No Android/Google types or protocol decisions.
 * Completion means API completion, never durable remote commit. Caller bounds all waits.
 * close is terminal and requests release of owned and late-created resources, without retry.
 * Input/output belong to the channel; keep it open until handshake completes or cancellation.
 * Registration consumers are installed before their future completes. Callbacks may race close;
 * caller must still validate operation identity/deadline. Handles are stable per native channel.
 */
public interface TransportPort extends AutoCloseable {
    int CONTRACT_VERSION=1;
    interface Channel { String getNodeId(); String getPath(); }
    @FunctionalInterface interface Messages { void received(String node,String path,byte[] bytes); }
    interface Channels {
        void opened(Channel channel);
        void closed(Channel channel,int reason,int appError);
    }
    CompletableFuture<Void> registerMessages(Messages consumer);
    CompletableFuture<Void> registerChannels(Channels consumer);
    CompletableFuture<List<String>> nodes();
    CompletableFuture<Integer> send(String node,String path,byte[] bytes);
    CompletableFuture<Channel> open(String node,String path);
    CompletableFuture<InputStream> input(Channel channel);
    CompletableFuture<OutputStream> output(Channel channel);
    void adopt(Channel channel);
    void reject(Channel channel);
    @Override void close();
}
