// Keeps native row focus/selection bounds aligned with the settings content pane.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps native row focus/selection bounds aligned with the settings content pane. */
@Mixin(OptionsList.class)
public abstract class SettingsOptionsListMixin {
    @Shadow @Final private OptionsSubScreen screen;

    @Inject(method="getRowWidth", at=@At("HEAD"), cancellable=true)
    private void darkwinter$rowWidth(CallbackInfoReturnable<Integer> cir) {
        if (SettingsNavigation.applies(screen)) cir.setReturnValue(SettingsNavigation.optionWidth(screen));
    }
}
