package com.rawwave.core;

/**
 * Time policy for one raw-activity transfer.
 *
 * <p>The attempt starts with a short {@link #INITIAL_MS} window so an idle, failed or tiny
 * transfer cannot keep the watch awake. Once packaging (sender) or HELLO (receiver) reveals
 * the exact archive size, only a genuinely larger archive gets a later deadline. The extension
 * is computed from the size and the conservative link floor, never from how long the previous
 * step happened to take.</p>
 *
 * <p>Handshake, send and commit have separate sub-budgets so a stuck handshake cannot consume
 * the whole transfer time. A stalled file write is aborted after {@link #STALL_MS}. This is a
 * safety ceiling, not a promise of throughput. The transfer is driven by a foreground service
 * and CPU wake lock, not by the screen being on.</p>
 */
public final class ActivityTransferBudget {
    private ActivityTransferBudget() {}

    /** One short attempt window before the archive size is known. */
    public static final long INITIAL_MS = 80_000L;
    /** Conservative packaging throughput: raw bytes per second on the watch (17 min observed). */
    public static final long PACKAGING_MIN_RATE_BYTES_PER_SECOND = 256L * 1024L;
    /** A packaging attempt may legitimately take minutes on a long activity. */
    public static final long PACKAGING_BASE_MS = 60_000L;
    /** Packaging ceiling; the longer watch lease is 35 minutes. */
    public static final long PACKAGING_MAX_MS = 25L * 60_000L;
    /** Extra protection beyond the packaging estimate for slow storage and final verification. */
    public static final long PACKAGING_MARGIN_MS = 120_000L;
    /** Discovery, HELLO retries and opening the file channel after the size is known. */
    public static final long HANDSHAKE_MS = 30_000L;
    /** Base phone commit allowance for small archives. */
    public static final long COMMIT_BASE_MS = 30_000L;
    /** Conservative deep-verification throughput over the compressed archive bytes. */
    public static final long COMMIT_MIN_RATE_BYTES_PER_SECOND = 1L * 1024L * 1024L;
    /** Commit allowance ceiling, bounded so the largest archive still fits the watch lease. */
    public static final long COMMIT_MAX_MS = 5L * 60_000L;
    /** Abort when no file bytes move for this long, even if the absolute ceiling remains. */
    public static final long STALL_MS = 30_000L;
    /** Conservative floor used to size the ceiling. Observed device rate was about 60-265 KB/s. */
    public static final long MIN_RATE_BYTES_PER_SECOND = 32L * 1024L;
    /** Minimum estimated send time; the initial window supplies the rest for tiny archives. */
    public static final long BASE_TRANSFER_MS = 10_000L;
    /**
     * Hard per-attempt file-byte window. The owner resumes from the receiver's durable offset
     * on the next attempt, so a large archive no longer needs one 25-minute window.
     */
    public static final long MAX_TRANSFER_MS = 5L * 60_000L;
    /** Cleanup margin after the work deadline; the lease is a last-resort, not a second attempt. */
    public static final long LEASE_MARGIN_MS = 5_000L;
    /** Last-resort foreground-service ceiling for the watch, including packaging and cleanup. */
    public static final long WATCH_LEASE_MS = 35L * 60_000L;

    /**
     * Estimated packaging time before the archive size is known. This is a separate phase:
     * the short {@link #INITIAL_MS} window must not abort a multi-minute compression of raw
     * session files. The estimate is deliberately conservative and bounded.
     */
    public static long packagingMs(long rawBytes) {
        long bytes = Math.max(1L, rawBytes);
        long estimated = (bytes * 1000L + PACKAGING_MIN_RATE_BYTES_PER_SECOND - 1L)
            / PACKAGING_MIN_RATE_BYTES_PER_SECOND;
        return Math.min(PACKAGING_MAX_MS, Math.max(PACKAGING_BASE_MS, estimated));
    }

    /** Initial attempt window: small activities keep the short window, long ones get packaging time. */
    public static long prepareBudgetMs(long rawBytes) {
        return Math.max(INITIAL_MS, packagingMs(rawBytes));
    }

    /** Watch foreground-service lease for packaging + handshake cleanup. */
    public static long prepareLeaseMs(long rawBytes) {
        return prepareBudgetMs(rawBytes) + PACKAGING_MARGIN_MS;
    }

    public static long transferMs(long bytes) {
        if (bytes <= 0L || bytes > ActivityTransferFiles.MAX_BYTES)
            throw new IllegalArgumentException("INVALID_ACTIVITY_TRANSFER_SIZE");
        long estimated = (bytes * 1000L + MIN_RATE_BYTES_PER_SECOND - 1L) / MIN_RATE_BYTES_PER_SECOND;
        return Math.min(MAX_TRANSFER_MS, Math.max(BASE_TRANSFER_MS, estimated));
    }

    /**
     * Deep verification reads every raw block and hashes it; the watch measured 96 s for a
     * 69 MB / 531 MB-uncompressed archive. Scale from compressed bytes so a fixed 30 s does
     * not time out a valid large commit on the phone.
     */
    public static long commitMs(long bytes) {
        long size = Math.max(1L, bytes);
        long estimated = (size * 1000L + COMMIT_MIN_RATE_BYTES_PER_SECOND - 1L) / COMMIT_MIN_RATE_BYTES_PER_SECOND;
        return COMMIT_BASE_MS + Math.min(COMMIT_MAX_MS, estimated);
    }

    /** Handshake + estimated send + commit, measured from the moment the size is known. */
    public static long requiredMs(long bytes) {
        return HANDSHAKE_MS + transferMs(bytes) + commitMs(bytes);
    }

    /** True only when the archive cannot fit in the initial window even at the conservative rate. */
    public static boolean needsExtension(long bytes) {
        return requiredMs(bytes) > INITIAL_MS;
    }

    /**
     * Deadline after packaging/HELLO. Small archives keep the original short window; larger
     * archives get one size-based extension measured from now. Never shortens.
     */
    public static long sizeAwareDeadline(long nowMs, long currentDeadlineMs, long bytes) {
        if (!needsExtension(bytes)) return currentDeadlineMs;
        return Math.max(currentDeadlineMs, nowMs + requiredMs(bytes));
    }

    /** Sender ceiling from the first file write through the final confirmation exchange. */
    public static long sendMs(long bytes) {
        return transferMs(bytes) + commitMs(bytes);
    }

    /** Receiver ceiling from accepting HELLO through saving and acknowledging the archive. */
    public static long receiveMs(long bytes) {
        return HANDSHAKE_MS + sendMs(bytes);
    }

    /** Initial protection before the archive size is known. */
    public static long initialLeaseMs() {
        return INITIAL_MS + LEASE_MARGIN_MS;
    }

    /** Phone receiver protection; size-aware from HELLO, never longer than the watch ceiling. */
    public static long leaseMs(long bytes) {
        return Math.min(WATCH_LEASE_MS, Math.max(initialLeaseMs(), receiveMs(bytes) + LEASE_MARGIN_MS));
    }

    /** Watch protection extension from the moment packaging reveals the size; 0 for small archives. */
    public static long leaseExtensionMs(long bytes) {
        return needsExtension(bytes) ? leaseMs(bytes) : 0L;
    }
}
