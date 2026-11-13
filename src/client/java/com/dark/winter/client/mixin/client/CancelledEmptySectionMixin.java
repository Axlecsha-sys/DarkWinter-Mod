// Releases completed section snapshots and protects cancelled section-mesh publication.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.SectionMeshPublication;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CancelledEmptySectionMixin implements SectionMeshPublication.Task {
    @Shadow @Final @Mutable private RenderSectionRegion region;
    @Unique private boolean darkwinter$compiling;
    @Unique private SectionMeshPublication.Token darkwinter$meshToken;
    @Override public void darkwinter$bindMeshTask(SectionMeshPublication.Owner owner,long node,long generation) {
        darkwinter$meshToken=new SectionMeshPublication.Token(owner,this,
                ((SectionTaskCancellationAccess)this).darkwinter$cancellationFlag(),node,generation);
    }
    @Override public SectionMeshPublication.Token darkwinter$meshTaskToken(){return darkwinter$meshToken;}

    @ModifyExpressionValue(method="doTask",at=@At(value="NEW",target="net/minecraft/client/renderer/chunk/CompiledSectionMesh"),require=1,allow=1)
    private CompiledSectionMesh darkwinter$tagUploadResult(CompiledSectionMesh mesh) {
        SectionMeshPublication.bind(mesh,darkwinter$meshToken);return mesh;
    }

    @Unique
    private boolean darkwinter$discardedEmptyResult;

    @WrapMethod(method = "doTask")
    private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult darkwinter$releaseSnapshot(
            SectionBufferBuilderPack buffers,
            Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original) {
        synchronized (this) {
            darkwinter$compiling = true;
        }
        boolean retryableFailure = false;
        try {
            return original.call(buffers);
        } catch (NullPointerException failure) {
            // The native executor requeues NPE failures before marking the task completed.
            retryableFailure = true;
            throw failure;
        } finally {
            synchronized (this) {
                darkwinter$compiling = false;
                if (!retryableFailure || ((SectionTaskCancellationAccess) this).darkwinter$cancellationFlag().get()) {
                    region = null;
                }
            }
        }
    }

    @WrapMethod(method = "cancel")
    private void darkwinter$serializeCancellation(Operation<Void> original) {
        synchronized (this) {
            original.call();
            if (!darkwinter$compiling) {
                region = null;
            }
        }
    }

    @WrapOperation(method = "doTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh darkwinter$publishCurrentEmptyResult(SectionRenderDispatcher.RenderSection section,
            SectionMesh mesh, Operation<SectionMesh> original) {
        // Cancel and publication share this task monitor; no meshing, staging or GPU wait holds it.
        synchronized (this) {
            if (((SectionTaskCancellationAccess) this).darkwinter$cancellationFlag().get()) {
                darkwinter$discardedEmptyResult = true;
                // The caller releases this return value. Never give it the newer live mesh.
                return CompiledSectionMesh.EMPTY;
            }
            return original.call(section, mesh);
        }
    }

    @Inject(method = "doTask", at = @At("RETURN"), cancellable = true)
    private void darkwinter$reportDiscardedResult(CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> result) {
        if (darkwinter$discardedEmptyResult) {
            result.setReturnValue(SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED);
        }
    }
}
