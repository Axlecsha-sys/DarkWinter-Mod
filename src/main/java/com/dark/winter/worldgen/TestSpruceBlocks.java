// Registers the dedicated test-spruce trunk and bough blocks.
package com.dark.winter.worldgen;

import java.util.function.Function;
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

public final class TestSpruceBlocks {
    public static final Block LOG = register("test_spruce_log", TestSpruceLogBlock::new,
            BlockBehaviour.Properties.of().strength(2f).sound(SoundType.WOOD).ignitedByLava().noOcclusion());
    public static final Block BOUGH = register("test_spruce_bough", TestSpruceBoughBlock::new,
            BlockBehaviour.Properties.of().strength(.2f).sound(SoundType.GRASS).ignitedByLava().noCollision().noOcclusion());
    private TestSpruceBlocks() {}
    public static void initialize() { /* force registry initialization before dynamic worldgen loading */ }
    private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
        Identifier id = Identifier.fromNamespaceAndPath("darkwinter", name);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
        Registry.register(BuiltInRegistries.ITEM, ResourceKey.create(Registries.ITEM, id),
                new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(ResourceKey.create(Registries.ITEM, id))));
        return block;
    }
}
