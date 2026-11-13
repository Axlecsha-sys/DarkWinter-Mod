package com.winterkore.engine.surface.host.mixin;

import com.winterkore.engine.surface.host.SnowCollisionSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Context-only entity support. Placement, cached world shapes and generation retain their original path. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class SnowEntityCollisionMixin {
    @Inject(method="getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",at=@At("RETURN"),cancellable=true,require=1)
    private void wke$snowSupport(BlockGetter blocks,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> cir){
        if(blocks instanceof Level level)cir.setReturnValue(SnowCollisionSupport.shape((BlockState)(Object)this,level,pos,context,cir.getReturnValue()));
    }
}
