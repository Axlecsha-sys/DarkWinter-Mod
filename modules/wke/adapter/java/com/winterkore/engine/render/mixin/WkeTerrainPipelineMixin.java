// Selects the WKE pipeline description before Minecraft compiles/caches its backend pipeline.
package com.winterkore.engine.render.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.winterkore.engine.render.WkeRendererRuntime;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = {ChunkSectionsToRender.DrawSeparate.class, ChunkSectionsToRender.DrawIndirect.class}, remap = false)
public abstract class WkeTerrainPipelineMixin {
    @ModifyArg(method = "render", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
        index = 0, require = 1, allow = 1)
    private RenderPipeline wke$ownedTerrainPipeline(RenderPipeline source) {
        return WkeRendererRuntime.pipeline(source);
    }
}
