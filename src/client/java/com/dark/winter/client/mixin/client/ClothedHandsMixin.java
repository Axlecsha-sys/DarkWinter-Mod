// Renders clothing-compatible first-person hands through the existing avatar renderer.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.LegacyFirstPersonModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class ClothedHandsMixin {
    @Inject(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$legacyFirstPersonHand(PoseStack poses, SubmitNodeCollector collector, int light, Identifier skin,
                                                  ModelPart arm, boolean sleeve, CallbackInfo ci) {
        var model = ((AvatarRenderer<?>) (Object) this).getModel();
        HumanoidArm side = arm == model.leftArm ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        LegacyFirstPersonModel.submit(model, side, sleeve, Minecraft.getInstance().player, poses, collector, light, skin);
        ci.cancel();
    }
}
