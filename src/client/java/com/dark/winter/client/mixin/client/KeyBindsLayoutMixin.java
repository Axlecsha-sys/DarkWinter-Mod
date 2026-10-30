// Sizes native key-binding rows for the settings content pane.
package com.dark.winter.client.mixin.client;

import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBindsList.class)
public abstract class KeyBindsLayoutMixin {
    @Inject(method="getRowWidth",at=@At("HEAD"),cancellable=true)
    private void darkwinter$fitColumns(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.min(340,((KeyBindsList)(Object)this).getWidth()-32));
    }
}
