// Covers the underwater sky horizon with the current frame's water fog color.
package com.dark.winter.visuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;

public final class UnderwaterSkyOccluder {
    private record Frame(boolean underwater, float red, float green, float blue, float alpha) {}

    private static final Frame DISABLED = new Frame(false, 0.0F, 0.0F, 0.0F, 0.0F);
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "DarkWinter underwater sky fog");
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("darkwinter_visuals", "pipeline/underwater_sky_occluder"))
            .withVertexShader(Identifier.fromNamespaceAndPath("darkwinter_visuals", "core/underwater_sky_occluder"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("darkwinter_visuals", "core/underwater_sky_occluder"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build()
    );

    private UnderwaterSkyOccluder() {}

    public static void capture(LevelRenderState state, boolean panoramic) {
        SkyRenderState sky = state.skyRenderState;
        Frame frame = DISABLED;
        if (!panoramic && sky.skybox == Skybox.OVERWORLD
                && state.cameraRenderState.fogType == FogType.WATER
                && !state.cameraRenderState.entityRenderState.doesMobEffectBlockSky) {
            Vector4f fog = state.cameraRenderState.fogData.color;
            frame = new Frame(true, fog.x, fog.y, fog.z, fog.w);
        }
        // Every extraction replaces this value, including air, other dimensions and blocked skies.
        sky.setData(FRAME, frame);
    }

    public static boolean isActive(SkyRenderState state) {
        Frame frame = state.getData(FRAME);
        return frame != null && frame.underwater && state.skybox == Skybox.OVERWORLD && Minecraft.getInstance().level != null;
    }

    public static void render(RenderPass pass, SkyRenderState state) {
        if (!isActive(state)) return;
        Frame frame = state.getData(FRAME);

        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(
            RenderSystem.getModelViewMatrixCopy(), new Vector4f(frame.red, frame.green, frame.blue, frame.alpha)
        );
        var pipeline = RenderSystem.getCompiledPipeline(PIPELINE);
        pass.pushDebugGroup(() -> "Underwater sky fog");
        try {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.draw(3, 1, 0, 0);
        } finally {
            pass.popDebugGroup();
        }
    }
}
