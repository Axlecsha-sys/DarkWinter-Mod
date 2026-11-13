// Registers the wiki provision assortment while its individual artwork and mechanics are pending.
package com.dark.winter.registry;

import com.dark.winter.DarkWinter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Ordinary catalog items: shared placeholder art, no consumption or balance rules yet. */
public final class ModProvisionItems {
    private static final List<String> IDS = List.of(
            "canned_corn",
            "canned_peas",
            "canned_tomatoes",
            "canned_peaches",
            "canned_mushroom_soup",
            "canned_chicken_noodle_soup",
            "peanut_butter",
            "berry_jam",
            "condensed_milk",
            "processed_cheese",
            "salted_peanuts",
            "beef_jerky",
            "granola_bar",
            "potato_chips",
            "smoked_sausage",
            "chocolate_bar",
            "wafers",
            "oatmeal_cookies",
            "energy_bar",
            "instant_noodles",
            "potato_flakes",
            "corn_flakes",
            "cola",
            "energy_cola",
            "lemon_lime_soda",
            "ginger_ale",
            "orange_soda",
            "root_beer",
            "apple_juice",
            "orange_juice",
            "grape_juice",
            "iced_tea",
            "fruit_drink",
            "black_tea",
            "coffee",
            "cocoa",
            "rosehip_infusion",
            "chaga_infusion",
            "cranberry_drink",
            "lingonberry_drink"
    );
    private static final Set<Item> ITEMS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean initialized;

    public static void initialize() {
        if (initialized) return;
        for (String path : IDS) {
            Identifier id = Identifier.fromNamespaceAndPath(DarkWinter.MOD_ID, path);
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
            Item item = Registry.register(BuiltInRegistries.ITEM, key,
                    new Item(new Item.Properties().setId(key)));
            ITEMS.add(item);
        }
        initialized = true;
        DarkWinter.LOGGER.info("Registered {} provision catalog items", ITEMS.size());
    }

    public static boolean contains(Item item) {
        return ITEMS.contains(item);
    }

    private ModProvisionItems() {}
}
