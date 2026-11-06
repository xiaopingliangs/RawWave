package com.rawwave.shell
import com.samsung.android.service.health.tracking.HealthTracker
import org.junit.Assert.*
import org.junit.Test

class SamsungReleaseTest {
    private class Tracker : SamsungTracker {
        lateinit var listener: HealthTracker.TrackerEventListener
        var stops=0
        var fail=false
        override fun listen(listener: HealthTracker.TrackerEventListener) { this.listener=listener }
        override fun unset() { stops++; if(fail) error("injected stop failure") }
        override fun flush()=false
    }
    private class Backend : SamsungBackend {
        val trackers=linkedMapOf<String,Tracker>()
        var creates=0; var disconnects=0;var failDisconnect=false;var delayedReady:(()->Unit)?=null;var delay=false
        override fun connect(ready:()->Unit, ended:()->Unit, failed:(String)->Unit) = if(delay){delayedReady=ready}else ready()
        override fun create(sensor:String):SamsungTracker {creates++;return Tracker().also{trackers[sensor]=it}}
        override fun disconnect(){disconnects++;if(failDisconnect)error("disconnect failure")}
        override fun capability():Map<String,Any> = mapOf(
            "healthSensorServiceVersion" to "test-service-version",
            "supportedHealthTrackerTypes" to "PPG_CONTINUOUS,ECG_ON_DEMAND")
    }
    @Test fun capabilitySnapshotIsRecordedAtConnectionSuccess() {
        val b=Backend();val records=mutableListOf<Pair<String,Map<String,Any>>>()
        val a=SamsungSensorAdapter(b,{it()},{kind,fields->records.add(kind to fields)},{1L},{1L})
        a.connect({}, {fail(it)})
        val capability=records.firstOrNull{it.first=="SDK_CAPABILITY"}?.second
        assertNotNull(capability)
        assertEquals("test-service-version",capability!!["healthSensorServiceVersion"])
        assertEquals("PPG_CONTINUOUS,ECG_ON_DEMAND",capability["supportedHealthTrackerTypes"])
        a.close()
    }
    @Test fun disconnectFailureStillFinalizesOwnerAndCloseIsIdempotent() {
        val b=Backend();b.failDisconnect=true;val stages=mutableListOf<String>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L})
        a.start("PPG",{},{});a.start("EDA",{}, {})
        try {a.close();fail("Expected failure")}catch(_:IllegalStateException){}
        a.close()
        assertEquals(1,b.disconnects);assertEquals(2,b.trackers.getValue("PPG").stops)
        assertEquals(2,b.trackers.getValue("EDA").stops);assertTrue(stages.contains("OWNER_CLOSED"))
    }
    @Test fun connectionSuccessAfterCloseCannotRestartAcquisition() {
        val b=Backend();b.delay=true;var ready=0
        val a=SamsungSensorAdapter(b,{it()},{_,_->},{1L},{1L})
        a.connect({ready++},{fail(it)});a.close();b.delayedReady!!.invoke()
        assertEquals(0,ready);assertEquals(0,b.creates);assertEquals(1,b.disconnects)
    }
    @Test fun retryReusesHandleAndFinalCloseReleasesAlreadyStoppedTypes() {
        val b=Backend(); val a=SamsungSensorAdapter(b,{it()},{_,_->}, {1L},{1L})
        a.start("SPO2",{}, {fail(it)}); a.stop("SPO2")
        a.start("SPO2",{}, {fail(it)}); a.stop("SPO2"); a.close();a.close()
        assertEquals(1,b.creates);assertEquals(3,b.trackers.getValue("SPO2").stops);assertEquals(1,b.disconnects)
    }
    @Test fun failingStopDoesNotSkipOtherSourcesOrDisconnect() {
        val b=Backend(); val a=SamsungSensorAdapter(b,{it()},{_,_->},{1L},{1L})
        a.start("PPG",{},{});a.start("SPO2",{},{});b.trackers.getValue("PPG").fail=true
        try {a.close();fail("Expected failure")} catch(_:IllegalStateException){}
        assertEquals(2,b.trackers.getValue("SPO2").stops);assertEquals(1,b.disconnects)
    }
    @Test fun callbackAfterStopIsEvidenceAndCannotDeliverIntoRetry() {
        val b=Backend();val stages=mutableListOf<String>();var rows=0
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L})
        a.start("SPO2",{rows++},{});val old=b.trackers.getValue("SPO2").listener
        a.stop("SPO2");a.start("SPO2",{rows++},{})
        old.onDataReceived(emptyList());assertEquals(0,rows)
        assertTrue(stages.contains("CALLBACK_AFTER_STOP"));a.close()
    }
    @Test fun configuredGraceRetriesUnsetBeforeVendorDisconnect() {
        val b=Backend();val stages=mutableListOf<String>();val pauses=mutableListOf<Long>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L},
            releaseGraceMs=40L,pause={pauses.add(it)})
        a.start("PPG",{}, {})
        a.close()
        assertEquals(listOf(40L),pauses)
        assertEquals(3,b.trackers.getValue("PPG").stops)
        assertTrue(stages.indexOf("RELEASE_GRACE_BEFORE_DISCONNECT") < stages.indexOf("FINAL_UNSET_RETRY_REQUEST"))
        assertTrue(stages.indexOf("FINAL_UNSET_RETRY_RETURNED") < stages.indexOf("DISCONNECT_REQUEST"))
        assertEquals(1,b.disconnects)
    }
    @Test fun postRetryGraceWaitsBeforeVendorDisconnect() {
        val b=Backend();val stages=mutableListOf<String>();val pauses=mutableListOf<Long>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L},
            releaseGraceMs=40L,postRetryGraceMs=25L,pause={pauses.add(it)})
        a.start("PPG_ON_DEMAND",{}, {})
        a.close()
        assertEquals(listOf(40L,25L),pauses)
        assertTrue(stages.indexOf("RELEASE_GRACE_AFTER_UNSET_RETRY") < stages.indexOf("REFS_CLEARED_BEFORE_DISCONNECT"))
        assertEquals(1,b.disconnects)
    }
    @Test fun noGraceKeepsExistingReleaseOrderAndStopCount() {
        val b=Backend();val stages=mutableListOf<String>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L})
        a.start("PPG",{}, {})
        a.close()
        assertEquals(2,b.trackers.getValue("PPG").stops)
        assertFalse(stages.contains("RELEASE_GRACE_BEFORE_DISCONNECT"))
        assertEquals(1,b.disconnects)
    }
    @Test fun closeClearsVendorReferencesBeforeDisconnectAndRecordsRecheck() {
        val b=Backend();val stages=mutableListOf<String>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L},
            releaseGraceMs=40L,pause={})
        a.start("PPG",{}, {})
        a.close()
        assertTrue(stages.indexOf("FINAL_UNSET_RETRY_RETURNED") < stages.indexOf("REFS_CLEARED_BEFORE_DISCONNECT"))
        assertTrue(stages.indexOf("REFS_CLEARED_BEFORE_DISCONNECT") < stages.indexOf("DISCONNECT_REQUEST"))
        assertTrue(stages.indexOf("DISCONNECT_REQUEST") < stages.indexOf("DISCONNECT_RETURNED"))
        assertTrue(stages.indexOf("DISCONNECT_RETURNED") < stages.indexOf("DISCONNECT_RECHECK"))
        assertTrue(stages.indexOf("DISCONNECT_RECHECK") < stages.indexOf("OWNER_CLOSED"))
        assertEquals(1,b.disconnects)
    }
    @Test fun failedDisconnectStillClearsReferencesAndRecordsRecheck() {
        val b=Backend();b.failDisconnect=true;val stages=mutableListOf<String>()
        val a=SamsungSensorAdapter(b,{it()},{_,f->stages.add(f["stage"].toString())},{1L},{1L})
        a.start("PPG",{}, {})
        try { a.close(); fail("Expected failure") } catch (_:IllegalStateException){}
        assertTrue(stages.contains("REFS_CLEARED_BEFORE_DISCONNECT"))
        assertTrue(stages.contains("DISCONNECT_FAILED"))
        assertTrue(stages.contains("DISCONNECT_RECHECK"))
        assertTrue(stages.contains("OWNER_CLOSED"))
        assertEquals(1,b.disconnects)
    }
}
