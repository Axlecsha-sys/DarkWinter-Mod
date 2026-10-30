// Replaces selected native widget and separator textures with shared menu materials.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class MenuTextureSkinMixin {
    // Both the no-color and float-alpha overloads delegate to this final identifier
    // overload. Covering it fixes SpriteIconButton, Checkbox and multi-line fields too.
    @Inject(method="blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$widgets(RenderPipeline pipeline,Identifier sprite,int x,int y,int w,int h,int color,CallbackInfo ci) {
        Screen screen=Minecraft.getInstance().gui.screen();
        if((screen==null||MenuStyle.themed(screen))
                && com.dark.winter.client.MenuWidgetSkin.draw((GuiGraphicsExtractor)(Object)this,sprite,x,y,w,h,color)) ci.cancel();
    }
    @Inject(method="blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$separators(RenderPipeline pipeline,Identifier texture,int x,int y,float u,float v,int w,int h,int tw,int th,CallbackInfo ci) {
        if(!MenuStyle.themed(Minecraft.getInstance().gui.screen())) return;
        if(texture.equals(Screen.HEADER_SEPARATOR)||texture.equals(Screen.FOOTER_SEPARATOR)
                ||texture.equals(Screen.INWORLD_HEADER_SEPARATOR)||texture.equals(Screen.INWORLD_FOOTER_SEPARATOR)
                ||texture.equals(CreateWorldScreen.TAB_HEADER_BACKGROUND)) {
            var g=(GuiGraphicsExtractor)(Object)this;
            g.fillGradient(x,y,x+w,y+h,0xB021303B,0xA009141F);
            g.fill(x,y+h-1,x+w,y+h,0x70556D7B);
            ci.cancel();
        }
    }
}
