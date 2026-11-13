package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class StorageFurnitureBlock extends FurnitureBlock implements EntityBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    public record Leaf(double left, double bottom, double right, double top, double front, double back,
                       double hingeX, double hingeZ, int angle) {}

    public enum Kind {
        // The hinge is ahead of the door face. Swinging beside a wall must not push its
        // thickness past the side of the fridge, including on existing placements.
        FRIDGE(
                new Leaf(2.07, 2.55, 13.93, 22.13, .08, 1.83, 13.89, .07, -90),
                new Leaf(2.07, 22.39, 13.93, 30.64, .08, 1.83, 13.89, .07, -90)),
        WARDROBE(
                new Leaf(2.20, 2.69, 7.91, 29.36, .09, 2.19, 2.24, 1.57, 90),
                new Leaf(8.09, 2.69, 13.80, 29.36, .09, 2.19, 13.76, 1.57, -90)),
        CABINET(
                new Leaf(2.17, 4.03, 7.88, 11.69, .08, 2.12, 2.20, 1.65, 90),
                new Leaf(8.12, 4.03, 13.83, 11.69, .08, 2.12, 13.80, 1.65, -90));

        private final Leaf[] leaves;

        Kind(Leaf first, Leaf second) {
            leaves = new Leaf[]{first, second};
        }

        public Leaf leaf(int index) { return leaves[index]; }

        public static @Nullable Kind forId(String id) {
            return switch (id) {
                case "old_fridge" -> FRIDGE;
                case "wooden_wardrobe" -> WARDROBE;
                case "low_cabinet" -> CABINET;
                default -> null;
            };
        }
    }

    private final Kind kind;
    private final VoxelShape[][] body;

    public StorageFurnitureBlock(FurnitureCatalog.Entry entry, Properties properties, Kind kind) {
        super(entry, properties.dynamicShape());
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
        body = new VoxelShape[entry.parts().size()][4];
        for (int part = 0; part < body.length; part++) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape shape = Shapes.empty();
                for (var box : entry.parts().get(part).boxes()) {
                    if (!box.collision()) continue;
                    // The old catalog's moving faces/handles were part of the block's closed collision.
                    double cut = switch (kind) {
                        case FRIDGE -> 1.66;
                        case WARDROBE -> 2.19;
                        case CABINET -> 2.12;
                    };
                    if (kind == Kind.FRIDGE && part == 0 && box.maxY() <= 2.55) cut = box.minZ();
                    if (box.maxZ() <= cut) continue;
                    var fixed = new FurnitureCatalog.Box(box.minX(), box.minY(), Math.max(cut, box.minZ()),
                            box.maxX(), box.maxY(), box.maxZ(), box.texture(), true);
                    shape = Shapes.or(shape, rotatedBox(fixed, facing, 1));
                }
                body[part][facingIndex(facing)] = shape.optimize();
            }
        }
    }

    public Kind kind() { return kind; }

    public static boolean isRoot(BlockState state) {
        return state.getBlock() instanceof StorageFurnitureBlock && state.getValue(PART) == 0;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    @Override protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(OPEN, source.getValue(OPEN));
    }

    @Override public @Nullable StorageFurnitureEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state) ? new StorageFurnitureEntity(pos, state) : null;
    }

    public boolean leafIsSeparate(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getValue(OPEN) || level.getBlockEntity(origin(pos, state)) instanceof StorageFurnitureEntity entity
                && entity.progress(0) > 0;
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        if (part < body.length && leafIsSeparate(level, pos, state)) return body[part][facingIndex(state.getValue(FACING))];
        return super.getShape(state, level, pos, context);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        if (part < body.length && leafIsSeparate(level, pos, state)) return body[part][facingIndex(state.getValue(FACING))];
        return super.getCollisionShape(state, level, pos, context);
    }
}
