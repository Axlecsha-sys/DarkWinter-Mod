// Avoid a second gait transform in the projection while the weighted shoulder camera owns movement.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.ShoulderCameraState;
import com.dark.winter.client.motion.GaitRuntime;
import com.dark.winter.client.motion.MotionConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoid a second gait transform in the projection while the weighted shoulder camera owns movement. */
@Mixin(GameRenderer.class)
public abstract class ShoulderViewBobMixin {
    // Descriptor verified against the installed Mojang-mapped Minecraft 26.3 source.
    @Inject(method = "bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$singleCameraGait(CameraRenderState state, PoseStack pose, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        var settings = MotionConfig.get();
        if (!state.isFirstPerson && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && client.getCameraEntity() == client.player && GaitRuntime.posture(client.player)
                && settings.enabled() && settings.camera() && settings.cameraWeight() > 0
                && !ShoulderCameraState.isManualToFirst()) {
            ci.cancel();
        }
    }
}
