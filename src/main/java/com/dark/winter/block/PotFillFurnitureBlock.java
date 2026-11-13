// Three authored decorative appearances of the existing T01 pot, with no fluid mechanics.
package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/** Three permanent visual variants; this block has no fill, melt or cooking actions. */
public final class PotFillFurnitureBlock extends FurnitureBlock {
    public static final String ID = "camp_cooking_pot";

    public enum Fill implements StringRepresentable {
        CLOSED("closed"), SNOW("snow"), WATER("water");

        private final String serializedName;

        Fill(String serializedName) { this.serializedName = serializedName; }

        @Override
        public String getSerializedName() { return serializedName; }
    }

    public static final EnumProperty<Fill> POT_FILL = EnumProperty.create("pot_fill", Fill.class);

    public PotFillFurnitureBlock(FurnitureCatalog.Entry definition, Properties properties) {
        super(definition, properties);
        if (!definition.id().equals(ID) || definition.snowVariants() || definition.parts().size() != 1) {
            throw new IllegalArgumentException("Pot fill variants belong only to the existing single-cell T01 pot");
        }
        registerDefaultState(defaultBlockState().setValue(POT_FILL, Fill.CLOSED));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POT_FILL);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(POT_FILL, fill(context.getItemInHand()));
    }

    @Override
    protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(POT_FILL, source.getValue(POT_FILL));
    }

    @Override
    public ItemStack stackForState(BlockState state) {
        return stack(this, state.getValue(POT_FILL));
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return stackForState(state);
    }

    /** Missing or invalid item state is the historical closed pot. */
    public static Fill fill(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof PotFillFurnitureBlock)) {
            return Fill.CLOSED;
        }
        Fill value = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).get(POT_FILL);
        return value == null ? Fill.CLOSED : value;
    }

    /** The ordinary closed stack remains component-free and keeps its old name. */
    public static ItemStack stack(PotFillFurnitureBlock block, Fill fill) {
        ItemStack stack = new ItemStack(block);
        if (fill != Fill.CLOSED) {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(POT_FILL, fill));
            stack.set(DataComponents.CUSTOM_NAME,
                    Component.translatable("item.darkwinter.pot_fill." + fill.getSerializedName(), stack.getHoverName())
                            .withStyle(style -> style.withItalic(false)));
        }
        return stack;
    }
}
