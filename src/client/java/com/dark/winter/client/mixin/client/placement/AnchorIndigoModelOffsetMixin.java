package com.dark.winter.client.mixin.client.placement;

import com.dark.winter.placement.AnchorRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers Fabric's actual section compiler path, including DarkWinter polygon meshes. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
abstract class AnchorIndigoModelOffsetMixin {
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow @Final private BlockPos.MutableBlockPos scratchPos;
    @Unique private boolean darkwinter$anchoredModel;

    @ModifyExpressionValue(method = "tesselateBlock", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/block/state/BlockState;getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;",
            remap = false), remap = false, require = 1, allow = 1)
    private Vec3 darkwinter$translateModel(Vec3 nativeOffset, QuadEmitter output,
            float x, float y, float z, BlockAndTintGetter view, BlockPos blockPos,
            BlockState state, BlockStateModel model, long seed) {
        Vec3 anchor = AnchorRuntime.offset(view, blockPos);
        boolean translated = anchor.x != 0 || anchor.y != 0 || anchor.z != 0;
        darkwinter$anchoredModel = translated || AnchorRuntime.rotation(view, blockPos) != 0;
        return translated ? nativeOffset.add(anchor) : nativeOffset;
    }

    @Inject(method = "shouldCullFace", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void darkwinter$keepExposedFace(Direction direction, CallbackInfoReturnable<Boolean> callback) {
        if (direction == null) return;
        if (darkwinter$anchoredModel) {
            callback.setReturnValue(false);
            return;
        }
        BlockPos neighborPos = scratchPos.setWithOffset(pos, direction);
        Vec3 neighbor = AnchorRuntime.offset(level, neighborPos);
        if (neighbor.x != 0 || neighbor.y != 0 || neighbor.z != 0 || AnchorRuntime.rotation(level, neighborPos) != 0)
            callback.setReturnValue(false);
    }
}
