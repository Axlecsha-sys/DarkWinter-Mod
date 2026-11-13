// Specializes the single named OIT rank define while preserving other pipeline definitions.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.render.EarlyTransparency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(targets="net.minecraft.client.renderer.RenderPipelines")
abstract class BalancedOitPipelinesMixin {
    @ModifyArgs(method="<clinit>",
            at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withShaderDefine(Ljava/lang/String;I)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;"),
            slice=@Slice(from=@At(value="CONSTANT",args="stringValue=OIT_WAVELET_RANK"),
                    to=@At(value="CONSTANT",args="stringValue=OIT_COEFF_COUNT")),
            require=1,allow=1)
    private static void darkwinter$balancedNamedRank(Args args) {
        if(!"OIT_WAVELET_RANK".equals(args.get(0)))throw new IllegalStateException("Unexpected OIT rank call slice");
        int original=args.get(1);
        args.set(1,EarlyTransparency.shaderRank(original));
    }
}
