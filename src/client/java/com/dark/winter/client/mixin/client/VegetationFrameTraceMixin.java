// Debug-only frame observer. The disabled path only delegates to the original render.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.diagnostics.vegetation.VegetationTrace;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class VegetationFrameTraceMixin {
    @WrapMethod(method="render")
    private void darkwinter$traceVegetationRender(Operation<Void> original) {
        if(!VegetationTrace.enabled()) { original.call();return; }
        VegetationTrace.renderBegin();
        try { original.call(); }
        finally { VegetationTrace.renderEnd(); }
    }
    @Inject(method="renderLevel()V",at=@At("HEAD"),require=1,allow=1)
    private void darkwinter$traceVegetationWorld(CallbackInfo ci) {
        if(VegetationTrace.enabled())VegetationTrace.worldBegin((GameRenderer)(Object)this);
    }
}
