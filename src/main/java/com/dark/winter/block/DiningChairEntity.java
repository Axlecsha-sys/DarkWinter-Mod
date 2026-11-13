package com.dark.winter.block;

import com.dark.winter.interaction.DiningChairInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class DiningChairEntity extends IndexedFurnitureSwingEntity {
    private record SlideShape(VoxelShape source, BlockState state, float progress, VoxelShape moved) {}

    private SlideShape outlineShape;
    private SlideShape collisionShape;

    public DiningChairEntity(BlockPos pos, BlockState state) {
        super(DiningChairInteraction.TYPE, pos, state, DiningChairBlock.TUCKED, DiningChairBlock.SLIDE_TICKS);
    }

    VoxelShape slidShape(VoxelShape source, BlockState state, boolean collision) {
        float progress = progress(0);
        if (progress == 0 || source.isEmpty()) return source;
        SlideShape last = collision ? collisionShape : outlineShape;
        if (last != null && last.source == source && last.state == state && last.progress == progress) return last.moved;
        var next = new SlideShape(source, state, progress, source.move(DiningChairBlock.slide(state, progress)));
        if (collision) collisionShape = next;
        else outlineShape = next;
        return next.moved;
    }
}
