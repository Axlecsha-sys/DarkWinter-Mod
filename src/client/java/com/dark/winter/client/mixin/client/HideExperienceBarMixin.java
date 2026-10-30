// Hides the experience bar while preserving locator and mount-jump bars.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hide only the experience contextual bar.
 * Locator and horse-jump bars remain functional.
 */
@Mixin(ExperienceBar.class)
public abstract class HideExperienceBarMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void darkwinter$hideExperienceBackground(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void darkwinter$hideExperienceProgress(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ci.cancel();
    }
}
