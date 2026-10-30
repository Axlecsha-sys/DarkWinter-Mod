// Applies the shared menu treatment to death and confirmation screens.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared by death and its confirmation dialog. Respawn, delay and input are untouched. */
@Mixin(DeathScreen.class)
public abstract class DeathScreenSkinMixin {
    @Inject(method="extractDeathBackground",at=@At("HEAD"),cancellable=true)
    private static void darkwinter$coldBackground(GuiGraphicsExtractor g,int width,int height,CallbackInfo ci) {
        g.fillGradient(0,0,width,height,0xB60A1824,0xED030A11);
        g.fillGradient(0,height*3/4,width,height,0x001F0B13,0x402E101A);
        int left=Math.max(14,width/2-116),right=Math.min(width-14,width/2+116);
        g.fill(left,47,right,48,0x60537384);
        g.fill(width/2-21,47,width/2+21,48,HudStyle.alpha(HudStyle.CRITICAL,.72f));
        ci.cancel();
    }
}
