// Bounded angular lag: immediate control plus a short, frame-rate independent viscous response.
package com.dark.winter.client.motion;

/** Bounded angular lag: immediate control plus a short, frame-rate independent viscous response. */
public final class LookWeight {
    private static final double DIRECT_RESPONSE = 0.25;
    private static final double MAX_LAG_DEGREES = 2.0;
    private static final double MAX_FRAME_SECONDS = 0.25;

    // Accepted mouse rotation which has not reached the view yet, in degrees.
    private double remaining;

    public void reset() {
        remaining = 0;
    }

    /** frameSeconds is the elapsed time of this mouse frame, not an absolute timestamp. */
    public double update(double deltaDegrees, double frameSeconds, boolean active,
                         double maxSpeed, double responseSeconds) {
        if (!Double.isFinite(deltaDegrees) || !Double.isFinite(frameSeconds)
                || !Double.isFinite(maxSpeed) || !Double.isFinite(responseSeconds)) {
            reset();
            return 0;
        }
        if (!active) {
            reset();
            return deltaDegrees;
        }
        // A focus transition or a long stall must not replay accumulated mouse motion.
        if (frameSeconds <= 0 || frameSeconds > MAX_FRAME_SECONDS) {
            reset();
            return 0;
        }

        double limit = Math.max(1, maxSpeed);
        double response = Math.max(0.001, responseSeconds);
        double frameLimit = limit * frameSeconds;
        double lagLimit = Math.min(MAX_LAG_DEGREES, limit * response * (1 - DIRECT_RESPONSE));
        remaining = MotionMath.clamp(remaining, -lagLimit, lagLimit);

        // Changing direction responds immediately; old rotation never fights the mouse.
        if (deltaDegrees * remaining < 0) remaining = 0;

        // Discard the excess of a flick before filtering, so it cannot become a queued turn.
        double accepted = MotionMath.clamp(deltaDegrees, -frameLimit, frameLimit);
        double alpha = -Math.expm1(-frameSeconds / response);
        double movingLag = response * alpha / frameSeconds;

        // Exact first-order lag for a target travelling uniformly during this frame.
        // Zero-input frames still release the small residual: sparse mouse counts are
        // preserved instead of restarting acceleration on every empty render frame.
        double next = remaining * (1 - alpha) + (1 - DIRECT_RESPONSE) * accepted * movingLag;
        double result = remaining * alpha + accepted * (1 - (1 - DIRECT_RESPONSE) * movingLag);
        double bounded = MotionMath.clamp(next, -lagLimit, lagLimit);
        result += next - bounded;
        remaining = bounded;
        return MotionMath.clamp(result, -frameLimit, frameLimit);
    }
}
