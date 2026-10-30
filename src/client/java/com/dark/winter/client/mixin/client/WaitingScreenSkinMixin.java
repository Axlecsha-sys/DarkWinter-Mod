// Only the waiting indicator changes; button delays, callbacks and narration remain original.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.UiPreferences;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the waiting indicator changes; button delays, callbacks and narration remain original. */
@Mixin(GenericWaitingScreen.class)
public abstract class WaitingScreenSkinMixin {
    @Redirect(method="extractRenderState",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
    private void darkwinter$waiting(GuiGraphicsExtractor g,Font font,String dots,int x,int y,int color) {
        int active=UiPreferences.animated()?(int)(UiPreferences.seconds()*2)%3:-1;
        for(int i=0;i<3;i++) {
            int left=x-15+i*11;
            g.fill(left,y+3,left+8,y+5,i==active?HudStyle.INK:0xFF537384);
        }
    }
}
