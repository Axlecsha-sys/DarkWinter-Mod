// Keeps ordinary BlockItem placement atomic while companion pieces are installed.
package com.dark.winter.block;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

/** Keeps ordinary BlockItem placement atomic while companion pieces are installed. */
public final class FurnitureBlockItem extends BlockItem {
    private final FurnitureBlock furniture;

    public FurnitureBlockItem(FurnitureBlock block, Properties properties) {
        super(block, properties);
        furniture = block;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        return furniture.mutate(context.getLevel(), context.getClickedPos(), () -> super.place(context));
    }
}
