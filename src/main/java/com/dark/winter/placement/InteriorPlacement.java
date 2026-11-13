package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.WallSegmentBlock;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Attachment rules for the interior pieces with a prescribed supporting surface. */
final class InteriorPlacement {
    private static final double RUG_FLOOR_SEARCH = 3;

    private InteriorPlacement() {}

    static boolean rug(Block block) {
        return block instanceof FurnitureBlock furniture && furniture.definition().id().startsWith("interior_rug_");
    }

    static boolean wallMounted(Block block) {
        if (!(block instanceof FurnitureBlock furniture)) return false;
        String id = furniture.definition().id();
        return id.startsWith("interior_art_") || switch (id) {
            case "interior_coat_hooks", "interior_bookshelf", "interior_wall_clock", "interior_wall_mirror",
                    "interior_long_kitchen_shelf", "interior_long_wall_shelf", "interior_wall_shelf" -> true;
            default -> false;
        };
    }

    static boolean ceilingMounted(Block block) {
        return block instanceof FurnitureBlock furniture && furniture.definition().id().equals("interior_cabin_pendant");
    }

    static boolean kitchenCabinet(Block block) {
        return block instanceof FurnitureBlock furniture && switch (furniture.definition().id()) {
            case "interior_kitchen_worktable", "interior_kitchen_hutch", "kitchen_sink" -> true;
            default -> false;
        };
    }

    static boolean kitchenPair(Block first, Block second) {
        if (first == second || !kitchenCabinet(first) || !kitchenCabinet(second)) return false;
        return ((FurnitureBlock) first).definition().id().equals("interior_kitchen_worktable")
                || ((FurnitureBlock) second).definition().id().equals("interior_kitchen_worktable");
    }

    static boolean floor(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof WallSegmentBlock) return false;
        if (state.getBlock() instanceof FurnitureBlock furniture) {
            return switch (furniture.definition().id()) {
                case "cabin_porch_deck", "cabin_porch_deck_supported", "timber_boardwalk",
                        "timber_boardwalk_slab", "timber_boardwalk_raised", "paving_slab_block",
                        "gravel_surface", "asphalt_block", "asphalt_white_line", "asphalt_dashed_line",
                        "asphalt_crosswalk", "asphalt_edge_line", "cabin_worn_path" -> true;
                default -> false;
            };
        }
        if (SurfacePlacement.nativeFurniture(state)) return false;
        return !state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
    }

    static boolean rugCanPassUnder(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof FurnitureBlock furniture) {
            if (rug(furniture) || floor(level, pos, state) || RoofPlacement.isRoof(furniture)) return false;
            return switch (furniture.definition().group()) {
                case "residential", "industrial", "hospital", "outdoor" -> true;
                default -> furniture.definition().id().equals("park_bench");
            };
        }
        return SurfacePlacement.nativeFurniture(state);
    }

    static SurfaceGeometry.Hit rugContact(Level level, BlockHitResult picked, Vec3 point) {
        BlockPos owner = picked.getBlockPos();
        if (picked.getType() != HitResult.Type.BLOCK || !level.hasChunkAt(owner)) return null;
        BlockState state = level.getBlockState(owner);
        boolean throughFurniture = rugCanPassUnder(level, owner, state);
        if (!throughFurniture && picked.getDirection() != Direction.UP) return null;
        if (!throughFurniture && !rug(state.getBlock()) && !floor(level, owner, state)) return null;

        Vec3 normal = picked.getDirection().getUnitVec3();
        var contact = SurfaceGeometry.hit(level, owner, point.add(normal.scale(.06)), point.subtract(normal.scale(.06)));
        if (contact == null || contact.face() != picked.getDirection()) return null;
        if (throughFurniture) return floorBelow(level, contact.point(), RUG_FLOOR_SEARCH, false);
        return rug(state.getBlock()) ? floorBelow(level, contact.point()) : contact;
    }

    static SurfaceGeometry.Hit floorBelow(Level level, Vec3 point) {
        return floorBelow(level, point, .06, false);
    }

    private static SurfaceGeometry.Hit floorBelow(Level level, Vec3 point, double depth, boolean furnitureTop) {
        Vec3 top = point.add(0, .003, 0);
        Vec3 bottom = point.add(0, -depth, 0);
        var owners = new LinkedHashSet<BlockPos>();
        BlockPos.MutableBlockPos cell = BlockPos.containing(top).mutable();
        int minY = BlockPos.containing(bottom).getY();
        for (int y = cell.getY(); y >= minY; y--) {
            cell.setY(y);
            owners.add(cell.immutable());
            owners.addAll(AnchorRuntime.logicalOwners(level, cell));
        }
        SurfaceGeometry.Hit best = null;
        for (BlockPos owner : owners) {
            if (!level.hasChunkAt(owner)) continue;
            BlockState state = level.getBlockState(owner);
            boolean supported = furnitureTop
                    ? !AnchorRuntime.rawShape(level, owner, state, true).isEmpty()
                    : floor(level, owner, state);
            if (!supported) continue;
            SurfaceGeometry.Hit hit = SurfaceGeometry.hit(level, owner, top, bottom);
            if (hit != null && hit.face() == Direction.UP && (best == null || hit.point().y > best.point().y)) best = hit;
        }
        return best;
    }

    static boolean furnitureSupport(Level level, Vec3 point) {
        var support = floorBelow(level, point, .06, true);
        return support != null && Math.abs(support.point().y - point.y) < .014;
    }

    static boolean supported(Level level, AABB part) {
        // Both sides must rest on the same floor level.
        for (double x : new double[]{part.minX + .006, part.maxX - .006}) {
            for (double z : new double[]{part.minZ + .006, part.maxZ - .006}) {
                SurfaceGeometry.Hit support = floorBelow(level, new Vec3(x, part.minY, z));
                if (support == null || Math.abs(support.point().y - part.minY) > .003) return false;
            }
        }
        return true;
    }

    static boolean supportedRug(Level level, List<BlockPos> members, Vec3 offset) {
        for (BlockPos member : members) {
            if (!level.hasChunkAt(member)) continue;
            BlockState state = level.getBlockState(member);
            Vec3 origin = Vec3.atLowerCornerOf(member).add(offset);
            if (!rugFootprint(level, member, state, Vec3.ZERO, origin, 0, true)) return false;
        }
        return true;
    }

    static boolean supportedRug(Level level, BlockPos root, FurnitureBlock furniture, BlockState state,
                                Vec3 origin, double radians, boolean deferUnloadedFloor) {
        Direction facing = state.getValue(FurnitureBlock.FACING);
        for (int part = 0; part < furniture.definition().parts().size(); part++) {
            BlockPos offset = furniture.partOffset(part, facing);
            if (!rugFootprint(level, root.offset(offset), state.setValue(FurnitureBlock.PART, part),
                    Vec3.atLowerCornerOf(offset), origin, radians, deferUnloadedFloor)) return false;
        }
        return true;
    }

    static boolean supportedRug(Level level, BlockPos root, FurnitureBlock furniture, BlockState state,
                                Vec3 origin, double radians) {
        return supportedRug(level, root, furniture, state, origin, radians, false);
    }

    private static boolean rugFootprint(Level level, BlockPos member, BlockState state,
                                        Vec3 partOffset, Vec3 origin, double radians, boolean deferUnloadedFloor) {
        var footprint = AnchorRuntime.rawShape(level, member, state, false);
        if (footprint.isEmpty()) return false;
        for (AABB box : footprint.toAabbs()) {
            if (!rugBoxSupported(level, box, partOffset, origin, radians, deferUnloadedFloor)) return false;
        }
        return true;
    }

    private static boolean rugBoxSupported(Level level, AABB box, Vec3 partOffset, Vec3 origin,
                                           double radians, boolean deferUnloadedFloor) {
        double dx = Math.min(.006, box.getXsize() * .25);
        double dz = Math.min(.006, box.getZsize() * .25);
        double[] xs = {box.minX + dx, box.maxX - dx};
        double[] zs = {box.minZ + dz, box.maxZ - dz};
        for (double x : xs) for (double z : zs) {
            if (!rugSample(level, new Vec3(x, box.minY, z), partOffset, origin, radians, deferUnloadedFloor)) return false;
        }
        Vec3 center = box.getCenter();
        return rugSample(level, new Vec3(center.x, box.minY, center.z), partOffset, origin, radians, deferUnloadedFloor);
    }

    private static boolean rugSample(Level level, Vec3 local, Vec3 partOffset, Vec3 origin,
                                     double radians, boolean deferUnloadedFloor) {
        Vec3 sample = FurnitureTransform.point(local.add(partOffset), Vec3.ZERO, radians).add(origin);
        BlockPos cell = BlockPos.containing(sample);
        if (deferUnloadedFloor && !level.hasChunkAt(cell)) return true;
        SurfaceGeometry.Hit support = floorBelow(level, sample);
        if (support != null && Math.abs(support.point().y - sample.y) <= .003) return true;
        if (!deferUnloadedFloor) return false;
        // A shifted floor can occupy this cell while its saved block sits in the next chunk.
        for (BlockPos owner : AnchorRuntime.logicalOwners(level, cell)) if (!level.hasChunkAt(owner)) return true;
        for (BlockPos owner : AnchorRuntime.logicalOwners(level, cell.below())) if (!level.hasChunkAt(owner)) return true;
        return false;
    }
}
