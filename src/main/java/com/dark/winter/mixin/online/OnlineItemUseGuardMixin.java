// Checks authenticated island permissions before native item use or placement.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineNativeRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ItemStack.class,priority=2000)
public abstract class OnlineItemUseGuardMixin {
    @Inject(method="useOn",at=@At("HEAD"),cancellable=true)
    private void darkwinter$placePermission(UseOnContext context,CallbackInfoReturnable<InteractionResult> cir) {
        if(context.getPlayer() instanceof ServerPlayer server&&!OnlineNativeRules.item(server,(ItemStack)(Object)this))cir.setReturnValue(InteractionResult.FAIL);
    }
    @Inject(method="use",at=@At("HEAD"),cancellable=true)
    private void darkwinter$usePermission(Level level,Player player,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir) {
        if(player instanceof ServerPlayer server&&!OnlineNativeRules.item(server,(ItemStack)(Object)this))cir.setReturnValue(InteractionResult.FAIL);
    }
}
