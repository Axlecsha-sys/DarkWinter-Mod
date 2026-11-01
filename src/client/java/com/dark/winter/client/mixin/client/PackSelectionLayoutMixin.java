package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resizes the native columns and their entries without replacing pack actions. */
@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionLayoutMixin {
    @Shadow private TransferableSelectionList availablePackList;
    @Shadow private TransferableSelectionList selectedPackList;

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void darkwinter$fitPackColumns(CallbackInfo ci) {
        int width = ((PackSelectionScreen)(Object)this).width;
        int margin = 8;
        int nativeColumnWidth = 200;
        // Retain the native 30px gutter when it fits; compact screens keep a 12px gap.
        int gap = Math.clamp(width - margin * 2 - nativeColumnWidth * 2, 12, 30);
        int columnWidth = Math.clamp((width - margin * 2 - gap) / 2, 1, nativeColumnWidth);
        int left = (width - columnWidth * 2 - gap) / 2;
        if (availablePackList != null) {
            availablePackList.updateSizeAndPosition(columnWidth, availablePackList.getHeight(),
                    left, availablePackList.getY());
        }
        if (selectedPackList != null) {
            selectedPackList.updateSizeAndPosition(columnWidth, selectedPackList.getHeight(),
                    left + columnWidth + gap, selectedPackList.getY());
        }
    }
}
