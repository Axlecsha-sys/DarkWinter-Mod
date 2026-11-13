package com.dark.winter.placement;

import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.component.WallShape;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Storage and overlap rules for walls snapped to a floor or an upper wall course. */
final class WallCornerPlacement {
    private static final double EPSILON = 1.0e-6;

    private WallCornerPlacement() {}

    static BlockPos storage(BlockPlaceContext context, WallShape shape, Direction facing, BlockPos base,
                            SurfaceGeometry.Hit contact) {
        var level = context.getLevel();
        Vec3 supportOffset = AnchorRuntime.offset(level, contact.owner());
        Vec3 physical = new Vec3(base.getX() + supportOffset.x, contact.point().y, base.getZ() + supportOffset.z);
        // Search around the physical course so stored offsets do not accumulate as walls are stacked.
        return AnchorStorage.find(physical, root -> canStore(context, shape, facing, root, root.equals(base)));
    }

    private static boolean canStore(BlockPlaceContext context, WallShape shape, Direction facing,
                                    BlockPos root, boolean original) {
        var level = context.getLevel();
        var player = context.getPlayer();
        for (BlockPos member : shape.getPositions(root, facing)) {
            if (!level.hasChunkAt(member) || !level.isInWorldBounds(member)
                    || !level.getWorldBorder().isWithinBounds(member)) return false;
            var state = level.getBlockState(member);
            if (original ? !state.canBeReplaced(context) : !state.isAir()) return false;
            if (player != null && (!level.mayInteract(player, member)
                    || !player.mayUseItemAt(member, Direction.UP, context.getItemInHand()))) return false;
        }
        return true;
    }

    static boolean joins(Level level, Set<BlockPos> newMembers, BlockPos member, Direction facing, AABB wall,
                         BlockPos other, Direction otherFacing, AABB adjoining) {
        if (facing.getAxis() == otherFacing.getAxis()) return false;
        Direction end = endAtOverlap(wall, adjoining, facing.getClockWise());
        if (end == null || newMembers.contains(member.relative(end))) return false;
        Direction otherEnd = endAtOverlap(adjoining, wall, otherFacing.getClockWise());
        return otherEnd != null && !continues(level, other, adjoining, otherFacing, otherEnd,
                Math.max(wall.minY, adjoining.minY), Math.min(wall.maxY, adjoining.maxY));
    }

    private static Direction endAtOverlap(AABB wall, AABB other, Direction along) {
        Direction.Axis axis = along.getAxis();
        double low = Math.max(wall.min(axis), other.min(axis));
        double high = Math.min(wall.max(axis), other.max(axis));
        if (high - low > .25 + EPSILON) return null;
        Direction positive = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        if (Math.abs(low - wall.min(axis)) <= EPSILON) return positive.getOpposite();
        if (Math.abs(high - wall.max(axis)) <= EPSILON) return positive;
        return null;
    }

    private static boolean continues(Level level, BlockPos owner, AABB wall, Direction facing, Direction end,
                                     double bottom, double top) {
        // Look just past the end over the whole overlapping height, including displaced owners.
        Vec3 center = wall.getCenter();
        double distance = (end.getAxis() == Direction.Axis.X ? wall.getXsize() : wall.getZsize()) / 2 + EPSILON;
        Vec3 point = center.add(end.getStepX() * distance, 0, end.getStepZ() * distance);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(point.x, bottom, point.z),
                BlockPos.containing(point.x, top, point.z))) {
            if (!level.hasChunkAt(cell)) return true;
            if (covers(level, owner, cell, facing, point, bottom, top)) return true;
            for (BlockPos candidate : AnchorRuntime.logicalOwners(level, cell)) {
                if (covers(level, owner, candidate, facing, point, bottom, top)) return true;
            }
        }
        return false;
    }

    private static boolean covers(Level level, BlockPos owner, BlockPos candidate, Direction facing, Vec3 point,
                                  double bottom, double top) {
        if (candidate.equals(owner) || !level.hasChunkAt(candidate)) return false;
        var state = level.getBlockState(candidate);
        if (!(state.getBlock() instanceof WallSegmentBlock)
                || state.getValue(WallSegmentBlock.FACING).getAxis() != facing.getAxis()) return false;
        var shape = state.getCollisionShape(level, candidate, CollisionContext.empty());
        if (shape.isEmpty()) return false;
        AABB bounds = shape.bounds().move(candidate);
        return point.x >= bounds.minX && point.x < bounds.maxX && point.z >= bounds.minZ && point.z < bounds.maxZ
                && Math.min(top, bounds.maxY) - Math.max(bottom, bounds.minY) > EPSILON;
    }
}
