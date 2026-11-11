package com.rawwave.shell

import com.rawwave.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Actual production Service logic, fake FIFO/clock/vendor, real ActivityStore.
 * Android notification, wake lock and main-thread UI/sync callbacks are mocked, not device proof. */
class CaptureServiceTest {
    @Test fun ecgSilencePreservesFragmentAndReviewDeadlineDoesNotStartNextSensor() {
        val r=Rig(true)
        r.sensors.emit("ECG",List(10){index->buildMap<String,Any> {
            put("sdkTimestamp",100L);put("ECG_MV",0.25)
            put("captureCallbackSequence",1L);put("captureCallbackIndex",index);put("captureCallbackSize",10)
            if(index==0){put("LEAD_OFF",0);put("MIN_THRESHOLD_MV",-5.0);put("MAX_THRESHOLD_MV",5.0)}
        }})
        r.queue.runReady();r.queue.advance(15000)
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("REVIEW_PRE",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("15秒没有新数据"))
        assertEquals(10L,r.session.counts()["PRE-1/ECG"])
        // A full 80 seconds from review entry, not the leftover measurement budget.
        r.queue.advance(79999);assertFalse(r.seal().exists())
        r.queue.advance(1001)
        assertTrue(r.seal().exists());assertEquals(listOf("ECG"),r.sensors.starts)
        assertEquals(1,r.sensors.closes)
    }
    private fun bodyRow(status:Int=0):Map<String,Any> = mapOf("STATUS" to status,"BODY_FAT_RATIO" to 20f,
        "BODY_FAT_MASS" to 13f,"SKELETAL_MUSCLE_MASS" to 28f,"TOTAL_BODY_WATER" to 40f,
        "sdkTimestamp" to 100L,"captureCallbackSequence" to 1L,"captureCallbackIndex" to 0,"captureCallbackSize" to 1)
    private fun ppgRows(count:Int) = List(count) { index -> mapOf(
        "sdkTimestamp" to index * 10L,
        "PPG_GREEN" to 100 + index, "PPG_IR" to 200 + index, "PPG_RED" to 300 + index,
        "GREEN_STATUS" to 0, "IR_STATUS" to 0, "RED_STATUS" to 0) }
    @Test fun ppgOnDemandStartsImmediatelyBeforeAndAfterCoreAndKeepsEveryRawRow() {
        val r=Rig(ppg=true,sources=listOf("PRESSURE"))
        assertEquals("PRE-300",ActivityRecordingService.state.phase)
        assertEquals("PPG_ON_DEMAND",ActivityRecordingService.state.shortSensor)
        r.sensors.emit("PPG_ON_DEMAND",ppgRows(10));r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("10 \u6761\u539f\u59cb\u8bb0\u5f55"))
        r.queue.advance(30000)
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("PPG_ON_DEMAND");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("POST-300",ActivityRecordingService.state.phase)
        assertEquals("PPG_ON_DEMAND",ActivityRecordingService.state.shortSensor)
        r.sensors.emit("PPG_ON_DEMAND",ppgRows(10));r.queue.runReady()
        r.queue.advance(30000);r.sensors.ack("PPG_ON_DEMAND");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(10L,r.session.counts()["PRE-300/PPG_ON_DEMAND"])
        assertEquals(10L,r.session.counts()["POST-300/PPG_ON_DEMAND"])
        assertEquals(listOf("PPG_ON_DEMAND","PRESSURE","PPG_ON_DEMAND"),r.sensors.starts)
    }
    @Test fun continuousPpgSkipsPreOnDemandAndStillRunsPostOnDemand() {
        val r=Rig(ppg=true,sources=listOf("PPG"))
        // Continuous CORE PPG and pre short PPG share the vendor 25 Hz client; the pre
        // short measurement is skipped to avoid the known persistent-LED leak.
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.sensors.emit("PPG",ppgRows(10));r.queue.runReady()
        r.end();r.sensors.ack("PPG");r.queue.runReady()
        assertEquals("POST-300",ActivityRecordingService.state.phase)
        r.sensors.emit("PPG_ON_DEMAND",ppgRows(10));r.queue.runReady()
        r.queue.advance(30000);r.sensors.ack("PPG_ON_DEMAND");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertNull(r.session.counts()["PRE-300/PPG_ON_DEMAND"])
        assertEquals(10L,r.session.counts()["CORE-1/PPG"])
        assertEquals(10L,r.session.counts()["POST-300/PPG_ON_DEMAND"])
        assertEquals(listOf("PPG","PPG_ON_DEMAND"),r.sensors.starts)
    }
    @Test fun continuousPpgIsHeldThroughPostEcgAndStoppedLast() {
        val r=Rig(ecg=true,sources=listOf("PPG"))
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.sensors.emit("PPG",ppgRows(4));r.queue.runReady()
        r.end()
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        assertFalse(r.sensors.drains.containsKey("PPG"))
        // The held stream keeps delivering while post ECG runs; rows stay under CORE-1.
        r.sensors.emit("PPG",ppgRows(2));r.queue.runReady()
        assertEquals(6L,r.session.counts()["CORE-1/PPG"])
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        // PPG is drained only after the post-ECG drain has completed.
        assertTrue(r.sensors.drains.containsKey("PPG"))
        r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.seal().exists())
    }
    @Test fun preAndPostChoicesAreIndependent() {
        // No pre measurement: the session opens directly in CORE-1.
        val plain=Rig(preEcg=false,postEcg=false,sources=listOf("PRESSURE"),prePpg=false,postPpg=false)
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        assertFalse(plain.sensors.starts.contains("ECG"))
        // Post-only ECG then post-only PPG; nothing runs before the activity.
        val r=Rig(preEcg=false,postEcg=true,sources=listOf("PRESSURE"),prePpg=false,postPpg=true)
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("POST-300",ActivityRecordingService.state.phase)
    }
    @Test fun preOnlyEcgStillStartsCoreAndSkipsPostMeasurements() {
        val r=Rig(preEcg=true,postEcg=false,sources=listOf("PRESSURE"),prePpg=false,postPpg=false)
        assertEquals("PRE-1",ActivityRecordingService.state.phase)
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertFalse(r.session.counts().containsKey("POST-1/ECG"))
        assertEquals(1,r.sensors.closes)
    }
    @Test fun noPostEcgDrainsPpgBeforeHeartRateForLedRelease() {
        val r=Rig(sources=listOf("HEART_RATE","PPG"),ppgFirstForLed=true)
        r.end()
        assertTrue(r.sensors.drains.containsKey("PPG"))
        assertFalse(r.sensors.drains.containsKey("HEART_RATE"))
        r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.sensors.drains.containsKey("HEART_RATE"))
        r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1,r.sensors.closes)
    }
    @Test fun stepDetectorRowsAreSavedAndSparseCallbacksDoNotTriggerSilence() {
        val r=Rig(sources=listOf("STEP_DETECTOR"))
        r.sensors.emit("STEP_DETECTOR",listOf(StepDetector.row(1f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.queue.advance(60000)
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        assertFalse(ActivityRecordingService.state.text.contains("\u6682\u65e0\u65b0\u6570\u636e"))
        r.end();r.sensors.ack("STEP_DETECTOR");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/STEP_DETECTOR"])
    }
    @Test fun offBodyStateRowsAreSavedAndSparseCallbacksDoNotTriggerSilence() {
        val r=Rig(sources=listOf("OFFBODY"))
        r.sensors.emit("OFFBODY",listOf(OffBody.row(1f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.queue.advance(60000)
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        assertFalse(ActivityRecordingService.state.text.contains("\u6682\u65e0\u65b0\u6570\u636e"))
        r.sensors.emit("OFFBODY",listOf(OffBody.row(0f,2000L,3,3000L,4000L,2,0,1)))
        r.queue.runReady()
        r.end();r.sensors.ack("OFFBODY");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(2L,r.session.counts()["CORE-1/OFFBODY"])
    }
    @Test fun uncalibratedImuRowsAreSavedAndDrained() {
        val r=Rig(sources=listOf("GYROSCOPE_UNCALIBRATED","MAGNETIC_FIELD_UNCALIBRATED"))
        r.sensors.emit("GYROSCOPE_UNCALIBRATED",listOf(
            GyroscopeUncalibrated.row(0.1f,0.2f,0.3f,0.01f,0.02f,0.03f,1000L,3,2000L,3000L,1,0,1)))
        r.sensors.emit("MAGNETIC_FIELD_UNCALIBRATED",listOf(
            MagneticFieldUncalibrated.row(1f,2f,3f,0.1f,0.2f,0.3f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        r.end();r.sensors.ack("GYROSCOPE_UNCALIBRATED");r.sensors.ack("MAGNETIC_FIELD_UNCALIBRATED");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/GYROSCOPE_UNCALIBRATED"])
        assertEquals(1L,r.session.counts()["CORE-1/MAGNETIC_FIELD_UNCALIBRATED"])
    }
    @Test fun fusedLinearAccelerationAndGameRotationRowsAreSavedAndDrained() {
        val r=Rig(sources=listOf("LINEAR_ACCELERATION","GAME_ROTATION_VECTOR"))
        r.sensors.emit("LINEAR_ACCELERATION",listOf(
            LinearAcceleration.row(0.1f,-0.2f,0.3f,1000L,3,2000L,3000L,1,0,1)))
        r.sensors.emit("GAME_ROTATION_VECTOR",listOf(
            GameRotationVector.row(0.1f,0.2f,0.3f,0.9f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        r.end();r.sensors.ack("LINEAR_ACCELERATION");r.sensors.ack("GAME_ROTATION_VECTOR");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/LINEAR_ACCELERATION"])
        assertEquals(1L,r.session.counts()["CORE-1/GAME_ROTATION_VECTOR"])
    }
    @Test fun absoluteRotationAndWristTiltRowsAreSavedAndDrained() {
        val r=Rig(sources=listOf("ROTATION_VECTOR","WRIST_TILT_GESTURE"))
        r.sensors.emit("ROTATION_VECTOR",listOf(
            RotationVector.row(0.1f,0.2f,0.3f,0.9f,0.05f,1000L,3,2000L,3000L,1,0,1)))
        r.sensors.emit("WRIST_TILT_GESTURE",listOf(
            WristTiltGesture.row(1f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        r.end();r.sensors.ack("ROTATION_VECTOR");r.sensors.ack("WRIST_TILT_GESTURE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/ROTATION_VECTOR"])
        assertEquals(1L,r.session.counts()["CORE-1/WRIST_TILT_GESTURE"])
    }
    @Test fun gravityAndGeomagneticRotationRowsAreSavedAndDrained() {
        val r=Rig(sources=listOf("GRAVITY","GEOMAGNETIC_ROTATION_VECTOR"))
        r.sensors.emit("GRAVITY",listOf(
            Gravity.row(0.1f,0.2f,9.8f,1000L,3,2000L,3000L,1,0,1)))
        r.sensors.emit("GEOMAGNETIC_ROTATION_VECTOR",listOf(
            GeomagneticRotationVector.row(0.1f,0.2f,0.3f,0.9f,1000L,3,2000L,3000L,1,0,1)))
        r.queue.runReady()
        r.end();r.sensors.ack("GRAVITY");r.sensors.ack("GEOMAGNETIC_ROTATION_VECTOR");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/GRAVITY"])
        assertEquals(1L,r.session.counts()["CORE-1/GEOMAGNETIC_ROTATION_VECTOR"])
    }
    @Test fun ppgOnlyPostStillRunsAfterUnconfirmedCoreTail() {
        val r=Rig(ppg=true,sources=listOf("PRESSURE"))
        r.sensors.emit("PPG_ON_DEMAND",ppgRows(1));r.queue.runReady()
        r.queue.advance(30000);r.sensors.ack("PPG_ON_DEMAND");r.queue.runReady()
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end();r.queue.advance(5000)
        assertEquals("POST-300",ActivityRecordingService.state.phase)
    }
    @Test fun ppgWithoutRowsGoesToReviewAndSkipContinues() {
        val r=Rig(ppg=true,sources=listOf("PRESSURE"))
        r.queue.advance(30000)
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("PPG_ON_DEMAND");r.queue.runReady()
        assertEquals("REVIEW_PPG_PRE",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("\u672a\u53d6\u5f97\u539f\u59cb\u8bb0\u5f55"))
        r.service.command("SKIP_PPG");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
    }
    @Test fun biaRunsBeforeSpo2AndSpo2RemainsThePostLastMeasurement() {
        val r=Rig(bia=true,spo2=true,sources=listOf("PRESSURE"))
        // BIA only needs contact and starts before the SpO2 placement.
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals(1L,r.session.counts()["PRE-200/BIA"])
        assertEquals("READY_SPO2_PRE",ActivityRecordingService.state.phase)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 97)));r.queue.runReady()
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        // BIA/MF-BIA are pre-activity only; the post queue ends with SpO2.
        assertEquals("READY_SPO2_POST",ActivityRecordingService.state.phase)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 99)));r.queue.runReady()
        r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(listOf("BIA","SPO2","PRESSURE","SPO2"),r.sensors.starts)
        assertFalse(r.session.counts().containsKey("POST-200/BIA"))
    }
    @Test fun biaStatusOnlyCannotSucceedAndRetryKeepsEachAttempt() {
        val r=Rig(bia=true)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 0,"PROGRESS" to 0f)));r.queue.runReady()
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        r.queue.advance(60000);r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("REVIEW_BIA_PRE",ActivityRecordingService.state.phase)
        r.service.command("RETRY_BIA");r.queue.runReady()
        r.sensors.emit("BIA",listOf(bodyRow(9)));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("WAIT_BIA_PRE",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["PRE-200/BIA"]);assertEquals(1L,r.session.counts()["PRE-201/BIA"])
        r.service.command("SKIP_BIA");r.queue.runReady();assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
    }
    @Test fun biaIsNotStartedAfterCoreAndSessionFinishes() {
        val r=Rig(bia=true,sources=listOf("PRESSURE","GYROSCOPE"))
        r.service.command("SKIP_BIA");r.queue.runReady();r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("GYROSCOPE");r.queue.runReady()
        // BIA/MF-BIA are pre-activity only; no post BIA attempt is started.
        assertTrue(r.seal().exists())
        assertEquals(1,r.sensors.starts.count { it == "BIA" })
        assertFalse(r.session.counts().containsKey("POST-200/BIA"))
    }
    @Test fun biaDiskFailureNeverAdvancesToCoreOrBloodOxygen() {
        val r=Rig(bia=true)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        r.failDisk=true;r.sensors.ack("BIA");r.queue.runReady()
        assertEquals(listOf("BIA"),r.sensors.starts);assertFalse(r.seal().exists())
    }
    @Test fun multiFrequencyProgressDoesNotFailAndSuccessEndsImmediately() {
        val r=Rig(bia=true,mf=true,sources=listOf("MAGNETIC_FIELD"))
        r.sensors.emit("MF_BIA",listOf(mapOf("STATUS" to 23,"PROGRESS" to 50f)));r.queue.runReady()
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("50%"))
        r.queue.advance(5000)
        val row=buildMap<String,Any> {
            put("STATUS",0)
            listOf("5K","10K","50K","250K").forEach { put("BODY_IMPEDANCE_MAGNITUDE_$it",500f);put("BODY_IMPEDANCE_PHASE_$it",-8f) }
        }
        r.sensors.emit("MF_BIA",listOf(row));r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("MF_BIA");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        assertEquals(2L,r.session.counts()["PRE-200/MF_BIA"])
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        assertEquals(listOf("MF_BIA","MAGNETIC_FIELD"),r.sensors.starts)
    }
    @Test fun bothBiaModesRunTwiceInOnePhase() {
        val r=Rig(bia=true,both=true,sources=listOf("MAGNETIC_FIELD"))
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("PRE-201",ActivityRecordingService.state.phase)
        assertEquals("多频阻抗",ActivityRecordingService.state.biaMode)
        val row=buildMap<String,Any> {
            put("STATUS",0)
            listOf("5K","10K","50K","250K").forEach { put("BODY_IMPEDANCE_MAGNITUDE_$it",500f);put("BODY_IMPEDANCE_PHASE_$it",-8f) }
        }
        r.sensors.emit("MF_BIA",listOf(row));r.queue.runReady()
        r.sensors.ack("MF_BIA");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["PRE-200/BIA"])
        assertEquals(1L,r.session.counts()["PRE-201/MF_BIA"])
    }
    @Test fun biaContactErrorWaitsAndRetriesThenMeasures() {
        val r=Rig(bia=true)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 9,"PROGRESS" to 0f)));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("WAIT_BIA_PRE",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.biaRetrying)
        assertEquals(1,ActivityRecordingService.state.biaAttempt)
        r.queue.advance(5000)
        assertEquals("PRE-201",ActivityRecordingService.state.phase)
        assertEquals(2,ActivityRecordingService.state.biaAttempt)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["PRE-200/BIA"]);assertEquals(1L,r.session.counts()["PRE-201/BIA"])
    }
    @Test fun touchDuringBiaRetryWaitDoesNotPostponeTheRetry() {
        val r=Rig(bia=true)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 9,"PROGRESS" to 0f)));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("WAIT_BIA_PRE",ActivityRecordingService.state.phase)
        r.service.command("TOUCH");r.queue.runReady()
        r.queue.advance(5000)
        assertEquals("PRE-201",ActivityRecordingService.state.phase)
    }

    @Test fun biaMidMeasurementFailureKeepsLastProgressAndRetryState() {
        val r=Rig(bia=true)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 0,"PROGRESS" to 0.2f)));r.queue.runReady()
        assertEquals(20,ActivityRecordingService.state.biaProgress)
        assertTrue(ActivityRecordingService.state.biaContact)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 9,"PROGRESS" to 0f)));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("WAIT_BIA_PRE",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.biaRetrying)
        assertEquals(20,ActivityRecordingService.state.biaProgress)
        assertTrue(ActivityRecordingService.state.text.contains("20%"))
    }
    @Test fun biaNoContactGetsFullReviewBudgetAfterMeasurementTimeout() {
        val r=Rig(bia=true)
        r.queue.advance(60000);r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("REVIEW_BIA_PRE",ActivityRecordingService.state.phase)
        // The review is a separate 80-second decision window; the measurement timeout
        // must not consume it.
        r.queue.advance(80000)
        assertTrue(r.seal().exists())
    }
    @Test fun biaStartsImmediatelyWithoutCountdown() {
        val r=Rig(bia=true)
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        assertEquals(listOf("BIA"),r.sensors.starts)
        r.service.command("CANCEL");r.queue.runReady();r.sensors.ack("BIA");r.queue.runReady()
        assertTrue(r.seal().exists())
    }
    @Test fun edaBoundaryCompletionPersistsTailBeforePostPhaseAndIgnoresLateFlush() {
        val r=Rig(spo2=true,sources=listOf("EDA","PRESSURE"))
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 97)));r.queue.runReady()
        r.sensors.ack("SPO2");r.queue.runReady()
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end()
        val late=r.sensors.drains.getValue("EDA")
        r.sensors.emit("EDA",listOf(mapOf("sdkTimestamp" to 10500L,"SKIN_CONDUCTANCE" to 3.5f,"STATUS" to 0)))
        r.sensors.ack("EDA",SensorPort.DrainStatus.END_TIME_COVERED);r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("READY_SPO2_POST",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["CORE-1/EDA"])
        late.accept(SensorPort.DrainStatus.FLUSHED,"late vendor ack");r.queue.runReady()
        assertEquals("READY_SPO2_POST",ActivityRecordingService.state.phase)
    }
    @get:Rule val temp=TemporaryFolder()
    private class Queue:CaptureQueue {
        data class Entry(val at:Long,val seq:Long,val action:Runnable)
        var now=1L;var closed=false;private var sequence=0L
        private val jobs=mutableListOf<Entry>()
        override fun post(action:Runnable)=postDelayed(action,0)
        override fun postDelayed(action:Runnable,delayMs:Long):Boolean {
            if(closed)return false
            jobs.add(Entry(now+delayMs,sequence++,action));return true
        }
        override fun removeCallbacks(action:Runnable) {jobs.removeAll {it.action===action}}
        override fun close() {closed=true;jobs.removeAll {it.at>now}}
        fun runReady() {
            var count=0
            while(true) {
                val job=jobs.filter {it.at<=now}.minWithOrNull(compareBy<Entry> {it.at}.thenBy {it.seq}) ?: return
                check(++count<10000) {"test queue did not quiesce"}
                jobs.remove(job);job.action.run()
            }
        }
        fun advance(ms:Long){now+=ms;runReady()}
    }
    private class Sensors(val owner:ActivityRecordingService):SensorPort {
        val rows=mutableMapOf<String,SensorPort.Rows>()
        val drains=mutableMapOf<String,SensorPort.Drain>()
        val starts=mutableListOf<String>();val stops=mutableListOf<String>()
        val failStarts=mutableSetOf<String>()
        var closes=0;var throwDrain=false
        override fun contractVersion()=SensorPort.CONTRACT_VERSION
        override fun implementationVersion()="host-fake/2"
        override fun connect(ready:Runnable,failed:SensorPort.Failure){owner.enqueue {ready.run()}}
        override fun start(sensor:String,data:SensorPort.Rows,failed:SensorPort.Failure){
            starts.add(sensor)
            if (sensor in failStarts) { owner.enqueue { failed.accept("fake start failure") }; return }
            rows[sensor]=data
        }
        override fun drain(sensor:String,done:SensorPort.Drain) {
            if(throwDrain)throw IOException("injected drain failure")
            check(!drains.containsKey(sensor));drains[sensor]=done
        }
        override fun stop(sensor:String){stops.add(sensor)}
        override fun close(){closes++}
        fun emit(sensor:String,data:List<Map<String,Any?>>) {val receiver=rows.getValue(sensor);owner.enqueueRows(data) {receiver.accept(data)}}
        fun ack(sensor:String, status:SensorPort.DrainStatus?=null) {
            val done=drains.remove(sensor) ?: error("no pending drain")
            owner.enqueue {done.accept(status ?: if(sensor=="MF_BIA" || sensor=="BIA" || sensor=="ECG" || sensor=="SPO2" || sensor=="PPG_ON_DEMAND" || sensor=="GPS")SensorPort.DrainStatus.DELIVERED_ONLY else SensorPort.DrainStatus.FLUSHED,"fake ack")}
        }
    }
    private inner class Rig(ecg:Boolean=false,autoRun:Boolean=true,diskOwner:Boolean=false,spo2:Boolean=false,bia:Boolean=false,mf:Boolean=false,ppg:Boolean=false,both:Boolean=false,sources:List<String> = listOf("HEART_RATE","PPG"),
        preEcg:Boolean=ecg,postEcg:Boolean=ecg,preSpo2:Boolean=spo2,postSpo2:Boolean=spo2,prePpg:Boolean=ppg,postPpg:Boolean=ppg,
        ppgFirstForLed:Boolean=false) {
        val queue=Queue();val service=ActivityRecordingService();val sensors=Sensors(service)
        @Volatile var failDisk=false
        @Volatile var diskHook: (() -> Unit)? = null
        val store=ActivityStore(temp.newFolder()){diskHook?.invoke();if(failDisk)throw IOException("injected disk failure")}
        val template=store.createTemplate("宿主收尾",preEcg,postEcg,sources,preSpo2,postSpo2,
            if(bia)BiaProfile(170f,65f,30,1)else null,mf,both,prePpg,postPpg)
        val session=store.start(template,1000,1)
        init {
            service.writer=queue;service.clock={queue.now};service.useDiskOwner=diskOwner
            service.drainPpgFirstForLed=ppgFirstForLed
            service.beginSession(session,template,sensors)
            if(autoRun)queue.runReady()
        }
        fun end(){service.command("END");queue.runReady()}
        fun seal()=File(session.folder,"seal.json")
        fun preComplete() {
            repeat(1500) {seq->
                sensors.emit("ECG",List(10){index->buildMap<String,Any> {
                    put("sdkTimestamp",seq*20L);put("ECG_MV",0.25)
                    put("captureCallbackSequence",seq+1L);put("captureCallbackIndex",index);put("captureCallbackSize",10)
                    if(index==0){put("LEAD_OFF",0);put("MIN_THRESHOLD_MV",-5.0);put("MAX_THRESHOLD_MV",5.0)}
                }})
                queue.runReady()
            }
            assertEquals("DRAINING",ActivityRecordingService.state.phase)
        }
    }
    @Test fun slowDiskWithElevenSourcesDoesNotBlockCaptureOrLoseAcceptedSamples() {
        val sources=listOf("HEART_RATE","PPG","ACCELEROMETER","EDA","SKIN_TEMPERATURE","GYROSCOPE","PRESSURE","MAGNETIC_FIELD","LIGHT","GPS","STEP_COUNT")
        val r=Rig(diskOwner=true,sources=sources)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        r.diskHook={ entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to -7)))
            r.queue.runReady();r.queue.advance(1000)
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            repeat(20) { burst ->
                repeat(25) { sample -> sources.forEach { sensor ->
                    r.sensors.emit(sensor,listOf(mapOf("sdkTimestamp" to (2000L+burst*1000+sample),"STATUS" to -1,"rawFixtureValue" to sample)))
                } }
                r.queue.runReady();r.queue.advance(1000)
                assertTrue(ActivityRecordingService.state.active)
            }
        } finally {release.countDown();r.diskHook=null}
        r.end();sources.forEach {r.sensors.ack(it)};r.queue.runReady()
        assertTrue(r.seal().exists());assertEquals(1,r.sensors.closes)
        sources.forEach {assertEquals(if(it=="PPG")501L else 500L,r.session.counts()["CORE-1/$it"])}
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }
    /** Opt-in private corpus: never embeds health records in source or test reports.
     * Replays saved CORE rows, not SDK/hardware or missing uncommitted tails. */
    @Test fun privateThreeActivityCorpusPreservesEverySavedCoreField() {
        val input=System.getenv("RAWWAVE_REPLAY_INPUT")
        org.junit.Assume.assumeTrue("Private corpus not configured",!input.isNullOrBlank())
        val files=File(input!!).listFiles()!!.filter { it.name.startsWith("RawWave-activities-2026-10-30_") && it.extension=="zip" }.sortedBy {it.name}
        assertEquals("Expected the three supplied activity archives",3,files.size)
        val gson=com.google.gson.GsonBuilder().serializeNulls()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create()
        fun canonical(e:com.google.gson.JsonElement):String = when {
            e.isJsonNull -> "null"
            e.isJsonObject -> e.asJsonObject.entrySet().sortedBy {it.key}.joinToString(",","{","}") {gson.toJson(it.key)+":"+canonical(it.value)}
            e.isJsonArray -> e.asJsonArray.joinToString(",","[","]") {canonical(it)}
            e.asJsonPrimitive.isNumber -> java.math.BigDecimal(e.asString).stripTrailingZeros().toPlainString()
            else -> gson.toJson(e)
        }
        fun digest()=java.security.MessageDigest.getInstance("SHA-256")
        fun feed(d:java.security.MessageDigest,e:com.google.gson.JsonElement) {d.update((canonical(e)+"\n").toByteArray(Charsets.UTF_8))}
        val rowType=object:com.google.gson.reflect.TypeToken<List<Map<String,Any?>>>(){}.type
        files.forEach { archive ->
            val rowsBySensor=linkedMapOf<String,MutableList<com.google.gson.JsonElement>>()
            java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter {it.name.contains("-CORE-1-") && !it.name.endsWith("-EVENT.json")}.sortedBy {it.name}.forEach {entry ->
                    val block=zip.getInputStream(entry).reader().use {com.google.gson.JsonParser.parseReader(it).asJsonObject}
                    rowsBySensor.getOrPut(block["sensor"].asString){mutableListOf()}.addAll(block["samples"].asJsonArray.toList())
                }
            }
            if(archive.name.contains("11-59-15")) {
                val cutoff=1793334551466L;val mono=93967374L
                val boundary=EdaEndBoundary()
                val eda=rowsBySensor.getValue("EDA")
                // Model the last pre-cutoff callback waiting on the owner when drain arms.
                val queued=eda.indexOfLast {it.asJsonObject["receivedUtcMs"].asLong<=cutoff}
                assertTrue(queued>=0)
                var covered=false
                eda.forEachIndexed {index,row ->
                    if(index==queued)boundary.arm(cutoff,mono)
                    val values:List<Map<String,Any?>> = gson.fromJson(gson.toJson(listOf(row)),rowType)
                    covered=boundary.observe(values) || covered
                }
                assertTrue("Actual saved EDA covers the stop boundary even with a queued pre-cutoff callback",covered)
                println("PRIVATE_EDA_BOUNDARY savedRows=${eda.size} queuedPreCutoffScenarioCovered=true;notSdkFlushAck=true")
            }
            assertTrue(rowsBySensor.isNotEmpty())
            val expected=rowsBySensor.mapValues {(_,rows)->digest().also {d->rows.forEach {feed(d,it)}}.digest()}
            val batches=mutableListOf<Pair<String,List<com.google.gson.JsonElement>>>()
            rowsBySensor.forEach { (sensor,rows) ->
                var batch=mutableListOf<com.google.gson.JsonElement>();var key:String?=null
                rows.forEach { row ->
                    val obj=row.asJsonObject
                    val next=obj["captureCallbackSequence"]?.toString() ?: obj["sensorTimestampNs"]?.toString() ?: obj["sdkTimestamp"].toString()
                    if(key!=next && batch.isNotEmpty()){batches.add(sensor to batch);batch=mutableListOf()}
                    key=next;batch.add(row)
                }
                if(batch.isNotEmpty())batches.add(sensor to batch)
            }
            batches.sortBy {it.second.first().asJsonObject["receivedElapsedMs"]?.asLong ?: 0L}
            val r=Rig(diskOwner=true,sources=rowsBySensor.keys.toList())
            var pendingRows=0;var pendingBatches=0;var submittedRows=0L
            fun pump() {
                r.queue.runReady()
                // Offline replay is faster than acquisition. Bound the replay lead over
                // disk, while still exercising 500 pending callback bursts. No production throttling.
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                while(r.session.counts().filterKeys {it!="CORE-1/EVENT"}.values.sum()<submittedRows-8000) {
                    assertTrue("Replay stopped: "+r.service.terminalReason,ActivityRecordingService.state.active)
                    assertTrue("Replay disk catch-up timeout",System.nanoTime()<deadline)
                    Thread.sleep(2)
                }
            }
            batches.forEach { (sensor,rows) ->
                if(pendingRows+rows.size>4000 || pendingBatches>=500) {pump();pendingRows=0;pendingBatches=0}
                val values:List<Map<String,Any?>> = gson.fromJson(gson.toJson(rows),rowType)
                r.sensors.emit(sensor,values);pendingRows+=rows.size;pendingBatches++;submittedRows+=rows.size
            }
            r.end();rowsBySensor.keys.forEach {r.sensors.ack(it)};r.queue.runReady()
            assertTrue("Corpus activity must seal: "+r.service.terminalReason,r.seal().exists());assertEquals(1,r.sensors.closes)
            val actual=rowsBySensor.keys.associateWith {digest()}
            r.session.folder.listFiles()!!.filter {it.name.contains("-CORE-1-") && !it.name.endsWith("-EVENT.json")}.sortedBy {it.name}.forEach {file ->
                val block=file.reader().use {com.google.gson.JsonParser.parseReader(it).asJsonObject}
                block["samples"].asJsonArray.forEach {feed(actual.getValue(block["sensor"].asString),it)}
            }
            rowsBySensor.forEach { (sensor,rows) ->
                assertEquals("Saved row count: $sensor",rows.size.toLong(),r.session.counts()["CORE-1/$sensor"])
                assertArrayEquals("Every original field and per-source order: $sensor",expected.getValue(sensor),actual.getValue(sensor).digest())
            }
            println("PRIVATE_CORE_REPLAY rows=${rowsBySensor.values.sumOf {it.size}} callbacks=${batches.size} sources=${rowsBySensor.size} allFieldsAndOrderMatch=true")
        }
    }
    @Test fun backlogLimitStopsButRecoverySavesAlreadyBufferedRows() {
        val r=Rig(diskOwner=true,sources=listOf("PPG"))
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        r.diskHook={entered.countDown();check(release.await(30,java.util.concurrent.TimeUnit.SECONDS))}
        val value="x".repeat(8192)
        var submitted=0L
        try {
            while(ActivityRecordingService.state.phase=="CORE-1" && submitted<5000) {
                r.sensors.emit("PPG",List(50){mapOf("sdkTimestamp" to submitted+it,"PPG_GREEN" to -1,"rawFixture" to value)})
                submitted+=50;r.queue.runReady()
            }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("DRAINING",ActivityRecordingService.state.phase)
        } finally {release.countDown();r.diskHook=null}
        r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.service.terminalReason,r.seal().exists())
        assertTrue(r.service.terminalReason.contains("PERSISTENCE_BACKLOG_LIMIT"))
        assertFalse(r.service.terminalReason.contains("NOT_SEALED"))
        assertEquals(submitted,r.session.counts()["CORE-1/PPG"])
        assertEquals(1,r.sensors.closes)
    }
    @Test fun removingTaskDuringFinalDrainSuppressesNewSyncAfterSeal() {
        val r=Rig(diskOwner=true)
        r.service.autoSyncAfterCapture = true
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to -1)));r.queue.runReady()
        r.end();r.service.onTaskRemoved(null);r.queue.runReady()
        r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists());assertEquals(1,r.sensors.closes)
        assertEquals(1L,r.session.counts()["CORE-1/PPG"])
        assertFalse(r.service.shouldStartFinishSync(true))
    }
    @Test fun normalCompletionCanSyncButUnsealedDataCannot() {
        val r=Rig(diskOwner=true)
        r.service.autoSyncAfterCapture = true
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to -1)))
        r.queue.runReady()
        r.end();r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists());assertTrue(r.service.shouldStartFinishSync(true))
        assertFalse(r.service.shouldStartFinishSync(false))
        // Also covers task removal after the owner queued the main-thread handoff.
        r.service.onTaskRemoved(null)
        assertFalse(r.service.shouldStartFinishSync(true))
    }
    @Test fun noCoreDataCannotStartFinishSync() {
        val r=Rig(diskOwner=true)
        r.service.autoSyncAfterCapture = true
        r.end();r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertFalse(r.service.shouldStartFinishSync(true))
    }
    @Test fun coreNoDataTimeoutStopsAndExitsAfter80Seconds() {
        val r=Rig(diskOwner=true)
        r.service.autoSyncAfterCapture = true
        r.queue.advance(79_999)
        assertTrue(ActivityRecordingService.state.active)
        assertFalse(r.seal().exists())
        r.queue.advance(1_000)
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertTrue(r.service.terminalReason.contains("CORE_NO_DATA_TIMEOUT_80S"))
        assertFalse(r.service.shouldStartFinishSync(true))
        assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun firstCoreRowDisarmsNoDataTimeout() {
        val r=Rig(diskOwner=true)
        r.service.autoSyncAfterCapture = true
        r.queue.advance(30_000)
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to -1)))
        r.queue.runReady()
        r.queue.advance(60_000)
        assertTrue(ActivityRecordingService.state.active)
        assertFalse(r.seal().exists())
        r.end();r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertTrue(r.service.shouldStartFinishSync(true))
    }
    @Test fun thousandQueuedRawCallbacksSurviveOld256LimitAndDrainInOrder() {
        val r=Rig(diskOwner=true)
        repeat(1000) { n -> r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to n.toLong(),"PPG_GREEN" to -n))) }
        // END is queued behind all samples, with no owner execution during admission.
        r.end();r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertTrue(r.seal().exists());assertEquals(1000L,r.session.counts()["CORE-1/PPG"])
        assertEquals(1,r.sensors.closes)
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }
    @Test fun elevenSourceBurstsUseProductionDiskOwnerAndSealEveryAcceptedRow() {
        val sources=listOf("HEART_RATE","PPG","ACCELEROMETER","EDA","SKIN_TEMPERATURE","GYROSCOPE","PRESSURE","MAGNETIC_FIELD","LIGHT","GPS","STEP_COUNT")
        val r=Rig(diskOwner=true,sources=sources)
        repeat(100) { burst ->
            // 200 callbacks per burst; all sources share the actual capture service FIFO.
            repeat(25) { sample -> sources.forEach { sensor ->
                r.sensors.emit(sensor,listOf(mapOf("sdkTimestamp" to (burst*1000L+sample),"STATUS" to -1,"rawFixtureValue" to sample)))
            } }
            r.queue.runReady();r.queue.advance(1000)
            assertTrue(ActivityRecordingService.state.active)
        }
        r.end();sources.forEach {r.sensors.ack(it)};r.queue.runReady()
        assertTrue(r.seal().exists());assertEquals(1,r.sensors.closes)
        sources.forEach {assertEquals(2500L,r.session.counts()["CORE-1/$it"])}
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }
    @Test fun asyncDiskFailureClosesSensorsAndCannotSeal() {
        val r=Rig(diskOwner=true)
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to 12)))
        r.queue.runReady();r.failDisk=true
        r.end();r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertFalse(r.seal().exists());assertEquals(1,r.sensors.closes)
        assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun removingTaskStopsAsyncCaptureAndNeverStartsPostMeasurement() {
        val r=Rig(diskOwner=true)
        r.service.onTaskRemoved(null);r.queue.runReady()
        r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertEquals(1,r.sensors.closes);assertTrue(r.seal().exists())
        assertFalse(ActivityRecordingService.state.active)
        assertTrue(r.seal().readText().contains("USER_INTERRUPTED"))
    }
    @Test fun coreEndWaitsForBothSourcesAndPersistsQueuedTail() {
        val r=Rig();r.end();r.service.command("END");r.queue.runReady()
        r.sensors.emit("HEART_RATE",listOf(mapOf("sdkTimestamp" to 123L,"HEART_RATE" to 70)))
        r.sensors.ack("HEART_RATE");r.queue.runReady();assertFalse(r.seal().exists())
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 124L,"PPG_GREEN" to 12)))
        r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.seal().exists());assertEquals(1,r.sensors.closes)
        assertEquals(1L,r.session.counts()["CORE-1/PPG"]);assertEquals(1L,r.session.counts()["CORE-1/HEART_RATE"])
    }
    @Test fun delayedConnectionCannotStartSensorsAfterAbsoluteDeadline() {
        val r=Rig(autoRun=false);r.queue.advance(20000)
        assertTrue(r.sensors.starts.isEmpty());assertEquals(1,r.sensors.closes)
        assertTrue(r.seal().readText().contains("SDK_CONNECT_TIMEOUT"))
    }
    @Test fun queuedDrainAckCannotAdvanceAfterAbsoluteDeadline() {
        val r=Rig(true);r.preComplete();r.sensors.ack("ECG")
        r.queue.advance(5000)
        assertEquals(listOf("ECG"),r.sensors.starts);assertEquals(1,r.sensors.closes)
        assertTrue(r.seal().readText().contains("DRAIN_INCOMPLETE"))
        assertEquals(15000L,r.session.counts()["PRE-1/ECG"])
    }
    @Test fun timeoutClosesSensorsAndLateAckCannotReviveCapture() {
        val r=Rig();r.end();r.queue.advance(5000)
        assertTrue(r.seal().readText().contains("DRAIN_INCOMPLETE"));assertEquals(1,r.sensors.closes)
        r.sensors.ack("PPG");r.sensors.ack("HEART_RATE");r.queue.runReady()
        assertEquals(1,r.sensors.closes);assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun forcedSkipPreEcgStartsCoreAndKeepsPostEcg() {
        val r=Rig(ecg=true,sources=listOf("PPG"))
        assertEquals("PRE-1",ActivityRecordingService.state.phase)
        r.service.command("TEST_SKIP_PRE_ECG");r.queue.runReady()
        r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 1L,"PPG_GREEN" to 1)));r.queue.runReady()
        r.end();r.queue.runReady()
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        assertFalse(r.sensors.drains.containsKey("PPG"))
    }
    @Test fun preEcgCannotStartCoreUntilAcceptedTailIsSaved() {
        val r=Rig(true);r.preComplete();assertEquals(listOf("ECG"),r.sensors.starts)
        r.sensors.emit("ECG",listOf(mapOf("sdkTimestamp" to 30001L,"ECG_MV" to 0.2)))
        r.sensors.ack("ECG");r.queue.runReady()
        assertEquals(15001L,r.session.counts()["PRE-1/ECG"])
        assertTrue(r.sensors.starts.contains("HEART_RATE"));assertFalse(r.seal().exists())
    }
    @Test fun destructionDuringPreDrainSavesTailButNeverStartsCore() {
        val r=Rig(true);r.preComplete();r.service.onDestroy()
        r.sensors.emit("ECG",listOf(mapOf("sdkTimestamp" to 30001L,"ECG_MV" to 0.2)))
        r.queue.runReady();assertFalse(r.queue.closed)
        r.sensors.ack("ECG");r.queue.runReady()
        assertTrue(r.queue.closed);assertEquals(listOf("ECG"),r.sensors.starts)
        assertEquals(15001L,r.session.counts()["PRE-1/ECG"])
        assertTrue(r.seal().readText().contains("SERVICE_DESTROYED"));assertEquals(1,r.sensors.closes)
    }
    @Test fun overflowLatchPreventsQueuedNormalDrainFromAdvancing() {
        val r=Rig(true);r.preComplete()
        r.sensors.ack("ECG") // completion queued BEFORE overflow notification
        repeat(257){r.service.enqueue {}}
        r.queue.runReady()
        assertEquals(listOf("ECG"),r.sensors.starts)
        assertTrue(r.seal().readText().contains("CALLBACK_QUEUE_OVERFLOW"));assertEquals(1,r.sensors.closes)
    }
    @Test fun diskFailureCannotSkipCleanupOrClaimSealed() {
        val r=Rig();r.failDisk=true;r.end();r.queue.advance(5000)
        assertEquals(1,r.sensors.closes);assertFalse(r.seal().exists())
        assertTrue(r.sensors.stops.containsAll(listOf("HEART_RATE","PPG")))
        assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun thrownDrainStillClosesAndKeepsExplicitFailure() {
        val r=Rig();r.sensors.throwDrain=true;r.end()
        assertTrue(r.seal().readText().contains("DRAIN_INCOMPLETE"));assertEquals(1,r.sensors.closes)
    }
    @Test fun unusedPreEcgStopsAfter80SecondsWithoutReviewOrCore() {
        val r=Rig(true);r.queue.advance(80000)
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("ECG");r.queue.runReady()
        val seal=r.seal().readText()
        assertTrue(seal.contains("ECG_IDLE_TIMEOUT_80S") || seal.contains("SHORT_MEASUREMENT_TIMEOUT_80S"))
        assertEquals(listOf("ECG"),r.sensors.starts);assertEquals(1,r.sensors.closes)
        assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun ecgContactAndContinuityResetRenewTheNoContactBudget() {
        val r = Rig(true)
        fun batch(sequence: Int, firstTimestampMs: Long) = List(10) { index ->
            buildMap<String, Any> {
                put("sdkTimestamp", firstTimestampMs + index)
                put("ECG_MV", 0.25)
                put("captureCallbackSequence", sequence.toLong())
                put("captureCallbackIndex", index)
                put("captureCallbackSize", 10)
                if (index == 0) {
                    put("LEAD_OFF", 0)
                    put("MIN_THRESHOLD_MV", -5.0)
                    put("MAX_THRESHOLD_MV", 5.0)
                }
            }
        }
        r.queue.advance(70_000)
        r.sensors.emit("ECG", batch(1, 0)); r.queue.runReady()
        r.queue.advance(1_000)
        assertTrue(ActivityRecordingService.state.contact)
        r.queue.advance(4_000)
        // A >100 ms callback gap resets the strict segment but must not reuse the original
        // phase-start budget: the user is still in contact and retrying.
        r.sensors.emit("ECG", batch(2, 200_000)); r.queue.runReady()
        r.queue.advance(10_000)
        assertTrue(ActivityRecordingService.state.active)
        assertNotEquals("DRAINING", ActivityRecordingService.state.phase)
        r.service.command("CANCEL"); r.queue.runReady()
        r.sensors.ack("ECG"); r.queue.runReady()
        assertTrue(r.seal().exists())
    }

    @Test fun missingCoreAckStillSavesRowsThenRunsPostEcgWithPersistentWarning() {
        val r=Rig(true,sources=listOf("HEART_RATE","PPG","GYROSCOPE"))
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        r.end();r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 30002L,"PPG_GREEN" to 3)))
        r.sensors.ack("HEART_RATE");r.queue.advance(5000)
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["CORE-1/PPG"])
        assertTrue(ActivityRecordingService.state.text.contains("\u5c3e\u90e8\u672a\u786e\u8ba4"))
        assertFalse(r.seal().exists())
        // Late completion belongs to the old core drain and cannot finish POST.
        r.sensors.ack("GYROSCOPE");r.queue.runReady()
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertTrue(r.sensors.drains.containsKey("PPG"))
        r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.session.counts().getValue("POST-1/ECG") >= 15000)
        assertTrue(r.seal().readText().contains("CORE_TAIL_UNCONFIRMED"))
        assertEquals(1,r.sensors.closes)
    }
    @Test fun coreSaveFailureNeverUsesPartialContinuation() {
        val r=Rig(true);r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        r.end();r.failDisk=true;r.queue.advance(5000)
        assertEquals(1,r.sensors.starts.count {it=="ECG"})
        assertEquals(1,r.sensors.closes);assertFalse(r.seal().exists())
    }
    @Test fun preparedSpo2ThenRestoredEcgThenGyroThenPostEcgAndPreparedSpo2() {
        val r=Rig(ecg=true,spo2=true,sources=listOf("GYROSCOPE"))
        assertTrue(r.sensors.starts.isEmpty())
        r.service.command("START_SPO2");r.queue.runReady()
        assertEquals("SPO2",ActivityRecordingService.state.shortSensor)
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to 2,"SPO2" to 98,"HEART_RATE" to 70)))
        r.queue.runReady();assertFalse(r.sensors.starts.contains("GYROSCOPE"))
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals(1L,r.session.counts()["PRE-100/SPO2"])
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        assertEquals(listOf("SPO2"),r.sensors.starts)
        r.queue.advance(60000)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        assertEquals("PRE-1",ActivityRecordingService.state.phase)
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        r.sensors.emit("GYROSCOPE",listOf(mapOf("sensorTimestampNs" to 123456789L,"sdkTimestamp" to 123L,"GYROSCOPE_X" to 0.1,"GYROSCOPE_Y" to -0.2,"GYROSCOPE_Z" to 0.3)))
        r.queue.runReady();r.end();r.sensors.ack("GYROSCOPE");r.queue.runReady()
        assertEquals(1L,r.session.counts()["CORE-1/GYROSCOPE"])
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        assertEquals("READY_SPO2_POST",ActivityRecordingService.state.phase)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 2L,"STATUS" to 2,"SPO2" to 97)))
        r.queue.runReady();assertFalse(r.seal().exists())
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals(1L,r.session.counts()["POST-100/SPO2"]);assertTrue(r.seal().exists())
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }
    @Test fun spo2TimeoutStopsBeforeReviewAndIdleDoesNotRestartMeasurement() {
        val r=Rig(spo2=true);r.service.command("START_SPO2");r.queue.runReady();r.queue.advance(30000)
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("REVIEW_SPO2_PRE",ActivityRecordingService.state.phase)
        assertEquals(listOf("SPO2"),r.sensors.starts)
        r.queue.advance(80000)
        val seal=r.seal().readText()
        assertTrue(seal.contains("SHORT_MEASUREMENT_TIMEOUT_80S") || seal.contains("SPO2_REVIEW_IDLE_TIMEOUT"));assertEquals(1,r.sensors.closes)
    }
    @Test fun spo2FailedStatusIsSavedAndExplicitSkipContinues() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to -4,"SPO2" to 0)))
        r.queue.runReady();r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals(1L,r.session.counts()["PRE-100/SPO2"])
        r.service.command("SKIP_SPO2");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
    }
    @Test fun spo2LateSuccessCannotReviveExpiredMeasurement() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to 2,"SPO2" to 98)))
        r.queue.advance(30000);r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("REVIEW_SPO2_PRE",ActivityRecordingService.state.phase)
        assertFalse(r.sensors.starts.contains("HEART_RATE"))
    }
    @Test fun preparationAutoStartsSpo2AndLateTapCannotRestart() {
        val r=Rig(spo2=true)
        assertEquals("READY_SPO2_PRE",ActivityRecordingService.state.phase)
        r.queue.advance(15000)
        assertEquals(listOf("SPO2"),r.sensors.starts)
        r.queue.advance(30000);r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("REVIEW_SPO2_PRE",ActivityRecordingService.state.phase)
        r.queue.advance(35000)
        r.service.command("START_SPO2");r.queue.runReady()
        assertEquals(1,r.sensors.starts.size)
        // The review gets its own full 80 seconds; the preparation/measurement budget
        // cannot cut it short.
        r.queue.advance(45000)
        val seal=r.seal().readText()
        assertTrue(seal.contains("SPO2_REVIEW_IDLE_TIMEOUT") || seal.contains("SHORT_MEASUREMENT_TIMEOUT_80S"))
    }
    @Test fun spo2RetryStartsImmediatelyAndDuplicateStartDoesNotRestartSensor() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.service.command("START_SPO2");r.queue.runReady()
        assertEquals(listOf("SPO2"),r.sensors.starts)
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to -4,"SPO2" to 0)))
        r.queue.runReady();r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("检测到移动"))
        r.service.command("RETRY_SPO2");r.queue.runReady()
        assertEquals("PRE-101",ActivityRecordingService.state.phase)
        assertEquals(2,r.sensors.starts.size)
        r.queue.advance(30000)
        assertFalse(ActivityRecordingService.state.text.contains("\u6682\u65e0\u65b0\u6570\u636e"))
    }
    @Test fun restoreAutoStartsCoreAfter15SecondsAndArchiveRemainsValid() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 98)))
        r.queue.runReady();r.sensors.ack("SPO2");r.queue.runReady()
        r.queue.advance(15000)
        assertEquals("CORE-1",ActivityRecordingService.state.phase)
        assertTrue(r.sensors.starts.contains("HEART_RATE"))
        r.service.command("CANCEL");r.queue.runReady()
        r.sensors.ack("HEART_RATE");r.sensors.ack("PPG");r.queue.runReady()
        assertTrue(r.seal().exists())
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }

    @Test fun gpsDeliveredOnlyDrainIsAcceptedWhileOtherSourcesFlushAndSeal() {
        val r=Rig(sources=listOf("GPS","LIGHT","PRESSURE"))
        r.sensors.emit("GPS",listOf(gpsRow(1000)))
        r.sensors.emit("LIGHT",listOf(mapOf("sdkTimestamp" to 1000L,"LIGHT_LUX" to 20f)))
        r.queue.runReady()
        r.end()
        r.sensors.ack("GPS",SensorPort.DrainStatus.DELIVERED_ONLY)
        r.sensors.ack("LIGHT")
        r.sensors.ack("PRESSURE")
        r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/GPS"])
        assertFalse(r.seal().readText().contains("DRAIN_INCOMPLETE"))
    }
    @Test fun gpsFixAcceptedBeforeDeliveredOnlyCompletionIsPersistedUnderCorePhase() {
        val r=Rig(sources=listOf("GPS","PRESSURE"))
        r.sensors.emit("GPS",listOf(gpsRow(1000)))
        r.queue.runReady()
        r.end()
        // A callback already accepted before the drain completion still belongs to CORE-1.
        r.sensors.emit("GPS",listOf(gpsRow(2000)))
        r.sensors.ack("GPS",SensorPort.DrainStatus.DELIVERED_ONLY)
        r.sensors.ack("PRESSURE")
        r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(2L,r.session.counts()["CORE-1/GPS"])
        assertFalse(r.session.counts().keys.any { it.contains("POST") })
    }
    @Test fun gpsStartFailureLeavesOtherSourcesRunningAndDoesNotCorruptSeal() {
        val r=Rig(autoRun=false,sources=listOf("GPS","LIGHT","PRESSURE"))
        r.sensors.failStarts.add("GPS")
        r.queue.runReady()
        assertTrue(ActivityRecordingService.state.active)
        assertEquals(setOf("GPS","LIGHT","PRESSURE"),r.sensors.starts.toSet())
        r.sensors.emit("LIGHT",listOf(mapOf("sdkTimestamp" to 1L,"LIGHT_LUX" to 10f)))
        r.queue.runReady()
        r.end()
        r.sensors.ack("LIGHT");r.sensors.ack("PRESSURE");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(1L,r.session.counts()["CORE-1/LIGHT"])
        assertFalse(r.session.counts().containsKey("CORE-1/GPS"))
    }
    @Test fun stepCountFlushedDrainSealsWithCumulativeRowsAndKeepsLateRowsInCore() {
        val r=Rig(sources=listOf("STEP_COUNT","PRESSURE"))
        r.sensors.emit("STEP_COUNT",listOf(stepCountRow(1000,120f)))
        r.queue.runReady()
        r.end()
        // Accepted before completion: still belongs to CORE-1.
        r.sensors.emit("STEP_COUNT",listOf(stepCountRow(2000,128f)))
        r.sensors.ack("STEP_COUNT")
        r.sensors.ack("PRESSURE")
        r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(2L,r.session.counts()["CORE-1/STEP_COUNT"])
        assertFalse(r.seal().readText().contains("DRAIN_INCOMPLETE"))
    }
    @Test fun stepCountDeliveredOnlyIsExplicitFailureNotSilentSuccess() {
        val r=Rig(sources=listOf("STEP_COUNT","PRESSURE"))
        r.sensors.emit("STEP_COUNT",listOf(stepCountRow(1000,120f)))
        r.queue.runReady()
        r.end()
        r.sensors.ack("STEP_COUNT",SensorPort.DrainStatus.DELIVERED_ONLY)
        r.sensors.ack("PRESSURE")
        r.queue.runReady()
        assertTrue(r.seal().exists())
        assertTrue(r.seal().readText().contains("SDK_FLUSH_REQUIRED"))
        assertEquals(1L,r.session.counts()["CORE-1/STEP_COUNT"])
    }
    @Test fun sparseStepCounterCallbackIsNotReportedAsDataSilence() {
        val r=Rig(sources=listOf("STEP_COUNT"))
        r.queue.advance(20_000)
        assertTrue(ActivityRecordingService.state.active)
        assertFalse(ActivityRecordingService.state.text.contains("\u6682\u65e0\u65b0\u6570\u636e"))
        r.sensors.emit("STEP_COUNT",listOf(stepCountRow(21_000,121f)))
        r.end();r.sensors.ack("STEP_COUNT");r.queue.runReady()
        assertTrue(r.seal().exists())
    }
    private fun gpsRow(timestamp:Long,latitude:Double=31.2304)=mapOf<String,Any?>(
        "sensorTimestampNs" to timestamp*1_000_000,
        "sdkTimestamp" to timestamp,
        "GPS_LATITUDE" to latitude,
        "GPS_LONGITUDE" to 121.4737,
        "provider" to "gps")
    private fun stepCountRow(timestamp:Long,total:Float)=mapOf<String,Any?>(
        "sensorTimestampNs" to timestamp*1_000_000,
        "sdkTimestamp" to timestamp,
        "STEP_COUNT_TOTAL" to total,
        "accuracy" to 3,
        "stepCountKind" to "PLATFORM_CUMULATIVE_COUNTER_SINCE_BOOT_NOT_RAW_MOTION")
    @Test fun spo2CalculationRowIsNotDisplayedAsSuccessfulResultAndTimeoutRemainsFailure() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to 0,"SPO2" to 0)))
        r.queue.runReady();r.queue.advance(1000)
        assertTrue(ActivityRecordingService.state.text.contains("计算中，尚无有效结果"))
        assertFalse(ActivityRecordingService.state.text.contains("血氧：1"))
        assertFalse(ActivityRecordingService.state.text.contains("成功 0%"))
        r.queue.advance(29000);r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("\u672a\u6210\u529f\uff0c\u672a\u53d6\u5f97\u6709\u6548\u7ed3\u679c"))
        assertEquals(1L,r.session.counts()["PRE-100/SPO2"])
        r.service.command("RETRY_SPO2");r.queue.runReady()
        assertEquals("PRE-101",ActivityRecordingService.state.phase)
        assertEquals(2,r.sensors.starts.size)
        assertTrue(ActivityRecordingService.state.text.contains("\u6d4b\u91cf\u4e2d"))
    }
    @Test fun spo2SuccessShowsPercentageOnlyAfterPersistingAndDuplicateRowsRemainRaw() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady();r.queue.advance(17000)
        r.sensors.emit("SPO2",List(3){mapOf("sdkTimestamp" to 1L,"STATUS" to 2,"SPO2" to 99)})
        r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        assertFalse(ActivityRecordingService.state.text.contains("成功 99%"))
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("99%"))
        assertEquals(3L,r.session.counts()["PRE-100/SPO2"])
        assertFalse(ActivityRecordingService.state.text.contains("血氧：3"))
    }
    @Test fun spo2CompletedStatusWithZeroDoesNotBecomeValidResult() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to 2,"SPO2" to 0)))
        r.queue.runReady()
        assertEquals("PRE-100",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("尚无有效结果"))
        r.queue.advance(30000);r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("REVIEW_SPO2_PRE",ActivityRecordingService.state.phase)
    }

}
