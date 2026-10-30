// Geographic daylight and astronomical twilight from sun-center elevation, in degrees.
package com.dark.winter.visuals;

/** Geographic daylight and astronomical twilight from sun-center elevation, in degrees.
 *  Only presentation/sky/lightmap: no mutation of world time, server spawning or skylight data.
 */
public final class SolarDaylight {
    private SolarDaylight() { }
    public static final float SUN_DIAMETER_DEGREES = 0.53f;
    /** Lighting includes civil twilight; full black is reserved for astronomical night. */
    public static float illumination(float elevationDeg) {
        if (!Float.isFinite(elevationDeg)) return 0.0f;
        if (elevationDeg <= -14f) return .045f;
        if (elevationDeg < -6f) return lerp(.045f, .12f, smooth(-14f,-6f,elevationDeg));
        if (elevationDeg < -.833f) return lerp(.12f,.32f,smooth(-6f,-.833f,elevationDeg));
        if (elevationDeg < 8f) return lerp(.32f,.86f,smooth(-.833f,8f,elevationDeg));
        return lerp(.86f,1f,smooth(8f,28f,elevationDeg));
    }
    public static float stars(float elevationDeg) {
        float darkness = 1f - smooth(-14.5f,-8.0f,elevationDeg);
        return (float)Math.pow(Math.max(0f, darkness), 1.35f);
    }
    public static float sunDiscVisibility(float elevationDeg) {
        return smooth(-.833f,-.26f,elevationDeg);
    }
    public static float sunHalfSizeAtDistance100() {
        return (float)(100.0 * Math.tan(Math.toRadians(SUN_DIAMETER_DEGREES / 2.0)));
    }
    private static float lerp(float a,float b,float t) {return a + (b-a)*t;}
    private static float smooth(float a,float b,float v) {
        if(v<=a) return 0f;
        if(v>=b) return 1f;
        float t=(v-a)/(b-a);
        return t*t*(3f-2f*t);
    }
}
