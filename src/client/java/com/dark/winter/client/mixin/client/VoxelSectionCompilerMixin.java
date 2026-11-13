// Creates one optional mask per real compiled section, always releasing per-thread context.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.voxel.VoxelFaceRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionCompiler.class)
abstract class VoxelSectionCompilerMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results darkwinter$applyOptionalVoxelMask(
            SectionPos section, RenderSectionRegion region, VertexSorting sorting,
            SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
        boolean dedupComparisonExcluded = com.dark.winter.client.fixes.mesh.ChunkMeshComparison.running()
                || com.dark.winter.client.fixes.mesh.ChunkMeshComparison.replaying();
        if (!com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.needsScope()
                && VoxelFaceRuntime.requested() == VoxelFaceRuntime.Mode.JAVA) {
            SectionCompiler.Results nativeResult = original.call(section, region, sorting, builders);
            return dedupComparisonExcluded ? nativeResult
                    : com.dark.winter.client.fixes.mesh.SolidVertexDeduplicator.apply(nativeResult, builders);
        }
        SectionCompiler.Results result;
        int eligible;
        java.util.Map<String, Long> measured = null;
        boolean replay = com.dark.winter.client.fixes.mesh.ChunkMeshComparison.replaying();
        try (VoxelFaceRuntime.Scope context = VoxelFaceRuntime.begin(section, region);
             var meshScope = com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.begin()) {
            result = original.call(section, region, sorting, builders);
            meshScope.finish();
            eligible = meshScope.eligibleQuads();
            if (replay || (meshScope.mode() == com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.Mode.VANILLA
                    && com.dark.winter.client.fixes.mesh.ChunkMeshComparison.running())) {
                measured = meshScope.measurements();
            }
        }
        if (measured != null) {
            // onCompiled may finish the final reference and clear running before this method returns.
            dedupComparisonExcluded = true;
            com.dark.winter.client.fixes.mesh.ChunkMeshComparison.onCompiled(
                    (SectionCompiler) (Object) this, section, region, sorting, result, eligible, measured);
        }
        return dedupComparisonExcluded ? result
                : com.dark.winter.client.fixes.mesh.SolidVertexDeduplicator.apply(result, builders);
    }
}
