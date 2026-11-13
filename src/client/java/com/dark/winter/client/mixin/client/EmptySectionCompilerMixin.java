// Avoids the 4096-block meshing traversal for verified empty, non-debug snapshots.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.EmptySectionCompiler;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
abstract class EmptySectionCompilerMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void darkwinter$compileKnownEmpty(SectionPos position, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> result) {
        SectionCompiler.Results empty = EmptySectionCompiler.tryCompile(position, region);
        if (empty != null) result.setReturnValue(empty);
    }
}
