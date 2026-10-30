// Applies custom materials and text placement to native GUI surfaces.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.GuiSurfaceArt;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiSurfaceSkinMixin {
    @Inject(method="blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$surface(RenderPipeline p,Identifier id,int x,int y,int w,int h,int color,CallbackInfo ci){
        if(GuiSurfaceArt.sprite((GuiGraphicsExtractor)(Object)this,id,x,y,w,h,color))ci.cancel();
    }
    @Inject(method="blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$progress(RenderPipeline p,Identifier id,int sw,int sh,int u,int v,int x,int y,int w,int h,int color,CallbackInfo ci){
        // Partial bars retain their native clipped destination size and real progress.
        if(GuiSurfaceArt.sprite((GuiGraphicsExtractor)(Object)this,id,x,y,w,h,color))ci.cancel();
    }
    @Inject(method="blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$panels(RenderPipeline p,Identifier id,int x,int y,float u,float v,int w,int h,int sw,int sh,int tw,int th,int color,CallbackInfo ci){
        if(GuiSurfaceArt.texture((GuiGraphicsExtractor)(Object)this,id,x,y,w,h))ci.cancel();
    }
    @ModifyVariable(method="text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",at=@At("HEAD"),argsOnly=true,ordinal=2)
    private int darkwinter$darkPanelLabels(int color){return GuiSurfaceArt.readableText(color);}
}
