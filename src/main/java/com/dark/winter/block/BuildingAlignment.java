// Shared placement geometry for walls, windows and vanilla doors.
package com.dark.winter.block;

import com.dark.winter.component.WallShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Shared placement geometry for walls, windows and vanilla doors. */
public final class BuildingAlignment {
    private BuildingAlignment() {}

    public static Direction resolve(BlockPlaceContext context, WallShape shape, Direction preferred) {
        // Sneaking offers an explicit way to choose a new plane instead of snapping.
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) return preferred;
        Direction[] candidates = {preferred, preferred.getOpposite(), preferred.getClockWise(), preferred.getCounterClockWise()};
        Direction best = preferred;
        double bestScore = 0;
        for (Direction candidate : candidates) {
            double score = contactScore(context, shape, candidate);
            if (score > bestScore + 0.000001) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static double contactScore(BlockPlaceContext context, WallShape shape, Direction facing) {
        var positions = shape.getPositions(context.getClickedPos(), facing);
        AABB own = WallSegmentBlock.getShapeForFacing(facing).bounds();
        double score = 0;
        for (BlockPos pos : positions) {
            if (!context.getLevel().isInWorldBounds(pos) || !context.getLevel().getBlockState(pos).canBeReplaced(context)) return -1;
            for (Direction side : Direction.values()) {
                BlockPos neighborPos = pos.relative(side);
                if (positions.contains(neighborPos)) continue;
                BlockState neighbor = context.getLevel().getBlockState(neighborPos);
                Direction plane = planeFacing(neighbor);
                if (plane == null) continue;
                // Doors keep their closed plane while open: opening never moves adjoining walls.
                AABB adjacent = WallSegmentBlock.getShapeForFacing(plane).bounds()
                        .move(side.getStepX(), side.getStepY(), side.getStepZ());
                double planePreference = plane == facing ? 1.25 : 1;
                score += touchingArea(own, adjacent, side) * planePreference
                        * (neighbor.getBlock() instanceof DoorBlock ? 2 : 1);
            }
        }
        return score;
    }

    public static BlockPlaceContext withDirection(BlockPlaceContext context, Direction direction) {
        return new BlockPlaceContext(context) {
            @Override
            public Direction getHorizontalDirection() {
                return direction;
            }
        };
    }

    public static Direction planeFacing(BlockState state) {
        if (state.getBlock() instanceof WallSegmentBlock || state.getBlock() instanceof FrozenWindowBlock) {
            return state.getValue(WallSegmentBlock.FACING);
        }
        // Vanilla NORTH doors occupy z=13..16; NORTH wall panels occupy z=0..4.
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.FACING).getOpposite();
        return null;
    }

    private static double touchingArea(AABB a, AABB b, Direction side) {
        double x = Math.max(0, Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX));
        double y = Math.max(0, Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY));
        double z = Math.max(0, Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ));
        return switch (side) {
            case EAST -> Math.abs(a.maxX - b.minX) < 0.000001 ? y * z : 0;
            case WEST -> Math.abs(a.minX - b.maxX) < 0.000001 ? y * z : 0;
            case UP -> Math.abs(a.maxY - b.minY) < 0.000001 ? x * z : 0;
            case DOWN -> Math.abs(a.minY - b.maxY) < 0.000001 ? x * z : 0;
            case SOUTH -> Math.abs(a.maxZ - b.minZ) < 0.000001 ? x * y : 0;
            case NORTH -> Math.abs(a.minZ - b.maxZ) < 0.000001 ? x * y : 0;
        };
    }
}
