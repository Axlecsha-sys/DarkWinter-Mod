// Two authored, permanent visual variants, enabled only by explicit catalogue metadata.
package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/** Two authored, permanent visual variants, enabled only by explicit catalogue metadata. */
public final class SnowVariantFurnitureBlock extends FurnitureBlock {
    public static final BooleanProperty SNOWY = BooleanProperty.create("snowy");

    public SnowVariantFurnitureBlock(FurnitureCatalog.Entry definition, Properties properties) {
        super(definition, properties);
        registerDefaultState(defaultBlockState().setValue(SNOWY, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SNOWY);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(SNOWY, isSnowy(context.getItemInHand()));
    }

    @Override
    protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(SNOWY, source.getValue(SNOWY));
    }

    @Override
    public ItemStack stackForState(BlockState state) {
        return stack(this, state.getValue(SNOWY));
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return stackForState(state);
    }

    public static boolean isSnowy(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof SnowVariantFurnitureBlock
                && Boolean.TRUE.equals(stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).get(SNOWY));
    }

    public static ItemStack stack(SnowVariantFurnitureBlock block, boolean snowy) {
        ItemStack stack = new ItemStack(block);
        if (snowy) {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(SNOWY, true));
            stack.set(DataComponents.CUSTOM_NAME,
                    Component.translatable("item.darkwinter.snow_variant", stack.getHoverName()).withStyle(style -> style.withItalic(false)));
        }
        return stack;
    }
}
