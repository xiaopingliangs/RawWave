package com.rawwave.core;

import java.util.*;

/** Reads one Samsung callback without changing any SDK value stored in its rows. */
public final class EcgBatchQuality {
    private EcgBatchQuality() {}

    public record Result(Integer leadOff, int rawRows, int ecgSamples, int missingEcgValues,
                         int missingTimestamps, int decreasingTimestamps, long maxGapMs,
                         long firstTimestampMs, long lastTimestampMs, int saturatedSamples,
                         Double minThresholdMv, Double maxThresholdMv, Long callbackSequence,
                         int callbackMetadataErrors) {
        public boolean contactValid() { return leadOff != null && leadOff == 0; }
        public boolean thresholdsPresent() { return minThresholdMv != null && maxThresholdMv != null; }
    }

    public static Result inspect(List<? extends Map<String,?>> rows) {
        Integer leadOff=rows.isEmpty()?null:integer(rows.get(0).get("LEAD_OFF"));
        Double min=rows.isEmpty()?null:number(rows.get(0).get("MIN_THRESHOLD_MV"));
        Double max=rows.isEmpty()?null:number(rows.get(0).get("MAX_THRESHOLD_MV"));
        Long callbackSequence=rows.isEmpty()?null:longNumber(rows.get(0).get("captureCallbackSequence"));
        int samples=0, missingValues=0, missingTimes=0, decreasing=0, saturated=0, metadataErrors=0;
        long first=Long.MIN_VALUE, last=Long.MIN_VALUE, maxGap=0;
        for(Map<String,?> row:rows) {
            Double value=number(row.get("ECG_MV"));
            if(value==null) missingValues++; else {
                samples++;
                if(min!=null && max!=null && (value<min || value>max)) saturated++;
            }
            Long timestamp=longNumber(row.get("sdkTimestamp"));
            if(timestamp==null) missingTimes++;
            else {
                if(first==Long.MIN_VALUE) first=timestamp;
                if(last!=Long.MIN_VALUE) {
                    if(timestamp<last) decreasing++;
                    else if(timestamp>last) maxGap=Math.max(maxGap,timestamp-last);
                }
                last=timestamp;
            }
        }
        for(int i=0;i<rows.size();i++) {
            Map<String,?> row=rows.get(i);
            Long sequence=longNumber(row.get("captureCallbackSequence"));
            Integer index=integer(row.get("captureCallbackIndex"));
            Integer size=integer(row.get("captureCallbackSize"));
            if(callbackSequence==null || !callbackSequence.equals(sequence) || index==null || index!=i
                || size==null || size!=rows.size()) metadataErrors++;
        }
        return new Result(leadOff,rows.size(),samples,missingValues,missingTimes,decreasing,maxGap,
            first,last,saturated,min,max,callbackSequence,metadataErrors);
    }

    private static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private static Long longNumber(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static Double number(Object value) {
        if(!(value instanceof Number n)) return null;
        double result=n.doubleValue(); return Double.isFinite(result) ? result : null;
    }
}