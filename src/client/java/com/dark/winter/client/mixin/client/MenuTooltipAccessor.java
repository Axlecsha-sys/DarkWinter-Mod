// Exposes the original tooltip message for consistent custom tooltip rendering.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Tooltip.class)
public interface MenuTooltipAccessor {
    @Accessor("message") Component darkwinter$message();
}
