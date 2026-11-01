// Prevents cancelled empty rebuilds from replacing or releasing a newer section mesh.
package com.dark.winter.client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CancelledEmptySectionMixin {
    @Unique
    private boolean darkwinter$discardedEmptyResult;

    @WrapMethod(method = "cancel")
    private void darkwinter$serializeCancellation(Operation<Void> original) {
        synchronized (this) {
            original.call();
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
