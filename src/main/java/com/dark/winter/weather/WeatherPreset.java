// Defines named weather targets for the DarkWinter atmosphere.
package com.dark.winter.weather;

import java.util.Locale;

/** Overcast is calibrated to the original DarkWinter atmosphere. */
public enum WeatherPreset {
    OVERCAST(       1,    0, .12f, 1,    1,    0,    1,    1,    0,    0),
    CLEAR(          0,    0, .08f, .22f, 1.10f,1,    1,    1,    .95f, 1),
    PARTLY_CLOUDY( .32f,  0, .14f, .48f, 1.03f,1,    .70f, 1,    .55f,.65f),
    WINDY(         .18f,  0, .72f, .15f, 1.04f,.35f, 1.35f,3,    1,   .80f),
    LIGHT_SNOW(    .42f,.035f,.06f,.65f, .88f, .70f, .70f, 1,    .60f,.45f),
    SNOW(          .82f,.38f,0,   1.35f,.55f, .30f, 1.15f,1.45f,.35f,.15f),
    BLIZZARD(       1,    1, 1,   3.8f, .10f, 0,    .65f, 2.2f, .25f,.03f),
    SEVERE_BLIZZARD(1,1.65f,1.45f,6.7f,.04f, 0,    .52f, 3.4f, .22f,0),
    GROUND_SNOW(  .25f,   0,.65f, .38f,.95f, .40f, 1.20f,3.4f, .75f,.60f);

    private final WeatherValues profile;
    WeatherPreset(float cover, float snow, float wind, float fog, float sun, float roundness,
                  float scale, float stretch, float fogBlue, float skyBlue) {
        // Actual strength and direction are overlaid from the shared climate timeline.
        profile = new WeatherValues(cover, snow, wind, .94f, .34f, fog, sun,
                roundness, scale, stretch, fogBlue, skyBlue);
    }
    public WeatherValues profile() { return profile; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String translationKey() { return "weather.darkwinter." + id(); }
    public static WeatherPreset byId(String id) {
        for (WeatherPreset preset : values()) if (preset.id().equals(id)) return preset;
        throw new IllegalArgumentException("Unknown DarkWinter weather: " + id);
    }
}
