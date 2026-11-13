package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** The block state persists the endpoint. Timing is local to a loaded instance. */
public abstract class FurnitureTransitionEntity extends BlockEntity {
    private final BooleanProperty endpoint;
    private final int duration;
    private long began = Long.MIN_VALUE;
    private float from;

    protected FurnitureTransitionEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                        BooleanProperty endpoint, int duration) {
        super(type, pos, state);
        this.endpoint = endpoint;
        this.duration = duration;
    }

    @Override public void setBlockState(BlockState next) {
        if (level != null && getBlockState().is(next.getBlock())
                && getBlockState().getValue(endpoint) != next.getValue(endpoint)) {
            from = progress(0);
            began = level.getGameTime();
        }
        super.setBlockState(next);
    }

    public final float progress(float partial) {
        float target = getBlockState().getValue(endpoint) ? 1 : 0;
        if (level == null || began == Long.MIN_VALUE) return target;
        float t = Math.clamp((level.getGameTime() - began + partial) / duration, 0, 1);
        return Mth.lerp(t * t * (3 - 2 * t), from, target);
    }
}
