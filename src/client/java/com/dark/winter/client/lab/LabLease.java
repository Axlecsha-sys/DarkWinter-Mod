// Tracks monotonic, bounded laboratory input leases on the client thread.
package com.dark.winter.client.lab;

import java.util.Set;
import java.util.function.LongSupplier;

/** Monotonic, bounded input lease. All methods are called on the client thread. */
public final class LabLease {
    public static final long LEASE_NANOS = 10_000_000_000L;
    public static final Set<String> KEYS = Set.of("W", "A", "S", "D", "SPACE", "SHIFT", "CTRL",
            "E", "TAB", "F", "F5", "1", "2", "3", "4", "5", "6", "7", "8", "9", "LEFT", "RIGHT");
    private final LongSupplier clock;
    private String session = "", actionId = "", result = "idle", reason = "";
    private long leaseUntil, actionUntil, actionStart;
    private int durationMillis;
    private Set<String> keys = Set.of();
    private double yaw, pitch, appliedFraction;

    public LabLease() { this(System::nanoTime); }
    public LabLease(LongSupplier clock) { this.clock = clock; }
    public boolean active() { return !session.isEmpty(); }
    public String session() { return session; }
    public String actionId() { return actionId; }
    public String result() { return result; }
    public String reason() { return reason; }
    public boolean running() { return "running".equals(result); }
    public Set<String> keys() { return running() ? keys : Set.of(); }
    public int durationMillis() { return durationMillis; }

    public static boolean validKey(String key) { return KEYS.contains(key) || key.matches("key\\.[a-zA-Z0-9_.-]{1,120}"); }

    public void begin(String id) {
        expire();
        if (active()) throw new IllegalStateException("A lab session already exists");
        if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid session ID");
        session = id;
        leaseUntil = clock.getAsLong() + LEASE_NANOS;
        actionId = ""; result = "idle"; reason = "";
    }
    public void require(String id) {
        expire();
        if (!active() || !session.equals(id)) throw new IllegalStateException("Lab session expired or changed");
    }
    public void heartbeat(String id) { require(id); leaseUntil = clock.getAsLong() + LEASE_NANOS; }
    public void start(String id, String action, Set<String> keys, int millis, double yaw, double pitch) {
        require(id);
        if (!action.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid action ID");
        if (millis < 20 || millis > 5000 || !Double.isFinite(yaw) || !Double.isFinite(pitch)
                || Math.abs(yaw) > 180 || Math.abs(pitch) > 90 || !keys.stream().allMatch(LabLease::validKey))
            throw new IllegalArgumentException("Action exceeds allowed bounds");
        if (action.equals(actionId)) throw new IllegalStateException("Action ID already used; inspect state");
        if (running()) throw new IllegalStateException("An action is already running");
        this.keys = Set.copyOf(keys); this.yaw = yaw; this.pitch = pitch;
        durationMillis = millis; actionId = action; result = "running"; reason = "";
        actionStart = clock.getAsLong(); actionUntil = actionStart + millis * 1_000_000L; appliedFraction = 0;
    }
    public double[] turnDelta() {
        if (!running()) return new double[] {0, 0};
        double fraction = Math.min(1, Math.max(0, (clock.getAsLong() - actionStart) / (durationMillis * 1_000_000.0)));
        double increment = fraction - appliedFraction;
        appliedFraction = fraction;
        return new double[] {yaw * increment, pitch * increment};
    }
    public void expire() {
        long now = clock.getAsLong();
        if (active() && now - leaseUntil >= 0) { end("heartbeat_timeout"); return; }
        if (running() && now - actionUntil >= 0) { result = "completed"; keys = Set.of(); }
    }
    public void stop(String why) {
        if (running()) result = "cancelled";
        keys = Set.of(); reason = why;
    }
    public void end(String why) { stop(why); session = ""; }
}

