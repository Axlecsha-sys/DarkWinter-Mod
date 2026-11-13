// Repairs only proven historical snowy-object deletions once per marked local editing world.
package com.dark.winter.testing;

import com.dark.winter.block.SnowyPlantFloorRestore;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/** One bounded history pass, one native Undo transaction, one atomic receipt; no terrain sweep. */
public final class TestingSnowFloorRecovery {
    static final String LABEL="Нативная установка блоков: восстановление снежного пола";
    static final String RECEIPT="snow-floor-recovery-v1.json";
    static final int MAX_CANDIDATES=4096,MAX_HISTORY_CELLS=131072;
    private final TestingServer.Context context;
    private final Path receipt;
    private List<TestingEdits.Operation> sourceHistory;
    private List<BlockPos> candidates=List.of();
    private boolean completed,failed;
    private int restored,skipped,unloaded;
    private long nextAttempt;
    private String operationId="",error="";
    public record Snapshot(boolean completed,boolean failed,int candidates,int restored,int skipped,int unloaded,String operationId,String error){}

    TestingSnowFloorRecovery(TestingServer.Context context){
        this.context=context;receipt=context.directory.resolve(RECEIPT);
        if(context.readOnlyPreview)return;
        if(Files.exists(receipt))try{
            JsonObject saved=JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
            if(saved.get("schema").getAsInt()!=1||saved.get("version").getAsInt()!=1||!saved.get("completed").getAsBoolean())
                throw new IOException("Unknown snow-floor recovery receipt");
            completed=true;restored=saved.get("restored").getAsInt();skipped=saved.get("skipped").getAsInt();
            operationId=saved.get("operationId").getAsString();
        }catch(Exception failure){fail(failure);}
    }
    public static Snapshot snapshot(MinecraftServer server){
        var c=TestingServer.context(server);
        return c==null?new Snapshot(false,false,0,0,0,0,"","No marked test world"):c.snowFloorRecovery.snapshot();
    }
    Snapshot snapshot(){return new Snapshot(completed,failed,candidates.size(),restored,skipped,unloaded,operationId,error);}

    void tick(){
        MinecraftServer server=context.server;
        if(completed||failed||context.readOnlyPreview||!server.isSameThread()||!server.isSingleplayer()
                ||server.isPublished()||!"EDIT".equals(context.settings.mode)||TestingServer.busy(server))return;
        long now=server.getTickCount();if(now<nextAttempt)return;nextAttempt=now+20;
        ServerPlayer owner=null;
        for(ServerPlayer player:server.getPlayerList().getPlayers())
            if(player.level()==server.overworld()&&server.isSingleplayerOwner(player.nameAndId())){owner=player;break;}
        if(owner==null)return;
        try{
            // A committed recovery still present in Redo proves completion even if a previous
            // receipt write was interrupted. Never undo the owner's later Undo on restart.
            if(context.edits.snowFloorRecoveryRecorded()){writeReceipt();return;}
            List<TestingEdits.Operation> current=context.edits.snowFloorAppliedHistory();
            if(sourceHistory==null||!current.equals(sourceHistory)){
                candidates=historicalCandidates(current);sourceHistory=current;
            }
            ServerLevel level=server.overworld();unloaded=0;
            for(BlockPos pos:candidates)if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)unloaded++;
            if(unloaded>0)return;
            var eligible=new ArrayList<BlockPos>();
            for(BlockPos pos:candidates)if(eligible(level,pos))eligible.add(pos);
            skipped=candidates.size()-eligible.size();
            if(eligible.isEmpty()){writeReceipt();return;}
            long before=TestingNativeEdits.recordedOperations();
            var scope=TestingNativeEdits.begin(owner,LABEL);
            if(!scope.recording()){scope.close();return;}
            try(scope){
                try{
                    for(BlockPos pos:eligible){
                        if(!eligible(level,pos))throw new IOException("Snow recovery changed during its native scope");
                        scope.seed(pos);
                        BlockState snow=Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,1);
                        if(!level.setBlock(pos,snow,Block.UPDATE_ALL))throw new IOException("Native snow placement refused");
                        TestingNativeEdits.naturalFloorRestored(level,pos);
                    }
                }catch(Exception failure){scope.abort();throw failure;}
            }
            if(!scope.accepted()||TestingNativeEdits.recordedOperations()!=before+1)
                throw new IOException("Snow recovery did not enter durable native Undo history");
            for(BlockPos pos:eligible)if(!level.getBlockState(pos).is(Blocks.SNOW))
                throw new IOException("Snow recovery final state was not preserved");
            restored=eligible.size();
            var committed=context.edits.snowFloorAppliedHistory();
            operationId=committed.isEmpty()?"":committed.getFirst().id();
            writeReceipt();
            context.message="Восстановлен снежный пол: "+restored+" блоков. Изменение доступно в Undo.";
        }catch(Exception failure){fail(failure);}
    }

    private static List<BlockPos> historicalCandidates(List<TestingEdits.Operation> newestFirst)throws IOException{
        LongOpenHashSet seen=new LongOpenHashSet();var result=new ArrayList<BlockPos>();int inspected=0;
        for(var operation:newestFirst)for(var change:operation.changes()){
            if(++inspected>MAX_HISTORY_CELLS)throw new IOException("Snow recovery history exceeds bounded inspection");
            if(!seen.add(change.pos().asLong()))continue;
            if(change.after().state().isAir()&&SnowyPlantFloorRestore.floorPart(change.before().state())){
                if(result.size()>=MAX_CANDIDATES)throw new IOException("Snow recovery candidate limit exceeded");
                result.add(change.pos().immutable());
            }
        }
        return List.copyOf(result);
    }
    private boolean eligible(ServerLevel level,BlockPos pos){
        if(level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos)
                ||level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null
                ||!level.getBlockState(pos).isAir()||!level.getFluidState(pos).isEmpty()
                ||!level.getBiome(pos).is(TestBiomeCompatibility.BIOME)
                ||TestingServer.zoneProtected(context,pos)||TestingServer.naturalZoneProtected(context,pos)
                ||TestingServer.zoneProtected(context,pos.below())||TestingServer.naturalZoneProtected(context,pos.below()))return false;
        BlockState support=level.getBlockState(pos.below());
        return (support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL)||support.is(BlockTags.DIRT))
                &&support.getFluidState().isEmpty()&&support.isFaceSturdy(level,pos.below(),Direction.UP)
                &&Blocks.SNOW.defaultBlockState().canSurvive(level,pos);
    }
    private void writeReceipt()throws IOException{
        JsonObject saved=new JsonObject();saved.addProperty("schema",1);saved.addProperty("version",1);
        saved.addProperty("completed",true);saved.addProperty("candidates",candidates.size());
        saved.addProperty("restored",restored);saved.addProperty("skipped",skipped);saved.addProperty("operationId",operationId);
        TestingEdits.atomic(receipt,saved);completed=true;sourceHistory=null;
    }
    private void fail(Exception failure){
        failed=true;error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        context.message="Снежный пол не восстановлен автоматически: "+error;
        LoggerFactory.getLogger("DarkWinter/Testing").error("Historical snow-floor repair stopped without a completion receipt",failure);
    }
}
