// Registration and item-side dispatch; SurfacePlacement owns authoritative hit and anchor validation.
package com.dark.winter.placement.item;

import com.dark.winter.placement.SurfacePlacement;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class PlacedItems {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("darkwinter", "placed_item");
    public static final PlacedItemBlock BLOCK = Registry.register(BuiltInRegistries.BLOCK,
            ResourceKey.create(Registries.BLOCK, ID),
            new PlacedItemBlock(Block.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ID))
                    .strength(.2f).noCollision().noOcclusion().noLootTable()));
    public static final BlockEntityType<PlacedItemBlockEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            ID, FabricBlockEntityTypeBuilder.create(PlacedItemBlockEntity::new, BLOCK).build());

    private PlacedItems() {}
    public static void init() {}

    public static boolean setStack(Level level, BlockPos pos, ItemStack item) {
        if (!(level.getBlockEntity(pos) instanceof PlacedItemBlockEntity placed)) return false;
        placed.setStack(item);
        return true;
    }

    public static InteractionResult place(UseOnContext context) {
        ItemStack item = context.getItemInHand();
        if (item.isEmpty() || item.getItem() instanceof BlockItem
                || !BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("darkwinter")
                || !SurfacePlacement.canPlaceItem(context)) return InteractionResult.PASS;
        return SurfacePlacement.placeItem(context);
    }
}
