// Gives the existing native option a WKE label without replacing its value or formatter.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The native formatter reads this caption on each input update. */
@Mixin(OptionInstance.class)
public interface SettingsOptionCaptionAccess {
    @Mutable @Accessor("caption") void darkwinter$caption(Component caption);
}
