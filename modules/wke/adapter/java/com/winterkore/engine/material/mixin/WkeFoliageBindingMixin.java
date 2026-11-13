package com.winterkore.engine.material.mixin;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.winterkore.engine.material.WkeFoliageTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VulkanRenderPass.class,remap=false)
public abstract class WkeFoliageBindingMixin {
    @Inject(method="setPipeline",at=@At("TAIL"))
    private void wke$bindOwnedMaterials(BackendRenderPipeline pipeline,CallbackInfo ci) {
        if(pipeline instanceof VulkanRenderPipeline vulkan)
            WkeFoliageTextures.bind((VulkanRenderPass)(Object)this,vulkan);
    }
}
