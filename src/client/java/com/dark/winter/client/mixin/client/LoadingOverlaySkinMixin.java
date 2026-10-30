// Styles the loading overlay without changing reload progress, errors, or completion.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.loading.StartupLoadingArt;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drawing only. Original reload progress, fades, readiness, errors and completion still execute. */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlaySkinMixin {
    @Shadow private float currentProgress;

    @Inject(method = "registerTextures", at = @At("TAIL"))
    private static void darkwinter$bootstrap(TextureManager textures, CallbackInfo ci) {
        StartupLoadingArt.register(textures);
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Ljava/util/function/IntSupplier;getAsInt()I"), require = 3, expect = 3, allow = 3)
    private int darkwinter$background(IntSupplier original) { return StartupLoadingArt.BACKGROUND; }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
            ordinal = 0))
    private void darkwinter$wordmark(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier texture,
                                    int x, int y, float u, float v, int w, int h,
                                    int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color) {
        StartupLoadingArt.brand(g, pipeline, color);
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
            ordinal = 1))
    private void darkwinter$secondLogoHalf(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier texture,
                                          int x, int y, float u, float v, int w, int h,
                                          int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color) {
        // The DarkWinter wordmark is one image, unlike the original two-piece logo.
    }

    @Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
    private void darkwinter$progress(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                                    float fade, CallbackInfo ci) {
        StartupLoadingArt.progress(g, this.currentProgress, fade);
        ci.cancel();
    }
}
