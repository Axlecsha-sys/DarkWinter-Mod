// Aligns ordinary and crossbow item submissions with the authored first-person grip.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.FirstPersonGripRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both actual 26.3 ordinary/crossbow item submissions; map hands and empty hands keep their own path. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonItemGripMixin {
    @Inject(method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"), require = 2)
    private void darkwinter$gripExistingItemPose(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                                float partialTick, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                                float equip, PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        poses.pushPose();
        var avatar = player.avatarRenderState;
        if (avatar != null) {
            var arm = hand == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite();
            FirstPersonGripRenderer.frameTool(stack, arm, poses);
            FirstPersonGripRenderer.submit(avatar, arm, stack,
                    hand == InteractionHand.MAIN_HAND ? hands.mainHandRenderState : hands.offHandRenderState, poses, collector, light);
        }
    }

    @Inject(method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V", shift = At.Shift.AFTER), require = 2)
    private void darkwinter$restoreExistingItemPose(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                                   float partialTick, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                                   float equip, PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        poses.popPose();
    }
}
