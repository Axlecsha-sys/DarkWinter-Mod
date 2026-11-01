// Inserts the transparency quality choice into the native video quality list without replacing its layout.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.render.TransparencyVideoOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class TransparencyVideoOptionMixin {
    @Unique private TransparencyVideoOption darkwinter$transparencyOption;

    @Inject(method="addOptions",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V",
            ordinal=1,shift=At.Shift.AFTER),require=1,allow=1)
    private void darkwinter$addTransparencyQuality(CallbackInfo callback) {
        var list=((SettingsOptionsAccess)(Object)this).darkwinter$optionsList();
        if(list==null)throw new IllegalStateException("Video options list not initialized");
        darkwinter$transparencyOption=new TransparencyVideoOption(Minecraft.getInstance(),list);
        list.addBig(darkwinter$transparencyOption.option());
        darkwinter$transparencyOption.added();
    }
}
