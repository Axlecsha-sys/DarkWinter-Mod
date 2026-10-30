// Captures the actual changed footprint, including both halves of doors and beds.
package com.dark.winter.mixin;

import com.dark.winter.testing.TestingServer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the actual changed footprint, including both halves of doors and beds. */
@Mixin(BlockItem.class)
public abstract class TestingPlacementMixin {
    @Unique private static final ThreadLocal<Map<BlockPos,BlockState>> darkwinter$before=ThreadLocal.withInitial(HashMap::new);
    @Inject(method="place",at=@At("HEAD"))
    private void darkwinter$rememberPlacement(BlockPlaceContext context,CallbackInfoReturnable<InteractionResult> cir) {
        Map<BlockPos,BlockState> before=darkwinter$before.get();before.clear();
        var level=context.getLevel();
        if(level.isClientSide()||!TestingServer.isTesting(level.getServer()))return;
        BlockPos center=context.getClickedPos();
        for(BlockPos pos:BlockPos.betweenClosed(center.offset(-1,-1,-1),center.offset(1,2,1)))
            before.put(pos.immutable(),level.getBlockState(pos));
    }
    @Inject(method="place",at=@At("RETURN"))
    private void darkwinter$authorPlacement(BlockPlaceContext context,CallbackInfoReturnable<InteractionResult> cir) {
        Map<BlockPos,BlockState> before=darkwinter$before.get();
        if(!context.getLevel().isClientSide()&&cir.getReturnValue().consumesAction())
            before.forEach((pos,state)->{if(!state.equals(context.getLevel().getBlockState(pos)))TestingServer.authored(context.getLevel(),pos);});
        before.clear();
    }
}
