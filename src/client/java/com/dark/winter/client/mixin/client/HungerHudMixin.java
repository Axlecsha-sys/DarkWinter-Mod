// Suppresses the vanilla food display when the custom survival HUD owns it.

package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HungerHudMixin {

    @Inject(
            method = "extractFood",
            at = @At("HEAD"),
            cancellable = true
    )
    private void darkwinter$hideVanillaFood(
            GuiGraphicsExtractor graphics,
            Player player,
            int yLineBase,
            int xRight,
            CallbackInfo ci
    ) {
        ci.cancel();
    }
}
