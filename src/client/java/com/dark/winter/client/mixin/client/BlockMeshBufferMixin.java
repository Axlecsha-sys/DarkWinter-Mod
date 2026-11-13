package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.mesh.BlockMeshBufferAccess;
import com.dark.winter.client.fixes.mesh.BlockMeshPacking;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Bounded append only. Never changes a format, GPU state, pending result or existing vertices. */
@Mixin(BufferBuilder.class)
abstract class BlockMeshBufferMixin implements BlockMeshBufferAccess {
    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow @Final private boolean blockFormat;
    @Shadow @Final private int vertexSize;
    @Shadow @Final private PrimitiveTopology primitiveTopology;
    @Shadow private boolean building;
    @Shadow private int vertices;
    @Shadow private int elementsToFill;
    @Shadow private long vertexPointer;
    @Unique private boolean darkwinter$pendingBlockMesh;

    @Override @Unique
    public void darkwinter$setPendingBlockMesh(boolean pending) { darkwinter$pendingBlockMesh = pending; }

    @org.spongepowered.asm.mixin.injection.Inject(method = "beginVertex",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void darkwinter$preserveMixedProducerOrder(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Long> callback) {
        if (darkwinter$pendingBlockMesh) {
            com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.flush((BufferBuilder) (Object) this);
        }
    }

    @Override @Unique
    public boolean darkwinter$canAppendBlockMesh() {
        return building && blockFormat && vertexSize == BlockMeshPacking.VERTEX_BYTES
                && primitiveTopology == PrimitiveTopology.QUADS && elementsToFill == 0
                && (vertices & 3) == 0;
    }

    @Override @Unique
    public long darkwinter$reserveBlockQuads(int quads) {
        if (!darkwinter$canAppendBlockMesh() || quads <= 0 || quads > BlockMeshPacking.MAX_QUADS
                || vertices > 16777215 - quads * 4) {
            throw new IllegalStateException("Unsafe BLOCK quad append");
        }
        int bytes = quads * BlockMeshPacking.QUAD_BYTES;
        // Reserve may reallocate. Obtain the destination only AFTER reserve returns.
        long pointer = buffer.reserve(bytes);
        vertices += quads * 4;
        vertexPointer = pointer + bytes - vertexSize;
        elementsToFill = 0;
        return pointer;
    }
}
