package com.dark.winter.client.mixin.client;

import com.dark.winter.client.inventory.InventoryPortraitLighting;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** Rendering happens after renderToTexture returns, so the scope must include the actual PIP draw. */
@Mixin(PictureInPictureRenderer.class)
public abstract class InventoryPortraitLightScopeMixin {
    @WrapMethod(method="prepare")
    private void darkwinter$restoreLighting(PictureInPictureRenderState state,GuiRenderState gui,
            FeatureRenderDispatcher dispatcher,int scale,Operation<Void> original) {
        if(!InventoryPortraitLighting.matches(state)){original.call(state,gui,dispatcher,scale);return;}
        var previous=RenderSystem.getShaderLights();
        try {original.call(state,gui,dispatcher,scale);}
        finally {RenderSystem.setShaderLights(previous);}
    }
}
