package com.rawwave.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Diagnostic-only queue. Never used to transport raw samples or control sensors. */
public final class DiagnosticDispatcher implements AutoCloseable {
    @FunctionalInterface public interface Sink {void write(String kind,Map<String,Object> fields)throws Exception;}
    private final ThreadPoolExecutor worker;
    private final AtomicLong rejected=new AtomicLong(),failed=new AtomicLong();
    private volatile String lastFailure="";
    public DiagnosticDispatcher(int capacity) {
        worker=new ThreadPoolExecutor(0,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),r->{
            Thread t=new Thread(r,"sensor-diagnostic-writer");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean submit(String kind,Map<String,?> fields,Sink sink) {
        // Small scalar metadata only. Reject excessive evidence rather than retain arbitrary payloads.
        long chars=kind.length();
        for(var entry:fields.entrySet()) {
            Object v=entry.getValue();
            if(v!=null && !(v instanceof String || v instanceof Number || v instanceof Boolean)) {rejected.incrementAndGet();return false;}
            chars+=entry.getKey().length()+String.valueOf(v).length();
        }
        if(chars>16384){rejected.incrementAndGet();return false;}
        var copy=new LinkedHashMap<String,Object>(fields);
        try {worker.execute(()->{
            copy.put("diagnosticRejected",rejected.get());copy.put("diagnosticWriteFailures",failed.get());
            copy.put("diagnosticLastFailure",lastFailure);
            try{sink.write(kind,Collections.unmodifiableMap(copy));}
            catch(Exception e){lastFailure=e.toString();failed.incrementAndGet();}
        });return true;}catch(RejectedExecutionException e){rejected.incrementAndGet();return false;}
    }
    public long rejected(){return rejected.get();}
    public long failed(){return failed.get();}
    public void close(){worker.shutdown();}
    public boolean awaitClosed(long timeout,TimeUnit unit)throws InterruptedException{return worker.awaitTermination(timeout,unit);}
}
