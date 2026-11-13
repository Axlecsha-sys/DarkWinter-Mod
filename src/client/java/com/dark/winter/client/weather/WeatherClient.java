// Samples synchronized timelines or one immutable laboratory presentation input per operation.
package com.dark.winter.client.weather;

import com.dark.winter.client.TestingAccess;
import com.dark.winter.testing.TestingPresentation;
import com.dark.winter.weather.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** Authoritative timelines remain server-owned; presentation never reads mutable laboratory settings. */
public final class WeatherClient {
    private static WeatherTimeline current;
    private static ClimateTimeline climate;
    private static ClimateGeography geography;
    private WeatherClient() {}
    public static void init(){
        ClientPlayConnectionEvents.INIT.register((handler,client)->clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientPlayNetworking.registerGlobalReceiver(WeatherPayload.TYPE,(payload,context)->{
            current=payload.timeline();climate=payload.climate();geography=payload.geography();
        });
    }
    public static boolean active(){return active(TestingAccess.presentation());}
    public static boolean active(TestingPresentation lab){
        Minecraft client=Minecraft.getInstance();
        if(lab!=null)return client.level!=null&&Level.OVERWORLD.equals(client.level.dimension())
                &&(lab.enabled("weather")||lab.enabled("wind")||lab.enabled("precipitation")||lab.enabled("atmosphere"));
        return current!=null&&client.level!=null&&Level.OVERWORLD.equals(client.level.dimension());
    }
    public static WeatherValues sample(float partialTicks){return sample(partialTicks,TestingAccess.presentation());}
    /** Null selects the ordinary synchronized timeline; a captured value is never looked up again. */
    public static WeatherValues sample(float partialTicks,TestingPresentation lab){
        if(lab!=null)return labWeather(lab);
        if(!active(null))return WeatherPreset.OVERCAST.profile();
        double time=Minecraft.getInstance().level.getGameTime()+Math.clamp(partialTicks,0,1);
        WeatherValues weather=current.sample(time);ClimateSample air=climate.sample(time);
        return weather.withWind(air.windMetersPerSecond()/18,air.windX(),air.windZ());
    }
    public static ClimateSample climate(float partialTicks){
        var lab=TestingAccess.presentation();
        if(lab!=null)return labClimate(lab);
        return active(null)&&climate!=null?climate.sample(Minecraft.getInstance().level.getGameTime()
                +Math.clamp(partialTicks,0,1)):null;
    }
    public static ClimateGeography geography(){return geography(TestingAccess.presentation());}
    public static ClimateGeography geography(TestingPresentation lab){return active(lab)?geography:null;}
    public static float northRotation(){
        var lab=TestingAccess.presentation();
        if(lab!=null)return com.dark.winter.client.online.OnlineTestingView.active()&&geography!=null
                ?geography.northRotation():(float)com.dark.winter.visuals.SolarRuntime.geography(lab).northRotation();
        return active(null)&&geography!=null?geography.northRotation():0;
    }
    public static WeatherPreset targetPreset(){
        var lab=TestingAccess.presentation();
        if(lab!=null)return WeatherPreset.valueOf(lab.weather().toUpperCase(java.util.Locale.ROOT));
        return active(null)?current.target():WeatherPreset.OVERCAST;
    }
    public static float groundSnow(float partialTicks){
        var lab=TestingAccess.presentation();
        if(lab!=null)return lab.enabled("precipitation")?labWeather(lab).snowfall():0;
        return active(null)?current.groundSnow(Minecraft.getInstance().level.getGameTime()+Math.clamp(partialTicks,0,1)):0;
    }
    private static WeatherValues labWeather(TestingPresentation settings){
        WeatherValues chosen=WeatherPreset.valueOf(settings.weather().toUpperCase(java.util.Locale.ROOT)).profile();
        WeatherValues sky=settings.enabled("weather")?chosen:WeatherPreset.CLEAR.profile();ClimateSample air=labClimate(settings);
        return new WeatherValues(sky.cloudCover(),settings.enabled("precipitation")?chosen.snowfall():0,
                settings.enabled("wind")?settings.windSpeed()/18:0,air.windX(),air.windZ(),
                sky.fogMultiplier(),sky.sunVisibility(),sky.cloudRoundness(),sky.cloudScale(),
                sky.cloudStretch(),sky.fogBlue(),sky.skyBlue());
    }
    private static ClimateSample labClimate(TestingPresentation settings){
        double angle=Math.toRadians(settings.windDirection());
        return new ClimateSample(settings.enabled("wind")?settings.windSpeed():0,-(float)Math.sin(angle),
                (float)Math.cos(angle),settings.windDirection(),settings.temperature());
    }
    private static void clear(){current=null;climate=null;geography=null;}
    public static Component weatherName(){
        var lab=TestingAccess.presentation();
        if(!active(lab))return Component.empty();
        if(lab!=null)return Component.translatable((lab.enabled("weather")
                ?WeatherPreset.valueOf(lab.weather().toUpperCase(java.util.Locale.ROOT)):WeatherPreset.CLEAR).translationKey());
        Component target=Component.translatable(current.target().translationKey());
        return current.transitioning(Minecraft.getInstance().level.getGameTime())
                ?Component.translatable("weather.darkwinter.transition",target):target;
    }
}
