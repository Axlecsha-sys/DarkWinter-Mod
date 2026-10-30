// Keep the world's real conversion counts, progress and cancellation; change only its bar.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.loading.NorthernLoadingArt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.worldselection.FileFixerProgressScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the world's real conversion counts, progress and cancellation; change only its bar. */
@Mixin(FileFixerProgressScreen.class)
public abstract class LoadingProgressBarMixin {
    @Inject(method="extractProgressBar",at=@At("HEAD"),cancellable=true)
    private void darkwinter$conversionProgress(GuiGraphicsExtractor g,int centerX,int y,float progress,CallbackInfo ci) {
        NorthernLoadingArt.progressLine(g,centerX-100,y,200,progress);
        ci.cancel();
    }
}
