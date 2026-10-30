// Blocks native item interactions while Testing editor operations hold an edit lock.
package com.dark.winter.mixin;

import com.dark.winter.testing.TestingServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class TestingEditLockMixin {
    @Inject(method="useItem",at=@At("HEAD"),cancellable=true)
    private void darkwinter$lockItem(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir) {
        if(TestingServer.busy(level.getServer()))cir.setReturnValue(InteractionResult.FAIL);
    }
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void darkwinter$lockUse(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> cir) {
        if(TestingServer.busy(level.getServer()))cir.setReturnValue(InteractionResult.FAIL);
    }
}
