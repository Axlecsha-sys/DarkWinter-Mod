// All skips are proven equivalents of Block.shouldRenderFace == false for full occluders.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.voxel.VoxelFaceRuntime;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelBlockRenderer.class)
abstract class VoxelVanillaFaceMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private void darkwinter$nativeCullOnlyVerifiedCubePairs(BlockAndTintGetter level,
            BlockState state, Direction direction, BlockPos neighborPos,
            CallbackInfoReturnable<Boolean> result) {
        if (VoxelFaceRuntime.maySkipNeighbor(level, state, neighborPos, direction)) {
            result.setReturnValue(false);
        }
    }
}
