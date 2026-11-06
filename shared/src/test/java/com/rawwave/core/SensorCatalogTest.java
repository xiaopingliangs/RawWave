package com.rawwave.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SensorCatalogTest {
    @Test public void normalizationIsStableAndDeduplicated() {
        assertEquals(List.of("HEART_RATE","PPG","EDA"),
            SensorCatalog.normalizeCoreSources(List.of("EDA","PPG","PPG","HEART_RATE")));
    }
    @Test public void unknownAndEmptySelectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->SensorCatalog.normalizeCoreSources(List.of()));
        assertThrows(IllegalArgumentException.class,()->SensorCatalog.normalizeCoreSources(List.of("UNKNOWN")));
    }
    @Test public void legacyNullSelectionKeepsOldBehavior() {
        assertEquals(List.of("HEART_RATE","ACCELEROMETER"),SensorCatalog.normalizeCoreSources(null));
    }
    @Test public void ppgQualityRequiresAllThreeUnmodifiedSdkChannels() {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("sdkTimestamp",1000L);row.put("PPG_GREEN",10);row.put("PPG_IR",20);row.put("PPG_RED",30);
        row.put("GREEN_STATUS",0);row.put("IR_STATUS",1);row.put("RED_STATUS",2);
        String before=row.toString();var tracker=new StreamQualityTracker("PPG");tracker.observe(List.of(row));
        assertEquals(before,row.toString());assertEquals(1L,tracker.summary().get("valueRows"));
        assertEquals(3,((Map<?,?>)tracker.summary().get("statusCounts")).size());
    }
    @Test public void newSensorsHaveIndependentValueChecks() {
        var skin=new StreamQualityTracker("SKIN_TEMPERATURE");
        skin.observe(List.of(Map.of("sdkTimestamp",1,"OBJECT_TEMPERATURE",32.0f,"AMBIENT_TEMPERATURE",24.0f,"STATUS",0)));
        var eda=new StreamQualityTracker("EDA");
        eda.observe(List.of(Map.of("sdkTimestamp",1,"SKIN_CONDUCTANCE",2.5f,"STATUS",0)));
        assertEquals(1L,skin.summary().get("valueRows"));assertEquals(1L,eda.summary().get("valueRows"));
    }
    @Test public void ppgOnDemandIsAShortStreamNotACoreSource() {
        assertTrue(SensorCatalog.acceptsStream(SensorCatalog.PPG_ON_DEMAND));
        assertFalse(SensorCatalog.availableCoreSources().contains(SensorCatalog.PPG_ON_DEMAND));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.PPG_ON_DEMAND).contains("100 Hz"));
        assertThrows(IllegalArgumentException.class,
            () -> SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.PPG_ON_DEMAND)));
    }
    @Test public void ppgOnDemandQualityUsesTheSameThreeRawChannels() {
        var ppg=new StreamQualityTracker(SensorCatalog.PPG_ON_DEMAND);
        ppg.observe(List.of(Map.of("sdkTimestamp",1,"PPG_GREEN",10,"PPG_IR",20,"PPG_RED",30,
            "GREEN_STATUS",0,"IR_STATUS",0,"RED_STATUS",0)));
        assertEquals(1L,ppg.summary().get("valueRows"));
        assertEquals(3,((Map<?,?>)ppg.summary().get("statusCounts")).size());
    }
    @Test public void gpsIsACorePlatformSourceWithLocationValueCheck() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.GPS));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.GPS));
        assertFalse(SensorCatalog.isAndroidPlatformSource(SensorCatalog.HEART_RATE));
        assertEquals(List.of("GPS"), SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.GPS)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.GPS).contains("not raw GNSS measurements"));
        var gps = new StreamQualityTracker(SensorCatalog.GPS);
        gps.observe(List.of(Map.of("sdkTimestamp", 1, "GPS_LATITUDE", 31.2, "GPS_LONGITUDE", 121.4,
            "provider", "gps")));
        assertEquals(1L, gps.summary().get("valueRows"));
        assertEquals(1, ((Map<?,?>)gps.summary().get("statusCounts")).size());
    }
    @Test public void stepCountIsACorePlatformSourceWithCumulativeValueCheck() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.STEP_COUNT));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.STEP_COUNT));
        assertEquals(List.of("STEP_COUNT"), SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.STEP_COUNT)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.STEP_COUNT).contains("since boot"));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.STEP_COUNT).contains("not raw motion"));
        var steps = new StreamQualityTracker(SensorCatalog.STEP_COUNT);
        steps.observe(List.of(StepCount.row(42f, 1L, 3, 1L, 2L, 1, 0, 1)));
        assertEquals(1L, steps.summary().get("valueRows"));
        assertEquals(1, ((Map<?,?>)steps.summary().get("statusCounts")).size());
    }
    @Test public void stepDetectorIsACorePlatformSourceWithPerStepValueCheck() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.STEP_DETECTOR));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.STEP_DETECTOR));
        assertEquals(List.of("STEP_DETECTOR"), SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.STEP_DETECTOR)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.STEP_DETECTOR).contains("per-step event"));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.STEP_DETECTOR).contains("not raw motion"));
        var detector = new StreamQualityTracker(SensorCatalog.STEP_DETECTOR);
        detector.observe(List.of(StepDetector.row(1f, 1L, 3, 1L, 2L, 1, 0, 1)));
        assertEquals(1L, detector.summary().get("valueRows"));
        assertEquals(1, ((Map<?,?>)detector.summary().get("statusCounts")).size());
    }
    @Test public void offBodyIsACorePlatformSourceWithStateValueCheck() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.OFFBODY));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.OFFBODY));
        assertEquals(List.of("OFFBODY"), SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.OFFBODY)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.OFFBODY).contains("on-body"));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.OFFBODY).contains("not raw optical"));
        var offBody = new StreamQualityTracker(SensorCatalog.OFFBODY);
        offBody.observe(List.of(OffBody.row(1f, 1L, 3, 1L, 2L, 1, 0, 1)));
        assertEquals(1L, offBody.summary().get("valueRows"));
        assertEquals(1, ((Map<?,?>)offBody.summary().get("statusCounts")).size());
    }
    @Test public void uncalibratedImuSourcesKeepRawAndBiasValues() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.GYROSCOPE_UNCALIBRATED));
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.GYROSCOPE_UNCALIBRATED));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.GYROSCOPE_UNCALIBRATED).contains("estimated bias"));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED).contains("hard-iron bias"));
        var gyro = new StreamQualityTracker(SensorCatalog.GYROSCOPE_UNCALIBRATED);
        gyro.observe(List.of(GyroscopeUncalibrated.row(0.1f,0.2f,0.3f,0.01f,0.02f,0.03f,1L,3,1L,2L,1,0,1)));
        assertEquals(1L, gyro.summary().get("valueRows"));
        var mag = new StreamQualityTracker(SensorCatalog.MAGNETIC_FIELD_UNCALIBRATED);
        mag.observe(List.of(MagneticFieldUncalibrated.row(1f,2f,3f,0.1f,0.2f,0.3f,1L,3,1L,2L,1,0,1)));
        assertEquals(1L, mag.summary().get("valueRows"));
    }
    @Test public void uncalibratedAccelerometerIsACorePlatformSourceWithBias() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.ACCELEROMETER_UNCALIBRATED));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.ACCELEROMETER_UNCALIBRATED));
        assertEquals(List.of(SensorCatalog.ACCELEROMETER_UNCALIBRATED),
            SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.ACCELEROMETER_UNCALIBRATED)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.ACCELEROMETER_UNCALIBRATED).contains("estimated bias"));
        var tracker = new StreamQualityTracker(SensorCatalog.ACCELEROMETER_UNCALIBRATED);
        tracker.observe(List.of(AccelerometerUncalibrated.row(0.1f,0.2f,9.8f,0.01f,0.02f,0.03f,1L,3,1L,2L,1,0,1)));
        assertEquals(1L, tracker.summary().get("valueRows"));
    }
    @Test public void fusedAccelerationAndOrientationAreCorePlatformSources() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.LINEAR_ACCELERATION));
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.GAME_ROTATION_VECTOR));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.LINEAR_ACCELERATION));
        assertTrue(SensorCatalog.isAndroidPlatformSource(SensorCatalog.GAME_ROTATION_VECTOR));
        assertEquals(List.of(SensorCatalog.LINEAR_ACCELERATION, SensorCatalog.GAME_ROTATION_VECTOR),
            SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.GAME_ROTATION_VECTOR, SensorCatalog.LINEAR_ACCELERATION)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.LINEAR_ACCELERATION).contains("gravity removed"));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.GAME_ROTATION_VECTOR).contains("without magnetometer"));
        var linear = new StreamQualityTracker(SensorCatalog.LINEAR_ACCELERATION);
        linear.observe(List.of(LinearAcceleration.row(0.1f,0.2f,0.3f,1L,3,1L,2L,1,0,1)));
        assertEquals(1L, linear.summary().get("valueRows"));
        var rotation = new StreamQualityTracker(SensorCatalog.GAME_ROTATION_VECTOR);
        rotation.observe(List.of(GameRotationVector.row(0.1f,0.2f,0.3f,0.9f,1L,3,1L,2L,1,0,1)));
        assertEquals(1L, rotation.summary().get("valueRows"));
    }
    @Test public void ambientLightIsACoreSourceWithLuxValueCheck() {
        assertTrue(SensorCatalog.availableCoreSources().contains(SensorCatalog.LIGHT));
        assertEquals(List.of("LIGHT"), SensorCatalog.normalizeCoreSources(List.of(SensorCatalog.LIGHT)));
        assertTrue(SensorCatalog.dataKind(SensorCatalog.LIGHT).contains("lux"));
        var light = new StreamQualityTracker(SensorCatalog.LIGHT);
        light.observe(List.of(Map.of("sdkTimestamp", 1, "LIGHT_LUX", 12.5f, "accuracy", 3)));
        assertEquals(1L, light.summary().get("valueRows"));
    }
}
