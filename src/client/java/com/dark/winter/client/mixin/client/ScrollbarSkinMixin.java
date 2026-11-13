// Keeps scrollbar hit areas, drag calculations and cursor feedback unchanged.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;

/** Keeps scrollbar hit areas, drag calculations and cursor feedback unchanged. */
@Mixin(AbstractScrollArea.class)
public abstract class ScrollbarSkinMixin {
    @Shadow @Final private AbstractScrollArea.ScrollbarSettings scrollbarSettings;

    @Redirect(method="extractScrollbar",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void darkwinter$scrollbar(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) { g.blitSprite(pipeline,sprite,x,y,w,h);return; }
        if(sprite.equals(scrollbarSettings.backgroundSprite())) SvgHud.surface(g,x+2,y,Math.max(1,w-4),h,MenuSurface.TRACK,0,0xB093AEBB);
        else {
            int color=sprite.equals(scrollbarSettings.disabledScrollerSprite())?0xFF43505A:HudStyle.MUTED;
            SvgHud.surface(g,x+1,y,Math.max(1,w-2),h,MenuSurface.THUMB,0,color);
        }
    }
}
