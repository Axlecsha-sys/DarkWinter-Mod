// Renders authenticated online avatars with the selected human model.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.equipment.CharacterVisuals;
import com.dark.winter.client.equipment.FemaleHumanModel;
import com.dark.winter.client.online.OnlineHumanAppearance;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderer.class)
public abstract class OnlineHumanModelMixin {
    @Shadow protected EntityModel<?> model;
    @Unique private PlayerModel darkwinter$legacyHuman;
    @Unique private PlayerModel darkwinter$femaleHuman;
    @WrapMethod(method="submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void darkwinter$managedHuman(LivingEntityRenderState state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera,Operation<Void> vanilla){
        if(!((Object)this instanceof AvatarRenderer<?>)||!(state instanceof AvatarRenderState avatar)
                ||CharacterVisuals.isBody(avatar)||!(model instanceof PlayerModel)){
            vanilla.call(state,poses,collector,camera);return;
        }
        EntityModel<?> before=model;
        if(darkwinter$legacyHuman==null)darkwinter$legacyHuman=(PlayerModel)before;
        try{
            if(OnlineHumanAppearance.female(avatar)){
                if(darkwinter$femaleHuman==null){
                    boolean slim=((PlayerModelAccessor)darkwinter$legacyHuman).darkwinter$isSlim();
                    darkwinter$femaleHuman=new PlayerModel(FemaleHumanModel.createRoot(slim),slim);
                }
                model=darkwinter$femaleHuman;
            }else model=darkwinter$legacyHuman;
            vanilla.call(state,poses,collector,camera);
        }finally{model=before;}
    }
}
