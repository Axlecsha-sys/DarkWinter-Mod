// Specializes only native OIT coefficient initialization and its declared Java rank mirror.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.render.EarlyTransparency;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class BalancedOitLevelRendererMixin {
    @Shadow @Final @Mutable public static int OIT_WAVELET_RANK;

    @ModifyArg(method="<clinit>",at=@At(value="INVOKE",target="Ljava/lang/Math;powExact(II)I"),
            index=1,require=1,allow=1)
    private static int darkwinter$balancedCoefficientExponent(int original) {
        return EarlyTransparency.coefficientExponent(original);
    }

    @Inject(method="<clinit>",at=@At("RETURN"),require=1,allow=1)
    private static void darkwinter$mirrorBalancedRank(CallbackInfo callback) {
        // The shader literal is specialized separately because javac inlines the native rank.
        if(EarlyTransparency.compact())OIT_WAVELET_RANK=1;
    }
}
