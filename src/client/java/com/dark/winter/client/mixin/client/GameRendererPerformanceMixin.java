// Observes matching CPU and GPU render scopes only during an explicitly requested capture.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.diagnostics.performance.PerformanceClient;
import com.dark.winter.diagnostics.performance.PerformanceRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
abstract class GameRendererPerformanceMixin {
    @WrapMethod(method = "render")
    private void darkwinter$observeRender(Operation<Void> original) {
        if (PerformanceRuntime.active() == null) { original.call(); return; }
        PerformanceClient.renderBegin();
        try { original.call(); }
        finally { PerformanceClient.renderEnd(); }
    }
}
