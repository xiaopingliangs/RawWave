package com.rawwave.core;

import java.util.*;

/** Serial-owner barrier. Register all sources before invoking possibly inline completions. */
public final class CaptureDrain {
    private final Set<String> pending;
    private final Map<String,String> failures = new LinkedHashMap<>();
    private boolean claimed;
    public CaptureDrain(Collection<String> sources) { pending = new LinkedHashSet<>(sources); }
    public boolean complete(String sensor, SensorPort.DrainStatus status, String detail) {
        if (claimed || !pending.remove(sensor)) return false;
        if (status == null || status == SensorPort.DrainStatus.FAILED)
            failures.put(sensor, String.valueOf(detail));
        else if (status == SensorPort.DrainStatus.END_TIME_COVERED && !sensor.equals("EDA"))
            failures.put(sensor,"END_TIME_COVERAGE_NOT_SUPPORTED:" + detail);
        else if (status == SensorPort.DrainStatus.DELIVERED_ONLY && !sensor.equals("ECG") && !sensor.equals("SPO2") && !sensor.equals("BIA") && !sensor.equals("MF_BIA"))
            failures.put(sensor,"SDK_FLUSH_REQUIRED:" + detail);
        return true;
    }
    public void timeout() {
        if (claimed) return;
        for (String sensor : pending) failures.put(sensor,"DRAIN_TIMEOUT_TAIL_UNKNOWN");
        pending.clear();
    }
    public boolean claimReady() {
        if (claimed || !pending.isEmpty()) return false;
        claimed = true; return true;
    }
    public boolean succeeded() { return failures.isEmpty() && pending.isEmpty(); }
    public Map<String,String> failures() { return Map.copyOf(failures); }
    @FunctionalInterface public interface Persistence { void save() throws Exception; }
    @FunctionalInterface public interface Completion { void done(String failure); }
    /** The only normal phase-release gate. Persist even after SDK failure to salvage rows. */
    public boolean persist(Persistence persistence, Completion completion) {
        if (!claimReady()) return false;
        String failure = succeeded() ? null : "DRAIN_INCOMPLETE:" + failures;
        try { persistence.save(); }
        catch (Exception e) {
            failure = (failure == null ? "" : failure + ";") + "DRAIN_SAVE_FAILED:" + e;
        }
        // Outside the persistence catch: failure in the next phase is not a disk failure.
        completion.done(failure);
        return true;
    }
}
