// Couples asynchronous native uploads to their original section generation and releases stale keys once.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.SectionMeshPublication;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionPublicationMixin implements SectionMeshPublication.Owner {
    @Shadow @Final private SectionRenderDispatcher this$0;
    @Shadow @Final public AtomicReference<SectionMesh> sectionMesh;
    @Shadow public abstract long getSectionNode();
    @Invoker("releaseSectionMesh") public abstract void darkwinter$releaseNativeMesh(SectionMesh mesh);
    @Unique private volatile long darkwinter$publicationGeneration;
    @Override public long darkwinter$meshGeneration(){return darkwinter$publicationGeneration;}
    @Override public long darkwinter$meshNode(){return getSectionNode();}
    @Override public SectionMesh darkwinter$currentMesh(){return sectionMesh.get();}
    @Override public void darkwinter$lockMeshUploads(){this$0.lock();}
    @Override public void darkwinter$unlockMeshUploads(){this$0.unlock();}

    @Inject(method="reset()V",at=@At("HEAD"),require=1,allow=1)
    private void darkwinter$resetPublication(CallbackInfo ci){darkwinter$publicationGeneration++;}
    @Inject(method="createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at=@At("HEAD"),require=1,allow=1)
    private void darkwinter$advancePublication(RenderSectionRegion region,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> ci){darkwinter$publicationGeneration++;}

    @Inject(method="createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$bindPublication(RenderSectionRegion region,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> ci) {
        ((SectionMeshPublication.Task)ci.getReturnValue()).darkwinter$bindMeshTask(this,getSectionNode(),darkwinter$publicationGeneration);
    }

    @WrapMethod(method="vertexBufferUploadCallback(Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)V")
    private void darkwinter$guardVertexUpload(CompiledSectionMesh mesh,ChunkSectionLayer layer,Operation<Void> original) {
        darkwinter$lockMeshUploads();
        try {if(SectionMeshPublication.acceptCallback(this,mesh,false))original.call(mesh,layer);}
        finally {darkwinter$unlockMeshUploads();}
    }
    @WrapMethod(method="indexBufferUploadCallback(Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Z)V")
    private void darkwinter$guardIndexUpload(CompiledSectionMesh mesh,ChunkSectionLayer layer,boolean sorted,Operation<Void> original) {
        darkwinter$lockMeshUploads();
        try {if(SectionMeshPublication.acceptCallback(this,mesh,sorted))original.call(mesh,layer,sorted);}
        finally {darkwinter$unlockMeshUploads();}
    }
    @WrapOperation(method="checkSectionMesh",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"),require=1,allow=1)
    private SectionMesh darkwinter$publishCurrentUpload(SectionRenderDispatcher.RenderSection section,
            SectionMesh mesh,Operation<SectionMesh> original) {
        var token=SectionMeshPublication.token(mesh);if(token==null)return original.call(section,mesh);
        Object task=token.task();
        if(task!=null)synchronized(task){if(SectionMeshPublication.current(this,token))return original.call(section,mesh);}
        SectionMeshPublication.retireObsolete(this,mesh,token);
        // The native caller releases the return value: never hand it the newer live mesh.
        return CompiledSectionMesh.EMPTY;
    }
    @WrapMethod(method="releaseSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)V")
    private void darkwinter$releaseMeshOnce(SectionMesh mesh,Operation<Void> original) {
        if(SectionMeshPublication.claimRelease(mesh))original.call(mesh);
    }
}
