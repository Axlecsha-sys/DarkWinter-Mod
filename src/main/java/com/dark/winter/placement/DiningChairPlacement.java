package com.dark.winter.placement;

import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.block.FenceGateGeometry;
import com.dark.winter.block.FurnitureBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Center a dining chair on the edge of a nearby table picked by the floor click. */
public final class DiningChairPlacement {
    private static final double MAX_GAP = .9;
    private static final double SIDE_TOLERANCE = .18;
    private static final double FLOOR_TOLERANCE = .012;
    private static final double FRONT_GAP = .025;
    private static final double SLIDE_CLEARANCE = .002;

    record Snap(Vec3 origin, Direction facing, SurfaceGeometry.Hit floor) {}

    private DiningChairPlacement() {}

    static boolean isChair(Block block) {
        return block instanceof FurnitureBlock furniture && furniture.definition().id().equals("interior_spindle_chair");
    }

    static List<Snap> candidates(Level level, SurfaceGeometry.Hit clickedFloor, FurnitureBlock chair) {
        Vec3 aim = clickedFloor.point();
        var tables = new LinkedHashSet<BlockPos>();
        for (BlockPos physical : BlockPos.betweenClosed(BlockPos.containing(aim.add(-1.3, .006, -1.3)),
                BlockPos.containing(aim.add(1.3, .006, 1.3)))) {
            collectTable(level, physical, tables);
            for (BlockPos owner : AnchorRuntime.logicalOwners(level, physical)) collectTable(level, owner, tables);
        }

        var candidates = new ArrayList<Snap>();
        for (BlockPos root : tables) {
            // The generic snap path aligns with angled tables in their own local axes.
            if (AnchorRuntime.rotation(level, root) != 0) continue;
            BlockState state = level.getBlockState(root);
            AABB table = SurfaceGeometry.assemblyBounds(level, root, state)
                    .move(Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root)));
            if (Math.abs(table.minY - aim.y) > FLOOR_TOLERANCE) continue;
            Direction side = nearestSide(table, aim);
            if (side == null) continue;
            Snap snap = fitChair(level, chair, root, table, side);
            if (snap != null) candidates.add(snap);
        }
        candidates.sort(Comparator.comparingDouble(snap -> snap.floor().point().distanceToSqr(aim)));
        return candidates;
    }

    public static boolean hasTable(Level level, BlockPos pos, BlockState state) {
        return tuckedAtPlacement(level, pos, state) != null;
    }

    public static @Nullable Boolean tuckedAtPlacement(Level level, BlockPos pos, BlockState state) {
        var facing = state.getValue(FurnitureBlock.FACING);
        Vec3 base = Vec3.atLowerCornerOf(pos).add(AnchorRuntime.offset(level, pos));
        double yaw = AnchorRuntime.rotation(level, pos);
        var chair = SurfaceGeometry.assemblyBounds(level, pos, state, yaw).move(base);
        var localChair = SurfaceGeometry.bounds(level, pos, state);
        Vec3 forward = FurnitureTransform.point(facing.getUnitVec3(), Vec3.ZERO, yaw);
        var search = chair.expandTowards(forward.scale(.80));
        var tables = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(search.minX, chair.minY, search.minZ),
                BlockPos.containing(search.maxX, chair.minY + .5, search.maxZ))) {
            collectTable(level, cell, tables);
            for (BlockPos owner : AnchorRuntime.logicalOwners(level, cell)) collectTable(level, owner, tables);
        }
        double nearest = Double.POSITIVE_INFINITY;
        Boolean tucked = null;
        for (BlockPos root : tables) {
            var tableState = level.getBlockState(root);
            double tableYaw = AnchorRuntime.rotation(level, root);
            Vec3 tableBase = Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root));
            var table = SurfaceGeometry.assemblyBounds(level, root, tableState, tableYaw)
                    .move(tableBase);
            double alignment = Math.abs(Math.IEEEremainder(yaw - tableYaw, Math.PI / 2));
            if (alignment > Math.toRadians(15) || Math.abs(table.minY - chair.minY) > FLOOR_TOLERANCE
                    || !table.intersects(search)) continue;

            AABB footprint = SurfaceGeometry.assemblyBounds(level, root, tableState);
            Vec3 heading = FurnitureTransform.point(forward, Vec3.ZERO, -tableYaw);
            Direction edge = Direction.fromYRot(Math.toDegrees(Math.atan2(-heading.x, heading.z)));
            var axis = edge.getAxis();
            var crossAxis = edge.getClockWise().getAxis();
            Vec3 center = FurnitureTransform.point(chair.getCenter().subtract(tableBase), Vec3.ZERO, -tableYaw);
            double sign = edge.getAxisDirection().getStep();
            double halfDepth = (localChair.max(facing.getAxis()) - localChair.min(facing.getAxis())) * .5;
            double frontDepth = (center.get(axis) - edge(footprint, edge.getOpposite())) * sign + halfDepth;
            if (frontDepth < -.80 || (footprint.getCenter().get(axis) - center.get(axis)) * sign <= 0
                    || center.get(crossAxis) < footprint.min(crossAxis) - SIDE_TOLERANCE
                    || center.get(crossAxis) > footprint.max(crossAxis) + SIDE_TOLERANCE) continue;
            double distance = center.distanceToSqr(footprint.getCenter());
            if (distance < nearest) {
                nearest = distance;
                tucked = frontDepth >= halfDepth;
            }
        }
        return tucked;
    }

    public static @Nullable BlockState slideTarget(ServerLevel level, BlockPos pos, BlockState state, boolean pull) {
        Vec3 base = Vec3.atLowerCornerOf(pos).add(AnchorRuntime.offset(level, pos));
        double yaw = AnchorRuntime.rotation(level, pos);
        var raw = ((DiningChairBlock)state.getBlock()).baseCollision(state, level, pos);
        if (raw.isEmpty()) return null;
        List<AABB> boxes = raw.toAabbs();
        double bottom = raw.bounds().minY;
        if (DiningChairBlock.hasTravel(state)) {
            return slideClear(level, pos, state, boxes, bottom, base, yaw) ? state.cycle(DiningChairBlock.TUCKED) : null;
        }
        for (int choice = DiningChairBlock.travelChoices() - 1; choice >= 0; choice--) {
            BlockState withTravel = DiningChairBlock.withTravel(state, choice, pull);
            if (slideClear(level, pos, withTravel, boxes, bottom, base, yaw)) return withTravel.cycle(DiningChairBlock.TUCKED);
        }
        return null;
    }

    private static boolean slideClear(ServerLevel level, BlockPos pos, BlockState state, List<AABB> boxes,
                                      double bottom, Vec3 base, double yaw) {
        Vec3 slide = DiningChairBlock.slide(state, 1);
        Vec3 destination = state.getValue(DiningChairBlock.TUCKED) ? base
                : base.add(FurnitureTransform.point(slide, Vec3.ZERO, yaw));
        if (!feetSupported(level, boxes, bottom, destination, yaw)) return false;
        // The local slide is along one axis, so each box has a single exact oriented sweep.
        var sweep = boxes.stream().map(box -> box.expandTowards(slide).deflate(SLIDE_CLEARANCE).move(base)).toList();
        AABB bounds = sweep.stream().map(box -> FurnitureTransform.bounds(box, base, yaw)).reduce(AABB::minmax).orElseThrow();
        for (var entity : level.getEntities(null, bounds)) {
            if (entity.isRemoved() || !entity.blocksBuilding) continue;
            for (AABB swept : sweep) {
                if (FurnitureTransform.intersects(swept, base, yaw, entity.getBoundingBox())) return false;
            }
        }
        var candidates = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            if (!level.hasChunkAt(cell) || !level.getWorldBorder().isWithinBounds(cell)) return false;
            candidates.add(cell.immutable());
            candidates.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        for (BlockPos other : candidates) {
            if (other.equals(pos)) continue;
            var obstacle = level.getBlockState(other).getCollisionShape(level, other).move(other);
            if (intersects(sweep, base, yaw, bounds, obstacle)) return false;
        }
        for (var obstacle : FenceGateGeometry.collisions(level, bounds, pos)) {
            if (intersects(sweep, base, yaw, bounds, obstacle)) return false;
        }
        return true;
    }

    private static boolean intersects(List<AABB> sweep, Vec3 base, double yaw, AABB bounds, VoxelShape obstacle) {
        if (obstacle.isEmpty() || !bounds.intersects(obstacle.bounds())) return false;
        for (AABB fixed : obstacle.toAabbs()) {
            for (AABB swept : sweep) {
                if (FurnitureTransform.intersects(swept, base, yaw, fixed)) return true;
            }
        }
        return false;
    }

    private static boolean feetSupported(Level level, List<AABB> boxes, double bottom, Vec3 origin, double yaw) {
        for (AABB box : boxes) {
            if (box.minY > bottom + .003) continue;
            double dx = Math.min(.028, box.getXsize() * .3);
            double dz = Math.min(.028, box.getZsize() * .3);
            for (double x : new double[]{box.minX + dx, box.maxX - dx}) {
                for (double z : new double[]{box.minZ + dz, box.maxZ - dz}) {
                    Vec3 point = FurnitureTransform.point(new Vec3(x, bottom, z), Vec3.ZERO, yaw).add(origin);
                    if (!InteriorPlacement.furnitureSupport(level, point)) return false;
                }
            }
        }
        return true;
    }

    private static void collectTable(Level level, BlockPos pos, LinkedHashSet<BlockPos> tables) {
        if (!level.hasChunkAt(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FurnitureBlock table && table.definition().id().equals("interior_cabin_dining_table"))
            tables.add(table.origin(pos, state).immutable());
    }

    private static Direction nearestSide(AABB table, Vec3 aim) {
        Direction nearest = null;
        double nearestDistance = MAX_GAP;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Direction.Axis normal = side.getAxis();
            Direction.Axis width = side.getClockWise().getAxis();
            double distance = aim.get(normal) - edge(table, side);
            if (side.getAxisDirection() == Direction.AxisDirection.NEGATIVE) distance = -distance;
            if (distance < -.003 || distance > nearestDistance
                    || aim.get(width) < table.min(width) - SIDE_TOLERANCE
                    || aim.get(width) > table.max(width) + SIDE_TOLERANCE) continue;
            nearestDistance = distance;
            nearest = side;
        }
        return nearest;
    }

    private static Snap fitChair(Level level, FurnitureBlock chair, BlockPos tableRoot, AABB table, Direction side) {
        Direction facing = side.getOpposite();
        AABB bounds = SurfaceGeometry.bounds(level, tableRoot,
                chair.defaultBlockState().setValue(FurnitureBlock.FACING, facing));
        Direction.Axis normal = side.getAxis();
        double sign = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        double shift = edge(table, side) + sign * FRONT_GAP - edge(bounds, facing);
        Vec3 center = table.getCenter().subtract(bounds.getCenter());
        Vec3 origin = new Vec3(normal == Direction.Axis.X ? shift : center.x, table.minY - bounds.minY,
                normal == Direction.Axis.Z ? shift : center.z);
        AABB placed = bounds.move(origin);
        Vec3 centerOnFloor = new Vec3(placed.getCenter().x, placed.minY, placed.getCenter().z);
        SurfaceGeometry.Hit floor = InteriorPlacement.floorBelow(level, centerOnFloor);
        if (floor == null || Math.abs(floor.point().y - table.minY) > FLOOR_TOLERANCE
                || !InteriorPlacement.supported(level, placed)) return null;
        return new Snap(origin, facing, floor);
    }

    private static double edge(AABB bounds, Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? bounds.max(direction.getAxis()) : bounds.min(direction.getAxis());
    }
}
