package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A root whose moving parts can occupy cells outside its saved footprint. */
abstract class IndexedFurnitureSwingEntity extends FurnitureTransitionEntity {
    private final Object indexToken = new Object();
    private SwingShape lastShape;

    private record SwingShape(FurnitureBlock furniture, Direction facing, int angle, double yaw, VoxelShape shape) {}

    IndexedFurnitureSwingEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                BooleanProperty endpoint, int duration) {
        super(type, pos, state, endpoint, duration);
    }

    VoxelShape swingShape(FurnitureBlock furniture, Direction facing, int angle, double yaw) {
        SwingShape cached = lastShape;
        if (cached == null || cached.furniture() != furniture || cached.facing() != facing
                || cached.angle() != angle || cached.yaw() != yaw) {
            cached = new SwingShape(furniture, facing, angle, yaw, FenceGateGeometry.pose(furniture, facing, angle, yaw));
            lastShape = cached;
        }
        return cached.shape();
    }

    @Override public void setLevel(Level level) {
        if (this.level != null) FenceGateGeometry.remove(this.level, worldPosition, indexToken);
        super.setLevel(level);
    }

    @Override public void clearRemoved() {
        super.clearRemoved();
        if (level != null) FenceGateGeometry.add(level, worldPosition, indexToken);
    }

    @Override public void setRemoved() {
        super.setRemoved();
        if (level != null) FenceGateGeometry.remove(level, worldPosition, indexToken);
    }
}
