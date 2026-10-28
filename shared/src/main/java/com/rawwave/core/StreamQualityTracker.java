package com.rawwave.core;

import java.util.*;

/** Computes evidence about callback streams while leaving every captured SDK value untouched. */
public final class StreamQualityTracker {
    private final String sensor;
    private long rows, valueRows, missingTimestamps, decreasingTimestamps, equalTimestampRows;
    private long callbackCount, callbackTimestampProblems, callbackSequenceGaps, largeGaps;
    private long firstTimestamp=Long.MIN_VALUE, lastTimestamp=Long.MIN_VALUE, lastCallbackTimestamp=Long.MIN_VALUE;
    private long lastCallbackSequence=Long.MIN_VALUE, maxGapMs, lastBatchRows;
    private final Map<String,Long> statusCounts=new TreeMap<>();
    public StreamQualityTracker(String sensor){this.sensor=Objects.requireNonNull(sensor);}

    public void observe(List<? extends Map<String,?>> batch) {
        callbackCount++;
        lastBatchRows=batch.size();
        Long anchor=null, callbackSequence=null;
        if(!batch.isEmpty()) {
            Object rawAnchor=batch.get(0).get("sdkTimestamp");
            if(rawAnchor instanceof Number n) anchor=n.longValue();
            Object rawSequence=batch.get(0).get("captureCallbackSequence");
            if(rawSequence instanceof Number n) callbackSequence=n.longValue();
        }
        if(anchor!=null && lastCallbackTimestamp!=Long.MIN_VALUE) {
            long gap=anchor-lastCallbackTimestamp;
            if(gap<=0) callbackTimestampProblems++;
            else { maxGapMs=Math.max(maxGapMs,gap); if(gap>gapThresholdMs()) largeGaps++; }
        }
        if(callbackSequence!=null && lastCallbackSequence!=Long.MIN_VALUE && callbackSequence!=lastCallbackSequence+1)
            callbackSequenceGaps++;
        if(anchor!=null) lastCallbackTimestamp=anchor;
        if(callbackSequence!=null) lastCallbackSequence=callbackSequence;
        for(Map<String,?> row:batch) {
            rows++;
            if(hasPrimaryValue(row)) valueRows++;
            for(String statusName:statusNames()) {
                Object status=row.get(statusName);
                if(status!=null) statusCounts.merge(statusName+"="+status,1L,Long::sum);
            }
            Object rawTime=row.get("sdkTimestamp");
            if(!(rawTime instanceof Number n)) { missingTimestamps++; continue; }
            long timestamp=n.longValue();
            if(firstTimestamp==Long.MIN_VALUE) firstTimestamp=timestamp;
            if(lastTimestamp!=Long.MIN_VALUE) {
                long gap=timestamp-lastTimestamp;
                if(gap<0) decreasingTimestamps++;
                else if(gap==0) equalTimestampRows++;
            }
            lastTimestamp=timestamp;
        }
    }

    public Map<String,Object> summary() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("sensor",sensor); result.put("rawRows",rows); result.put("valueRows",valueRows);
        result.put("missingValueRows",rows-valueRows); result.put("missingTimestamps",missingTimestamps);
        result.put("decreasingTimestamps",decreasingTimestamps); result.put("equalTimestampRows",equalTimestampRows);
        result.put("callbackCount",callbackCount); result.put("callbackTimestampProblems",callbackTimestampProblems);
        result.put("callbackSequenceGapCount",callbackSequenceGaps); result.put("largeCallbackGapCount",largeGaps);
        result.put("maxCallbackGapMs",maxGapMs); result.put("firstSdkTimestampMs",nullableTime(firstTimestamp));
        result.put("lastSdkTimestampMs",nullableTime(lastTimestamp)); result.put("statusCounts",Map.copyOf(statusCounts));
        if(firstTimestamp!=Long.MIN_VALUE && lastTimestamp>firstTimestamp) {
            double span=lastTimestamp-firstTimestamp;
            if(sensor.equals("ECG")) span+=lastBatchRows*2.0;
            result.put("observedRateHz",rows*1000.0/span);
        }
        return result;
    }
    private boolean hasPrimaryValue(Map<String,?> row) {
        return switch(sensor) {
            case "MF_BIA" -> row.get("BODY_IMPEDANCE_MAGNITUDE_50K") instanceof Number;
            case "MAGNETIC_FIELD" -> row.get("MAGNETIC_FIELD_X") instanceof Number && row.get("MAGNETIC_FIELD_Y") instanceof Number && row.get("MAGNETIC_FIELD_Z") instanceof Number;
            case "BIA" -> row.get("BODY_FAT_RATIO") instanceof Number && row.get("SKELETAL_MUSCLE_MASS") instanceof Number;
            case "PRESSURE" -> row.get("PRESSURE_HPA") instanceof Number;
            case "ECG" -> row.get("ECG_MV") instanceof Number;
            case "SPO2" -> row.get("SPO2") instanceof Number;
            case "GYROSCOPE" -> row.get("GYROSCOPE_X") instanceof Number && row.get("GYROSCOPE_Y") instanceof Number && row.get("GYROSCOPE_Z") instanceof Number;
            case "HEART_RATE" -> row.get("HEART_RATE") instanceof Number;
            case "ACCELEROMETER" -> row.get("ACCELEROMETER_X") instanceof Number
                && row.get("ACCELEROMETER_Y") instanceof Number && row.get("ACCELEROMETER_Z") instanceof Number;
            case "PPG" -> row.get("PPG_GREEN") instanceof Number && row.get("PPG_IR") instanceof Number
                && row.get("PPG_RED") instanceof Number;
            case "SKIN_TEMPERATURE" -> row.get("OBJECT_TEMPERATURE") instanceof Number
                && row.get("AMBIENT_TEMPERATURE") instanceof Number;
            case "EDA" -> row.get("SKIN_CONDUCTANCE") instanceof Number;
            default -> false;
        };
    }
    private List<String> statusNames() { return switch(sensor) {
        case "HEART_RATE" -> List.of("HEART_RATE_STATUS");
        case "PPG" -> List.of("GREEN_STATUS","IR_STATUS","RED_STATUS");
        case "MF_BIA", "BIA", "SKIN_TEMPERATURE", "EDA", "SPO2" -> List.of("STATUS");
        case "MAGNETIC_FIELD", "PRESSURE", "GYROSCOPE" -> List.of("accuracy");
        default -> List.of();
    }; }
    private long gapThresholdMs(){return switch(sensor){
        case "ECG"->100; case "ACCELEROMETER", "PPG"->200; default->5_000;
    };}
    private static Object nullableTime(long value){return value==Long.MIN_VALUE?null:value;}
}
