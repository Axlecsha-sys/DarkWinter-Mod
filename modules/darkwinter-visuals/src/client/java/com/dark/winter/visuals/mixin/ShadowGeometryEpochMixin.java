// Invalidates retained shadow depth when native section geometry is published, removed or relocated.
package com.dark.winter.visuals.mixin;
import com.dark.winter.visuals.ShadowCaptureCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class ShadowGeometryEpochMixin {
    @Inject(method="setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
            at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$meshPublished(SectionMesh mesh,CallbackInfoReturnable<SectionMesh> ci) {
        ShadowCaptureCache.terrainChanged();
    }
    @Inject(method="reset()V",at=@At("RETURN"),require=1,allow=1)
    private void darkwinter$meshRemoved(CallbackInfo ci) {
        ShadowCaptureCache.terrainChanged();
    }
}
