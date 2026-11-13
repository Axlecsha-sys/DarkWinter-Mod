package com.dark.winter.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/** Internal no-item visual carrier: every wire fragment stays in its own chunk cell. */
public final class UtilityWireBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS=EnumProperty.create("axis",Direction.Axis.class,Direction.Axis.X,Direction.Axis.Z);
    public static final IntegerProperty DISTANCE=IntegerProperty.create("distance",2,RoadWires.MAX_SPAN);
    public static final IntegerProperty INDEX=IntegerProperty.create("index",1,RoadWires.MAX_SPAN-1);
    public UtilityWireBlock(Properties properties) {
        super(properties);registerDefaultState(stateDefinition.any().setValue(AXIS,Direction.Axis.Z).setValue(DISTANCE,2).setValue(INDEX,1));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(AXIS,DISTANCE,INDEX);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.empty();}
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction side,BlockPos otherPos,BlockState other,RandomSource random){
        ticks.scheduleTick(pos,this,1);return state;
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!RoadWires.validLink(level,pos,state))RoadWires.clearLink(level,pos,state);
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state,ServerLevel level,BlockPos pos,boolean piston){
        if(!RoadWires.mutating())RoadWires.clearLink(level,pos,state);
    }
}
