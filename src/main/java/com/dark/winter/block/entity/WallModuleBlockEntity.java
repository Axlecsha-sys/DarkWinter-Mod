// Persists a wall module master position and its shared shape.
package com.dark.winter.block.entity;

import com.dark.winter.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.dark.winter.component.WallShape;

public class WallModuleBlockEntity extends BlockEntity {
    private BlockPos masterPos = this.worldPosition;
    private WallShape shape = WallShape.WALL_1X1;

    public WallModuleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WALL_MODULE_BE, pos, state);
    }

    public void setup(BlockPos masterPos, WallShape shape) {
        this.masterPos = masterPos;
        this.shape = shape;
        this.setChanged();
    }

    public BlockPos getMasterPos() { return masterPos; }
    public WallShape getShape() { return shape; }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("MasterPos", masterPos.asLong());
        output.putString("WallShape", shape.getSerializedName());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.masterPos = BlockPos.of(input.getLongOr("MasterPos", this.worldPosition.asLong()));
        String shapeName = input.getStringOr("WallShape", "1x1");
        for (WallShape s : WallShape.values()) {
            if (s.getSerializedName().equals(shapeName)) {
                this.shape = s;
                break;
            }
        }
    }
}