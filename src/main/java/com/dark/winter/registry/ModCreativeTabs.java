// Own creative categories, built from the registered DarkWinter items.
package com.dark.winter.registry;

import com.dark.winter.DarkWinter;
import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.FrozenWindowBlock;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.PotFillFurnitureBlock;
import com.dark.winter.block.SnowVariantFurnitureBlock;
import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.GearSlot;
import com.dark.winter.equipment.WeightedItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

/** One native creative tab per category; the client chooses which page to show. */
public final class ModCreativeTabs {
    public enum Category {
        CLOTHING("clothing", "darkwinter", "winter_parka"),
        CONSTRUCTION("construction", "construction", "wall_segment"),
        ROADS("roads", "roads", "asphalt_white_line"),
        FURNITURE("furniture", "furniture", "wooden_chair"),
        MEDICAL("medical", "medical", "medical_cabinet"),
        WORKSHOP("workshop", "workshop", "metal_toolbox"),
        FOOD_CAMP("food_camp", "food_camp", "hiking_backpack"),
        STREET("street", "street", "street_lamp"),
        NATURE("nature", "nature", "test_spruce_bough"),
        WEIGHTS("weights", "weights", "weight_5kg");

        private final String id;
        private final ResourceKey<CreativeModeTab> key;
        private final Identifier iconId;

        Category(String id, String registryPath, String iconPath) {
            this.id = id;
            this.key = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, registryPath));
            this.iconId = Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, iconPath);
        }

        public String id() { return id; }
        public ResourceKey<CreativeModeTab> key() { return key; }
        public String translationKey() { return "itemGroup.darkwinter." + id; }
        public Identifier iconId() { return iconId; }
    }

    /** Retains the original tab registry key as the clothing entry point. */
    public static final ResourceKey<CreativeModeTab> DARKWINTER = Category.CLOTHING.key();
    private static final List<Category> CATEGORIES = List.of(Category.values());
    private static final List<ResourceKey<CreativeModeTab>> KEYS =
            Arrays.stream(Category.values()).map(Category::key).toList();
    private static List<CreativeModeTab> tabs = List.of();
    private static Map<Category, List<Item>> itemsByCategory = Map.of();

    public static List<CreativeModeTab> tabs() { return tabs; }
    public static List<ResourceKey<CreativeModeTab>> keys() { return KEYS; }
    public static boolean isDarkWinterTab(CreativeModeTab tab) { return tabs.contains(tab); }
    public static CreativeModeTab firstTab() { return tabs.getFirst(); }

    public static void initialize() {
        if (!tabs.isEmpty()) return;
        // All common item registrations precede this initializer. Snapshot item
        // references once; every creative rebuild still creates fresh variant stacks.
        EnumMap<Category, List<Item>> buckets = new EnumMap<>(Category.class);
        for (Category category : CATEGORIES) buckets.put(category, new ArrayList<>());
        for (Item item : BuiltInRegistries.ITEM) {
            if (DarkWinter.MOD_ID.equals(BuiltInRegistries.ITEM.getKey(item).getNamespace()))
                buckets.get(category(item)).add(item);
        }
        EnumMap<Category, List<Item>> immutableBuckets = new EnumMap<>(Category.class);
        for (Category category : CATEGORIES) {
            List<Item> items = buckets.get(category);
            items.sort(Comparator.comparingInt(ModCreativeTabs::itemOrder));
            immutableBuckets.put(category, List.copyOf(items));
        }
        itemsByCategory = Map.copyOf(immutableBuckets);
        List<CreativeModeTab> registered = new ArrayList<>(CATEGORIES.size());
        for (Category category : CATEGORIES) {
            List<Item> items = itemsByCategory.get(category);
            CreativeModeTab tab = FabricCreativeModeTab.builder()
                    .title(Component.translatable(category.translationKey()))
                    .icon(() -> new ItemStack(BuiltInRegistries.ITEM.getValue(category.iconId())))
                    .displayItems((parameters, output) -> {
                        for (Item item : items) {
                            output.accept(item);
                            if (item instanceof BlockItem blockItem
                                    && blockItem.getBlock() instanceof SnowVariantFurnitureBlock block)
                                output.accept(SnowVariantFurnitureBlock.stack(block, true));
                            if (item instanceof BlockItem blockItem
                                    && blockItem.getBlock() instanceof PotFillFurnitureBlock block) {
                                output.accept(PotFillFurnitureBlock.stack(block, PotFillFurnitureBlock.Fill.SNOW));
                                output.accept(PotFillFurnitureBlock.stack(block, PotFillFurnitureBlock.Fill.WATER));
                            }
                        }
                    }).build();
            registered.add(Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, category.key(), tab));
        }
        tabs = List.copyOf(registered);
        DarkWinter.LOGGER.info("DarkWinter creative categories: {}",
                CATEGORIES.stream().map(category -> category.id() + "="
                        + itemsByCategory.get(category).size()).toList());
    }

    private static Category category(Item item) {
        if (ModProvisionItems.contains(item)) return Category.FOOD_CAMP;
        if (item instanceof GearItem gear)
            return gear.slot() == GearSlot.BACKPACK ? Category.FOOD_CAMP : Category.CLOTHING;
        if (item instanceof WeightedItem) return Category.WEIGHTS;
        if (item instanceof BlockItem blockItem) {
            if (blockItem.getBlock() instanceof FurnitureBlock furniture)
                return furnitureCategory(furniture.definition().id(), furniture.definition().group());
            if (blockItem.getBlock() instanceof WallSegmentBlock
                    || blockItem.getBlock() instanceof FloorSegmentBlock
                    || blockItem.getBlock() instanceof FrozenWindowBlock)
                return Category.CONSTRUCTION;
            // Dedicated spruce and terrain blocks belong beside the forest catalog.
            return Category.NATURE;
        }
        // Future miscellaneous DarkWinter items stay visible until given a category.
        return Category.WORKSHOP;
    }

    private static Category furnitureCategory(String id, String group) {
        if (id.startsWith("interior_")) return Category.FURNITURE;
        if (id.startsWith("picket_") || id.startsWith("horizontal_") || id.startsWith("wire_mesh_"))
            return Category.ROADS;
        return switch (id) {
            case "timber_boardwalk", "timber_boardwalk_slab", "timber_boardwalk_raised", "concrete_steps",
                    "cabin_roof_chimney" ->
                    Category.CONSTRUCTION;
            case "gravel_surface", "paving_slab_block", "road_barrier",
                    "fallen_road_barrier", "concrete_bollard" -> Category.ROADS;
            case "worn_sofa", "wooden_chair", "dining_table", "writing_desk",
                    "low_cabinet", "wooden_wardrobe" -> Category.FURNITURE;
            case "kitchen_sink", "old_fridge", "old_tv", "radiator", "kitchen_stove",
                    "fuel_jerrycan", "metal_bucket", "oval_bucket", "metal_toolbox",
                    "carpenter_tool_tote", "folded_newspaper", "tied_newspapers",
                    "rolled_newspapers", "cardboard_boxes", "litter_bag" -> Category.WORKSHOP;
            case "canned_meat", "canned_fish", "canned_beans", "oat_carton", "cracker_box",
                    "ration_bundle", "water_bottle", "canvas_canteen", "vacuum_flask",
                    "matchbox", "kerosene_lantern", "camp_cooking_pot", "camp_wide_pot",
                    "sleeping_bag_roll", "sleeping_bag_sack", "sleeping_bag_folded",
                    "northern_campfire", "firewood_crib" -> Category.FOOD_CAMP;
            case "forest_trail_marker" -> Category.STREET;
            default -> switch (group) {
                case "hospital" -> Category.MEDICAL;
                case "industrial", "residential" -> Category.WORKSHOP;
                case "road" -> Category.ROADS;
                case "street", "outdoor" -> Category.STREET;
                case "nature" -> Category.NATURE;
                case "construction" -> Category.CONSTRUCTION;
                default -> Category.WORKSHOP;
            };
        };
    }

    private static int itemOrder(Item item) {
        if (item instanceof GearItem gear) {
            return switch (gear.slot()) {
                case TSHIRT -> 0;
                case SWEATER -> 1;
                case JACKET -> 2;
                case PANTS -> 3;
                case SOCKS -> 4;
                case FOOTWEAR -> 5;
                case GLOVES -> 6;
                case HAT -> 7;
                case BACKPACK -> 8;
            };
        }
        return 0;
    }

    private ModCreativeTabs() {}
}
