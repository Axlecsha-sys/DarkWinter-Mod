// Placement preview for the actual wall, window and floor block shapes.
package com.dark.winter.client;

import com.dark.winter.component.WallBuildManager;
import com.dark.winter.component.WallShape;
import com.dark.winter.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Collection;
import java.util.List;

/**
 * Placement preview for the actual wall, window and floor block shapes.
 * Note: Gizmos can render colored translucent shapes, but not textured translucent block models.
 */
public final class WallPlacementPreviewRenderer {
    private static final GizmoStyle VALID_STYLE = GizmoStyle.strokeAndFill(
            0xFF44FF66, // outline ARGB
            2.0F,
            0x6644FF66  // fill ARGB (semi-transparent)
    );
    private static final GizmoStyle INVALID_STYLE = GizmoStyle.strokeAndFill(
            0xFFFF5555,
            2.0F,
            0x66FF5555
    );

    private static List<BlockPos> previewPositions = List.of();
    private static Direction previewFacing = Direction.NORTH;
    private static boolean previewValid;

    private WallPlacementPreviewRenderer() {}

    public static void setPreviewPositions(Collection<BlockPos> positions, Direction facing) {
        previewPositions = List.copyOf(positions);
        previewFacing = facing;
        previewValid = true;
    }

    public static void clearPreview() {
        previewPositions = List.of();
        previewFacing = Direction.NORTH;
        previewValid = false;
    }

    public static void onClientTick(Minecraft client) {
        if (client.level == null || client.player == null || client.gui.screen() != null) {
            clearPreview();
            return;
        }

        ItemStack held = client.player.getMainHandItem();
        if (!ModBlocks.isBuildingSegment(held) || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            clearPreview();
            return;
        }

        BlockPlaceContext context = new BlockPlaceContext(
                client.player, InteractionHand.MAIN_HAND, held, hit
        );
        BlockPos origin = context.getClickedPos();
        var heldBlock = ((net.minecraft.world.item.BlockItem) held.getItem()).getBlock();
        var placementState = heldBlock.getStateForPlacement(context);
        Direction facing = placementState != null
                && placementState.hasProperty(com.dark.winter.block.WallSegmentBlock.FACING)
                ? placementState.getValue(com.dark.winter.block.WallSegmentBlock.FACING)
                : com.dark.winter.block.WallSegmentBlock.resolvePlacementFacing(context);
        WallShape shape = ModBlocks.isWallSegment(held) ? WallBuildManager.getClientShape() : WallShape.WALL_1X1;
        setPreviewPositions(WallBuildManager.calculatePreviewPositions(origin, facing, shape), facing);

        // Reuse the server's placement direction and complete shape checks.
        previewValid = context.canPlace() && placementState != null
                && placementState.canSurvive(client.level, origin)
                && client.level.isUnobstructed(placementState, origin,
                        CollisionContext.placementContext(client.player));

        GizmoStyle style = previewValid ? VALID_STYLE : INVALID_STYLE;
        // A rejected placement still needs a useful red outline at the intended position.
        var previewState = placementState != null ? placementState : heldBlock.defaultBlockState();
        if (previewState.hasProperty(com.dark.winter.block.WallSegmentBlock.FACING)) {
            previewState = previewState.setValue(com.dark.winter.block.WallSegmentBlock.FACING, previewFacing);
        }
        for (BlockPos pos : previewPositions) {
            for (AABB box : previewState.getShape(client.level, pos, CollisionContext.empty()).toAabbs()) {
                Gizmos.cuboid(box.move(pos), style, false);
            }
        }
    }
}
