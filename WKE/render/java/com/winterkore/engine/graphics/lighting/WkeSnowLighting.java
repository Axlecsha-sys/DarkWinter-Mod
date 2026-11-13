// Independent snow irradiance policy; the host supplies real sun, lightmap and occluding geometry.
package com.winterkore.engine.graphics.lighting;

/** Uniform upper-sky hemisphere plus ground bounce and the current directional solar contribution. */
public final class WkeSnowLighting {
    public static final float GROUND_BOUNCE=.16f,UPPER_SKY=.84f,DIRECT_FRACTION=.60f,AO_STRENGTH=1;
    public record Sunlight(float sunX,float sunY,float sunZ,float intensity) {
        public Sunlight {
            if(!Float.isFinite(sunX)||!Float.isFinite(sunY)||!Float.isFinite(sunZ)||!Float.isFinite(intensity)
                    ||intensity<0||intensity>1)throw new IllegalArgumentException("Invalid snow sunlight");
            double length=(double)sunX*sunX+(double)sunY*sunY+(double)sunZ*sunZ;
            if(Math.abs(length-1)>1e-5)throw new IllegalArgumentException("Snow sun direction must be unit length");
        }
    }
    private static final Sunlight OVERCAST=new Sunlight(0,1,0,0);
    private WkeSnowLighting(){}
    public static Sunlight overcast(){return OVERCAST;}
    public static Sunlight sunlight(float x,float y,float z,float intensity){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(intensity))
            throw new IllegalArgumentException("Non-finite snow lighting input");
        double length=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        if(length<1e-12)return OVERCAST;
        return new Sunlight((float)(x/length),(float)(y/length),(float)(z/length),Math.clamp(intensity,0,1));
    }
    /**
     * Fraction of diffuse upper-sky irradiance above a geometric horizon, cos(horizonElevation)^2.
     * The host computes the maximum positive rise/run from actual neighbouring surfaces per azimuth.
     * Clear sky is one; raised neighbouring snow/solid geometry progressively occludes its true cavity.
     */
    public static float horizonVisibility(double positiveRiseOverRun){
        if(!Double.isFinite(positiveRiseOverRun))throw new IllegalArgumentException("Non-finite snow horizon");
        double slope=Math.max(0,positiveRiseOverRun);
        return (float)(1/(1+slope*slope));
    }
    public static float skyVisibility(double[] horizonSlopes){
        if(horizonSlopes==null||horizonSlopes.length<4||horizonSlopes.length>32)
            throw new IllegalArgumentException("Snow sky visibility needs four to thirty-two azimuths");
        double sum=0;for(double slope:horizonSlopes)sum+=horizonVisibility(slope);
        return (float)(sum/horizonSlopes.length);
    }
}
