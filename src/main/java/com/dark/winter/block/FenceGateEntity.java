package com.dark.winter.block;

import com.dark.winter.interaction.FenceGateInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class FenceGateEntity extends IndexedFurnitureSwingEntity {
    public FenceGateEntity(BlockPos pos, BlockState state) {
        super(FenceGateInteraction.TYPE, pos, state, FenceGateFurnitureBlock.OPEN, 12);
    }
}
