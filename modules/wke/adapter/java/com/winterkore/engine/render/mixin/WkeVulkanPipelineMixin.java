package com.winterkore.engine.render.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.winterkore.engine.render.WkeVulkanBridge;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanRenderPipeline.class, remap = false)
public abstract class WkeVulkanPipelineMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"), require = 2)
    private static int wke$create(VkDevice vkDevice, long cache, VkGraphicsPipelineCreateInfo.Buffer infos,
            VkAllocationCallbacks allocator, LongBuffer output, Operation<Integer> original,
            VulkanDevice host, BackendRenderPipeline.CreateInfo info) {
        if (allocator == null && WkeVulkanBridge.tryCreateGraphics(host, info.name(), cache, infos, output)) return 0;
        return original.call(vkDevice, cache, infos, allocator, output);
    }

    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkDestroyPipeline(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"), require = 2)
    private void wke$destroy(VkDevice device, long pipeline, VkAllocationCallbacks allocator, Operation<Void> original) {
        if (allocator != null || !WkeVulkanBridge.tryDestroyGraphics(device, pipeline))
            original.call(device, pipeline, allocator);
    }
}
