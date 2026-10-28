package com.rawwave.core;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
public class BiaPressureTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void templatesRoundTripProfileAndKeepOldPlans() throws Exception {
        var root=temp.newFolder();var store=new ActivityStore(root,d->{});
        var profile=new BiaProfile(170,65,30,1);
        var t=store.createTemplate("new",false,List.of("PRESSURE"),false,profile);
        assertEquals(profile,store.templates().get(0).biaProfile());
        var run=store.start(t,1000,1);var plan=Files.readString(new File(run.folder,"plan.json").toPath());
        store.updateTemplate(t.id(),"changed",true,List.of("GYROSCOPE"),true,null);
        assertFalse(store.templates().get(0).bia());
        assertEquals(plan,Files.readString(new File(run.folder,"plan.json").toPath()));
        run.append("PRE-200","BIA",List.of(Map.of("STATUS",0,"BODY_FAT_RATIO",20)));
        run.append("CORE-1","PRESSURE",List.of(Map.of("PRESSURE_HPA",1000.25,"sensorTimestampNs",12345L)));
        run.seal("test",2000);
        assertTrue(SensorCatalog.acceptsStream("BIA"));assertTrue(SensorCatalog.acceptsStream("PRESSURE"));
    }
    @Test public void legacyTemplateWithoutProfileRemainsUsable() throws Exception {
        var root=temp.newFolder();var dir=new File(root,"templates");assertTrue(dir.mkdir());
        Files.writeString(new File(dir,"legacy.json").toPath(),"{\"id\":\"legacy\",\"name\":\"old\",\"ecg\":true}");
        var t=new ActivityStore(root,d->{}).templates().get(0);
        assertFalse(t.bia());assertFalse(t.spo2());assertEquals(SensorCatalog.defaultCoreSources(),t.coreSources());
    }
    @Test public void invalidProfileOrPlaceholderIsNotAccepted() {
        assertThrows(IllegalArgumentException.class,()->new BiaProfile(Float.NaN,65,30,1));
        assertThrows(IllegalArgumentException.class,()->new BiaProfile(170,0,30,1));
        assertThrows(IllegalArgumentException.class,()->new BiaProfile(170,65,30,2));
        assertFalse(BiaResult.complete(Map.of("STATUS",0)));
        var result=new HashMap<String,Object>(Map.of("STATUS",0,"BODY_FAT_RATIO",20,"BODY_FAT_MASS",13,"SKELETAL_MUSCLE_MASS",28,"TOTAL_BODY_WATER",40));
        assertTrue(BiaResult.complete(result));result.put("STATUS",9);assertFalse(BiaResult.complete(result));
        result.put("STATUS",0);result.put("BODY_FAT_MASS",Float.NaN);assertFalse(BiaResult.complete(result));
    }
    @Test public void newStreamsQualityUsesTheirActualFieldNames() {
        var pressure=new StreamQualityTracker("PRESSURE");
        pressure.observe(List.of(Map.of("PRESSURE_HPA",1000.25,"sdkTimestamp",100,"accuracy",3)));
        assertEquals(1L,pressure.summary().get("valueRows"));
        assertEquals(Map.of("accuracy=3",1L),pressure.summary().get("statusCounts"));
        var bia=new StreamQualityTracker("BIA");
        bia.observe(List.of(Map.of("BODY_FAT_RATIO",20,"SKELETAL_MUSCLE_MASS",28,"STATUS",0,"sdkTimestamp",100)));
        assertEquals(1L,bia.summary().get("valueRows"));
        assertEquals(Map.of("STATUS=0",1L),bia.summary().get("statusCounts"));
    }
    @Test public void multiFrequencyRejectsPartialAndNonFiniteResults() throws Exception {
        var row=new HashMap<String,Object>();row.put("STATUS",0);
        assertFalse(BiaResult.multiFrequencyComplete(row));
        for(String frequency:List.of("5K","10K","50K","250K")) {
            row.put("BODY_IMPEDANCE_MAGNITUDE_"+frequency,500f);row.put("BODY_IMPEDANCE_PHASE_"+frequency,-8f);
        }
        assertTrue(BiaResult.multiFrequencyComplete(row));
        row.put("STATUS",23);assertFalse(BiaResult.multiFrequencyComplete(row));
        row.put("STATUS",0);row.put("BODY_IMPEDANCE_PHASE_5K",Float.NaN);assertFalse(BiaResult.multiFrequencyComplete(row));
        var store=new ActivityStore(temp.newFolder(),d->{});
        store.createTemplate("mf",false,List.of("MAGNETIC_FIELD"),false,new BiaProfile(170,65,30,1),true);
        assertTrue(store.templates().get(0).mfBia());
        assertEquals(List.of("MAGNETIC_FIELD"),store.templates().get(0).coreSources());
    }
    @Test public void pressureNeedsFlushButBiaAcceptsDeliveredRows() {
        var drain=new CaptureDrain(List.of("BIA","PRESSURE"));
        drain.complete("BIA",SensorPort.DrainStatus.DELIVERED_ONLY,"on demand");
        drain.complete("PRESSURE",SensorPort.DrainStatus.DELIVERED_ONLY,"not sufficient");
        assertFalse(drain.failures().containsKey("BIA"));assertTrue(drain.failures().containsKey("PRESSURE"));
    }
}
