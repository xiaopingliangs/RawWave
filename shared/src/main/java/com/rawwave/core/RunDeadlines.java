package com.rawwave.core;

import java.util.*;
import java.util.function.LongSupplier;

/** Absolute deadlines. Clock must include sleep; a UI scheduler is only a wake-up hint. */
public final class RunDeadlines {
    public enum Kind { IDLE, TRANSFER, EXPORT }
    public record Due(Kind kind, long deadlineMs, long observedMs, long lateMs) {}
    private final LongSupplier clock;
    private final EnumMap<Kind, Long> deadlines = new EnumMap<>(Kind.class);
    private boolean closed;
    public RunDeadlines(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public long now() { return clock.getAsLong(); }
    /** No work survives an idle background page. Background time still counts toward
     * the existing absolute idle deadline; returning is not itself an interaction. */
    public boolean resumeIdle(long budgetMs) {
        if (closed || deadlines.keySet().stream().anyMatch(k -> k != Kind.IDLE)) return false;
        if (deadlines.isEmpty()) put(Kind.IDLE, budgetMs);
        return expired() == null;
    }
    public Map<Kind, Long> snapshot() { return Map.copyOf(deadlines); }
    public Due expired() {
        long now = now();
        return deadlines.entrySet().stream().filter(e -> now >= e.getValue())
            .min(Map.Entry.comparingByValue())
            .map(e -> new Due(e.getKey(), e.getValue(), now, now-e.getValue())).orElse(null);
    }
    /** Interaction cannot forgive an expired window, or extend an operation deadline. */
    public boolean touchIdle(long budgetMs) {
        if (closed || expired() != null || deadlines.keySet().stream().anyMatch(k -> k != Kind.IDLE)) return false;
        put(Kind.IDLE, budgetMs); return true;
    }
    public boolean begin(Kind kind, long budgetMs) {
        if (kind == Kind.IDLE) throw new IllegalArgumentException("USE_TOUCH_IDLE");
        if (closed || expired() != null) return false;
        long end = Math.addExact(now(), positive(budgetMs));
        deadlines.remove(Kind.IDLE);
        // Repeated callbacks cannot renew an existing operation's budget.
        deadlines.merge(kind, end, Math::min);
        return true;
    }
    public boolean finishExport(long idleBudgetMs) {
        if (closed || expired() != null) return false;
        deadlines.remove(Kind.EXPORT);
        if (deadlines.isEmpty()) put(Kind.IDLE, idleBudgetMs);
        return true;
    }
    public boolean finishTransfer(long idleBudgetMs) {
        if (closed || expired() != null) return false;
        deadlines.remove(Kind.TRANSFER);
        if (deadlines.isEmpty()) put(Kind.IDLE, idleBudgetMs);
        return true;
    }
    public long delayMs() {
        if (closed || deadlines.isEmpty()) return Long.MAX_VALUE;
        return Math.max(0, Collections.min(deadlines.values()) - now());
    }
    public void close() { closed = true; deadlines.clear(); }
    private void put(Kind kind, long budgetMs) { deadlines.put(kind, Math.addExact(now(), positive(budgetMs))); }
    private long positive(long budgetMs) {
        if (budgetMs <= 0) throw new IllegalArgumentException("INVALID_BUDGET");
        return budgetMs;
    }
}

