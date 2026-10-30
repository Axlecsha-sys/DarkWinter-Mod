// Sizes the native language list for the custom settings layout.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList")
public abstract class SettingsLanguageListMixin {
    @Inject(method="getRowWidth", at=@At("HEAD"), cancellable=true)
    private void darkwinter$rowWidth(CallbackInfoReturnable<Integer> cir) {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen != null && SettingsNavigation.applies(screen)) {
            int listWidth = ((AbstractSelectionList<?>)(Object)this).getWidth();
            cir.setReturnValue(Math.max(1, Math.min(270, listWidth - 20)));
        }
    }
}
