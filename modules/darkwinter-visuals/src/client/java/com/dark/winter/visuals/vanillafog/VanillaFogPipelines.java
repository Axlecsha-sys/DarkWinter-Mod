// Adapts complete 26.3 terrain pipeline state to background-aware fog and depth-preserving cloud blits.
package com.dark.winter.visuals.vanillafog;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;

public final class VanillaFogPipelines {
    public static final BindGroupLayout BACKGROUND = BindGroupLayout.builder()
            .withUniform("SkySampler",UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CloudsSampler",UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CloudsDepthSampler",UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout CLOUD_INPUT = BindGroupLayout.builder()
            .withUniform("InSampler",UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("InDepthSampler",UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final Map<RenderPipeline,RenderPipeline> TERRAIN = new IdentityHashMap<>();
    private static final Map<RenderPipeline,RenderPipeline> HORIZONTAL_TERRAIN = new IdentityHashMap<>();
    public static final RenderPipeline SKY_BLIT = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/vanillafog/sky_blit"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/vanillafog/sky_blit"))
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false))
                .withColorTargetState(ColorTargetState.DEFAULT).withCull(false).build());
    private static final RenderPipeline.Snippet CLOUD_BLIT = RenderPipeline.builder()
            .withVertexShader("core/screenquad").withFragmentShader(id("core/vanillafog/cloud_blit"))
            .withBindGroupLayout(CLOUD_INPUT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).buildSnippet();
    public static final RenderPipeline CLASSIC_CLOUDS=RenderPipelines.register(
            RenderPipeline.builder(CLOUD_BLIT,RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/vanillafog/cloud_classic"))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build());
    public static final OitPipelineSet CLOUDS = new OitPipelineSet(
            cloudStage("bounds",RenderPipelines.OIT_DEPTH_BOUNDS_SNIPPET),
            cloudStage("transmittance",RenderPipelines.OIT_TRANSMITTANCE_SNIPPET),
            cloudStage("accumulate",RenderPipelines.OIT_ACCUMULATE_SNIPPET));

    private VanillaFogPipelines() {}

    private static RenderPipeline cloudStage(String name,RenderPipeline.Snippet stage) {
        return RenderPipelines.register(RenderPipeline.builder(CLOUD_BLIT,stage)
                .withLocation(id("pipeline/vanillafog/cloud_"+name)).build());
    }

    public static RenderPipeline terrain(RenderPipeline original) {
        if(VanillaFogRenderer.current()==null
                || !Identifier.withDefaultNamespace("core/terrain").equals(original.getShaders().get(ShaderType.FRAGMENT))) return original;
        boolean horizontal=com.dark.winter.visuals.VisualRenderer.currentFrame()!=null && com.dark.winter.visuals.VisualRenderer.currentFrame().atmosphereAllowed && com.dark.winter.visuals.VisualRenderer.controlsAtmosphere(net.minecraft.client.Minecraft.getInstance().level);
        return (horizontal?HORIZONTAL_TERRAIN:TERRAIN).computeIfAbsent(original,source -> {
            ColorTargetState[] targets=source.getColorTargetStates().toArray(ColorTargetState[]::new);
            RenderPipeline.Snippet state=new RenderPipeline.Snippet(source.getShaders(),Optional.of(source.getShaderDefines()),
                    Optional.of(source.getBindGroupLayouts()),targets,targets.length,Optional.ofNullable(source.getDepthStencilState()),
                    Optional.of(source.getPolygonMode()),Optional.of(source.isCull()),
                    source.getVertexFormatBindings().toArray(VertexFormat[]::new),Optional.of(source.getPrimitiveTopology()),source.pushConstantSize());
            RenderPipeline.Builder builder=RenderPipeline.builder(state)
                    .withLocation(id("pipeline/vanillafog/"+(horizontal?"horizontal/":"")+source.getLocation().getNamespace()+"/"+source.getLocation().getPath()))
                    .withFragmentShader(id("core/vanillafog/terrain"))
                    .withShaderDefine("IMPROVED_FOG").withBindGroupLayout(BACKGROUND);
            if(horizontal) builder.withVertexShader(id("core/vanillafog/terrain"))
                    .withShaderDefine("DW_HORIZONTAL_DISTANCE_FOG");
            return RenderPipelines.register(builder.build());
        });
    }

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("darkwinter_visuals",path); }
}
