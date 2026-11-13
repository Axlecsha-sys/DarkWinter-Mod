package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The original credits action/message remain native, with the shared footer typography. */
@Mixin(PlainTextButton.class)
public abstract class TitleFooterMixin {
    @Inject(method="extractContents",at=@At("HEAD"),cancellable=true)
    private void darkwinter$credits(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick,CallbackInfo ci){
        if(!(Minecraft.getInstance().gui.screen() instanceof TitleScreen))return;
        var widget=(AbstractWidget)(Object)this;
        HudStyle.fit(g,widget.getMessage(),widget.getX(),widget.getY(),widget.getWidth(),.66f,
            HudStyle.alpha(widget.isHoveredOrFocused()?HudStyle.INK:HudStyle.MUTED,widget.getAlpha()));
        if(widget.isHoveredOrFocused())g.fill(widget.getX(),widget.getY()+7,widget.getX()+widget.getWidth(),widget.getY()+8,0x80537384);
        ci.cancel();
    }
}
