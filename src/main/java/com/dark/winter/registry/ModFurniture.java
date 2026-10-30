// Registers every decorative catalog entry before the Minecraft registries freeze.
package com.dark.winter.registry;

import com.dark.winter.DarkWinter;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.FurnitureBlockItem;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.block.FurnitureCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import org.jspecify.annotations.Nullable;

/** Registers every decorative catalog entry before the Minecraft registries freeze. */
public final class ModFurniture {
    private static final Map<String, FurnitureBlock> BLOCKS = new LinkedHashMap<>();
    private static FurnitureCatalog catalog;

    public static void initialize() {
        if (catalog != null) return;
        catalog = FurnitureCatalog.load();
        for (FurnitureCatalog.Entry entry : catalog.entries()) {
            Identifier id = Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, entry.id());
            ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
            FurnitureBlock block = entry.snowVariants()
                    ? new SnowVariantFurnitureBlock(entry, properties(entry.material()).setId(blockKey))
                    : new FurnitureBlock(entry, properties(entry.material()).setId(blockKey));
            Registry.register(BuiltInRegistries.BLOCK, blockKey, block);
            Registry.register(BuiltInRegistries.ITEM, itemKey, new FurnitureBlockItem(block,
                    new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
            BLOCKS.put(entry.id(), block);
        }
        DarkWinter.LOGGER.info("Registered {} decorative furniture blocks", BLOCKS.size());
    }

    private static BlockBehaviour.Properties properties(String material) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().noOcclusion()
                .pushReaction(PushReaction.IMMOVEABLE).noLootTable();
        return switch (material) {
            case "metal" -> properties.strength(2.5F, 5.0F).sound(SoundType.METAL);
            case "stone" -> properties.strength(2.0F, 6.0F).sound(SoundType.STONE);
            case "road" -> properties.strength(2.5F, 6.0F).sound(SoundType.STONE);
            case "earth" -> properties.strength(0.6F, 1.0F).sound(SoundType.GRAVEL);
            case "plant" -> properties.strength(0.2F, 0.2F).sound(SoundType.GRASS).ignitedByLava();
            case "glass" -> properties.strength(1.0F, 2.0F).sound(SoundType.GLASS);
            case "fabric" -> properties.strength(0.8F, 1.0F).sound(SoundType.WOOL).ignitedByLava();
            default -> properties.strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava();
        };
    }

    public static List<FurnitureBlock> all() { return List.copyOf(BLOCKS.values()); }
    public static Map<String, FurnitureBlock> byId() { return Collections.unmodifiableMap(BLOCKS); }
    public static @Nullable FurnitureBlock get(String id) { return BLOCKS.get(id); }
    public static FurnitureCatalog catalog() {
        if (catalog == null) throw new IllegalStateException("Furniture is not initialized");
        return catalog;
    }
    public static boolean isFurniture(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof FurnitureBlock;
    }

    private ModFurniture() {}
}
