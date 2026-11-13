// Owns the explicitly started process-local capture without enabling sampling during normal gameplay.
package com.dark.winter.diagnostics.performance;

import java.util.Map;

public final class PerformanceRuntime {
    private static volatile PerformanceCapture active;
    private static volatile float expectedTickRate = Float.NaN;
    private PerformanceRuntime() {}

    public static PerformanceCapture active() { return active; }
    public static float expectedTickRate(PerformanceCapture capture) { return active == capture ? expectedTickRate : Float.NaN; }

    public static synchronized PerformanceCapture start(Map<String,String> metadata, int capacity, long durationNanos) {
        if (active != null) throw new IllegalStateException("A performance capture is already active");
        PerformanceCapture next = new PerformanceCapture(metadata, capacity, durationNanos);
        try { expectedTickRate = Float.parseFloat(metadata.getOrDefault("tickRate", "NaN")); }
        catch (NumberFormatException unavailable) { expectedTickRate = Float.NaN; }
        active = next;
        return next;
    }

    public static synchronized Map<String,Object> stop(String reason) {
        PerformanceCapture capture = active;
        if (capture == null) throw new IllegalStateException("No performance capture is active");
        active = null;
        return capture.finish(reason);
    }
}
