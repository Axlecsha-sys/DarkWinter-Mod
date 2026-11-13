package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.client.gui.screens.debug.DebugOptionsScreen$CategoryEntry")
public abstract class DebugOptionsCategoryMixin {
    @Shadow @Final private FocusableTextWidget widget;
    @Inject(method="extractContent",at=@At("HEAD"),cancellable=true)
    private void darkwinter$category(GuiGraphicsExtractor g,int mx,int my,boolean hovered,float dt,CallbackInfo ci) {
        var row=(LayoutElement)this;
        widget.setMessage(HudStyle.strong(widget.getMessage()).copy().withStyle(style->style.withUnderlined(false)).withColor(HudStyle.MUTED));
        widget.setWidth(Math.min(Math.max(1,row.getWidth()-16),HudStyle.width(widget.getMessage())));
        widget.setPosition(row.getX()+8,row.getY()+7);widget.setMaxWidth(Math.max(1,row.getWidth()-16));widget.updateHeight();
        widget.extractRenderState(g,mx,my,dt);
        g.fill(row.getX()+8,row.getY()+24,row.getX()+row.getWidth()-6,row.getY()+25,0x304D7082);ci.cancel();
    }
}
