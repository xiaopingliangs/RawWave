package com.rawwave.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TransferRateMonitorTest {
    private final TransferRateMonitor monitor = new TransferRateMonitor();

    @Test public void stableLongTransferNeverStartsTheGraceCountdown() {
        monitor.reset(0L);
        for (int second = 1; second <= 200; second++) {
            monitor.progress(second * 1_000L, second * 100_000L);
            assertEquals(TransferRateMonitor.Verdict.STABLE, monitor.check(second * 1_000L));
        }
        Map<String, Object> evidence = monitor.evidence(200_000L);
        assertEquals(0L, evidence.get("unstableMs"));
        assertEquals(0L, evidence.get("graceRemainingMs"));
        assertEquals(false, evidence.get("expired"));
    }

    @Test public void sustainedFlappingArmsAndThenExpiresTheGraceCountdown() {
        monitor.reset(0L);
        long bytes = 0L;
        for (int second = 1; second <= 90; second++) {
            bytes += (second % 2 == 0) ? 1_000_000L : 10_000L;
            monitor.progress(second * 1_000L, bytes);
            TransferRateMonitor.Verdict verdict = monitor.check(second * 1_000L);
            if (second < 10) assertEquals(TransferRateMonitor.Verdict.STABLE, verdict);
            else if (second < 90) assertEquals(TransferRateMonitor.Verdict.UNSTABLE, verdict);
            else assertEquals(TransferRateMonitor.Verdict.EXPIRED, verdict);
        }
        assertEquals(TransferRateMonitor.Verdict.EXPIRED, monitor.check(90_000L));
        assertEquals(true, monitor.evidence(90_000L).get("expired"));
    }

    @Test public void recoveryCancelsTheGraceCountdown() {
        monitor.reset(0L);
        long bytes = 0L;
        for (int second = 1; second <= 10; second++) {
            bytes += (second % 2 == 0) ? 1_000_000L : 10_000L;
            monitor.progress(second * 1_000L, bytes);
            monitor.check(second * 1_000L);
        }
        assertEquals(TransferRateMonitor.Verdict.UNSTABLE, monitor.check(10_000L));

        for (int second = 11; second <= 200; second++) {
            bytes += 100_000L;
            monitor.progress(second * 1_000L, bytes);
            TransferRateMonitor.Verdict verdict = monitor.check(second * 1_000L);
            if (second >= 20) assertEquals(TransferRateMonitor.Verdict.STABLE, verdict);
        }
        assertEquals(0L, monitor.evidence(200_000L).get("unstableMs"));
        assertFalse((Boolean) monitor.evidence(200_000L).get("expired"));
    }

    @Test public void slowSteadyLinkIsBoundedByTheAbsoluteWindow() {
        monitor.reset(0L);
        for (int second = 1; second <= 300; second++) {
            monitor.progress(second * 1_000L, second * 1_000L);
            assertEquals(TransferRateMonitor.Verdict.STABLE, monitor.check(second * 1_000L));
        }
        Map<String, Object> evidence = monitor.evidence(300_000L);
        assertEquals(1_000d, (Double) evidence.get("meanRateBytesPerSecond"), 0.001d);
        assertEquals(false, evidence.get("expired"));
    }

    @Test public void zeroProgressIsLeftToTheStallWatchdog() {
        monitor.reset(0L);
        for (int second = 1; second <= 120; second++) {
            assertEquals(TransferRateMonitor.Verdict.STABLE, monitor.check(second * 1_000L));
        }
        assertEquals(0d, (Double) monitor.evidence(120_000L).get("meanRateBytesPerSecond"), 0.001d);
        assertEquals(false, monitor.evidence(120_000L).get("expired"));
    }

    @Test public void resetDiscardsAnUnstableWindow() {
        monitor.reset(0L);
        long bytes = 0L;
        for (int second = 1; second <= 10; second++) {
            bytes += (second % 2 == 0) ? 1_000_000L : 10_000L;
            monitor.progress(second * 1_000L, bytes);
            monitor.check(second * 1_000L);
        }
        assertEquals(TransferRateMonitor.Verdict.UNSTABLE, monitor.check(10_000L));

        monitor.reset(20_000L);
        for (int second = 1; second <= 20; second++) {
            monitor.progress(20_000L + second * 1_000L, second * 100_000L);
            assertEquals(TransferRateMonitor.Verdict.STABLE,
                monitor.check(20_000L + second * 1_000L));
        }
        assertEquals(0L, monitor.evidence(40_000L).get("unstableMs"));
    }
}
