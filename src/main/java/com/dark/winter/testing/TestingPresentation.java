// Publishes detached laboratory presentation inputs without exposing mutable server settings.
package com.dark.winter.testing;

import java.util.Objects;

/** Immutable client/render inputs; authoritative settings and per-actor tools remain server-owned. */
public record TestingPresentation(int moduleMask,String weather,float windSpeed,float windDirection,
        float temperature,float filmStrength,float hazeDensity,float shadowStrength) {
    private static final TestingPresentation NEUTRAL=new TestingPresentation(0,"clear",0,270,-5,1,.005f,.5f);
    public TestingPresentation {
        Objects.requireNonNull(weather,"weather");
        if((moduleMask&~1023)!=0)throw new IllegalArgumentException("Unknown laboratory module bits");
    }
    public static TestingPresentation neutral(){return NEUTRAL;}
    public boolean enabled(String module){
        int bit=switch(module){
            case "hud"->1;case "weather"->2;case "wind"->4;case "precipitation"->8;
            case "visuals"->16;case "atmosphere"->32;case "stamina"->64;case "survival"->128;
            case "motion"->256;case "camera"->512;default->0;
        };
        return (moduleMask&bit)!=0;
    }
    /** Capture only on the settings owner's thread, or from an exclusively owned validated DTO. */
    public static TestingPresentation capture(TestingSettings settings,TestingPresentation previous){
        Objects.requireNonNull(settings,"settings");
        int mask=0;
        for(int i=0;i<TestingSettings.MODULES.length;i++)
            if(settings.enabled(TestingSettings.MODULES[i]))mask|=1<<i;
        String weather=settings.weather;
        float windSpeed=settings.windSpeed,windDirection=settings.windDirection,temperature=settings.temperature;
        float filmStrength=settings.filmStrength,hazeDensity=settings.hazeDensity,shadowStrength=settings.shadowStrength;
        if(previous!=null&&previous.moduleMask==mask&&previous.weather.equals(weather)
                &&same(previous.windSpeed,windSpeed)&&same(previous.windDirection,windDirection)
                &&same(previous.temperature,temperature)&&same(previous.filmStrength,filmStrength)
                &&same(previous.hazeDensity,hazeDensity)&&same(previous.shadowStrength,shadowStrength))return previous;
        return new TestingPresentation(mask,weather,windSpeed,windDirection,temperature,filmStrength,hazeDensity,shadowStrength);
    }
    private static boolean same(float left,float right){return Float.floatToIntBits(left)==Float.floatToIntBits(right);}
}
