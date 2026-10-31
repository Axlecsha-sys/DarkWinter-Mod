// Suppresses flat vanilla clouds only when the procedural weather sky owns this frame.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.VisualRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only the default flat clouds when the procedural weather sky owns this frame. */
@Mixin(CloudRenderer.class)
public abstract class VanillaCloudBypassMixin {
    @Inject(method="render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at=@At("HEAD"), cancellable=true, require=0)
    private void darkwinter$replaceVanillaClouds(CloudStatus status, RenderPass pass, CallbackInfo ci) {
        if (com.dark.winter.visuals.render.WeatherSkyRenderer.active()) ci.cancel();
    }

    @Inject(method="renderOit", at=@At("HEAD"), cancellable=true, require=0)
    private void darkwinter$replaceVanillaCloudsOit(CloudStatus status, OitStage stage,
            GpuTextureView depth, OitRenderPassProvider.Parameters params, CallbackInfo ci) {
        if (VisualRenderer.needsStableDepth()) ci.cancel();
    }
}
