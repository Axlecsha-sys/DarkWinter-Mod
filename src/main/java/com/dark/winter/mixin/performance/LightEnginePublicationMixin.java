// Publishes a parallel sky result on its native coordinator after the block layer.
package com.dark.winter.mixin.performance;

import com.dark.winter.fixes.performance.ParallelLightUpdates;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightEngine.class)
abstract class LightEnginePublicationMixin {
    @WrapOperation(method = "runLightUpdates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;swapSectionMap()V"),
            require = 1, allow = 1)
    private void darkwinter$publishOnCoordinator(LayerLightSectionStorage<?> storage, Operation<Void> original) {
        if (ParallelLightUpdates.isHelperContext()) {
            if (!ParallelLightUpdates.isExpectedPublication(storage)) {
                throw new IllegalStateException("Light publication does not belong to the admitted sky layer");
            }
            ParallelLightUpdates.deferPublication(() -> original.call(storage));
        } else {
            original.call(storage);
        }
    }
}
