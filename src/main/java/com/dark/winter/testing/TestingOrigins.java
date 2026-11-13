// Tracks server-owned provenance changes while exposing a safe live read view to client observers.
package com.dark.winter.testing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TestingOrigins {
    private final Thread owner=Thread.currentThread();
    private final Map<Long,String> values=new ConcurrentHashMap<>();
    private final Map<Long,String> view=Collections.unmodifiableMap(values);
    private long generation;
    private long savedGeneration=-1;

    Map<Long,String> view() { return view; }

    boolean set(long position,String value) {
        requireOwner();
        String previous=values.get(position);
        if(Objects.equals(previous,value)) return false;
        if(generation==Long.MAX_VALUE) throw new IllegalStateException("Provenance generation overflow");
        if(value==null) values.remove(position); else values.put(position,value);
        generation++;
        return true;
    }
    long generation() { requireOwner(); return generation; }
    boolean dirty() { requireOwner(); return generation!=savedGeneration; }

    /** Called by the owner only after the initial file was completely loaded and validated. */
    void loaded() { requireOwner(); savedGeneration=generation; }

    /** Acknowledges a successfully written immutable snapshot without clearing newer mutations. */
    void persisted(long capturedGeneration) {
        requireOwner();
        if(capturedGeneration<0 || capturedGeneration>generation)
            throw new IllegalArgumentException("Persisted provenance generation is outside the captured state");
        savedGeneration=Math.max(savedGeneration,capturedGeneration);
    }
    Snapshot snapshot() { requireOwner(); return new Snapshot(generation,values); }

    private void requireOwner() {
        if(Thread.currentThread()!=owner) throw new IllegalStateException("Provenance state requires its owner thread");
    }
    record Snapshot(long generation,Map<Long,String> values) {
        Snapshot {
            if(generation<0) throw new IllegalArgumentException("Negative provenance snapshot generation");
            values=Map.copyOf(values);
        }
    }
}
