package com.dark.winter.client.mixin.client.placement;

import com.dark.winter.client.placement.AnchorRenderState;
import com.dark.winter.placement.AnchorRuntime;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderState.class)
abstract class AnchorBlockEntityStateMixin implements AnchorRenderState {
    @Unique private Vec3 darkwinter$physicalOffset = Vec3.ZERO;
    @Unique private double darkwinter$physicalRotation;

    @Inject(method = "extractBase", at = @At("RETURN"), require = 1)
    private static void darkwinter$captureOffset(BlockEntity blockEntity, BlockEntityRenderState state,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, CallbackInfo callback) {
        var level = blockEntity.getLevel();
        var anchor = (AnchorRenderState) state;
        anchor.darkwinter$anchorOffset(level == null ? Vec3.ZERO : AnchorRuntime.offset(level, blockEntity.getBlockPos()));
        anchor.darkwinter$anchorRotation(level == null ? 0 : AnchorRuntime.rotation(level, blockEntity.getBlockPos()));
    }

    @Override
    public Vec3 darkwinter$anchorOffset() {
        return darkwinter$physicalOffset;
    }

    @Override
    public void darkwinter$anchorOffset(Vec3 offset) {
        darkwinter$physicalOffset = offset;
    }

    @Override
    public double darkwinter$anchorRotation() {
        return darkwinter$physicalRotation;
    }

    @Override
    public void darkwinter$anchorRotation(double rotation) {
        darkwinter$physicalRotation = rotation;
    }
}
