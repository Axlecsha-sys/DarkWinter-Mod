// Registers the dedicated test-biome ground slab without replacing vanilla grass blocks.
package com.dark.winter.worldgen;

import com.dark.winter.block.TestSnowyGrassSlabBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class TestTerrainBlocks {
    public static final Identifier SLAB_ID = Identifier.fromNamespaceAndPath("darkwinter", "test_snowy_grass_slab");
    public static final Block SNOWY_GRASS_SLAB = registerSlab();
    private TestTerrainBlocks() {}

    private static Block registerSlab() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, SLAB_ID);
        Block block = Registry.register(BuiltInRegistries.BLOCK, key, new TestSnowyGrassSlabBlock(
                BlockBehaviour.Properties.of().mapColor(MapColor.GRASS).strength(.6f).sound(SoundType.GRASS).setId(key)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, SLAB_ID);
        Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey)));
        return block;
    }

    public static void initialize() {
        // Force terrain registration before the DarkWinter creative categories are built.
    }
}
