package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.mesh.ChunkMeshRuntime;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terminal Fabric/Indigo quad output, after all model transforms, tint and lighting.
 * No mutable QuadView or data[] reference is retained: the used 32 words are copied immediately.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.mesh.QuadViewImpl", remap = false)
abstract class BlockMeshIndigoMixin {
    @Shadow protected int[] data;
    @Shadow protected int baseIndex;

    @Inject(method = "buffer(ILcom/mojang/blaze3d/vertex/VertexConsumer;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void darkwinter$packFinalIndigoQuad(int overlay, VertexConsumer consumer, CallbackInfo callback) {
        if (consumer instanceof BufferBuilder builder && EncodingFormat.HEADER_STRIDE == 4
                && EncodingFormat.VERTEX_STRIDE == 8 && EncodingFormat.QUAD_STRIDE == 32) {
            QuadView quad = (QuadView) (Object) this;
            if (ChunkMeshRuntime.enqueueEncoded(builder, data, baseIndex + 4, ChunkMeshRuntime.supportsLayer(quad.chunkLayer()))) {
                callback.cancel();
            }
        }
    }
}
