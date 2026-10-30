// Language entries retain native selection, double click, keyboard and reload behavior.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Language entries retain native selection, double click, keyboard and reload behavior. */
@Mixin(targets="net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public abstract class SettingsLanguageEntryMixin {
    @ModifyArg(method="extractContent", at=@At(value="INVOKE",
            target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"), index=2)
    private int darkwinter$textCenter(int original) {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen != null && SettingsNavigation.applies(screen)
                ? SettingsNavigation.contentLeft(screen) + SettingsNavigation.contentWidth(screen) / 2 : original;
    }
}
