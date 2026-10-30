// Maintains client-side temperature, thirst, hunger, and fatigue reserves.

package com.dark.winter.client;

import com.dark.winter.survival.HungerSystem;
import net.minecraft.client.Minecraft;

public final class SurvivalNeeds {

    // Пока демонстрационные значения
    private static float temperature = 75.0F;
    private static float fatigue = 10.0F;

    private SurvivalNeeds() {}

    public static float getTemperature() {
        return temperature;
    }

    public static float getHunger() {
        var player = Minecraft.getInstance().player;

        if (player == null) {
            return 0.0F;
        }

        return HungerSystem.getHungerPercent(player);
    }

    public static float getFatigue() {
        return fatigue;
    }

    public static void setTemperature(float value) {
        temperature = clamp(value);
    }

    public static void setFatigue(float value) {
        fatigue = clamp(value);
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(100.0F, value));
    }

    private static float thirst = 100.0F;

    public static float getThirst() {
        return thirst;
    }

    public static void setThirst(float value) {
        thirst = Math.max(0.0F, Math.min(100.0F, value));
    }
}
