package com.dark.winter.placement;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Finds nearby native storage while keeping the construction piece fixed in world space. */
final class AnchorStorage {
    private static final int RANGE = 2;
    private static final int[] VERTICAL_OFFSETS = {0, 1, -1, 2, -2};
    private static final double EPSILON = 1.0e-6;

    private AnchorStorage() {}

    static BlockPos find(Vec3 physical, Predicate<BlockPos> available) {
        BlockPos center = BlockPos.containing(physical.x + EPSILON, Math.ceil(physical.y - EPSILON),
                physical.z + EPSILON);
        for (int distance = 0; distance <= 3 * RANGE; distance++) {
            for (int y : VERTICAL_OFFSETS) {
                for (int z = -RANGE; z <= RANGE; z++) {
                    for (int x = -RANGE; x <= RANGE; x++) {
                        if (Math.abs(x) + Math.abs(y) + Math.abs(z) != distance) continue;
                        BlockPos root = center.offset(x, y, z);
                        if (Math.abs(physical.x - root.getX()) > RANGE
                                || Math.abs(physical.y - root.getY()) > RANGE
                                || Math.abs(physical.z - root.getZ()) > RANGE) continue;
                        if (available.test(root)) return root;
                    }
                }
            }
        }
        return null;
    }
}
