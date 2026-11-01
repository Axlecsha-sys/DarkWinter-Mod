// Keeps an explicitly selected Vulkan backend from silently falling back to OpenGL.
package com.dark.winter.client.mixin.client;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredVulkanBackendMixin {
    @Inject(method="getBackendsToTry", at=@At("HEAD"), cancellable=true, require=1)
    private void darkwinter$keepSelectedVulkan(CallbackInfoReturnable<GpuBackend[]> callback) {
        if ((Object)this == PreferredGraphicsApi.VULKAN) {
            callback.setReturnValue(new GpuBackend[]{new VulkanBackend()});
        }
    }
}
