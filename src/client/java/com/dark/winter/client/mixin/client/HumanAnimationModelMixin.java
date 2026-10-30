// Applies authored human joint animation after native player-model setup.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.equipment.HumanAnimations;
import com.dark.winter.client.equipment.ThirdPersonAnimations;
import com.dark.winter.client.equipment.ThirdPersonBodyModel;
import com.dark.winter.client.equipment.AppearanceRenderState;
import com.dark.winter.client.equipment.ClothingLayer;
import com.dark.winter.equipment.GearSlot;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerModel.class)
public abstract class HumanAnimationModelMixin {
    @Inject(method="setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",at=@At("TAIL"),require=1)
    private void darkwinter$animateHuman(AvatarRenderState state,CallbackInfo ci) {
        PlayerModel model=(PlayerModel)(Object)this;
        if(ThirdPersonBodyModel.isThirdPerson(model))
            ThirdPersonAnimations.apply(model,state,((FabricRenderState)state).getData(ThirdPersonAnimations.POSE));
        else HumanAnimations.apply(model,state,((FabricRenderState)state).getData(HumanAnimations.POSE));
        if(model.root().hasChild("darkwinter_human")) {
            Integer outfit=((FabricRenderState)state).getData(AppearanceRenderState.OUTFIT);
            int hat=outfit==null?0:ClothingLayer.variant(outfit,GearSlot.HAT);
            boolean thirdPerson=ThirdPersonBodyModel.isThirdPerson(model);
            model.head.getChild("hair").visible=hat==0||(thirdPerson&&hat>=5&&hat!=11);
            model.head.getChild("beard").visible=!thirdPerson||outfit==null||(ClothingLayer.variant(outfit,GearSlot.JACKET)!=7&&hat!=11);
        }
    }
}
