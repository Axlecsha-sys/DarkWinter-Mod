// Adds real post-processing controls while retaining Minecraft's persisted MIP slider.
package com.dark.winter.client.mixin.client;

import com.dark.winter.visuals.VisualConfig;
import com.winterkore.engine.graphics.WkeGraphicsPolicy;
import com.winterkore.engine.graphics.WkeMinecraftGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No parallel graphics preferences: the controls always read their owning systems. */
@Mixin(VideoSettingsScreen.class)
public abstract class WkeVideoSettingsMixin {
    @Unique private Button darkwinter$filmButton;
    @Unique private boolean darkwinter$shownFilmEnabled;
    @Unique private boolean darkwinter$filmSaveFailed;
    @Unique private Tooltip darkwinter$mipTooltip;
    @Unique private Button darkwinter$antialiasButton;
    @Unique private boolean darkwinter$shownAntialiasEnabled;
    @Unique private boolean darkwinter$antialiasSaveFailed;
    @Unique private Button darkwinter$qualityButton;
    @Unique private VisualConfig.Quality darkwinter$shownQuality;
    @Unique private boolean darkwinter$qualitySaveFailed;

    @Inject(method="addOptions", at=@At("HEAD"))
    private void darkwinter$labelMipOption(CallbackInfo ci) {
        // The native formatter reads this caption on every slider update, including dragging.
        ((SettingsOptionCaptionAccess)(Object)Minecraft.getInstance().options.mipmapLevels())
                .darkwinter$caption(Component.translatable("menu.darkwinter.graphics.mip"));
    }

    @Inject(method="addOptions", at=@At("TAIL"))
    private void darkwinter$addPostProcessing(CallbackInfo ci) {
        OptionsList list=((SettingsOptionsAccess)this).darkwinter$optionsList();
        if(list==null) return;
        darkwinter$mipTooltip=Tooltip.create(Component.translatable("menu.darkwinter.graphics.mip.hint",
                WkeGraphicsPolicy.MIN_MIP_LEVELS,WkeGraphicsPolicy.MAX_MIP_LEVELS));
        darkwinter$shownFilmEnabled=VisualConfig.shared().isFilmEnabled();
        darkwinter$filmSaveFailed=false;
        list.addHeader(Component.translatable("menu.darkwinter.graphics.post_processing"));
        darkwinter$shownQuality=VisualConfig.shared().graphicsQuality();
        darkwinter$qualitySaveFailed=false;
        darkwinter$qualityButton=Button.builder(darkwinter$qualityCaption(),button->{
            VisualConfig config=VisualConfig.shared();
            VisualConfig.Quality[] qualities=VisualConfig.Quality.values();
            darkwinter$qualitySaveFailed=!config.setGraphicsQuality(
                    qualities[(config.graphicsQuality().ordinal()+1)%qualities.length]);
            darkwinter$shownQuality=config.graphicsQuality();
            button.setMessage(darkwinter$qualityCaption());
            button.setTooltip(darkwinter$qualityTooltip());
        }).build();
        darkwinter$qualityButton.setTooltip(darkwinter$qualityTooltip());
        list.addBig(darkwinter$qualityButton);
        darkwinter$shownAntialiasEnabled=VisualConfig.shared().isAntialiasEnabled();
        darkwinter$antialiasSaveFailed=false;
        darkwinter$antialiasButton=Button.builder(darkwinter$antialiasCaption(),button->{
            VisualConfig config=VisualConfig.shared();
            darkwinter$antialiasSaveFailed=!config.setAntialiasEnabled(!config.isAntialiasEnabled());
            darkwinter$shownAntialiasEnabled=config.isAntialiasEnabled();
            button.setMessage(darkwinter$antialiasCaption());
            button.setTooltip(darkwinter$antialiasTooltip());
        }).build();
        darkwinter$antialiasButton.setTooltip(darkwinter$antialiasTooltip());
        list.addBig(darkwinter$antialiasButton);
        darkwinter$filmButton=Button.builder(darkwinter$filmCaption(),button->{
            VisualConfig config=VisualConfig.shared();
            darkwinter$filmSaveFailed=!config.setFilmEnabled(!config.isFilmEnabled());
            darkwinter$shownFilmEnabled=config.isFilmEnabled();
            button.setMessage(darkwinter$filmCaption());
            button.setTooltip(darkwinter$filmTooltip());
        }).build();
        darkwinter$filmButton.setTooltip(darkwinter$filmTooltip());
        list.addBig(darkwinter$filmButton);
        darkwinter$refreshMipTooltip(list);
    }

    @Inject(method="tick", at=@At("TAIL"))
    private void darkwinter$reflectEffectiveGraphics(CallbackInfo ci) {
        OptionsList list=((SettingsOptionsAccess)this).darkwinter$optionsList();
        if(list!=null) darkwinter$refreshMipTooltip(list);
        VisualConfig.Quality quality=VisualConfig.shared().graphicsQuality();
        if(darkwinter$qualityButton!=null && quality!=darkwinter$shownQuality) {
            darkwinter$shownQuality=quality;
            darkwinter$qualitySaveFailed=false;
            darkwinter$qualityButton.setMessage(darkwinter$qualityCaption());
            darkwinter$qualityButton.setTooltip(darkwinter$qualityTooltip());
        }
        boolean antialias=VisualConfig.shared().isAntialiasEnabled();
        if(darkwinter$antialiasButton!=null && antialias!=darkwinter$shownAntialiasEnabled) {
            darkwinter$shownAntialiasEnabled=antialias;
            darkwinter$antialiasSaveFailed=false;
            darkwinter$antialiasButton.setMessage(darkwinter$antialiasCaption());
            darkwinter$antialiasButton.setTooltip(darkwinter$antialiasTooltip());
        }
        boolean enabled=VisualConfig.shared().isFilmEnabled();
        if(darkwinter$filmButton!=null && enabled!=darkwinter$shownFilmEnabled) {
            darkwinter$shownFilmEnabled=enabled;
            darkwinter$filmSaveFailed=false;
            darkwinter$filmButton.setMessage(darkwinter$filmCaption());
            darkwinter$filmButton.setTooltip(darkwinter$filmTooltip());
        }
    }

    @Unique private Component darkwinter$qualityCaption() {
        return Component.translatable("menu.darkwinter.graphics.effects_quality",
                Component.translatable("menu.darkwinter.graphics.effects_quality."
                        +darkwinter$shownQuality.name().toLowerCase(java.util.Locale.ROOT)));
    }
    @Unique private Tooltip darkwinter$qualityTooltip() {
        return Tooltip.create(Component.translatable(darkwinter$qualitySaveFailed
                ? "menu.darkwinter.graphics.save_failed" : "menu.darkwinter.graphics.effects_quality.hint"));
    }

    @Unique private Component darkwinter$antialiasCaption() {
        return CommonComponents.optionStatus(Component.translatable("menu.darkwinter.graphics.antialias"),
                darkwinter$shownAntialiasEnabled);
    }
    @Unique private Tooltip darkwinter$antialiasTooltip() {
        return Tooltip.create(Component.translatable(darkwinter$antialiasSaveFailed
                ? "menu.darkwinter.graphics.save_failed" : "menu.darkwinter.graphics.antialias.hint"));
    }

    @Unique private void darkwinter$refreshMipTooltip(OptionsList list) {
        var mip=list.findOption(Minecraft.getInstance().options.mipmapLevels());
        if(mip!=null && darkwinter$mipTooltip!=null) mip.setTooltip(darkwinter$mipTooltip);
    }

    @Unique private Component darkwinter$filmCaption() {
        return CommonComponents.optionStatus(Component.translatable("menu.darkwinter.graphics.film"),
                darkwinter$shownFilmEnabled);
    }

    @Unique private Tooltip darkwinter$filmTooltip() {
        return Tooltip.create(Component.translatable(darkwinter$filmSaveFailed
                ? "menu.darkwinter.graphics.save_failed" : "menu.darkwinter.graphics.film.hint"));
    }

    // Preserve the native changed-value comparison and its single delayed resource reload.
    @Redirect(method="removed", at=@At(value="INVOKE",
            target="Lnet/minecraft/client/Minecraft;updateMaxMipLevel(I)V"))
    private void darkwinter$applyMipPolicy(Minecraft client,int levels) {
        WkeMinecraftGraphics.applyMipLevels(client,levels);
    }
}
