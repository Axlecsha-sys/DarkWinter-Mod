package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps native text widgets inside the resized row, including scrollbar space. */
@Mixin(targets = "net.minecraft.client.gui.screens.packs.TransferableSelectionList$PackEntry")
public abstract class PackEntryLayoutMixin {
    @Unique private int darkwinter$lastNameWidth = -1;

    @Unique
    private int darkwinter$packTextWidth() {
        // Native row content padding (4), icon (32), icon/text gap (2), and trailing padding (1).
        // TransferableSelectionList.Entry.getWidth() already reserves the scrollbar.
        return Math.max(1, ((LayoutElement)(Object)this).getWidth() - 39);
    }

    @ModifyArg(method = "extractContent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/MultiLineTextWidget;setMaxWidth(I)Lnet/minecraft/client/gui/components/MultiLineTextWidget;"),
            index = 0, require = 1, allow = 1)
    private int darkwinter$descriptionWidth(int nativeWidth) {
        return darkwinter$packTextWidth();
    }

    @Redirect(method = "extractContent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/StringWidget;setMaxWidth(I)Lnet/minecraft/client/gui/components/StringWidget;"),
            require = 1, allow = 1)
    private StringWidget darkwinter$refreshTitleBounds(StringWidget widget, int width) {
        width = darkwinter$packTextWidth();
        if (darkwinter$lastNameWidth != width) {
            // StringWidget.setMaxWidth alone does not invalidate its cached getWidth().
            widget.setMessage(widget.getMessage());
            darkwinter$lastNameWidth = width;
        }
        return widget.setMaxWidth(width);
    }
}
