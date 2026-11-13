package com.dark.winter.client.mixin.client;

import com.dark.winter.client.LanguageMenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Language entries retain native selection, double click, keyboard and reload behavior. */
@Mixin(targets="net.minecraft.client.gui.screens.options.LanguageSelectScreen$LanguageSelectionList$Entry")
public abstract class SettingsLanguageEntryMixin {
    @Shadow @Final private String code;
    @Shadow @Final private Component language;

    @Inject(method="extractContent",at=@At("HEAD"),cancellable=true)
    private void darkwinter$row(GuiGraphicsExtractor g,int mx,int my,boolean hovered,float dt,CallbackInfo ci) {
        LanguageMenuStyle.entry(g,(LayoutElement)this,language,code,mx,my,hovered);
        ci.cancel();
    }
}
