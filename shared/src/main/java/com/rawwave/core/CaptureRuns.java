package com.rawwave.core;

import java.util.*;

/** Serial capture-owner bookkeeping, not a hardware controller. Identity binds callbacks
 * to one start. Invalidate before SDK stop because stop may deliver a callback inline.
 * Inactive data is still owned by its original phase; caller must preserve it, not relabel.
 */
public final class CaptureRuns {
    public static final class Run {
        public final String phase, sensor;
        private Run(String phase,String sensor){this.phase=phase;this.sensor=sensor;}
    }
    private final Map<String,Run> active=new HashMap<>();
    public Run start(String phase,String sensor) {
        Objects.requireNonNull(phase);Objects.requireNonNull(sensor);
        if(active.containsKey(sensor))throw new IllegalStateException("CAPTURE_ALREADY_RUNNING:"+sensor);
        Run run=new Run(phase,sensor);active.put(sensor,run);return run;
    }
    public boolean current(Run run){return run!=null && active.get(run.sensor)==run;}
    public boolean end(Run run){if(!current(run))return false;active.remove(run.sensor);return true;}
    public void endSensor(String sensor){active.remove(sensor);}
}
