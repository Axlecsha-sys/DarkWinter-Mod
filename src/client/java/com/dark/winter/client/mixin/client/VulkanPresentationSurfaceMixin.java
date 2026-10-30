// Selects a supported DXGI presentation surface while retaining the original Vulkan fallback.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.vulkan.DxgiVulkanSurface;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanPresentationSurfaceMixin {
    @Inject(method = "createSurface", at = @At("HEAD"), cancellable = true, require = 1)
    private void darkwinter$useDxgiPresentation(long window, BooleanSupplier iconified,
            CallbackInfoReturnable<GpuSurfaceBackend> cir) {
        var surface = DxgiVulkanSurface.tryCreate((VulkanDevice)(Object)this, window);
        if (surface != null) cir.setReturnValue(surface);
    }
}
