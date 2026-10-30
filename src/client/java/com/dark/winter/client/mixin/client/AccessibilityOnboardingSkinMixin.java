// First-launch branding only; native narration, fading and setup callbacks remain intact.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.UiArt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** First-launch branding only; native narration, fading and setup callbacks remain intact. */
@Mixin(AccessibilityOnboardingScreen.class)
public abstract class AccessibilityOnboardingSkinMixin {
    @Redirect(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
    private void darkwinter$onboardingWordmark(LogoRenderer original,GuiGraphicsExtractor g,int screenWidth,float opacity) {
        int width=Math.min(256,screenWidth-48);
        UiArt.wordmark(g,(screenWidth-width)/2,28,width,HudStyle.alpha(HudStyle.INK,opacity));
    }
}
