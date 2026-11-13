// Acknowledge only a real committed native mesh built after snow ownership changed.
package com.winterkore.engine.surface.host.mixin;

import com.dark.winter.client.fixes.performance.SectionMeshPublication;
import com.winterkore.engine.surface.client.SnowNativeSectionEpoch;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class SnowNativeSectionMixin implements SnowNativeSectionEpoch.Owner {
    @Unique private volatile SnowNativeSectionEpoch.Task wke$lastSnowTask;
    @Override public SnowNativeSectionEpoch.Task wke$lastSnowTask(){return wke$lastSnowTask;}
    @Shadow public abstract long getSectionNode();
    @Inject(method="createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at=@At("RETURN"),require=1,allow=1)
    private void wke$bindSnowOwnership(RenderSectionRegion region,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> ci){
        if(ci.getReturnValue() instanceof SnowNativeSectionEpoch.Task task){
            task.wke$bindSnowEpoch(SnowSurfaceClient.ownershipEpoch());wke$lastSnowTask=task;
        }
    }
    @Inject(method="setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
            at=@At("RETURN"),require=1,allow=1)
    private void wke$acknowledgeSnowOwnership(SectionMesh mesh,CallbackInfoReturnable<SectionMesh> ci){
        long epoch=SnowNativeSectionEpoch.current();
        if(epoch<0){
            var token=SectionMeshPublication.token(mesh);
            if(token!=null&&token.task() instanceof SnowNativeSectionEpoch.Task task)epoch=task.wke$snowEpoch();
        }
        if(epoch>=0){
            long node=getSectionNode();
            SnowSurfaceClient.nativeSectionPublished(SectionPos.x(node),SectionPos.y(node),SectionPos.z(node),epoch);
        }
    }
}
