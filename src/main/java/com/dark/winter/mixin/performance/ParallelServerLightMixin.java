// Runs independent block and sky propagation concurrently inside the native server light batch.
package com.dark.winter.mixin.performance;

import com.dark.winter.fixes.performance.ParallelLightUpdates;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThreadedLevelLightEngine.class)
abstract class ParallelServerLightMixin {
    @Unique private ServerLevel darkwinter$level;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void darkwinter$captureOwner(LightChunkGetter source, ChunkMap chunks, boolean hasSky,
            ConsecutiveExecutor executor, ChunkTaskDispatcher dispatcher, CallbackInfo ci) {
        if (source.getLevel() instanceof ServerLevel level) darkwinter$level = level;
    }

    @WrapOperation(method = "runUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"),
            require = 1, allow = 1)
    private int darkwinter$runIndependentLayers(ThreadedLevelLightEngine engine, Operation<Integer> original) {
        // Direct/native fallback never introduces a wait on the simulation or render owner.
        if (darkwinter$level == null || darkwinter$level.getServer().isSameThread()) {
            return original.call(engine);
        }
        LevelLightEngineAccess layers = (LevelLightEngineAccess) engine;
        LightEngine<?, ?> block = layers.darkwinter$blockEngine();
        LightEngine<?, ?> sky = layers.darkwinter$skyEngine();
        if (block == null || sky == null || !block.hasLightWork() || !sky.hasLightWork()) {
            return original.call(engine);
        }
        Object skyStorage = ((LightEngineStorageAccess) sky).darkwinter$storage();
        return ParallelLightUpdates.runPair(block::runLightUpdates, sky::runLightUpdates,
                () -> original.call(engine), skyStorage);
    }
}
