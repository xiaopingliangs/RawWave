package com.rawwave.core;

import com.google.gson.JsonParser;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipFile;

/** Display-only naming v1. Never changes archive bytes or uses export time as activity time. */
public final class ActivityExportName {
    private ActivityExportName() {}
    public static String create(File archive, ActivityArchive.Verified verified, ZoneId zone) throws IOException {
        Long earliest=null;
        boolean unknown=false;
        try(ZipFile zip=new ZipFile(archive)) {
            for(String session:verified.sessionIds()) {
                var entry=zip.getEntry("sessions/"+session+"/plan.json");
                if(entry==null) {unknown=true;continue;}
                try(InputStream in=zip.getInputStream(entry)) {
                    // Basic stream API keeps naming compatible with the app's API 30 minimum.
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    byte[] chunk=new byte[4096]; int count;
                    while(buffer.size()<65537 && (count=in.read(chunk,0,Math.min(chunk.length,65537-buffer.size())))!=-1) {
                        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ACTIVITY_EXPORT_CANCELLED");
                        buffer.write(chunk,0,count);
                    }
                    byte[] bytes=buffer.toByteArray();
                    if(bytes.length>65536) {unknown=true;continue;}
                    try {
                        var value=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject().get("createdUtcMs");
                        long time=value.getAsBigDecimal().longValueExact();
                        if(time<=0) {unknown=true;continue;}
                        earliest=earliest==null?time:Math.min(earliest,time);
                    } catch(RuntimeException invalid) {unknown=true;}
                }
            }
        }
        String time="time-unknown";
        if(earliest!=null && !unknown) {
            try {
                var local=Instant.ofEpochMilli(earliest).atZone(zone);
                String offset=local.getOffset().getId().replace("Z","+00:00").replace("+","p").replace("-","m").replace(":","");
                time=DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss",Locale.ROOT).format(local)+"_UTC"+offset;
            } catch(DateTimeException invalid) {time="time-unknown";}
        }
        String multiple=verified.sessionIds().size()>1?"_"+verified.sessionIds().size()+"-sessions":"";
        return "RawWave-activities-"+time+multiple+"-"+verified.id()+".zip";
    }
}
