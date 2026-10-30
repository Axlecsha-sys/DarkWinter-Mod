// Meteorological direction: the bearing FROM which air arrives.
package com.dark.winter.weather;

import java.util.Locale;

/** Meteorological direction: the bearing FROM which air arrives. Geographic north is -Z at rotation zero. */
public enum WindDirection {
    NORTH(0, -5), NORTHEAST(45, -3), EAST(90, -1.5f), SOUTHEAST(135, 1),
    SOUTH(180, 3), SOUTHWEST(225, 1.5f), WEST(270, -1), NORTHWEST(315, -4.5f);

    private final float bearing, temperatureOffset;
    WindDirection(float bearing, float temperatureOffset) {
        this.bearing = bearing; this.temperatureOffset = temperatureOffset;
    }
    public float bearingDegrees() { return bearing; }
    public float temperatureOffsetC() { return temperatureOffset; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String translationKey() { return "wind.darkwinter." + id(); }
    public static WindDirection nearest(double bearing) {
        return values()[Math.floorMod((int)Math.floor((normalize(bearing) + 22.5) / 45), 8)];
    }
    public static WindDirection byId(String id) {
        for (WindDirection d : values()) if (d.id().equals(id)) return d;
        throw new IllegalArgumentException("Unknown wind direction: " + id);
    }
    public static double normalize(double bearing) {
        if (!Double.isFinite(bearing)) throw new IllegalArgumentException("Nonfinite wind bearing");
        return ((bearing % 360) + 360) % 360;
    }
    /** Same Y-axis rotation as SolarEphemeris/LunarEphemeris: x'=cos*x+sin*z, z'=-sin*x+cos*z. */
    public static float flowX(double sourceBearing, double northRotation) {
        return (float)-Math.sin(Math.toRadians(sourceBearing - northRotation));
    }
    public static float flowZ(double sourceBearing, double northRotation) {
        return (float)Math.cos(Math.toRadians(sourceBearing - northRotation));
    }
}
