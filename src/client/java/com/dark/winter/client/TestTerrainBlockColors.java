// Reuses native grass tint sources for the optional bare state of the test ground slab.
package com.dark.winter.client;

import com.dark.winter.worldgen.TestTerrainBlocks;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.color.block.BlockTintSources;

public final class TestTerrainBlockColors {
    private TestTerrainBlockColors() {}

    public static void init() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                client.getBlockColors().register(List.of(BlockTintSources.grassBlock()), TestTerrainBlocks.SNOWY_GRASS_SLAB));
    }
}
