// Uploads scene uniforms to postprocessing passes and releases their GPU buffers.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.SceneFrame;
import com.winterkore.engine.graphics.WkeAntialiasControls;
import com.winterkore.engine.graphics.WkeTemporalResolve;
import com.dark.winter.visuals.TemporalResolveHistory;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(PostPass.class)
public abstract class SceneUniformMixin {
    @Shadow @Final private Map<String,GpuBuffer> customUniforms;
    @Unique private MappableRingBuffer darkwinter$sceneUniforms;
    @Unique private boolean darkwinter$usedUniforms;
    @Unique private MappableRingBuffer darkwinter$antialiasUniforms;
    @Unique private boolean darkwinter$usedAntialiasUniforms;
    @Unique private MappableRingBuffer darkwinter$temporalUniforms;
    @Unique private boolean darkwinter$usedTemporalUniforms;

    @Inject(method="addToFrame",at=@At("HEAD"))
    private void darkwinter$uploadScene(CallbackInfo ci) {
        SceneFrame frame=SceneFrame.active;
        if(frame==null || (!customUniforms.containsKey(SceneFrame.BLOCK) && darkwinter$sceneUniforms==null)) return;
        if(darkwinter$sceneUniforms==null) {
            darkwinter$sceneUniforms=new MappableRingBuffer(
                () -> "DarkWinter DarkWinterScene",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,SceneFrame.BYTES);
            GpuBuffer initial=customUniforms.remove(SceneFrame.BLOCK);
            if(initial!=null) initial.close();
        } else if(darkwinter$usedUniforms) {
            darkwinter$sceneUniforms.rotate();
        }
        GpuBuffer buffer=darkwinter$sceneUniforms.currentBuffer();
        try(GpuBufferSlice.MappedView view=buffer.map(false,true)) { frame.write(view.data()); }
        customUniforms.put(SceneFrame.BLOCK,buffer);
        darkwinter$usedUniforms=true;
    }
    /** Independent controls keep the established 416-byte scene block unchanged. */
    @Inject(method="addToFrame",at=@At("HEAD"))
    private void darkwinter$uploadAntialias(CallbackInfo ci) {
        SceneFrame frame=SceneFrame.active;
        if(frame==null || (!customUniforms.containsKey(WkeAntialiasControls.BLOCK)
                && darkwinter$antialiasUniforms==null)) return;
        if(darkwinter$antialiasUniforms==null) {
            darkwinter$antialiasUniforms=new MappableRingBuffer(
                () -> "WKE native edge antialias",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,
                WkeAntialiasControls.BYTES);
            GpuBuffer initial=customUniforms.remove(WkeAntialiasControls.BLOCK);
            if(initial!=null) initial.close();
        } else if(darkwinter$usedAntialiasUniforms) {
            darkwinter$antialiasUniforms.rotate();
        }
        GpuBuffer buffer=darkwinter$antialiasUniforms.currentBuffer();
        boolean enabled=frame.config.antialiasEnabled && frame.config.debug==0
                && !TemporalResolveHistory.temporalReady();
        try(GpuBufferSlice.MappedView view=buffer.map(false,true)) {
            WkeAntialiasControls.write(view.data(),enabled,frame.config.flipDepthY);
        }
        customUniforms.put(WkeAntialiasControls.BLOCK,buffer);
        darkwinter$usedAntialiasUniforms=true;
    }

    @Inject(method="addToFrame",at=@At("HEAD"))
    private void darkwinter$uploadTemporal(CallbackInfo ci) {
        if(SceneFrame.active==null || (!customUniforms.containsKey(WkeTemporalResolve.BLOCK)
                && darkwinter$temporalUniforms==null)) return;
        if(darkwinter$temporalUniforms==null) {
            darkwinter$temporalUniforms=new MappableRingBuffer(
                () -> "WKE temporal resolve",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,
                WkeTemporalResolve.BYTES);
            GpuBuffer initial=customUniforms.remove(WkeTemporalResolve.BLOCK);
            if(initial!=null) initial.close();
        } else if(darkwinter$usedTemporalUniforms) {
            darkwinter$temporalUniforms.rotate();
        }
        GpuBuffer buffer=darkwinter$temporalUniforms.currentBuffer();
        try(GpuBufferSlice.MappedView view=buffer.map(false,true)) {
            TemporalResolveHistory.writeActive(view.data());
        }
        customUniforms.put(WkeTemporalResolve.BLOCK,buffer);
        darkwinter$usedTemporalUniforms=true;
    }

    @Inject(method="close",at=@At("HEAD"))
    private void darkwinter$closeUniforms(CallbackInfo ci) {
        if(darkwinter$temporalUniforms!=null) {
            customUniforms.remove(WkeTemporalResolve.BLOCK);
            darkwinter$temporalUniforms.close(); darkwinter$temporalUniforms=null;
        }
        if(darkwinter$antialiasUniforms!=null) {
            customUniforms.remove(WkeAntialiasControls.BLOCK);
            darkwinter$antialiasUniforms.close(); darkwinter$antialiasUniforms=null;
        }
        if(darkwinter$sceneUniforms!=null) {
            customUniforms.remove(SceneFrame.BLOCK);
            darkwinter$sceneUniforms.close(); darkwinter$sceneUniforms=null;
        }
    }
}
