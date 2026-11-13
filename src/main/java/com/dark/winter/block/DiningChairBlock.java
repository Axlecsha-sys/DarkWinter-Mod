package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class DiningChairBlock extends FurnitureBlock implements EntityBlock {
    public static final String ID = "interior_spindle_chair";
    public static final BooleanProperty TUCKED = BooleanProperty.create("tucked");
    private static final double[] SLIDE_DISTANCES = {.10, .16, .24, .32, .39, .46};
    private static final IntegerProperty TRAVEL = IntegerProperty.create("travel", 0, SLIDE_DISTANCES.length * 2);
    public static final int SLIDE_TICKS = 10;

    public DiningChairBlock(FurnitureCatalog.Entry entry, Properties properties) {
        super(entry, properties.dynamicShape());
        registerDefaultState(defaultBlockState().setValue(TUCKED, false).setValue(TRAVEL, 0));
    }

    public static boolean isRoot(BlockState state) {
        return state.getBlock() instanceof DiningChairBlock && state.getValue(PART) == 0;
    }

    public static Vec3 slide(BlockState state, float progress) {
        int travel = state.getValue(TRAVEL);
        double distance = travel == 0 ? SLIDE_DISTANCES[SLIDE_DISTANCES.length - 1]
                : SLIDE_DISTANCES[(travel - 1) % SLIDE_DISTANCES.length];
        return state.getValue(FACING).getUnitVec3().scale((startsUnderTable(state) ? -distance : distance) * progress);
    }

    public static boolean hasTravel(BlockState state) {
        return state.getValue(TRAVEL) != 0 || state.getValue(TUCKED);
    }

    public static boolean isTucked(BlockState state) {
        // The transition flag selects the displaced endpoint; a chair placed under the table moves outward first.
        return state.getValue(TUCKED) != startsUnderTable(state);
    }

    private static boolean startsUnderTable(BlockState state) {
        return state.getValue(TRAVEL) > SLIDE_DISTANCES.length;
    }

    public static int travelChoices() {
        return SLIDE_DISTANCES.length;
    }

    public static BlockState withTravel(BlockState state, int choice, boolean outward) {
        return state.setValue(TRAVEL, choice + 1 + (outward ? SLIDE_DISTANCES.length : 0));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TUCKED, TRAVEL);
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state) ? new DiningChairEntity(pos, state) : null;
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return movedShape(super.getShape(state, level, pos, context), state, level, pos, false);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return movedShape(super.getCollisionShape(state, level, pos, context), state, level, pos, true);
    }

    private static VoxelShape movedShape(VoxelShape shape, BlockState state, BlockGetter level, BlockPos pos,
                                         boolean collision) {
        if (level.getBlockEntity(pos) instanceof DiningChairEntity chair) {
            return chair.slidShape(shape, state, collision);
        }
        return state.getValue(TUCKED) ? shape.move(slide(state, 1)) : shape;
    }

    public VoxelShape baseCollision(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getCollisionShape(state, level, pos, CollisionContext.empty());
    }
}
