// A navigation click cancels key capture before the native mouse-binding branch.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A navigation click cancels key capture before the native mouse-binding branch. */
@Mixin(KeyBindsScreen.class)
public abstract class SettingsKeyCaptureMixin {
    @Shadow public @Nullable KeyMapping selectedKey;

    @Inject(method="mouseClicked", at=@At("HEAD"))
    private void darkwinter$cancelSidebarCapture(MouseButtonEvent event, boolean doubleClick,
                                                CallbackInfoReturnable<Boolean> cir) {
        Screen screen = (Screen)(Object)this;
        if (selectedKey != null && SettingsNavigation.applies(screen)
                && SettingsNavigation.sidebarHit(screen, event.x(), event.y())) selectedKey = null;
    }
}
