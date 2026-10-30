// Integrates measured native level-loading progress with the custom loading presentation.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.loading.NorthernLoadingArt;
import com.dark.winter.client.loading.WorldPreloadClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MC 26.3 methods verified in the installed Mojang-mapped client sources. */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
    @Shadow private LevelLoadTracker loadTracker;
    @Shadow private LevelLoadingScreen.Reason reason;
    @Shadow private float smoothedProgress;
    @Unique private long darkwinter$lastNarration;

    protected LevelLoadingScreenMixin(Component title) { super(title); }

    @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true)
    private void darkwinter$landscape(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        NorthernLoadingArt.background(graphics, this.width, this.height);
        ci.cancel();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true)
    private void darkwinter$progress(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        boolean localPreload = WorldPreloadClient.northernLoading(this.reason);
        if (localPreload) {
            NorthernLoadingArt.foreground(graphics, this.font, this.width, this.height, WorldPreloadClient.progress(),
                    WorldPreloadClient.displayedProgress(this.loadTracker.serverProgress()), this.loadTracker.hasProgress());
        } else {
            NorthernLoadingArt.transferForeground(graphics, this.width, this.height,
                    this.smoothedProgress, this.loadTracker.hasProgress());
        }
        // Keep Fabric-added buttons and standard screen keyboard/mouse handling.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        long now = net.minecraft.util.Util.getMillis();
        if (now - this.darkwinter$lastNarration >= 2000) {
            this.darkwinter$lastNarration = now;
            var progress = localPreload ? WorldPreloadClient.progress() : null;
            if (progress != null) {
                this.minecraft.getNarrator().saySystemNow(Component.translatable("loading.darkwinter.count", progress.completed(), progress.total()));
            } else {
                this.triggerImmediateNarration(true);
            }
        }
        ci.cancel();
    }

    @Inject(method = "onClose()V", at = @At("HEAD"), cancellable = true)
    private void darkwinter$waitForSurroundings(CallbackInfo ci) {
        if (!WorldPreloadClient.northernLoading(this.reason)) return;
        if (WorldPreloadClient.active()) ci.cancel();
        else WorldPreloadClient.loadingScreenClosed();
    }
}
