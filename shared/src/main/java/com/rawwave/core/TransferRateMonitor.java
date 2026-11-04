package com.rawwave.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded rate-stability watchdog for the file-byte phase of one raw-activity transfer.
 *
 * <p>A transfer whose rate is basically stable may use the size-aware transfer window; the UI
 * idle countdown is suspended separately while the transfer is active. When the measured rate
 * fluctuates significantly for a full {@link #WINDOW_MS} window, this monitor starts one
 * {@link #GRACE_MS} countdown. Recovery cancels the countdown. A complete stop is handled
 * earlier by the 30-second no-progress watchdog. These are transport safety rules, not
 * judgments about the recorded data.</p>
 */
public final class TransferRateMonitor {
    public enum Verdict { STABLE, UNSTABLE, EXPIRED }

    public static final long SAMPLE_MS = 1_000L;
    public static final long WINDOW_MS = 10_000L;
    public static final long GRACE_MS = 80_000L;
    /** Coefficient of variation above this value counts as significant fluctuation. */
    public static final double CV_THRESHOLD = 0.75d;
    /** Very slow but steady links are bounded by the absolute transfer window instead. */
    public static final long MIN_RATE_BYTES_PER_SECOND = 4L * 1024L;
    public static final int MIN_RATE_SAMPLES = 5;

    private record Sample(long atMs, long bytes) {}
    private record Metrics(int intervals, double meanRate, double cv) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long startedMs = -1L;
    private long currentBytes;
    private long graceStartMs = -1L;
    private boolean expired;

    public synchronized void reset(long nowMs) {
        samples.clear();
        startedMs = nowMs;
        currentBytes = 0L;
        graceStartMs = -1L;
        expired = false;
        samples.addLast(new Sample(nowMs, 0L));
    }

    /** Record cumulative file bytes. Called on every write/read progress callback. */
    public synchronized void progress(long nowMs, long cumulativeBytes) {
        if (startedMs < 0L || cumulativeBytes < currentBytes) reset(nowMs);
        currentBytes = cumulativeBytes;
        sample(nowMs, cumulativeBytes);
    }

    /**
     * Evaluate the current window. Returns {@link Verdict#UNSTABLE} once the fluctuation window
     * is full and the grace countdown is running, and {@link Verdict#EXPIRED} when it runs out.
     */
    public synchronized Verdict check(long nowMs) {
        if (startedMs < 0L) return Verdict.STABLE;
        if (expired) return Verdict.EXPIRED;
        // A stalled writer produces no progress callbacks; sampling here still records the
        // pause so the stability window cannot silently age out.
        sample(nowMs, currentBytes);
        if (nowMs - startedMs < WINDOW_MS) return Verdict.STABLE;
        Metrics metrics = metrics(nowMs);
        if (metrics.intervals() < MIN_RATE_SAMPLES || metrics.meanRate() < MIN_RATE_BYTES_PER_SECOND) {
            graceStartMs = -1L;
            return Verdict.STABLE;
        }
        if (metrics.cv() <= CV_THRESHOLD) {
            graceStartMs = -1L;
            return Verdict.STABLE;
        }
        if (graceStartMs < 0L) graceStartMs = nowMs;
        if (nowMs - graceStartMs >= GRACE_MS) {
            expired = true;
            return Verdict.EXPIRED;
        }
        return Verdict.UNSTABLE;
    }

    public synchronized Map<String, Object> evidence(long nowMs) {
        Metrics metrics = metrics(nowMs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMs", WINDOW_MS);
        result.put("graceMs", GRACE_MS);
        result.put("cvThreshold", CV_THRESHOLD);
        result.put("minRateBytesPerSecond", MIN_RATE_BYTES_PER_SECOND);
        result.put("samples", samples.size());
        result.put("rateSamples", metrics.intervals());
        result.put("meanRateBytesPerSecond", metrics.meanRate());
        result.put("cv", metrics.cv());
        result.put("unstableMs", graceStartMs < 0L ? 0L : Math.max(0L, nowMs - graceStartMs));
        result.put("graceRemainingMs", graceStartMs < 0L ? 0L : Math.max(0L, GRACE_MS - (nowMs - graceStartMs)));
        result.put("expired", expired);
        return result;
    }

    private void sample(long nowMs, long bytes) {
        Sample last = samples.peekLast();
        if (last == null || nowMs - last.atMs() >= SAMPLE_MS) {
            samples.addLast(new Sample(nowMs, bytes));
        } else {
            samples.removeLast();
            samples.addLast(new Sample(last.atMs(), bytes));
        }
    }

    private Metrics metrics(long nowMs) {
        if (samples.size() < 2) return new Metrics(0, 0d, 0d);
        long windowStart = nowMs - WINDOW_MS;
        Sample base = samples.peekFirst();
        for (Sample sample : samples) {
            if (sample.atMs() <= windowStart) base = sample;
            else break;
        }
        List<double[]> intervals = new ArrayList<>();
        double totalMs = 0d;
        double totalBytes = 0d;
        Sample previous = base;
        for (Sample sample : samples) {
            if (sample.atMs() <= base.atMs()) continue;
            long dt = sample.atMs() - previous.atMs();
            long bytes = sample.bytes() - previous.bytes();
            if (dt > 0L) {
                intervals.add(new double[]{bytes * 1000d / dt, dt});
                totalMs += dt;
                totalBytes += bytes;
            }
            previous = sample;
        }
        if (intervals.isEmpty() || totalMs <= 0d) return new Metrics(0, 0d, 0d);
        double mean = totalBytes * 1000d / totalMs;
        double variance = 0d;
        for (double[] interval : intervals) {
            double delta = interval[0] - mean;
            variance += interval[1] * delta * delta;
        }
        variance /= totalMs;
        double cv = mean <= 0d ? 0d : Math.sqrt(variance) / mean;
        return new Metrics(intervals.size(), mean, cv);
    }
}
