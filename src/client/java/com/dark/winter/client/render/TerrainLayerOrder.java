// Reuses the native terrain-layer order only after draw extraction reaches this bridge.
package com.dark.winter.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Public because injected LevelRenderer bytecode belongs to net.minecraft.client.renderer. */
public final class TerrainLayerOrder {
    /** Returns the private snapshot for the verified read-only terrain extraction loop. */
    public static ChunkSectionLayer[] readOnlyValues() {
        return Snapshot.VALUES;
    }

    private static final class Snapshot {
        private static final ChunkSectionLayer[] VALUES = ChunkSectionLayer.values();
    }

    private TerrainLayerOrder() { }
}
