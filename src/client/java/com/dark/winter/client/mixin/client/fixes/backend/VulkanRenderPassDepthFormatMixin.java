// Selects a native Vulkan pipeline variant matching this pass's actual depth attachment.
package com.dark.winter.client.mixin.client.fixes.backend;

import com.dark.winter.client.fixes.backend.DepthFormatPassAccess;
import com.dark.winter.client.fixes.backend.DepthFormatPipelineAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanRenderPass.class, remap = false)
public abstract class VulkanRenderPassDepthFormatMixin implements DepthFormatPassAccess {
    @Shadow @Final private boolean hasDepth;
    @Unique private @Nullable GpuFormat darkwinter$depthFormat;
    @Unique private boolean darkwinter$depthFormatCaptured;

    @Override
    public void darkwinter$setDepthAttachmentFormat(@Nullable GpuFormat format) {
        if (hasDepth != (format != null) || (format != null && !format.hasDepthAspect()))
            throw new IllegalArgumentException("Depth attachment presence and format disagree");
        if (darkwinter$depthFormatCaptured && darkwinter$depthFormat != format)
            throw new IllegalStateException("The depth attachment format cannot change inside a render pass");
        darkwinter$depthFormat = format;
        darkwinter$depthFormatCaptured = true;
    }

    @Inject(method = "setPipeline", at = @At("HEAD"), require = 1)
    private void darkwinter$validatePipelineDepth(BackendRenderPipeline pipeline, CallbackInfo ci) {
        if (pipeline instanceof VulkanRenderPipeline vulkan) {
            if (!darkwinter$depthFormatCaptured) throw new IllegalStateException("Vulkan render pass depth format was not captured");
            if (vulkan.isClosed()) throw new IllegalStateException("Cannot bind a closed Vulkan pipeline");
            if (!hasDepth && vulkan.withoutDepthPipeline() == 0L)
                throw new IllegalStateException("This Vulkan pipeline requires a depth attachment");
        }
    }

    @WrapOperation(
        method = "setPipeline",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline;withDepthPipeline()J"),
        require = 1
    )
    private long darkwinter$matchDepthFormat(VulkanRenderPipeline pipeline, Operation<Long> original) {
        if (!darkwinter$depthFormatCaptured || darkwinter$depthFormat == null)
            throw new IllegalStateException("A depth-enabled Vulkan pass must have an actual depth format");
        return ((DepthFormatPipelineAccess)(Object)pipeline).darkwinter$pipelineForDepthFormat(darkwinter$depthFormat);
    }
}
