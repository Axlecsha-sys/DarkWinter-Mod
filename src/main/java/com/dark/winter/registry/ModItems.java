// Registers DarkWinter equipment, building items, and their physical properties.
package com.dark.winter.registry;

import com.dark.winter.equipment.*;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ModItems {
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    public static final Item WOOL_SOCKS = gear("wool_socks", GearSlot.SOCKS, 120, 25, 5);
    public static final Item INSULATED_SOCKS = gear("insulated_socks", GearSlot.SOCKS, 180, 40, 10);
    public static final Item WORK_PANTS = gear("work_pants", GearSlot.PANTS, 650, 20, 15);
    public static final Item WINTER_PANTS = gear("winter_pants", GearSlot.PANTS, 1100, 65, 55);
    public static final Item WOOL_GLOVES = gear("wool_gloves", GearSlot.GLOVES, 100, 25, 5);
    public static final Item WINTER_MITTENS = gear("winter_mittens", GearSlot.GLOVES, 250, 65, 50);
    public static final Item KNIT_CAP = gear("knit_cap", GearSlot.HAT, 130, 35, 5);
    public static final Item FUR_HAT = gear("fur_hat", GearSlot.HAT, 350, 70, 25);
    public static final Item JACKET = gear("jacket", GearSlot.JACKET, 950, 30, 30);
    public static final Item WINTER_PARKA = gear("winter_parka", GearSlot.JACKET, 1900, 85, 65);
    public static final Item HIKING_BACKPACK = gear("hiking_backpack", GearSlot.BACKPACK, 1600, 0, 55);
    public static final Item WORK_BOOTS = gear("work_boots", GearSlot.FOOTWEAR, 1400, 25, 45);
    public static final Item WINTER_BOOTS = gear("winter_boots", GearSlot.FOOTWEAR, 2100, 75, 70);
    public static final Item COTTON_TSHIRT = gear("cotton_tshirt", GearSlot.TSHIRT, 180, 0, 0);
    public static final Item NAVY_TSHIRT = gear("navy_tshirt", GearSlot.TSHIRT, 180, 0, 0);
    public static final Item RED_TSHIRT = gear("red_tshirt", GearSlot.TSHIRT, 180, 0, 0);
    public static final Item OLIVE_WOOL_SWEATER = gear("olive_wool_sweater", GearSlot.SWEATER, 650, 0, 0);
    public static final Item NAVY_WOOL_SWEATER = gear("navy_wool_sweater", GearSlot.SWEATER, 650, 0, 0);
    public static final Item OCHRE_FLEECE = gear("ochre_fleece", GearSlot.SWEATER, 520, 0, 0);
    public static final Item RED_FLANNEL_SHIRT = gear("red_flannel_shirt", GearSlot.SWEATER, 430, 0, 0);
    public static final Item RED_EXPEDITION_PARKA = gear("red_expedition_parka", GearSlot.JACKET, 1900, 85, 65);
    public static final Item NAVY_DOWN_PARKA = gear("navy_down_parka", GearSlot.JACKET, 1900, 85, 65);
    public static final Item OCHRE_ANORAK = gear("ochre_anorak", GearSlot.JACKET, 950, 30, 30);
    public static final Item QUILTED_WORK_JACKET = gear("quilted_work_jacket", GearSlot.JACKET, 950, 30, 30);
    public static final Item OLIVE_CARGO_PANTS = gear("olive_cargo_pants", GearSlot.PANTS, 650, 20, 15);
    public static final Item NAVY_THERMAL_PANTS = gear("navy_thermal_pants", GearSlot.PANTS, 1100, 65, 55);
    public static final Item RED_WATCH_CAP = gear("red_watch_cap", GearSlot.HAT, 130, 35, 5);
    public static final Item NAVY_EARFLAP_HAT = gear("navy_earflap_hat", GearSlot.HAT, 350, 70, 25);
    public static final Item LEATHER_WORK_GLOVES = gear("leather_work_gloves", GearSlot.GLOVES, 100, 25, 5);
    public static final Item FLEECE_GLOVES = gear("fleece_gloves", GearSlot.GLOVES, 100, 25, 5);
    public static final Item INSULATED_WORK_BOOTS = gear("insulated_work_boots", GearSlot.FOOTWEAR, 2100, 75, 70);
    public static final Item LEATHER_HIKING_BOOTS = gear("leather_hiking_boots", GearSlot.FOOTWEAR, 1400, 25, 45);
    public static final Item THICK_HIKING_SOCKS = gear("thick_hiking_socks", GearSlot.SOCKS, 180, 40, 10);
    // Reference outfit; each piece uses the existing equipment slot and comparable winter stats.
    public static final Item CINEMATIC_NORTHERN_WANDERER = gear("cinematic_northern_wanderer", GearSlot.JACKET, 1900, 85, 65);
    public static final Item CINEMATIC_NORTHERN_WANDERER_HAT = gear("cinematic_northern_wanderer_hat", GearSlot.HAT, 350, 70, 25);
    public static final Item CINEMATIC_NORTHERN_WANDERER_PANTS = gear("cinematic_northern_wanderer_pants", GearSlot.PANTS, 1100, 65, 55);
    public static final Item CINEMATIC_NORTHERN_WANDERER_GLOVES = gear("cinematic_northern_wanderer_gloves", GearSlot.GLOVES, 250, 65, 50);
    public static final Item CINEMATIC_NORTHERN_WANDERER_BOOTS = gear("cinematic_northern_wanderer_boots", GearSlot.FOOTWEAR, 2100, 75, 70);
    public static final Item CINEMATIC_NORTHERN_WANDERER_BACKPACK = gear("cinematic_northern_wanderer_backpack", GearSlot.BACKPACK, 1600, 0, 55);
    public static final Map<String,Item> CINEMATIC_CATALOG = cinematicCatalog();

    private static Map<String,Item> cinematicCatalog() {
        Map<String,Item> result=new LinkedHashMap<>();
        for(var design:CinematicWardrobe.DESIGNS) {
            int[] stats=switch(design.slot()) {
                case JACKET -> new int[]{1900,85,65};
                case PANTS -> new int[]{1100,65,55};
                case GLOVES -> new int[]{250,65,50};
                case FOOTWEAR -> new int[]{2100,75,70};
                case HAT -> new int[]{350,70,25};
                default -> throw new IllegalStateException("Unsupported catalogue slot");
            };
            result.put(design.id(),gear(design.id(),design.slot(),stats[0],stats[1],stats[2]));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static final Item WEIGHT_HALF_KG = weight("weight_0_5kg", 500);
    public static final Item WEIGHT_1KG = weight("weight_1kg", 1000);
    public static final Item WEIGHT_2KG = weight("weight_2kg", 2000);
    public static final Item WEIGHT_5KG = weight("weight_5kg", 5000);
    public static final Item WEIGHT_10KG = weight("weight_10kg", 10000);

    private static Item.Properties properties(String id, int grams) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("darkwinter", id)))
                .component(ModDataComponents.MASS_GRAMS, grams);
    }

    private static Item register(String id, Item item) {
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath("darkwinter", id), item);
        ITEMS.put(id, item);
        return item;
    }

    private static Item gear(String id, GearSlot slot, int grams, int insulation, int waterproofing) {
        return register(id, new GearItem(properties(id, grams).stacksTo(1)
                .component(ModDataComponents.INSULATION, insulation)
                .component(ModDataComponents.WATER_RESISTANCE, waterproofing)
                .component(ModDataComponents.CONDITION, 100), slot));
    }

    private static Item weight(String id, int grams) { return register(id, new WeightedItem(properties(id, grams))); }

    public static Map<String, Item> all() { return java.util.Collections.unmodifiableMap(ITEMS); }

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> ITEMS.values().forEach(output::accept));
    }
}
