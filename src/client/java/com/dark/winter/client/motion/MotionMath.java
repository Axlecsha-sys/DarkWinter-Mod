// Exact first-order lag for a target moving linearly during this interval.
package com.dark.winter.client.motion;

public final class MotionMath {
    private MotionMath() { }
    public static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
    public static double mix(double a, double b, double t) { return a + (b-a)*t; }
    public static double smooth(double t) { t=clamp(t,0,1); return t*t*(3-2*t); }
    public static double decay(double value, double target, double dt, double seconds) {
        return mix(value, target, -Math.expm1(-dt / Math.max(seconds, 0.001)));
    }
    public static double wrap(double degrees) {
        return degrees - 360.0*Math.floor((degrees + 180.0)/360.0);
    }
    /** Exact first-order lag for a target moving linearly during this interval. */
    public static double lag(double oldLag, double delta, double dt, double seconds) {
        if (dt <= 1e-8) return oldLag;
        double alpha=-Math.expm1(-dt/seconds);
        return oldLag*(1-alpha)-delta*(seconds*alpha/dt);
    }
    /** One smooth compression after each contact, then a longer release. */
    public static double contact(double steps) {
        double p=steps-Math.floor(steps);
        if (p < 0.16) return smooth(p/0.16);
        return 1-smooth((p-0.16)/0.84);
    }
    /** A planted, near-linear stance followed by a curved recovery. Period = 2 steps. */
    public static double leg(double steps) {
        double p=steps-2*Math.floor(steps/2);
        return p<1 ? -1+2*p : 1-2*smooth(p-1);
    }
}
