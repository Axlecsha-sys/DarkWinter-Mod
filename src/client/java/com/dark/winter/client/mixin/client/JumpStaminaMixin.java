// Applies the player stamina rules when a native ground jump begins.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SprintStamina;
import com.dark.winter.client.interaction.HousePassageClient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class JumpStaminaMixin {

    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void darkwinter$jumpBreath(CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player)
            com.dark.winter.client.voice.BreathingClient.onJump();
    }

    @Inject(
            method = "jumpFromGround",
            at = @At("HEAD"),
            cancellable = true
    )
    private void darkwinter$jumpStamina(CallbackInfo ci) {

        Minecraft client = Minecraft.getInstance();

        // Проверяем, что прыгает именно наш игрок
        if (client.player == null
                || (Object) this != client.player) {
            return;
        }

        if (HousePassageClient.restrictMovement() || !SprintStamina.tryJump(
                client.player.tickCount,
                client.player.isSprinting()
        )) {
            ci.cancel();
        }
    }
}
