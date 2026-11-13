package com.dark.winter.block;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class FenceGateFurnitureBlock extends SnowVariantFurnitureBlock implements EntityBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    private static final double HINGE_X = .5, HINGE_Z = 7.25 / 16;
    private static final Set<String> IDS = Set.of(
            "picket_gate_low_clear_1m", "picket_gate_low_clear_2m", "picket_gate_tall_clear_1m", "picket_gate_tall_clear_2m",
            "horizontal_gate_low_clear_1m", "horizontal_gate_low_clear_2m", "horizontal_gate_tall_clear_1m", "horizontal_gate_tall_clear_2m",
            "wire_mesh_gate_low_clear_1m", "wire_mesh_gate_low_clear_2m", "wire_mesh_gate_tall_clear_1m", "wire_mesh_gate_tall_clear_2m");

    private final int width;
    private final boolean tall;
    private final VoxelShape[][] frames, frameCollisions;

    public FenceGateFurnitureBlock(FurnitureCatalog.Entry entry, Properties properties) {
        super(entry, properties.dynamicShape());
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
        width = entry.id().endsWith("_2m") ? 2 : 1;
        tall = entry.id().contains("_tall_");
        int count = entry.parts().size();
        frames = new VoxelShape[count][4];
        frameCollisions = new VoxelShape[count][4];
        for (int part = 0; part < count; part++) {
            var source = entry.parts().get(part);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape frame = Shapes.empty(), solid = Shapes.empty();
                for (var box : source.boxes()) {
                    int offsetX = source.offset().getX() * 16;
                    if (isLeaf(box.minX() + offsetX, box.maxX() + offsetX)) continue;
                    VoxelShape piece = rotatedBox(box, facing, 1);
                    frame = Shapes.or(frame, piece);
                    if (box.collision()) solid = Shapes.or(solid, piece);
                }
                int index = facingIndex(facing);
                frames[part][index] = frame.optimize();
                frameCollisions[part][index] = solid.optimize();
            }
        }
    }

    public static boolean isGateId(String id) { return IDS.contains(id); }
    public static boolean isRoot(BlockState state) {
        return state.getBlock() instanceof FenceGateFurnitureBlock && state.getValue(PART) == 0;
    }

    /** The authored leaf and latch sit between the jambs. The hinge plates straddle the left jamb. */
    public boolean isLeaf(double minX, double maxX) {
        return minX >= 8.1199 && maxX <= 8 + 16 * width - .0799;
    }

    public int clearWidth() { return width; }
    public boolean tall() { return tall; }

    public static Vec3 hinge(Direction facing) {
        double x = HINGE_X, z = HINGE_Z;
        for (int i = 0; i < facingIndex(facing); i++) {
            double previousX = x;
            x = 1 - z;
            z = previousX;
        }
        return new Vec3(x, 0, z);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    @Override protected BlockState copyVisualState(BlockState source, BlockState target) {
        return super.copyVisualState(source, target).setValue(OPEN, source.getValue(OPEN));
    }

    @Override public @Nullable FenceGateEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state) ? new FenceGateEntity(pos, state) : null;
    }

    private boolean leafIsSeparate(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getValue(OPEN) || level.getBlockEntity(origin(pos, state)) instanceof FenceGateEntity gate
                && gate.progress(0) > 0;
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        if (part < frames.length && leafIsSeparate(level, pos, state)) return frames[part][facingIndex(state.getValue(FACING))];
        return super.getShape(state, level, pos, context);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        if (part < frames.length && leafIsSeparate(level, pos, state)) return frameCollisions[part][facingIndex(state.getValue(FACING))];
        return super.getCollisionShape(state, level, pos, context);
    }
}
