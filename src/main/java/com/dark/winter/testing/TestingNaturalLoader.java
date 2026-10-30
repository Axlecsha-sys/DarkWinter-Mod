// Whole-island commands prepare their complete native footprint before inspecting heights or planning changes.
package com.dark.winter.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

/** Whole-island commands prepare their complete native footprint before inspecting heights or planning changes. */
public final class TestingNaturalLoader {
    private static final Map<MinecraftServer,Job> JOBS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<MinecraftServer,Diagnostics> LAST=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Job {
        final MinecraftServer server;final TestingServer.Context context;final Path world;final String action,value,signature,mode,token="natural-"+UUID.randomUUID();
        final TestingIslandGenerator.Configuration config;final Set<ChunkPos> chunks;final boolean frozen;final long started=System.nanoTime();
        final TestingActors.Captured actor=TestingActors.capture();final TestingSettings parameters;
        volatile boolean holding;
        Job(MinecraftServer server,String action,String value,Set<ChunkPos> chunks) {
            this.server=server;context=TestingServer.context(server);world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            this.action=action;this.value=value;this.chunks=Set.copyOf(chunks);config=((TestingIslandGenerator)server.overworld().getChunkSource().getGenerator()).configuration();
            signature=signature(context);mode=context.settings.mode;frozen=server.tickRateManager().isFrozen();parameters=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(context.settings),TestingSettings.class);
        }
    }
    public record Diagnostics(String action,int chunks,boolean active,boolean planning,boolean frozenBefore) {}
    private TestingNaturalLoader() {}
    public static boolean busy(MinecraftServer server){return JOBS.containsKey(server);}
    public static boolean active(MinecraftServer server){return JOBS.containsKey(server);}
    public static Diagnostics diagnostics(MinecraftServer server){Job job=JOBS.get(server);return job==null?LAST.get(server):new Diagnostics(job.action,job.chunks.size(),true,!job.holding,job.frozen);}
    public static void queue(MinecraftServer server,String action,String value)throws IOException {
        var context=authority(server);
        if(!Set.of("biome","vegetation").contains(action)||JOBS.containsKey(server)||TestingServer.busy(server))throw new IOException("Дождитесь окончания природной операции");
        var generator=(TestingIslandGenerator)server.overworld().getChunkSource().getGenerator();
        if(action.equals("biome")){String key=value.contains(":")?value:"minecraft:"+value;server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(key)));}
        else if(!Set.of("grass","bushes","trees").contains(value))throw new IOException("Неизвестная категория растительности");
        // Four blocks of loaded halo cover whole crowns and adjacent native NPC support; writes remain within192.
        Set<ChunkPos> chunks=new LinkedHashSet<>(TestingTerrain.chunks(TestingTerrain.extent(context,generator)+4));
        if(action.equals("vegetation"))for(var origin:context.edits.origins.entrySet())if(value.equals(origin.getValue())) {
            BlockPos point=BlockPos.of(origin.getKey());
            if(server.overworld().isOutsideBuildHeight(point)||Math.abs((long)point.getX())>29999984||Math.abs((long)point.getZ())>29999984)throw new IOException("Неверный член растительности");
            ChunkPos center=ChunkPos.containing(point);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)chunks.add(new ChunkPos(center.x()+x,center.z()+z));
        }
        if(chunks.size()>4096)throw new IOException("Природная операция превышает4096 чанков");
        Job job=new Job(server,action,value,chunks);JOBS.put(server,job);server.tickRateManager().setFrozen(true);
        context.message="Природа: подготовка "+chunks.size()+" чанков";context.event("natural_prepare",action+"|"+value+"|chunks="+chunks.size());
    }
    public static void tick(MinecraftServer server) {
        Job job=JOBS.get(server);if(job==null)return;
        try(var actor=job.holding?new TestingActors.Scope(null):TestingActors.enterCaptured(server,job.actor)) {
            if(job.holding) {
                if(TestingServer.recoveryBlocked(server)){finish(job,true);return;}
                if(!job.context.edits.busy()&&!TestingResize.busy(server)) {
                    job.context.message="Природа: "+job.context.edits.status();job.context.event("natural_finished",job.action+"|"+job.context.edits.status());finish(job,false);
                }return;
            }
            if(authority(server)!=job.context||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(job.world)
                    ||!job.context.settings.mode.equals(job.mode)||!((TestingIslandGenerator)server.overworld().getChunkSource().getGenerator()).configuration().equals(job.config))
                throw new IOException("Мир или конфигурация природной операции изменились");
            if(job.actor==null?!signature(job.context).equals(job.signature):!job.context.settings.biome.equals(job.parameters.biome))throw new IOException("Параметры природы изменились во время подготовки");
            if(System.nanoTime()-job.started>180_000_000_000L)throw new IOException("Подготовка природной области превысила180 секунд");
            if(!TestingImportLoader.prepareChunks(server,job.token,job.chunks))return;
            job.holding=true;TestingServer.rebuildSupport(job.context);
            TestingSettings previous=job.context.settings;job.context.settings=job.parameters;
            try{if(job.action.equals("biome"))TestingTerrain.biome(job.context,job.value);else TestingTerrain.vegetation(job.context,job.value);}finally{job.context.settings=previous;}
            job.context.event("natural_prepared",job.action+"|"+job.value);
        }catch(Exception failure) {
            job.context.message="Природная операция отклонена: "+failure.getMessage();job.context.event("natural_failed",failure.toString());finish(job,TestingServer.recoveryBlocked(server));
        }
    }
    private static String signature(TestingServer.Context context) {
        var settings=context.settings;
        return settings.biome+"|"+settings.density+"|"+settings.grassSpecies+"|"+settings.bushSpecies+"|"+settings.treeSpecies;
    }
    private static TestingServer.Context authority(MinecraftServer server)throws IOException {
        var context=TestingServer.context(server);
        if(context==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||context.readOnlyPreview||!TestingServer.isTesting(server)
                ||!Set.of("EDIT","CHARACTER").contains(context.settings.mode)||!(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator))
            throw new IOException("Природная подготовка доступна только в локальном редакторе острова");
        return context;
    }
    private static void finish(Job job,boolean preserveFreeze) {
        JOBS.remove(job.server);TestingImportLoader.releaseChunks(job.server,job.token);
        LAST.put(job.server,new Diagnostics(job.action,job.chunks.size(),false,false,job.frozen));
        if(!preserveFreeze&&!job.context.edits.busy()&&!TestingResize.busy(job.server)) {
            var preload=com.dark.winter.loading.WorldPreloadServer.currentProgress(job.server);
            job.server.tickRateManager().setFrozen(job.frozen||job.context.settings.mode.equals("TEST_PAUSED")||preload!=null&&preload.active());
        }
    }
    public static void cancel(MinecraftServer server){Job job=JOBS.get(server);if(job!=null)finish(job,job.context.edits.busy()||TestingResize.busy(server)||TestingServer.recoveryBlocked(server));LAST.remove(server);}
}
