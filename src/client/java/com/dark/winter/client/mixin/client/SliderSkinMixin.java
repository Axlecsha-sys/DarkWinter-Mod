// Applies shared menu materials to native slider rendering.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractSliderButton.class)
public abstract class SliderSkinMixin {
    @Redirect(method="extractWidgetRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
    private void darkwinter$slider(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h,int color) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) { g.blitSprite(pipeline,sprite,x,y,w,h,color);return; }
        var widget=(AbstractWidget)(Object)this;
        float opacity=(color>>>24)/255f;
        if(w==8) {
            int ink=HudStyle.alpha(widget.isHoveredOrFocused()?HudStyle.INK:HudStyle.MUTED,opacity);
            g.fill(x+2,y+2,x+6,y+h-2,0xC01C2E3A);
            g.fill(x+3,y+3,x+5,y+h-3,ink);
            HudStyle.halo(g,x+3,y+3,2,h-6,ink,.5f);
        } else MenuStyle.button(g,widget,opacity,widget.isHoveredOrFocused()?.8f:0);
    }
}
