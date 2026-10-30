// Applies authored spruce foliage collision shapes to native leaf behavior.
package com.dark.winter.mixin;

import com.dark.winter.block.SpruceFoliage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/** LeavesBlock inherits this exact BlockBehaviour method in Mojang-mapped Minecraft 26.3. */
@Mixin(LeavesBlock.class)
public abstract class SpruceLeavesCollisionMixin extends Block {
    protected SpruceLeavesCollisionMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SpruceFoliage.isSpruce(state) ? SpruceFoliage.collision(state)
                : super.getCollisionShape(state, level, pos, context);
    }
}
