// Owns coherent sky/cloud backgrounds for native fog, procedural weather and the final atmosphere resolve.
package com.dark.winter.visuals.vanillafog;

import com.dark.winter.client.TestingAccess;
import com.dark.winter.visuals.VisualConfig;
import com.dark.winter.visuals.render.WeatherSkyRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

public final class VanillaFogRenderer implements AutoCloseable {
    public static final Identifier SKY_BACKGROUND=VanillaFogPipelines.id("sky_background");
    public static final Identifier CLOUD_BACKGROUND=VanillaFogPipelines.id("cloud_background");
    private static final ThreadLocal<VanillaFogRenderer> CURRENT=new ThreadLocal<>();
    private static WeakReference<VanillaFogRenderer> latest=new WeakReference<>(null);
    static {
        ClientPlayConnectionEvents.DISCONNECT.register((listener,client) -> {
            VanillaFogRenderer renderer=latest.get();
            Object disconnectedWorld=renderer==null?null:renderer.world;
            if(renderer!=null) client.execute(() -> {
                if(renderer.world==disconnectedWorld) renderer.close();
            });
        });
    }
    private final WeatherSkyRenderer weatherSky=new WeatherSkyRenderer();
    private TextureTarget sky,clouds;
    private Object world;
    private int width,height;
    private boolean active,hasClouds,weatherClouds;

    public static boolean eligible(GameRenderer game) {
        return game.useImprovedTransparency() || TestingAccess.pipeline(VisualConfig.shared().enabled);
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
            sky=new TextureTarget("DarkWinter coherent sky background",width,height,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
            active=true;
        }
        hasClouds=weatherClouds=false;
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                sky.getColorTexture(),new Vector4f(fogColor),sky.getDepthTexture(),0.0);
        CURRENT.set(this);
        latest=new WeakReference<>(this);
        return previous;
    }

    public static void restore(VanillaFogRenderer previous) {
        if(previous==null) CURRENT.remove(); else CURRENT.set(previous);
    }
    public static VanillaFogRenderer current() {
        VanillaFogRenderer frame=CURRENT.get();
        return frame!=null && frame.active?frame:null;
    }
    public static VanillaFogRenderer latest() {
        VanillaFogRenderer frame=latest.get();
        return frame!=null && frame.active && frame.world==Minecraft.getInstance().level?frame:null;
    }
    public RenderTarget skyTarget() { return sky; }
    public RenderTarget cloudTarget() { return clouds; }
    public WeatherSkyRenderer weatherSky() { return weatherSky; }
    public boolean hasClouds() { return hasClouds; }
    public boolean weatherClouds() { return weatherClouds; }

    public void prepareClouds(CloudRenderer renderer,CloudStatus status,boolean nativeCloudsVisible) {
        weatherClouds=WeatherSkyRenderer.active();
        int cloudWidth=width,cloudHeight=height;
        if(weatherClouds) {
            int cap=switch(VisualConfig.shared().quality) { case LOW -> 640; case BALANCED -> 960; case HIGH -> 1280; };
            float scale=Math.min(1f,(float)cap/width);
            cloudWidth=Math.max(1,Math.round(width*scale));cloudHeight=Math.max(1,Math.round(height*scale));
        }
        if(clouds==null || clouds.width!=cloudWidth || clouds.height!=cloudHeight) {
            if(clouds!=null) clouds.destroyBuffers();
            clouds=new TextureTarget("DarkWinter coherent cloud background",cloudWidth,cloudHeight,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                clouds.getColorTexture(),new Vector4f(0),clouds.getDepthTexture(),0.0);
        hasClouds=weatherClouds || nativeCloudsVisible;
        if(weatherClouds) weatherSky.renderClouds(clouds);
        else if(nativeCloudsVisible) {
            try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Vanilla fog cloud background",clouds.getColorTextureView(),Optional.empty(),
                    clouds.getDepthTextureView(),OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                renderer.render(status,pass);
            }
        }
    }

    public void blitSky(RenderPass pass) {
        pass.pushDebugGroup(() -> "Coherent sky background");
        pass.setPipeline(RenderSystem.getCompiledPipeline(VanillaFogPipelines.SKY_BLIT));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("InSampler",sky.getColorTextureView(),nearest());
        pass.draw(3,1,0,0);
        pass.popDebugGroup();
    }

    public void bindTerrain(RenderPass pass) {
        if(clouds==null) throw new IllegalStateException("Cloud background must be prepared before terrain");
        GpuSampler sampler=nearest();
        pass.setUniform("SkySampler",sky.getColorTextureView(),sampler);
        pass.setUniform("CloudsSampler",clouds.getColorTextureView(),sampler);
        pass.setUniform("CloudsDepthSampler",clouds.getDepthTextureView(),sampler);
    }

    public void blitClouds(OitStage stage,OitRenderPassProvider.Parameters params) {
        if(!hasClouds) return;
        try(RenderPass pass=OitRenderPassProvider.createRenderPass(stage,() -> "Resolved coherent clouds",params)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(VanillaFogPipelines.CLOUDS.getPipeline(stage)));
            bindClouds(pass);
            pass.draw(3,1,0,0);
        }
    }

    public void blitCloudsClassic(RenderPass pass) {
        if(!hasClouds) return;
        pass.setPipeline(RenderSystem.getCompiledPipeline(VanillaFogPipelines.CLASSIC_CLOUDS));
        RenderSystem.bindDefaultUniforms(pass);
        bindClouds(pass);
        pass.draw(3,1,0,0);
    }

    private void bindClouds(RenderPass pass) {
        pass.setUniform("InSampler",clouds.getColorTextureView(),nearest());
        pass.setUniform("InDepthSampler",clouds.getDepthTextureView(),nearest());
    }
    private static GpuSampler nearest() { return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST); }

    @Override public void close() {
        active=hasClouds=weatherClouds=false;
        if(sky!=null) { sky.destroyBuffers();sky=null; }
        if(clouds!=null) { clouds.destroyBuffers();clouds=null; }
        weatherSky.close();
        width=height=0;world=null;
    }
}
