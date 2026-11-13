// Matches WKE temporal accumulation's real attachment format before the host compiles its post pipeline.
package com.dark.winter.visuals.mixin;

import com.dark.winter.visuals.TemporalResolveHistory;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PostChain.class)
public abstract class TemporalPostFormatMixin {
    @Redirect(method="createPass",at=@At(value="INVOKE",
            target="Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withColorTargetState(Lcom/mojang/renderpearl/api/pipeline/ColorTargetState;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;"),
            require=1)
    private static RenderPipeline.Builder darkwinter$temporalFloatTarget(
            RenderPipeline.Builder builder,ColorTargetState original,
            TextureManager textures,PostChainConfig.Pass config,Identifier passId) {
        // External targets are supplied only during addToFrame. The host otherwise
        // compiles every post pass with RGBA8 before it sees the actual attachment.
        if("wke:post/temporal_resolve".equals(config.fragmentShaderId().toString())
                &&"wke:temporal_current".equals(config.outputTarget().toString())
                &&"minecraft:core/screenquad".equals(config.vertexShaderId().toString())) {
            return builder.withColorTargetState(new ColorTargetState(original.blendFunction(),
                    TemporalResolveHistory.COLOR_FORMAT,original.writeMask()));
        }
        return builder.withColorTargetState(original);
    }
}
