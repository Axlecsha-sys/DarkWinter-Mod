// Draws the existing procedural sky before terrain and stores cloud color at its sampled scene depth.
package com.dark.winter.visuals.render;

import com.dark.winter.visuals.SceneFrame;
import com.dark.winter.visuals.VisualRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class WeatherSkyRenderer implements AutoCloseable {
    private static final BindGroupLayout SCENE = BindGroupLayout.builder()
            .withUniform(SceneFrame.BLOCK,UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout PROJECTION = BindGroupLayout.builder()
            .withUniform("WeatherSkyProjection",UniformType.UNIFORM_BUFFER).build();
    private static final RenderPipeline SKY = RenderPipelines.register(base("weather_sky",false)
            .withFragmentShader(id("core/weather_sky_background")).build());
    private static final RenderPipeline CLOUDS = RenderPipelines.register(base("weather_sky_clouds",true)
            .withFragmentShader(id("core/weather_sky_clouds")).withBindGroupLayout(PROJECTION).build());
    private MappableRingBuffer sceneUniforms, projectionUniforms;
    private boolean sceneUsed, projectionUsed,failed;
    private int resourceEpoch=-1;
    private GpuBufferSlice sceneSlice,projectionSlice;
    private CompiledRenderPipeline skyPipeline,cloudPipeline;

    private static RenderPipeline.Builder base(String name,boolean depthWrite) {
        return RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/"+name)).withVertexShader("core/screenquad")
                .withBindGroupLayout(SCENE).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,depthWrite))
                .withColorTargetState(ColorTargetState.DEFAULT).withCull(false);
    }
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("darkwinter_visuals",path);
    }

    /** Validate both new programs before taking ownership of a native sky frame. */
    public boolean available() {
        if(resourceEpoch!=SceneFrame.resourceEpoch) { resourceEpoch=SceneFrame.resourceEpoch;failed=false; }
        if(failed) return false;
        try {
            skyPipeline=RenderSystem.getCompiledPipeline(SKY);
            cloudPipeline=RenderSystem.getCompiledPipeline(CLOUDS);
            return true;
        } catch(RuntimeException error) {
            failed=true;
            com.dark.winter.visuals.VisualConfig.LOG.error(
                    "Procedural sky unavailable; keeping native sky, clouds and environmental fog until resource reload.",error);
            return false;
        }
    }

    /** Upload outside every RenderPass: rotating a ring buffer can enqueue a fence. */
    public boolean prepare(SceneFrame frame) {
        if(!available()) return false;
        try {
            sceneSlice=uploadScene(frame);
            projectionSlice=uploadProjection(frame);
            return true;
        } catch(RuntimeException error) {
            failed=true;sceneSlice=projectionSlice=null;
            com.dark.winter.visuals.VisualConfig.LOG.error(
                    "Procedural sky preparation failed; retaining the native frame until resource reload.",error);
            return false;
        }
    }

    public static boolean active() {
        SceneFrame frame=VisualRenderer.currentFrame();
        return frame!=null && frame.weatherSky && frame.backgroundAvailable && !frame.inFluid && frame.config.debug==0;
    }

    public void renderBackground(RenderPass pass) {
        if(!active()) return;
        pass.pushDebugGroup(() -> "DarkWinter weather sky background");
        try {
            pass.setPipeline(skyPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(SceneFrame.BLOCK,sceneSlice);
            pass.draw(3,1,0,0);
        } finally { pass.popDebugGroup(); }
    }

    public void renderClouds(RenderTarget target) {
        if(!active()) return;
        if(sceneSlice==null || projectionSlice==null) throw new IllegalStateException("Weather sky must be prepared before rendering");
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "DarkWinter sampled weather clouds",target.getColorTextureView(),Optional.of(new Vector4f(0)),
                target.getDepthTextureView(),OptionalDouble.of(0))) {
            pass.setPipeline(cloudPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(SceneFrame.BLOCK,sceneSlice);
            pass.setUniform("WeatherSkyProjection",projectionSlice);
            pass.draw(3,1,0,0);
        }
    }

    private GpuBufferSlice uploadScene(SceneFrame frame) {
        if(sceneUniforms==null) sceneUniforms=new MappableRingBuffer(
                () -> "DarkWinter early sky scene",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,SceneFrame.BYTES);
        else if(sceneUsed) sceneUniforms.rotate();
        GpuBuffer buffer=sceneUniforms.currentBuffer();
        try(GpuBufferSlice.MappedView view=buffer.map(false,true)) { frame.write(view.data()); }
        sceneUsed=true;
        return buffer.slice();
    }

    private GpuBufferSlice uploadProjection(SceneFrame frame) {
        if(projectionUniforms==null) projectionUniforms=new MappableRingBuffer(
                () -> "DarkWinter cloud scene projection",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,80);
        else if(projectionUsed) projectionUniforms.rotate();
        GpuBuffer buffer=projectionUniforms.currentBuffer();
        try(GpuBufferSlice.MappedView view=buffer.map(false,true)) {
            var data=view.data().order(ByteOrder.nativeOrder());
            new Matrix4f(frame.inverseViewProjection).invert().get(0,data);
            data.putFloat(64,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()?1:0);
            data.putFloat(68,0);data.putFloat(72,0);data.putFloat(76,0);
        }
        projectionUsed=true;
        return buffer.slice();
    }

    @Override public void close() {
        if(sceneUniforms!=null) { sceneUniforms.close();sceneUniforms=null; }
        if(projectionUniforms!=null) { projectionUniforms.close();projectionUniforms=null; }
        sceneUsed=projectionUsed=false;
        sceneSlice=projectionSlice=null;skyPipeline=cloudPipeline=null;
    }
}
