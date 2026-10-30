// Routes fresh world creation through explicit DarkWinter mode selection.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.WorldModeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class WorldModeMixin {
    @Inject(method="onCreate",at=@At("HEAD"))
    private void darkwinter$prepareTestingCreation(CallbackInfo ci) {
        com.dark.winter.client.TestingWorlds.prepareCreation((CreateWorldScreen)(Object)this);
    }

    @Inject(method="createNewWorld",at=@At("HEAD"))
    private void darkwinter$confirmTestingCreation(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        com.dark.winter.client.TestingWorlds.confirmCreation(((CreateWorldScreen)(Object)this).getUiState().getTargetFolder());
    }

    @Inject(method="openFresh(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;)V",at=@At("HEAD"),cancellable=true)
    private static void darkwinter$chooseMode(Minecraft client,Runnable onClose,CallbackInfo ci) {
        if(client.gui.screen() instanceof SelectWorldScreen) {
            client.gui.setScreen(new WorldModeScreen(onClose));
            ci.cancel();
        }
    }
}
