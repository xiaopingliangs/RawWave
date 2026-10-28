package com.rawwave.core;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Attempt admission v1: pure, process-local state. No IO, clocks, waits or network SDK types.
 * Caller validates protocol/kind/envelope before handing in Identity and supplies monotonic time.
 * Caller still owns registration, transport, wire encoding, phase waits and resource cleanup.
 */
public final class TransferAttempt {
    public static final int CONTRACT_VERSION = 1;
    public record Identity(String peer, String senderWindow, String id, String sha256, long bytes) {
        public Identity {
            Objects.requireNonNull(peer); Objects.requireNonNull(senderWindow);
            Objects.requireNonNull(id); Objects.requireNonNull(sha256);
            if(peer.isEmpty() || senderWindow.isEmpty() || id.isEmpty() || sha256.isEmpty() || bytes<=0)
                throw new IllegalArgumentException("INCOMPLETE_TRANSFER_IDENTITY");
        }
    }
    public enum Action { IGNORE, ACCEPT_HELLO, REPEAT_HELLO, READY, COMMITTED, CONFIRM, FINISHED }
    private final boolean sender, repeatHello;
    private final long deadlineMs;
    private final EnumSet<Action> delivered = EnumSet.noneOf(Action.class);
    private Identity identity;
    private boolean channelAccepted, receiptReady, closed;

    public TransferAttempt(boolean sender, boolean repeatHello, long deadlineMs) {
        this.sender=sender; this.repeatHello=repeatHello; this.deadlineMs=deadlineMs;
    }
    public synchronized boolean isClosed() { return closed; }
    public synchronized boolean isLive(long nowMs) { return !closed && nowMs<deadlineMs; }
    /** Sender binds once, after discovery and before sending HELLO. Cannot rebind a window. */
    public synchronized void bindSender(Identity value, long nowMs) {
        if(!sender || !isLive(nowMs) || identity!=null) throw new IllegalStateException("ATTEMPT_BIND_REJECTED");
        identity=Objects.requireNonNull(value);
    }
    /** Registration and wire parsing must have completed before calling this method. */
    public synchronized Action accept(String type, Identity incoming, long nowMs) {
        if(!isLive(nowMs)) return Action.IGNORE;
        if(identity==null) {
            if(sender || !"HELLO".equals(type)) return Action.IGNORE;
            identity=Objects.requireNonNull(incoming);
            return Action.ACCEPT_HELLO;
        }
        if(!identity.equals(incoming)) return Action.IGNORE;
        if("HELLO".equals(type))
            return !sender && repeatHello && !channelAccepted ? Action.REPEAT_HELLO : Action.IGNORE;
        Action action=switch(type) {
            case "READY" -> sender ? Action.READY : Action.IGNORE;
            case "COMMITTED" -> sender ? Action.COMMITTED : Action.IGNORE;
            case "CONFIRM" -> !sender && receiptReady ? Action.CONFIRM : Action.IGNORE;
            case "FINISHED" -> sender ? Action.FINISHED : Action.IGNORE;
            default -> Action.IGNORE;
        };
        // Existing protocol phase waits remain responsible for ordering; duplicates never advance twice.
        return action!=Action.IGNORE && delivered.add(action) ? action : Action.IGNORE;
    }
    /** Receiver claims exactly one correctly addressed channel, never a late or foreign channel. */
    public synchronized boolean acceptChannel(String peer, String path, String prefix, long nowMs) {
        if(sender || !isLive(nowMs) || identity==null || channelAccepted
                || !identity.peer().equals(peer)
                || !(prefix+identity.senderWindow()+"/"+identity.id()).equals(path)) return false;
        channelAccepted=true;
        return true;
    }
    /** Only after ReceiveStore.commit returns successfully, before sending COMMITTED. */
    public synchronized boolean receiptSaved(long nowMs) {
        if(sender || !isLive(nowMs) || !channelAccepted) return false;
        receiptReady=true;
        return true;
    }
    /** One winner owns cleanup and (for completion) the host callback. */
    public synchronized boolean close() {
        if(closed) return false;
        closed=true;
        return true;
    }
}
