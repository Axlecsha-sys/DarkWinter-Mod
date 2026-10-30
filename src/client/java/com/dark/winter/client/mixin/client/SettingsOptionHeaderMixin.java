// The native focusable header is centered in the right pane, including its focus bounds.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.SettingsNavigation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native focusable header is centered in the right pane, including its focus bounds. */
@Mixin(targets="net.minecraft.client.gui.components.OptionsList$HeaderEntry")
public abstract class SettingsOptionHeaderMixin {
    @Shadow @Final private Screen screen;
    @Shadow @Final private int paddingTop;
    @Shadow @Final private FocusableTextWidget widget;

    @Inject(method="extractContent", at=@At("HEAD"), cancellable=true)
    private void darkwinter$rightPane(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     boolean hovered, float partialTick, CallbackInfo ci) {
        if (!SettingsNavigation.applies(screen)) return;
        int width = SettingsNavigation.optionWidth(screen);
        widget.setMaxWidth(width);
        widget.setWidth(width);
        widget.updateHeight();
        widget.setPosition(SettingsNavigation.optionLeft(screen),
                ((ContainerObjectSelectionList.Entry<?>)(Object)this).getContentY() + paddingTop);
        widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ci.cancel();
    }
}
