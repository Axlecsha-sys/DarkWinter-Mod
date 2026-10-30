// Checks authenticated island interaction permissions before native block use.
package com.dark.winter.mixin.online;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class OnlineBlockUseGuardMixin {
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void darkwinter$blockWithItem(ItemStack stack,Level level,Player player,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> cir) {
        if(player instanceof ServerPlayer server&&!OnlineGameHost.nativeAllowed(server,Permission.INTERACT))cir.setReturnValue(InteractionResult.PASS);
    }
    @Inject(method="useWithoutItem",at=@At("HEAD"),cancellable=true)
    private void darkwinter$blockWithoutItem(Level level,Player player,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> cir) {
        if(player instanceof ServerPlayer server&&!OnlineGameHost.nativeAllowed(server,Permission.INTERACT))cir.setReturnValue(InteractionResult.PASS);
    }
}
