package com.dark.winter.placement;

import com.dark.winter.block.FloorSegmentBlock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Resolves a floor module's physical footprint before choosing its storage cell. */
final class FloorPlacement {
    private static final double EPSILON = 1.0e-6;
    private static final double SNAP_DISTANCE = .5;

    record Fit(Vec3 origin, int spanX, int spanZ, SlabType type) {
        AABB bounds() {
            double bottom = origin.y + (type == SlabType.TOP ? .5 : 0);
            double top = origin.y + (type == SlabType.BOTTOM ? .5 : 1);
            return new AABB(origin.x, bottom, origin.z, origin.x + spanX * .25, top, origin.z + spanZ * .25);
        }

        boolean sameSpan(BlockState state) {
            return state.getValue(FloorSegmentBlock.SPAN_X) == spanX
                    && state.getValue(FloorSegmentBlock.SPAN_Z) == spanZ;
        }

        BlockState apply(BlockState state) {
            return state.setValue(FloorSegmentBlock.SPAN_X, spanX).setValue(FloorSegmentBlock.SPAN_Z, spanZ)
                    .setValue(SlabBlock.TYPE, state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? SlabType.DOUBLE : type);
        }
    }

    private FloorPlacement() {}

    static Fit resolve(Level level, BlockPos support, BlockState supporting, Direction face, Vec3 point, Vec3 origin) {
        int spanX = FloorSegmentBlock.FULL_SPAN, spanZ = FloorSegmentBlock.FULL_SPAN;
        SlabType type = face == Direction.DOWN || (face.getAxis().isHorizontal() && point.y - origin.y > .5)
                ? SlabType.TOP : SlabType.BOTTOM;
        if (supporting.getBlock() instanceof FloorSegmentBlock) {
            if (face.getAxis() != Direction.Axis.X) spanX = supporting.getValue(FloorSegmentBlock.SPAN_X);
            if (face.getAxis() != Direction.Axis.Z) spanZ = supporting.getValue(FloorSegmentBlock.SPAN_Z);
            SlabType course = supporting.getValue(SlabBlock.TYPE);
            if (face.getAxis().isHorizontal() && course != SlabType.DOUBLE) type = course;
        }
        Fit initial = new Fit(origin, spanX, spanZ, type);
        List<AABB> adjacent = nearbyFloors(level, support, initial.bounds());
        return face.getAxis().isHorizontal() ? fitBetween(initial, face, adjacent)
                : alignCourse(level, initial, point, face, adjacent);
    }

    private static List<AABB> nearbyFloors(Level level, BlockPos support, AABB bounds) {
        AABB search = bounds.inflate(SNAP_DISTANCE + EPSILON, EPSILON, SNAP_DISTANCE + EPSILON);
        var seen = new HashSet<BlockPos>();
        seen.add(support);
        var floors = new ArrayList<AABB>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(search.minX, search.minY, search.minZ),
                BlockPos.containing(search.maxX, search.maxY, search.maxZ))) {
            collectFloor(level, cell.immutable(), search, seen, floors);
            for (BlockPos owner : AnchorRuntime.logicalOwners(level, cell)) {
                collectFloor(level, owner, search, seen, floors);
            }
        }
        return floors;
    }

    private static void collectFloor(Level level, BlockPos pos, AABB search, HashSet<BlockPos> seen, List<AABB> floors) {
        if (!seen.add(pos) || !level.hasChunkAt(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FloorSegmentBlock) {
            AABB physical = state.getShape(level, pos).bounds().move(pos);
            if (physical.intersects(search)) floors.add(physical);
        }
    }

    private static Fit fitBetween(Fit initial, Direction face, List<AABB> floors) {
        Direction.Axis axis = face.getAxis();
        Direction.Axis across = face.getClockWise().getAxis();
        boolean forward = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        AABB bounds = initial.bounds();
        double length = 1;
        for (AABB floor : floors) {
            if (overlap(bounds, floor, across) <= EPSILON || overlap(bounds, floor, Direction.Axis.Y) <= EPSILON) continue;
            double gap = forward ? floor.min(axis) - bounds.min(axis) : bounds.max(axis) - floor.max(axis);
            if (gap > EPSILON) length = Math.min(length, gap);
        }
        int quarters = (int) Math.round(length * FloorSegmentBlock.FULL_SPAN);
        if (quarters < 1 || quarters >= FloorSegmentBlock.FULL_SPAN || Math.abs(quarters * .25 - length) > EPSILON) {
            return initial;
        }
        // Keep the clicked edge fixed when the free span is shorter than a full module.
        Vec3 origin = forward ? initial.origin : initial.origin.add(-face.getStepX() * (1 - length), 0,
                -face.getStepZ() * (1 - length));
        return axis == Direction.Axis.X ? new Fit(origin, quarters, initial.spanZ, initial.type)
                : new Fit(origin, initial.spanX, quarters, initial.type);
    }

    private static Fit alignCourse(Level level, Fit initial, Vec3 point, Direction face, List<AABB> floors) {
        AABB bounds = initial.bounds();
        Fit best = initial;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AABB floor : floors) {
            double elevation = face == Direction.UP ? floor.maxY - bounds.maxY : floor.minY - bounds.minY;
            if (Math.abs(elevation) > EPSILON) continue;
            for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
                Direction.Axis across = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
                if (overlap(bounds, floor, across) <= EPSILON) continue;
                double along = floor.getCenter().get(axis) >= bounds.getCenter().get(axis)
                        ? floor.min(axis) - bounds.max(axis) : floor.max(axis) - bounds.min(axis);
                double transverse = floor.min(across) - bounds.min(across);
                if (Math.abs(along) > SNAP_DISTANCE + EPSILON || Math.abs(transverse) > SNAP_DISTANCE + EPSILON) continue;
                double dx = axis == Direction.Axis.X ? along : transverse;
                double dz = axis == Direction.Axis.X ? transverse : along;
                double distance = dx * dx + dz * dz;
                AABB moved = bounds.move(dx, 0, dz);
                // The click must still lie on the mounted piece; genuine steps keep their elevation.
                if (point.x < moved.minX - EPSILON || point.x > moved.maxX + EPSILON
                        || point.z < moved.minZ - EPSILON || point.z > moved.maxZ + EPSILON) continue;
                if (distance < EPSILON * EPSILON) return initial;
                if (distance > bestDistance || distance == bestDistance && !before(moved, best.bounds())) continue;
                if (!clear(level, moved)) continue;
                best = new Fit(initial.origin.add(dx, 0, dz), initial.spanX, initial.spanZ, initial.type);
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double overlap(AABB a, AABB b, Direction.Axis axis) {
        return Math.min(a.max(axis), b.max(axis)) - Math.max(a.min(axis), b.min(axis));
    }

    private static boolean before(AABB a, AABB b) {
        return a.minX < b.minX || a.minX == b.minX && a.minZ < b.minZ;
    }

    private static boolean clear(Level level, AABB bounds) {
        var candidate = Shapes.create(bounds);
        for (var collision : level.getBlockCollisions(null, bounds)) {
            if (Shapes.joinIsNotEmpty(candidate, collision, BooleanOp.AND)) return false;
        }
        return true;
    }
}
