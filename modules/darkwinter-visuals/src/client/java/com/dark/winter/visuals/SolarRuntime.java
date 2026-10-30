// Same sun position is captured during sky state extraction and consumed by sky + shadows.
package com.dark.winter.visuals;

import com.dark.winter.client.weather.WeatherClient;
import com.dark.winter.weather.ClimateGeography;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/** Same sun position is captured during sky state extraction and consumed by sky + shadows. */
public final class SolarRuntime {
    private static ClientLevel lastWorld;
    private static SolarEphemeris.Position lastPosition;
    private static LunarEphemeris.Position lastMoon;
    private SolarRuntime() { }

    /** One geographic frame for server wind, solar shadows, Moon and displayed local time. */
    public static ClimateGeography geography() {
        ClimateGeography synced=WeatherClient.geography();
        if (synced!=null) return synced;
        VisualConfig config=VisualConfig.shared();
        return new ClimateGeography(config.latitude,config.longitude,config.utcOffsetHours,config.northRotation,
                config.visualDayLengthMultiplier,config.calendarStartDay,config.calendarDaysPerMinecraftDay);
    }

    public static void capture(ClientLevel level, float partialTicks) {
        VisualConfig config=VisualConfig.shared();
        config.poll();
        if (!com.dark.winter.client.TestingAccess.resolve("visuals",config.enabled && config.geographicSun) || level==null || level.dimension()!=Level.OVERWORLD) {
            lastWorld=null;lastPosition=null;lastMoon=null;return;
        }
        lastWorld=level;
        ClimateGeography geography=geography();
        double scaledTicks=VisualTime.clockTicks(level,partialTicks);
        var epoch=com.dark.winter.client.CalendarClient.modern();
        if (epoch!=null) {
            var date=epoch.at(level.getDefaultClockTime()+(double)partialTicks).date();
            lastPosition=SolarEphemeris.calculateCalendar(scaledTicks,geography.latitude(),geography.longitude(),
                    geography.utcOffsetHours(),geography.northRotation(),date);
            lastMoon=LunarEphemeris.calculateCalendar(scaledTicks,geography.latitude(),geography.longitude(),
                    geography.utcOffsetHours(),geography.northRotation(),date);
            return;
        }
        lastPosition=SolarEphemeris.calculate(scaledTicks,
                geography.latitude(),geography.longitude(),geography.utcOffsetHours(),geography.northRotation(),
                geography.calendarStartDay(),geography.calendarDaysPerMinecraftDay());
        lastMoon=LunarEphemeris.calculate(scaledTicks,
                geography.latitude(),geography.longitude(),geography.utcOffsetHours(),geography.northRotation(),
                geography.calendarStartDay(),geography.calendarDaysPerMinecraftDay());
    }
    public static SolarEphemeris.Position current(ClientLevel level) {
        if (com.dark.winter.client.TestingAccess.enabled("visuals") && level == lastWorld) return lastPosition;
        return null;
    }
    /** Same clock, geography and ephemeris as GeographicSunSkyMixin's visible lunar quad. */
    public static LunarEphemeris.Position currentMoon(ClientLevel level) {
        return com.dark.winter.client.TestingAccess.enabled("visuals") && level == lastWorld ? lastMoon : null;
    }
}
