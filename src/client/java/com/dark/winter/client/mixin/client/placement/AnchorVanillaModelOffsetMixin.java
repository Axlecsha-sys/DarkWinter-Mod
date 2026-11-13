package com.dark.winter.client.mixin.client.placement;

import com.dark.winter.placement.AnchorRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the saved physical translation using the explicit section/world view. */
@Mixin(ModelBlockRenderer.class)
abstract class AnchorVanillaModelOffsetMixin {
    @Unique private boolean darkwinter$anchoredModel;

    @ModifyExpressionValue(method = "tesselateBlock", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/block/state/BlockState;getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1, allow = 1)
    private Vec3 darkwinter$translateModel(Vec3 nativeOffset, BlockQuadOutput output,
            float x, float y, float z, BlockAndTintGetter level, BlockPos pos,
            BlockState state, BlockStateModel model, long seed) {
        Vec3 anchor = AnchorRuntime.offset(level, pos);
        boolean translated = anchor.x != 0 || anchor.y != 0 || anchor.z != 0;
        darkwinter$anchoredModel = translated || AnchorRuntime.rotation(level, pos) != 0;
        return translated ? nativeOffset.add(anchor) : nativeOffset;
    }

    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$keepExposedFace(BlockAndTintGetter level, BlockState state,
            Direction direction, BlockPos neighborPos, CallbackInfoReturnable<Boolean> callback) {
        if (darkwinter$anchoredModel) {
            callback.setReturnValue(true);
            return;
        }
        Vec3 neighbor = AnchorRuntime.offset(level, neighborPos);
        if (neighbor.x != 0 || neighbor.y != 0 || neighbor.z != 0 || AnchorRuntime.rotation(level, neighborPos) != 0)
            callback.setReturnValue(true);
    }
}
