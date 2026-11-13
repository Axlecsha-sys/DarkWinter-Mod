// Publishes one outline snapshot per completed native world-extraction frame, including a miss.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.UiPreferences;
import com.dark.winter.client.highlight.ModelHighlight;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class ModelHighlightExtractMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method="extractBlockOutline",at=@At("HEAD"),cancellable=true)
    private void darkwinter$hideOutlineOutsideCreative(Camera camera, LevelRenderState state, CallbackInfo ci) {
        if (UiPreferences.creativeItemHighlight() && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() == GameType.CREATIVE) return;
        state.blockOutlineRenderState = null;
        ci.cancel();
    }

    @Inject(method="extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",at=@At("TAIL"))
    private void darkwinter$extractModelOutline(DeltaTracker tracker,Camera camera,float worldPartialTicks,CallbackInfo ci){
        ModelHighlight.extract(camera,levelRenderState);
    }
}
