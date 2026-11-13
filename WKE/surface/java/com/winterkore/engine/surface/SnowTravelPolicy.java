package com.winterkore.engine.surface;

/** Shared snow travel rules. Depth is remaining loose cover, in millimetres, never a biome flag. */
public final class SnowTravelPolicy {
    private SnowTravelPolicy() {}
    private static double smooth(double value) {double t=Math.clamp(value,0,1);return t*t*(3-2*t);}
    public static float gaitWeight(double depth) {return (float)smooth((depth-80)/520);}
    /** Earlier, visibly deliberate extraction at calf depth; thin powder still retains ordinary gait. */
    public static float wadingPoseWeight(float demand){
        if(!Float.isFinite(demand)||demand<=.015f)return 0;
        if(demand>=1)return 1;
        return (float)Math.sqrt(Math.clamp((demand-.015f)/.985f,0,1));
    }
    public static double strideMeters(float running,float crouching,float snow){
        double ordinary=2.0+.5*running-.55*crouching;
        return ordinary*(1-.45*wadingPoseWeight(snow));
    }
    public static double movementMultiplier(double depth) {
        if(!Double.isFinite(depth)||depth<=60)return 1;
        if(depth<600)return 1-.76*smooth((depth-60)/540);
        if(depth<900)return .24-.16*smooth((depth-600)/300);
        return .08*(1-smooth((depth-900)/200));
    }
    /** Deep sprint input is an effortful faster wade, never the ordinary running cycle. */
    public static double sprintBonus(double depth) {return .30-.28*wadingPoseWeight(gaitWeight(depth));}
    public static float exertion(double depth) {return 1+1.4f*gaitWeight(depth);}
    /** Loose snow supports weight below its skin; a firm basal layer and the underlying terrain remain solid. */
    public static double sinkMillimeters(double depth,double authoredBase) {
        if(!Double.isFinite(depth+authoredBase))return 0;
        double extra=Math.max(0,depth-Math.clamp(authoredBase,0,125));
        return Math.min(800,extra*.70*smooth(extra/160));
    }
}
