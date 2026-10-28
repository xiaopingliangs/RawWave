package com.rawwave.core;

/** Strict 30-second ECG gate. Raw samples are never filtered or modified by this class. */
public final class EcgContinuityGate {
    public static final int REQUIRED_SAMPLES=15_000;
    public static final long MIN_COVERAGE_MS=29_900;
    /** Callback timestamps are batch anchors; rows inside one Samsung callback may share a timestamp. */
    public static final long MAX_CALLBACK_GAP_MS=100;
    public enum Status { WAITING, STARTED, CONTINUING, RESET, COMPLETE }
    public record Update(Status status, int segment, String reason, long continuousSamples,
                         long coverageMs, int remainingSeconds, long firstTimestampMs,
                         long lastTimestampMs) {}
    private boolean measuring, complete;
    private int segment;
    private long samples, firstTimestamp=Long.MIN_VALUE, lastTimestamp=Long.MIN_VALUE;
    private long lastCallbackSequence=Long.MIN_VALUE;

    public Update observe(EcgBatchQuality.Result batch) {
        if(complete) return update(Status.COMPLETE,"ALREADY_COMPLETE");
        String invalid=invalidReason(batch);
        if(invalid==null && measuring) {
            long gap=batch.firstTimestampMs()-lastTimestamp;
            if(gap<=0) invalid="CALLBACK_TIMESTAMP_NOT_INCREASING";
            else if(gap>MAX_CALLBACK_GAP_MS) invalid="CALLBACK_TIMESTAMP_GAP_"+gap+"MS";
            else if(batch.callbackSequence()!=lastCallbackSequence+1) invalid="LOCAL_CALLBACK_SEQUENCE_GAP";
        }
        if(invalid!=null) {
            boolean reset=measuring;
            measuring=false; samples=0; firstTimestamp=lastTimestamp=Long.MIN_VALUE;
            lastCallbackSequence=Long.MIN_VALUE;
            return update(reset?Status.RESET:Status.WAITING,invalid);
        }
        Status status=Status.CONTINUING;
        if(!measuring) {
            measuring=true; segment++; firstTimestamp=batch.firstTimestampMs(); status=Status.STARTED;
        }
        samples+=batch.ecgSamples(); lastTimestamp=batch.firstTimestampMs();
        lastCallbackSequence=batch.callbackSequence();
        if(samples>=REQUIRED_SAMPLES && coverageMs()>=MIN_COVERAGE_MS) {
            complete=true; measuring=false; status=Status.COMPLETE;
        }
        return update(status,status==Status.COMPLETE?"CONTINUOUS_30S_VERIFIED":"VALID_BATCH");
    }

    private String invalidReason(EcgBatchQuality.Result batch) {
        if(batch.leadOff()==null) return "LEAD_OFF_MISSING";
        if(batch.leadOff()!=0) return "LEAD_OFF_"+batch.leadOff();
        if(batch.rawRows()!=5 && batch.rawRows()!=10) return "UNEXPECTED_ECG_BATCH_SIZE_"+batch.rawRows();
        if(batch.ecgSamples()==0) return "ECG_VALUE_MISSING";
        if(batch.missingEcgValues()>0) return "ECG_VALUE_PARTIAL_MISSING";
        if(batch.missingTimestamps()>0) return "SDK_TIMESTAMP_MISSING";
        if(batch.decreasingTimestamps()>0) return "SDK_TIMESTAMP_DECREASED_WITHIN_CALLBACK";
        if(batch.maxGapMs()>MAX_CALLBACK_GAP_MS) return "SDK_TIMESTAMP_GAP_WITHIN_CALLBACK_"+batch.maxGapMs()+"MS";
        if(batch.callbackSequence()==null || batch.callbackMetadataErrors()>0) return "LOCAL_CALLBACK_METADATA_INVALID";
        if(!batch.thresholdsPresent()) return "ECG_THRESHOLD_MISSING";
        if(batch.saturatedSamples()>0) return "ECG_SATURATED";
        return null;
    }
    private long coverageMs(){return firstTimestamp==Long.MIN_VALUE||lastTimestamp==Long.MIN_VALUE?0:lastTimestamp-firstTimestamp;}
    private Update update(Status status,String reason) {
        long remaining=Math.max(0,REQUIRED_SAMPLES-samples);
        int seconds=(int)((remaining+499)/500);
        return new Update(status,segment,reason,samples,coverageMs(),seconds,firstTimestamp,lastTimestamp);
    }
    public boolean measuring(){return measuring;}
    public boolean complete(){return complete;}
    public int remainingSeconds(){return (int)((Math.max(0,REQUIRED_SAMPLES-samples)+499)/500);}
}