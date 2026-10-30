// A dedicated narrow trunk; vanilla logs and their render/physics hooks are untouched.
package com.dark.winter.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A dedicated narrow trunk; vanilla logs and their render/physics hooks are untouched. */
public final class TestSpruceLogBlock extends RotatedPillarBlock {
    public static final IntegerProperty RADIUS = IntegerProperty.create("radius", 1, 5);
    public static final double[] RADII = {.065, .12, .18, .24, .30};
    private static final VoxelShape[][] SHAPES = new VoxelShape[3][5];
    static {
        for (int r = 0; r < 5; r++) {
            double inset = 8 - RADII[r] * 16;
            SHAPES[0][r] = Block.box(0, inset, inset, 16, 16-inset, 16-inset);
            SHAPES[1][r] = Block.box(inset, 0, inset, 16-inset, 16, 16-inset);
            SHAPES[2][r] = Block.box(inset, inset, 0, 16-inset, 16-inset, 16);
        }
    }
    public TestSpruceLogBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(RADIUS, 5));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(RADIUS);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(AXIS).ordinal()][state.getValue(RADIUS)-1];
    }
}
