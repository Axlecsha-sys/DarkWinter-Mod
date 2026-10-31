// Independent lab inputs feed the existing weather effects and wind physics.
package com.dark.winter.testing;

import com.dark.winter.weather.*;
import net.minecraft.server.MinecraftServer;

/** Independent lab inputs feed the existing weather effects and wind physics. */
public final class TestingWeather {
    private TestingWeather() {}
    public static WeatherValues weather(MinecraftServer server) {
        TestingSettings s=TestingServer.settings(server);
        WeatherValues chosen=WeatherPreset.valueOf(s.weather.toUpperCase(java.util.Locale.ROOT)).profile();
        WeatherValues sky=TestingServer.enabled(server,"weather")?chosen:WeatherPreset.CLEAR.profile();
        ClimateSample air=climate(server);
        return new WeatherValues(sky.cloudCover(),s.enabled("precipitation")?chosen.snowfall():0,
                s.enabled("wind")?s.windSpeed/18:0,air.windX(),air.windZ(),
                sky.fogMultiplier(),sky.sunVisibility(),
                sky.cloudRoundness(),sky.cloudScale(),sky.cloudStretch(),sky.fogBlue(),sky.skyBlue());
    }
    public static ClimateSample climate(MinecraftServer server) {
        TestingSettings s=TestingServer.settings(server);
        double angle=Math.toRadians(s.windDirection);
        return new ClimateSample(s.enabled("wind")?s.windSpeed:0,-(float)Math.sin(angle),(float)Math.cos(angle),s.windDirection,s.temperature);
    }
}
