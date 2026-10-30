// Tracks tick-based movement intent, release, and direction changes.
package com.dark.winter.client.motion;

/** Tick-based intentional input. Release stops input; reversing must replant the foot. */
public final class WalkEnvelope {
    public record Output(double x, double z) { }
    private final Axis lateral = new Axis();
    private final Axis forward = new Axis();
    private double speed = 0.82;

    public void reset() { lateral.reset(); forward.reset(); speed = 0.82; }

    public Output filter(double x, double z, boolean sprint, boolean eligible, MotionSettings s) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) { reset(); return new Output(0, 0); }
        if (!eligible || !s.enabled() || !s.locomotion()) { reset(); return new Output(x, z); }
        double length = Math.hypot(x, z);
        if (length > 1) { x /= length; z /= length; }
        // S + Shift and backwards diagonals never get the sprint input scale.
        boolean running = sprint && z > 0.001;
        speed = MotionMath.decay(speed, running ? 1 : s.walkSpeed(), 0.05, 0.16);
        double base = z < -0.001 ? s.walkSpeed() * s.backwardSpeed() : speed;
        double targetX = x * base * s.strafeSpeed();
        double targetZ = z * base;
        double rate = Math.max(0.5, base / s.startSeconds());
        double outX = lateral.tick(targetX, rate, s.reverseSeconds());
        double outZ = forward.tick(targetZ, rate, s.reverseSeconds());
        // Clamp diagonals to the requested gait, even while the previous gait is decaying.
        double cap = Math.hypot(targetX, targetZ);
        double actual = Math.hypot(outX, outZ);
        if (actual > cap && actual > 1e-8) { outX *= cap / actual; outZ *= cap / actual; }
        return new Output(outX, outZ);
    }

    private static final class Axis {
        private double value, lock, released;
        private int lastSign;
        void reset() { value=lock=released=0; lastSign=0; }
        double tick(double target, double rate, double reversal) {
            final double dt = 0.05;
            int sign = target > 0.0001 ? 1 : target < -0.0001 ? -1 : 0;
            if (sign == 0) {
                // No extra push after release. Brief release cannot bypass reversal memory.
                value = 0; released += dt; lock = Math.max(0, lock-dt);
                if (released >= Math.max(0.18, reversal)) lastSign = 0;
                return 0;
            }
            if (lastSign != 0 && sign != lastSign) {
                value = 0;
                lock = Math.max(0, reversal-released);
            }
            lastSign = sign; released = 0;
            if (lock > 1e-8) { lock = Math.max(0, lock-dt); return 0; }
            double change = Math.min(Math.abs(target-value), rate*dt);
            value += Math.copySign(change, target-value);
            return value;
        }
    }
}
