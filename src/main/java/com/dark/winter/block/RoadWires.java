package com.dark.winter.block;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded, saved decorative links; no electrical simulation, ticking network or block entities. */
public final class RoadWires {
    public static final int MAX_SPAN=16;
    private static final ThreadLocal<Boolean> MUTATING=ThreadLocal.withInitial(()->false);
    public static final UtilityWireBlock WIRE=register();
    private static UtilityWireBlock register(){
        var key=ResourceKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath("darkwinter","utility_wire"));
        return Registry.register(BuiltInRegistries.BLOCK,key,new UtilityWireBlock(Block.Properties.of().setId(key).noCollision().noOcclusion().replaceable().noLootTable().strength(0)));
    }
    private RoadWires(){}
    public static boolean mutating(){return MUTATING.get();}
    public static void initialize(){
        ServerChunkEvents.CHUNK_LOAD.register((level,chunk,generated)->{
            var sections=chunk.getSections();
            for(int sy=0;sy<sections.length;sy++){
                var section=sections[sy];if(!section.maybeHas(s->s.getBlock() instanceof RoadUtilityPoleBlock&&s.getValue(FurnitureBlock.PART)==0))continue;
                int y0=chunk.getSectionYFromSectionIndex(sy)*16;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    var state=section.getBlockState(x,y,z);
                    if(state.getBlock() instanceof RoadUtilityPoleBlock&&state.getValue(FurnitureBlock.PART)==0)
                        level.scheduleTick(new BlockPos(chunk.getPos().getMinBlockX()+x,y0+y,chunk.getPos().getMinBlockZ()+z),state.getBlock(),2);
                }
            }
        });
    }
    public static boolean pole(LevelReader level,BlockPos root,Direction.Axis axis){
        if(!level.hasChunkAt(root))return false;
        var state=level.getBlockState(root);
        if(!(state.getBlock() instanceof RoadUtilityPoleBlock)||state.getValue(FurnitureBlock.PART)!=0||state.getValue(FurnitureBlock.FACING).getAxis()!=axis)return false;
        for(int part=1;part<3;part++){
            var next=level.getBlockState(root.above(part));
            if(next.getBlock()!=state.getBlock()||next.getValue(FurnitureBlock.PART)!=part||next.getValue(FurnitureBlock.FACING)!=state.getValue(FurnitureBlock.FACING))return false;
        }
        return true;
    }
    private static Direction positive(Direction.Axis axis){return axis==Direction.Axis.X?Direction.EAST:Direction.SOUTH;}
    public static boolean validLink(LevelReader level,BlockPos pos,BlockState wire){
        if(!wire.is(WIRE))return false;
        int index=wire.getValue(UtilityWireBlock.INDEX),distance=wire.getValue(UtilityWireBlock.DISTANCE);if(index>=distance)return false;
        var axis=wire.getValue(UtilityWireBlock.AXIS);var direction=positive(axis);
        var start=pos.relative(direction,-index).below(2);var end=start.relative(direction,distance);
        // Unloaded endpoints are unknown, not a reason to destroy a saved span.
        if(!level.hasChunkAt(start)||!level.hasChunkAt(end))return true;
        return pole(level,start,axis)&&pole(level,end,axis);
    }
    public static void rebuild(ServerLevel level,BlockPos root){
        if(mutating())return;
        var state=level.getBlockState(root);if(!(state.getBlock() instanceof RoadUtilityPoleBlock))return;
        var axis=state.getValue(FurnitureBlock.FACING).getAxis();if(!pole(level,root,axis))return;
        MUTATING.set(true);
        try{
            var positive=positive(axis);
            for(int sign:new int[]{-1,1})for(int distance=1;distance<=MAX_SPAN;distance++){
                var peer=root.relative(positive,sign*distance);
                if(!level.hasChunkAt(peer))break;
                var other=level.getBlockState(peer);
                if(!(other.getBlock() instanceof RoadUtilityPoleBlock)||other.getValue(FurnitureBlock.PART)!=0)continue;
                if(distance<2||!pole(level,peer,axis))break;
                var start=sign>0?root:peer;boolean clear=true;
                for(int i=1;i<distance;i++){
                    var cell=start.above(2).relative(positive,i);var here=level.getBlockState(cell);
                    if(!level.hasChunkAt(cell)||!here.isAir()&&(!here.is(WIRE)
                            ||here.getValue(UtilityWireBlock.AXIS)!=axis
                            ||here.getValue(UtilityWireBlock.DISTANCE)!=distance
                            ||here.getValue(UtilityWireBlock.INDEX)!=i)){clear=false;break;}
                }
                if(clear)for(int i=1;i<distance;i++){
                    var cell=start.above(2).relative(positive,i);
                    var wire=WIRE.defaultBlockState().setValue(UtilityWireBlock.AXIS,axis).setValue(UtilityWireBlock.DISTANCE,distance).setValue(UtilityWireBlock.INDEX,i);
                    if(level.getBlockState(cell)!=wire)level.setBlock(cell,wire,Block.UPDATE_ALL);
                }
                break;
            }
        }finally{MUTATING.remove();}
    }
    public static void clearLink(ServerLevel level,BlockPos pos,BlockState wire){
        if(mutating()||!wire.is(WIRE))return;
        MUTATING.set(true);
        try{
            var axis=wire.getValue(UtilityWireBlock.AXIS);var direction=positive(axis);int distance=wire.getValue(UtilityWireBlock.DISTANCE);
            var start=pos.relative(direction,-wire.getValue(UtilityWireBlock.INDEX));
            for(int i=1;i<distance;i++){
                var cell=start.relative(direction,i);if(!level.hasChunkAt(cell))continue;
                var here=level.getBlockState(cell);
                if(here.is(WIRE)&&here.getValue(UtilityWireBlock.AXIS)==axis&&here.getValue(UtilityWireBlock.DISTANCE)==distance&&here.getValue(UtilityWireBlock.INDEX)==i)
                    level.setBlock(cell,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
            }
        }finally{MUTATING.remove();}
    }
    public static void removePoleLinks(ServerLevel level,BlockPos root,Direction.Axis axis){
        var direction=positive(axis);
        for(int sign:new int[]{-1,1}){
            var cell=root.above(2).relative(direction,sign);var wire=level.getBlockState(cell);
            if(wire.is(WIRE))clearLink(level,cell,wire);
            for(int d=2;d<=MAX_SPAN;d++){
                var peer=root.relative(direction,sign*d);if(!level.hasChunkAt(peer))break;
                var s=level.getBlockState(peer);
                if(s.getBlock() instanceof RoadUtilityPoleBlock&&s.getValue(FurnitureBlock.PART)==0){level.scheduleTick(peer,s.getBlock(),2);break;}
            }
        }
    }
}
