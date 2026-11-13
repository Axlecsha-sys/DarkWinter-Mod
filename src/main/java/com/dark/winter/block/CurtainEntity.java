package com.dark.winter.block;

import com.dark.winter.interaction.CurtainInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class CurtainEntity extends FurnitureTransitionEntity {
    public CurtainEntity(BlockPos pos, BlockState state) {
        super(CurtainInteraction.TYPE, pos, state, CurtainedWindowBlock.CURTAINS_CLOSED, 15);
    }
}
