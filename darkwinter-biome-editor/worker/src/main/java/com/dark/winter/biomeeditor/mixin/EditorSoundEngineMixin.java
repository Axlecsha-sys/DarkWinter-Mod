// Keeps the isolated biome worker from opening an unused OpenAL playback device.
package com.dark.winter.biomeeditor.mixin;

import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class EditorSoundEngineMixin {
    @Inject(method="loadLibrary()V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void editor$keepPlaybackDeviceClosed(CallbackInfo ci) {
        if (!System.getProperty("darkwinter.biomeEditor.ipc", "").isBlank()) ci.cancel();
    }
}
