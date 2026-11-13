// Repairs explicitly requested natural snow gaps through one native editor Undo transaction.
package com.dark.winter.testing;

import com.dark.winter.block.SnowyPlantFloorRestore;
import com.dark.winter.block.TestSnowyGrassSlabBlock;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestSpruceBlocks;
import com.dark.winter.worldgen.TestTerrainBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Explicit local EDIT action only. No lifecycle callback, automatic scan, forced load or receipt. */
public final class TestingSnowCoverRepair {
    public static final int MIN_RADIUS=1,MAX_RADIUS=32,MAX_REPAIRS=4096,MAX_COLUMN_DEPTH=64;
    private static final int MAX_HISTORY_CELLS=262144;
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE;
    public static final String LABEL="Нативная установка блоков: восстановление снежного покрова";
    public record Result(int repaired,int unloadedChunks,int unloadedColumns,int protectedCount,
                         int historySkipped,int authoredSkipped,int noWinterContext,int examinedColumns,boolean limitReached) { }
    private record Candidate(BlockPos target,BlockState soil) { }
    private TestingSnowCoverRepair() { }

    public static Result repair(ServerPlayer player,int radius)throws IOException {
        if(radius<MIN_RADIUS||radius>MAX_RADIUS)throw new IOException("Радиус восстановления: 1–32 блока");
        TestingServer.Context c=authority(player);
        ServerLevel level=c.server.overworld();
        TestingServer.rebuildSupport(c);
        LongOpenHashSet history=new LongOpenHashSet();int historyCells=0;
        // Both applied and undone operations express retained editing intent.
        for(var operation:c.edits.nativeOperations().values())for(var change:operation.changes()) {
            if(++historyCells>MAX_HISTORY_CELLS)throw new IOException("История превышает предел безопасной проверки");
            history.add(change.pos().asLong());
        }
        int centerX=player.blockPosition().getX(),centerZ=player.blockPosition().getZ();
        Snapshot snapshot=new Snapshot(level,centerX>>4,centerZ>>4);
        int examined=0,unloaded=0,protectedCount=0,historySkipped=0,authored=0,noWinter=0;
        boolean limit=false;
        var plan=new ArrayList<Candidate>();
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        scan:for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            if(dx*dx+dz*dz>radius*radius)continue;
            examined++;
            int x=centerX+dx,z=centerZ+dz;
            LevelChunk chunk=snapshot.chunk(x,z);
            // The native biome zoom and the winter-context reads must stay inside the resident snapshot.
            if(chunk==null||!snapshot.halo(x,z,4)){unloaded++;continue;}
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15);
            int bottom=Math.max(level.getMinY(),top-MAX_COLUMN_DEPTH+1);
            for(int y=top;y>=bottom;y--) {
                cursor.set(x,y,z);BlockState state=chunk.getBlockState(cursor);
                if(!state.getFluidState().isEmpty())break;
                if(state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.PODZOL)) {
                    BlockPos soil=cursor.immutable(),target=soil.above();
                    if(!snapshot.state(target).isAir())break;
                    if(level.isOutsideBuildHeight(target)||level.isOutsideBuildHeight(soil)
                            ||!level.getWorldBorder().isWithinBounds(target)||!level.getWorldBorder().isWithinBounds(soil)
                            ||!level.getBiome(target).is(TestBiomeCompatibility.BIOME)
                            ||!state.isFaceSturdy(level,soil,Direction.UP)
                            ||!Blocks.SNOW.defaultBlockState().canSurvive(level,target))break;
                    if(TestingServer.protectedAt(c,target)||TestingServer.naturalProtectedAt(c,target)
                            ||TestingServer.protectedAt(c,soil)||TestingServer.naturalProtectedAt(c,soil)
                            ||nearRoot(snapshot,target)) {protectedCount++;break;}
                    if(history.contains(target.asLong())||history.contains(soil.asLong())){historySkipped++;break;}
                    if(c.edits.origins.containsKey(target.asLong())||c.edits.origins.containsKey(soil.asLong())){authored++;break;}
                    if(level.getBlockEntity(target)!=null||level.getBlockEntity(soil)!=null){protectedCount++;break;}
                    if(!winterContext(snapshot,target)){noWinter++;break;}
                    if(plan.size()==MAX_REPAIRS){limit=true;break scan;}
                    plan.add(new Candidate(target,state));
                    break;
                }
                if(passThrough(state))continue;
                break; // First solid surface only; never repair a hidden cave or an authored roof.
            }
        }
        if(plan.isEmpty())return new Result(0,snapshot.unloaded,unloaded,protectedCount,historySkipped,authored,noWinter,examined,limit);
        long recorded=TestingNativeEdits.recordedOperations();
        var scope=TestingNativeEdits.begin(player,LABEL);
        if(!scope.recording()){scope.close();throw new IOException("Нет доступной нативной транзакции");}
        try(scope) {
            try {
                // Seed all before-images before the first world write.
                for(Candidate candidate:plan){scope.seed(candidate.target());scope.seed(candidate.target().below());}
                for(Candidate candidate:plan) {
                    BlockPos target=candidate.target(),soil=target.below();
                    if(!snapshot.state(target).isAir()||snapshot.state(soil)!=candidate.soil()
                            ||!level.getFluidState(target).isEmpty())throw new IOException("План восстановления изменился");
                    if(!level.setBlock(target,Blocks.SNOW.defaultBlockState(),FLAGS))throw new IOException("Установка снега отклонена");
                    BlockState snowy=candidate.soil().setValue(SnowyBlock.SNOWY,true);
                    if(candidate.soil()!=snowy&&!level.setBlock(soil,snowy,FLAGS))throw new IOException("Состояние снежной почвы отклонено");
                    TestingNativeEdits.naturalFloorRestored(level,target);
                }
            }catch(Exception failure){scope.abort();throw failure instanceof IOException io?io:new IOException("Восстановление отменено",failure);}
        }
        if(!scope.accepted()||TestingNativeEdits.recordedOperations()!=recorded+1)
            throw new IOException("Восстановление не записано в нативную историю");
        return new Result(plan.size(),snapshot.unloaded,unloaded,protectedCount,historySkipped,authored,noWinter,examined,limit);
    }

    private static TestingServer.Context authority(ServerPlayer player)throws IOException {
        if(player==null)throw new IOException("Нет владельца тестового мира");
        var server=player.level().getServer();var c=TestingServer.context(server);
        if(c==null||!server.isSameThread()||!server.isSingleplayer()||server.isPublished()
                ||!server.isSingleplayerOwner(player.nameAndId())||server.getPlayerList().getPlayer(player.getUUID())!=player
                ||player.level()!=server.overworld()||player.level().dimension()!=Level.OVERWORLD
                ||!TestingServer.isTesting(server)||c.readOnlyPreview||!"EDIT".equals(c.settings.mode)
                ||com.dark.winter.online.server.OnlineGameHost.get(server)!=null)
            throw new IOException("Восстановление доступно только локальному владельцу в EDIT");
        if(TestingServer.busy(server)||TestingNativeEdits.deferSave(server))
            throw new IOException("Дождитесь завершения текущего редактирования");
        return c;
    }

    private static boolean passThrough(BlockState state) {
        return state.isAir()||state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK)
                ||state.is(TestSpruceBlocks.LOG)||state.is(TestSpruceBlocks.BOUGH)
                ||state.getBlock() instanceof VegetationBlock||SnowyPlantFloorRestore.floorPart(state);
    }
    private static boolean nearRoot(Snapshot snapshot,BlockPos target) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++) {
            BlockState state=snapshot.state(target.offset(dx,dy,dz));
            if(state!=null&&(state.is(TestSpruceBlocks.LOG)||state.is(BlockTags.LOGS)))return true;
        }
        return false;
    }
    private static boolean winterContext(Snapshot snapshot,BlockPos target) {
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++) {
            BlockState state=snapshot.state(target.offset(dx,dy,dz));
            if(state!=null&&(state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK)||SnowyPlantFloorRestore.floorPart(state)
                    ||state.is(TestTerrainBlocks.SNOWY_GRASS_SLAB)&&state.getValue(TestSnowyGrassSlabBlock.SNOWY)))return true;
        }
        return false;
    }
    private static final class Snapshot {
        final Map<Long,LevelChunk> chunks=new HashMap<>();
        final int unloaded;
        Snapshot(ServerLevel level,int centerX,int centerZ) {
            int missing=0;
            for(int x=centerX-2;x<=centerX+2;x++)for(int z=centerZ-2;z<=centerZ+2;z++) {
                LevelChunk chunk=level.getChunkSource().getChunkNow(x,z);
                if(chunk==null)missing++;else chunks.put(key(x,z),chunk);
            }
            unloaded=missing;
        }
        LevelChunk chunk(int x,int z){return chunks.get(key(x>>4,z>>4));}
        BlockState state(BlockPos pos){LevelChunk chunk=chunk(pos.getX(),pos.getZ());return chunk==null?null:chunk.getBlockState(pos);}
        boolean halo(int x,int z,int radius){return chunk(x-radius,z-radius)!=null&&chunk(x-radius,z+radius)!=null
                &&chunk(x+radius,z-radius)!=null&&chunk(x+radius,z+radius)!=null;}
        static long key(int x,int z){return ((long)x&0xffffffffL)|((long)z<<32);}
    }
}
