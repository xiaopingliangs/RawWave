package com.rawwave.core;

import java.util.List;
import java.util.Map;

/**
 * Capture handoff v2. No platform types, storage, networking or activity decisions.
 * Commands and delivered callbacks run on the owner's serial dispatcher. Implementations
 * copy SDK-owned values before dispatch; rows retain nulls, raw fields and original clocks.
 * connect/start returning is NOT proof of samples or successful measurement. Unsupported
 * sources/permissions/start failures use Failure; one source failure does not stop others.
 * stop returning means the listener-removal call returned, NOT SDK flush completion.
 * Already queued rows may still arrive on their original callback after stop: the owner
 * preserves their original phase. close suppresses further delivery; SDK tail is unknown.
 * Each instance belongs to one session; close is terminal. No reconnect or automatic retry.
 */
public interface SensorPort {
    int CONTRACT_VERSION = 3;
    int contractVersion();
    String implementationVersion();
    void connect(Runnable ready, Failure disconnected);
    void start(String sensor, Rows data, Failure failed);
    /** Remove the listener and deliver accepted rows before completion on the serial owner.
     * FLUSHED acknowledges SDK flush, NOT disk persistence or an atomic hardware stop.
     * END_TIME_COVERED is EDA-only evidence of adjacent original 1 Hz samples spanning
     * the requested end time; it is not SDK flush or proof of the entire stream.
     * DELIVERED_ONLY is allowed only for on-demand sources without SDK flush; the owner
     * separately verifies measurement completion. Owner supplies a bounded timeout and
     * may call stop for emergency cleanup. No automatic retry or new measurement. */
    void drain(String sensor, Drain completed);
    void stop(String sensor);
    void close();

    @FunctionalInterface interface Rows { void accept(List<Map<String, Object>> rows); }
    @FunctionalInterface interface Failure { void accept(String reason); }
    enum DrainStatus { FLUSHED, END_TIME_COVERED, DELIVERED_ONLY, FAILED }
    @FunctionalInterface interface Drain { void accept(DrainStatus status, String detail); }

    /** Reject incompatible implementations before connecting or starting any hardware. */
    static SensorPort requireCompatible(SensorPort port) {
        if (port == null || port.contractVersion() != CONTRACT_VERSION)
            throw new IllegalArgumentException("INCOMPATIBLE_SENSOR_CONTRACT");
        if (port.implementationVersion() == null || port.implementationVersion().isBlank())
            throw new IllegalArgumentException("MISSING_SENSOR_IMPLEMENTATION_VERSION");
        return port;
    }
}
