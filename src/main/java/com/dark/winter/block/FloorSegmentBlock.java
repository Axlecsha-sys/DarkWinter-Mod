// Wooden floor modules retain vanilla top, bottom, double and waterlogged slab behavior.
package com.dark.winter.block;

import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.SurfacePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Wooden floor modules retain vanilla top, bottom, double and waterlogged slab behavior. */
public final class FloorSegmentBlock extends SlabBlock {
    public static final int FULL_SPAN = 4;
    public static final IntegerProperty SPAN_X = IntegerProperty.create("span_x", 1, FULL_SPAN);
    public static final IntegerProperty SPAN_Z = IntegerProperty.create("span_z", 1, FULL_SPAN);
    private static final VoxelShape[][][] FITTED_SHAPES = createFittedShapes();

    public FloorSegmentBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SPAN_X, FULL_SPAN).setValue(SPAN_Z, FULL_SPAN));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SPAN_X, SPAN_Z);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : SurfacePlacement.plannedFloorState(context, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int spanX = state.getValue(SPAN_X), spanZ = state.getValue(SPAN_Z);
        if (spanX == FULL_SPAN && spanZ == FULL_SPAN) return super.getShape(state, level, pos, context);
        return FITTED_SHAPES[state.getValue(TYPE).ordinal()][spanX - 1][spanZ - 1];
    }

    private static VoxelShape[][][] createFittedShapes() {
        var shapes = new VoxelShape[SlabType.values().length][FULL_SPAN][FULL_SPAN];
        for (SlabType type : SlabType.values()) {
            double bottom = type == SlabType.TOP ? .5 : 0;
            double top = type == SlabType.BOTTOM ? .5 : 1;
            for (int x = 1; x <= FULL_SPAN; x++) for (int z = 1; z <= FULL_SPAN; z++) {
                shapes[type.ordinal()][x - 1][z - 1] = Shapes.box(0, bottom, 0, x * .25, top, z * .25);
            }
        }
        return shapes;
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (context.replacingClickedOnBlock() && context.getClickedFace().getAxis().isHorizontal()
                && state.getValue(TYPE) != SlabType.DOUBLE && context.getItemInHand().is(asItem())) {
            var pos = context.getClickedPos();
            double offset = AnchorRuntime.offset(context.getLevel(), pos).y;
            if (offset != 0) {
                boolean upperHit = context.getClickLocation().y - pos.getY() - offset > .5;
                return state.getValue(TYPE) == SlabType.BOTTOM ? upperHit : !upperHit;
            }
        }
        return super.canBeReplaced(state, context);
    }
}
