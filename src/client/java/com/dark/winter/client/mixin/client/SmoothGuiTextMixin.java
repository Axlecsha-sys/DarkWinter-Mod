package com.dark.winter.client.mixin.client;

import com.dark.winter.client.text.SmoothUiText;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native GUI glyph submission otherwise forces nearest sampling even on a filtered font atlas. */
@Mixin(GlyphRenderState.class)
public abstract class SmoothGuiTextMixin {
    @Shadow public abstract TextRenderable renderable();

    @Inject(method="pipeline",at=@At("RETURN"),cancellable=true,require=1,allow=1)
    private void darkwinter$coverage(CallbackInfoReturnable<RenderPipeline> cir) {
        if(cir.getReturnValue()==RenderPipelines.GUI_TEXT_GRAYSCALE)cir.setReturnValue(SmoothUiText.PIPELINE);
    }

    @ModifyArg(method="textureSetup",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/render/TextureSetup;singleTextureWithLightmap(Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;"),index=1,require=1,allow=1)
    private GpuSampler darkwinter$filteredGlyphs(GpuSampler original) {
        return renderable().guiPipeline()==RenderPipelines.GUI_TEXT_GRAYSCALE
                ?RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR):original;
    }
}
