// Bounds additional synchronous section rebuilds while preserving one immediate update per frame.
package com.dark.winter.client.fixes.performance;

import java.util.Objects;
import java.util.function.LongSupplier;

/** A soft budget for indivisible native section rebuilds, owned by the render thread. */
public final class SectionCompileBudget {
    public static final long BUDGET_NANOS = 2_000_000L;
    public static final int MAX_SYNCHRONOUS_SECTIONS = 2;

    private final LongSupplier clock;
    private long frameStarted;
    private int synchronousSections;

    public SectionCompileBudget() {
        this(System::nanoTime);
    }

    SectionCompileBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void startFrame() {
        frameStarted = clock.getAsLong();
        synchronousSections = 0;
    }

    public boolean allowSynchronousCompile() {
        // The first prioritized section stays immediate even when the frame is already late.
        if (synchronousSections == 0) {
            synchronousSections = 1;
            return true;
        }
        if (synchronousSections >= MAX_SYNCHRONOUS_SECTIONS
                || clock.getAsLong() - frameStarted >= BUDGET_NANOS) {
            return false;
        }
        synchronousSections++;
        return true;
    }
}
