// Client only observes the server's shared timeline; no wall-clock animation or packet restarts.
package com.dark.winter.client.weather;

import com.dark.winter.weather.WeatherPayload;
import com.dark.winter.weather.WeatherPreset;
import com.dark.winter.weather.WeatherTimeline;
import com.dark.winter.weather.WeatherValues;
import com.dark.winter.weather.ClimateGeography;
import com.dark.winter.weather.ClimateSample;
import com.dark.winter.weather.ClimateTimeline;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** Client only observes the server's shared timeline; no wall-clock animation or packet restarts. */
public final class WeatherClient {
    private static WeatherTimeline current;
    private static ClimateTimeline climate;
    private static ClimateGeography geography;
    private WeatherClient() { }

    public static void init() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayNetworking.registerGlobalReceiver(WeatherPayload.TYPE, (payload, context) -> {
            current = payload.timeline(); climate = payload.climate(); geography = payload.geography();
        });
    }

    public static boolean active() {
        Minecraft client = Minecraft.getInstance();
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)
            return client.level!=null && Level.OVERWORLD.equals(client.level.dimension()) &&
                    (com.dark.winter.client.TestingAccess.enabled("weather") || com.dark.winter.client.TestingAccess.enabled("wind")
                    || com.dark.winter.client.TestingAccess.enabled("precipitation") || com.dark.winter.client.TestingAccess.enabled("atmosphere"));
        return current != null && client.level != null && Level.OVERWORLD.equals(client.level.dimension());
    }

    public static WeatherValues sample(float partialTicks) {
        Minecraft client = Minecraft.getInstance();
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return labWeather(lab);
        if (!active()) return WeatherPreset.OVERCAST.profile();
        double time = client.level.getGameTime() + Math.clamp(partialTicks, 0, 1);
        WeatherValues weather = current.sample(time);
        ClimateSample air = climate.sample(time);
        return weather.withWind(air.windMetersPerSecond() / 18, air.windX(), air.windZ());
    }

    public static ClimateSample climate(float partialTicks) {
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return labClimate(lab);
        return active() && climate != null ? climate.sample(Minecraft.getInstance().level.getGameTime()
                + Math.clamp(partialTicks, 0, 1)) : null;
    }
    public static ClimateGeography geography() { return active() ? geography : null; }
    public static float northRotation() {
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return com.dark.winter.client.online.OnlineTestingView.active()&&geography!=null?geography.northRotation():(float)com.dark.winter.visuals.SolarRuntime.geography().northRotation();
        return active()&&geography!=null?geography.northRotation():0;
    }
    public static WeatherPreset targetPreset() {
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return WeatherPreset.valueOf(lab.weather.toUpperCase(java.util.Locale.ROOT));
        return active()?current.target():WeatherPreset.OVERCAST;
    }
    public static float groundSnow(float partialTicks) {
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return lab.enabled("precipitation")?labWeather(lab).snowfall():0;
        return active() ? current.groundSnow(Minecraft.getInstance().level.getGameTime() + Math.clamp(partialTicks, 0, 1)) : 0;
    }
    private static WeatherValues labWeather(com.dark.winter.testing.TestingSettings settings){
        WeatherValues chosen=WeatherPreset.valueOf(settings.weather.toUpperCase(java.util.Locale.ROOT)).profile();
        WeatherValues sky=settings.enabled("weather")?chosen:WeatherPreset.CLEAR.profile();ClimateSample air=labClimate(settings);
        return new WeatherValues(sky.cloudCover(),settings.enabled("precipitation")?chosen.snowfall():0,
                settings.enabled("wind")?settings.windSpeed/18:0,air.windX(),air.windZ(),
                sky.fogMultiplier(),sky.sunVisibility(),sky.cloudRoundness(),
                sky.cloudScale(),sky.cloudStretch(),sky.fogBlue(),sky.skyBlue());
    }
    private static ClimateSample labClimate(com.dark.winter.testing.TestingSettings settings){
        double angle=Math.toRadians(settings.windDirection);
        return new ClimateSample(settings.enabled("wind")?settings.windSpeed:0,-(float)Math.sin(angle),(float)Math.cos(angle),settings.windDirection,settings.temperature);
    }
    private static void clear() { current = null; climate = null; geography = null; }

    public static Component weatherName() {
        if (!active()) return Component.empty();
        var lab=com.dark.winter.client.TestingAccess.settings();
        if(lab!=null)return Component.translatable((lab.enabled("weather")?WeatherPreset.valueOf(lab.weather.toUpperCase(java.util.Locale.ROOT)):WeatherPreset.CLEAR).translationKey());
        Component target = Component.translatable(current.target().translationKey());
        return current.transitioning(Minecraft.getInstance().level.getGameTime())
                ? Component.translatable("weather.darkwinter.transition", target) : target;
    }
}
