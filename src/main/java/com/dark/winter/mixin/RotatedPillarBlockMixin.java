// Adapts narrow trunk shapes to native rotated-pillar geometry.
package com.dark.winter.mixin;

import com.dark.winter.block.ThinTrunks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * RotatedPillarBlock inherits these methods in 26.3, so these are added overrides.
 * Vanilla derives its cached collision, occlusion and visual shapes from getShape.
 */
@Mixin(RotatedPillarBlock.class)
public abstract class RotatedPillarBlockMixin extends Block {
    protected RotatedPillarBlockMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ThinTrunks.isThin(state) ? ThinTrunks.shape(state) : super.getShape(state, level, pos, context);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return ThinTrunks.isThin(state) || super.useShapeForLightOcclusion(state);
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        // Keep existing torches, ladders and other attachments on replaced logs.
        // Support does not enlarge the player's collision or selection outline.
        return ThinTrunks.isThin(state) ? Shapes.block() : super.getBlockSupportShape(state, level, pos);
    }
}
