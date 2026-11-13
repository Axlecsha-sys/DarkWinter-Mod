// Custom menu typography uses its own contrast, without a second pixel-shadow silhouette.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(targets="net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class MenuTextClarityMixin {
    @ModifyArgs(method="accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",require=1,allow=1,at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/state/gui/GuiTextRenderState;<init>(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;Lorg/joml/Matrix3x2fc;IIIIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"))
    private void darkwinter$clearMenuText(Args args){
        if(MenuStyle.themed(Minecraft.getInstance().gui.screen())&&HudStyle.usesUiFont((FormattedCharSequence)args.get(1)))args.set(7,false);
    }
}
