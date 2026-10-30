// Guards native entity drops during controlled Testing edits.
package com.dark.winter.mixin;
import com.dark.winter.testing.TestingNativeEdits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerLevel.class)
public abstract class TestingNativeDropGuardMixin {
    @Inject(method="addFreshEntity",at=@At("HEAD"),cancellable=true)
    private void darkwinter$drop(Entity entity,CallbackInfoReturnable<Boolean> cir){
        if(TestingNativeEdits.suppressDrop((ServerLevel)(Object)this,entity))cir.setReturnValue(false);
    }
}
