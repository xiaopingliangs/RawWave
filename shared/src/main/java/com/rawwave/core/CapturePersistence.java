package com.rawwave.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Serial disk owner. Admission is bounded by retained rows; no caller-runs or silent discard. */
public final class CapturePersistence implements AutoCloseable {
    private final ActivityStore.Session session;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    static final long MAX_RETAINED_BYTES = 64L * 1024 * 1024;
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Throwable> journalFailure = new AtomicReference<>();
    private final AtomicLong acceptedRows = new AtomicLong(), committedRows = new AtomicLong(), uncommittedRows = new AtomicLong();
    private volatile Map<String,Long> counts = Map.of();
    private boolean closed;
    public CapturePersistence(ActivityStore.Session session) { this.session=session; }
    public synchronized void append(String phase,String sensor,List<? extends Map<String,?>> rows) throws IOException {
        check();
        if(closed) throw new IOException("DISK_OWNER_CLOSED");
        int size=rows.size();
        long bytes=estimate(rows,0);
        if(size>100000-pending.get() || bytes>MAX_RETAINED_BYTES-retainedBytes.get()) {
            throw new IOException("PERSISTENCE_BACKLOG_LIMIT_UNCOMMITTED_TAIL_UNKNOWN");
        }
        // Take ownership of nested values too: caller mutation cannot change queued samples.
        var snapshot=SensorRows.snapshot(rows);
        pending.addAndGet(size);retainedBytes.addAndGet(bytes);acceptedRows.addAndGet(size);
        try { disk.execute(()->{
            try {
                if(failure.get()==null) {session.append(phase,sensor,snapshot);counts=session.counts();committedRows.addAndGet(size);}
                else uncommittedRows.addAndGet(size);
            }
            catch(Throwable error){uncommittedRows.addAndGet(size);failure.compareAndSet(null,error);}
            finally{pending.addAndGet(-size);retainedBytes.addAndGet(-bytes);}
        }); } catch (RejectedExecutionException error) {
            pending.addAndGet(-size);retainedBytes.addAndGet(-bytes);acceptedRows.addAndGet(-size);
            throw new IOException("PERSISTENCE_QUEUE_CLOSED",error);
        }
    }
    /** Preserve diagnostic ordering without filesystem work on the capture thread. */
    public synchronized void journalEvent(Journal journal,String kind,Map<String,?> fields) throws IOException {
        check();if(closed)throw new IOException("DISK_OWNER_CLOSED");
        long bytes=estimate(fields,0)+estimate(kind,0);
        if(pending.get()>=100000 || bytes>MAX_RETAINED_BYTES-retainedBytes.get())
            throw new IOException("PERSISTENCE_BACKLOG_LIMIT_UNCOMMITTED_TAIL_UNKNOWN");
        var snapshot=SensorRows.snapshot(List.of(fields)).get(0);
        pending.incrementAndGet();retainedBytes.addAndGet(bytes);
        try {disk.execute(()->{
            try {if(journalFailure.get()==null)journal.event(kind,snapshot);}
            catch(Throwable error){journalFailure.compareAndSet(null,error);}
            finally{pending.decrementAndGet();retainedBytes.addAndGet(-bytes);}
        });}catch(RejectedExecutionException error){
            pending.decrementAndGet();retainedBytes.addAndGet(-bytes);
            throw new IOException("PERSISTENCE_QUEUE_CLOSED",error);
        }
    }
    // Conservative retained-object estimate, not a JVM heap measurement. Stop walking as
    // soon as one submission exceeds the budget; never serialize payloads just to size them.
    private static long estimate(Object value,int depth) throws IOException {
        if(depth>16)throw new IOException("PERSISTENCE_VALUE_NESTING_LIMIT");
        if(value==null)return 8;
        if(value instanceof String text)return 48L+2L*text.length();
        long total=64;
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {
                total+=48+estimate(entry.getKey(),depth+1)+estimate(entry.getValue(),depth+1);
                if(total>MAX_RETAINED_BYTES)return total;
            }
        } else if(value instanceof List<?> list) {
            for(var item:list){total+=8+estimate(item,depth+1);if(total>MAX_RETAINED_BYTES)return total;}
        }
        return total;
    }
    public long pendingEstimatedBytes(){return retainedBytes.get();}
    public Map<String,Long> counts(){return counts;}
    public Map<String,Object> evidence(){return Map.of("acceptedRows",acceptedRows.get(),"committedRows",committedRows.get(),
        "uncommittedRows",uncommittedRows.get(),"pendingEstimatedBytes",retainedBytes.get(),
        "journalFailure",Objects.toString(journalFailure.get(),""));}
    public void check() throws IOException {
        Throwable error=failure.get();if(error!=null)throw new IOException("PERSISTENCE_FAILED:"+evidence(),error);
    }
    /** Only after sensor drain/stop; never on the continuous acquisition callback path. */
    public void barrier() throws IOException {
        try {disk.submit(()->{}).get(30,TimeUnit.SECONDS);check();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("PERSISTENCE_INTERRUPTED",e);}
        catch(ExecutionException|TimeoutException|RejectedExecutionException e){throw new IOException("PERSISTENCE_BARRIER_FAILED",e);}
    }
    public synchronized void close(){closed=true;disk.shutdown();try{session.close();}catch(Exception ignored){}}
}
