package com.rawwave.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensorSilencePolicyTest {
    @Test public void skinTemperatureUsesItsSixtySecondCadence() {
        assertEquals(60_000L, SensorSilencePolicy.expectedIntervalMs(SensorCatalog.SKIN_TEMPERATURE));
        assertEquals(180_000L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.SKIN_TEMPERATURE));
    }

    @Test public void highRateSourcesKeepTheFifteenSecondFloor() {
        assertEquals(15_000L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.HEART_RATE));
        assertEquals(15_000L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.PLATFORM_ACCELEROMETER));
        assertEquals(15_000L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.GYROSCOPE));
    }

    @Test public void eventSourcesDoNotRaiseSilenceAlarms() {
        assertEquals(0L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.STEP_COUNT));
        assertEquals(0L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.STEP_DETECTOR));
        assertEquals(0L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.OFFBODY));
        assertEquals(0L, SensorSilencePolicy.silenceThresholdMs(SensorCatalog.WRIST_TILT_GESTURE));
    }

    @Test public void unknownSourceFallsBackToTheGenericFloor() {
        assertEquals(15_000L, SensorSilencePolicy.silenceThresholdMs("UNKNOWN"));
        assertEquals(0L, SensorSilencePolicy.silenceThresholdMs(null));
    }
}
