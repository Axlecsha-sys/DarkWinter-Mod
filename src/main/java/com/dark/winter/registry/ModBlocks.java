// Registers structural blocks and their authored material variants.
package com.dark.winter.registry;

import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.FrozenWindowBlock;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.util.function.Function;

public final class ModBlocks {
    public static final String MOD_ID = "darkwinter";

    public static final Block WALL_SEGMENT = register(
            "wall_segment",
            WallSegmentBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(2.0f, 3.0f) // 2.0f время разрушения, 3.0f взрывоустойчивость (как у досок)
                    .instrument(NoteBlockInstrument.BASS)
                    .sound(SoundType.WOOD)
                    .ignitedByLava()
                    .noOcclusion(),
            true
    );

    public static final Block WEATHERED_WALL_SEGMENT_GRAY = registerWeatheredWall("weathered_wall_segment_gray");
    public static final Block WEATHERED_WALL_SEGMENT_DARK = registerWeatheredWall("weathered_wall_segment_dark");
    public static final Block WEATHERED_WALL_SEGMENT_PATCHWORK = registerWeatheredWall("weathered_wall_segment_patchwork");
    public static final Block WEATHERED_WALL_SEGMENT_PAINTED = registerWeatheredWall("weathered_wall_segment_painted");

    public static final Block FLOOR_SEGMENT = registerFloor("floor_segment");
    public static final Block WEATHERED_FLOOR_SEGMENT_GRAY = registerFloor("weathered_floor_segment_gray");
    public static final Block WEATHERED_FLOOR_SEGMENT_DARK = registerFloor("weathered_floor_segment_dark");
    public static final Block WEATHERED_FLOOR_SEGMENT_PATCHWORK = registerFloor("weathered_floor_segment_patchwork");
    public static final Block WEATHERED_FLOOR_SEGMENT_PAINTED = registerFloor("weathered_floor_segment_painted");
    public static final Block FROZEN_WINDOW = registerWindow("frozen_window");
    public static final Block FROZEN_WINDOW_GRAY = registerWindow("frozen_window_gray");
    public static final Block FROZEN_WINDOW_DARK = registerWindow("frozen_window_dark");

    private static Block registerFloor(String name) {
        return register(name, FloorSegmentBlock::new, BlockBehaviour.Properties.of()
                .strength(2.0f, 3.0f).instrument(NoteBlockInstrument.BASS)
                .sound(SoundType.WOOD).ignitedByLava(), true);
    }

    private static Block registerWindow(String name) {
        return register(name, FrozenWindowBlock::new, BlockBehaviour.Properties.of()
                .strength(1.5f, 3.0f).instrument(NoteBlockInstrument.HAT)
                .sound(SoundType.GLASS).noOcclusion(), true);
    }

    private static Block registerWeatheredWall(String name) {
        // Each registration needs its own mutable Properties instance.
        return register(name, WallSegmentBlock::new, BlockBehaviour.Properties.of()
                .strength(2.0f, 3.0f)
                .instrument(NoteBlockInstrument.BASS)
                .sound(SoundType.WOOD)
                .ignitedByLava()
                .noOcclusion(), true);
    }

    public static boolean isWallSegment(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof WallSegmentBlock;
    }

    public static boolean isFloorSegment(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof FloorSegmentBlock;
    }

    public static boolean isFrozenWindow(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof FrozenWindowBlock;
    }

    public static boolean isBuildingSegment(net.minecraft.world.item.ItemStack stack) {
        return isWallSegment(stack) || isFloorSegment(stack) || isFrozenWindow(stack);
    }

    public static String wallMaterialKey(net.minecraft.world.item.ItemStack stack) {
        if (stack.is(WEATHERED_WALL_SEGMENT_GRAY.asItem()) || stack.is(WEATHERED_FLOOR_SEGMENT_GRAY.asItem()) || stack.is(FROZEN_WINDOW_GRAY.asItem())) return "wall.material.darkwinter.gray";
        if (stack.is(WEATHERED_WALL_SEGMENT_DARK.asItem()) || stack.is(WEATHERED_FLOOR_SEGMENT_DARK.asItem()) || stack.is(FROZEN_WINDOW_DARK.asItem())) return "wall.material.darkwinter.dark";
        if (stack.is(WEATHERED_WALL_SEGMENT_PATCHWORK.asItem()) || stack.is(WEATHERED_FLOOR_SEGMENT_PATCHWORK.asItem())) return "wall.material.darkwinter.patchwork";
        if (stack.is(WEATHERED_WALL_SEGMENT_PAINTED.asItem()) || stack.is(WEATHERED_FLOOR_SEGMENT_PAINTED.asItem())) return "wall.material.darkwinter.painted";
        return "wall.material.darkwinter.spruce";
    }

    private static Block register(
            String name,
            Function<BlockBehaviour.Properties, Block> blockFactory,
            BlockBehaviour.Properties properties,
            boolean shouldRegisterItem
    ) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, name);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);

        // Привязываем ключ до вызова конструктора WallSegmentBlock
        Block block = blockFactory.apply(properties.setId(blockKey));

        if (shouldRegisterItem) {
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
            BlockItem blockItem = new BlockItem(
                    block,
                    new Item.Properties()
                            .useBlockDescriptionPrefix()
                            .setId(itemKey)
            );
            Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);
        }

        return Registry.register(BuiltInRegistries.BLOCK, blockKey, block);
    }

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(output -> {
            output.accept(WALL_SEGMENT.asItem());
            output.accept(WEATHERED_WALL_SEGMENT_GRAY.asItem());
            output.accept(WEATHERED_WALL_SEGMENT_DARK.asItem());
            output.accept(WEATHERED_WALL_SEGMENT_PATCHWORK.asItem());
            output.accept(WEATHERED_WALL_SEGMENT_PAINTED.asItem());
            output.accept(FLOOR_SEGMENT.asItem());
            output.accept(WEATHERED_FLOOR_SEGMENT_GRAY.asItem());
            output.accept(WEATHERED_FLOOR_SEGMENT_DARK.asItem());
            output.accept(WEATHERED_FLOOR_SEGMENT_PATCHWORK.asItem());
            output.accept(WEATHERED_FLOOR_SEGMENT_PAINTED.asItem());
            output.accept(FROZEN_WINDOW.asItem());
            output.accept(FROZEN_WINDOW_GRAY.asItem());
            output.accept(FROZEN_WINDOW_DARK.asItem());
        });
    }

    private ModBlocks() {}
}