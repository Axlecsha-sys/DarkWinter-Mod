// Observes native block and block-entity mutations inside Testing edit transactions.
package com.dark.winter.mixin;
import com.dark.winter.testing.TestingNativeEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Level.class)
public abstract class TestingNativeBlockObserverMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void darkwinter$before(BlockPos pos,BlockState state,int flags,int limit,CallbackInfoReturnable<Boolean> cir){
        if(!TestingNativeEdits.before((Level)(Object)this,pos))cir.setReturnValue(false);
    }
    @Inject(method="removeBlockEntity",at=@At("HEAD"),cancellable=true)
    private void darkwinter$beforeEntityRemoval(BlockPos pos,CallbackInfo ci){
        if(!TestingNativeEdits.before((Level)(Object)this,pos))ci.cancel();
    }
}
