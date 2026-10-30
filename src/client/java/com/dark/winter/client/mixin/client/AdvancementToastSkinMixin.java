// Styles advancement toasts while preserving challenge labels and native crossfades.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Preserve challenge distinction and the native title/message crossfade. */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastSkinMixin {
    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -256))
    private int darkwinter$title(int original) { return HudStyle.AMBER; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -30465))
    private int darkwinter$challenge(int original) { return HudStyle.STABLE; }
}
