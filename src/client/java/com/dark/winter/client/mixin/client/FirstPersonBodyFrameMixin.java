// Captures first-person body diagnostics while frame-local entity snapshots remain valid.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.CharacterVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture evidence while this frame's entity snapshots are still alive. */
@Mixin(LevelRenderer.class)
public abstract class FirstPersonBodyFrameMixin {
    @Inject(method="submitEntities",at=@At("TAIL"),require=1)
    private void darkwinter$completeBodyFrame(PoseStack poses,LevelRenderState state,SubmitNodeCollector output,CallbackInfo ci) {
        CharacterVisuals.finishBodyFrame(state);
    }
}
