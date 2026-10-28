package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class EcgQualityTest {
    private static Map<String,Object> row(long timestamp,double mv,long callbackSequence,int index,int size) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("sdkTimestamp",timestamp); row.put("ECG_MV",mv);
        row.put("captureCallbackSequence",callbackSequence);
        row.put("captureCallbackIndex",index); row.put("captureCallbackSize",size);
        return row;
    }
    /** Mirrors the observed Samsung shape: 5/10 raw points in one callback may share one timestamp. */
    private static List<Map<String,Object>> batch(long timestamp,int count,int leadOff,long callbackSequence) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<count;i++) rows.add(row(timestamp,0.25,callbackSequence,i,count));
        rows.get(0).put("LEAD_OFF",leadOff); rows.get(0).put("MIN_THRESHOLD_MV",-5.0);
        rows.get(0).put("MAX_THRESHOLD_MV",5.0); return rows;
    }
    @Test public void officialBatchShapeAllowsSharedTimestampWithoutChangingRows() {
        var rows=batch(1000,10,0,1); String before=rows.toString();
        var result=EcgBatchQuality.inspect(rows);
        assertEquals(Integer.valueOf(0),result.leadOff()); assertEquals(10,result.ecgSamples());
        assertEquals(0,result.decreasingTimestamps()); assertEquals(0,result.maxGapMs());
        assertEquals(0,result.callbackMetadataErrors()); assertEquals(Long.valueOf(1),result.callbackSequence());
        assertEquals(before,rows.toString());
    }
    @Test public void anyLeadOffResetsAStartedMeasurement() {
        var gate=new EcgContinuityGate();
        assertEquals(EcgContinuityGate.Status.STARTED,gate.observe(EcgBatchQuality.inspect(batch(0,10,0,1))).status());
        var reset=gate.observe(EcgBatchQuality.inspect(batch(20,10,5,2)));
        assertEquals(EcgContinuityGate.Status.RESET,reset.status()); assertFalse(gate.measuring());
        var restarted=gate.observe(EcgBatchQuality.inspect(batch(40,10,0,3)));
        assertEquals(EcgContinuityGate.Status.STARTED,restarted.status()); assertEquals(2,restarted.segment());
    }
    @Test public void thirtySecondsAcceptsSamsungCallbackAnchors() {
        var gate=new EcgContinuityGate(); EcgContinuityGate.Update update=null;
        for(int i=0;i<1500;i++) update=gate.observe(EcgBatchQuality.inspect(batch(i*20L,10,0,i+1L)));
        assertNotNull(update); assertEquals(EcgContinuityGate.Status.COMPLETE,update.status());
        assertEquals(15_000,update.continuousSamples()); assertTrue(update.coverageMs()>=29_900); assertTrue(gate.complete());
    }
    @Test public void sampleCountWithoutThirtySecondsCannotComplete() {
        var gate=new EcgContinuityGate(); EcgContinuityGate.Update update=null;
        for(int i=0;i<1500;i++) update=gate.observe(EcgBatchQuality.inspect(batch(i,10,0,i+1L)));
        assertNotNull(update); assertEquals(EcgContinuityGate.Status.CONTINUING,update.status());
        assertFalse(gate.complete()); assertEquals(15_000,update.continuousSamples());
    }
    @Test public void largeCallbackTimestampGapResetsMeasurement() {
        var gate=new EcgContinuityGate(); gate.observe(EcgBatchQuality.inspect(batch(0,10,0,1)));
        var update=gate.observe(EcgBatchQuality.inspect(batch(101,10,0,2)));
        assertEquals(EcgContinuityGate.Status.RESET,update.status());
        assertTrue(update.reason().startsWith("CALLBACK_TIMESTAMP_GAP_"));
    }
    @Test public void localCallbackSequenceGapResetsMeasurement() {
        var gate=new EcgContinuityGate(); gate.observe(EcgBatchQuality.inspect(batch(0,10,0,1)));
        var update=gate.observe(EcgBatchQuality.inspect(batch(20,10,0,3)));
        assertEquals(EcgContinuityGate.Status.RESET,update.status());
        assertEquals("LOCAL_CALLBACK_SEQUENCE_GAP",update.reason());
    }
    @Test public void saturationResetsMeasurementAndRawValueRemains() {
        var gate=new EcgContinuityGate(); gate.observe(EcgBatchQuality.inspect(batch(0,10,0,1)));
        var saturated=batch(20,10,0,2); saturated.get(3).put("ECG_MV",6.0);
        var inspected=EcgBatchQuality.inspect(saturated); assertEquals(1,inspected.saturatedSamples());
        assertEquals(6.0,(Double)saturated.get(3).get("ECG_MV"),0.0);
        assertEquals(EcgContinuityGate.Status.RESET,gate.observe(inspected).status());
    }
    @Test public void missingLeadOffNeverStarts() {
        var rows=batch(0,10,0,1); rows.get(0).remove("LEAD_OFF");
        var update=new EcgContinuityGate().observe(EcgBatchQuality.inspect(rows));
        assertEquals(EcgContinuityGate.Status.WAITING,update.status()); assertEquals("LEAD_OFF_MISSING",update.reason());
    }
    @Test public void retryIsUnlimitedAndSkipAppearsOnlyAfterTwoFailures() {
        var policy=new EcgRetryPolicy();
        assertTrue(policy.retryAllowed()); assertFalse(policy.skipAllowed());
        assertEquals(1,policy.recordFailure()); assertFalse(policy.skipAllowed());
        assertEquals(2,policy.recordFailure()); assertTrue(policy.skipAllowed());
        for(int i=0;i<100;i++) { assertTrue(policy.retryAllowed()); policy.recordFailure(); }
        assertEquals(102,policy.failures()); assertTrue(policy.skipAllowed());
    }
    @Test public void callbackAwareQualitySummaryDoesNotModifyRows() {
        var first=batch(0,10,0,1); var second=batch(20,10,0,2);
        String before=first.toString()+second.toString();
        var tracker=new StreamQualityTracker("ECG"); tracker.observe(first); tracker.observe(second);
        assertEquals(before,first.toString()+second.toString());
        assertEquals(20L,tracker.summary().get("rawRows"));
        assertEquals(2L,tracker.summary().get("callbackCount"));
        assertEquals(18L,tracker.summary().get("equalTimestampRows"));
        assertEquals(0L,tracker.summary().get("callbackTimestampProblems"));
        assertEquals(500.0,(Double)tracker.summary().get("observedRateHz"),0.001);
    }
}