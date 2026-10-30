// Exposes the parent screen and native options list to settings navigation.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.jspecify.annotations.Nullable;

@Mixin(OptionsSubScreen.class)
public interface SettingsOptionsAccess {
    @Accessor("lastScreen") Screen darkwinter$parent();
    @Accessor("list") @Nullable OptionsList darkwinter$optionsList();
}
