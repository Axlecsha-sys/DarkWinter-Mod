// Owns offscreen vanilla sky/cloud backgrounds and supplies consistent fog inputs throughout one world render.
package com.dark.winter.visuals.vanillafog;

import com.dark.winter.client.TestingAccess;
import com.dark.winter.visuals.VisualConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Vector4f;

public final class VanillaFogRenderer implements AutoCloseable {
    private static final ThreadLocal<VanillaFogRenderer> CURRENT=new ThreadLocal<>();
    private TextureTarget sky,clouds;
    private Object world;
    private int width,height;
    private boolean active;

    public static boolean eligible(GameRenderer game) {
        // The separate DarkWinter atmosphere keeps its established pipeline and depth contract.
        return game.useImprovedTransparency() && !TestingAccess.pipeline(VisualConfig.shared().enabled);
    }

    public VanillaFogRenderer begin(GameRenderer game,Vector4f fogColor) {
        VanillaFogRenderer previous=CURRENT.get();
        Object currentWorld=Minecraft.getInstance().level;
        RenderTarget main=game.mainRenderTarget();
        active=eligible(game) && currentWorld!=null && main.width>0 && main.height>0;
        if(!active) {
            close();
            CURRENT.remove();
            return previous;
        }
        if(sky==null || width!=main.width || height!=main.height || world!=currentWorld) {
            close();
            width=main.width;height=main.height;world=currentWorld;
            sky=new TextureTarget("DarkWinter vanilla fog sky",width,height,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
            clouds=new TextureTarget("DarkWinter vanilla fog clouds",width,height,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
            active=true;
        }
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(sky.getColorTexture(),new Vector4f(fogColor),sky.getDepthTexture(),0.0);
        encoder.clearColorAndDepthTextures(clouds.getColorTexture(),new Vector4f(0),clouds.getDepthTexture(),0.0);
        CURRENT.set(this);
        return previous;
    }

    public static void restore(VanillaFogRenderer previous) {
        if(previous==null) CURRENT.remove(); else CURRENT.set(previous);
    }
    public static VanillaFogRenderer current() {
        VanillaFogRenderer frame=CURRENT.get();
        return frame!=null && frame.active?frame:null;
    }
    public RenderTarget skyTarget() { return sky; }

    public void prepareClouds(CloudRenderer renderer,CloudStatus status) {
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Vanilla fog cloud background",clouds.getColorTextureView(),Optional.of(new Vector4f(0)),
                clouds.getDepthTextureView(),OptionalDouble.of(0))) {
            RenderSystem.bindDefaultUniforms(pass);
            renderer.render(status,pass);
        }
    }

    public void blitSky(RenderPass pass) {
        pass.pushDebugGroup(() -> "Vanilla fog sky background");
        pass.setPipeline(RenderSystem.getCompiledPipeline(VanillaFogPipelines.SKY_BLIT));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("InSampler",sky.getColorTextureView(),nearest());
        pass.draw(3,1,0,0);
        pass.popDebugGroup();
    }

    public void bindTerrain(RenderPass pass) {
        GpuSampler sampler=nearest();
        pass.setUniform("SkySampler",sky.getColorTextureView(),sampler);
        pass.setUniform("CloudsSampler",clouds.getColorTextureView(),sampler);
        pass.setUniform("CloudsDepthSampler",clouds.getDepthTextureView(),sampler);
    }

    public void blitClouds(OitStage stage,OitRenderPassProvider.Parameters params) {
        try(RenderPass pass=OitRenderPassProvider.createRenderPass(stage,() -> "Vanilla fog resolved clouds",params)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(VanillaFogPipelines.CLOUDS.getPipeline(stage)));
            GpuSampler sampler=nearest();
            pass.setUniform("InSampler",clouds.getColorTextureView(),sampler);
            pass.setUniform("InDepthSampler",clouds.getDepthTextureView(),sampler);
            pass.draw(3,1,0,0);
        }
    }

    private static GpuSampler nearest() { return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST); }

    @Override public void close() {
        active=false;
        if(sky!=null) { sky.destroyBuffers();sky=null; }
        if(clouds!=null) { clouds.destroyBuffers();clouds=null; }
        width=height=0;world=null;
    }
}
