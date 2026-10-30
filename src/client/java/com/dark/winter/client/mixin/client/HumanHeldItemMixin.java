// Attaches held tools to the authored human hand pose.
package com.dark.winter.client.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep held tools in the new palm, rather than at the old short biped's wrist. */
@Mixin(PlayerModel.class)
public abstract class HumanHeldItemMixin {
    @Inject(method="translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void darkwinter$humanPalm(AvatarRenderState state,HumanoidArm arm,PoseStack poses,CallbackInfo ci) {
        PlayerModel model=(PlayerModel)(Object)this;
        if(!model.root().hasChild("darkwinter_human"))return;
        model.root().translateAndRotate(poses);
        model.getArm(arm).translateAndRotate(poses);
        var limb=model.getArm(arm);
        if(limb.hasChild("forearm"))com.dark.winter.client.equipment.HumanJoints.forearm(limb).translateAndRotate(poses);
        if(com.dark.winter.client.equipment.ThirdPersonBodyModel.isThirdPerson(model))
            com.dark.winter.client.equipment.ThirdPersonBodyModel.handOrigin(poses,arm==HumanoidArm.RIGHT);
        else poses.translate((arm==HumanoidArm.RIGHT?1:-1)/16f,(1.7f-com.dark.winter.client.equipment.HumanJoints.ELBOW_Y)/16f,0);
        ci.cancel();
    }
}
