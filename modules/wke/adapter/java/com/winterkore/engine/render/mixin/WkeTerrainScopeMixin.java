// Limits WKE replacement to actual terrain rendering, including OIT and custom terrain passes.
package com.winterkore.engine.render.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.winterkore.engine.render.WkeRendererRuntime;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ChunkSectionsToRender.class, remap = false)
public abstract class WkeTerrainScopeMixin {
    @WrapMethod(method = "renderLayers([Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V")
    private void wke$terrainScope(ChunkSectionLayer[] layers, GpuSampler sampler, RenderPass pass,
            GpuTextureView atlas, GpuTextureView lightmap, RenderPipeline override,
            RenderPipeline overrideMultidraw, Operation<Void> original) {
        WkeRendererRuntime.enterTerrain();
        try { original.call(layers, sampler, pass, atlas, lightmap, override, overrideMultidraw); }
        finally { WkeRendererRuntime.leaveTerrain(); }
    }
}
