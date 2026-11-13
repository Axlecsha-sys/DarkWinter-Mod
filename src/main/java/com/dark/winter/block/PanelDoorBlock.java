package com.dark.winter.block;

import com.dark.winter.placement.AnchorRuntime;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class PanelDoorBlock extends FurnitureBlock implements EntityBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final int SWING_TICKS = 9;
    private static final int ANGLE_STEPS = 24;
    private static final double HINGE_X = 14.71, HINGE_Z = 2.05;
    private static final VoxelShape[][][] POSES = new VoxelShape[4][2][ANGLE_STEPS + 1];
    private static final VoxelShape[] SWEPT_LEAF = new VoxelShape[4];

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            int direction = facingIndex(facing);
            VoxelShape swept = Shapes.empty();
            for (int step = 0; step <= ANGLE_STEPS; step++) {
                double angle = step * Math.PI / (2 * ANGLE_STEPS);
                for (int part = 0; part < 2; part++) {
                    VoxelShape leaf = leaf(facing, angle, part == 0 ? 1.19 : 0, part == 0 ? 16 : 14.8);
                    POSES[direction][part][step] = Shapes.or(frame(facing, part), leaf).optimize();
                    swept = Shapes.or(swept, leaf.move(0, part, 0));
                }
            }
            SWEPT_LEAF[direction] = swept.optimize();
        }
    }

    public PanelDoorBlock(FurnitureCatalog.Entry definition, Properties properties) {
        super(definition, properties.dynamicShape());
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    public static boolean isDoorId(String id) {
        return id.equals("northern_panel_door") || id.equals("cabin_panel_door");
    }

    public static boolean isRoot(BlockState state) {
        return state.getBlock() instanceof PanelDoorBlock && state.getValue(PART) == 0;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    @Override protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(OPEN, source.getValue(OPEN));
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state) ? new PanelDoorEntity(pos, state) : null;
    }

    public static Vec3 hinge(Direction facing) {
        double x = HINGE_X, z = HINGE_Z;
        for (int turn = 0; turn < facingIndex(facing); turn++) {
            double nextX = 16 - z;
            z = x;
            x = nextX;
        }
        return new Vec3(x / 16, 0, z / 16);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state, level, pos, context, false);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state, level, pos, context, true);
    }

    private VoxelShape shape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, boolean collision) {
        int part = state.getValue(PART);
        if (part >= 2) return Shapes.empty();
        float progress = state.getValue(OPEN) ? 1 : 0;
        if (level.getBlockEntity(origin(pos, state)) instanceof PanelDoorEntity door) progress = door.progress(0);
        int angle = Math.round(progress * ANGLE_STEPS);
        if (angle == 0) return collision ? super.getCollisionShape(state, level, pos, context)
                : super.getShape(state, level, pos, context);
        return POSES[facingIndex(state.getValue(FACING))][part][angle];
    }

    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type != PathComputationType.WATER && state.getValue(OPEN);
    }

    public boolean swingClear(ServerLevel level, BlockPos origin, Direction facing) {
        VoxelShape sweep = SWEPT_LEAF[facingIndex(facing)].move(Vec3.atLowerCornerOf(origin).add(AnchorRuntime.offset(level, origin)));
        if (!level.isUnobstructed(null, sweep)) return false;
        AABB box = sweep.bounds();
        var candidates = new HashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!level.hasChunkAt(cell) || !level.getWorldBorder().isWithinBounds(cell)) return false;
            candidates.add(cell.immutable());
            candidates.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        BlockPos upper = origin.above();
        for (BlockPos candidate : candidates) {
            if (candidate.equals(origin) || candidate.equals(upper)) continue;
            VoxelShape other = level.getBlockState(candidate).getCollisionShape(level, candidate).move(candidate);
            if (Shapes.joinIsNotEmpty(sweep, other, BooleanOp.AND)) return false;
        }
        return true;
    }

    private static VoxelShape frame(Direction facing, int part) {
        VoxelShape jambs = Shapes.or(box(facing, 0, 0, 0, 1.18, 16, 3.65),
                box(facing, 14.82, 0, 0, 16, 16, 3.65));
        return Shapes.or(jambs, part == 0 ? box(facing, 0, 0, .07, 16, 1.22, 3.98)
                : box(facing, 0, 14.8, 0, 16, 16, 3.65));
    }

    private static VoxelShape leaf(Direction facing, double angle, double bottom, double top) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        VoxelShape shape = Shapes.empty();
        // Narrow slices keep the angled collision close to the panel instead of filling its bounding rectangle.
        for (int slice = 0; slice < 8; slice++) {
            double left = 1.21 + (14.79 - 1.21) * slice / 8, right = left + (14.79 - 1.21) / 8;
            double minX = HINGE_X + (left - HINGE_X) * cos + (.91 - HINGE_Z) * sin;
            double maxX = HINGE_X + (right - HINGE_X) * cos + (3.10 - HINGE_Z) * sin;
            double minZ = HINGE_Z - (right - HINGE_X) * sin + (.91 - HINGE_Z) * cos;
            double maxZ = HINGE_Z - (left - HINGE_X) * sin + (3.10 - HINGE_Z) * cos;
            shape = Shapes.or(shape, box(facing, minX, bottom, minZ, maxX, top, maxZ));
        }
        return shape;
    }

    private static VoxelShape box(Direction facing, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        for (int turn = 0; turn < facingIndex(facing); turn++) {
            double nextMinX = 16 - maxZ, nextMaxX = 16 - minZ;
            minZ = minX;
            maxZ = maxX;
            minX = nextMinX;
            maxX = nextMaxX;
        }
        return Block.box(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
