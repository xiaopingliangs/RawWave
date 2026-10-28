package com.rawwave.core;

import java.util.List;
import java.util.Map;

/** Evidence for coverage of a requested EDA end time, not an SDK flush acknowledgement.
 * At 1 Hz require adjacent samples straddling the boundary within 1250 ms.
 * Wall-clock changes, future timestamps, missing fields and unordered data fail closed.
 */
public final class EdaEndBoundary {
    private long cutoff, elapsed, previous = -1;
    private boolean armed, invalid, covered;
    public void arm(long utc, long monotonic) { cutoff=utc; elapsed=monotonic; armed=true; }
    public boolean observe(List<? extends Map<String,?>> rows) {
        for (var row:rows) {
            if (!(row.get("sdkTimestamp") instanceof Number t)
                || !(row.get("receivedUtcMs") instanceof Number u)
                || !(row.get("receivedElapsedMs") instanceof Number e)) { invalid=true; continue; }
            long stamp=t.longValue(), utc=u.longValue(), mono=e.longValue();
            if (stamp<=0 || stamp>utc+100 || (previous>=0 && stamp<=previous)) invalid=true;
            if (armed && (mono<elapsed || Math.abs((utc-cutoff)-(mono-elapsed))>100)) invalid=true;
            if (armed && !invalid && previous>=0 && previous<=cutoff && stamp>=cutoff
                && stamp>previous && stamp-previous<=1250) covered=true;
            previous=stamp;
        }
        return armed && covered && !invalid;
    }
    public String evidence() { return "EDA_END_TIME_COVERED;cutoffUtcMs="+cutoff+
        ";lastSdkTimestamp="+previous+";maxBoundaryGapMs=1250;SDK_FLUSH_NOT_ACKNOWLEDGED"; }
}
