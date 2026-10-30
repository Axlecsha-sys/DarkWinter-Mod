// Avoid queueing a render-thread cache miss behind chunk upload tasks.
package com.dark.winter.client.mixin.client;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Avoid queueing a render-thread cache miss behind chunk upload tasks.
 *
 * PipelineCache.get immediately joins the future returned by compilePipeline.
 * Chunk tasks may occupy every worker while they wait for the render thread to
 * drain their staging buffer. In that situation using the same worker pool for
 * compilation prevents the render thread from making progress.
 *
 * Only this already-blocking, lazy cache-miss path uses a direct executor.
 * Normal ShaderManager resource-reload compilation is unchanged. GPU finishing,
 * cache insertion, exceptions and pipeline lifetime remain vanilla-owned.
 * No timeout, null result, or skipped draw is used as a substitute for a pipeline.
 */
@Mixin(value = PipelineCache.class, remap = false)
public abstract class PipelineCacheProgressMixin {
    @Unique
    private static final Executor darkwinter$directPipelineExecutor = Runnable::run;

    @Unique
    private static final Logger darkwinter$pipelineGuardLogger =
            LoggerFactory.getLogger("DarkWinter/PipelineGuard");

    // Read/written only on the render thread. Do not log on every cache miss.
    @Unique
    private static boolean darkwinter$pipelineGuardReported;

    @ModifyArg(
            method = "get(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            index = 2,
            require = 1,
            expect = 1,
            allow = 1,
            remap = false
    )
    private Executor darkwinter$avoidSharedWorkerStarvation(Executor original) {
        if (!RenderSystem.isOnRenderThread()) {
            return original;
        }
        if (!darkwinter$pipelineGuardReported) {
            darkwinter$pipelineGuardReported = true;
            darkwinter$pipelineGuardLogger.info(
                    "Pipeline guard active: lazy render-thread compilation bypasses the shared chunk worker pool."
            );
        }
        return darkwinter$directPipelineExecutor;
    }
}
