// Labels Tracy GPU contexts with the actual Vulkan backend while preserving generic queries.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.jtracy.GpuApi;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.TracyGpuProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TracyGpuProfiler.class)
abstract class VulkanTracyApiMixin {
    @ModifyExpressionValue(method="<init>", at=@At(value="FIELD",
            target="Lcom/mojang/jtracy/GpuApi;OPENGL:Lcom/mojang/jtracy/GpuApi;"),
            require=1, allow=1)
    private GpuApi darkwinter$actualGpuApi(GpuApi original,
            @Local(argsOnly=true) FrontendGpuDevice device) {
        return "Vulkan".equals(device.getDeviceInfo().backendName()) ? GpuApi.VULKAN : original;
    }
}
