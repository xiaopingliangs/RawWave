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
        r.queue.advance(79999);assertFalse(r.seal().exists())
        r.queue.advance(1001)
        assertTrue(r.seal().exists());assertEquals(listOf("ECG"),r.sensors.starts)
        assertEquals(1,r.sensors.closes)
    }
    private fun bodyRow(status:Int=0):Map<String,Any> = mapOf("STATUS" to status,"BODY_FAT_RATIO" to 20f,
        "BODY_FAT_MASS" to 13f,"SKELETAL_MUSCLE_MASS" to 28f,"TOTAL_BODY_WATER" to 40f,
        "sdkTimestamp" to 100L,"captureCallbackSequence" to 1L,"captureCallbackIndex" to 0,"captureCallbackSize" to 1)
    @Test fun oxygenIsFirstAndLastWithBiaInsideAndOneRestore() {
        val r=Rig(bia=true,spo2=true,sources=listOf("PRESSURE"))
        assertEquals("READY_SPO2_PRE",ActivityRecordingService.state.phase)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 97)));r.queue.runReady()
        r.sensors.ack("SPO2");r.queue.runReady()
        assertEquals("READY_BIA_PRE",ActivityRecordingService.state.phase)
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals(1L,r.session.counts()["PRE-200/BIA"])
        assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("READY_BIA_POST",ActivityRecordingService.state.phase)
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("READY_SPO2_POST",ActivityRecordingService.state.phase)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 99)));r.queue.runReady()
        r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(r.seal().exists())
        assertEquals(listOf("SPO2","BIA","PRESSURE","BIA","SPO2"),r.sensors.starts)
    }
    @Test fun biaStatusOnlyCannotSucceedAndRetryKeepsEachAttempt() {
        val r=Rig(bia=true)
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("BIA",listOf(mapOf("STATUS" to 0,"PROGRESS" to 0f)));r.queue.runReady()
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        r.queue.advance(60000);r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("REVIEW_BIA_PRE",ActivityRecordingService.state.phase)
        r.service.command("RETRY_BIA");r.queue.runReady()
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("BIA",listOf(bodyRow(9)));r.queue.runReady()
        r.sensors.ack("BIA");r.queue.runReady()
        assertEquals("REVIEW_BIA_PRE",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["PRE-200/BIA"]);assertEquals(1L,r.session.counts()["PRE-201/BIA"])
        r.service.command("SKIP_BIA");r.queue.runReady();assertEquals("RESTORE_PRE",ActivityRecordingService.state.phase)
    }
    @Test fun biaPostRunsAfterPressureDrainAndPreparationExpires() {
        val r=Rig(bia=true,sources=listOf("PRESSURE","GYROSCOPE"))
        r.service.command("SKIP_BIA");r.queue.runReady()
        r.service.command("PLACEMENT_READY");r.queue.runReady()
        r.end();r.sensors.ack("PRESSURE");r.queue.runReady()
        assertEquals("DRAINING",ActivityRecordingService.state.phase)
        r.sensors.ack("GYROSCOPE");r.queue.runReady()
        assertEquals("READY_BIA_POST",ActivityRecordingService.state.phase)
        r.queue.advance(80000);assertTrue(r.seal().exists());assertEquals(1,r.sensors.closes)
    }
    @Test fun biaDiskFailureNeverAdvancesToCoreOrBloodOxygen() {
        val r=Rig(bia=true)
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("BIA",listOf(bodyRow()));r.queue.runReady()
        r.failDisk=true;r.sensors.ack("BIA");r.queue.runReady()
        assertEquals(listOf("BIA"),r.sensors.starts);assertFalse(r.seal().exists())
    }
    @Test fun multiFrequencyProgressDoesNotFailAndSuccessEndsImmediately() {
        val r=Rig(bia=true,mf=true,sources=listOf("MAGNETIC_FIELD"))
        r.service.command("START_BIA");r.queue.runReady();r.queue.advance(5000)
        r.sensors.emit("MF_BIA",listOf(mapOf("STATUS" to 23,"PROGRESS" to 50f)));r.queue.runReady()
        assertEquals("PRE-200",ActivityRecordingService.state.phase)
        assertTrue(ActivityRecordingService.state.text.contains("50%"))
        r.queue.advance(2000)
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
    @Test fun bodyCountdownCanBeCancelledWithoutStartingHardware() {
        val r=Rig(bia=true)
        r.service.command("START_BIA");r.queue.runReady()
        assertEquals("ARM_BIA_PRE",ActivityRecordingService.state.phase)
        assertTrue(r.sensors.starts.isEmpty())
        r.queue.advance(4999);assertTrue(r.sensors.starts.isEmpty())
        r.service.command("CANCEL");r.queue.runReady();r.queue.advance(10000)
        assertTrue(r.sensors.starts.isEmpty());assertTrue(r.seal().exists())
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
        var closes=0;var throwDrain=false
        override fun contractVersion()=SensorPort.CONTRACT_VERSION
        override fun implementationVersion()="host-fake/2"
        override fun connect(ready:Runnable,failed:SensorPort.Failure){owner.enqueue {ready.run()}}
        override fun start(sensor:String,data:SensorPort.Rows,failed:SensorPort.Failure){starts.add(sensor);rows[sensor]=data}
        override fun drain(sensor:String,done:SensorPort.Drain) {
            if(throwDrain)throw IOException("injected drain failure")
            check(!drains.containsKey(sensor));drains[sensor]=done
        }
        override fun stop(sensor:String){stops.add(sensor)}
        override fun close(){closes++}
        fun emit(sensor:String,data:List<Map<String,Any>>) {val receiver=rows.getValue(sensor);owner.enqueue {receiver.accept(data)}}
        fun ack(sensor:String, status:SensorPort.DrainStatus?=null) {
            val done=drains.remove(sensor) ?: error("no pending drain")
            owner.enqueue {done.accept(status ?: if(sensor=="MF_BIA" || sensor=="BIA" || sensor=="ECG" || sensor=="SPO2")SensorPort.DrainStatus.DELIVERED_ONLY else SensorPort.DrainStatus.FLUSHED,"fake ack")}
        }
    }
    private inner class Rig(ecg:Boolean=false,autoRun:Boolean=true,spo2:Boolean=false,bia:Boolean=false,mf:Boolean=false,sources:List<String> = listOf("HEART_RATE","PPG")) {
        val queue=Queue();val service=ActivityRecordingService();val sensors=Sensors(service)
        var failDisk=false
        val store=ActivityStore(temp.newFolder()){if(failDisk)throw IOException("injected disk failure")}
        val template=store.createTemplate("宿主收尾",ecg,sources,spo2,if(bia)BiaProfile(170f,65f,30,1)else null,mf)
        val session=store.start(template,1000,1)
        init {
            service.writer=queue;service.clock={queue.now}
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
        assertTrue(r.seal().readText().contains("ECG_IDLE_TIMEOUT_80S"))
        assertEquals(listOf("ECG"),r.sensors.starts);assertEquals(1,r.sensors.closes)
        assertFalse(ActivityRecordingService.state.active)
    }
    @Test fun missingCoreAckStillSavesRowsThenRunsPostEcgWithPersistentWarning() {
        val r=Rig(true);r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
        r.end();r.sensors.emit("PPG",listOf(mapOf("sdkTimestamp" to 30002L,"PPG_GREEN" to 3)))
        r.sensors.ack("HEART_RATE");r.queue.advance(5000)
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        assertEquals(1L,r.session.counts()["CORE-1/PPG"])
        assertTrue(ActivityRecordingService.state.text.contains("尾部未确认"))
        assertFalse(r.seal().exists())
        // Late completion belongs to the old core drain and cannot finish POST.
        r.sensors.ack("PPG");r.queue.runReady()
        assertEquals("POST-1",ActivityRecordingService.state.phase)
        r.preComplete();r.sensors.ack("ECG");r.queue.runReady()
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
        assertTrue(r.seal().readText().contains("SPO2_REVIEW_IDLE_TIMEOUT"));assertEquals(1,r.sensors.closes)
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
    @Test fun preparationExpiresWithoutSensorsAndLateTapCannotStart() {
        val r=Rig(spo2=true)
        assertEquals("READY_SPO2_PRE",ActivityRecordingService.state.phase)
        r.queue.advance(80000);r.service.command("START_SPO2");r.queue.runReady()
        assertTrue(r.sensors.starts.isEmpty())
        assertTrue(r.seal().readText().contains("PLACEMENT_IDLE_TIMEOUT"))
    }
    @Test fun retryRequiresPreparationAndDuplicateStartDoesNotRestartSensor() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.service.command("START_SPO2");r.queue.runReady()
        assertEquals(listOf("SPO2"),r.sensors.starts)
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to -4,"SPO2" to 0)))
        r.queue.runReady();r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("检测到移动"))
        r.service.command("RETRY_SPO2");r.queue.runReady()
        assertEquals("READY_SPO2_PRE",ActivityRecordingService.state.phase)
        assertEquals(1,r.sensors.starts.size)
        r.service.command("START_SPO2");r.queue.runReady()
        assertEquals(2,r.sensors.starts.size)
        r.queue.advance(15000)
        assertFalse(ActivityRecordingService.state.text.contains("暂无新数据"))
    }
    @Test fun restoreTimeoutNeverStartsCoreAndArchiveRemainsValid() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("STATUS" to 2,"SPO2" to 98)))
        r.queue.runReady();r.sensors.ack("SPO2");r.queue.runReady()
        r.queue.advance(80000);r.service.command("PLACEMENT_READY");r.queue.runReady()
        assertEquals(listOf("SPO2"),r.sensors.starts)
        assertTrue(r.seal().exists())
        val batch=ActivityArchive.preparePending(r.session.folder.parentFile!!.parentFile!!,temp.newFolder())
        assertTrue(ActivityArchive.verify(batch.file()).allSealed())
    }

    @Test fun spo2CalculationRowIsNotDisplayedAsSuccessfulResultAndTimeoutRemainsFailure() {
        val r=Rig(spo2=true)
        r.service.command("START_SPO2");r.queue.runReady()
        r.sensors.emit("SPO2",listOf(mapOf("sdkTimestamp" to 1L,"STATUS" to 0,"SPO2" to 0)))
        r.queue.runReady();r.queue.advance(1000)
        assertTrue(ActivityRecordingService.state.text.contains("计算中，尚无有效结果"))
        assertFalse(ActivityRecordingService.state.text.contains("血氧：1"))
        assertFalse(ActivityRecordingService.state.text.contains("成功 0%"))
        r.queue.advance(29000);r.sensors.ack("SPO2");r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("未成功，未取得有效结果"))
        assertEquals(1L,r.session.counts()["PRE-100/SPO2"])
        r.service.command("RETRY_SPO2");r.queue.runReady()
        r.service.command("START_SPO2");r.queue.runReady()
        assertTrue(ActivityRecordingService.state.text.contains("第2次：测量中"))
        assertTrue(ActivityRecordingService.state.text.contains("第1次：未成功"))
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
        assertTrue(ActivityRecordingService.state.text.contains("第1次：成功 99%"))
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
