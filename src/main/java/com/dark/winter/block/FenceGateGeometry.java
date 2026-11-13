package com.dark.winter.block;

import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.BoxUnionShape;
import com.dark.winter.placement.FurnitureTransform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Indexes loaded gate roots so a leaf can collide and be picked outside the saved block footprint. */
public final class FenceGateGeometry {
    private static final int ANGLES = 32;
    private static final double INTERSECTION_EPSILON = 1e-7;
    // The 2 m leaf can extend two cells from its root, plus the anchor's up to 2 m shift.
    private static final double HORIZONTAL_REACH = 4.5;
    private static final Map<Level, Map<Long, Map<BlockPos, Object>>> LEVELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final VoxelShape[][][][] POSES = bake();

    private FenceGateGeometry() {}

    static void add(Level level, BlockPos pos, Object token) {
        Map<Long, Map<BlockPos, Object>> chunks = LEVELS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        chunks.compute(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), (key, roots) -> {
            if (roots == null) roots = new ConcurrentHashMap<>();
            roots.put(pos, token);
            return roots;
        });
    }

    static void remove(Level level, BlockPos pos, Object token) {
        var chunks = LEVELS.get(level);
        if (chunks == null) return;
        chunks.computeIfPresent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), (key, roots) -> {
            roots.remove(pos, token);
            return roots.isEmpty() ? null : roots;
        });
    }

    private static List<BlockPos> nearby(BlockGetter getter, AABB box) {
        if (!(getter instanceof Level level)) return List.of();
        var chunks = LEVELS.get(level);
        if (chunks == null || chunks.isEmpty()) return List.of();
        int x0 = ((int)Math.floor(box.minX - HORIZONTAL_REACH)) >> 4, x1 = ((int)Math.floor(box.maxX + HORIZONTAL_REACH)) >> 4;
        int z0 = ((int)Math.floor(box.minZ - HORIZONTAL_REACH)) >> 4, z1 = ((int)Math.floor(box.maxZ + HORIZONTAL_REACH)) >> 4;
        var roots = new ArrayList<BlockPos>();
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            var column = chunks.get(ChunkPos.pack(x, z));
            if (column == null) continue;
            for (BlockPos root : column.keySet()) {
                if (root.getY() > box.maxY + 2 || root.getY() + 4 < box.minY) continue;
                if (root.getX() + HORIZONTAL_REACH >= box.minX && root.getX() - HORIZONTAL_REACH <= box.maxX
                        && root.getZ() + HORIZONTAL_REACH >= box.minZ && root.getZ() - HORIZONTAL_REACH <= box.maxZ) roots.add(root);
            }
        }
        return roots;
    }

    public static @Nullable VoxelShape movingShape(BlockGetter level, BlockPos root) {
        if (level instanceof Level world && !world.hasChunkAt(root)) return null;
        var state = level.getBlockState(root);
        if (DiningChairBlock.isRoot(state)) return state.getCollisionShape(level, root).move(root);
        if (!FenceGateFurnitureBlock.isRoot(state) && !StorageFurnitureBlock.isRoot(state)) return null;
        var entity = level.getBlockEntity(root) instanceof IndexedFurnitureSwingEntity swing ? swing : null;
        float progress = entity != null ? entity.progress(0) : state.getValue(FenceGateFurnitureBlock.OPEN) ? 1 : 0;
        if (progress <= 0) return null;
        var furniture = (FurnitureBlock) state.getBlock();
        Direction facing = state.getValue(FurnitureBlock.FACING);
        int angle = Math.round(progress * ANGLES);
        double yaw = AnchorRuntime.rotation(level, root);
        VoxelShape shape = entity != null && yaw != 0 ? entity.swingShape(furniture, facing, angle, yaw)
                : pose(furniture, facing, angle, yaw);
        return shape.move(Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root)));
    }

    public static List<VoxelShape> collisions(BlockGetter level, AABB box) {
        return collisions(level, box, null);
    }

    public static List<VoxelShape> collisions(BlockGetter level, AABB box, @Nullable BlockPos ignored) {
        var roots = nearby(level, box);
        if (roots.isEmpty()) return List.of();
        var result = new ArrayList<VoxelShape>();
        VoxelShape query = Shapes.create(box);
        for (BlockPos root : roots) {
            if (root.equals(ignored)) continue;
            var leaf = movingShape(level, root);
            if (leaf != null && Shapes.joinIsNotEmpty(query, leaf, BooleanOp.AND)) result.add(leaf);
        }
        return result;
    }

    public static BlockHitResult ray(BlockGetter level, Vec3 from, Vec3 to, BlockHitResult nativeHit) {
        BlockHitResult chosen = nativeHit;
        double best = chosen.getType() == HitResult.Type.BLOCK
                ? from.distanceToSqr(chosen.getLocation()) : Double.POSITIVE_INFINITY;
        for (BlockPos root : nearby(level, new AABB(from, to))) {
            var shape = movingShape(level, root);
            if (shape == null) continue;
            BlockHitResult hit = shape.clip(from, to, BlockPos.ZERO);
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit.getLocation());
            if (distance < best) {
                chosen = new BlockHitResult(hit.getLocation(), hit.getDirection(), root, hit.isInside());
                best = distance;
            }
        }
        return chosen;
    }

    public static boolean swingClear(ServerLevel level, BlockPos origin, FurnitureBlock block,
                                     Direction facing, List<BlockPos> parts) {
        Vec3 base = Vec3.atLowerCornerOf(origin).add(AnchorRuntime.offset(level, origin));
        double yaw = AnchorRuntime.rotation(level, origin);
        var sweep = new ArrayList<AABB>((ANGLES + 1) * 24);
        AABB bounds = null;
        for (int angle = 0; angle <= ANGLES; angle++) {
            for (AABB box : poseBoxes(block, facing, angle, yaw)) {
                AABB world = box.move(base);
                sweep.add(world);
                bounds = bounds == null ? world : bounds.minmax(world);
            }
        }
        if (bounds == null) return true;
        for (var entity : level.getEntities(null, bounds)) {
            if (!entity.isRemoved() && entity.blocksBuilding && intersects(sweep, entity.getBoundingBox())) return false;
        }
        Set<BlockPos> candidates = new java.util.HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            if (!level.hasChunkAt(cell) || !level.getWorldBorder().isWithinBounds(cell)) return false;
            candidates.add(cell.immutable());
            candidates.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        for (BlockPos pos : candidates) {
            if (parts.contains(pos)) continue;
            var shape = level.getBlockState(pos).getCollisionShape(level, pos).move(pos);
            if (intersects(sweep, shape, bounds)) return false;
        }
        for (BlockPos other : nearby(level, bounds)) {
            if (!other.equals(origin) && intersects(sweep, movingShape(level, other), bounds)) return false;
        }
        return true;
    }

    private static boolean intersects(List<AABB> sweep, @Nullable VoxelShape shape, AABB bounds) {
        if (shape == null || shape.isEmpty() || !bounds.intersects(shape.bounds())) return false;
        for (AABB box : shape.toAabbs()) {
            if (intersects(sweep, box)) return true;
        }
        return false;
    }

    private static boolean intersects(List<AABB> sweep, AABB obstacle) {
        // Use the same tolerance as Minecraft's coordinate merger.
        for (AABB box : sweep) {
            if (Math.min(box.maxX, obstacle.maxX) - Math.max(box.minX, obstacle.minX) > INTERSECTION_EPSILON
                    && Math.min(box.maxY, obstacle.maxY) - Math.max(box.minY, obstacle.minY) > INTERSECTION_EPSILON
                    && Math.min(box.maxZ, obstacle.maxZ) - Math.max(box.minZ, obstacle.minZ) > INTERSECTION_EPSILON) return true;
        }
        return false;
    }

    static VoxelShape pose(FurnitureBlock block, Direction facing, int angle, double yaw) {
        if (block instanceof FenceGateFurnitureBlock gate) {
            return yaw == 0 ? POSES[gate.clearWidth() - 1][gate.tall() ? 1 : 0][FurnitureBlock.facingIndex(facing)][angle]
                    : gatePose(gate.clearWidth(), gate.tall(), facing, angle, yaw);
        }
        var kind = ((StorageFurnitureBlock)block).kind();
        return yaw == 0 ? StoragePoses.POSES[kind.ordinal()][FurnitureBlock.facingIndex(facing)][angle]
                : storagePose(kind, facing, angle, yaw);
    }

    private static List<AABB> poseBoxes(FurnitureBlock block, Direction facing, int angle, double yaw) {
        if (block instanceof FenceGateFurnitureBlock gate) {
            return gateBoxes(gate.clearWidth(), gate.tall(), facing, angle, yaw);
        }
        return storageBoxes(((StorageFurnitureBlock) block).kind(), facing, angle, yaw);
    }

    private static final class StoragePoses {
        static final VoxelShape[][][] POSES = bake();

        private static VoxelShape[][][] bake() {
            var kinds = StorageFurnitureBlock.Kind.values();
            var poses = new VoxelShape[kinds.length][4][ANGLES + 1];
            for (var kind : kinds) for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (int i = 0; i <= ANGLES; i++) {
                    poses[kind.ordinal()][FurnitureBlock.facingIndex(facing)][i] = storagePose(kind, facing, i, 0);
                }
            }
            return poses;
        }
    }

    private static VoxelShape storagePose(StorageFurnitureBlock.Kind kind, Direction facing, int angle, double yaw) {
        return BoxUnionShape.of(storageBoxes(kind, facing, angle, yaw));
    }

    private static List<AABB> storageBoxes(StorageFurnitureBlock.Kind kind, Direction facing, int angle, double yaw) {
        var boxes = new ArrayList<AABB>(24);
        for (int index = 0; index < 2; index++) {
            var leaf = kind.leaf(index);
            var transform = new LeafTransform(leaf.hingeX() / 16, leaf.hingeZ() / 16,
                    Math.toRadians(leaf.angle()) * angle / ANGLES, facing, yaw);
            double dx = (leaf.right() - leaf.left()) / 12;
            for (int slice = 0; slice < 12; slice++) {
                double x0 = (leaf.left() + slice * dx) / 16;
                double x1 = (leaf.left() + (slice + 1) * dx) / 16;
                boxes.add(slice(x0, x1, leaf.bottom() / 16, leaf.top() / 16,
                        leaf.front() / 16, leaf.back() / 16, transform));
            }
        }
        return boxes;
    }

    private static AABB slice(double x0, double x1, double y0, double y1, double z0, double z1, LeafTransform transform) {
        Vec3 center = transform.point((x0 + x1) * .5, (z0 + z1) * .5);
        double radiusX = .5 * ((x1 - x0) * Math.abs(transform.cos()) + (z1 - z0) * Math.abs(transform.sin()));
        double radiusZ = .5 * ((x1 - x0) * Math.abs(transform.sin()) + (z1 - z0) * Math.abs(transform.cos()));
        return new AABB(center.x - radiusX, y0, center.z - radiusZ, center.x + radiusX, y1, center.z + radiusZ);
    }

    private static VoxelShape[][][][] bake() {
        var poses = new VoxelShape[2][2][4][ANGLES + 1];
        for (int width = 1; width <= 2; width++) for (int height = 0; height < 2; height++) {
            for (Direction facing : Direction.Plane.HORIZONTAL) for (int angle = 0; angle <= ANGLES; angle++) {
                poses[width - 1][height][FurnitureBlock.facingIndex(facing)][angle] = gatePose(width, height != 0, facing, angle, 0);
            }
        }
        return poses;
    }

    private static VoxelShape gatePose(int width, boolean tall, Direction facing, int angle, double yaw) {
        return BoxUnionShape.of(gateBoxes(width, tall, facing, angle, yaw));
    }

    private static List<AABB> gateBoxes(int width, boolean tall, Direction facing, int angle, double yaw) {
        int slices = 16 * width;
        var boxes = new ArrayList<AABB>(slices);
        Vec3 hinge = FenceGateFurnitureBlock.hinge(Direction.NORTH);
        var transform = new LeafTransform(hinge.x, hinge.z, angle * Math.PI / (2 * ANGLES), facing, yaw);
        // Narrow slices approximate the rotated panel without filling its entire bounding rectangle.
        double step = 1 - .24 / slices;
        for (int slice = 0; slice < slices; slice++) {
            double x0 = (8.12 + slice * step) / 16;
            double x1 = (8.12 + (slice + 1) * step) / 16;
            boxes.add(slice(x0, x1, 1.0 / 16, (tall ? 27.8 : 13.4) / 16, 6.62 / 16, 9.10 / 16, transform));
        }
        return boxes;
    }

    private record LeafTransform(Vec3 hinge, double hingeX, double hingeZ, double sin, double cos) {
        LeafTransform(double hingeX, double hingeZ, double swing, Direction facing, double yaw) {
            this(FurnitureTransform.point(facingHinge(hingeX, hingeZ, facing), Vec3.ZERO, yaw), hingeX, hingeZ,
                    Math.sin(yaw + FurnitureBlock.facingIndex(facing) * Math.PI / 2 - swing),
                    Math.cos(yaw + FurnitureBlock.facingIndex(facing) * Math.PI / 2 - swing));
        }

        Vec3 point(double x, double z) {
            double dx = x - hingeX, dz = z - hingeZ;
            return new Vec3(hinge.x + cos * dx - sin * dz, 0, hinge.z + sin * dx + cos * dz);
        }

        private static Vec3 facingHinge(double x, double z, Direction facing) {
            return switch (facing) {
                case EAST -> new Vec3(1 - z, 0, x);
                case SOUTH -> new Vec3(1 - x, 0, 1 - z);
                case WEST -> new Vec3(z, 0, 1 - x);
                default -> new Vec3(x, 0, z);
            };
        }
    }
}
