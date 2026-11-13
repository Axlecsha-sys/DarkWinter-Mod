package com.winterkore.engine.material.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.winterkore.engine.material.WkeFoliageTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VulkanDevice.class,remap=false)
public abstract class WkeFoliageDeviceMixin {
    @Inject(method="close",at=@At("HEAD"))
    private void wke$releaseMaterials(CallbackInfo ci) { WkeFoliageTextures.close(); }
}
