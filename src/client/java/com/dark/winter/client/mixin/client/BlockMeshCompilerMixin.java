package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.mesh.ChunkMeshRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Intercepts only the two vanilla section output lambdas, including Indigo's output adapter. */
@Mixin(SectionCompiler.class)
abstract class BlockMeshCompilerMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/BufferBuilder;putBlockBakedQuad(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"), require = 2)
    private void darkwinter$batchFinalBlockVertices(BufferBuilder builder, float x, float y, float z,
            BakedQuad quad, QuadInstance instance, Operation<Void> original) {
        if (!ChunkMeshRuntime.enqueue(builder, x, y, z, quad, instance)) {
            original.call(builder, x, y, z, quad, instance);
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"), require = 1)
    private void darkwinter$flushBeforeFluid(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos,
            FluidRenderer.Output output, BlockState state, FluidState fluid, Operation<Void> original) {
        ChunkMeshRuntime.flushAll();
        original.call(renderer, level, pos, output, state, fluid);
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;"), require = 1)
    private MeshData darkwinter$flushBeforeBuild(BufferBuilder builder, Operation<MeshData> original) {
        ChunkMeshRuntime.flush(builder);
        return original.call(builder);
    }
}
