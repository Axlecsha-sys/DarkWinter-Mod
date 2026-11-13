// Reuses enum order at the read-only terrain draw-group loop without eager renderer initialization.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.render.TerrainLayerOrder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
abstract class TerrainLayerIterationMixin {
    @Redirect(method = "extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"),
            require = 1, allow = 1)
    private ChunkSectionLayer[] darkwinter$readOnlyTerrainLayerOrder() {
        return TerrainLayerOrder.readOnlyValues();
    }
}
