// Selects background-aware copies of existing terrain pipelines without changing vertex or draw-buffer ownership.
package com.dark.winter.visuals.mixin.vanillafog;

import com.dark.winter.visuals.vanillafog.VanillaFogPipelines;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({ChunkSectionsToRender.DrawSeparate.class,ChunkSectionsToRender.DrawIndirect.class})
public abstract class VanillaFogTerrainPipelineMixin {
    @ModifyArg(method="render",at=@At(value="INVOKE",
            target="Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),index=0,require=1)
    private RenderPipeline darkwinter$backgroundFogPipeline(RenderPipeline original) {
        return VanillaFogPipelines.terrain(original);
    }
}
