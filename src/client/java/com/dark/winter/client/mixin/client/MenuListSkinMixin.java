// Styles native selection-list backgrounds, selections, and hover states.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.MenuStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSelectionList.class)
public abstract class MenuListSkinMixin {
    @Inject(method="extractListBackground",at=@At("HEAD"),cancellable=true)
    private void darkwinter$list(GuiGraphicsExtractor g,CallbackInfo ci) {
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())) {
            var widget=(AbstractWidget)(Object)this;
            g.fillGradient(widget.getX(),widget.getY(),widget.getRight(),widget.getBottom(),0x94101C29,0xC3060D17);
            ci.cancel();
        }
    }
    @Inject(method="extractSelection",at=@At("HEAD"),cancellable=true)
    private void darkwinter$selection(GuiGraphicsExtractor g,@Coerce Object entry,int color,CallbackInfo ci) {
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())) {
            var bounds=(LayoutElement)entry;
            boolean world=entry instanceof net.minecraft.client.gui.screens.worldselection.WorldSelectionList.WorldListEntry;
            MenuStyle.listSelection(g,bounds.getX(),bounds.getY()+(world?2:0),bounds.getWidth(),Math.max(1,bounds.getHeight()-(world?4:0)),((AbstractWidget)(Object)this).isFocused());
            ci.cancel();
        }
    }
    @Inject(method="extractItem",at=@At("HEAD"))
    private void darkwinter$hover(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick,@Coerce Object entry,CallbackInfo ci) {
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())) {
            var b=(LayoutElement)entry;
            if(entry instanceof net.minecraft.client.gui.screens.worldselection.WorldSelectionList.WorldListEntry) {
                int x=b.getX(),y=b.getY()+2,w=b.getWidth(),h=b.getHeight()-4;
                g.fillGradient(x,y,x+w,y+h,0x8B13212E,0xAE050E18);
                g.fill(x,y,x+w,y+1,0x554D6B7C);
                g.fill(x,y+h-1,x+w,y+h,0x554D6B7C);
                g.fill(x,y,x+1,y+h,0x384D6B7C);
                g.fill(x+w-1,y,x+w,y+h,0x384D6B7C);
            }
            if(mouseX>=b.getX() && mouseX<b.getX()+b.getWidth() && mouseY>=b.getY() && mouseY<b.getY()+b.getHeight())
                g.fillGradient(b.getX(),b.getY()+2,b.getX()+b.getWidth(),b.getY()+b.getHeight()-2,0x38476679,0x18233243);
        }
    }
}
