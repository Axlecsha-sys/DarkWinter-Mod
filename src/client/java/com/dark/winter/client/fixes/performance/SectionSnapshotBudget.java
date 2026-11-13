// Limits per-frame snapshot work while reserving progress for background and player changes.
package com.dark.winter.client.fixes.performance;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class SectionSnapshotBudget {
    public static final long BUDGET_NANOS = 2_000_000L;
    public static final int MAX_BACKGROUND = 8;
    public static final int MAX_PLAYER = 2;

    private final LongSupplier clock;
    private long started;
    private int background;
    private int player;
    private int nextSection;
    private int startSection;
    private int visitedSections;
    private boolean advanced;

    public SectionSnapshotBudget() { this(System::nanoTime); }

    SectionSnapshotBudget(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public void startFrame() {
        started = clock.getAsLong();
        background = 0;
        player = 0;
    }

    public int beginVisibleSections(int size) {
        if (size < 0) throw new IllegalArgumentException("Negative visible section count");
        startSection = size == 0 ? 0 : Math.floorMod(nextSection, size);
        visitedSections = 0;
        advanced = false;
        return startSection;
    }

    public void visitSection() { visitedSections++; }

    private void advanceCursor() {
        if (!advanced) {
            // The first eligible admission moves every stable backlog forward, including either lane.
            nextSection = startSection + visitedSections;
            advanced = true;
        }
    }

    public boolean allowSnapshot(boolean playerChanged) {
        // Reserved lanes always admit their first work even when another frame phase was slow.
        if (playerChanged) {
            if (player >= MAX_PLAYER) return false;
            player++;
            advanceCursor();
            return true;
        }
        if (background >= MAX_BACKGROUND
                || (background != 0 && clock.getAsLong() - started >= BUDGET_NANOS)) return false;
        background++;
        advanceCursor();
        return true;
    }
}
