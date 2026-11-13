package com.dark.winter.client.mixin.client;

import com.dark.winter.client.FurniturePlacementPreviewRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class FurniturePreviewSubmitMixin {
    @Inject(method = "submitFeatures", at = @At("TAIL"))
    private void darkwinter$submitFurniturePreview(LevelRenderState state, SubmitNodeCollector collector,
                                                   boolean renderOutline, CallbackInfo ci) {
        FurniturePlacementPreviewRenderer.submit(collector, state.cameraRenderState);
    }
}
