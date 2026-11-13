package com.winterkore.engine.render.mixin;

import com.dark.winter.client.fixes.backend.VulkanDepthPipelineVariants;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.winterkore.engine.render.WkeVulkanBridge;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanDepthPipelineVariants.class, remap = false)
public abstract class WkeVulkanDepthVariantsMixin {
    @Shadow @Final private VulkanRenderPipeline owner;
    @Shadow @Final private String name;

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"), require = 1)
    private int wke$createVariant(VkDevice device, long cache, VkGraphicsPipelineCreateInfo.Buffer infos,
            VkAllocationCallbacks allocator, LongBuffer output, Operation<Integer> original) {
        if (allocator == null && WkeVulkanBridge.tryCreateGraphics(owner.device(), name, cache, infos, output)) return 0;
        return original.call(device, cache, infos, allocator, output);
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkDestroyPipeline(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"), require = 1)
    private void wke$destroyVariant(VkDevice device, long pipeline, VkAllocationCallbacks allocator, Operation<Void> original) {
        if (allocator != null || !WkeVulkanBridge.tryDestroyGraphics(device, pipeline))
            original.call(device, pipeline, allocator);
    }

    @WrapOperation(method = "lambda$new$1", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkDestroyPipeline(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"), require = 1)
    private static void wke$destroyCachedVariant(VkDevice device, long pipeline, VkAllocationCallbacks allocator,
            Operation<Void> original) {
        if (allocator != null || !WkeVulkanBridge.tryDestroyGraphics(device, pipeline))
            original.call(device, pipeline, allocator);
    }

}
