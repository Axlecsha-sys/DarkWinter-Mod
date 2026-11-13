// Defers OpenGL library initialization until an OpenGL backend actually requests it.
package com.dark.winter.client.mixin.client;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
abstract class NativeLibrariesBootstrapMixin {
    @Inject(method="loadOpenGL", at=@At("HEAD"), cancellable=true, require=1)
    private static void darkwinter$deferUnusedOpenGL(CallbackInfo callback) {
        // GlBackend.loadLibrary initializes the same provider before loading its SDL library.
        callback.cancel();
    }
}
