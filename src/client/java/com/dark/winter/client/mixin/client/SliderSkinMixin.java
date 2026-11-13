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
import org.spongepowered.asm.mixin.Shadow;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractSliderButton.class)
public abstract class SliderSkinMixin {
    @Shadow protected double value;
    @Redirect(method="extractWidgetRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
    private void darkwinter$slider(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h,int color) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) { g.blitSprite(pipeline,sprite,x,y,w,h,color);return; }
        var widget=(AbstractWidget)(Object)this;
        float opacity=(color>>>24)/255f;
        if(w==8) {
            int ink=HudStyle.alpha(widget.isHoveredOrFocused()?HudStyle.INK:HudStyle.MUTED,opacity);
            SvgHud.surface(g,x+1,y+h-5,6,3,MenuSurface.THUMB,widget.isHoveredOrFocused()?1:0,ink);
        } else {
            MenuStyle.button(g,widget,opacity,widget.isHoveredOrFocused()?.8f:0);
            SvgHud.surface(g,x+4,y+h-4,w-8,1,MenuSurface.TRACK,0,HudStyle.alpha(HudStyle.MUTED,opacity));
            int fill=Math.round((w-8)*(float)Math.clamp(value,0,1));
            SvgHud.surface(g,x+4,y+h-4,fill,1,MenuSurface.THUMB,0,HudStyle.alpha(widget.active?HudStyle.STABLE:HudStyle.MUTED,opacity*.7f));
        }
    }
}
