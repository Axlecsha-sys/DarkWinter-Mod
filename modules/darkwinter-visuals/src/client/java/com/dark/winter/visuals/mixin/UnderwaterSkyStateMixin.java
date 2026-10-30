// Captures underwater sky occlusion from the same extracted frame as terrain fog.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.render.UnderwaterSkyOccluder;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class UnderwaterSkyStateMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("TAIL"), require = 1)
    private void darkwinter$captureWaterSky(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
        UnderwaterSkyOccluder.capture(this.levelRenderState, camera.isPanoramicMode());
    }
}
