// Measures the same styled glyphs that are drawn; never shrinks the font.
package com.dark.winter.client;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Measures the same styled glyphs that are drawn; never shrinks the font. */
public final class MenuText {
    private MenuText() {}
    public static Component fit(Component text,int width) {
        var font=Minecraft.getInstance().font;
        if(font.width(text)<=width) return text;
        var dots=Component.literal("…").withStyle(text.getStyle());
        if(width<font.width(dots)) return Component.empty();
        var result=Component.empty();
        font.substrByWidth(text,width-font.width(dots)).visit((style,part)->{
            result.append(Component.literal(part).withStyle(style));
            return Optional.empty();
        },text.getStyle());
        return result.append(dots);
    }
}
