// Keep the immutable SVG generation paired with its extracted GUI frame.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.vector.SvgHud;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class SvgGuiBindingsMixin {
    @Shadow @Final private GuiRenderState renderState;
    @Inject(method="render",at=@At("HEAD"))
    private void darkwinter$prepareSvg(CallbackInfo ci){SvgHud.prepareFrame(renderState);}
    @Inject(method="executeDraw",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;setVertexBuffer(ILcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"))
    private void darkwinter$bindSvg(CallbackInfo ci,@Local RenderPipeline pipeline,@Local(argsOnly=true) RenderPass pass){
        SvgHud.bind(renderState,pipeline,pass);
    }
    @Inject(method="render",at=@At("RETURN"))
    private void darkwinter$finishSvg(CallbackInfo ci){SvgHud.endFrame(renderState);}
}
