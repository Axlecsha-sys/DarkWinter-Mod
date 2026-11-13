// Observes real compile requests, installed meshes and released allocations without changing publication guards.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.diagnostics.vegetation.VegetationTrace;
import com.dark.winter.client.fixes.performance.SectionMeshPublication;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class VegetationSectionTraceMixin {
    @Shadow public abstract long getSectionNode();
    private void darkwinter$traceSection(int type,SectionMesh mesh) {
        if(VegetationTrace.enabled())VegetationTrace.section(type,getSectionNode(),
                ((SectionMeshPublication.Owner)this).darkwinter$meshGeneration(),mesh,0);
    }
    @Inject(method="createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$traceCompile(RenderSectionRegion region,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> ci) {
        darkwinter$traceSection(VegetationTrace.SECTION_COMPILE,null);
    }
    @Inject(method="reset()V",at=@At("HEAD"),require=1,allow=1)
    private void darkwinter$traceReset(CallbackInfo ci) { darkwinter$traceSection(VegetationTrace.SECTION_RESET,null); }
    @Inject(method="setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
            at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$tracePublication(SectionMesh mesh,CallbackInfoReturnable<SectionMesh> ci) {
        darkwinter$traceSection(VegetationTrace.SECTION_PUBLISH,mesh);
    }
    @Inject(method="releaseSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)V",
            at=@At("HEAD"),require=1,allow=1)
    private void darkwinter$traceRelease(SectionMesh mesh,CallbackInfo ci) {
        darkwinter$traceSection(VegetationTrace.SECTION_RELEASE,mesh);
    }
}
