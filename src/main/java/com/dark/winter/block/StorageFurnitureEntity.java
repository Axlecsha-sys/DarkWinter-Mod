package com.dark.winter.block;

import com.dark.winter.interaction.StorageFurnitureInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class StorageFurnitureEntity extends IndexedFurnitureSwingEntity {
    public StorageFurnitureEntity(BlockPos pos, BlockState state) {
        super(StorageFurnitureInteraction.TYPE, pos, state, StorageFurnitureBlock.OPEN,
                state.getBlock() instanceof StorageFurnitureBlock block
                        && block.kind() == StorageFurnitureBlock.Kind.FRIDGE ? 14 : 11);
    }
}
