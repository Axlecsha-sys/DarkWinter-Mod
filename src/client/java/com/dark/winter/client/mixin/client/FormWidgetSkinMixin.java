// Skin only: original text editing, tab selection, focus and narration still execute.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Skin only: original text editing, tab selection, focus and narration still execute. */
@Mixin({EditBox.class,MenuTabBar.MenuTabButton.class})
public abstract class FormWidgetSkinMixin {
    @Redirect(method="extractWidgetRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void darkwinter$skin(GuiGraphicsExtractor g,RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h) {
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())
                && com.dark.winter.client.MenuWidgetSkin.draw(g,sprite,x,y,w,h,-1)) return;
        g.blitSprite(pipeline,sprite,x,y,w,h);
    }
}
