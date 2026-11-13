// All world graphics callers share this precompile selector; GUI outside world scopes stays host-selected.
package com.winterkore.engine.render.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.winterkore.engine.render.WkeRendererRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = RenderSystem.class, remap = false)
public abstract class WkeWorldPipelineMixin {
    @ModifyVariable(method = "getCompiledPipelineNullable", at = @At("HEAD"),
        argsOnly = true, ordinal = 0, require = 1, allow = 1)
    private static RenderPipeline wke$selectWorldPipeline(RenderPipeline source) {
        return WkeRendererRuntime.pipeline(source);
    }
}
