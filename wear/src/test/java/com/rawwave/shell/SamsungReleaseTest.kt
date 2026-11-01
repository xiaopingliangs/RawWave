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
}
