// Changes only presentation; original poem, attribution, scroll, narration and completion remain native.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.dark.winter.client.UiArt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Changes only presentation; original poem, attribution, scroll, narration and completion remain native. */
@Mixin(WinScreen.class)
public abstract class CreditsScreenSkinMixin extends Screen {
    @Shadow private List<FormattedCharSequence> lines;
    @Unique private boolean darkwinter$creditColorsApplied;
    protected CreditsScreenSkinMixin(Component title){super(title);}

    @Inject(method="init",at=@At("TAIL"))
    private void darkwinter$creditPalette(CallbackInfo ci) {
        if(darkwinter$creditColorsApplied || lines==null) return;
        // Recolor the already wrapped visual runs once. The original text, line
        // lengths, narrator components and poem's two distinct voices stay intact.
        lines.replaceAll(original -> sink -> original.accept((index,style,codePoint) -> {
            TextColor color=style.getColor();
            int themed=switch(color==null?0xFFFFFF:color.getValue()) {
                case 0xFFFF55 -> HudStyle.AMBER;
                case 0x55FF55 -> HudStyle.STABLE;
                case 0x55FFFF,0xAAAAAA -> HudStyle.MUTED;
                case 0xFFFFFF -> HudStyle.INK;
                default -> color.getValue();
            };
            return sink.accept(index,style.withColor(themed&0xFFFFFF),codePoint);
        }));
        darkwinter$creditColorsApplied=true;
    }

    @Inject(method="extractBackground",at=@At("HEAD"),cancellable=true)
    private void darkwinter$northernCredits(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick,CallbackInfo ci) {
        MenuStyle.landscape(g,width,height);
        g.fillGradient(0,0,width,height,0xC0081520,0xE4030B13);
        ci.cancel();
    }

    @Inject(method="extractVignette",at=@At("HEAD"),cancellable=true)
    private void darkwinter$creditEdges(GuiGraphicsExtractor g,CallbackInfo ci) {
        int edge=Math.max(24,height/5);
        g.fillGradient(0,0,width,edge,0xC5030A12,0x00030A12);
        g.fillGradient(0,height-edge,width,height,0x00030A12,0xD5030A12);
        ci.cancel();
    }

    @Redirect(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V"))
    private void darkwinter$creditWordmark(LogoRenderer original,GuiGraphicsExtractor g,int screenWidth,float opacity,int y) {
        int wordmarkWidth=Math.min(256,screenWidth-48);
        UiArt.wordmark(g,(screenWidth-wordmarkWidth)/2,y,wordmarkWidth,HudStyle.alpha(HudStyle.INK,opacity));
        // Existing pose already contains the credit scroll. Keep the following
        // attribution lines at their original offsets instead of replacing them.
    }
}
