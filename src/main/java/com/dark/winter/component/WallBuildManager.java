// Single source of truth for the wall coordinates used by the preview.
package com.dark.winter.component;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WallBuildManager {
    private static final Map<UUID, WallShape> PLAYER_SHAPES = new ConcurrentHashMap<>();
    private static WallShape clientActiveShape = WallShape.WALL_3X3;

    public static WallShape getClientShape() {
        return clientActiveShape;
    }

    public static void setClientShape(WallShape shape) {
        clientActiveShape = shape;
    }

    public static WallShape getPlayerShape(UUID playerUuid) {
        return PLAYER_SHAPES.getOrDefault(playerUuid, WallShape.WALL_3X3);
    }

    public static void setPlayerShape(UUID playerUuid, WallShape shape) {
        PLAYER_SHAPES.put(playerUuid, shape);
    }

    /** Single source of truth for the wall coordinates used by the preview. */
    public static List<BlockPos> calculatePreviewPositions(
            BlockPos origin, Direction facing, WallShape shape
    ) {
        return shape.getPositions(origin, facing);
    }

    private WallBuildManager() {
    }
}
