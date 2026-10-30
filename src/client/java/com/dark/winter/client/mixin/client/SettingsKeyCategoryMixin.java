// Centers native key-category labels within the settings content pane.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets="net.minecraft.client.gui.screens.options.controls.KeyBindsList$CategoryEntry")
public abstract class SettingsKeyCategoryMixin {
    @Shadow @Final private FocusableTextWidget categoryName;

    @ModifyArg(method="extractContent", at=@At(value="INVOKE",
            target="Lnet/minecraft/client/gui/components/FocusableTextWidget;setPosition(II)V"), index=0)
    private int darkwinter$textCenter(int original) {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen != null && SettingsNavigation.applies(screen)
                ? SettingsNavigation.contentLeft(screen) + SettingsNavigation.contentWidth(screen) / 2
                        - categoryName.getWidth() / 2 : original;
    }
}
