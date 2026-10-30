// Continuous, dimensionless inputs shared by sky, fog, snow and breath.
package com.dark.winter.weather;

/** Continuous, dimensionless inputs shared by sky, fog, snow and breath. */
public record WeatherValues(float cloudCover, float snowfall, float windSpeed, float windX, float windZ,
                            float fogMultiplier, float sunVisibility, float cloudRoundness,
                            float cloudScale, float cloudStretch, float fogBlue, float skyBlue) {
    public WeatherValues {
        check(cloudCover, 0, 1); check(snowfall, 0, 2); check(windSpeed, 0, 2);
        check(windX, -1, 1); check(windZ, -1, 1); check(fogMultiplier, 0, 8);
        check(sunVisibility, 0, 1.5f); check(cloudRoundness, 0, 1);
        check(cloudScale, .25f, 4); check(cloudStretch, .25f, 6);
        check(fogBlue, 0, 1); check(skyBlue, 0, 1);
    }

    /** Strength is metres/second divided by 18; directions describe air movement, not its source. */
    public WeatherValues withWind(float strength, float x, float z) {
        return new WeatherValues(cloudCover, snowfall, strength, x, z, fogMultiplier, sunVisibility,
                cloudRoundness, cloudScale, cloudStretch, fogBlue, skyBlue);
    }

    public WeatherValues blend(WeatherValues target, double amount) {
        float t = (float)Math.clamp(amount, 0, 1);
        return new WeatherValues(mix(cloudCover, target.cloudCover, t), mix(snowfall, target.snowfall, t),
                mix(windSpeed, target.windSpeed, t), mix(windX, target.windX, t), mix(windZ, target.windZ, t),
                mix(fogMultiplier, target.fogMultiplier, t), mix(sunVisibility, target.sunVisibility, t),
                mix(cloudRoundness, target.cloudRoundness, t), mix(cloudScale, target.cloudScale, t),
                mix(cloudStretch, target.cloudStretch, t), mix(fogBlue, target.fogBlue, t), mix(skyBlue, target.skyBlue, t));
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static void check(float value, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Invalid weather value: " + value);
    }
}
