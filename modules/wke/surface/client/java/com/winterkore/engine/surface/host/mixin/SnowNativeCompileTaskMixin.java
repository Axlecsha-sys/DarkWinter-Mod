// The shared EMPTY mesh has no per-result token; scope only its actual native setter invocation.
package com.winterkore.engine.surface.host.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.dark.winter.client.mixin.client.SectionTaskCancellationAccess;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.winterkore.engine.surface.client.SnowNativeSectionEpoch;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class SnowNativeCompileTaskMixin implements SnowNativeSectionEpoch.Task {
    @Unique private volatile long wke$snowOwnershipEpoch=-1;
    @Override public void wke$bindSnowEpoch(long epoch){wke$snowOwnershipEpoch=epoch;}
    @Unique private volatile boolean wke$snowTaskRunning;
    @Override public long wke$snowEpoch(){return wke$snowOwnershipEpoch;}
    @Override public boolean wke$snowTaskRunning(){return wke$snowTaskRunning;}
    @Override public boolean wke$snowTaskCancelled(){return ((SectionTaskCancellationAccess)this).darkwinter$cancellationFlag().get();}
    @WrapMethod(method="doTask")
    private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult wke$trackNativeWork(SectionBufferBuilderPack buffers,
            Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original){
        wke$snowTaskRunning=true;
        try{return original.call(buffers);}
        finally{wke$snowTaskRunning=false;}
    }
    @WrapOperation(method="doTask",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"),require=1,allow=1)
    private SectionMesh wke$scopeEmptySnowOwnership(SectionRenderDispatcher.RenderSection section,SectionMesh mesh,Operation<SectionMesh> original){
        Long prior=SnowNativeSectionEpoch.push(wke$snowOwnershipEpoch);
        try{return original.call(section,mesh);}
        finally{SnowNativeSectionEpoch.restore(prior);}
    }
}
