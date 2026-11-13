// Coordinates isolated exact Minecraft previews for the C++ editor without opening a playable game window.
package com.dark.winter.biomeeditor;

import com.dark.winter.biomeeditor.mixin.EditorCreateAccess;
import com.dark.winter.biomeeditor.mixin.EditorOcclusionAccess;
import com.dark.winter.testing.*;
import com.dark.winter.client.TestingWorlds;
import com.dark.winter.loading.WorldPreloadServer;
import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.phys.Vec3;

public final class BiomeEditorWorker implements ClientModInitializer {
    public static volatile BiomeEditorWorker INSTANCE;
    private enum Phase{IDLE,OPENING,PACK_RELOAD,LOADING,GENERATING,READY,DISCONNECT,STOPPING}
    private volatile Phase phase=Phase.IDLE;
    private EditorFiles files;
    private EditorFrames frames;
    private EditorSceneExporter meshes;
    private EditorLighting lighting;
    private final boolean retained=!Boolean.getBoolean("darkwinter.biomeEditor.raster");
    private boolean meshStarted,meshPublished;
    private long litRevision=-1,lightmapRevision=-1,meshEpoch=-1;
    private JsonObject lightmapAsset;
    private volatile BiomeEditorProfile profile;
    private volatile JsonObject camera=new JsonObject(),environment=new JsonObject();
    private record Clip(int minX,int minZ,int maxX,int maxZ,int cutY){}
    private static volatile Clip clipping;
    private Clip requestedClip;
    private JsonObject deferredRequest,deferredGeneration;
    private BiomeEditorPack.Result pack;
    private CreateWorldScreen form;
    private CompletableFuture<?> pending,configuration;
    private long lastResidencyRevision=-1;
    private volatile long revision,sceneRevision;
    private long appliedRevision,lastPublished,phaseStarted=System.nanoTime();
    private int steadyFrames;
    private volatile boolean stop;
    private boolean failed,framePending,dirty=true,terminated;
    private Throwable frameFailure;
    private int frameRetries;
    private long previewStarted=System.nanoTime(),lastReadinessNotice;
    private String readinessReason="Ожидается первый кадр";
    private long parentPid;
    private long requestAcceptedNanos,interactiveUntil,publishedReadyRevision=-1;
    private long cameraRequests,serverConfigurations,sceneGenerations,maxRequestLatencyNanos;
    private int sceneViewDistance;
    private String configuredEnvironment="";
    private String folder="";
    public static boolean active(){return INSTANCE!=null;}
    public static boolean orbitActive(){var w=INSTANCE;return w!=null&&w.phase==Phase.READY;}
    public static boolean retainedActive(){var w=INSTANCE;return w!=null&&w.retained&&(w.phase==Phase.READY||w.phase==Phase.DISCONNECT||w.phase==Phase.STOPPING);}
    public static int serverViewDistance(){var w=INSTANCE;var p=w==null?null:w.profile;return p==null?2:Math.max(2,(p.chunks()+1)/2+1);}
    public static JsonObject camera(){var w=INSTANCE;return w==null?new JsonObject():w.camera.deepCopy();}
    public static boolean clip(BlockPos pos){
        Clip bounds=clipping;if(bounds==null)return false;
        return pos.getX()<bounds.minX||pos.getZ()<bounds.minZ||pos.getX()>=bounds.maxX||pos.getZ()>=bounds.maxZ||pos.getY()>bounds.cutY;
    }
    @Override public void onInitializeClient(){
        if(System.getProperty("darkwinter.biomeEditor.ipc","").isBlank())throw new IllegalStateException("Editor worker requires an owned IPC session");
        try{files=new EditorFiles();frames=new EditorFrames(files);meshes=new EditorSceneExporter(files);lighting=new EditorLighting(files);}catch(Exception e){throw new IllegalStateException(e);}
        parentPid=Long.getLong("darkwinter.biomeEditor.parentPid",0L);
        INSTANCE=this;
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            WorldPreloadServer.cancel(server);
            handler.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        files.state(0,0,"starting",0,"Загружается движок Minecraft","",null);
    }
    private void tick(Minecraft c){
        if(terminated)return;
        try{
            if(frameFailure!=null){Throwable failure=frameFailure;frameFailure=null;throw failure;}
            if(parentPid>0&&!ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false))stop=true;
            poll(c);
            if(phase==Phase.READY&&dirty&&System.nanoTime()-previewStarted>120_000_000_000L)
                throw new IllegalStateException("Не получен готовый кадр: "+readinessReason);
            if(phase!=Phase.READY&&phase!=Phase.IDLE&&System.nanoTime()-phaseStarted>240_000_000_000L)
                throw new IllegalStateException("Превышено время ожидания точного предпросмотра: "+phase);
            c.options.pauseOnLostFocus=false;
            c.mouseHandler.releaseMouse();
            if(stop&&phase!=Phase.STOPPING){phase=Phase.STOPPING;phaseStarted=System.nanoTime();}
            switch(phase){
                case IDLE->{if(profile!=null&&!failed&&c.isGameLoadFinished()&&c.gui.overlay()==null){
                    if(!RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"))throw new IllegalStateException("Exact preview requires Vulkan");
                    c.options.enableVsync().set(false);c.options.framerateLimit().set(60);c.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                    c.options.simulationDistance().set(5);
                    c.options.chunkSectionFadeInTime().set(0.0);
                    c.options.prioritizeChunkUpdates().set(PrioritizeChunkUpdates.NONE);
                    c.options.bobView().set(false);c.options.improvedTransparency().set(true);
                    c.options.cloudStatus().set(CloudStatus.OFF); // Keep overhead native cloud geometry clear of the terrain-editing view.
                    c.getWindow().setWindowed(retained?320:1280,retained?180:720);
                    CreateWorldScreen.openFresh(c,()->failure(c,new IllegalStateException("Создание предпросмотра отменено")));
                    phase=Phase.OPENING;phaseStarted=System.nanoTime();
                }}
                case OPENING->{
                    if(!(c.gui.screen() instanceof CreateWorldScreen screen)||c.gui.overlay()!=null)return;
                    form=screen;
                    Path directory=files.root.resolve("export").resolve("scene-"+sceneRevision).resolve(profile.id());
                    Files.createDirectories(directory.getParent());
                    pack=BiomeEditorPack.write(directory,screen.getUiState().getSettings().worldgenLoadContext(),profile);
                    Path temp=((EditorCreateAccess)screen).editor$temp();
                    if(temp==null)throw new IllegalStateException("Не создан временный каталог наборов данных");
                    copyPack(pack.directory(),temp.resolve(profile.id()));
                    var selection=((EditorCreateAccess)screen).editor$selection(screen.getUiState().getSettings().dataConfiguration());
                    var repository=selection.getSecond();repository.reload();
                    var selected=new ArrayList<>(repository.getSelectedIds());String id="file/"+profile.id();
                    if(!repository.getAvailableIds().contains(id))throw new IllegalStateException("Нативный загрузчик не обнаружил набор биома");
                    if(!selected.contains(id))selected.add(id);repository.setSelected(selected);
                    phase=Phase.PACK_RELOAD;phaseStarted=System.nanoTime();
                    ((EditorCreateAccess)screen).editor$apply(repository,false,config->failure(c,new IllegalStateException("Набор биома не прошёл нативную проверку")));
                }
                case PACK_RELOAD->{
                    if(c.gui.screen()!=form||c.gui.overlay()!=null)return;
                    var ui=form.getUiState();var registry=ui.getSettings().worldgenLoadContext();
                    var preset=registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET,Identifier.parse(pack.presetId())));
                    ui.setName("BiomeEditorPreview-"+sceneRevision);ui.setSeed(Long.toString(profile.seed()));
                    ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
                    ui.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,null);ui.getGameRules().set(GameRules.SPAWN_MOBS,false,null);
                    ui.getGameRules().set(GameRules.ADVANCE_TIME,false,null);ui.getGameRules().set(GameRules.ADVANCE_WEATHER,false,null);
                    folder=ui.getTargetFolder();TestingWorlds.arm(TestingWorlds.Template.NORMAL);
                    ((EditorCreateAccess)form).editor$create();phase=Phase.LOADING;phaseStarted=System.nanoTime();
                    files.state(revision,sceneRevision,"generating",.05,"Генерируются настоящие чанки","",pack.directory());
                }
                case LOADING->{
                    if(confirm(c))return;
                    if(c.level==null||c.player==null||c.getSingleplayerServer()==null||c.gui.screen()!=null||c.gui.overlay()!=null)return;
                    long generation=sceneRevision;
                    pending=server(c,game->{TestingServer.attach(game);WorldPreloadServer.cancel(game);
                        int halo=2,minX=profile.chunkX()-halo,minZ=profile.chunkZ()-halo,n=profile.chunks()+2*halo,count=0;
                        for(int z=minZ;z<minZ+n;z++)for(int x=minX;x<minX+n;x++){
                            if(generation!=sceneRevision||stop)throw new CancellationException("Superseded editor generation");
                            var chunk=game.overworld().getChunkSource().getChunk(x,z,ChunkStatus.FULL,true);
                            if(chunk==null)throw new IllegalStateException("Native FULL chunk missing");
                            count++;
                            if(count%8==0||count==n*n)files.state(revision,sceneRevision,"generating",.05+.85*count/(n*n),"Точные чанки: "+count+" / "+n*n,"",pack.directory());
                        }
                        var p=game.getPlayerList().getPlayers().getFirst();
                        double centerX=(profile.chunkX()+profile.chunks()*.5)*16,centerZ=(profile.chunkZ()+profile.chunks()*.5)*16;
                        game.getCommands().performPrefixedCommand(game.createCommandSourceStack(),"gamemode spectator @a");
                        p.teleportTo(centerX,Math.min(300,profile.terrain().baseHeight()+32),centerZ);
                        p.setNoGravity(true);p.setDeltaMovement(Vec3.ZERO);
                        return true;});
                    phase=Phase.GENERATING;phaseStarted=System.nanoTime();
                }
                case GENERATING->{
                    if(!pending.isDone())return;pending.join();pending=null;
                    configure(c);phase=Phase.READY;clipping=requestedClip;dirty=true;steadyFrames=0;appliedRevision=revision;
                    if(retained)c.levelRenderer.resetLevelRenderData();
                    else{
                        c.levelRenderer.invalidateCompiledGeometry(c.level,c.options,c.gameRenderer.mainCamera(),c.getBlockColors());
                        c.levelRenderer.sectionOcclusionGraph().invalidate();
                        ((EditorOcclusionAccess)c.levelRenderer.sectionOcclusionGraph()).editor$needsFrustumUpdate().set(true);
                    }
                }
                case READY->{
                    if(c.level==null||c.player==null)return;
                    if(appliedRevision!=revision){configure(c);appliedRevision=revision;steadyFrames=0;dirty=true;}
                    if(c.options.getEffectiveRenderDistance()!=sceneViewDistance)c.options.setServerRenderDistance(sceneViewDistance);
                    if(retained)pollMesh(c);
                    int fps=retained?(dirty?30:10):(dirty||environment.get("effects").getAsBoolean()||System.nanoTime()<interactiveUntil?60:20);
                    if(c.options.framerateLimit().get()!=fps)c.options.framerateLimit().set(fps);
                    if(c.gui.screen()!=null)c.gui.setScreen(null);
                    if(!c.gui.hud.isHidden())c.gui.hud.toggle();
                    c.options.setCameraType(CameraType.FIRST_PERSON);
                }
                case DISCONNECT->{
                    if(c.level==null&&c.getSingleplayerServer()==null&&c.gui.screen() instanceof TitleScreen){
                        phase=Phase.IDLE;pending=null;form=null;pack=null;phaseStarted=System.nanoTime();
                    }
                }
                case STOPPING->{
                    if(c.level!=null||c.getSingleplayerServer()!=null){c.disconnect(new TitleScreen(),false);return;}
                    meshes.cancel();
                    if(frames.idle()&&lighting.idle()&&meshes.idle()){
                        terminated=true;writePerformance();frames.close();lighting.close();meshes.close();files.state(revision,sceneRevision,"stopped",1,"Предпросмотр остановлен","",null);
                        clipping=null;if(!retained)INSTANCE=null;c.stop();
                    }
                }
            }
            if(phase!=Phase.READY&&phase!=Phase.IDLE&&phase!=Phase.STOPPING&&System.nanoTime()-phaseStarted>240_000_000_000L)
                throw new IllegalStateException("Превышено время ожидания точного предпросмотра: "+phase);
        }catch(Throwable e){failure(c,e);}
    }
    public void beforeExtract(Minecraft c){
        if(retained||phase!=Phase.READY||c.level==null||deferredRequest!=null)return;
        try{
            JsonObject r=files.request();if(r==null)return;
            JsonObject pose=r.has("camera")&&r.get("camera").isJsonObject()?r.getAsJsonObject("camera"):null;
            int cut=pose!=null&&pose.has("cutY")?pose.get("cutY").getAsInt():320;
            boolean pureCamera=r.has("kind")&&r.get("kind").getAsString().equals("camera")
                    &&r.has("sceneRevision")&&r.get("sceneRevision").getAsLong()==sceneRevision&&pose!=null
                    &&cut==(requestedClip==null?320:requestedClip.cutY)
                    &&r.has("environment")&&r.get("environment").equals(environment);
            if(!pureCamera){deferredRequest=r;return;}
            applyRequest(c,r);configure(c);appliedRevision=revision;
            if(c.options.framerateLimit().get()!=60)c.options.framerateLimit().set(60);
        }catch(Throwable failure){frameFailure=failure;}
    }
    private void poll(Minecraft c)throws Exception{
        JsonObject r=deferredRequest;deferredRequest=null;if(r==null)r=files.request();
        if(r!=null)applyRequest(c,r);
        if(deferredGeneration!=null&&(phase==Phase.IDLE||phase==Phase.READY||phase==Phase.DISCONNECT)){
            JsonObject latest=deferredGeneration;deferredGeneration=null;applyRequest(c,latest);
        }
    }
    private void applyRequest(Minecraft c,JsonObject r)throws Exception{
        if(!r.has("schema")||r.get("schema").getAsInt()!=1)throw new IllegalArgumentException("Unknown editor protocol");
        long next=r.get("revision").getAsLong();if(next<=revision)return;
        if(next>9_007_199_254_740_991L)throw new IllegalArgumentException("Unsafe request revision");
        String kind=r.get("kind").getAsString();if(kind.equals("stop")){revision=next;stop=true;return;}
        if(!Set.of("generate","camera","environment").contains(kind))throw new IllegalArgumentException("Unknown editor request");
        if(kind.equals("generate")&&phase!=Phase.IDLE&&phase!=Phase.READY&&phase!=Phase.DISCONNECT){
            if(deferredGeneration==null||next>deferredGeneration.get("revision").getAsLong())deferredGeneration=r.deepCopy();
            return;
        }
        if(!kind.equals("generate")&&(profile==null||r.get("sceneRevision").getAsLong()!=sceneRevision))return;
        JsonObject full=kind.equals("generate")?r.getAsJsonObject("profile").deepCopy():profile.json();
        if(r.has("camera"))full.add("camera",r.getAsJsonObject("camera").deepCopy());
        if(r.has("environment"))full.add("environment",r.getAsJsonObject("environment").deepCopy());
        BiomeEditorProfile requested=BiomeEditorProfile.parse(full);
        int previousCut=requestedClip==null?320:requestedClip.cutY;
        camera=requested.json().getAsJsonObject("camera");environment=requested.json().getAsJsonObject("environment");
        int cut=camera.has("cutY")?camera.get("cutY").getAsInt():320;
        requestedClip=new Clip(requested.chunkX()*16,requested.chunkZ()*16,(requested.chunkX()+requested.chunks())*16,(requested.chunkZ()+requested.chunks())*16,cut);
        if(!kind.equals("generate")&&phase==Phase.READY){
            if(retained&&previousCut!=cut){meshes.cancel();meshStarted=false;meshPublished=false;}
            clipping=requestedClip;
        }
        revision=next;requestAcceptedNanos=System.nanoTime();previewStarted=requestAcceptedNanos;
        interactiveUntil=requestAcceptedNanos+300_000_000L;lastReadinessNotice=0;
        if(!kind.equals("generate")&&c.level!=null){
            if(previousCut!=cut)c.levelRenderer.invalidateCompiledGeometry(c.level,c.options,c.gameRenderer.mainCamera(),c.getBlockColors());
            ((EditorOcclusionAccess)c.levelRenderer.sectionOcclusionGraph()).editor$needsFrustumUpdate().set(true);
        }
        if(kind.equals("generate")){
            meshes.cancel();meshStarted=false;meshPublished=false;lightmapAsset=null;litRevision=-1;lightmapRevision=-1;meshEpoch=-1;
            sceneRevision=next;profile=requested;failed=false;dirty=true;steadyFrames=0;
            sceneGenerations++;configuredEnvironment="";sceneViewDistance=0;clipping=null;
            files.state(revision,sceneRevision,"generating",0,"Подготавливается точный биом","",null);
            if(c.level!=null){c.disconnect(new TitleScreen(),false);phase=Phase.DISCONNECT;phaseStarted=System.nanoTime();}
            else if(c.getSingleplayerServer()==null){c.gui.setScreen(new TitleScreen());phase=Phase.IDLE;}
        }else{
            profile=requested;dirty=true;steadyFrames=0;
            if(kind.equals("camera"))cameraRequests++;
            files.state(revision,sceneRevision,"generating",.95,"Обновляется вид биома","",pack==null?null:pack.directory());
        }
    }
    private void configure(Minecraft c){
        readinessReason="Согласуется состояние сцены";
        int distance=requiredViewDistance();
        sceneViewDistance=Math.max(sceneViewDistance,distance);
        if(c.options.renderDistance().get()!=sceneViewDistance)c.options.renderDistance().set(sceneViewDistance);
        // Server streaming stays centered on the player; the separate orbit view area must cover its camera offset.
        c.options.setServerRenderDistance(sceneViewDistance);
        String envKey=environment.toString();
        if(configuration!=null&&configuredEnvironment.equals(envKey))return;
        configuredEnvironment=envKey;serverConfigurations++;
        final long expected=sceneRevision;JsonObject env=environment.deepCopy();
        configuration=server(c,game->{if(expected!=sceneRevision)return false;
            var s=TestingServer.settings(game);s.all(false);
            boolean on=env.get("effects").getAsBoolean();
            for(String name:List.of("weather","wind","precipitation","visuals","atmosphere"))s.modules.put(name,on);
            String weather=env.get("weather").getAsString();s.weather=switch(weather){case "rain"->"light_snow";case "snow"->"snow";case "thunder"->"severe_blizzard";default->"clear";};
            s.windSpeed=weather.equals("thunder")?8:0;s.validate();
            game.getCommands().performPrefixedCommand(game.createCommandSourceStack(),"time set "+env.get("time").getAsInt());
            game.getCommands().performPrefixedCommand(game.createCommandSourceStack(),"weather clear");return true;});
    }
    public void afterFrame(Minecraft c){
        if(phase!=Phase.READY||c.level==null||c.player==null||c.gui.overlay()!=null||c.gui.screen()!=null||framePending)return;
        if(retained){captureLighting(c);return;}
        boolean animated=environment.get("effects").getAsBoolean();
        if(!dirty&&(!animated||System.nanoTime()-lastPublished<33_333_333L))return;
        if(configuration==null||!configuration.isDone()){waitingFrame("Согласуется состояние сервера",null);return;}
        try{configuration.join();}catch(Throwable failure){failure(c,failure);return;}
        var state=com.dark.winter.client.TestingAccess.presentation();boolean on=environment.get("effects").getAsBoolean();
        if(state==null||state.moduleMask()!=(on?62:0)||Math.abs(c.level.getDefaultClockTime()-environment.get("time").getAsInt())>.01){waitingFrame("Согласуются время и эффекты клиента",null);return;}
        // Only the native-selected preview meshes gate a frame; unrelated background sections do not.
        steadyFrames++;
        var residency=EditorResidency.inspect(c,profile,clipping==null?320:clipping.cutY);
        if(!residency.ready()){steadyFrames=0;waitingFrame(residency.reason(),residency.json());return;}
        if(lastResidencyRevision!=revision){
            try{EditorFiles.atomic(files.root.resolve("residency.json"),residency.json());lastResidencyRevision=revision;}
            catch(Exception failure){failure(c,failure);return;}
        }

        long request=revision,scene=sceneRevision;
        framePending=true;
        try{
            boolean scheduled=frames.capture(c.gameRenderer.mainRenderTarget(),request,scene,camera,
                    ()->revision==request&&sceneRevision==scene&&phase==Phase.READY,error->{
                framePending=false;
                if(revision!=request||sceneRevision!=scene||phase!=Phase.READY)return;
                if(error==null){
                    frameRetries=0;lastPublished=System.nanoTime();dirty=false;
                    if(publishedReadyRevision!=request){publishedReadyRevision=request;
                        maxRequestLatencyNanos=Math.max(maxRequestLatencyNanos,lastPublished-requestAcceptedNanos);
                        files.state(request,scene,"ready",1,"Точный предпросмотр готов","",pack.directory());}
                }else{
                    dirty=true;
                    if(!(error instanceof CancellationException)&&++frameRetries>8)frameFailure=error;
                }
            });
            if(!scheduled)framePending=false;
        }catch(Throwable failure){framePending=false;frameFailure=failure;}

    }
    private void pollMesh(Minecraft c){
        long epoch=meshes.resourceEpoch();
        if(meshStarted&&meshEpoch!=epoch){meshes.cancel();meshStarted=false;meshPublished=false;lightmapAsset=null;litRevision=-1;lightmapRevision=-1;dirty=true;}
        if(!meshStarted&&lightmapAsset!=null&&lightmapRevision==revision){
            meshes.start(c,profile,lightmapRevision,sceneRevision,environment,lightmapAsset);
            meshStarted=true;meshEpoch=epoch;
        }
        if(!meshStarted)return;
        var progress=meshes.poll(c);
        if(progress.failure()!=null)throw new CompletionException(progress.failure());
        if(progress.publicationComplete()){
            meshPublished=true;
            if(litRevision==revision){dirty=false;lastPublished=System.nanoTime();
                if(publishedReadyRevision!=revision){publishedReadyRevision=revision;
                    maxRequestLatencyNanos=Math.max(maxRequestLatencyNanos,lastPublished-requestAcceptedNanos);
                    files.state(revision,sceneRevision,"ready",1,"Точная геометрия готова. Камера работает локально.","",pack.directory());}
            }
        }else waitingFrame(progress.message(),null);
    }
    private void captureLighting(Minecraft c){
        if(!dirty||!lighting.idle()||litRevision==revision)return;
        if(configuration==null||!configuration.isDone())return;
        configuration.join();
        var state=com.dark.winter.client.TestingAccess.presentation();boolean on=environment.get("effects").getAsBoolean();
        if(state==null||state.moduleMask()!=(on?62:0)||Math.abs(c.level.getDefaultClockTime()-environment.get("time").getAsInt())>.01)return;
        final long request=revision,scene=sceneRevision,epoch=meshes.resourceEpoch();
        lighting.capture(c,request,scene,epoch,environment,
                ()->revision==request&&sceneRevision==scene&&phase==Phase.READY,
                true,asset->{lightmapAsset=asset;lightmapRevision=request;litRevision=request;},
                error->{if(!(error instanceof CancellationException))frameFailure=error;});
    }
    private int requiredViewDistance(){
        if(retained)return serverViewDistance();
        double span=camera.get("span").getAsDouble(),pitch=Math.toRadians(camera.get("pitch").getAsDouble());
        double distance=Math.max(80,Math.min(256,span*1.2));
        int cx=(int)Math.floor(camera.get("targetX").getAsDouble()/16),cz=(int)Math.floor(camera.get("targetZ").getAsDouble()/16);
        int maxX=profile.chunkX()+profile.chunks()-1,maxZ=profile.chunkZ()+profile.chunks()-1;
        int radius=Math.max(Math.max(Math.abs(cx-profile.chunkX()),Math.abs(cx-maxX)),Math.max(Math.abs(cz-profile.chunkZ()),Math.abs(cz-maxZ)));
        // Cover all orbit headings before dragging; yaw alone must not recreate the native view area.
        radius+=(int)Math.ceil(Math.abs(Math.cos(pitch)*distance)/16)+2;
        return Math.min(32,Math.max(2,radius));
    }
    private void writePerformance(){
        JsonObject data=frames.diagnostics();data.addProperty("retainedMeshPreview",retained);data.addProperty("meshPublished",meshPublished);data.addProperty("cameraRequests",cameraRequests);data.addProperty("serverConfigurations",serverConfigurations);
        data.addProperty("sceneGenerations",sceneGenerations);data.addProperty("maxAcceptedRequestLatencyMs",maxRequestLatencyNanos/1e6);
        data.addProperty("clientViewDistance",sceneViewDistance);data.addProperty("serverViewDistance",serverViewDistance());
        var usage=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        JsonObject memory=new JsonObject();memory.addProperty("heapUsedBytes",usage.getUsed());
        memory.addProperty("heapCommittedBytes",usage.getCommitted());memory.addProperty("heapMaxBytes",usage.getMax());
        JsonArray nativePools=new JsonArray();
        for(var pool:java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)){
            JsonObject b=new JsonObject();b.addProperty("name",pool.getName());b.addProperty("count",pool.getCount());
            b.addProperty("memoryUsedBytes",pool.getMemoryUsed());b.addProperty("capacityBytes",pool.getTotalCapacity());nativePools.add(b);
        }
        memory.add("bufferPools",nativePools);
        JsonArray gc=new JsonArray();for(var collector:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()){
            JsonObject g=new JsonObject();g.addProperty("name",collector.getName());g.addProperty("collections",collector.getCollectionCount());
            g.addProperty("collectionMs",collector.getCollectionTime());gc.add(g);
        }memory.add("gc",gc);data.add("memoryAtShutdown",memory);
        try{EditorFiles.atomic(files.root.resolve("worker-performance.json"),data);}catch(Exception failure){failure.printStackTrace();}
    }
    private void waitingFrame(String reason,JsonObject residency){
        // Background native work must not overwrite the receipt of an already published static frame.
        if(!dirty)return;
        readinessReason=reason;long now=System.nanoTime();
        if(now-lastReadinessNotice<1_000_000_000L)return;lastReadinessNotice=now;
        try{
            if(residency!=null)EditorFiles.atomic(files.root.resolve("residency.json"),residency);
            files.state(revision,sceneRevision,"generating",.95,reason,"",pack==null?null:pack.directory());
        }catch(Exception failure){frameFailure=failure;}
    }
    private boolean confirm(Minecraft c){
        if(!(c.gui.screen() instanceof ConfirmScreen screen))return false;
        String title=screen.getTitle().getString();
        if(!title.equals(Component.translatable("selectWorld.warning.experimental.title").getString())&&!title.equals(Component.translatable("selectWorld.warning.deprecated.title").getString()))return false;
        screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().getString().equals(Component.translatable("gui.yes").getString())).findFirst().orElseThrow().onPress(null);return true;
    }
    private void failure(Minecraft c,Throwable e){
        Throwable cause=e instanceof CompletionException&&e.getCause()!=null?e.getCause():e;
        if(cause instanceof CancellationException&&!stop){if(c.level!=null)c.disconnect(new TitleScreen(),false);phase=Phase.DISCONNECT;phaseStarted=System.nanoTime();return;}
        meshes.cancel();e.printStackTrace();failed=true;files.state(revision,sceneRevision,"failed",0,"Не удалось пересчитать биом",cause.toString(),null);
        if(c.level!=null){c.disconnect(new TitleScreen(),false);phase=Phase.DISCONNECT;phaseStarted=System.nanoTime();}else phase=Phase.IDLE;
    }
    private static <T> CompletableFuture<T> server(Minecraft c,Function<MinecraftServer,T> action){
        var result=new CompletableFuture<T>();MinecraftServer server=c.getSingleplayerServer();
        if(server==null){result.completeExceptionally(new IllegalStateException("Preview server missing"));return result;}
        server.execute(()->{try{result.complete(action.apply(server));}catch(Throwable e){result.completeExceptionally(e);}});return result;
    }
    private static void copyPack(Path source,Path target)throws Exception{
        if(Files.exists(target)||Files.isSymbolicLink(source))throw new IllegalStateException("Owned new datapack target required");
        try(var paths=Files.walk(source)){
            for(Path p:paths.toList()){if(Files.isSymbolicLink(p))throw new IllegalStateException("Linked export refused");
                Path q=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}
        }
    }
}
