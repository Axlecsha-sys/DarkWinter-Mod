// Keeps orthographic editor culling bounded instead of applying perspective camera-cube retreat.
package com.dark.winter.biomeeditor.mixin;

import com.dark.winter.biomeeditor.BiomeEditorWorker;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public abstract class EditorFrustumMixin {
    @Inject(method="offsetToFullyIncludeCameraCube",at=@At("HEAD"),cancellable=true,require=1)
    private void editor$orthographicCamera(int cubeSize,CallbackInfoReturnable<Frustum> cir){
        if(BiomeEditorWorker.orbitActive())cir.setReturnValue((Frustum)(Object)this);
    }
}
