// Defines wall geometry and placement using the shared preview snapping rules.
package com.dark.winter.block;

import com.dark.winter.block.entity.WallModuleBlockEntity;
import com.dark.winter.component.WallBuildManager;
import com.dark.winter.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import com.dark.winter.component.WallShape;

import java.util.List;

public class WallSegmentBlock extends HorizontalDirectionalBlock implements EntityBlock {

    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 4.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0);
    private static final VoxelShape WEST_SHAPE  = Block.box(0.0, 0.0, 0.0, 4.0, 16.0, 16.0);
    private static final VoxelShape EAST_SHAPE  = Block.box(12.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public WallSegmentBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WallModuleBlockEntity(pos, state);
    }

    private static WallShape resolveActiveShape(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player != null) {
            if (context.getLevel().isClientSide()) {
                return WallBuildManager.getClientShape();
            }
            return WallBuildManager.getPlayerShape(player.getUUID());
        }
        return context.getItemInHand().getOrDefault(ModDataComponents.WALL_SHAPE, WallShape.WALL_3X3);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        Direction facing = resolvePlacementFacing(context);
        WallShape shape = resolveActiveShape(context);
        BlockState placedState = defaultBlockState().setValue(FACING, facing);

        for (BlockPos targetPos : shape.getPositions(origin, facing)) {
            if (!level.isInWorldBounds(targetPos) || !level.getBlockState(targetPos).canBeReplaced(context)
                    || !level.isUnobstructed(placedState, targetPos, CollisionContext.placementContext(context.getPlayer()))) {
                return null;
            }
        }
        return placedState;
    }

    /** The preview uses exactly the same snapping decision as server placement. */
    public static Direction resolvePlacementFacing(BlockPlaceContext context) {
        return BuildingAlignment.resolve(context, resolveActiveShape(context), context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;

        Direction facing = state.getValue(FACING);
        WallShape shape = (placer instanceof Player player)
                ? WallBuildManager.getPlayerShape(player.getUUID())
                : stack.getOrDefault(ModDataComponents.WALL_SHAPE, WallShape.WALL_3X3);

        List<BlockPos> allPositions = shape.getPositions(pos, facing);

        // Сначала расставляем все блоки стены
        for (BlockPos targetPos : allPositions) {
            if (!targetPos.equals(pos)) {
                level.setBlock(targetPos, state, Block.UPDATE_ALL);
            }
        }

        // Затем в каждый BlockEntity модуля прописываем центр (pos) и размер (shape)
        for (BlockPos targetPos : allPositions) {
            if (level.getBlockEntity(targetPos) instanceof WallModuleBlockEntity be) {
                be.setup(pos, shape);
            }
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            destroyWholeModule(level, pos, state, player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    public void destroyWholeModule(Level level, BlockPos brokenPos, BlockState state, @Nullable Player player) {
        if (!(level.getBlockEntity(brokenPos) instanceof WallModuleBlockEntity be)) {
            return;
        }

        BlockPos masterPos = be.getMasterPos();
        WallShape shape = be.getShape();
        Direction facing = state.getValue(FACING);

        if (level.getBlockEntity(masterPos) instanceof WallModuleBlockEntity masterBe) {
            shape = masterBe.getShape();
        }

        List<BlockPos> modulePositions = shape.getPositions(masterPos, facing);

        // В режиме выживания выдаем ровно 1 блок за весь модуль
        if (player != null && !player.isCreative()) {
            ItemStack drop = new ItemStack(this.asItem());
            drop.set(ModDataComponents.WALL_SHAPE, shape);
            Block.popResource(level, masterPos, drop);
        }

        // Сносим каждый сегмент модуля (включая соседние)
        for (BlockPos targetPos : modulePositions) {
            if (level.getBlockState(targetPos).is(this)
                    && level.getBlockEntity(targetPos) instanceof WallModuleBlockEntity member
                    && member.getMasterPos().equals(masterPos)) {
                level.removeBlockEntity(targetPos);
                level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST  -> WEST_SHAPE;
            case EAST  -> EAST_SHAPE;
            default    -> NORTH_SHAPE;
        };
    }

    public static VoxelShape getShapeForFacing(Direction facing) {
        return switch (facing) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST  -> WEST_SHAPE;
            case EAST  -> EAST_SHAPE;
            default    -> NORTH_SHAPE;
        };
    }
}