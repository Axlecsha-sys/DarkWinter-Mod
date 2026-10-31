// Render-only bridge to the synchronized, continuously blended DarkWinter weather.
package com.dark.winter.visuals;

import com.dark.winter.client.weather.WeatherClient;
import com.dark.winter.weather.WeatherValues;

/** Render-only bridge to the synchronized, continuously blended DarkWinter weather. */
public final class WeatherInput {
    public record State(float cloudCover,float snowfall,float windSpeed,
                        float windX,float windZ,float fogMultiplier,float sunVisibility,
                        float cloudRoundness,float cloudScale,float cloudStretch,float fogBlue,float skyBlue) {
        public State {
            cloudCover=range(cloudCover,0,1); snowfall=range(snowfall,0,2);
            windSpeed=range(windSpeed,0,50); windX=range(windX,-1,1); windZ=range(windZ,-1,1);
            fogMultiplier=range(fogMultiplier,0,8); sunVisibility=range(sunVisibility,0,1.2f);
            cloudRoundness=range(cloudRoundness,0,1); cloudScale=range(cloudScale,.25f,4);
            cloudStretch=range(cloudStretch,1,6); fogBlue=range(fogBlue,0,1); skyBlue=range(skyBlue,0,1);
            float length=(float)Math.sqrt(windX*windX+windZ*windZ);
            if(length>1e-5f) {windX/=length; windZ/=length;}
        }
        public State(float cloudCover,float snowfall,float windSpeed,float windX,float windZ,
                     float fogMultiplier,float sunVisibility) {
            this(cloudCover,snowfall,windSpeed,windX,windZ,fogMultiplier,sunVisibility,0,1,1,0,0);
        }
    }
    private static volatile State override;
    private WeatherInput() {}
    public static void set(State state) { override=state; }
    public static void clear() { override=null; }
    public static State snapshot(float vanillaRainBrightness) {
        return snapshot(vanillaRainBrightness,1);
    }
    public static State snapshot(float vanillaRainBrightness,float partialTicks) {
        State state=override;
        if(state!=null) return state;
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null && !lab.enabled("weather")) {
            // The atmosphere and film do not select a weather sky. Keep independent wind and snowfall inputs.
            WeatherValues independent=WeatherClient.sample(partialTicks);
            float clear=range(vanillaRainBrightness,0,1);
            return new State(1-clear,independent.snowfall(),independent.windSpeed(),
                    independent.windX(),independent.windZ(),1,clear,0,1,1,0,0);
        }
        if(WeatherClient.active()) {
            WeatherValues weather=WeatherClient.sample(partialTicks);
            return new State(weather.cloudCover(),weather.snowfall(),weather.windSpeed(),
                    weather.windX(),weather.windZ(),weather.fogMultiplier(),weather.sunVisibility(),
                    weather.cloudRoundness(),weather.cloudScale(),weather.cloudStretch(),weather.fogBlue(),weather.skyBlue());
        }
        float clear=range(vanillaRainBrightness,0,1);
        // Weather comes from the engine, not from the brightness of snow on screen.
        return new State(1-clear,0,0,1,0,1+(1-clear)*.4f,clear);
    }
    private static float range(float v,float min,float max) {
        return Float.isFinite(v)?Math.clamp(v,min,max):min;
    }
}
