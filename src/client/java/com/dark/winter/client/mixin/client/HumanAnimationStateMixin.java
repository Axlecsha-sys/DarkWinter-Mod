// Captures human motion and third-person animation state during entity extraction.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.equipment.HumanAnimations;
import com.dark.winter.client.equipment.ThirdPersonAnimations;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntityRenderer.class)
public abstract class HumanAnimationStateMixin {
    @Inject(method="extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",at=@At("TAIL"),require=1)
    private void darkwinter$humanMotion(LivingEntity entity,LivingEntityRenderState state,float partial,CallbackInfo ci) {HumanAnimations.extract(entity,state,partial);ThirdPersonAnimations.extract(entity,state,partial);}
}
