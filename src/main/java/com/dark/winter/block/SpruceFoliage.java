// Firm branch cores with soft, traversable needle tips on both logical sides.
package com.dark.winter.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Firm branch cores with soft, traversable needle tips on both logical sides. */
public final class SpruceFoliage {
    private static final VoxelShape INNER = Block.box(3, 0, 3, 13, 16, 13);
    private static final VoxelShape MIDDLE = Block.box(4, 0, 4, 12, 16, 12);
    private static final VoxelShape OUTER = Block.box(5, 0, 5, 11, 16, 11);

    private SpruceFoliage() {}

    public static boolean isSpruce(BlockState state) {
        // Shape caches are built while Blocks is still initializing: no registry lookup here.
        return state.getBlock() instanceof LeavesBlock
                && state.getBlock().getDescriptionId().equals("block.minecraft.spruce_leaves");
    }

    public static VoxelShape collision(BlockState state) {
        return switch (state.getValue(LeavesBlock.DISTANCE)) {
            case 1 -> INNER;
            case 2 -> MIDDLE;
            default -> OUTER;
        };
    }
}
