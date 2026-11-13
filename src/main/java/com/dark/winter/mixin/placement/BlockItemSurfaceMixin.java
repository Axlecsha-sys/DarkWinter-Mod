package com.dark.winter.mixin.placement;
import com.dark.winter.placement.SurfacePlacement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(BlockItem.class)
public abstract class BlockItemSurfaceMixin {
    @WrapMethod(method="place")
    private InteractionResult darkwinter$surfacePlace(BlockPlaceContext context,Operation<InteractionResult> original){
        return SurfacePlacement.place((BlockItem)(Object)this,context,value->original.call(value));
    }
    @WrapOperation(method="place",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/BlockItem;getPlacementState(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState darkwinter$updated(BlockItem item,BlockPlaceContext context,Operation<BlockState> original){
        SurfacePlacement.updatedContext(context);return original.call(item,context);
    }
    @WrapOperation(method="canPlace",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;isUnobstructed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Z"))
    private boolean darkwinter$checkEntities(Level level, BlockState state, BlockPos pos, CollisionContext context,
                                               Operation<Boolean> original) {
        return SurfacePlacement.isUnobstructed(level, state, pos, context);
    }
    @WrapOperation(method="place",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean darkwinter$validate(BlockItem item,BlockPlaceContext context,BlockState state,Operation<Boolean> original){
        return SurfacePlacement.validateCandidate(context,state)&&original.call(item,context,state);
    }
}
