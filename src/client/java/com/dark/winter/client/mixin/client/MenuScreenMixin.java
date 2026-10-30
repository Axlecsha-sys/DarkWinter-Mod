// Draws shared title and pause-menu backgrounds through native screen hooks.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class MenuScreenMixin {
    @Inject(method="extractBlurredBackground",at=@At("HEAD"),cancellable=true)
    private void darkwinter$pausedFrame(GuiGraphicsExtractor g,CallbackInfo ci) {
        if((Object)this instanceof PauseScreen) ci.cancel();
    }
    @Inject(method="extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$titleBackdrop(GuiGraphicsExtractor g,float partialTick,CallbackInfo ci) {
        Screen screen=(Screen)(Object)this;
        if(MenuStyle.themed(screen)) {
            if(MenuStyle.earlyMessage(screen)) MenuStyle.earlyBackdrop(g,screen.width,screen.height);
            else MenuStyle.landscape(g,screen.width,screen.height);
            ci.cancel();
        }
    }

    @Inject(method="extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$pauseBackdrop(GuiGraphicsExtractor g,CallbackInfo ci) {
        if((Object)this instanceof PauseScreen screen) {
            MenuStyle.pause(g,screen);
            ci.cancel();
        } else if(MenuStyle.themed((Screen)(Object)this)) {
            Screen screen=(Screen)(Object)this;
            g.fillGradient(0,0,screen.width,screen.height,0x2808121C,0x48030912);
            ci.cancel();
        }
    }

    @Inject(method="extractTransparentBackground",at=@At("HEAD"),cancellable=true)
    private void darkwinter$worldVeil(GuiGraphicsExtractor g,CallbackInfo ci) {
        Screen screen=(Screen)(Object)this;
        if(MenuStyle.themed(screen)) {
            MenuStyle.worldBackdrop(g,screen.width,screen.height);
            ci.cancel();
        }
    }

    @Inject(method="extractMenuBackgroundTexture",at=@At("HEAD"),cancellable=true)
    private static void darkwinter$formMaterial(GuiGraphicsExtractor g,net.minecraft.resources.Identifier texture,
                                                int x,int y,float u,float v,int w,int h,CallbackInfo ci) {
        if(MenuStyle.themed(net.minecraft.client.Minecraft.getInstance().gui.screen())) {
            g.fillGradient(x,y,x+w,y+h,0x90202B34,0xB008111A);
            ci.cancel();
        }
    }
}
