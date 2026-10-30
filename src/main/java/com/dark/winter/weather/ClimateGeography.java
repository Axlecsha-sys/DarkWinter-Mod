// Server copy of the existing sky geography; sent to clients so celestial and wind axes agree.
package com.dark.winter.weather;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server copy of the existing sky geography; sent to clients so celestial and wind axes agree. */
public record ClimateGeography(float latitude, float longitude, float utcOffsetHours, float northRotation,
                               float visualDayLengthMultiplier, int calendarStartDay, float calendarDaysPerMinecraftDay) {
    public static final ClimateGeography DEFAULT = new ClimateGeography(73, -80, -5, 0, 4, 355, .10f);
    public ClimateGeography {
        range(latitude, -89.9f, 89.9f); range(longitude, -180, 180); range(utcOffsetHours, -12, 14);
        range(northRotation, -360, 360); range(visualDayLengthMultiplier, 1, 32);
        range(calendarDaysPerMinecraftDay, 0, 20);
        if (calendarStartDay < 1 || calendarStartDay > 365) throw new IllegalArgumentException("Invalid calendar start");
    }
    public static ClimateGeography load(Path file) throws IOException {
        if (!Files.exists(file)) return DEFAULT;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { p.load(reader); }
        try {
            return new ClimateGeography(value(p, "latitude", 73), value(p, "longitude", -80),
                    value(p, "utcOffsetHours", -5), value(p, "northRotation", 0),
                    value(p, "visualDayLengthMultiplier", 4), (int)value(p, "calendarStartDay", 355),
                    value(p, "calendarDaysPerMinecraftDay", .10f));
        } catch (RuntimeException ex) { throw new IOException("Invalid DarkWinter geographic settings: " + file, ex); }
    }
    private static float value(Properties p, String key, float fallback) {
        return Float.parseFloat(p.getProperty(key, Float.toString(fallback)));
    }
    private static void range(float value, float low, float high) {
        if (!Float.isFinite(value) || value < low || value > high) throw new IllegalArgumentException("Invalid geography value");
    }
}
