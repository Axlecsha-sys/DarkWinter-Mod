package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.WallSegmentBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fits the ends of wooden shelf boards along walls or across an inside corner. */
final class ShelfPlacement {
    private static final double SEAM = .0005;
    private static final double SNAP = .86;
    private static final double CORNER_REACH = .58;
    private static final double SAME_COURSE = .025;

    record Join(Vec3 origin, SurfaceGeometry.Hit wall) {}

    private ShelfPlacement() {}

    static boolean isWoodenShelf(Block block) {
        return block instanceof FurnitureBlock furniture && switch (furniture.definition().id()) {
            case "interior_long_wall_shelf", "interior_wall_shelf", "interior_bookshelf" -> true;
            default -> false;
        };
    }

    static List<Join> joins(Level level, Direction facing, Vec3 origin, AABB local, BlockPos clickedWall) {
        AABB board = local.move(origin);
        Direction.Axis width = facing.getClockWise().getAxis();
        Direction.Axis depth = facing.getAxis();
        AABB search = board.inflate(SNAP, SAME_COURSE, SNAP);
        var owners = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(search.minX, search.minY, search.minZ),
                BlockPos.containing(search.maxX, search.maxY, search.maxZ))) {
            owners.add(cell.immutable());
            owners.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        var seen = new LinkedHashSet<BlockPos>();
        var fitted = new ArrayList<Join>();
        for (BlockPos owner : owners) {
            if (!level.hasChunkAt(owner)) continue;
            var state = level.getBlockState(owner);
            if (!isWoodenShelf(state.getBlock())) continue;
            var shelf = (FurnitureBlock) state.getBlock();
            BlockPos root = shelf.origin(owner, state);
            if (!seen.add(root)) continue;
            Direction otherFacing = state.getValue(FurnitureBlock.FACING);
            if (otherFacing == facing.getOpposite()) continue;
            Vec3 otherOrigin = Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root));
            if (Math.abs(otherOrigin.y - origin.y) > SAME_COURSE) continue;
            AABB other = SurfaceGeometry.assemblyBounds(level, root, state.setValue(FurnitureBlock.PART, 0)).move(otherOrigin);
            double rear = edge(board, facing.getOpposite());
            if (otherFacing == facing && Math.abs(rear - edge(other, facing.getOpposite())) > SAME_COURSE) continue;
            if (otherFacing != facing && (Math.min(board.max(depth), other.max(depth))
                    - Math.max(board.min(depth), other.min(depth)) < .015)) continue;

            double shift = board.getCenter().get(width) < other.getCenter().get(width)
                    ? other.min(width) - board.max(width) - SEAM
                    : other.max(width) - board.min(width) + SEAM;
            if (Math.abs(shift) > SNAP) continue;
            addJoin(level, facing, origin, board, width, shift, clickedWall, fitted);
        }
        fitted.sort(Comparator.comparingDouble(join -> join.origin().distanceToSqr(origin)));

        // Finish a first run at a perpendicular wall, ready for the return shelf.
        for (Direction end : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
            Vec3 atEnd = coordinate(board.getCenter(), width, edge(board, end));
            Direction inward = end.getOpposite();
            SurfaceGeometry.Hit corner = wallHit(level, atEnd, inward, .03, CORNER_REACH, Set.of());
            if (corner == null) continue;
            double sign = inward.getAxisDirection().getStep();
            double shift = corner.point().get(width) + sign * SEAM - atEnd.get(width);
            addJoin(level, facing, origin, board, width, shift, clickedWall, fitted);
        }
        return fitted;
    }

    private static void addJoin(Level level, Direction facing, Vec3 origin, AABB board, Direction.Axis width,
                                double shift, BlockPos clickedWall, List<Join> fitted) {
        Vec3 delta = coordinate(Vec3.ZERO, width, shift);
        Direction.Axis depth = facing.getAxis();
        double rear = edge(board, facing.getOpposite());
        Vec3 center = coordinate(board.getCenter().add(delta), depth, rear);
        // Keep support on the broad face of a timber course instead of its bevel.
        SurfaceGeometry.Hit wall = wallHit(level, new Vec3(center.x, origin.y + .5, center.z),
                facing, .03, .04, Set.of(clickedWall));
        if (wall == null || Math.abs(wall.point().get(depth) - rear) > .012) return;
        fitted.add(new Join(origin.add(delta), wall));
    }

    private static SurfaceGeometry.Hit wallHit(Level level, Vec3 point, Direction facing, double outside, double inside,
                                               Set<BlockPos> preferred) {
        Vec3 normal = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        Vec3 from = point.add(normal.scale(outside));
        Vec3 to = point.subtract(normal.scale(inside));
        var owners = new LinkedHashSet<>(preferred);
        var range = new AABB(from, to).inflate(.002);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(range.minX, range.minY, range.minZ),
                BlockPos.containing(range.maxX, range.maxY, range.maxZ))) {
            owners.add(cell.immutable());
            owners.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        SurfaceGeometry.Hit best = null;
        for (BlockPos owner : owners) {
            if (!level.hasChunkAt(owner)) continue;
            var state = level.getBlockState(owner);
            if (!(state.getBlock() instanceof WallSegmentBlock) && !state.isFaceSturdy(level, owner, facing)) continue;
            var hit = SurfaceGeometry.hit(level, owner, from, to);
            if (hit != null && hit.face() == facing && (best == null || hit.distanceSquared() < best.distanceSquared()))
                best = hit;
        }
        return best;
    }

    private static Vec3 coordinate(Vec3 point, Direction.Axis axis, double value) {
        return axis == Direction.Axis.X ? new Vec3(value, point.y, point.z) : new Vec3(point.x, point.y, value);
    }

    private static double edge(AABB box, Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? box.max(direction.getAxis()) : box.min(direction.getAxis());
    }
}
