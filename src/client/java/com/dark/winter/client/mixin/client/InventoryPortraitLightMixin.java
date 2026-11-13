package com.dark.winter.client.mixin.client;

import com.dark.winter.client.inventory.InventoryPortraitLighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiEntityRenderer.class)
public abstract class InventoryPortraitLightMixin {
    @Inject(method="renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/Lighting;setupFor(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V",shift=At.Shift.AFTER))
    private void darkwinter$portraitLights(GuiEntityRenderState state,PoseStack poses,SubmitNodeCollector collector,CallbackInfo ci) {
        if(InventoryPortraitLighting.matches(state))InventoryPortraitLighting.bind();
    }
}
