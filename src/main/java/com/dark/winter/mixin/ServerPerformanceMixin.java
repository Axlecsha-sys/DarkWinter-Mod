// Measures actual server tick work only while an explicit diagnostic capture is active.
package com.dark.winter.mixin;

import com.dark.winter.diagnostics.performance.PerformanceCapture;
import com.dark.winter.diagnostics.performance.PerformanceRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MinecraftServer.class)
abstract class ServerPerformanceMixin {
    @Unique private PerformanceCapture darkwinter$previousCapture;
    @Unique private long darkwinter$previousTickStart;

    @WrapMethod(method = "tickServer")
    private void darkwinter$observeTick(BooleanSupplier hasTimeLeft, Operation<Void> original) {
        PerformanceCapture capture = PerformanceRuntime.active();
        if (capture == null) {
            darkwinter$previousCapture = null;
            original.call(hasTimeLeft);
            return;
        }
        MinecraftServer server = (MinecraftServer)(Object)this;
        long started = System.nanoTime();
        long interval = darkwinter$previousCapture == capture ? started - darkwinter$previousTickStart : -1;
        darkwinter$previousCapture = capture;
        darkwinter$previousTickStart = started;
        var rate = server.tickRateManager();
        int ticksBefore = server.getTickCount();
        float expectedRate = PerformanceRuntime.expectedTickRate(capture);
        if (Float.isFinite(expectedRate) && Float.compare(expectedRate, rate.tickrate()) != 0)
            capture.unavailable("configChanged", "The server tick rate changed during capture");
        boolean advancing = !server.isPaused() && !rate.isFrozen() && rate.runsNormally()
                && !rate.isSprinting() && !rate.isSteppingForward();
        try {
            original.call(hasTimeLeft);
        } finally {
            long ended = System.nanoTime();
            capture.tickAt(interval, started, ended, server.getTickCount() != ticksBefore && advancing && !server.isPaused()
                    && !rate.isFrozen() && rate.runsNormally() && !rate.isSprinting() && !rate.isSteppingForward());
        }
    }
}
