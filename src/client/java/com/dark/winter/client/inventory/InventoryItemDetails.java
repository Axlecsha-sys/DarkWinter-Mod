package com.dark.winter.client.inventory;

import com.dark.winter.equipment.GearItem;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item-card text and the individual clothing balance from the project wiki (2026-11-09).
 * These are base ratings for a garment; they do not simulate wear/wetness or combine layers.
 * Live condition and mass continue to come from the selected stack.
 */
public final class InventoryItemDetails {
    public record ClothingStats(float warmthCelsius, int windResistance, int waterResistance, int mobilityPenalty) {}

    // Deliberately separate from the legacy 0..100 INSULATION component: it is not degrees.
    private static final Map<String, ClothingStats> CLOTHING = Map.ofEntries(
            Map.entry("cinematic_balaclava_and_cap", new ClothingStats(1.5f, 25, 10, 0)),
            Map.entry("cinematic_bib_overalls", new ClothingStats(3.5f, 55, 30, 4)),
            Map.entry("cinematic_canvas_gauntlet_gloves", new ClothingStats(1.0f, 60, 15, 1)),
            Map.entry("cinematic_cargo_winter_trousers", new ClothingStats(2.5f, 35, 20, 2)),
            Map.entry("cinematic_expedition_parka", new ClothingStats(11.0f, 80, 40, 7)),
            Map.entry("cinematic_expedition_snow_boots", new ClothingStats(3.0f, 75, 55, 7)),
            Map.entry("cinematic_felt_lined_field_boots", new ClothingStats(3.0f, 55, 25, 5)),
            Map.entry("cinematic_fold_over_watch_cap", new ClothingStats(2.0f, 10, 0, 0)),
            Map.entry("cinematic_fur_collar_cold_weather_jacket", new ClothingStats(8.5f, 60, 25, 5)),
            Map.entry("cinematic_fur_lined_cold_weather_boots", new ClothingStats(3.0f, 65, 35, 6)),
            Map.entry("cinematic_fur_lined_storm_cap", new ClothingStats(3.0f, 60, 35, 3)),
            Map.entry("cinematic_heavy_mittens", new ClothingStats(2.5f, 55, 25, 3)),
            Map.entry("cinematic_heavy_mountaineering_boots", new ClothingStats(2.5f, 85, 65, 8)),
            Map.entry("cinematic_insulated_field_pants", new ClothingStats(3.0f, 40, 20, 3)),
            Map.entry("cinematic_insulated_rubber_winter_boots", new ClothingStats(2.0f, 85, 95, 6)),
            Map.entry("cinematic_insulated_winter_gloves", new ClothingStats(2.0f, 40, 25, 1)),
            Map.entry("cinematic_knit_liner_gloves", new ClothingStats(1.0f, 5, 0, 0)),
            Map.entry("cinematic_lace_up_work_boots", new ClothingStats(1.0f, 50, 35, 4)),
            Map.entry("cinematic_leather_work_gloves", new ClothingStats(0.5f, 30, 20, 0)),
            Map.entry("cinematic_long_anorak", new ClothingStats(5.5f, 75, 50, 4)),
            Map.entry("cinematic_low_profile_winter_field_cap", new ClothingStats(1.0f, 30, 20, 0)),
            Map.entry("cinematic_military_winter_coat", new ClothingStats(8.0f, 75, 20, 6)),
            Map.entry("cinematic_northern_wanderer", new ClothingStats(8.0f, 55, 25, 4)),
            Map.entry("cinematic_northern_wanderer_boots", new ClothingStats(2.5f, 60, 45, 5)),
            Map.entry("cinematic_northern_wanderer_gloves", new ClothingStats(2.0f, 40, 25, 1)),
            Map.entry("cinematic_northern_wanderer_hat", new ClothingStats(1.5f, 5, 0, 0)),
            Map.entry("cinematic_northern_wanderer_pants", new ClothingStats(2.5f, 35, 20, 2)),
            Map.entry("cinematic_padded_bomber", new ClothingStats(6.5f, 60, 25, 3)),
            Map.entry("cinematic_padded_snow_pants", new ClothingStats(3.5f, 50, 40, 5)),
            Map.entry("cinematic_quilted_expedition_trousers", new ClothingStats(4.0f, 60, 25, 6)),
            Map.entry("cinematic_quilted_field_jacket", new ClothingStats(7.0f, 45, 25, 3)),
            Map.entry("cinematic_reinforced_survival_boots", new ClothingStats(1.5f, 70, 60, 5)),
            Map.entry("cinematic_reinforced_work_pants", new ClothingStats(1.5f, 55, 25, 2)),
            Map.entry("cinematic_ribbed_beanie", new ClothingStats(1.5f, 5, 0, 0)),
            Map.entry("cinematic_rugged_scout_pants", new ClothingStats(2.0f, 45, 35, 2)),
            Map.entry("cinematic_survival_shell_jacket", new ClothingStats(4.5f, 90, 75, 5)),
            Map.entry("cinematic_tactical_cold_weather_gloves", new ClothingStats(1.5f, 45, 25, 0)),
            Map.entry("cinematic_tactical_winter_boots", new ClothingStats(2.0f, 60, 45, 4)),
            Map.entry("cinematic_technical_shell_pants", new ClothingStats(1.5f, 70, 65, 3)),
            Map.entry("cinematic_thick_knit_ear_cap", new ClothingStats(2.0f, 10, 0, 0)),
            Map.entry("cinematic_trapper_hat", new ClothingStats(2.5f, 45, 30, 2)),
            Map.entry("cinematic_trigger_mitts", new ClothingStats(1.5f, 50, 30, 1)),
            Map.entry("cinematic_ushanka", new ClothingStats(2.5f, 35, 15, 1)),
            Map.entry("cinematic_waterproof_shell_gloves", new ClothingStats(1.5f, 70, 80, 2)),
            Map.entry("cinematic_workwear_insulated_jacket", new ClothingStats(6.5f, 50, 40, 4)),
            Map.entry("cotton_tshirt", new ClothingStats(1.0f, 0, 0, 0)),
            Map.entry("fleece_gloves", new ClothingStats(1.0f, 5, 0, 0)),
            Map.entry("fur_hat", new ClothingStats(2.5f, 35, 15, 1)),
            Map.entry("insulated_socks", new ClothingStats(1.5f, 0, 0, 0)),
            Map.entry("insulated_work_boots", new ClothingStats(2.0f, 65, 40, 5)),
            Map.entry("jacket", new ClothingStats(5.0f, 30, 20, 2)),
            Map.entry("knit_cap", new ClothingStats(1.5f, 5, 0, 0)),
            Map.entry("leather_hiking_boots", new ClothingStats(1.0f, 45, 45, 3)),
            Map.entry("leather_work_gloves", new ClothingStats(0.5f, 30, 20, 0)),
            Map.entry("navy_down_parka", new ClothingStats(12.0f, 65, 20, 6)),
            Map.entry("navy_earflap_hat", new ClothingStats(2.5f, 35, 15, 1)),
            Map.entry("navy_thermal_pants", new ClothingStats(2.0f, 20, 15, 1)),
            Map.entry("navy_tshirt", new ClothingStats(1.0f, 0, 0, 0)),
            Map.entry("navy_wool_sweater", new ClothingStats(3.0f, 0, 0, 0)),
            Map.entry("ochre_anorak", new ClothingStats(4.5f, 60, 40, 3)),
            Map.entry("ochre_fleece", new ClothingStats(2.0f, 0, 0, 0)),
            Map.entry("olive_cargo_pants", new ClothingStats(1.0f, 20, 15, 1)),
            Map.entry("olive_wool_sweater", new ClothingStats(3.0f, 0, 0, 0)),
            Map.entry("quilted_work_jacket", new ClothingStats(6.0f, 35, 15, 3)),
            Map.entry("red_expedition_parka", new ClothingStats(11.0f, 80, 40, 7)),
            Map.entry("red_flannel_shirt", new ClothingStats(1.5f, 0, 0, 0)),
            Map.entry("red_tshirt", new ClothingStats(1.0f, 0, 0, 0)),
            Map.entry("red_watch_cap", new ClothingStats(1.5f, 5, 0, 0)),
            Map.entry("thick_hiking_socks", new ClothingStats(1.5f, 0, 0, 0)),
            Map.entry("winter_boots", new ClothingStats(2.5f, 60, 45, 5)),
            Map.entry("winter_mittens", new ClothingStats(2.0f, 45, 25, 2)),
            Map.entry("winter_pants", new ClothingStats(2.0f, 20, 15, 1)),
            Map.entry("winter_parka", new ClothingStats(8.0f, 60, 30, 4)),
            Map.entry("wool_gloves", new ClothingStats(1.0f, 5, 0, 0)),
            Map.entry("wool_socks", new ClothingStats(1.0f, 0, 0, 0)),
            Map.entry("work_boots", new ClothingStats(1.0f, 50, 35, 4)),
            Map.entry("work_pants", new ClothingStats(1.0f, 20, 15, 1))
    );
    // At most one component per registered DarkWinter item, accessed by the render thread.
    // Translatable components keep the current language; no translated string is cached.
    private static final Map<Item, Component> DESCRIPTIONS = new IdentityHashMap<>();

    private InventoryItemDetails() {}

    public static ClothingStats clothing(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "darkwinter".equals(id.getNamespace()) ? CLOTHING.get(id.getPath()) : null;
    }

    public static Component description(ItemStack stack) {
        if (stack.isEmpty()) return Component.empty();
        Item item = stack.getItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if ("darkwinter".equals(id.getNamespace())) {
            return DESCRIPTIONS.computeIfAbsent(item,
                    ignored -> Component.translatable("item.darkwinter." + id.getPath() + ".description"));
        }
        String fallback = item instanceof GearItem ? "bag_clothes_description"
                : stack.has(DataComponents.FOOD) ? "food_description" : "item_description";
        return Component.translatable("inventory.darkwinter.v2." + fallback);
    }
}
