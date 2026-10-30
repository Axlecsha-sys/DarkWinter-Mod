// Applies the shared notification palette to native system toasts.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.components.toasts.SystemToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Palette only; message styles (including errors), wrapping and lifetime remain native. */
@Mixin(SystemToast.class)
public abstract class SystemToastSkinMixin {
    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -256))
    private int darkwinter$title(int original) { return HudStyle.AMBER; }
}
