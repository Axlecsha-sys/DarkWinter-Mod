// Exposes compiled chunk render sections for shadow capture.
package com.dark.winter.visuals;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
/** Shadow preparation reads compiled chunk sections, not world block data. */
public interface SceneAccess {
    ChunkSectionsToRender darkwinter$prepareShadowTerrain(Matrix4fc lightView, Matrix4f lightClip);
}
