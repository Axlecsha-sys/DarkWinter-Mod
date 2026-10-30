// Applies the shared title color to native tutorial notifications.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Tutorial title color is embedded in its styled glyphs, not the later draw-call color. */
@Mixin(TutorialToast.class)
public abstract class TutorialToastSkinMixin {
    @ModifyConstant(method = "<init>(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/gui/components/toasts/TutorialToast$Icons;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;ZI)V",
            constant = @Constant(intValue = -11534256))
    private int darkwinter$readableTitle(int original) { return HudStyle.AMBER; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -16777216))
    private int darkwinter$readableDescription(int original) { return HudStyle.INK; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -1))
    private int darkwinter$progressTrack(int original) { return 0xFF334D5D; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -16755456))
    private int darkwinter$progressIncreasing(int original) { return HudStyle.STABLE; }

    @ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = -11206656))
    private int darkwinter$progressDecreasing(int original) { return HudStyle.AMBER; }
}
