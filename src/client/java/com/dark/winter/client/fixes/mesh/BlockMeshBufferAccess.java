package com.dark.winter.client.fixes.mesh;

/** Borrowed pointer valid only until the next reserve on this worker-owned BufferBuilder. */
public interface BlockMeshBufferAccess {
    boolean darkwinter$canAppendBlockMesh();
    void darkwinter$setPendingBlockMesh(boolean pending);
    long darkwinter$reserveBlockQuads(int quads);
}
