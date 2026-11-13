package com.dark.winter.mixin.placement;
import com.dark.winter.placement.AnchorRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AnchorShapeMixin {
    @WrapMethod(method="getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    private VoxelShape darkwinter$outline(BlockGetter level,BlockPos pos,CollisionContext context,Operation<VoxelShape> original){
        if(!AnchorRuntime.hasTransform(level,pos))return original.call(level,pos,context);
        VoxelShape raw=AnchorRuntime.raw(()->original.call(level,pos,context));
        return AnchorRuntime.transform(level,pos,(BlockState)(Object)this,raw);
    }
    @WrapMethod(method="getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    private VoxelShape darkwinter$collision(BlockGetter level,BlockPos pos,CollisionContext context,Operation<VoxelShape> original){
        if(!AnchorRuntime.hasTransform(level,pos))return original.call(level,pos,context);
        VoxelShape raw=AnchorRuntime.raw(()->original.call(level,pos,context));
        return AnchorRuntime.transform(level,pos,(BlockState)(Object)this,raw);
    }
    @WrapMethod(method="getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    private VoxelShape darkwinter$cached(BlockGetter level,BlockPos pos,Operation<VoxelShape> original){
        if(!AnchorRuntime.hasTransform(level,pos))return original.call(level,pos);
        VoxelShape raw=AnchorRuntime.raw(()->original.call(level,pos));
        return AnchorRuntime.transform(level,pos,(BlockState)(Object)this,raw);
    }
    @WrapMethod(method="getInteractionShape")
    private VoxelShape darkwinter$interaction(BlockGetter level,BlockPos pos,Operation<VoxelShape> original){
        if(!AnchorRuntime.hasTransform(level,pos))return original.call(level,pos);
        return AnchorRuntime.transform(level,pos,(BlockState)(Object)this,AnchorRuntime.raw(()->original.call(level,pos)));
    }
}
