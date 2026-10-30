// Preserves ordinary-world star rendering and the clean laboratory sky treatment.
package com.dark.winter.visuals.mixin;

import com.dark.winter.client.TestingAccess;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve the established star treatment in ordinary worlds; the clean lab uses vanilla geometry. */
@Mixin(SkyRenderer.class)
public abstract class SmallStarsMixin {
    @Shadow @Final @Mutable private GpuBuffer starBuffer;
    @Shadow private GpuBuffer buildStars() { throw new AssertionError(); }
    @Unique private boolean darkwinter$builtSmallStars;

    @Inject(method="buildStars",at=@At("HEAD"))
    private void darkwinter$rememberStarPolicy(CallbackInfoReturnable<GpuBuffer> cir) {
        darkwinter$builtSmallStars=TestingAccess.enabled("visuals");
    }
    @Inject(method="render",at=@At("HEAD"))
    private void darkwinter$refreshStarPolicy(CallbackInfo ci) {
        if(starBuffer!=null && darkwinter$builtSmallStars!=TestingAccess.enabled("visuals")) {
            // SkyRenderer.render executes on the render thread. Rebuild only on a policy transition,
            // using Minecraft's own builder; no worker pool or base pipeline changes are made.
            GpuBuffer previous=starBuffer;
            GpuBuffer replacement=buildStars();
            starBuffer=replacement;
            previous.close();
        }
    }
    // Literals and the native builder were verified in the actual 26.3 client jar.
    @ModifyConstant(method="buildStars",constant=@Constant(floatValue=.15F),require=0)
    private float darkwinter$starMinimumRadius(float original) {
        return TestingAccess.enabled("visuals")?.054F:original;
    }
    @ModifyConstant(method="buildStars",constant=@Constant(floatValue=.1F),require=0)
    private float darkwinter$starRandomRadius(float original) {
        return TestingAccess.enabled("visuals")?.030F:original;
    }
    @ModifyArg(method="renderSunMoonAndStars",at=@At(value="INVOKE",target=
            "Lnet/minecraft/client/renderer/SkyRenderer;renderStars(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V"),index=1,require=0)
    private float darkwinter$reduceStarGlare(float brightness) {
        return TestingAccess.enabled("visuals")?brightness*.48F:brightness;
    }
}
