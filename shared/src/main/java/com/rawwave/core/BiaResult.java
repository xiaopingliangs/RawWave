package com.rawwave.core;

import java.util.Map;

/** Completion metadata only; callers retain every original row, including partial/error rows. */
public final class BiaResult {
    private BiaResult() {}
    public static boolean complete(Map<String,?> row) {
        if (!(row.get("STATUS") instanceof Number status) || status.doubleValue() != 0) return false;
        // STATUS alone or an empty/zero placeholder must not count as a body-composition result.
        return range(row,"BODY_FAT_RATIO",0,100) && range(row,"BODY_FAT_MASS",2,500)
            && range(row,"SKELETAL_MUSCLE_MASS",2,500) && range(row,"TOTAL_BODY_WATER",2,500);
    }
    public static boolean multiFrequencyComplete(Map<String,?> row) {
        if (!(row.get("STATUS") instanceof Number n) || n.doubleValue()!=0) return false;
        for(String frequency: new String[]{"5K","10K","50K","250K"}) {
            if(!range(row,"BODY_IMPEDANCE_MAGNITUDE_"+frequency,0.000001,Double.MAX_VALUE)
                || !range(row,"BODY_IMPEDANCE_PHASE_"+frequency,-Double.MAX_VALUE,Double.MAX_VALUE)) return false;
        }
        return true;
    }
    private static boolean range(Map<String,?> row,String key,double min,double max) {
        return row.get(key) instanceof Number n && Double.isFinite(n.doubleValue())
            && n.doubleValue() >= min && n.doubleValue() <= max;
    }
}
