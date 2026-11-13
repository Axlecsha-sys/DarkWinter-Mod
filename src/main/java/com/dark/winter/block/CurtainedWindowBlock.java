package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

public final class CurtainedWindowBlock extends FurnitureBlock implements EntityBlock {
    public static final BooleanProperty CURTAINS_CLOSED = BooleanProperty.create("curtains_closed");
    public static final String TRANSOM_ID = "cabin_transom_window";
    public static final String ATTIC_ID = "cabin_attic_window";

    public CurtainedWindowBlock(FurnitureCatalog.Entry entry, Properties properties) {
        super(entry, properties);
        registerDefaultState(defaultBlockState().setValue(CURTAINS_CLOSED, false));
    }

    public static boolean isCurtained(String id) {
        return TRANSOM_ID.equals(id) || ATTIC_ID.equals(id);
    }

    public static boolean isRoot(BlockState state) {
        return state.getBlock() instanceof CurtainedWindowBlock && state.getValue(PART) == 0;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CURTAINS_CLOSED);
    }

    @Override protected BlockState copyVisualState(BlockState source, BlockState target) {
        return target.setValue(CURTAINS_CLOSED, source.getValue(CURTAINS_CLOSED));
    }

    @Override public @Nullable CurtainEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state) ? new CurtainEntity(pos, state) : null;
    }
}
