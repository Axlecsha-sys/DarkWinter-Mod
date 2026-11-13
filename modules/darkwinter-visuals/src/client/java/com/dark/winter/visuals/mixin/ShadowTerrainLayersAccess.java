// Exposes native terrain layer overrides without replacing draw-list or buffer ownership.
package com.dark.winter.visuals.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkSectionsToRender.class)
public interface ShadowTerrainLayersAccess {
    @Invoker("renderLayers")
    void darkwinter$renderShadowLayers(ChunkSectionLayer[] layers, GpuSampler sampler, RenderPass pass,
            GpuTextureView atlas, GpuTextureView lightmap, RenderPipeline override, RenderPipeline overrideMultidraw);
}
