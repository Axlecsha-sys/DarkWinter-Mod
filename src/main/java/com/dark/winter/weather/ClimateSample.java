// One shared wind sample feeds HUD, particles, clouds, audio and player movement.
package com.dark.winter.weather;

/** One shared wind sample feeds HUD, particles, clouds, audio and player movement. */
public record ClimateSample(float windMetersPerSecond, float windX, float windZ,
                            float sourceBearingDegrees, float airTemperatureC) {
    public WindDirection direction() { return WindDirection.nearest(sourceBearingDegrees); }
    public float feelsLikeC() { return feelsLikeC(1); }
    public float feelsLikeC(float exposure) {
        return windChillC(airTemperatureC, windMetersPerSecond * Math.clamp(exposure, 0, 1));
    }
    /** Environment and Climate Change Canada index; the low-wind branch avoids a jump near calm. */
    public static float windChillC(float temperatureC, float metresPerSecond) {
        if (!Float.isFinite(temperatureC) || !Float.isFinite(metresPerSecond) || metresPerSecond < 0)
            throw new IllegalArgumentException("Invalid wind chill input");
        if (temperatureC > 0 || metresPerSecond == 0) return temperatureC;
        double kmh = metresPerSecond * 3.6;
        double chill = kmh < 5 ? temperatureC + (-1.59 + .1345 * temperatureC) / 5 * kmh
                : 13.12 + .6215 * temperatureC - 11.37 * Math.pow(kmh, .16)
                    + .3965 * temperatureC * Math.pow(kmh, .16);
        return (float)Math.min(temperatureC, chill);
    }
}
