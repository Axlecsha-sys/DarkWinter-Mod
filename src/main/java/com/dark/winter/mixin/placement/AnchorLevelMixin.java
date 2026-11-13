package com.dark.winter.mixin.placement;
import com.dark.winter.placement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
@Mixin(Level.class)
public abstract class AnchorLevelMixin {
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean darkwinter$changed(BlockPos pos,BlockState after,int flags,int depth,Operation<Boolean> original){
        Level level=(Level)(Object)this;BlockState before=level.getBlockState(pos);
        SurfacePlacement.beforeChange(level,pos,before);
        boolean changed=original.call(pos,after,flags,depth);
        if(changed&&before!=after){
            AnchorRuntime.blockChanged(level,pos,before,level.getBlockState(pos));
            com.winterkore.engine.surface.host.SnowSurfaceRuntime.blockChanged(level,pos,before,level.getBlockState(pos));
        }return changed;
    }
}
