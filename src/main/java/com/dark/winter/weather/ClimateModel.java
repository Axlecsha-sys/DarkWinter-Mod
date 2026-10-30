// Stylised high-Arctic climate, not a weather station reconstruction or historical forecast.
package com.dark.winter.weather;

/** Stylised high-Arctic climate, not a weather station reconstruction or historical forecast. */
public final class ClimateModel {
    private ClimateModel() { }

    /** Northeastern Canadian Arctic profile: E/NE frequent; latitude and longitude alter the weights. */
    public static WindDirection chooseDirection(double unit, double latitude, double longitude) {
        double coastal = Math.clamp((longitude + 120) / 55, 0, 1);
        double arctic = Math.clamp((latitude - 60) / 20, 0, 1);
        double[] weights = {14 + 2 * arctic, 20 + 9 * coastal, 17 + 9 * coastal,
                7 - 2 * arctic, 7 - 3 * arctic, 6 - 2 * arctic, 12 - 4 * coastal, 12 + 2 * arctic};
        double total = 0;
        for (double w : weights) total += w;
        double selected = Math.clamp(unit, 0, Math.nextDown(1.0)) * total;
        for (int i = 0; i < weights.length; i++) {
            selected -= weights[i];
            if (selected < 0) return WindDirection.values()[i];
        }
        return WindDirection.NORTHWEST;
    }

    public static float speed(WeatherPreset weather, double unit) {
        float low, high;
        switch (weather) {
            case CLEAR -> { low = 1; high = 3; }
            case PARTLY_CLOUDY -> { low = 2; high = 5; }
            case OVERCAST -> { low = 2; high = 6; }
            case WINDY, GROUND_SNOW -> { low = 8; high = 14; }
            case LIGHT_SNOW -> { low = .6f; high = 2; }
            case SNOW -> { low = 0; high = 0; }
            case BLIZZARD -> { low = 12; high = 18; }
            case SEVERE_BLIZZARD -> { low = 21; high = 28; }
            default -> throw new IllegalArgumentException("Unknown weather");
        }
        return (float)(low + (high - low) * Math.clamp(unit, 0, 1));
    }

    /** Direction changes the advected air mass. Wind chill then uses this actual temperature and speed. */
    public static float airTemperature(double latitude, double longitude, double dayOfYear, double localHour,
                                       WeatherPreset weather, WindDirection direction) {
        double north = Math.clamp((latitude - 60) / 20, 0, 1);
        double coast = Math.clamp((longitude + 120) / 55, 0, 1);
        double annualMean = -6 - 11 * north;
        double amplitude = 20 - 5 * coast;
        double seasonal = annualMean + amplitude * Math.cos((dayOfYear - 205) * Math.PI * 2 / 365.2425);
        double clear = 1 - weather.profile().cloudCover();
        double daily = (1.0 + 2.2 * clear) * Math.cos((localHour - 15) * Math.PI * 2 / 24);
        double front = switch (weather) {
            case BLIZZARD -> -1.5;
            case SEVERE_BLIZZARD -> -3.5;
            case GROUND_SNOW -> -2;
            case CLEAR -> -1;
            default -> 0;
        };
        return (float)Math.clamp(seasonal + daily + front + direction.temperatureOffsetC(), -65, 25);
    }
}
