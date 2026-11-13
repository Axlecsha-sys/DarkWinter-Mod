package com.dark.winter.block;

import com.dark.winter.interaction.PanelDoorInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class PanelDoorEntity extends FurnitureTransitionEntity {
    public PanelDoorEntity(BlockPos pos, BlockState state) {
        super(PanelDoorInteraction.TYPE, pos, state, PanelDoorBlock.OPEN, PanelDoorBlock.SWING_TICKS);
    }
}
