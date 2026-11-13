// Owns bounded native scratch leases independently of application-lifetime shared libraries.
package com.dark.winter.client.fixes.nativebuffers;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeScratchBuffers implements AutoCloseable {
    public enum Kind { MESH, VOXEL }
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<Kind, Counters> COUNTERS = new EnumMap<>(Kind.class);
    private static final Set<State> LIVE = ConcurrentHashMap.newKeySet();
    static { for (Kind kind : Kind.values()) COUNTERS.put(kind, new Counters()); }
    private final State state;
    private final Cleaner.Cleanable cleanable;

    public NativeScratchBuffers(Kind kind, long... capacities) {
        state = new State(java.util.Objects.requireNonNull(kind), capacities);
        try {
            cleanable = CLEANER.register(this, state);
        } catch (Throwable failure) {
            state.run();
            throw failure;
        }
    }

    public MemorySegment segment(int index) { return state.segments[index]; }
    public boolean tryAcquire() { return state.tryAcquire(); }
    public void release() { state.release(); }
    public boolean isRetired() { return state.retired; }
    public void retire() { cleanable.clean(); }
    @Override public void close() { retire(); }

    /** Does not wait for native calls: active leases close themselves after their synchronous return. */
    public static void retireAll(Kind kind) {
        for (State state : LIVE) if (state.kind == kind) state.run();
    }

    /** Native scratch only; loaded shared libraries intentionally have application lifetime. */
    public static Map<String, Long> memorySnapshot(Kind kind) {
        Counters c = COUNTERS.get(kind);
        return Map.of("liveRegions", c.liveRegions.get(), "liveBytes", c.liveBytes.get(),
                "activeCalls", c.activeCalls.get(), "retiredRegions", c.retiredRegions.get(),
                "allocatedBytes", c.allocatedBytes.get(), "freedBytes", c.freedBytes.get());
    }

    private static final class Counters {
        final AtomicLong liveRegions = new AtomicLong(), liveBytes = new AtomicLong();
        final AtomicLong activeCalls = new AtomicLong(), retiredRegions = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong(), freedBytes = new AtomicLong();
    }

    /** Registry and Cleaner retain this state, never its owner or a worker Thread. */
    private static final class State implements Runnable {
        final Kind kind;
        final Arena arena;
        final MemorySegment[] segments;
        final long bytes;
        final Counters counters;
        volatile boolean retired;
        boolean busy, closed;

        State(Kind kind, long[] capacities) {
            if (capacities.length == 0 || capacities.length > 3) throw new IllegalArgumentException("Scratch segment count");
            long total = 0;
            for (long size : capacities) {
                if (size <= 0 || size > 1_048_576L) throw new IllegalArgumentException("Scratch capacity");
                total = Math.addExact(total, size);
            }
            if (total > 1_048_576L) throw new IllegalArgumentException("Scratch exceeds 1 MiB");
            this.kind = kind;
            this.bytes = total;
            this.counters = COUNTERS.get(kind);
            arena = Arena.ofShared();
            segments = new MemorySegment[capacities.length];
            try {
                for (int i = 0; i < capacities.length; i++) segments[i] = arena.allocate(capacities[i], 16);
            } catch (Throwable failure) {
                arena.close();
                throw failure;
            }
            counters.liveRegions.incrementAndGet();
            counters.liveBytes.addAndGet(bytes);
            counters.allocatedBytes.addAndGet(bytes);
            try {
                LIVE.add(this);
            } catch (Throwable failure) {
                arena.close();
                closed = true;
                retired = true;
                counters.liveRegions.decrementAndGet();
                counters.liveBytes.addAndGet(-bytes);
                counters.freedBytes.addAndGet(bytes);
                LIVE.remove(this);
                throw failure;
            }
        }

        synchronized boolean tryAcquire() {
            if (retired) return false;
            if (busy) throw new IllegalStateException("Scratch already borrowed");
            busy = true;
            counters.activeCalls.incrementAndGet();
            return true;
        }

        synchronized void release() {
            if (!busy) throw new IllegalStateException("Scratch is not borrowed");
            busy = false;
            counters.activeCalls.decrementAndGet();
            if (retired) closeIdle();
        }

        @Override public synchronized void run() {
            if (!retired) {
                retired = true;
                if (!closed) counters.retiredRegions.incrementAndGet();
            }
            if (!busy) closeIdle();
        }

        private void closeIdle() {
            if (closed) return;
            arena.close();
            closed = true;
            LIVE.remove(this);
            counters.liveRegions.decrementAndGet();
            counters.liveBytes.addAndGet(-bytes);
            counters.freedBytes.addAndGet(bytes);
            counters.retiredRegions.decrementAndGet();
        }
    }
}
