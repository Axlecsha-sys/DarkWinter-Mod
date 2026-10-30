// Shapes shared by the existing overworld logs and their bark/stripped variants.
package com.dark.winter.block;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shapes shared by the existing overworld logs and their bark/stripped variants. */
public final class ThinTrunks {
    public static final float INSET = 1.5F / 16.0F;

    private static final VoxelShape X_SHAPE = Block.box(0, 1.5, 1.5, 16, 14.5, 14.5);
    private static final VoxelShape Y_SHAPE = Block.box(1.5, 0, 1.5, 14.5, 16, 14.5);
    private static final VoxelShape Z_SHAPE = Block.box(1.5, 1.5, 0, 14.5, 14.5, 16);

    // BlockState asks about light occlusion before its block is registered.
    // Description IDs are already assigned then; registry lookups and tags are not ready.
    private static final Set<String> DESCRIPTION_IDS = Set.of(
            "block.minecraft.oak_log", "block.minecraft.oak_wood",
            "block.minecraft.stripped_oak_log", "block.minecraft.stripped_oak_wood",
            "block.minecraft.spruce_log", "block.minecraft.spruce_wood",
            "block.minecraft.stripped_spruce_log", "block.minecraft.stripped_spruce_wood",
            "block.minecraft.birch_log", "block.minecraft.birch_wood",
            "block.minecraft.stripped_birch_log", "block.minecraft.stripped_birch_wood",
            "block.minecraft.jungle_log", "block.minecraft.jungle_wood",
            "block.minecraft.stripped_jungle_log", "block.minecraft.stripped_jungle_wood",
            "block.minecraft.acacia_log", "block.minecraft.acacia_wood",
            "block.minecraft.stripped_acacia_log", "block.minecraft.stripped_acacia_wood",
            "block.minecraft.dark_oak_log", "block.minecraft.dark_oak_wood",
            "block.minecraft.stripped_dark_oak_log", "block.minecraft.stripped_dark_oak_wood",
            "block.minecraft.mangrove_log", "block.minecraft.mangrove_wood",
            "block.minecraft.stripped_mangrove_log", "block.minecraft.stripped_mangrove_wood",
            "block.minecraft.cherry_log", "block.minecraft.cherry_wood",
            "block.minecraft.stripped_cherry_log", "block.minecraft.stripped_cherry_wood",
            "block.minecraft.pale_oak_log", "block.minecraft.pale_oak_wood",
            "block.minecraft.stripped_pale_oak_log", "block.minecraft.stripped_pale_oak_wood",
            "block.minecraft.poplar_log", "block.minecraft.poplar_wood",
            "block.minecraft.stripped_poplar_log", "block.minecraft.stripped_poplar_wood");

    private ThinTrunks() {}

    public static boolean isThin(BlockState state) {
        return isThin(state.getBlock());
    }

    public static boolean isThin(Block block) {
        return block instanceof RotatedPillarBlock && DESCRIPTION_IDS.contains(block.getDescriptionId());
    }

    public static VoxelShape shape(BlockState state) {
        return switch (state.getValue(RotatedPillarBlock.AXIS)) {
            case X -> X_SHAPE;
            case Y -> Y_SHAPE;
            case Z -> Z_SHAPE;
        };
    }
}
