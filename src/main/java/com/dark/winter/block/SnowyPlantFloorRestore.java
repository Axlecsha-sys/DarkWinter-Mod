// Restores real floor snow only after an opted-in snowy plant assembly has been removed.
package com.dark.winter.block;

import com.dark.winter.worldgen.TestBiomeCompatibility;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Event-driven, nonpersistent repair; ordinary worlds, bare plants and replacement blocks are untouched. */
public final class SnowyPlantFloorRestore {
    public static final int MAX_PENDING_PER_LEVEL=4096,MAX_PER_LEVEL_TICK=128;
    private static final Map<ServerLevel,Pending> PENDING=Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean initialized;
    private static final class Pending {
        final DeferredFloorQueue queue=new DeferredFloorQueue(MAX_PENDING_PER_LEVEL);
        long enqueued,restored,skipped,nativeRestored;
    }
    public record Snapshot(int pending,long enqueued,long restored,long skipped,long overflow,long nativeRestored){}
    private SnowyPlantFloorRestore(){}
    public static synchronized void initialize(){
        if(initialized)return;initialized=true;
        ServerTickEvents.END_LEVEL_TICK.register(SnowyPlantFloorRestore::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            synchronized(PENDING){PENDING.keySet().removeIf(level->level.getServer()==server);}
        });
    }
    static void enqueue(ServerLevel level,BlockPos origin){
        if(!level.getServer().isSameThread()||level.isOutsideBuildHeight(origin)||!level.hasChunkAt(origin)
                ||!level.getBiome(origin).is(TestBiomeCompatibility.BIOME))return;
        Pending state; synchronized(PENDING){state=PENDING.computeIfAbsent(level,ignored->new Pending());}
        if(state.queue.offer(origin.asLong(),level.getGameTime()))state.enqueued++;
    }
    /** Tree cells contain a rendered floor only when grounded in the dedicated snowy biome. */
    public static void treeRemoved(net.minecraft.world.level.Level level,BlockPos pos,BlockState former){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return;
        boolean tree=former.is(com.dark.winter.worldgen.TestSpruceBlocks.BOUGH)
                ||former.is(com.dark.winter.worldgen.TestSpruceBlocks.LOG)
                &&former.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)==net.minecraft.core.Direction.Axis.Y;
        if(!tree)return;
        BlockState support=level.getBlockState(pos.below());
        if((support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL)||support.is(BlockTags.DIRT))
                &&support.isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP))enqueue(server,pos);
    }
    /** The authored snow floor covers every horizontal foot cell, never a shrub's upper half. */
    public static boolean floorPart(BlockState state){
        if(!(state.getBlock() instanceof SnowVariantFurnitureBlock block)
                ||!state.getValue(SnowVariantFurnitureBlock.SNOWY))return false;
        int part=state.getValue(FurnitureBlock.PART);
        if(part>=block.definition().parts().size()
                ||block.partOffset(part,state.getValue(FurnitureBlock.FACING)).getY()!=0)return false;
        var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals("darkwinter")&&switch(id.getPath()){
            case "dry_grass_tuft","snowy_shrub","forest_fern","forest_branch_pile","fallen_spruce_log","spruce_stump",
                    "rock_scatter","boulder_small","boulder_large"->true;
            default->false;
        };
    }
    private static Pending pending(ServerLevel level){synchronized(PENDING){return PENDING.get(level);}}
    private static void tick(ServerLevel level){
        Pending state=pending(level);if(state==null)return;
        state.queue.drain(level.getGameTime(),MAX_PER_LEVEL_TICK,origin->restore(level,state,origin,false));
    }
    /**
     * Called only when the native destructive action has returned and before that gesture
     * snapshots its final cells. Neighbour changes are still captured by the same Undo scope.
     */
    public static void finishNativeGesture(ServerLevel level,Collection<BlockPos> touched){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Native snow repair requires the level owner");
        Pending state=pending(level);if(state==null||state.queue.size()==0)return;
        LongArrayList origins=new LongArrayList();
        // Snapshot before writes: Snow/Grass neighbour updates can extend the native before-map.
        for(BlockPos pos:touched)if(state.queue.contains(pos.asLong()))origins.add(pos.asLong());
        for(int i=0;i<origins.size();i++){long origin=origins.getLong(i);if(state.queue.take(origin))restore(level,state,origin,true);}
    }
    private static void restore(ServerLevel level,Pending pending,long packed,boolean nativeGesture){
        BlockPos pos=BlockPos.of(packed);
        if(level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos)||!level.hasChunkAt(pos)
                ||!level.getBlockState(pos).isAir()||!level.getFluidState(pos).isEmpty()
                ||!level.getBiome(pos).is(TestBiomeCompatibility.BIOME)){pending.skipped++;return;}
        BlockState support=level.getBlockState(pos.below());
        if(!(support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL)||support.is(BlockTags.DIRT))
                ||!support.getFluidState().isEmpty()){pending.skipped++;return;}
        BlockState snow=Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,1);
        if(!snow.canSurvive(level,pos)||!level.setBlock(pos,snow,Block.UPDATE_ALL)){pending.skipped++;return;}
        com.dark.winter.testing.TestingNativeEdits.naturalFloorRestored(level,pos);
        pending.restored++;if(nativeGesture)pending.nativeRestored++;
    }
    /** Creates diagnostics only on request; pending values contain no level or server references. */
    public static Snapshot snapshot(ServerLevel level){
        Pending state=pending(level);return state==null?new Snapshot(0,0,0,0,0,0):
                new Snapshot(state.queue.size(),state.enqueued,state.restored,state.skipped,state.queue.overflow(),state.nativeRestored);
    }
}
