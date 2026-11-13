// Applies the DarkWinter hunger policy to native sprint eligibility.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.dark.winter.client.interaction.HousePassageClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Убираем только ванильную проверку сытости для начала/продолжения
 * спринта. Система голода и другие ограничения не изменяются.
 */
@Mixin(LocalPlayer.class)
public abstract class HungerSprintMixin {
    @ModifyReturnValue(method = "isSprintingPossible", at = @At("RETURN"))
    private boolean darkwinter$restrictIndoorSprint(boolean allowed) {
        return allowed && !HousePassageClient.restrictMovement();
    }


    @ModifyExpressionValue(
            method = "isSprintingPossible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;hasEnoughFoodToDoExhaustiveManoeuvres()Z"
            )
    )
    private boolean darkwinter$allowSprintingWhenHungry(boolean vanillaAllowed) {
        return true;
    }
}
