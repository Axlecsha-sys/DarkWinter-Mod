// Fabric API's default Indigo path is separate from the vanilla ModelBlockRenderer.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.voxel.VoxelFaceRuntime;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
abstract class VoxelIndigoFaceMixin {
    @Shadow private @Nullable BlockAndTintGetter level;
    @Shadow private @Nullable BlockPos pos;
    @Shadow private @Nullable BlockState blockState;

    @Inject(method = "shouldCullFace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void darkwinter$nativeCullOnlyVerifiedCubePairs(@Nullable Direction direction,
            CallbackInfoReturnable<Boolean> result) {
        if (direction != null && level != null && pos != null && blockState != null &&
                VoxelFaceRuntime.maySkip(level, blockState, pos, direction)) {
            result.setReturnValue(true);
        }
    }
}
