// Retains immutable Vulkan pipeline metadata and destroys additional format variants with vanilla GPU lifetime.
package com.dark.winter.client.mixin.client.fixes.backend;

import com.dark.winter.client.fixes.backend.DepthFormatPipelineAccess;
import com.dark.winter.client.fixes.backend.VulkanDepthPipelineVariants;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanRenderPipeline.class, remap = false)
public abstract class VulkanRenderPipelineDepthFormatMixin implements DepthFormatPipelineAccess {
    @Shadow @Final private LongList shaderModules;
    @Unique private @Nullable VulkanDepthPipelineVariants darkwinter$depthVariants;

    @Inject(method = "compile", at = @At("RETURN"), require = 1)
    private static void darkwinter$capturePipeline(VulkanDevice device, BackendRenderPipeline.CreateInfo info,
            CallbackInfoReturnable<VulkanRenderPipeline> cir) {
        ((DepthFormatPipelineAccess)(Object)cir.getReturnValue()).darkwinter$captureDepthPipelineInfo(info);
    }

    @Override
    public void darkwinter$captureDepthPipelineInfo(BackendRenderPipeline.CreateInfo info) {
        if (darkwinter$depthVariants != null) throw new IllegalStateException("Depth-format pipeline metadata was already captured");
        darkwinter$depthVariants = new VulkanDepthPipelineVariants((VulkanRenderPipeline)(Object)this, info, shaderModules);
    }

    @Override
    public long darkwinter$pipelineForDepthFormat(GpuFormat format) {
        if (darkwinter$depthVariants == null) throw new IllegalStateException("Vulkan pipeline depth-format metadata is missing");
        return darkwinter$depthVariants.get(format);
    }

    @WrapMethod(method = "destroy")
    private void darkwinter$destroyDepthVariants(Operation<Void> original) {
        try {
            if (darkwinter$depthVariants != null) darkwinter$depthVariants.close();
        } finally {
            original.call();
        }
    }
}
