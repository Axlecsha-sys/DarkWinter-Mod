// Snow and foliage belong to this mesh, never to vanilla leaf or snow blocks.
package com.dark.winter.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Snow and foliage belong to this mesh, never to vanilla leaf or snow blocks. */
public final class TestSpruceBoughBlock extends Block {
    public static final IntegerProperty DIRECTION = IntegerProperty.create("direction", 0, 7);
    public static final IntegerProperty DROP = IntegerProperty.create("drop", 0, 1);
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 3);
    public static final BooleanProperty TIP = BooleanProperty.create("tip");
    private static final VoxelShape PICK = Block.box(1, 3, 1, 15, 14, 15);
    public TestSpruceBoughBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DIRECTION, 0).setValue(DROP, 0)
                .setValue(VARIANT, 0).setValue(TIP, true));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DIRECTION, DROP, VARIANT, TIP);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return PICK; }
    @Override protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        int octant = Math.floorMod(Math.round(context.getRotation() / 45f) + 4, 8);
        return defaultBlockState().setValue(DIRECTION, octant);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        int offset = switch (rotation) { case CLOCKWISE_90 -> 2; case CLOCKWISE_180 -> 4; case COUNTERCLOCKWISE_90 -> 6; default -> 0; };
        return state.setValue(DIRECTION, (state.getValue(DIRECTION) + offset) & 7);
    }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        int d = state.getValue(DIRECTION);
        return state.setValue(DIRECTION, switch (mirror) { case LEFT_RIGHT -> (4-d)&7; case FRONT_BACK -> (-d)&7; default -> d; });
    }
}
