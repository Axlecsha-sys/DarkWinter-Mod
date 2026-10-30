// Decorative, non-ticking multi-block furniture; no containers or other interactions.
package com.dark.winter.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Decorative, non-ticking multi-block furniture; no containers or other interactions. */
public class FurnitureBlock extends HorizontalDirectionalBlock {
    public static final int MAX_PARTS = 8;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, MAX_PARTS - 1);
    private record Mutation(FurnitureBlock block, Object level, BlockPos origin) {}
    private static final ThreadLocal<Map<Mutation, Integer>> MUTATIONS = ThreadLocal.withInitial(HashMap::new);

    private final FurnitureCatalog.Entry definition;
    private final VoxelShape[][] outlines = new VoxelShape[MAX_PARTS][4];
    private final VoxelShape[][] collisions = new VoxelShape[MAX_PARTS][4];

    public FurnitureBlock(FurnitureCatalog.Entry definition, Properties properties) {
        super(properties);
        this.definition = definition;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
        for (int part = 0; part < MAX_PARTS; part++) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape outline = Shapes.empty(), collision = Shapes.empty();
                if (part < definition.parts().size()) {
                    for (FurnitureCatalog.Box box : definition.parts().get(part).boxes()) {
                        VoxelShape shape = rotatedBox(box, facing);
                        outline = Shapes.or(outline, shape);
                        if (box.collision()) collision = Shapes.or(collision, shape);
                    }
                }
                outlines[part][facingIndex(facing)] = outline.optimize();
                // Paint is separated visually by 0.02 model units, while a road remains a full foundation.
                collisions[part][facingIndex(facing)] = part == 0 && isFullRoadSurface()
                        ? Shapes.block() : collision.optimize();
            }
        }
    }

    public FurnitureCatalog.Entry definition() { return definition; }

    private boolean isFullRoadSurface() {
        if (!definition.group().equals("road")) return false;
        // Cones and delineators also use the road material but retain their actual geometry.
        return switch (definition.id()) {
            case "asphalt_block", "asphalt_white_line", "asphalt_dashed_line", "asphalt_crosswalk" -> true;
            default -> false;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    public BlockPos partOffset(int part, Direction facing) {
        return rotateOffset(definition.parts().get(part).offset(), facing);
    }

    public List<BlockPos> positions(BlockPos origin, Direction facing) {
        return java.util.stream.IntStream.range(0, definition.parts().size())
                .mapToObj(part -> origin.offset(partOffset(part, facing))).toList();
    }

    public BlockPos origin(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return part < definition.parts().size() ? pos.subtract(partOffset(part, state.getValue(FACING))) : pos;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        for (int part = 0; part < definition.parts().size(); part++) {
            BlockPos target = origin.offset(partOffset(part, facing));
            BlockState state = state(part, facing);
            if (!level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)
                    || !level.getBlockState(target).canBeReplaced(context)
                    || context.getPlayer() != null && (!context.getPlayer().mayUseItemAt(target, context.getClickedFace(), context.getItemInHand())
                        || !level.mayInteract(context.getPlayer(), target))
                    || !level.isUnobstructed(state, target, CollisionContext.placementContext(context.getPlayer()))) return null;
        }
        return state(0, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide()) return;
        Direction facing = state.getValue(FACING);
        Player player = placer instanceof Player p ? p : null;
        // Recheck after BlockItem's optional BLOCK_STATE component has been applied.
        boolean valid = state.getValue(PART) == 0 && level.getBlockState(pos).is(this);
        for (int part = 0; valid && part < definition.parts().size(); part++) {
            BlockPos target = pos.offset(partOffset(part, facing));
            valid = level.isInWorldBounds(target) && level.getWorldBorder().isWithinBounds(target)
                    && (player == null || level.mayInteract(player, target) && player.mayUseItemAt(target, Direction.UP, stack))
                    && (part == 0 || level.getBlockState(target).canBeReplaced())
                    && level.isUnobstructed(copyVisualState(state, state(part, facing)), target, CollisionContext.placementContext(player));
        }
        if (!valid) {
            removeAssembly(level, pos, state);
            if (player == null || !player.preventsBlockDrops()) Block.popResource(level, pos, stackForState(state));
            return;
        }
        mutate(level, pos, () -> {
            for (int part = 1; part < definition.parts().size(); part++) {
                level.setBlock(pos.offset(partOffset(part, facing)), copyVisualState(state, state(part, facing)),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
            }
            for (BlockPos target : positions(pos, facing)) {
                level.updateNeighborsAt(target, this);
                level.getBlockState(target).updateNeighbourShapes(level, target, Block.UPDATE_ALL);
            }
            return null;
        });
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockState result = super.playerWillDestroy(level, pos, state, player);
        if (!level.isClientSide() && level.getBlockState(pos) == state) {
            BlockPos origin = origin(pos, state);
            boolean complete = removeAssembly(level, pos, state);
            if (complete && !player.preventsBlockDrops()) Block.popResource(level, origin, stackForState(state));
        }
        return result;
    }

    /** All loot is emitted once per assembly above, so vanilla mining cannot duplicate it. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    @Override
    protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion,
                                  BiConsumer<ItemStack, BlockPos> onHit) {
        if (explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK || level.getBlockState(pos) != state) return;
        BlockPos origin = origin(pos, state);
        boolean complete = removeAssembly(level, pos, state);
        if (complete && dropFromExplosion(explosion)
                && (explosion.getBlockInteraction() != Explosion.BlockInteraction.DESTROY_WITH_DECAY
                    || explosion.radius() <= 0 || level.getRandom().nextFloat() <= 1.0F / explosion.radius())) {
            onHit.accept(stackForState(state), origin);
        }
        wasExploded(level, pos, explosion);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (!isMutating(level, origin(pos, state))) removeAssembly(level, pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide() || isMutating(level, origin(pos, state))) return;
        if (oldState.is(this) && oldState != state) removeAssembly(level, pos, oldState);
        if (!complete(level, origin(pos, state), state.getValue(FACING))) removeAssembly(level, pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                   @Nullable Orientation orientation, boolean movedByPiston) {
        BlockPos origin = origin(pos, state);
        if (!level.isClientSide() && !isMutating(level, origin)
                && !complete(level, origin, state.getValue(FACING))) removeAssembly(level, pos, state);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction side, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        BlockPos origin = origin(pos, state);
        return !isMutating(level, origin) && !complete(level, origin, state.getValue(FACING))
                ? Blocks.AIR.defaultBlockState() : state;
    }

    private boolean complete(LevelReader level, BlockPos origin, Direction facing) {
        for (int part = 0; part < definition.parts().size(); part++) {
            BlockPos target = origin.offset(partOffset(part, facing));
            // An unloaded neighboring chunk is unknown, not an orphan.
            if (level.hasChunkAt(target) && !matches(level.getBlockState(target), part, facing)) return false;
        }
        return true;
    }

    private boolean fullyPresent(Level level, BlockPos origin, Direction facing) {
        for (int part = 0; part < definition.parts().size(); part++) {
            if (!matches(level.getBlockState(origin.offset(partOffset(part, facing))), part, facing)) return false;
        }
        return true;
    }

    private boolean matches(BlockState state, int part, Direction facing) {
        return state.is(this) && state.getValue(PART) == part && state.getValue(FACING) == facing;
    }

    private boolean removeAssembly(Level level, BlockPos brokenPos, BlockState state) {
        BlockPos origin = origin(brokenPos, state);
        Direction facing = state.getValue(FACING);
        boolean complete = state.getValue(PART) < definition.parts().size() && fullyPresent(level, origin, facing);
        return mutate(level, origin, () -> {
            boolean removed = false;
            for (int part = 0; part < definition.parts().size(); part++) {
                BlockPos target = origin.offset(partOffset(part, facing));
                if (matches(level.getBlockState(target), part, facing)) {
                    removed |= level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
            if (state.getValue(PART) >= definition.parts().size() && level.getBlockState(brokenPos) == state) {
                level.setBlock(brokenPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
            return complete && removed;
        });
    }

    <T> T mutate(Level level, BlockPos origin, Supplier<T> action) {
        Map<Mutation, Integer> mutations = MUTATIONS.get();
        Mutation key = new Mutation(this, level, origin.immutable());
        mutations.merge(key, 1, Integer::sum);
        try {
            return action.get();
        } finally {
            if (mutations.get(key) == 1) mutations.remove(key);
            else mutations.computeIfPresent(key, (ignored, count) -> count - 1);
            if (mutations.isEmpty()) MUTATIONS.remove();
        }
    }

    private boolean isMutating(LevelReader level, BlockPos origin) {
        return MUTATIONS.get().containsKey(new Mutation(this, level, origin));
    }

    /** Only opt-in subclasses copy a visual variant; ordinary furniture has no extra states. */
    protected BlockState copyVisualState(BlockState source, BlockState target) { return target; }

    /** A whole-object drop; visual variants may attach their ordinary BLOCK_STATE component. */
    public ItemStack stackForState(BlockState state) { return new ItemStack(this); }

    private BlockState state(int part, Direction facing) {
        return defaultBlockState().setValue(FACING, facing).setValue(PART, part);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return outlines[state.getValue(PART)][facingIndex(state.getValue(FACING))];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return collisions[state.getValue(PART)][facingIndex(state.getValue(FACING))];
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        // Passable grass and other collision-free decorations must not obstruct mob paths.
        return type != PathComputationType.WATER
                && collisions[state.getValue(PART)][facingIndex(state.getValue(FACING))].isEmpty();
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) return state;
        Direction before = state.getValue(FACING);
        Direction after = mirror.getRotation(before).rotate(before);
        int part = state.getValue(PART);
        if (part >= definition.parts().size()) return state.setValue(FACING, after);
        // Reflection can reverse rows and columns in a compound footprint as well as its facing.
        List<BlockPos> reflected = java.util.stream.IntStream.range(0, definition.parts().size())
                .mapToObj(i -> reflect(partOffset(i, before), mirror)).toList();
        for (int start = 0; start < definition.parts().size(); start++) {
            BlockPos translation = reflected.getFirst().subtract(partOffset(start, after));
            int remapped = -1;
            boolean valid = true;
            for (int i = 0; i < reflected.size(); i++) {
                int match = -1;
                for (int j = 0; j < reflected.size(); j++) {
                    if (reflected.get(i).equals(partOffset(j, after).offset(translation))) match = j;
                }
                if (match < 0) valid = false;
                if (i == part) remapped = match;
            }
            if (valid) return state.setValue(FACING, after).setValue(PART, remapped);
        }
        return state.setValue(FACING, after);
    }

    private static BlockPos reflect(BlockPos offset, Mirror mirror) {
        return mirror == Mirror.FRONT_BACK ? new BlockPos(-offset.getX(), offset.getY(), offset.getZ())
                : new BlockPos(offset.getX(), offset.getY(), -offset.getZ());
    }

    public static BlockPos rotateOffset(BlockPos offset, Direction facing) {
        return switch (facing) {
            case EAST -> new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
            case SOUTH -> new BlockPos(-offset.getX(), offset.getY(), -offset.getZ());
            case WEST -> new BlockPos(offset.getZ(), offset.getY(), -offset.getX());
            default -> offset;
        };
    }

    private static int facingIndex(Direction facing) {
        return switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }

    private static VoxelShape rotatedBox(FurnitureCatalog.Box box, Direction facing) {
        double minX = box.minX(), minZ = box.minZ(), maxX = box.maxX(), maxZ = box.maxZ();
        for (int step = 0; step < facingIndex(facing); step++) {
            double nextMinX = 16 - maxZ, nextMaxX = 16 - minZ;
            minZ = minX; maxZ = maxX; minX = nextMinX; maxX = nextMaxX;
        }
        return Block.box(minX, box.minY(), minZ, maxX, box.maxY(), maxZ);
    }
}
