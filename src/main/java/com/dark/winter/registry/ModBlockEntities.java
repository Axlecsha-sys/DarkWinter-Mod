// Registers block-entity types used by DarkWinter world content.
package com.dark.winter.registry;

import com.dark.winter.block.entity.WallModuleBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static final BlockEntityType<WallModuleBlockEntity> WALL_MODULE_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "wall_module_be"),
            // Сюда через запятую можно передавать любые будущие стены (каменные, дубовые и т.д.)
            FabricBlockEntityTypeBuilder.create(WallModuleBlockEntity::new,
                    ModBlocks.WALL_SEGMENT,
                    ModBlocks.WEATHERED_WALL_SEGMENT_GRAY,
                    ModBlocks.WEATHERED_WALL_SEGMENT_DARK,
                    ModBlocks.WEATHERED_WALL_SEGMENT_PATCHWORK,
                    ModBlocks.WEATHERED_WALL_SEGMENT_PAINTED).build()
    );

    public static void initialize() {}
}