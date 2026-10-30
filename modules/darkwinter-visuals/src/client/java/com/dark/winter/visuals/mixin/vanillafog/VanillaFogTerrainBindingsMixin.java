// Supplies the same sky/cloud backgrounds to single-draw, multi-draw and OIT terrain passes.
package com.dark.winter.visuals.mixin.vanillafog;

import com.dark.winter.visuals.vanillafog.VanillaFogRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public abstract class VanillaFogTerrainBindingsMixin {
    @Inject(method="renderLayers",at=@At("HEAD"),require=1)
    private void darkwinter$bindBackground(ChunkSectionLayer[] layers,GpuSampler sampler,RenderPass pass,
            GpuTextureView atlas,GpuTextureView lightmap,RenderPipeline override,RenderPipeline overrideMultidraw,CallbackInfo ci) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame!=null)frame.bindTerrain(pass);
    }
}
