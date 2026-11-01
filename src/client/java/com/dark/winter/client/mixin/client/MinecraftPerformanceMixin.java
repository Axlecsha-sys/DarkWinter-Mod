// Samples cadence at the native rendered-frame log point rather than skipped render-loop returns.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.diagnostics.performance.PerformanceClient;
import com.dark.winter.diagnostics.performance.PerformanceRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftPerformanceMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;logFrameDuration(J)V"))
    private void darkwinter$renderedFrame(boolean advanceGameTime, CallbackInfo ci) {
        if (PerformanceRuntime.active() != null) PerformanceClient.presentedFrame();
    }
}
