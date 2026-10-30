// Schedules coherent vanilla fog backgrounds and cloud OIT phases without replacing the world's draw lists.
package com.dark.winter.visuals.mixin.vanillafog;

import com.dark.winter.visuals.vanillafog.VanillaFogRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaFogLevelRendererMixin {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private CloudRenderer cloudRenderer;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Unique private VanillaFogRenderer darkwinter$vanillaFog;

    @WrapMethod(method="render")
    private void darkwinter$backgroundScope(GraphicsResourceAllocator allocator,boolean outlines,CameraRenderState camera,
            GpuBufferSlice fog,Vector4f fogColor,boolean sky,boolean consistentDepth,Operation<Void> original) {
        if(darkwinter$vanillaFog==null) darkwinter$vanillaFog=new VanillaFogRenderer();
        VanillaFogRenderer previous=darkwinter$vanillaFog.begin(gameRenderer,fogColor);
        try { original.call(allocator,outlines,camera,fog,fogColor,sky,consistentDepth); }
        finally { VanillaFogRenderer.restore(previous); }
    }

    @Inject(method="prepareTranslucents",at=@At("TAIL"),require=1)
    private void darkwinter$resolveCloudBackground(CallbackInfo ci) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame!=null && optionsRenderState.cloudStatus!=CloudStatus.OFF && ARGB.alpha(levelRenderState.cloudColor)>0)
            frame.prepareClouds(cloudRenderer,optionsRenderState.cloudStatus);
    }

    @Inject(method="executeSolid",at=@At("HEAD"),require=1)
    private void darkwinter$blitSkyBeforeTerrain(ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,
            RenderPass pass,CallbackInfo ci) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame!=null)frame.blitSky(pass);
    }

    @WrapOperation(method="executeOit",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/LevelRenderer;executeDepthBoundsCull()V"),require=1)
    private void darkwinter$cloudBoundsBeforeCull(LevelRenderer renderer,Operation<Void> original,
            @Local(ordinal=0) OitRenderPassProvider.Parameters params) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame!=null && optionsRenderState.cloudStatus!=CloudStatus.OFF && ARGB.alpha(levelRenderState.cloudColor)>0)
            frame.blitClouds(OitStage.DEPTH_BOUNDS,params);
        original.call(renderer);
    }

    @WrapOperation(method="executeOit",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/CloudRenderer;renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V"),require=1)
    private void darkwinter$resolvedCloudStages(CloudRenderer renderer,CloudStatus status,OitStage stage,
            GpuTextureView mainDepth,OitRenderPassProvider.Parameters oldCloudParams,Operation<Void> original,
            @Local(ordinal=0) OitRenderPassProvider.Parameters params) {
        VanillaFogRenderer frame=VanillaFogRenderer.current();
        if(frame==null) original.call(renderer,status,stage,mainDepth,oldCloudParams);
        else if(stage!=OitStage.DEPTH_BOUNDS) frame.blitClouds(stage,params);
    }

    @Inject(method="close",at=@At("HEAD"))
    private void darkwinter$closeBackgrounds(CallbackInfo ci) {
        if(darkwinter$vanillaFog!=null) { darkwinter$vanillaFog.close();darkwinter$vanillaFog=null; }
    }
}
