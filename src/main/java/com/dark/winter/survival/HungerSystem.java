// Reads and updates hunger and satiety through native player food state.

package com.dark.winter.survival;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

public final class HungerSystem {

    private HungerSystem() {}

    // Голод для HUD: от 0 до 100
    public static float getHungerPercent(Player player) {
        FoodData food = player.getFoodData();

        return (20 - food.getFoodLevel()) * 5.0F;
    }

    // Насколько игрок сыт: от 0 до 100
    public static float getSatietyPercent(Player player) {
        return 100.0F - getHungerPercent(player);
    }

    // Восстановление сытости специальной едой
    public static void feed(
            ServerPlayer player,
            int nutrition,
            float saturationModifier
    ) {
        if (nutrition <= 0) {
            return;
        }

        player.getFoodData().eat(
                nutrition,
                Math.max(0.0F, saturationModifier)
        );
    }

    // Дополнительная нагрузка на организм
    public static void addExhaustion(
            ServerPlayer player,
            float amount
    ) {
        if (amount > 0.0F) {
            player.getFoodData().addExhaustion(amount);
        }
    }
}
