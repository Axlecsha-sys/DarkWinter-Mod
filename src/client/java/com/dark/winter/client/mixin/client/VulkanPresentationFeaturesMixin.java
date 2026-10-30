// Requests optional Win32 image and fence import extensions for DXGI presentation.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.vulkan.DxgiVulkanSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanFeatureSets.class, remap = false)
public abstract class VulkanPresentationFeaturesMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true, require = 1)
    private static void darkwinter$requestDxgiImports(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        if (!DxgiVulkanSurface.platformSupported()) return;
        Set<FeatureSet> features = new HashSet<>(cir.getReturnValue());
        features.add(DxgiVulkanSurface.FEATURES);
        cir.setReturnValue(features);
    }
}
