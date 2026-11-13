package com.dark.winter.mixin.placement;
import com.dark.winter.placement.AnchorRuntime;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AnchorSupportMixin {
    @Inject(method="isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",at=@At("HEAD"),cancellable=true)
    private void darkwinter$actualSupport(BlockGetter level,BlockPos pos,Direction face,SupportType type,CallbackInfoReturnable<Boolean> cir){
        if(AnchorRuntime.support(level,pos,face))cir.setReturnValue(true);
    }
}
