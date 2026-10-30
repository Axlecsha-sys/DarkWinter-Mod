// Draws native buttons using the shared menu materials and label treatment.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractButton.class)
public abstract class MenuButtonMixin extends AbstractWidget {
    @Unique private float darkwinter$light;
    @Unique private long darkwinter$lastPaint;
    protected MenuButtonMixin(int x,int y,int w,int h,Component title) { super(x,y,w,h,title); }
    @Inject(method="extractDefaultSprite(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",at=@At("HEAD"),cancellable=true)
    private void darkwinter$material(GuiGraphicsExtractor g,CallbackInfo ci) {
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())) {
            long now=System.nanoTime();
            float seconds=darkwinter$lastPaint==0?0:Math.min(.1f,(now-darkwinter$lastPaint)/1_000_000_000f);
            darkwinter$lastPaint=now;
            float target=this.active && this.isHoveredOrFocused()?1:0;
            float blend=com.dark.winter.client.UiPreferences.animated()?1-(float)Math.exp(-seconds/(target>darkwinter$light?.17f:.22f)):1;
            darkwinter$light+=(target-darkwinter$light)*blend;
            MenuStyle.button(g,this,this.alpha,darkwinter$light);
            ci.cancel();
        }
    }
    @Inject(method="extractDefaultLabel",at=@At("HEAD"),cancellable=true)
    private void darkwinter$rowLabel(net.minecraft.client.gui.ActiveTextCollector output,CallbackInfo ci) {
        if(MenuStyle.rows(Minecraft.getInstance().gui.screen()) && getWidth()>60) ci.cancel();
    }
}
