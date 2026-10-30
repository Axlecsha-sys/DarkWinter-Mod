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

/** Keeps scrollbar hit areas, drag calculations and cursor feedback unchanged. */
@Mixin(AbstractScrollArea.class)
public abstract class ScrollbarSkinMixin {
    @Shadow @Final private AbstractScrollArea.ScrollbarSettings scrollbarSettings;

    @Redirect(method="extractScrollbar",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void darkwinter$scrollbar(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) { g.blitSprite(pipeline,sprite,x,y,w,h);return; }
        if(sprite.equals(scrollbarSettings.backgroundSprite())) g.fill(x+1,y,x+w-1,y+h,0xA0071018);
        else {
            int color=sprite.equals(scrollbarSettings.disabledScrollerSprite())?0xFF43505A:HudStyle.MUTED;
            g.fillGradient(x+1,y,x+w-1,y+h,color,0xFF40525F);
            g.fill(x+1,y,x+w-1,y+1,HudStyle.INK);
        }
    }
}
