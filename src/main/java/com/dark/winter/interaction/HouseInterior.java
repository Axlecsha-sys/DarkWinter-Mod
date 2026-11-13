package com.dark.winter.interaction;

import com.dark.winter.block.CurtainedWindowBlock;
import com.dark.winter.block.FloorSegmentBlock;
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.block.RoofSegmentBlock;
import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.placement.AnchorRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Local room detection, including structures assembled from shifted wall and roof pieces. */
public final class HouseInterior {
    private static final int ROOM_REACH = 16;

    private HouseInterior() {}

    public static boolean contains(Level level, Vec3 feet) {
        Vec3 point = feet.add(0, 1.05, 0);
        if (!hitsStructure(level, point, point.add(0, ROOM_REACH, 0), true)) return false;
        int walls = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (hitsStructure(level, point, point.add(direction.getUnitVec3().scale(ROOM_REACH)), false)
                    && ++walls == 3) return true;
        }
        return false;
    }

    private static boolean hitsStructure(Level level, Vec3 from, Vec3 to, boolean roof) {
        return BlockGetter.traverseBlocks(from, to, level, (world, cell) -> {
            if (!world.hasChunkAt(cell)) return false;
            if (intersects(world, cell, from, to, roof)) return true;
            for (BlockPos owner : AnchorRuntime.logicalOwners(world, cell)) {
                if (!owner.equals(cell) && world.hasChunkAt(owner) && intersects(world, owner, from, to, roof)) return true;
            }
            return null;
        }, world -> false);
    }

    private static boolean intersects(Level level, BlockPos pos, Vec3 from, Vec3 to, boolean roof) {
        BlockState state = level.getBlockState(pos);
        var block = state.getBlock();
        if (roof) {
            if (!(block instanceof RoofSegmentBlock || block instanceof FloorSegmentBlock || block instanceof SlabBlock
                    || block instanceof StairBlock || state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS))) return false;
        } else {
            // A doorway still forms the edge of a room while its leaf is open.
            if (block instanceof PanelDoorBlock) {
                var door = PanelDoorInteraction.target(level, pos);
                if (door == null) return false;
                Vec3 center = HousePassage.center(level, door);
                return net.minecraft.world.phys.AABB.ofSize(center.add(0, 1, 0), .98, 2, .98).clip(from, to).isPresent();
            }
            if (block instanceof DoorBlock) return true;
            if (state.isAir() || state.is(BlockTags.LEAVES)) return false;
            if (!(block instanceof WallSegmentBlock || block instanceof CurtainedWindowBlock
                    || state.isCollisionShapeFullBlock(level, pos))) return false;
        }
        return state.getCollisionShape(level, pos).clip(from, to, pos) != null;
    }
}
