// Persists versioned laboratory settings inside each Testing save.
package com.dark.winter.testing;

import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned per-save settings. No ordinary world ever receives these overrides. */
public final class TestingSettings {
    public int schema = 2;
    public static final String[] MODULES = {"hud","weather","wind","precipitation","visuals","atmosphere","stamina","survival","motion","camera"};
    public final java.util.concurrent.ConcurrentMap<String, Boolean> modules = new java.util.concurrent.ConcurrentHashMap<>();
    public volatile String mode = "EDIT", biome = "snowy_taiga", weather = "clear", brush = "raise";
    public volatile int radius = 6, strength = 1, density = 8;
    public volatile String brushShape="circle",grassSpecies="auto",bushSpecies="auto",treeSpecies="auto";
    public volatile float windSpeed = 0, windDirection = 270, temperature = -5, flySpeed = .05f;
    public volatile float filmStrength = 1, hazeDensity = .005f, shadowStrength = .5f;
    public volatile float staminaCapacity=150,staminaSprintDrain=8,staminaWalkRegen=12,staminaIdleRegen=18,staminaJumpCost=10,staminaSprintJumpCost=18;
    public volatile double motionWalkSpeed=.82,motionCameraWeight=1,motionTurnWeight=1;
    public volatile boolean diagnostics;
    public TestingSettings() { for (String module : MODULES) modules.put(module, false); }
    public boolean enabled(String name) { return Boolean.TRUE.equals(modules.get(name)); }
    public void all(boolean on) { for (String module : MODULES) modules.put(module, on); }
    public String value(String key) {
        return switch(key) {
            case "mode" -> mode; case "biome" -> biome; case "weather" -> weather; case "brush" -> brush;
            case "radius" -> ""+radius; case "strength" -> ""+strength; case "density" -> ""+density;case "brushShape" -> brushShape;case "grassSpecies" -> grassSpecies;case "bushSpecies" -> bushSpecies;case "treeSpecies" -> treeSpecies;
            case "windSpeed" -> ""+windSpeed; case "windDirection" -> ""+windDirection;
            case "filmStrength" -> ""+filmStrength;case "hazeDensity" -> ""+hazeDensity;case "shadowStrength" -> ""+shadowStrength; case "temperature" -> ""+temperature; case "flySpeed" -> ""+flySpeed;
            case "staminaCapacity"->""+staminaCapacity;case "staminaSprintDrain"->""+staminaSprintDrain;case "staminaWalkRegen"->""+staminaWalkRegen;case "staminaIdleRegen"->""+staminaIdleRegen;
            case "staminaJumpCost"->""+staminaJumpCost;case "staminaSprintJumpCost"->""+staminaSprintJumpCost;
            case "motionWalkSpeed"->""+motionWalkSpeed;case "motionCameraWeight"->""+motionCameraWeight;case "motionTurnWeight"->""+motionTurnWeight;
            case "diagnostics" -> ""+diagnostics; default -> ""+enabled(key);
        };
    }
    public void validate() {
        if (schema != 2) throw new IllegalArgumentException("Unsupported settings schema " + schema);
        for (String key : java.util.Set.copyOf(modules.keySet()))
            if (!java.util.List.of(MODULES).contains(key)) modules.remove(key);
        for (String key : MODULES) modules.putIfAbsent(key, false);
        if(!java.util.Set.of("circle","square").contains(brushShape))throw new IllegalArgumentException("Кисть: circle/square");
        if(!java.util.Set.of("auto","minecraft:short_grass","minecraft:fern","minecraft:dead_bush").contains(grassSpecies)
                ||!java.util.Set.of("auto","minecraft:sweet_berry_bush","minecraft:azalea","minecraft:dead_bush").contains(bushSpecies)
                ||!java.util.Set.of("auto","minecraft:spruce","minecraft:oak","minecraft:birch").contains(treeSpecies))
            throw new IllegalArgumentException("Неподдерживаемый вид растений");
        radius = Math.clamp(radius,1,24); strength = Math.clamp(strength,1,8); density = Math.clamp(density,1,64);
        windDirection=finite(windDirection,0,360);filmStrength=finite(filmStrength,0,1);hazeDensity=finite(hazeDensity,0,.03f);shadowStrength=finite(shadowStrength,0,.7f);
        windSpeed = finite(windSpeed,0,26); temperature = finite(temperature,-60,45); flySpeed = finite(flySpeed,.01f,.5f);
        staminaCapacity=finite(staminaCapacity,1,1000);staminaSprintDrain=finite(staminaSprintDrain,0,1000);
        staminaWalkRegen=finite(staminaWalkRegen,0,1000);staminaIdleRegen=finite(staminaIdleRegen,0,1000);
        staminaJumpCost=finite(staminaJumpCost,0,1000);staminaSprintJumpCost=finite(staminaSprintJumpCost,0,1000);
        motionWalkSpeed=finiteDouble(motionWalkSpeed,.55,1);motionCameraWeight=finiteDouble(motionCameraWeight,0,1.6);motionTurnWeight=finiteDouble(motionTurnWeight,0,1.6);
        com.dark.winter.weather.WeatherPreset.valueOf(weather.toUpperCase(java.util.Locale.ROOT));
        net.minecraft.resources.Identifier.parse(biome.contains(":")?biome:"minecraft:"+biome);
    }
    private static double finiteDouble(double value,double min,double max){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite motion setting");return Math.clamp(value,min,max);
    }
    private static float finite(float v,float min,float max) {
        if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite setting");
        return Math.clamp(v,min,max);
    }
}
