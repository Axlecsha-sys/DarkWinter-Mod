package com.dark.winter.placement;

import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.WallSegmentBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Fits floor-standing furniture around walls and existing furniture, before reserving storage. */
final class FurnitureFloorPlacement {
    private static final double CLEARANCE = .002;
    private static final double MAX_ADJUSTMENT = .5;
    private static final double CABINET_SNAP = .8;

    record Fit(Vec3 nominal, Vec3 origin, AABB bounds, AABB support, List<BlockPos> nearby) {}

    private FurnitureFloorPlacement() {}

    static boolean applies(FurnitureBlock furniture, BlockState support, Direction face) {
        if (face != Direction.UP) return false;
        boolean floor = support.getBlock() instanceof FloorSegmentBlock
                || support.getBlock() instanceof FurnitureBlock deck && switch (deck.definition().id()) {
                    case "cabin_porch_deck", "cabin_porch_deck_supported" -> true;
                    default -> false;
                };
        if (!floor) return false;
        return isFloorFurniture(furniture);
    }

    static boolean isFloorFurniture(FurnitureBlock furniture) {
        if (InteriorPlacement.wallMounted(furniture) || InteriorPlacement.ceilingMounted(furniture)) return false;
        return switch (furniture.definition().group()) {
            case "residential", "industrial", "hospital", "outdoor" -> true;
            default -> false;
        };
    }

    static Fit fit(Level level, BlockPos base, SurfaceGeometry.Hit contact, FurnitureBlock furniture,
                   Direction facing) {
        AABB bounds = SurfaceGeometry.assemblyBounds(level, base, furniture.defaultBlockState().setValue(FurnitureBlock.FACING, facing));
        Vec3 nominal = Vec3.atLowerCornerOf(base).add(AnchorRuntime.offset(level, contact.owner()));
        nominal = new Vec3(nominal.x, contact.point().y - bounds.minY, nominal.z);
        AABB initial = bounds.move(nominal);
        AABB physical = initial;
        var nearby = nearby(level, initial.inflate(MAX_ADJUSTMENT + CLEARANCE, 0, MAX_ADJUSTMENT + CLEARANCE));
        for (BlockPos cell : nearby) {
            if (!level.hasChunkAt(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!(state.getBlock() instanceof WallSegmentBlock)) continue;
            AABB wall = state.getCollisionShape(level, cell, CollisionContext.empty()).bounds().move(cell);
            if (!physical.intersects(wall.inflate(CLEARANCE, 0, CLEARANCE))) continue;
            Direction.Axis axis = state.getValue(WallSegmentBlock.FACING).getAxis();
            double nudge = contact.point().get(axis) >= wall.getCenter().get(axis)
                    ? wall.max(axis) + CLEARANCE - physical.min(axis)
                    : wall.min(axis) - CLEARANCE - physical.max(axis);
            physical = physical.move(axis == Direction.Axis.X ? nudge : 0, 0, axis == Direction.Axis.Z ? nudge : 0);
        }
        Vec3 origin = nominal.add(physical.getCenter().subtract(initial.getCenter()));
        if (!withinReach(nominal, origin)) return null;
        BlockPos owner = contact.owner();
        AABB support = SurfaceGeometry.bounds(level, owner, level.getBlockState(owner))
                .move(Vec3.atLowerCornerOf(owner).add(AnchorRuntime.offset(level, owner)));
        return new Fit(nominal, origin, bounds, support, List.copyOf(nearby));
    }

    private static LinkedHashSet<BlockPos> nearby(Level level, AABB search) {
        var nearby = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(search.minX, search.minY, search.minZ),
                BlockPos.containing(search.maxX, search.maxY, search.maxZ))) {
            nearby.add(cell.immutable());
            nearby.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        return nearby;
    }

    static List<Vec3> cabinetSnaps(Level level, Fit fit, FurnitureBlock furniture, Direction facing) {
        if (!InteriorPlacement.kitchenCabinet(furniture)) return List.of();
        AABB physical = fit.bounds().move(fit.origin());
        Direction.Axis width = facing.getClockWise().getAxis();
        Direction.Axis depth = facing.getAxis();
        var candidates = new ArrayList<Vec3>();
        var visited = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : nearby(level, physical.inflate(CABINET_SNAP, .01, CABINET_SNAP))) {
            if (!level.hasChunkAt(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!(state.getBlock() instanceof FurnitureBlock neighbor) || !InteriorPlacement.kitchenPair(furniture, neighbor)
                    || state.getValue(FurnitureBlock.FACING) != facing) continue;
            BlockPos root = neighbor.origin(cell, state);
            if (!visited.add(root)) continue;
            AABB cabinet = SurfaceGeometry.assemblyBounds(level, root, state.setValue(FurnitureBlock.PART, 0))
                    .move(Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root)));
            double sharedDepth = Math.min(cabinet.max(depth), physical.max(depth))
                    - Math.max(cabinet.min(depth), physical.min(depth));
            if (Math.abs(cabinet.minY - physical.minY) > .01 || sharedDepth < .5) continue;
            double shift = physical.getCenter().get(width) < cabinet.getCenter().get(width)
                    ? cabinet.min(width) - physical.max(width) - CLEARANCE
                    : cabinet.max(width) - physical.min(width) + CLEARANCE;
            if (Math.abs(shift) > CABINET_SNAP) continue;
            Vec3 candidate = fit.origin().add(width == Direction.Axis.X ? shift : 0, 0, width == Direction.Axis.Z ? shift : 0);
            if (overlapsSupport(fit, candidate)) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble(fit.origin()::distanceToSqr));
        return candidates;
    }

    static List<Vec3> alternatives(Level level, Fit fit) {
        AABB physical = fit.bounds().move(fit.origin());
        var obstacles = new ArrayList<AABB>();
        for (BlockPos cell : fit.nearby()) {
            if (!level.hasChunkAt(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!(state.getBlock() instanceof FurnitureBlock)) continue;
            var collision = state.getCollisionShape(level, cell, CollisionContext.empty());
            if (!collision.isEmpty()) obstacles.add(collision.bounds().move(cell));
        }
        if (obstacles.stream().noneMatch(physical::intersects)) return List.of();

        // Each edge gives a position beside an obstacle. Try the shortest shifts first,
        // including combined X/Z moves for furniture pressed into a corner.
        var xs = new LinkedHashSet<Double>();
        var zs = new LinkedHashSet<Double>();
        xs.add(fit.origin().x);
        zs.add(fit.origin().z);
        AABB search = fit.bounds().move(fit.nominal()).inflate(MAX_ADJUSTMENT, 0, MAX_ADJUSTMENT);
        for (AABB obstacle : obstacles) {
            if (!search.intersects(obstacle)) continue;
            addEdge(xs, obstacle.minX - fit.bounds().maxX - CLEARANCE, fit.nominal().x);
            addEdge(xs, obstacle.maxX - fit.bounds().minX + CLEARANCE, fit.nominal().x);
            addEdge(zs, obstacle.minZ - fit.bounds().maxZ - CLEARANCE, fit.nominal().z);
            addEdge(zs, obstacle.maxZ - fit.bounds().minZ + CLEARANCE, fit.nominal().z);
        }
        var candidates = new ArrayList<Vec3>();
        for (double x : xs) for (double z : zs) {
            var candidate = new Vec3(x, fit.origin().y, z);
            if (candidate.equals(fit.origin())) continue;
            if (overlapsSupport(fit, candidate)) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble(fit.origin()::distanceToSqr));
        return candidates;
    }

    private static boolean overlapsSupport(Fit fit, Vec3 origin) {
        AABB moved = fit.bounds().move(origin);
        AABB support = fit.support();
        return moved.maxX > support.minX && moved.minX < support.maxX
                && moved.maxZ > support.minZ && moved.minZ < support.maxZ;
    }

    private static boolean withinReach(Vec3 nominal, Vec3 origin) {
        return Math.abs(nominal.x - origin.x) <= MAX_ADJUSTMENT && Math.abs(nominal.z - origin.z) <= MAX_ADJUSTMENT;
    }

    private static void addEdge(LinkedHashSet<Double> edges, double edge, double nominal) {
        if (Math.abs(edge - nominal) <= MAX_ADJUSTMENT) edges.add(edge);
    }

    static BlockPos storage(BlockPlaceContext context, FurnitureBlock furniture, Direction facing, Vec3 origin,
                            Direction supportFace) {
        Level level = context.getLevel();
        return AnchorStorage.find(origin, root -> {
            for (BlockPos member : furniture.positions(root, facing)) {
                if (!level.hasChunkAt(member) || !level.isInWorldBounds(member)
                        || !level.getWorldBorder().isWithinBounds(member)
                        || !level.getBlockState(member).canBeReplaced(context)
                        || AnchorRuntime.anchored(level, member)) return false;
                var player = context.getPlayer();
                if (player != null && (!level.mayInteract(player, member)
                        || !player.mayUseItemAt(member, supportFace, context.getItemInHand()))) return false;
            }
            return true;
        });
    }
}
