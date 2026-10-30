// All registered DarkWinter items, refreshed when the creative inventory is built.
package com.dark.winter.registry;

import com.dark.winter.DarkWinter;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.WeightedItem;
import java.util.Comparator;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** All registered DarkWinter items, refreshed when the creative inventory is built. */
public final class ModCreativeTabs {
    public static final ResourceKey<CreativeModeTab> DARKWINTER = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, "darkwinter"));

    public static void initialize() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, DARKWINTER,
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.darkwinter"))
                        .icon(() -> new ItemStack(ModBlocks.WEATHERED_WALL_SEGMENT_GRAY))
                        .displayItems((parameters, output) -> BuiltInRegistries.ITEM.stream()
                                .filter(item -> DarkWinter.MOD_ID.equals(BuiltInRegistries.ITEM.getKey(item).getNamespace()))
                                .sorted(Comparator.comparingInt(ModCreativeTabs::category))
                                .forEach(item -> {
                                    output.accept(item);
                                    if (item instanceof BlockItem blockItem
                                            && blockItem.getBlock() instanceof SnowVariantFurnitureBlock block)
                                        output.accept(SnowVariantFurnitureBlock.stack(block, true));
                                }))
                        .build());
    }

    private static int category(Item item) {
        if (item instanceof BlockItem blockItem) {
            if (blockItem.getBlock() instanceof FurnitureBlock furniture) {
                return switch (furniture.definition().group()) {
                    case "hospital" -> 3;
                    case "industrial" -> 4;
                    case "residential" -> 5;
                    case "street" -> 6;
                    case "road" -> 7;
                    case "outdoor" -> 8;
                    case "nature" -> 9;
                    default -> 10;
                };
            }
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (path.contains("wall_segment")) return 0;
            if (path.contains("floor_segment")) return 1;
            if (path.contains("window")) return 2;
            return 10;
        }
        if (item instanceof GearItem) return 11;
        if (item instanceof WeightedItem) return 12;
        return 13;
    }

    private ModCreativeTabs() {}
}
