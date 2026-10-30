// Tick-based lift; the caller still clips each displacement against real collision shapes.
package com.dark.winter.movement;

/** Tick-based lift; the caller still clips each displacement against real collision shapes. */
public final class StepCurve {
    private StepCurve() { }
    public static int duration(double rise) {
        // Collision clearance and float rounding must not add a whole extra tick.
        return Math.max(4,Math.min(6,(int)Math.ceil(5.0*rise-0.001)));
    }
    public static double progress(int tick,int startTick,int duration) {
        return Math.max(0.0,Math.min(1.0,(tick-startTick+1.0)/duration));
    }
    public static double eased(double t) {
        t=Math.max(0,Math.min(1,t));
        return t*t*(3.0-2.0*t);
    }
    public static double target(double start,double finish,int tick,int startTick,int duration) {
        return start+(finish-start)*eased(progress(tick,startTick,duration));
    }
}
