// Spreads fresh snapshots across frames, leaving deferred native dirty flags and payloads untouched.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.SectionSnapshotBudget;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorSnapshotBudgetMixin {
    @Shadow private ClientLevel level;
    @Shadow private SectionUpdateTracker sectionUpdateTracker;
    @Unique private final SectionSnapshotBudget darkwinter$snapshotBudget = new SectionSnapshotBudget();
    @Unique private final ObjectArrayList<SectionRenderDispatcher.RenderSection> darkwinter$rotatingSections = new ObjectArrayList<>();

    @Inject(method = "extract", at = @At("HEAD"))
    private void darkwinter$startSnapshots(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo ci) {
        darkwinter$snapshotBudget.startFrame();
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> darkwinter$rotateSnapshots(
            LevelRenderer renderer, Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original) {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = original.call(renderer);
        darkwinter$rotatingSections.clear();
        int size = visible.size();
        int start = darkwinter$snapshotBudget.beginVisibleSections(size);
        for (int index = 0; index < size; index++) {
            darkwinter$rotatingSections.add(visible.get((start + index) % size));
        }
        return darkwinter$rotatingSections;
    }

    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/SectionUpdateTracker;getDirtyState(J)Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;"))
    private SectionUpdateTracker.SectionDirtyState darkwinter$visitSnapshot(SectionUpdateTracker.SectionDirtyState original) {
        darkwinter$snapshotBudget.visitSection();
        return original;
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;isDirty()Z"))
    private boolean darkwinter$admitSnapshot(SectionUpdateTracker.SectionDirtyState state, Operation<Boolean> original,
            @Local SectionRenderDispatcher.RenderSection section) {
        if (!original.call(state)) return false;
        // Do not charge a slot for a region rejected by the native neighbor rule.
        if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED
                && !sectionUpdateTracker.hasAllNeighbors(level, section.getSectionNode())) return true;
        return darkwinter$snapshotBudget.allowSnapshot(state.isDirtyFromPlayer());
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void darkwinter$releaseSnapshotReferences(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo ci) {
        darkwinter$rotatingSections.clear();
    }
}
