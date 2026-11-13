// Smooths camera motion to bound shutter exposure and suppress held-frame and interlace trails.
package com.dark.winter.visuals;

/** One render owner's bounded shutter envelope; no frame-rate, world, GPU or wall-clock dependency. */
final class MotionBlurEnvelope {
    static final float MAX_WEIGHT=.16f;
    private static final double ATTACK_SECONDS=.08,RELEASE_SECONDS=.12;
    private float weight,motion;

    float update(double seconds,double angularDegreesPerSecond,double blocksPerSecond,float strength) {
        if(!Double.isFinite(seconds)||seconds<=0||seconds>.25
                ||!Double.isFinite(angularDegreesPerSecond)||!Double.isFinite(blocksPerSecond)
                ||!Float.isFinite(strength)) {
            reset();
            return 0;
        }
        double activity=Math.max(smoothstep(4,100,Math.max(0,angularDegreesPerSecond)),
                smoothstep(.08,6,Math.max(0,blocksPerSecond)));
        double response=-Math.expm1(-seconds/(activity>motion?ATTACK_SECONDS:RELEASE_SECONDS));
        motion=(float)Math.max(0,Math.min(1,motion+(activity-motion)*response));
        weight=Math.min(MAX_WEIGHT,Math.max(0,strength))*motion;
        return weight;
    }

    void reset() { weight=motion=0; }
    float weight() { return weight; }
    // Held/alternating fields fade out during camera motion, independently of shutter strength.
    float temporalRetention() { return (float)(1-smoothstep(.02,.35,motion)); }

    private static double smoothstep(double low,double high,double value) {
        double t=Math.max(0,Math.min(1,(value-low)/(high-low)));
        return t*t*(3-2*t);
    }
}
