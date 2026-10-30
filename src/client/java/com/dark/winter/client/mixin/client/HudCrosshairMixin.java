// Controls crosshair visibility for menus and shoulder-camera interaction.

package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.gui.Hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public abstract class HudCrosshairMixin {

    @org.spongepowered.asm.mixin.injection.Inject(method="extractCrosshair",at=@At("HEAD"),cancellable=true)
    private void darkwinter$hideInPause(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) ci.cancel();
    }

    @ModifyExpressionValue(
            method = "extractCrosshair",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"
            )
    )
    private boolean darkwinter$showCrosshair(
            boolean original
    ) {
        return true;
    }
}
