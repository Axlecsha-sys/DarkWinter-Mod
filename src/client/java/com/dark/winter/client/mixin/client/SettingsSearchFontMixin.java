package com.dark.winter.client.mixin.client;

import com.dark.winter.client.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.debug.DebugOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Search text and every cursor/selection measurement use the same glyph metrics. */
@Mixin(EditBox.class)
public abstract class SettingsSearchFontMixin {
    @Unique private static boolean darkwinter$search() {
        var screen=Minecraft.getInstance().gui.screen();
        return screen instanceof LanguageSelectScreen||screen instanceof DebugOptionsScreen;
    }
    @Unique private static Style darkwinter$style() {return HudStyle.strong(Component.empty()).getStyle();}

    @Inject(method="applyFormat",at=@At("RETURN"),cancellable=true)
    private void darkwinter$text(String text,int offset,CallbackInfoReturnable<FormattedCharSequence> cir) {
        if(darkwinter$search()) {
            var original=cir.getReturnValue();var font=darkwinter$style().getFont();
            cir.setReturnValue(sink->original.accept((index,style,point)->sink.accept(index,style.withFont(font).withItalic(false),point)));
        }
    }
    @Redirect(method={"findClickedPositionInText","extractWidgetRenderState","updateTextPosition","scrollTo"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;"))
    private String darkwinter$head(Font font,String text,int width) {
        return darkwinter$search()?font.getSplitter().plainHeadByWidth(text,width,darkwinter$style()):font.plainSubstrByWidth(text,width);
    }
    @Redirect(method="scrollTo",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;IZ)Ljava/lang/String;"))
    private String darkwinter$tail(Font font,String text,int width,boolean reverse) {
        if(!darkwinter$search())return font.plainSubstrByWidth(text,width,reverse);
        return reverse?font.getSplitter().plainTailByWidth(text,width,darkwinter$style()):font.getSplitter().plainHeadByWidth(text,width,darkwinter$style());
    }
    @Redirect(method={"extractWidgetRenderState","updateTextPosition","getScreenX"},at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I"))
    private int darkwinter$width(Font font,String text) {
        return darkwinter$search()?font.width(HudStyle.strong(Component.literal(text))):font.width(text);
    }
}
