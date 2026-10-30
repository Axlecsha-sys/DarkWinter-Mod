// ID-based vanilla behavior which a copied biome JSON cannot inherit.
package com.dark.winter.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.biome.Biome;

/** ID-based vanilla behavior which a copied biome JSON cannot inherit. */
public final class TestBiomeCompatibility {
    public static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath("darkwinter", "darkwinter_test"));

    private TestBiomeCompatibility() {}

    public static void init() {
        // Keep the exact Snowy Taiga villager/zombie-villager appearance, including cures.
        VillagerType.BY_BIOME.put(BIOME, VillagerType.SNOW);
    }
}
