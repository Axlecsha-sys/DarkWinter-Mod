// Draws existing solid and cutout shadow casters with color writes and color shading disabled.
package com.dark.winter.visuals;

import com.dark.winter.visuals.mixin.ShadowTerrainLayersAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;

/** Render-thread owner of the optional default Vulkan depth path; native buffers and draws remain native-owned. */
final class ShadowDepthTerrain {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("darkwinter.visuals.shadowDepthOnly", "true"));
    private static final ChunkSectionLayer[] SOLID_LAYERS = { ChunkSectionLayer.SOLID };
    private static final ChunkSectionLayer[] CUTOUT_LAYERS = { ChunkSectionLayer.CUTOUT };
    private static final ColorTargetState NO_COLOR_WRITES =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0);
    private static final RenderPipeline SOLID = !ENABLED ? null : RenderPipelines.registerOptional(
            RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(id("pipeline/shadow_solid_depth"))
                    .withFragmentShader(id("core/shadow_solid_depth"))
                    .withColorTargetState(NO_COLOR_WRITES).build());
    private static final RenderPipeline CUTOUT = !ENABLED ? null : RenderPipelines.registerOptional(
            RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(id("pipeline/shadow_cutout_depth"))
                    .withFragmentShader(id("core/shadow_cutout_depth"))
                    .withShaderDefine("ALPHA_CUTOUT", 0.5F)
                    .withColorTargetState(NO_COLOR_WRITES).build());
    private int resourceEpoch = -1;
    private boolean ready, failed;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("darkwinter_visuals", path);
    }

    /** Called before creating the pass: failure cannot leave a partially replaced shadow map. */
    boolean prepare(ChunkSectionsToRender terrain) {
        if (!ENABLED || !(terrain instanceof ChunkSectionsToRender.DrawSeparate)
                || !((Object) terrain instanceof ShadowTerrainLayersAccess)
                || !"Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName())) return false;
        if (resourceEpoch != SceneFrame.resourceEpoch) {
            resourceEpoch = SceneFrame.resourceEpoch;
            ready = failed = false;
        }
        if (failed) return false;
        if (ready) return true;
        try {
            // Use the normal cache, including PipelineGuard's direct render-thread executor.
            var solid = RenderSystem.getCompiledPipeline(SOLID);
            var cutout = RenderSystem.getCompiledPipeline(CUTOUT);
            if (solid == null || cutout == null)
                throw new IllegalStateException("An optional shadow depth program did not compile");
            ready = true;
            return true;
        } catch (RuntimeException error) {
            failed = true;
            VisualConfig.LOG.error("Shadow depth programs unavailable; retaining native terrain shading until resource reload.", error);
            return false;
        }
    }

    /** Read-only path identity for a depth-texture cache; compiled programs are never retained by it. */
    int cacheMode() {
        if (!ENABLED) return 0;
        if (resourceEpoch != SceneFrame.resourceEpoch) return 1;
        return failed ? 2 : ready ? 3 : 1;
    }

    /** Uses the same layer order, bindings, indexed draw lists and depth attachment as the native path. */
    void renderPrepared(ChunkSectionsToRender terrain, RenderPass pass, GpuSampler sampler,
                        GpuTextureView atlas, GpuTextureView lightmap) {
        ShadowTerrainLayersAccess access = (ShadowTerrainLayersAccess) (Object) terrain;
        access.darkwinter$renderShadowLayers(SOLID_LAYERS, sampler, pass, atlas, lightmap, SOLID, null);
        access.darkwinter$renderShadowLayers(CUTOUT_LAYERS, sampler, pass, atlas, lightmap, CUTOUT, null);
    }
}
