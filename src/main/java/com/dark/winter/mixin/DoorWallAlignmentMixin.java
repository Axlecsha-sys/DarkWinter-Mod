// Existing vanilla doors snap to a neighboring wall/window's outside edge.
package com.dark.winter.mixin;

import com.dark.winter.block.BuildingAlignment;
import com.dark.winter.component.WallShape;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Existing vanilla doors snap to a neighboring wall/window's outside edge. */
@Mixin(DoorBlock.class)
public abstract class DoorWallAlignmentMixin {
    @Shadow
    private DoorHingeSide getHinge(BlockPlaceContext context) {
        throw new AssertionError();
    }

    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void darkwinter$alignDoor(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null) return;
        Direction plane = BuildingAlignment.resolve(context, WallShape.WALL_1X1,
                state.getValue(DoorBlock.FACING).getOpposite());
        Direction facing = plane.getOpposite();
        if (facing == state.getValue(DoorBlock.FACING)) return;
        // Recompute hinges using the snapped direction, including ordinary double doors.
        BlockPlaceContext alignedContext = BuildingAlignment.withDirection(context, facing);
        cir.setReturnValue(state.setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, getHinge(alignedContext)));
    }
}
