// Propagates the actual Vulkan depth attachment format before any pipeline is bound in the new pass.
package com.dark.winter.client.mixin.client.fixes.backend;

import com.dark.winter.client.fixes.backend.DepthFormatPassAccess;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public abstract class VulkanCommandEncoderDepthFormatMixin {
    @Inject(method = "createRenderPass", at = @At("HEAD"), require = 1)
    private void darkwinter$validateDepthFormat(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPassBackend> cir) {
        var depth = descriptor.depthAttachment();
        if (depth != null && !depth.textureView().texture().getFormat().hasDepthAspect())
            throw new IllegalArgumentException("Vulkan depth attachment has a non-depth format: " + depth.textureView().texture().getFormat());
    }

    @Inject(method = "createRenderPass", at = @At("RETURN"), require = 1)
    private void darkwinter$captureDepthFormat(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPassBackend> cir) {
        var depth = descriptor.depthAttachment();
        ((DepthFormatPassAccess)cir.getReturnValue()).darkwinter$setDepthAttachmentFormat(
            depth == null ? null : depth.textureView().texture().getFormat());
    }
}
