// Reuse the native item model resolver instead of introducing a second item asset path.
package com.dark.winter.client.placement;

import com.dark.winter.placement.item.PlacedItems;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class PlacedItemsClient {
    private PlacedItemsClient() {}
    public static void init() { BlockEntityRenderers.register(PlacedItems.TYPE, PlacedItemRenderer::new); }
}
