package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Lines up freestanding pieces with the edge and orientation of nearby furniture. */
final class FurnitureSnap {
    private static final double SNAP_RANGE = .62;
    private static final double SEAM = .0025;
    private static final double SAME_FLOOR = .028;

    record Candidate(Vec3 origin, double yaw, SurfaceGeometry.Hit floor) {}

    private FurnitureSnap() {}

    static List<Candidate> candidates(Level level, SurfaceGeometry.Hit floor, FurnitureBlock held,
                                      BlockState state, Vec3 origin, double yaw) {
        if (!FurnitureFloorPlacement.isFloorFurniture(held) || InteriorPlacement.rug(held)) return List.of();
        AABB local = SurfaceGeometry.assemblyBounds(level, floor.owner(), state);
        Vec3 localCenter = local.getCenter();
        Vec3 center = FurnitureTransform.point(localCenter, Vec3.ZERO, yaw).add(origin);
        // The circumradius covers the corners for every possible yaw.
        double radius = .5 * Math.hypot(local.getXsize(), local.getZsize()) + SNAP_RANGE;
        var owners = new LinkedHashSet<BlockPos>();
        Vec3 low = new Vec3(center.x - radius, floor.point().y - SAME_FLOOR, center.z - radius);
        Vec3 high = new Vec3(center.x + radius, floor.point().y + .5, center.z + radius);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(low), BlockPos.containing(high))) {
            owners.add(cell.immutable());
            owners.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        var visited = new LinkedHashSet<BlockPos>();
        var fitted = new ArrayList<Candidate>();
        for (BlockPos owner : owners) {
            if (!level.hasChunkAt(owner)) continue;
            BlockState otherState = level.getBlockState(owner);
            if (!(otherState.getBlock() instanceof FurnitureBlock other)
                    || !FurnitureFloorPlacement.isFloorFurniture(other) || InteriorPlacement.rug(other)
                    || InteriorPlacement.floor(level, owner, otherState) || RoofPlacement.isRoof(other)) continue;
            BlockPos root = other.origin(owner, otherState);
            if (!visited.add(root) || !level.hasChunkAt(root)) continue;
            BlockState rootState = level.getBlockState(root);
            if (!rootState.is(other)) continue;
            AABB bounds = SurfaceGeometry.assemblyBounds(level, root, rootState);
            Vec3 otherOrigin = Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root));
            if (Math.abs(otherOrigin.y + bounds.minY - floor.point().y) > SAME_FLOOR) continue;
            double angle = AnchorRuntime.rotation(level, root);
            Vec3 otherCenter = FurnitureTransform.point(bounds.getCenter(), Vec3.ZERO, angle).add(otherOrigin);
            Direction neighborFacing = rootState.getValue(FurnitureBlock.FACING);
            Direction heldFacing = state.getValue(FurnitureBlock.FACING);
            double relative = Math.toRadians(neighborFacing.toYRot() - heldFacing.toYRot());
            Vec3 delta = FurnitureTransform.point(center.subtract(otherCenter), Vec3.ZERO, -angle);
            double alignedAngle = Math.IEEEremainder(angle + relative, Math.TAU);
            Vec3 alignedCenter = FurnitureTransform.point(localCenter, Vec3.ZERO, alignedAngle);
            double ownX = .5 * (heldFacing.getAxis() == neighborFacing.getAxis() ? local.getXsize() : local.getZsize());
            double ownZ = .5 * (heldFacing.getAxis() == neighborFacing.getAxis() ? local.getZsize() : local.getXsize());
            double halfX = .5 * bounds.getXsize();
            double halfZ = .5 * bounds.getZsize();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                boolean onX = side.getAxis() == Direction.Axis.X;
                int sign = side.getAxisDirection().getStep();
                double normal = onX ? delta.x : delta.z;
                double tangent = onX ? delta.z : delta.x;
                double edge = sign * ((onX ? halfX + ownX : halfZ + ownZ) + SEAM);
                if (normal * sign < (onX ? halfX : halfZ) - .05 || Math.abs(edge - normal) > SNAP_RANGE) continue;
                double ourSide = onX ? ownZ : ownX;
                double theirSide = onX ? halfZ : halfX;
                if (Math.abs(tangent) > ourSide + theirSide - .06) continue;
                double flush = closestAlignment(tangent, theirSide - ourSide);
                Vec3 adjustment = onX ? new Vec3(edge - normal, 0, flush - tangent)
                        : new Vec3(flush - tangent, 0, edge - normal);
                if (adjustment.horizontalDistanceSqr() > SNAP_RANGE * SNAP_RANGE) continue;
                Vec3 shift = FurnitureTransform.point(adjustment, Vec3.ZERO, angle);
                Vec3 supportPoint = new Vec3(center.x + shift.x, floor.point().y, center.z + shift.z);
                SurfaceGeometry.Hit support = InteriorPlacement.floorBelow(level, supportPoint);
                if (support == null || Math.abs(support.point().y - floor.point().y) > .003) continue;
                fitted.add(new Candidate(center.add(shift).subtract(alignedCenter), alignedAngle, support));
            }
        }
        fitted.sort(Comparator.comparingDouble(candidate -> candidate.floor().point()
                .distanceToSqr(new Vec3(center.x, floor.point().y, center.z))
                + .02 * Math.abs(Math.IEEEremainder(candidate.yaw() - yaw, Math.TAU))));
        return List.copyOf(fitted);
    }

    private static double closestAlignment(double current, double edge) {
        double side = Math.copySign(Math.abs(edge), current);
        return Math.abs(current - side) < Math.abs(current) ? side : 0;
    }
}
