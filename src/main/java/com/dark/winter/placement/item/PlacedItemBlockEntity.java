// ItemStack.CODEC preserves the actual item and all its data components across save and client updates.
package com.dark.winter.placement.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class PlacedItemBlockEntity extends BlockEntity {
    private ItemStack stack = ItemStack.EMPTY;

    public PlacedItemBlockEntity(BlockPos pos, BlockState state) { super(PlacedItems.TYPE, pos, state); }

    /** Return an owned copy so callers cannot mutate the stored inventory through a render/drop view. */
    public ItemStack stack() { return stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1); }

    public void setStack(ItemStack item) {
        stack = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
        setChanged();
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!stack.isEmpty()) output.store("item", ItemStack.CODEC, stack);
    }

    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ItemStack saved = input.read("item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        stack = saved.isEmpty() ? ItemStack.EMPTY : saved.copyWithCount(1);
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveCustomOnly(registries); }
}
