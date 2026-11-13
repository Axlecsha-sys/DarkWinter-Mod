// Masks only worker render snapshots outside the chosen chunk rectangle or above the preview cut plane.
package com.dark.winter.biomeeditor.mixin;
import com.dark.winter.biomeeditor.BiomeEditorWorker;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(RenderSectionRegion.class)
public abstract class EditorPreviewBoundsMixin {
    @Inject(method="getBlockState",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$cutaway(BlockPos pos,CallbackInfoReturnable<BlockState> cir){if(BiomeEditorWorker.clip(pos))cir.setReturnValue(Blocks.AIR.defaultBlockState());}
    @Inject(method="getFluidState",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$cutawayFluid(BlockPos pos,CallbackInfoReturnable<FluidState> cir){if(BiomeEditorWorker.clip(pos))cir.setReturnValue(Fluids.EMPTY.defaultFluidState());}
}
