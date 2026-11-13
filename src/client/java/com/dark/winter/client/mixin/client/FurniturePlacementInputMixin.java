package com.dark.winter.client.mixin.client;

import com.dark.winter.client.FurniturePlacementControls;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class FurniturePlacementInputMixin {
    @Inject(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V", shift = At.Shift.AFTER))
    private void darkwinter$syncFurnitureControls(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                   CallbackInfoReturnable<InteractionResult> callback) {
        if (hand == InteractionHand.MAIN_HAND) FurniturePlacementControls.beforePlacement(player);
    }
}
