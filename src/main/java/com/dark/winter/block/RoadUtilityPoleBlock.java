package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Existing pole ID/assembly; links are non-colliding decorative cable cells. */
public final class RoadUtilityPoleBlock extends FurnitureBlock {
    public RoadUtilityPoleBlock(FurnitureCatalog.Entry entry, Properties properties) { super(entry, properties); }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,@Nullable LivingEntity owner,ItemStack stack) {
        super.setPlacedBy(level,pos,state,owner,stack);
        if(level instanceof ServerLevel server&&server.getBlockState(pos).is(this))server.scheduleTick(pos,this,1);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
        if(state.getValue(PART)==0)RoadWires.rebuild(level,pos);
    }
    @Override protected void afterAssemblyRemoval(Level level,BlockPos origin,BlockState formerRoot) {
        if(level instanceof ServerLevel server)RoadWires.removePoleLinks(server,origin,formerRoot.getValue(FACING).getAxis());
    }
}
