// Controls custom hotbar visibility while retaining the vanilla hotbar in Testing worlds.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class DarkWinterHotbarMixin {
    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void darkwinter$hideHotbar(GuiGraphicsExtractor graphics, DeltaTracker ticks, CallbackInfo ci) {
        if (!com.dark.winter.client.TestingWorlds.isTesting()) ci.cancel();
    }
}
