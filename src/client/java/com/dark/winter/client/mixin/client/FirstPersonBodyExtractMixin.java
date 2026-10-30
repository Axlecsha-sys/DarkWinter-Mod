// Extracts the local player body into the first-person entity render state.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.CharacterVisuals;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class FirstPersonBodyExtractMixin {
    @Shadow @Final private LevelRenderer levelRenderer;
    @Inject(method="extractVisibleEntities",at=@At("TAIL"),require=1)
    private void darkwinter$extractOwnBody(Camera camera,Frustum frustum,DeltaTracker ticks,LevelRenderState output,CallbackInfo ci) {
        CharacterVisuals.extractBody(camera,ticks,levelRenderer,output);
    }
}
