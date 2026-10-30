// Registers bounded item and wall-shape data components.
package com.dark.winter.registry;

import com.dark.winter.component.WallShape;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class ModDataComponents {
    public static final DataComponentType<Integer> MASS_GRAMS = integer("mass_grams", 0, 100000000);
    // Prepared data only: no insulation, wear or wetness simulation is active yet.
    public static final DataComponentType<Integer> INSULATION = integer("insulation", 0, 100);
    public static final DataComponentType<Integer> WATER_RESISTANCE = integer("water_resistance", 0, 100);
    public static final DataComponentType<Integer> CONDITION = integer("condition", 0, 100);

    private static DataComponentType<Integer> integer(String id, int min, int max) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
                Identifier.fromNamespaceAndPath("darkwinter", id), DataComponentType.<Integer>builder()
                        .persistent(com.mojang.serialization.Codec.intRange(min, max))
                        .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());
    }
    public static final DataComponentType<WallShape> WALL_SHAPE = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "wall_shape"),
            DataComponentType.<WallShape>builder()
                    .persistent(WallShape.CODEC)
                    .networkSynchronized(WallShape.STREAM_CODEC)
                    .build()
    );

    public static void initialize() {}
}
