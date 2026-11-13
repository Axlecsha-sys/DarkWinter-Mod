package com.dark.winter.block;

import com.dark.winter.placement.SurfacePlacement;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** An R3 roof profile with a lateral span in quarter blocks, measured along its local X axis. */
public final class RoofSegmentBlock extends FurnitureBlock {
    public static final int FULL_SPAN = 4;
    public static final IntegerProperty SPAN = IntegerProperty.create("span", 1, FULL_SPAN);
    private static final Set<String> IDS = Set.of("r3_shake_roof_slope", "r3_shake_roof_eave",
            "r3_shake_roof_ridge", "r3_shake_roof_gable_left", "r3_shake_roof_gable_right", "r3_shake_roof_gable_peak",
            "cabin_roof_eave", "cabin_roof_eave_left", "cabin_roof_eave_right", "cabin_roof_gable_left",
            "cabin_roof_gable_right", "cabin_roof_gable_peak", "cabin_roof_chimney",
            "cabin_roof_chimney_left", "cabin_roof_chimney_right");

    private final VoxelShape[][][] fittedOutlines = new VoxelShape[FULL_SPAN - 1][MAX_PARTS][4];
    private final VoxelShape[][][] fittedCollisions = new VoxelShape[FULL_SPAN - 1][MAX_PARTS][4];

    public RoofSegmentBlock(FurnitureCatalog.Entry definition, Properties properties) {
        super(definition, properties);
        registerDefaultState(defaultBlockState().setValue(SPAN, FULL_SPAN));
        for (int span = 1; span < FULL_SPAN; span++) {
            createShapes(fittedOutlines[span - 1], fittedCollisions[span - 1], width(span));
        }
    }

    public static boolean isRoofId(String id) {
        return IDS.contains(id);
    }

    public static float width(BlockState state) {
        return state.getBlock() instanceof RoofSegmentBlock ? width(state.getValue(SPAN)) : 1;
    }

    private static float width(int span) {
        return (float) span / FULL_SPAN;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SPAN);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState root = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return getStateForPlacement(context, SurfacePlacement.plannedRoofState(context, root));
    }

    @Override
    protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(SPAN, source.getValue(SPAN));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int span = state.getValue(SPAN);
        if (span == FULL_SPAN) return super.getShape(state, level, pos, context);
        return fittedOutlines[span - 1][state.getValue(PART)][facingIndex(state.getValue(FACING))];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int span = state.getValue(SPAN);
        if (span == FULL_SPAN) return super.getCollisionShape(state, level, pos, context);
        return fittedCollisions[span - 1][state.getValue(PART)][facingIndex(state.getValue(FACING))];
    }
}
