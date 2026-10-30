// Dispatches authenticated online editor actions through marked-island server authority.
package com.dark.winter.testing;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.world.IslandAccess.Permission;
import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Typed marked-island adapter. Network peers never enter the legacy owner-only dispatcher implicitly. */
public final class TestingOnlineBridge {
    private TestingOnlineBridge(){}
    private static final Gson JSON=new Gson();
    private static final String MODE_REASON="Смена режима доступна только через координатор испытания: запуск, пауза, обзор и завершение";
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    private static final AtomicBoolean INITIALIZED=new AtomicBoolean();
    private static final Set<String> SETTINGS=Set.of("toggle","all_on","all_off","group_on","group_off","reset","parameter","setting","diagnostics");
    private static final Set<String> VIEW=Set.of("select_a","select_b","scene_select","scene_pick","scene_pick_entity","scene_focus","scene_refresh","teleport","preview_brush","scenario_document");
    private static final Set<String> EDIT=Set.of("place","rotate","remove","move","copy","group_rotate","brush","protect","unprotect","zone_rules","spawn","give","scene_capture","scene_rename","scene_group","scene_ungroup","scene_reparent","scene_move","scene_copy","scene_rotate","scene_remove");
    private static final Set<String> TOOLS=Set.of("brush","radius","strength","density","brushShape","grassSpecies","bushSpecies","treeSpecies","flySpeed");
    private static final List<String> ACTIONS=List.of("toggle","all_on","all_off","group_on","group_off","reset","parameter","setting","diagnostics","select_a","select_b","scene_select","scene_pick","scene_pick_entity","scene_focus","scene_refresh","teleport","preview_brush","scenario_document","camera","mode","place","rotate","remove","move","copy","group_rotate","brush","resize","biome","vegetation","protect","unprotect","zone_rules","spawn","give","undo","redo","profile_save","profile_load","report","scenario_save","scenario_run","scenario_stop","scenario_event","checkpoint","history_refresh","start","pause","review","finish","prefab_save","prefab_scene_save","scenario_checkpoint","camera_bookmarks");
    public interface AdvancedRoutes {
        Set<String> actions();
        Set<Permission> permissions(String action,String value)throws IOException;
        CompletableFuture<JsonObject> command(MinecraftServer server,ServerPlayer actor,UUID request,String action,String value);
        JsonObject snapshot(MinecraftServer server,ServerPlayer actor)throws IOException;
    }
    @FunctionalInterface public interface AuthorizedWork<T>{T run()throws Exception;}
    public record Selection(BlockPos first,BlockPos second,String sceneId) {
        public Selection {first=first==null?null:first.immutable();second=second==null?null:second.immutable();}
    }
    public static Selection selection(MinecraftServer server,ServerPlayer player)throws IOException {
        State state=state(server,player);Cursor cursor=cursor(state,TestingServer.context(server),player);return new Selection(cursor.a,cursor.b,cursor.selected);
    }
    public static void installAdvanced(MinecraftServer server,OnlineGameHost host,AdvancedRoutes routes){
        State state=STATES.get(server);if(!server.isSameThread()||state==null||state.host!=host||OnlineGameHost.get(server)!=host)throw new IllegalStateException("Exact current Online laboratory host required");
        state.advanced=Objects.requireNonNull(routes);
    }
    public static void removeAdvanced(MinecraftServer server,OnlineGameHost host,AdvancedRoutes routes){
        State state=STATES.get(server);if(state!=null&&state.host==host&&state.advanced==routes)state.advanced=null;
    }
    public static <T> T withActor(MinecraftServer server,ServerPlayer player,Set<Permission> permissions,AuthorizedWork<T> work)throws IOException {
        State state=state(server,player);for(Permission permission:permissions)if(!state.host.can(player,permission))throw new IOException("Право операции отозвано");
        try(var scope=TestingActors.enter(server,player,permissions)){return work.run();}
        catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Действие лаборатории не завершено",failure);}
    }
    /** Exact live captured actor authority for client-side native workbench adapters. */
    public static boolean managedWorkbench(MinecraftServer server)throws IOException {return TestingActors.managedScope(server);}
    public static void performShared(MinecraftServer server,ServerPlayer author,Set<Permission> permissions,String action,String value)throws IOException {
        withActor(server,author,permissions,()->{TestingServer.Context context=TestingServer.context(server);TestingServer.perform(context,author,action,value);context.settings.validate();context.event("online:"+action,value);context.save();return null;});
    }
    /** Trusted coordinator continuation after a lifecycle rebind; wire calls cannot choose this authority set. */
    public static void continueAction(MinecraftServer server,ServerPlayer author,UUID target,Set<Permission> permissions,String action,String value)throws IOException {
        withActor(server,author,permissions,()->{
            State state=state(server,author);TestingServer.Context context=TestingServer.context(server);Cursor cursor=cursor(state,context,author);
            if(action.equals("mode")){
                ServerPlayer recipient=server.getPlayerList().getPlayer(target);if(recipient==null)throw new IOException("Участник вышел");
                TestingServer.perform(context,recipient,action,value);context.event("online-continuation:"+action,value);context.save();return null;
            }
            TestingServer.rebuildSupport(context);
            BlockPos previousA=context.a,previousB=context.b;TestingSettings previous=context.settings;var selected=TestingScene.swapCursor(server,cursor.selected);
            context.a=cursor.a;context.b=cursor.b;context.settings=cloneSettings(previous);copyTools(cursor.tools,context.settings);
            try{TestingServer.perform(context,author,action,value);cursor.a=context.a;cursor.b=context.b;cursor.selected=TestingScene.cursorResult(server);context.event("online-continuation:"+action,value);}
            finally{context.a=previousA;context.b=previousB;context.settings=previous;TestingScene.restoreCursor(server,selected);}
            return null;
        });
    }
    private static final class Cursor {BlockPos a,b;String selected="";List<BlockPos> preview=List.of();JsonObject document;TestingSettings tools;}
    private static final class State {
        final OnlineGameHost host;final String template;final Map<UUID,Cursor> cursors=new HashMap<>();long revision;AdvancedRoutes advanced;
        State(OnlineGameHost host,String template){this.host=host;this.template=template;}
    }
    public static void attach(MinecraftServer server,OnlineGameHost host) {
        if(server==null||host==null||!server.isSameThread()||OnlineGameHost.get(server)!=host||!TestingServer.isTesting(server)||TestingServer.context(server)==null)
            throw new IllegalStateException("Online laboratory requires the exact live marked server context");
        if(INITIALIZED.compareAndSet(false,true))ServerLifecycleEvents.SERVER_STOPPED.register(STATES::remove);
        STATES.compute(server,(key,old)->old!=null&&old.host==host?old:new State(host,template(server)));
    }
    private static String template(MinecraftServer server){
        Properties marker=new Properties();Path file=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/darkwinter-testing.properties");
        try{if(Files.size(file)<=65536)try(InputStream input=Files.newInputStream(file)){marker.load(input);}}catch(IOException ignored){}
        String value=marker.getProperty("template",server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator?"ISLAND":"NORMAL");
        return Set.of("NORMAL","FLAT","ISLAND","TEST_MAP").contains(value)?value:"NORMAL";
    }
    private static State state(MinecraftServer server,ServerPlayer player)throws IOException {
        if(server==null)throw new IOException("Нет сервера лаборатории");
        State state=STATES.get(server);
        if(server==null||!server.isSameThread()||state==null||OnlineGameHost.get(server)!=state.host||!TestingServer.isTesting(server)
                ||TestingServer.context(server)==null||player==null||server.getPlayerList().getPlayer(player.getUUID())!=player||player.level().getServer()!=server||!player.level().dimension().equals(Level.OVERWORLD)
                ||!state.host.can(player,Permission.VIEW))throw new IOException("Нет действительного допуска к лаборатории острова");
        return state;
    }
    private static Cursor cursor(State state,TestingServer.Context context,ServerPlayer player){
        return state.cursors.computeIfAbsent(player.getUUID(),id->{Cursor cursor=new Cursor();if(state.host.localIdentityId().equals(state.host.actor(player))){cursor.a=context.a;cursor.b=context.b;cursor.selected=TestingScene.selected(context.server);}cursor.tools=cloneSettings(context.settings);return cursor;});
    }
    public static JsonObject snapshot(MinecraftServer server,ServerPlayer player) {
        try{return build(server,player,state(server,player));}
        catch(IOException failure){throw new IllegalStateException("Online laboratory snapshot is unavailable",failure);}
    }
    private static JsonObject build(MinecraftServer server,ServerPlayer player,State state)throws IOException {
        TestingServer.Context context=TestingServer.context(server);Cursor cursor=cursor(state,context,player);JsonObject root=new JsonObject();
        root.addProperty("schema",1);root.addProperty("revision",++state.revision);
        JsonObject world=new JsonObject();world.addProperty("id",state.host.islandId());world.addProperty("name",server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).getFileName().toString());
        world.addProperty("template",state.template);world.addProperty("dimension",server.overworld().dimension().identifier().toString());root.add("world",world);
        TestingSettings shown=cloneSettings(context.settings);copyTools(cursor.tools,shown);root.add("settings",JSON.toJsonTree(shown));root.addProperty("status",TestingServer.status(server));root.addProperty("busy",TestingServer.busy(server));root.addProperty("recoveryBlocked",TestingServer.recoveryBlocked(server));
        JsonObject selection=new JsonObject();selection.addProperty("sceneId",cursor.selected);selection.addProperty("description",(cursor.a==null?"—":cursor.a.toShortString())+" → "+(cursor.b==null?"—":cursor.b.toShortString()));root.add("selection",selection);
        JsonObject vegetation=new JsonObject();for(String category:List.of("grass","bushes","trees"))vegetation.addProperty(category,TestingServer.vegetated(server,category));root.add("vegetation",vegetation);
        JsonArray scene=new JsonArray();for(TestingScene.ObjectView object:TestingScene.views(server).stream().limit(8192).toList()){
            JsonObject row=new JsonObject();row.addProperty("id",object.id());row.addProperty("parentId",object.parentId());row.addProperty("name",object.name());row.addProperty("kind",object.kind());
            row.addProperty("blockCount",object.blockCount());row.addProperty("npcCount",object.npcCount());row.addProperty("loaded",object.loaded());row.add("min",pos(object.min()));row.add("max",pos(object.max()));scene.add(row);
        }root.add("scene",scene);
        JsonArray zones=new JsonArray();for(TestingServer.ZoneView zone:TestingServer.protectedZones(server).stream().limit(512).toList()){
            JsonObject row=new JsonObject();row.addProperty("name",zone.name());row.add("min",pos(new BlockPos(zone.x0(),zone.y0(),zone.z0())));row.add("max",pos(new BlockPos(zone.x1(),zone.y1(),zone.z1())));
            row.addProperty("natural",zone.natural());row.addProperty("objects",zone.objects());zones.add(row);
        }root.add("zones",zones);JsonArray preview=new JsonArray();cursor.preview.stream().limit(2401).forEach(point->preview.add(pos(point)));root.add("protectedPreview",preview);
        JsonObject generator=new JsonObject();if(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator island){generator.addProperty("island",true);generator.addProperty("radius",island.radius());generator.addProperty("shape",island.shape());generator.addProperty("hills",island.hills());}else generator.addProperty("island",false);root.add("generator",generator);
        JsonObject scenarios=new JsonObject();scenarios.addProperty("status",TestingScenarios.status(server));scenarios.add("names",JSON.toJsonTree(TestingScenarios.names(server)));
        scenarios.add("spawners",JSON.toJsonTree(TestingScenarios.spawnerNames(server)));scenarios.add("spawnableTypes",JSON.toJsonTree(TestingScenarios.spawnableTypes()));JsonArray triggers=new JsonArray();
        for(TestingScenarios.TriggerView trigger:TestingScenarios.triggerViews(server).stream().limit(1024).toList()){JsonObject row=new JsonObject();row.addProperty("name",trigger.name());row.add("min",pos(new BlockPos(trigger.x0(),trigger.y0(),trigger.z0())));row.add("max",pos(new BlockPos(trigger.x1(),trigger.y1(),trigger.z1())));triggers.add(row);}
        scenarios.add("triggers",triggers);if(cursor.document!=null)scenarios.add("selectedDocument",cursor.document.deepCopy());root.add("scenarios",scenarios);
        JsonObject capabilities=new JsonObject(),reasons=new JsonObject();Set<String> actions=new LinkedHashSet<>(ACTIONS);actions.addAll(EDIT);if(state.advanced!=null)actions.addAll(state.advanced.actions());for(String action:actions){boolean allowed=VIEW.contains(action)||Set.of("parameter","setting").contains(action)||SETTINGS.contains(action)&&state.host.can(player,Permission.SYSTEMS)||(action.equals("brush")&&(state.host.can(player,Permission.TERRAIN)||state.host.can(player,Permission.WATER)))||EDIT.contains(action)&&permitted(state,player,action,"")||(action.equals("undo")||action.equals("redo"))&&(state.host.can(player,Permission.UNDO_ANY)||state.host.can(player,Permission.UNDO_OWN));
            String reason=null;
            try{
                if(action.equals("mode"))throw new IOException(MODE_REASON);
                if(state.advanced!=null&&state.advanced.actions().contains(action))allowed=state.advanced.permissions(action,"").stream().allMatch(permission->state.host.can(player,permission));
            }catch(IOException unavailable){allowed=false;reason=unavailable.getMessage();}
            capabilities.addProperty(action,allowed);
            if(!allowed){if(reason==null||reason.isBlank())reason=SETTINGS.contains(action)?"Роль не разрешает менять общие системы острова":"Сетевой маршрут требует отдельного безопасного координатора; действие ещё недоступно";
                reasons.addProperty(action,reason.codePoints().limit(512).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString());}
        }
        root.add("capabilities",capabilities);root.add("reasons",reasons);root.add("historyAuthors",JSON.toJsonTree(context.edits.historyActors()));
        if(state.advanced!=null){JsonObject advanced=state.advanced.snapshot(server,player);for(var item:advanced.entrySet()){
            if(!Set.of("history","test","prefabs","library","scenarioTimeline","operation").contains(item.getKey()))throw new IOException("Неизвестный раздел координатора");
            root.add(item.getKey(),item.getValue());
        }}
        if(OnlineJson.bytes(root).length>2*1024*1024)throw new IOException("Снимок лаборатории превышает безопасный размер");return root;
    }
    public static CompletableFuture<JsonObject> command(MinecraftServer server,ServerPlayer player,UUID request,JsonObject body){
        CompletableFuture<JsonObject> result=new CompletableFuture<>();if(server==null)return CompletableFuture.failedFuture(new IOException("Нет сервера"));
        Runnable action=()->{
            try{
                State state=state(server,player);Objects.requireNonNull(request);OnlineJson.keys(body,"op","action","value");
                if(!OnlineJson.string(body,"op",16).equals("lab"))throw new IOException("Неизвестный лабораторный маршрут");
                String operation=OnlineJson.string(body,"action",64),value=body.has("value")?OnlineJson.string(body,"value",65536):"";
                TestingServer.Context context=TestingServer.context(server);Cursor cursor=cursor(state,context,player);
                if(operation.equals("mode"))throw new IOException(MODE_REASON);
                if(state.advanced!=null&&state.advanced.actions().contains(operation)){
                    for(Permission permission:state.advanced.permissions(operation,value))if(!state.host.can(player,permission))throw new IOException("Назначенная роль не разрешает действие координатора");
                    state.advanced.command(server,player,request,operation,value).whenComplete((reply,failure)->{if(failure==null)result.complete(reply);else result.completeExceptionally(failure);});return;
                }
                if(operation.equals("parameter")||operation.equals("setting")){
                    String key=value.contains("=")?value.split("=",2)[0]:value.split("\\|",2)[0];
                    if(TOOLS.contains(key)){
                        TestingSettings previous=context.settings;String previousMessage=context.message;context.settings=cloneSettings(previous);copyTools(cursor.tools,context.settings);
                        try{
                            if(key.equals("flySpeed")){String[] parameter=value.contains("=")?value.split("=",2):value.split("\\|",2);if(parameter.length!=2)throw new IOException("Нужны имя и значение");context.settings.flySpeed=Float.parseFloat(parameter[1]);context.settings.validate();player.getAbilities().setFlyingSpeed(context.settings.flySpeed);player.onUpdateAbilities();}
                            else TestingServer.perform(context,player,operation,value);
                            copyTools(context.settings,cursor.tools);
                        }finally{context.settings=previous;context.message=previousMessage;}
                        result.complete(accepted(server,state));return;
                    }
                }
                if(VIEW.contains(operation)&&!Set.of("scene_pick","scene_pick_entity").contains(operation)){
                    try(var actor=TestingActors.enter(server,player,Set.of(Permission.VIEW))){view(server,player,state,cursor,operation,value);}
                }
                else {
                    if(!SETTINGS.contains(operation)&&!EDIT.contains(operation)&&!Set.of("scene_pick","scene_pick_entity","undo","redo").contains(operation))throw new IOException("Безопасный сетевой маршрут этого действия ещё не подключён");
                    Set<Permission> permissions=(operation.equals("undo")||operation.equals("redo"))?Set.of(state.host.can(player,Permission.UNDO_ANY)?Permission.UNDO_ANY:Permission.UNDO_OWN):required(operation,value);
                    for(Permission permission:permissions)if(!state.host.can(player,permission))throw new IOException("Назначенная роль не разрешает это действие");
                    if(context.readOnlyPreview)throw new IOException("Предпросмотр доступен только для чтения");
                    if(context.edits.documentsBusy()||TestingServer.busy(server))throw new IOException("Дождитесь завершения транзакции острова");
                    if(EDIT.contains(operation)&&!Set.of("EDIT","CHARACTER").contains(context.settings.mode))throw new IOException("Редактирование запрещено во время общего испытания");
                    JsonObject replay=new JsonObject();replay.addProperty("op","lab");replay.addProperty("action",operation);replay.addProperty("value",value);
                    boolean deferred;
                    try(var actor=TestingActors.enter(server,player,permissions)){
                        deferred=context.edits.deferNativeAction(()->command(server,player,request,replay).whenComplete((reply,failure)->{if(failure==null)result.complete(reply);else result.completeExceptionally(failure);}));
                    }
                    if(deferred)return;
                    if(SETTINGS.contains(operation)){
                        try(var actor=TestingActors.enter(server,player,permissions)){TestingServer.perform(context,player,operation,value);context.settings.validate();context.event("online:"+operation,value);context.save();}
                    }else {
                        TestingServer.rebuildSupport(context);
                        validateTargets(server,operation,value);
                        if(operation.equals("group_rotate")&&!value.contains("|")){if(cursor.a==null||cursor.b==null)throw new IOException("Сначала выделите углы A и B");boundedSelection(server,cursor.a,cursor.b);}
                        BlockPos previousA=context.a,previousB=context.b;TestingSettings previous=context.settings;
                        var previousScene=TestingScene.swapCursor(server,cursor.selected);
                        context.a=cursor.a;context.b=cursor.b;context.settings=cloneSettings(previous);copyTools(cursor.tools,context.settings);
                        try(var actor=TestingActors.enter(server,player,permissions)){
                            if(operation.equals("undo")||operation.equals("redo")){
                                boolean any=state.host.can(player,Permission.UNDO_ANY);String identity=state.host.actor(player);
                                if(operation.equals("undo"))context.edits.undoFor(identity,any);else context.edits.redoFor(identity,any);
                            }else TestingServer.perform(context,player,operation,value);
                            cursor.a=context.a;cursor.b=context.b;cursor.selected=TestingScene.cursorResult(server);context.event("online:"+operation,value);
                        }finally{context.a=previousA;context.b=previousB;context.settings=previous;TestingScene.restoreCursor(server,previousScene);}
                    }
                }
                result.complete(accepted(server,state));
            }catch(Exception failure){result.completeExceptionally(failure instanceof IOException?failure:new IOException("Неверные параметры лабораторного действия",failure));}
        };
        if(server.isSameThread())action.run();else server.execute(action);return result;
    }
    /** A command receipt is distinct from its separately published world-state packet and eventual WAL commit. */
    private static JsonObject accepted(MinecraftServer server,State state){
        JsonObject reply=OnlineJson.ok();reply.addProperty("phase","accepted");reply.addProperty("revision",++state.revision);reply.addProperty("busy",TestingServer.busy(server));
        String status=TestingServer.status(server);reply.addProperty("status",status.codePoints().limit(512).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString());return reply;
    }
    private static void view(MinecraftServer server,ServerPlayer player,State state,Cursor cursor,String action,String value)throws IOException {
        switch(action){
            case "select_a"->cursor.a=position(server,value);
            case "select_b"->cursor.b=position(server,value);
            case "scene_refresh"->{}
            case "scene_select"->{if(!value.isEmpty()&&TestingScene.views(server).stream().noneMatch(object->object.id().equals(value)))throw new IOException("Объект сцены отсутствует");cursor.selected=value;}
            case "scene_focus"->{
                TestingScene.ObjectView object=TestingScene.views(server).stream().filter(item->item.id().equals(value)).findFirst().orElseThrow(()->new IOException("Объект сцены отсутствует"));
                if(!object.loaded())throw new IOException("Сначала загрузите область объекта");
                teleport(server,player,(object.min().getX()+object.max().getX()+1)/2d,Math.min(server.overworld().getMaxY()-1,object.max().getY()+4),(object.min().getZ()+object.max().getZ()+1)/2d,player.getYRot(),35);
            }
            case "teleport"->{String[] fields=value.split(",");if(fields.length!=5)throw new IOException("Нужны координаты и направление");teleport(server,player,Double.parseDouble(fields[0]),Double.parseDouble(fields[1]),Double.parseDouble(fields[2]),Float.parseFloat(fields[3]),Float.parseFloat(fields[4]));}
            case "preview_brush"->{
                String[] fields=value.split("\\|");if(fields.length!=2)throw new IOException("Нужны центр и радиус");BlockPos center=position(server,fields[0]);int radius=Math.clamp(Integer.parseInt(fields[1]),1,24);
                List<BlockPos> points=new ArrayList<>();var context=TestingServer.context(server);
                for(int x=center.getX()-radius;x<=center.getX()+radius;x++)for(int z=center.getZ()-radius;z<=center.getZ()+radius;z++){
                    int y=center.getY();if(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator island)y=TestingTerrain.height(context,island,x,z);
                    BlockPos point=new BlockPos(x,y,z);if(TestingServer.naturalProtectedAt(context,point))points.add(point);
                }cursor.preview=List.copyOf(points);
            }
            case "scenario_document"->{
                JsonObject docs=TestingScenarios.exportDocuments(server);cursor.document=null;
                for(JsonElement element:docs.getAsJsonArray("scenarios"))if(element.getAsJsonObject().get("name").getAsString().equals(value)){cursor.document=element.getAsJsonObject().deepCopy();break;}
                if(cursor.document==null)throw new IOException("Сценарий отсутствует");
            }
            default->throw new IOException("Неизвестный маршрут просмотра");
        }
    }
    private static TestingSettings cloneSettings(TestingSettings source){return JSON.fromJson(JSON.toJsonTree(source),TestingSettings.class);}
    private static void copyTools(TestingSettings from,TestingSettings to){to.radius=from.radius;to.strength=from.strength;to.density=from.density;to.brush=from.brush;to.brushShape=from.brushShape;to.grassSpecies=from.grassSpecies;to.bushSpecies=from.bushSpecies;to.treeSpecies=from.treeSpecies;to.flySpeed=from.flySpeed;}
    private static boolean permitted(State state,ServerPlayer player,String action,String value){try{return required(action,value).stream().allMatch(permission->state.host.can(player,permission));}catch(IOException denied){return false;}}
    static Set<Permission> required(String action,String value)throws IOException {
        if(SETTINGS.contains(action))return Set.of(Permission.SYSTEMS);
        if(VIEW.contains(action))return Set.of(Permission.VIEW);
        if(action.equals("undo")||action.equals("redo"))return Set.of(Permission.UNDO_OWN);
        return switch(action){
            case "place"->Set.of(Permission.BUILD);
            case "remove"->Set.of(Permission.BREAK);
            case "rotate","move","copy","group_rotate","give"->Set.of(Permission.OBJECTS);
            case "brush"->Set.of(value.startsWith("water|")||value.startsWith("drain|")?Permission.WATER:Permission.TERRAIN);
            case "biome"->Set.of(Permission.BIOME);case "vegetation"->Set.of(Permission.VEGETATION);case "resize"->Set.of(Permission.TERRAIN);
            case "mode"->throw new IOException(MODE_REASON);
            case "camera","report"->Set.of(Permission.VIEW);
            case "profile_save","profile_load"->Set.of(Permission.SYSTEMS);
            case "protect","unprotect","zone_rules","spawn"->Set.of(Permission.MANAGE_ZONES);
            default->{if(action.startsWith("scene_")&&TestingScene.handles(action))yield Set.of(Permission.OBJECTS);if(TestingScenarios.handles(action))yield Set.of(Permission.SCENARIOS);throw new IOException("Сетевой маршрут действия не подключён");}
        };
    }
    private static void validateTargets(MinecraftServer server,String action,String value)throws IOException {
        if(Set.of("remove","rotate","scene_pick").contains(action)){BlockPos target=position(server,value);if(!server.overworld().hasChunkAt(target))throw new IOException("Область должна быть загружена");}
        else if(action.equals("place")){String[] fields=value.split("\\|");if(fields.length!=2)throw new IOException("Нужны блок и позиция");BlockPos target=position(server,fields[1]);if(!server.overworld().hasChunkAt(target))throw new IOException("Область должна быть загружена");}
        else if(action.equals("brush")){String[] fields=value.split("\\|");if(fields.length<2||fields.length>4)throw new IOException("Неверный формат кисти");position(server,fields[1]);}
        else if(action.equals("move")||action.equals("copy")){
            String[] fields=value.split("\\|");if(fields.length!=3)throw new IOException("Нужны два угла и смещение");BlockPos a=position(server,fields[0]),b=position(server,fields[1]),delta=TestingServer.position(fields[2]);
            boundedSelection(server,a,b);BlockPos targetA=position(server,offset(a,delta)),targetB=position(server,offset(b,delta));boundedSelection(server,targetA,targetB);
        }
        else if(action.equals("group_rotate")&&value.contains("|")){String[] fields=value.split("\\|");if(fields.length!=2)throw new IOException("Нужны два угла");BlockPos a=position(server,fields[0]),b=position(server,fields[1]);boundedSelection(server,a,b);}
    }
    private static String offset(BlockPos a,BlockPos delta)throws IOException {
        long x=(long)a.getX()+delta.getX(),y=(long)a.getY()+delta.getY(),z=(long)a.getZ()+delta.getZ();
        if(Math.abs(x)>29999984||Math.abs(z)>29999984||y<Integer.MIN_VALUE||y>Integer.MAX_VALUE)throw new IOException("Смещение выходит за границы мира");
        return x+","+y+","+z;
    }
    private static void boundedSelection(MinecraftServer server,BlockPos a,BlockPos b)throws IOException {
        long count=((long)Math.abs(a.getX()-b.getX())+1)*(Math.abs(a.getY()-b.getY())+1)*(Math.abs(a.getZ()-b.getZ())+1);
        if(count>8192)throw new IOException("Группа больше 8192 блоков");
        for(int x=Math.min(a.getX(),b.getX())>>4;x<=Math.max(a.getX(),b.getX())>>4;x++)
            for(int z=Math.min(a.getZ(),b.getZ())>>4;z<=Math.max(a.getZ(),b.getZ())>>4;z++)
                if(!server.overworld().hasChunk(x,z))throw new IOException("Область группы должна быть загружена");
    }
    private static BlockPos position(MinecraftServer server,String value)throws IOException {
        BlockPos pos=TestingServer.position(value);
        if(Math.abs((long)pos.getX())>29999984||Math.abs((long)pos.getZ())>29999984||pos.getY()<server.overworld().getMinY()||pos.getY()>=server.overworld().getMaxY())throw new IOException("Координаты вне области лаборатории");
        return pos;
    }
    private static void teleport(MinecraftServer server,ServerPlayer player,double x,double y,double z,float yaw,float pitch)throws IOException {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(x)>29999984||Math.abs(z)>29999984
                ||y<server.overworld().getMinY()||y>=server.overworld().getMaxY())throw new IOException("Недопустимая позиция просмотра");
        player.teleportTo(server.overworld(),x,y,z,Set.of(),yaw,Math.clamp(pitch,-90,90),false);
    }
    private static JsonObject pos(BlockPos position){JsonObject result=new JsonObject();if(position!=null){result.addProperty("x",position.getX());result.addProperty("y",position.getY());result.addProperty("z",position.getZ());}return result;}
}
