// Spreads prioritized section rebuild bursts across the native worker queue instead of one render frame.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.SectionCompileBudget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererSectionBudgetMixin {
    @Unique
    private final SectionCompileBudget darkwinter$sectionCompileBudget = new SectionCompileBudget();

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void darkwinter$startSectionBudget(CameraRenderState camera, CallbackInfo ci) {
        darkwinter$sectionCompileBudget.startFrame();
    }

    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"))
    private void darkwinter$budgetSectionCompile(SectionRenderDispatcher.RenderSection section,
            RenderSectionRegion region, Operation<Void> original, @Local SectionUpdateRenderState state) {
        if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && !state.playerChanged()) {
            // Initial terrain has no old mesh to update; keep its indivisible first build off this frame.
            section.compileAsync(region);
        } else if (darkwinter$sectionCompileBudget.allowSynchronousCompile()) {
            original.call(section, region);
        } else {
            // Reuse the extracted snapshot and native cancellation, upload and visibility lifecycle.
            section.compileAsync(region);
        }
    }
}
