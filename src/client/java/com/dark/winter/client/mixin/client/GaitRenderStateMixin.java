// Copies measured gait state into native living-entity render snapshots.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.motion.*;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class GaitRenderStateMixin {
    @Inject(method="extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at=@At("TAIL"),require=1)
    private void darkwinter$extractGait(LivingEntity entity,LivingEntityRenderState state,float partial,CallbackInfo ci) {
        Minecraft mc=Minecraft.getInstance();
        GaitPose pose=null;
        MotionSettings s=MotionConfig.get();
        if(entity==mc.player && mc.gui.screen()==null && s.enabled() && s.body()
                && GaitRuntime.posture(mc.player) && !GaitRuntime.precision()) {
            pose=GaitRuntime.sample(partial);
            float delay=(float)(pose.bodyYawLag()*s.bodyWeight());
            state.bodyRot+=delay;
            state.yRot-=delay; // Keep the visual head pointed toward the requested view.
        }
        // Clear every extraction: renderer state objects are reused between entities.
        ((FabricRenderState)(Object)state).setData(GaitRuntime.POSE,pose);
        ((FabricRenderState)(Object)state).setData(com.dark.winter.client.equipment.ClothingLayer.OUTFIT,
                entity instanceof net.minecraft.world.entity.player.Player player
                        ? com.dark.winter.client.equipment.ClothingLayer.outfit(player) : 0);
        ((FabricRenderState)(Object)state).setData(com.dark.winter.client.equipment.BackpackLayer.EQUIPPED,
                entity instanceof net.minecraft.world.entity.player.Player player
                        && com.dark.winter.equipment.GearSlot.BACKPACK.accepts(com.dark.winter.equipment.EquipmentAccess.of(player)
                        .getItem(com.dark.winter.equipment.GearSlot.BACKPACK.ordinal())));
    }
}
