package com.rawwave.core;

import java.util.*;

/** Non-blocking admission for already-owned raw snapshots, not a data queue.
 * Conservative object estimate; independent of the downstream disk budget. */
public final class CallbackAdmission {
    private static final long LIMIT = 16L * 1024 * 1024;
    private long bytes;
    private int batches, rows;
    public synchronized Lease reserve(List<? extends Map<String,?>> payload) {
        long cost = 256 + estimate(payload,0);
        if (cost > LIMIT - bytes || batches >= 8192 || payload.size() > 100000 - rows) return null;
        bytes += cost; batches++; rows += payload.size();
        return new Lease(cost,payload.size());
    }
    private static long estimate(Object value,int depth) {
        if(depth>16)return LIMIT+1;
        if(value==null)return 8;
        if(value instanceof String s)return 48L+2L*s.length();
        long n=64;
        if(value instanceof Map<?,?> map) {
            for(var e:map.entrySet()) {n+=48+estimate(e.getKey(),depth+1)+estimate(e.getValue(),depth+1);if(n>LIMIT)return n;}
        } else if(value instanceof List<?> list) {
            for(var item:list){n+=8+estimate(item,depth+1);if(n>LIMIT)return n;}
        }
        return n;
    }
    public synchronized long pendingEstimatedBytes(){return bytes;}
    public final class Lease implements AutoCloseable {
        private final long cost; private final int count; private boolean closed;
        private Lease(long cost,int count){this.cost=cost;this.count=count;}
        public void close(){synchronized(CallbackAdmission.this){if(!closed){closed=true;bytes-=cost;rows-=count;batches--;}}}
    }
}
