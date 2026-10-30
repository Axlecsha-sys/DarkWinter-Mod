// Authoritative host file/session routes; all world IO still uses the tested stopped-save workflow.
package com.dark.winter.client.online;

import com.dark.winter.client.*;
import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import com.dark.winter.testing.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Authoritative host file/session routes; all world IO still uses the tested stopped-save workflow. */
public final class OnlineLabCoordinator implements AutoCloseable,TestingOnlineBridge.AdvancedRoutes,TestingSessions.OnlineHooks {
    public record Intent(UUID operationId,String action,String value,Path source,String actorId,UUID actorGameId,UUID sessionId,long epoch,String islandId,Set<Permission> permissions) {
        public Intent {Objects.requireNonNull(operationId);Objects.requireNonNull(action);Objects.requireNonNull(value);source=Objects.requireNonNull(source).toAbsolutePath().normalize();Objects.requireNonNull(actorId);Objects.requireNonNull(actorGameId);Objects.requireNonNull(sessionId);Objects.requireNonNull(islandId);permissions=Set.copyOf(permissions);if(permissions.isEmpty())throw new IllegalArgumentException("Transition permissions required");}
        @Override public String toString(){return "OnlineLabIntent[id="+operationId+",action="+action+"]";}
    }
    public record Rebound(OnlineGameHost host,UUID actorGameId) {}
    /** Callbacks run on the client thread. Rebind completes only after actual fresh native admission. */
    public interface Lifecycle {
        CompletableFuture<Void> beforeTransition(Intent intent);
        default CompletableFuture<Void> prepareStopped(Intent intent,Path target,String action,boolean internalExtraction){return CompletableFuture.completedFuture(null);}
        CompletableFuture<Rebound> rebind(Intent intent,MinecraftServer target,boolean internalExtraction);
        void failed(Intent intent,String message);
        default boolean allowInternalReturnAfterFailure(Intent intent,MinecraftServer source){return false;}
        default Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{return Optional.empty();}
    }
    private record Binding(MinecraftServer server,OnlineGameHost host) {}
    private static final class Cursor {volatile String selected="",prefab="",scenario="",confirmation="";volatile List<String> checkpoints=List.of();int historyPage,prefabPage,libraryPage;}
    private static final class Operation {
        final Intent intent;final Set<Permission> permissions;final TestingOnlineBridge.Selection selection;final boolean automaticDiscard;final Operation automaticParent;
        volatile String phase="accepted",message="Запрос принят ведущим";volatile boolean started;volatile long startedAt=System.nanoTime();volatile UUID currentActor;volatile String deferredFailure;
        Operation(Intent intent,Set<Permission> permissions,TestingOnlineBridge.Selection selection){this(intent,permissions,selection,false,null);}
        Operation(Intent intent,Set<Permission> permissions,TestingOnlineBridge.Selection selection,boolean automaticDiscard,Operation automaticParent){this.intent=intent;this.permissions=Set.copyOf(permissions);this.selection=selection;this.automaticDiscard=automaticDiscard;this.automaticParent=automaticParent;currentActor=intent.actorGameId();}
    }
    private static final Set<String> ARCHIVE=Set.of("checkpoint","branch","export","import","open_preview","preview","preview_exit","restore","restore_settings","restore_scenarios","restore_objects","restore_buildings","restore_terrain","restore_surface","restore_vegetation","recover_copy","protect_version","delete_version","cleanup_history","template_apply","template_terrain","template_surface","template_vegetation","template_buildings","template_objects");
    private static final Set<String> TEST=Set.of("start","pause","review","finish","keep_objects","keep_buildings","keep_settings");
    private static final Set<String> SCENARIO=Set.of("scenario_save","scenario_run","scenario_stop","scenario_export","scenario_import","trigger_add","trigger_remove","spawner_add","spawner_remove","spawner_activate","spawner_clear","scenario_record_start","scenario_record_stop","scenario_record_play","scenario_metadata","report");
    private static final Set<String> NATURAL=Set.of("biome","vegetation","resize");
    private static final Set<String> READ=Set.of("scenario_timeline","history_refresh","refresh","history_page","prefab_list","prefab_page","library_refresh","library_page","history_select","select","prefab_select");
    private static final Set<String> ACTIONS;
    static {Set<String> actions=new LinkedHashSet<>(ARCHIVE);actions.addAll(TEST);actions.addAll(SCENARIO);actions.addAll(NATURAL);actions.addAll(READ);actions.addAll(Set.of("transfer_apply","transfer_cancel","prefab_save","prefab_scene_save","prefab_paste","prefab_export","prefab_import","scenario_checkpoint","scenario_seek","history_metadata","profile_save","profile_load"));ACTIONS=Set.copyOf(actions);}
    private final Minecraft client;
    private final Lifecycle lifecycle;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),task->{Thread thread=new Thread(task,"DarkWinter Online lab metadata");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Cursor> cursors=new ConcurrentHashMap<>();
    private final Set<MinecraftServer> privateSources=ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Operation> nativeContext=new ThreadLocal<>();
    private final ThreadLocal<Operation> failureContext=new ThreadLocal<>();
    private final java.util.concurrent.atomic.AtomicInteger nativePending=new java.util.concurrent.atomic.AtomicInteger();
    private volatile Binding binding;
    private volatile Path boundAuthor;
    private volatile Operation operation;
    private volatile boolean closed,catalogBusy,refreshRequested=true;
    private volatile JsonArray versions=new JsonArray(),prefabs=new JsonArray();
    private volatile String catalogStatus="История ещё не загружена",storageStatus="",phase="ready",message="Готово";
    private volatile long revision,ioGeneration;
    private final Set<Future<?>> ioJobs=ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<?> catalogWork;
    private long catalogGeneration;
    private volatile JsonObject sessionView=new JsonObject(),libraryView=new JsonObject();
    public OnlineLabCoordinator(Minecraft client,Lifecycle lifecycle){this.client=Objects.requireNonNull(client);this.lifecycle=Objects.requireNonNull(lifecycle);}
    public void attach(MinecraftServer server,OnlineGameHost host){try{inArchive(server.getWorldPath(LevelResource.ROOT),()->{attachScoped(server,host);return null;});}catch(Exception failure){throw new CompletionException(failure);}}
    private void attachScoped(MinecraftServer server,OnlineGameHost host){
        if(closed||!server.isSameThread()||OnlineGameHost.get(server)!=host)throw new IllegalStateException("Exact live Online host required");
        TestingSessions.installOnlineHooks(this);TestingOnlineBridge.installAdvanced(server,host,this);Path author=TestingSessions.authorWorld(server);if(boundAuthor!=null&&!boundAuthor.equals(author))for(Cursor cursor:cursors.values()){cursor.selected="";cursor.confirmation="";cursor.historyPage=0;}boundAuthor=author;binding=new Binding(server,host);refreshRequested=true;revision++;
    }
    @Override public Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{return lifecycle.archiveContext(actualWorld);}
    private Path archiveWorld(){MinecraftServer server=client.getSingleplayerServer();if(server==null&&binding!=null)server=binding.server();return server==null?null:server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();}
    private Optional<TestingArchiveScope.Token> archiveToken(Path world)throws IOException{
        Optional<TestingArchiveScope.Token> token=world==null?Optional.empty():lifecycle.archiveContext(world);
        if(token.isPresent()){
            token.get().call(()->{TestingArchiveScope.requireWorld(world);if(world!=null&&!TestingArchiveScope.includesWorld(world))throw new IOException("Мир не привязан к этому пространству архива");return null;});return token;
        }
        token=TestingArchiveScope.capture();
        if(token.isPresent()&&world!=null&&!TestingArchiveScope.includesWorld(world))throw new IOException("Контекст архива другого мира");
        TestingArchiveScope.requireWorld(world);return token;
    }
    private <T>T inArchive(Path world,Callable<T> task)throws Exception{
        var token=archiveToken(world);if(token.isEmpty())return task.call();
        return token.get().call(()->{try{return task.call();}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Продолжение архива отклонено",failure);}});
    }
    private void onClient(Runnable task){
        final Optional<TestingArchiveScope.Token> context;
        try{context=archiveToken(archiveWorld());}catch(IOException failure){failed("Контекст архива недоступен");return;}
        client.execute(()->{try{if(context.isEmpty())task.run();else context.get().call(()->{task.run();return null;});}
            catch(Exception failure){failed("Отложенный контекст архива отменён");}});
    }
    public Set<String> actions(){return ACTIONS;}
    public Set<Permission> permissions(String action,String value)throws IOException {
        if(Set.of("branch","import","recover_copy").contains(action))throw new IOException("Сначала закройте Online: независимый остров должен получить новую власть и отдельный состав участников");
        statePreflight(action);
        if(READ.contains(action))return Set.of(action.startsWith("history")||action.equals("refresh")||action.equals("select")?Permission.HISTORY:Permission.VIEW);
        if(action.equals("start"))return Set.of(Permission.START_TEST);
        if(Set.of("pause","review","finish").contains(action))return action.equals("finish")&&(TestingSessions.keepObjects()||TestingSessions.keepBuildings()||TestingSessions.keepSettings())?Set.of(Permission.STOP_TEST,Permission.KEEP_TEST):Set.of(Permission.STOP_TEST);
        if(action.startsWith("keep_"))return Set.of(Permission.KEEP_TEST);
        if(action.equals("transfer_apply")){Operation current=operation;return current==null?Set.of(Permission.IMPORT):current.permissions;}
        if(action.equals("transfer_cancel"))return Set.of(Permission.VERSIONS);
        if(action.equals("import")||action.startsWith("template_")||action.equals("prefab_import"))return Set.of(Permission.IMPORT);
        if(action.equals("export")||action.equals("prefab_export")||action.equals("prefab_save")||action.equals("prefab_scene_save"))return Set.of(Permission.EXPORT);
        if(action.equals("prefab_paste"))return Set.of(Permission.OBJECTS);
        if(action.equals("biome"))return Set.of(Permission.BIOME);
        if(action.equals("vegetation"))return Set.of(Permission.VEGETATION);
        if(action.equals("resize"))return Set.of(Permission.TERRAIN);
        if(action.equals("profile_save")||action.equals("profile_load"))return Set.of(Permission.SYSTEMS);
        if(action.equals("scenario_checkpoint"))return Set.of(Permission.VERSIONS);
        if(action.equals("scenario_run")||action.equals("scenario_seek"))return Set.of(Permission.START_TEST);
        if(action.equals("scenario_import"))return Set.of(Permission.SCENARIOS,Permission.IMPORT);
        if(action.equals("scenario_export"))return Set.of(Permission.SCENARIOS,Permission.EXPORT);
        if(action.equals("report"))return Set.of(Permission.VIEW);
        if(SCENARIO.contains(action))return Set.of(Permission.SCENARIOS);
        if(ARCHIVE.contains(action)||action.equals("history_metadata"))return Set.of(Permission.VERSIONS);
        throw new IOException("Неизвестный маршрут координатора");
    }
    /** The same rejection reason feeds advertised capabilities and authoritative command admission. */
    private void statePreflight(String action)throws IOException {
        if(READ.contains(action)||Set.of("history_metadata","prefab_import","prefab_export").contains(action))return;
        if(action.equals("transfer_apply")||action.equals("transfer_cancel")){
            if(!TestingSessions.transferReviewActive())throw new IOException("Нет активного обзора переноса");
            if(action.equals("transfer_apply")&&!TestingSessions.transferReviewReady())throw new IOException("Дождитесь подготовки обзора переноса");
            if(action.equals("transfer_apply")&&!TestingSessions.transferReviewCanApply())throw new IOException("Обнаружены конфликты; применение переноса запрещено");
            return;
        }
        boolean sessionRoute=ARCHIVE.contains(action)||TEST.contains(action)||NATURAL.contains(action)
            ||Set.of("scenario_checkpoint","scenario_seek","scenario_run","prefab_save","prefab_scene_save","prefab_paste").contains(action);
        if(sessionRoute){
            if(TestingSessions.transferReviewActive())throw new IOException("Сначала подтвердите или отмените показанный перенос");
            if(TestingSessions.busy())throw new IOException("Дождитесь окончания текущей операции архива");
            Binding current=binding;boolean blocked=current!=null&&TestingServer.recoveryBlocked(current.server());
            if(current!=null&&TestingServer.busy(current.server())&&!blocked)throw new IOException("Дождитесь окончания операции редактора");
            if(blocked&&!Set.of("select","refresh","preview","restore","finish","preview_exit").contains(action))
                throw new IOException("Остановлено восстановление: доступен только безопасный полный возврат");
            if(TestingSessions.isPreview()&&!action.equals("preview_exit"))throw new IOException("Предпросмотр доступен только для чтения; вернитесь к авторскому миру");
        }
        boolean running=TestingSessions.isTestRunning();
        if(action.equals("start")&&TestingSessions.hasTestSession())throw new IOException("Сначала завершите существующее испытание");
        if(TEST.contains(action)&&!action.equals("start")&&!running)throw new IOException("Сначала запустите изолированное испытание");
        if(action.equals("preview_exit")&&!TestingSessions.isPreview())throw new IOException("Предпросмотр не открыт");
        if(action.equals("scenario_checkpoint")&&!running)throw new IOException("Контрольная точка сценария доступна внутри изолированного испытания");
        if(Set.of("scenario_run","scenario_seek").contains(action)&&TestingSessions.hasTestSession()&&!running)
            throw new IOException("Сначала завершите сохранённое испытание");
        boolean editorOnly=NATURAL.contains(action)||Set.of("checkpoint","export","open_preview","preview","restore","restore_settings","restore_scenarios",
            "restore_objects","restore_buildings","restore_terrain","restore_surface","restore_vegetation","prefab_save","prefab_scene_save","prefab_paste").contains(action)
            ||action.startsWith("template_");
        Binding current=binding;boolean testMode=current!=null&&TestingServer.settings(current.server()).mode.startsWith("TEST");
        if(editorOnly&&(running||testMode))throw new IOException("Эта операция доступна в авторском редакторе после завершения испытания");
    }
    private Cursor cursor(String actor)throws IOException {
        Cursor found=cursors.get(actor);if(found!=null)return found;
        if(cursors.size()>=64)throw new IOException("Лимит истории участников");Cursor next=new Cursor();Cursor previous=cursors.putIfAbsent(actor,next);return previous==null?next:previous;
    }
    private Binding exact(MinecraftServer server)throws IOException {
        Binding current=binding;
        if(closed||current==null||current.server()!=server||OnlineGameHost.get(server)!=current.host()||!server.isSameThread()||current.host().epoch()!=current.host().access().epoch())throw new IOException("Полномочия ведущего изменились");
        return current;
    }
    private ServerPlayer actor(Binding current,Operation request)throws IOException {return actor(current,request,true);}
    private ServerPlayer actor(Binding current,Operation request,boolean requirePermissions)throws IOException {
        ServerPlayer player=current.server().getPlayerList().getPlayer(request.currentActor);
        if(player==null||!request.intent.actorId().equals(current.host().actor(player)))player=current.server().getPlayerList().getPlayers().stream().filter(candidate->request.intent.actorId().equals(current.host().actor(candidate))).findFirst().orElse(null);
        if(player==null||!current.host().can(player,Permission.VIEW))throw new IOException("Инициатор ещё не подтвердил вход");
        if(requirePermissions)for(Permission permission:request.permissions)if(!current.host().can(player,permission))throw new IOException("Право инициатора отозвано");
        request.currentActor=player.getUUID();return player;
    }
    private Operation request(MinecraftServer server,ServerPlayer player,UUID id,String action,String value)throws IOException {
        Binding current=exact(server);String identity=current.host().actor(player);
        if(!current.host().can(player,Permission.VIEW))throw new IOException("Участник не допущен");
        Set<Permission> permissions=permissions(action,value);for(Permission permission:permissions)if(!current.host().can(player,permission))throw new IOException("Роль не разрешает это действие");
        Intent intent=new Intent(id,action,value,server.getWorldPath(LevelResource.ROOT),identity,player.getUUID(),current.host().sessionId(),current.host().epoch(),current.host().islandId(),permissions);
        return new Operation(intent,permissions,TestingOnlineBridge.selection(server,player));
    }
    /** Only the server's completed discard-policy request may start this internal full rollback. */
    private Operation automaticDiscardOperation(Binding current,Operation captured)throws IOException {
        if(!TestingSessions.automaticDiscardPending(current.server()))return captured;
        Operation latest=operation;
        if(latest!=null&&latest.automaticDiscard){
            if(captured==latest||captured==latest.automaticParent)return latest;
            throw new IOException("Фоновое продолжение не принадлежит автоматическому возврату");
        }
        if(latest!=captured||latest!=null&&!Set.of("complete","failed").contains(latest.phase))
            throw new IOException("Дождитесь действующей операции перед автоматическим возвратом");
        ServerPlayer owner=current.server().getPlayerList().getPlayers().stream()
            .filter(player->current.host().localIdentityId().equals(current.host().actor(player))).findFirst()
            .orElseThrow(()->new IOException("Нет действительного локального координатора"));
        Set<Permission> required=Set.of(Permission.STOP_TEST);
        if(!current.host().can(owner,Permission.VIEW)||!current.host().can(owner,Permission.STOP_TEST))
            throw new IOException("Нет действительного права завершить испытание");
        Intent intent=new Intent(UUID.randomUUID(),"finish","",current.server().getWorldPath(LevelResource.ROOT),current.host().actor(owner),
            owner.getUUID(),current.host().sessionId(),current.host().epoch(),current.host().islandId(),required);
        Operation created=new Operation(intent,required,TestingOnlineBridge.selection(current.server(),owner),true,captured);
        created.started=true;created.phase="preparing";created.message="Автоматический полный возврат по политике сценария";operation=created;return created;
    }
    public boolean allowAutomaticDiscard(MinecraftServer server){
        Binding current=binding;Operation request=operation;
        return !closed&&current!=null&&current.server()==server&&OnlineGameHost.get(server)==current.host()
            &&(request==null||request.automaticDiscard||Set.of("complete","failed").contains(request.phase));
    }
    public CompletableFuture<JsonObject> command(MinecraftServer server,ServerPlayer player,UUID id,String action,String value) {try{return inArchive(server.getWorldPath(LevelResource.ROOT),()->commandScoped(server,player,id,action,value));}catch(Exception failure){return CompletableFuture.failedFuture(failure);}}
    private CompletableFuture<JsonObject> commandScoped(MinecraftServer server,ServerPlayer player,UUID id,String action,String value) {
        try {
            Operation next=request(server,player,id,action,value);Cursor cursor=cursor(next.intent.actorId());
            if(READ.contains(action)){Operation read=next;return TestingOnlineBridge.withActor(server,player,read.permissions,()->readCommand(read,cursor,server));}
            Operation previous=operation;
            boolean transfer=action.equals("transfer_apply")||action.equals("transfer_cancel");
            if(previous!=null&&!Set.of("complete","failed").contains(previous.phase)&&!transfer)throw new IOException("Дождитесь операции общего острова");
            if(TestingServer.busy(server)&&!transfer)throw new IOException("Дождитесь транзакции острова");
            if(transfer){
                if(!TestingSessions.transferReviewActive())throw new IOException("Нет активного обзора переноса");
                if(!action.equals("transfer_cancel")&&(previous==null||!previous.intent.actorId().equals(next.intent.actorId())))throw new IOException("Подтверждение принадлежит инициатору переноса");
                if(action.equals("transfer_apply"))next=previous;else operation=next;
            }else operation=next;
            Operation selected=next;onClient(()->execute(selected,action,value,cursor));return CompletableFuture.completedFuture(receipt(selected));
        }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
    }
    private CompletableFuture<JsonObject> readCommand(Operation request,Cursor cursor,MinecraftServer server)throws IOException {
        String action=request.intent.action(),value=request.intent.value();
        if(Set.of("history_page","prefab_page","library_page").contains(action)){
            int page=Integer.parseInt(value);if(page<0||page>4095)throw new IOException("Недопустимая страница");
            if(action.equals("history_page"))cursor.historyPage=page;else if(action.equals("prefab_page"))cursor.prefabPage=page;else cursor.libraryPage=page;revision++;return CompletableFuture.completedFuture(receipt(request));
        }
        if(action.equals("scenario_timeline")){
            if(value.isBlank()||value.length()>80)throw new IOException("Недопустимое имя сценария");
            String document=TestingSessions.scenarioDocument(server,value);if(document.isBlank())throw new IOException("Сценарий отсутствует");
            cursor.scenario=value;cursor.checkpoints=List.copyOf(TestingSessions.scenarioCheckpoints(server,value));revision++;return CompletableFuture.completedFuture(receipt(request));
        }
        if(action.equals("prefab_select")){if(!value.equals("legacy")&&!uuid(value))throw new IOException("Недопустимый Prefab");cursor.prefab=value;revision++;return CompletableFuture.completedFuture(receipt(request));}
        if(action.equals("history_select")||action.equals("select")){
            Path source=TestingSessions.authorWorld(server);
            return io(()->{TestingArchive.Version version=TestingArchive.read(value);if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(source)))throw new IOException("Версия другого острова");return version.id();})
                .thenCompose(selected->onServer(server,()->{actor(exact(server),request);cursor.selected=selected;revision++;return receipt(request);}));
        }
        refreshRequested=true;onClient(()->{if(action.equals("library_refresh"))TestingExportLibraryClient.refresh(client);});return CompletableFuture.completedFuture(receipt(request));
    }
    private void execute(Operation request,String action,String value,Cursor cursor){
        if(closed)return;
        request.started=true;request.phase="preparing";phase="preparing";message="Проверка: "+action;revision++;
        Binding current=binding;
        if(current==null){failed("Ведущий недоступен");return;}
        onServer(current.server(),()->{actor(exact(current.server()),request);return null;}).whenComplete((ignored,failure)->onClient(()->{
            if(closed||operation!=request)return;if(failure!=null){failed("Полномочия инициатора изменились");return;}
            try{
                if(action.equals("history_metadata")){
                    JsonObject data=OnlineJson.object(value,8192);OnlineJson.keys(data,"id","description","category");String id=OnlineJson.string(data,"id",36),description=OnlineJson.string(data,"description",1024),category=OnlineJson.string(data,"category",32);Path source=TestingSessions.authorWorld(current.server());
                    authorizedIo(request,()->{TestingArchive.Version version=TestingArchive.read(id);if(!version.source().equals(TestingArchive.worldKey(source)))throw new IOException("Версия другого острова");TestingArchive.updateMetadata(id,description,category);return null;}).whenComplete((answer,error)->onClient(()->finishIo(request,error)));return;
                }
                if(action.equals("prefab_import")||action.equals("prefab_export")){
                    authorizedIo(request,()->{if(action.equals("prefab_import"))return TestingPrefabs.importPortable(value);TestingPrefabs.exportPortable(value);return value;}).whenComplete((selected,error)->onClient(()->{if(error==null)cursor.prefab=selected;finishIo(request,error);}));return;
                }
                if(action.equals("prefab_save")||action.equals("prefab_scene_save")||action.equals("prefab_paste")||SCENARIO.contains(action)||action.equals("profile_save")||action.equals("profile_load")||action.equals("scenario_seek")){
                    executeNative(request,action,value,cursor);return;
                }
                if(action.equals("scenario_checkpoint")){String[] args=value.split("\\|",-1);if(args.length!=2)throw new IOException("Нужны сценарий и имя точки");cursor.scenario=args[0];TestingSessions.scenarioCheckpoint(client,args[0],args[1]);request.phase="session_wait";return;}
                if(NATURAL.contains(action)){TestingSessions.guardedAction(client,action,value);request.phase="session_wait";return;}
                String routed=action;
                if(!cursor.selected.isEmpty()&&!Set.of("checkpoint","start","pause","review","finish","keep_objects","keep_buildings","keep_settings","import","template_apply","template_terrain","template_surface","template_vegetation","template_buildings","template_objects","transfer_apply","transfer_cancel","preview_exit").contains(action))
                    TestingSessions.action(client,"select",cursor.selected);
                if(Set.of("restore","delete_version","cleanup_history").contains(action)){
                    String confirmation=action+":"+cursor.selected;
                    if(!cursor.confirmation.equals(confirmation)){TestingSessions.clearConfirmation();cursor.confirmation=confirmation;}else cursor.confirmation="";
                }
                TestingSessions.action(client,routed,value);request.phase="session_wait";
            }catch(Exception error){failed("Операция отклонена: "+safe(error.getMessage()));}
        }));
    }
    private void executeNative(Operation request,String action,String value,Cursor cursor){
        Binding current=binding;
        onServer(current.server(),()->{
            ServerPlayer actor=actor(exact(current.server()),request);
            return TestingOnlineBridge.withActor(current.server(),actor,request.permissions,()->{
            if(action.equals("prefab_save")){
                String id=TestingOnlineBridge.withActor(current.server(),actor,request.permissions,()->TestingPrefabs.exportSelection(current.server(),request.selection.first(),request.selection.second(),value));cursor.prefab=id;return false;
            }
            if(action.equals("prefab_scene_save")){
                String[] args=value.split("\\|",-1);if(args.length!=2)throw new IOException("Нужны ID группы и имя");
                cursor.prefab=TestingOnlineBridge.withActor(current.server(),actor,request.permissions,()->TestingPrefabs.exportSceneGroup(current.server(),args[0],args[1]));return false;
            }
            if(action.equals("prefab_paste")){
                String[] args=value.split("\\|",-1);if(args.length!=3)throw new IOException("Нужны Prefab, позиция и поворот");BlockPos target=position(args[1]);int turns=Integer.parseInt(args[2]);if(turns<0||turns>3)throw new IOException("Поворот 0–3");
                var prepared=TestingOnlineBridge.withActor(current.server(),actor,request.permissions,()->TestingPrefabs.preparePaste(current.server(),target,args[0],turns));cursor.prefab=prepared.id();
                if(prepared.states().size()>128||!prepared.npcChanges().isEmpty()){onClient(()->TestingSessions.guardedPrefab(client,target,prepared.id(),turns));return true;}
                TestingOnlineBridge.withActor(current.server(),actor,request.permissions,()->{TestingServer.applyBatch(current.server(),"Prefab: "+prepared.name(),prepared.states(),prepared.entities(),"building",prepared.npcChanges(),prepared.scenarioDocuments(),prepared.sceneAfter());return null;});return false;
            }
            if(action.equals("scenario_seek")){
                String[] args=value.split("\\|",-1);if(args.length!=4)throw new IOException("Нужны сценарий, версия и точный курсор");
                String doc=TestingSessions.scenarioDocument(current.server(),args[0]);int index=Integer.parseInt(args[2]);long elapsed=Long.parseLong(args[3]);TestingScenarios.validateReplay(current.server(),doc,args[1],index,elapsed);cursor.scenario=args[0];
                onClient(()->TestingSessions.scenarioReplay(client,args[1],doc,index,elapsed));return true;
            }
            if(action.equals("scenario_run")){
                String doc=TestingSessions.scenarioDocument(current.server(),value);String baseline=TestingScenarios.replayVersion(current.server(),doc,0,0);cursor.scenario=value;
                if(!baseline.isEmpty()){onClient(()->TestingSessions.scenarioReplay(client,baseline,doc,0,0));return true;}
            }
            if(action.equals("scenario_run")&&!current.host().can(actor,Permission.SCENARIOS))throw new IOException("Запуск редакторского сценария запрещён текущим режимом");
            Set<Permission> captured=request.permissions;
            if(action.equals("scenario_run")){captured=EnumSet.copyOf(captured);captured.add(Permission.SCENARIOS);}
            TestingOnlineBridge.performShared(current.server(),actor,captured,action,value);if(action.startsWith("scenario_")&&!value.contains("|"))cursor.scenario=value;return false;
            });
        }).whenComplete((transition,failure)->onClient(()->{
            if(closed||operation!=request)return;if(failure!=null){failed("Операция отклонена: "+safe(failure.getMessage()));return;}
            request.phase=Boolean.TRUE.equals(transition)?"session_wait":"native_wait";request.message="Операция передана в проверенный обработчик";refreshRequested=true;
        }));
    }
    private void finishIo(Operation request,Throwable failure){if(closed||operation!=request)return;if(failure!=null){failed("Операция файла отклонена: "+safe(failure.getMessage()));return;}request.phase="complete";request.message="Файл сохранён";refreshRequested=true;revision++;}
    public CompletableFuture<Void> beforeTransition(String action,Path source){
        if(closed)return CompletableFuture.failedFuture(new IOException("Координатор закрыт"));
        Operation current=operation;
        if(current==null||Set.of("complete","failed").contains(current.phase))return CompletableFuture.failedFuture(new IOException("Сетевой переход требует авторизованного запроса"));
        if(current.deferredFailure!=null&&!action.equals("extract_abort"))return CompletableFuture.failedFuture(new IOException("Ошибка извлечения допускает только возврат к авторскому миру"));
        Intent step=new Intent(current.intent.operationId(),action,current.intent.value(),source,current.intent.actorId(),current.currentActor,current.intent.sessionId(),current.intent.epoch(),current.intent.islandId(),current.permissions);
        current.phase="transition";current.message="Сохранение и переподключение участников";revision++;
        return lifecycle.beforeTransition(step);
    }
    public CompletableFuture<Void> prepareStopped(Path target,String action,boolean internalExtraction){
        Operation current=operation;
        if(closed||current==null||Set.of("complete","failed").contains(current.phase))return CompletableFuture.failedFuture(new IOException("Нет действующей операции остановленного перехода"));
        Intent step=new Intent(current.intent.operationId(),action,current.intent.value(),current.intent.source(),current.intent.actorId(),current.currentActor,current.intent.sessionId(),current.intent.epoch(),current.intent.islandId(),current.permissions);
        return lifecycle.prepareStopped(step,target,action,internalExtraction);
    }
    public CompletableFuture<Void> opened(MinecraftServer server,Path source,boolean internalExtraction){try{return inArchive(source,()->openedScoped(server,source,internalExtraction));}catch(Exception failure){return CompletableFuture.failedFuture(failure);}}
    private CompletableFuture<Void> openedScoped(MinecraftServer server,Path source,boolean internalExtraction){
        Operation current=operation;if(current==null)return CompletableFuture.failedFuture(new IOException("Потерян контекст перехода"));
        Intent step=new Intent(current.intent.operationId(),current.intent.action(),current.intent.value(),source,current.intent.actorId(),current.currentActor,current.intent.sessionId(),current.intent.epoch(),current.intent.islandId(),current.permissions);
        return lifecycle.rebind(step,server,internalExtraction).thenCompose(rebound->{
            if(internalExtraction){if(rebound!=null&&rebound.host()!=null)return CompletableFuture.failedFuture(new IOException("Внутренняя копия не публикуется"));privateSources.add(server);return CompletableFuture.completedFuture(null);}
            if(rebound==null||rebound.host()==null||rebound.actorGameId()==null)return CompletableFuture.failedFuture(new IOException("Новый ведущий и инициатор не подтверждены"));
            return onServer(server,()->{
                if(closed||operation!=current)throw new IOException("Переход отменён");attach(server,rebound.host());current.currentActor=rebound.actorGameId();
                actor(exact(server),current,current.deferredFailure==null);
                if(current.deferredFailure!=null){current.phase="failed";current.message=current.deferredFailure+"; авторский мир открыт без применения";phase="failed";message=current.message;refreshRequested=true;}
                else current.phase="resuming";revision++;return null;
            });
        });
    }
    public Runnable bind(MinecraftServer server,Runnable work){
        Operation captured=nativeContext.get()==null?operation:nativeContext.get();final Optional<TestingArchiveScope.Token> context;
        try{context=archiveToken(server.getWorldPath(LevelResource.ROOT));}catch(IOException denied){return ()->{throw new CompletionException(denied);};}
        nativePending.incrementAndGet();
        return ()->{try{if(context.isEmpty())runNativeFor(server,captured,work);else context.get().call(()->{try{runNativeFor(server,captured,work);return null;}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Продолжение архива отменено",failure);}});}
            catch(Exception failure){if(failureContext.get()==null&&captured!=null)failureContext.set(captured);throw new CompletionException(failure);}finally{nativePending.decrementAndGet();}};
    }
    public void runNative(MinecraftServer server,Runnable work)throws Exception {inArchive(server.getWorldPath(LevelResource.ROOT),()->{runNativeFor(server,nativeContext.get()==null?operation:nativeContext.get(),work);return null;});}
    private void runNativeFor(MinecraftServer server,Operation request,Runnable work)throws Exception {
        if(privateSources.contains(server)){if(!TestingSessions.internalExtraction(server)||OnlineGameHost.get(server)!=null)throw new IOException("Внутренняя копия изменилась");work.run();return;}
        Binding current=exact(server);
        if(request!=null&&request.deferredFailure!=null)throw new IOException("Отклонённая операция не возобновляет изменения авторского мира");
        request=automaticDiscardOperation(current,request);
        if(request==null)throw new IOException("У продолжения нет подтверждённого инициатора");
        Operation previous=nativeContext.get();nativeContext.set(request);
        try{ServerPlayer actor=actor(current,request);TestingOnlineBridge.withActor(server,actor,request.permissions,()->{work.run();return null;});}
        catch(Exception failure){failureContext.set(request);throw failure;}
        finally{if(previous==null)nativeContext.remove();else nativeContext.set(previous);}
    }
    public boolean action(MinecraftServer server,UUID target,String action,String value)throws IOException {
        if(privateSources.contains(server))return false;
        Binding current=exact(server);Operation request=nativeContext.get()==null?operation:nativeContext.get();if(request==null)throw new IOException("Нет контекста инициатора");
        ServerPlayer actor=actor(current,request);TestingOnlineBridge.continueAction(server,actor,target,request.permissions,action,value);return true;
    }
    public boolean failedInternalExtraction(MinecraftServer server){
        Operation current=operation;return !closed&&current!=null&&current.deferredFailure!=null&&server!=null
            &&privateSources.contains(server)&&TestingSessions.internalExtraction(server)&&OnlineGameHost.get(server)==null
            &&lifecycle.allowInternalReturnAfterFailure(current.intent,server);
    }
    public void failed(String reason){
        Operation captured=failureContext.get();failureContext.remove();
        client.execute(()->{
            Operation current=captured==null?operation:captured;String detail=safe(reason);MinecraftServer source=client.getSingleplayerServer();
            boolean returnOnly=current!=null&&current==operation&&source!=null&&privateSources.contains(source)
                &&TestingSessions.internalExtraction(source)&&OnlineGameHost.get(source)==null&&lifecycle.allowInternalReturnAfterFailure(current.intent,source);
            if(returnOnly){current.deferredFailure=detail;current.phase="returning_after_failure";current.message=detail+"; возврат к авторскому миру";phase=current.phase;message=current.message;revision++;return;}
            if(current==operation){phase="failed";message=detail;}
            if(current!=null){current.phase="failed";current.message=detail;try{lifecycle.failed(current.intent,detail);}catch(RuntimeException ignored){}}revision++;
        });
    }
    public void tick(){try{inArchive(archiveWorld(),()->{tickScoped();return null;});}catch(Exception failure){failed("Контекст общего архива недоступен");}}
    private void tickScoped(){
        if(closed||binding==null)return;
        TestingSessions.tick(client);
        Binding current=binding;if(current==null)return;
        TestingExportLibraryClient.ensure(client);updateSessionView();
        Operation request=operation;
        if(nativePending.get()==0&&request!=null&&request.started&&Set.of("native_wait","session_wait","resuming","awaiting_confirmation").contains(request.phase)&&!TestingSessions.busy()){
            if(TestingSessions.transferReviewActive())request.phase="awaiting_confirmation";
            else if(current.server().isRunning()&&!TestingServer.busy(current.server())&&System.nanoTime()-request.startedAt>250_000_000L){request.phase="complete";request.message=safe(TestingSessions.status());String selected=TestingSessions.selectedVersion();Cursor owner=cursors.get(request.intent.actorId());if(owner!=null&&!selected.isEmpty())owner.selected=selected;phase="ready";refreshRequested=true;revision++;refreshTimelines(current);}
        }
        if(refreshRequested&&!catalogBusy&&client.getSingleplayerServer()==current.server()&&current.server().isRunning()){refreshRequested=false;refreshCatalog(TestingSessions.authorWorld(current.server()));}
    }
    private void updateSessionView(){
        JsonObject test=new JsonObject();test.addProperty("running",TestingSessions.isTestRunning());test.addProperty("paused",TestingSessions.isTestPaused());test.addProperty("reviewReady",TestingSessions.reviewReady());
        test.add("reviewLines",strings(TestingSessions.reviewLines(),128));test.addProperty("keepObjects",TestingSessions.keepObjects());test.addProperty("keepBuildings",TestingSessions.keepBuildings());test.addProperty("keepSettings",TestingSessions.keepSettings());
        JsonObject transfer=new JsonObject();transfer.addProperty("active",TestingSessions.transferReviewActive());transfer.addProperty("ready",TestingSessions.transferReviewReady());transfer.addProperty("canApply",TestingSessions.transferReviewCanApply());transfer.add("lines",strings(TestingSessions.transferReviewLines(),128));
        JsonObject root=new JsonObject();root.add("test",test);root.add("transfer",transfer);root.addProperty("preview",TestingSessions.isPreview());root.addProperty("busy",TestingSessions.busy());root.addProperty("status",safe(TestingSessions.status()));sessionView=root;
        JsonObject library=new JsonObject();library.addProperty("revision",TestingExportLibraryClient.revision());library.addProperty("busy",TestingExportLibraryClient.busy());library.addProperty("status",safe(TestingExportLibraryClient.status()));JsonArray rows=new JsonArray();
        for(var entry:TestingExportLibraryClient.entries()){JsonObject row=new JsonObject();row.addProperty("filename",entry.filename());row.addProperty("name",entry.name());row.addProperty("description",entry.description());row.addProperty("category",entry.category());row.addProperty("created",entry.created());row.addProperty("bytes",entry.bytes());row.addProperty("hasThumbnail",entry.hasThumbnail());row.addProperty("error",safe(entry.error()));rows.add(row);}
        library.add("exports",rows);libraryView=library;
    }
    private void refreshCatalog(Path source){
        catalogBusy=true;long generation=++catalogGeneration;catalogStatus="Чтение истории ведущего";catalogWork=io(()->{
            JsonArray history=new JsonArray();for(var version:TestingArchive.versions(source)){JsonObject row=new JsonObject();row.addProperty("id",version.id());row.addProperty("name",version.name());row.addProperty("description",version.description());row.addProperty("category",version.category());row.addProperty("bytes",version.bytes());row.addProperty("hasThumbnail",!version.thumbnail().isEmpty());row.addProperty("protected",version.protectedVersion());history.add(row);}
            JsonArray objects=new JsonArray();for(String entry:TestingPrefabs.list()){String[] parts=entry.split(" \\| ",3);JsonObject row=new JsonObject();row.addProperty("id",parts[0]);row.addProperty("name",parts.length>1?parts[1]:parts[0]);objects.add(row);}
            long bytes=TestingArchive.storedBytes();return new Object[]{history,objects,bytes};
        }).whenComplete((data,failure)->onClient(()->{
            if(closed||generation!=catalogGeneration)return;catalogBusy=false;
            if(failure!=null){catalogStatus="История недоступна: "+safe(failure.getMessage());return;}
            versions=(JsonArray)data[0];prefabs=(JsonArray)data[1];storageStatus="Архив: "+data[2]+" байт";catalogStatus="Версий: "+versions.size();revision++;
        }));
    }
    public JsonObject snapshot(MinecraftServer server,ServerPlayer actor)throws IOException {try{return inArchive(server.getWorldPath(LevelResource.ROOT),()->snapshotScoped(server,actor));}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Снимок архива отклонён",failure);}}
    private JsonObject snapshotScoped(MinecraftServer server,ServerPlayer actor)throws IOException {
        Binding current=exact(server);Cursor cursor=cursor(current.host().actor(actor));JsonObject state=sessionView,root=new JsonObject(),history=new JsonObject();
        history.addProperty("revision",revision);history.addProperty("status",catalogStatus);history.addProperty("busy",catalogBusy||state.has("busy")&&state.get("busy").getAsBoolean());history.addProperty("selectedId",cursor.selected);history.addProperty("storageStatus",storageStatus);
        history.addProperty("preview",state.has("preview")&&state.get("preview").getAsBoolean());history.addProperty("page",cursor.historyPage);history.addProperty("total",versions.size());history.add("versions",page(versions,cursor.historyPage,12));history.add("transferReview",state.has("transfer")?state.get("transfer").deepCopy():new JsonObject());root.add("history",history);
        root.add("test",state.has("test")?state.get("test").deepCopy():new JsonObject());JsonObject objects=new JsonObject();objects.addProperty("revision",revision);objects.addProperty("status",safe(TestingPrefabs.status()));objects.addProperty("busy",catalogBusy);objects.addProperty("selectedId",cursor.prefab);objects.addProperty("page",cursor.prefabPage);objects.addProperty("total",prefabs.size());objects.add("entries",page(prefabs,cursor.prefabPage,24));root.add("prefabs",objects);
        JsonObject library=libraryView.deepCopy();JsonArray all=library.has("exports")?library.getAsJsonArray("exports"):new JsonArray();library.addProperty("page",cursor.libraryPage);library.addProperty("total",all.size());library.add("exports",page(all,cursor.libraryPage,12));root.add("library",library);
        Operation request=operation;JsonObject progress=new JsonObject();progress.addProperty("phase",request==null?phase:request.phase);progress.addProperty("status",request==null?message:request.message);
        if(request!=null){progress.addProperty("id",request.intent.operationId().toString());progress.addProperty("actorId",request.intent.actorId());progress.addProperty("action",request.intent.action());}root.add("operation",progress);
        if(!cursor.scenario.isEmpty()){JsonObject timeline=new JsonObject();timeline.addProperty("name",cursor.scenario);timeline.add("checkpoints",strings(cursor.checkpoints,32));root.add("scenarioTimeline",timeline);}
        return root;
    }
    private void refreshTimelines(Binding current){
        if(current==null||!current.server().isRunning())return;
        onServer(current.server(),()->{if(binding!=current)return null;for(var entry:cursors.entrySet()){
            Cursor cursor=entry.getValue();if(cursor.scenario.isEmpty())continue;
            ServerPlayer player=current.server().getPlayerList().getPlayers().stream().filter(candidate->entry.getKey().equals(current.host().actor(candidate))).findFirst().orElse(null);
            try{cursor.checkpoints=TestingOnlineBridge.withActor(current.server(),player,Set.of(Permission.VIEW),()->List.copyOf(TestingSessions.scenarioCheckpoints(current.server(),cursor.scenario)));}
            catch(IOException unavailable){cursor.checkpoints=List.of();}
        }return null;});
    }
    private JsonObject receipt(Operation request){JsonObject reply=OnlineJson.ok();reply.addProperty("phase","accepted");reply.addProperty("operationId",request.intent.operationId().toString());reply.addProperty("revision",revision);return reply;}
    private static JsonArray page(JsonArray values,int page,int size){JsonArray result=new JsonArray();int start=(int)Math.min(values.size(),(long)page*size);for(int index=start;index<Math.min(values.size(),start+size);index++)result.add(values.get(index).deepCopy());return result;}
    private JsonArray strings(List<String> values,int limit){JsonArray result=new JsonArray();values.stream().limit(limit).map(this::safe).forEach(result::add);return result;}
    private String safe(String value){if(value==null)return "";String base=client.gameDirectory.getAbsolutePath();String result=value.replace(base,"<ведущий>").replace(base.replace('\\','/'),"<ведущий>");return result.codePoints().limit(2048).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    private static boolean uuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(RuntimeException bad){return false;}}
    private static BlockPos position(String value)throws IOException {String[] v=value.split(",",-1);if(v.length!=3)throw new IOException("Нужны x,y,z");try{int x=Integer.parseInt(v[0]),y=Integer.parseInt(v[1]),z=Integer.parseInt(v[2]);if(Math.abs((long)x)>29999984||Math.abs((long)z)>29999984||y<-2048||y>2048)throw new IOException("Координаты вне мира");return new BlockPos(x,y,z);}catch(NumberFormatException invalid){throw new IOException("Неверная позиция",invalid);}}
    private <T> CompletableFuture<T> authorizedIo(Operation request,Callable<T> task){
        return io(()->{Binding current=binding;if(current==null)throw new IOException("Ведущий недоступен");
            try{onServer(current.server(),()->{actor(exact(current.server()),request);return null;}).get(5,TimeUnit.SECONDS);}
            catch(ExecutionException failure){throw new IOException("Авторизация файла не подтверждена",failure.getCause());}
            if(closed)throw new IOException("Координатор закрыт");return task.call();});
    }
    private <T> CompletableFuture<T> io(Callable<T> work){
        CompletableFuture<T> result=new CompletableFuture<>();long generation=ioGeneration;final Optional<TestingArchiveScope.Token> context;
        try{context=archiveToken(archiveWorld());}catch(IOException denied){return CompletableFuture.failedFuture(denied);}
        FutureTask<Void> task=new FutureTask<>(()->{try{if(closed||generation!=ioGeneration)throw new IOException("Координатор отключён");
            T value=context.isEmpty()?work.call():context.get().call(()->{try{return work.call();}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Файловое продолжение отменено",failure);}});
            if(closed||generation!=ioGeneration)throw new IOException("Координатор отключён");result.complete(value);
        }catch(Exception failure){result.completeExceptionally(failure);}return null;}){
            @Override protected void done(){ioJobs.remove(this);if(isCancelled())result.completeExceptionally(new IOException("Операция координатора отменена"));}
        };
        ioJobs.add(task);result.whenComplete((value,failure)->{if(result.isCancelled())task.cancel(true);});
        try{io.execute(task);}catch(RejectedExecutionException full){ioJobs.remove(task);result.completeExceptionally(new IOException("Очередь файлов занята"));}return result;
    }
    private <T> CompletableFuture<T> onServer(MinecraftServer server,Callable<T> task){
        CompletableFuture<T> result=new CompletableFuture<>();final Optional<TestingArchiveScope.Token> context;
        try{context=archiveToken(server.getWorldPath(LevelResource.ROOT));}catch(IOException denied){return CompletableFuture.failedFuture(denied);}
        server.execute(()->{try{
            T value=context.isEmpty()?task.call():context.get().call(()->{try{return task.call();}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Серверное продолжение архива отклонено",failure);}});
            result.complete(value);
        }catch(Exception failure){result.completeExceptionally(failure);}});return result;
    }
    public boolean busy(){Operation request=operation;return nativePending.get()>0||binding!=null&&TestingSessions.busy()||request!=null&&!Set.of("complete","failed").contains(request.phase);}
    /** Normal close/reconfiguration only; a controlled stopped-save transition keeps this coordinator attached. */
    public void detach(){
        ioGeneration++;catalogGeneration++;TestingSessions.removeOnlineHooks(this);Binding current=binding;binding=null;boundAuthor=null;operation=null;
        if(current!=null)current.server().execute(()->TestingOnlineBridge.removeAdvanced(current.server(),current.host(),this));
        for(Future<?> task:List.copyOf(ioJobs))task.cancel(true);io.purge();catalogWork=null;catalogBusy=false;
        privateSources.clear();cursors.clear();versions=new JsonArray();prefabs=new JsonArray();sessionView=new JsonObject();libraryView=new JsonObject();phase="ready";message="Отключено";refreshRequested=true;revision++;
    }
    public void close(){if(closed)return;detach();closed=true;io.shutdownNow();}

}
