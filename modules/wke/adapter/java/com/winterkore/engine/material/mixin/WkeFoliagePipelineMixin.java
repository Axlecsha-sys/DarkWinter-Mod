package com.winterkore.engine.material.mixin;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.winterkore.engine.material.WkeFoliagePipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=VulkanRenderPipeline.class,remap=false)
public abstract class WkeFoliagePipelineMixin implements WkeFoliagePipeline {
    @Unique private int wke$materialKind;
    @Override public int wke$materialKind() { return wke$materialKind; }
    @Override public void wke$materialKind(int kind) { wke$materialKind=kind; }

    @Inject(method="compile",at=@At("RETURN"))
    private static void wke$classifyOwnedPipeline(VulkanDevice device,BackendRenderPipeline.CreateInfo info,
            CallbackInfoReturnable<VulkanRenderPipeline> cir) {
        String name=String.valueOf(info.name());
        int kind=name.contains("/material_main/")?MAIN:name.contains("/material_shadow/")?SHADOW:NONE;
        ((WkeFoliagePipeline)(Object)cir.getReturnValue()).wke$materialKind(kind);
    }
}
