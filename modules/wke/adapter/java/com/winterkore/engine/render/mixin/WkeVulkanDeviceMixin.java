package com.winterkore.engine.render.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.winterkore.engine.render.WkeVulkanBridge;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class WkeVulkanDeviceMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDevice(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"), require = 1)
    private void wke$detachBeforeDeviceDestroy(VkDevice device, VkAllocationCallbacks allocator, Operation<Void> original) {
        WkeVulkanBridge.beforeDeviceDestroy(device);
        original.call(device, allocator);
    }
}
