// Preserve recipe cycling, item previews and duration while making the dark notification readable.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Preserve recipe cycling, item previews and duration while making the dark notification readable. */
@Mixin(RecipeToast.class)
public abstract class RecipeToastSkinMixin {
    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -11534256))
    private int darkwinter$title(int original) { return HudStyle.AMBER; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -16777216))
    private int darkwinter$description(int original) { return HudStyle.INK; }
}
