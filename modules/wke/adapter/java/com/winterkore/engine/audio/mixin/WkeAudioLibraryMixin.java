package com.winterkore.engine.audio.mixin;

import com.mojang.blaze3d.audio.Library;
import com.winterkore.engine.audio.WkeAudioEfx;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Host cleanup has already destroyed all channels, while its context is still alive. */
@Mixin(Library.class)
public abstract class WkeAudioLibraryMixin {
    @Inject(method="cleanup",at=@At(value="INVOKE",
            target="Lorg/lwjgl/openal/ALC10;alcDestroyContext(J)V",remap=false))
    private void wke$releaseBeforeContext(CallbackInfo ci) { WkeAudioEfx.releaseRoom(); }
}
