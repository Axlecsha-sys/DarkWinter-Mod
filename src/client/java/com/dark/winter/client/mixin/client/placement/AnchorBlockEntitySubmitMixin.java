package com.dark.winter.client.mixin.client.placement;

import com.dark.winter.client.placement.AnchorRenderState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies the extracted anchor transform before any local block-entity animation. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class AnchorBlockEntitySubmitMixin {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"),
            require = 1, allow = 1)
    private void darkwinter$translateBlockEntity(BlockEntityRenderer<?, ?> renderer,
            BlockEntityRenderState state, PoseStack poses, SubmitNodeCollector collector,
            CameraRenderState camera, Operation<Void> original) {
        var anchor = (AnchorRenderState) state;
        Vec3 offset = anchor.darkwinter$anchorOffset();
        double rotation = anchor.darkwinter$anchorRotation();
        if (offset.x == 0 && offset.y == 0 && offset.z == 0 && rotation == 0) {
            original.call(renderer, state, poses, collector, camera);
            return;
        }
        poses.pushPose();
        poses.translate(offset.x, offset.y, offset.z);
        if (rotation != 0) poses.rotate(Axis.YN, (float) rotation);
        try {
            original.call(renderer, state, poses, collector, camera);
        } finally {
            poses.popPose();
        }
    }
}
