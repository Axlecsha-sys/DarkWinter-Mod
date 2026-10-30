// Uploads scene uniforms to postprocessing passes and releases their GPU buffers.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.SceneFrame;
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
    @Inject(method="close",at=@At("HEAD"))
    private void darkwinter$closeUniforms(CallbackInfo ci) {
        if(darkwinter$sceneUniforms!=null) {
            customUniforms.remove(SceneFrame.BLOCK);
            darkwinter$sceneUniforms.close(); darkwinter$sceneUniforms=null;
        }
    }
}
