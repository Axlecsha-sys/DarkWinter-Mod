// Builds read-only laboratory state from the current authenticated game connection.
package com.dark.winter.client.online;

import com.dark.winter.client.TestingWorlds;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.testing.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Read-only Lab DTOs, bound to the current authenticated game connection, never a directory endpoint. */
public final class OnlineTestingView {
    public record Generator(boolean island,int radius,String shape,double hills) {}
    public record VersionEntry(String id,String name,String description,String category,long bytes,boolean hasThumbnail,boolean protectedVersion) {}
    public record TransferReview(boolean active,boolean ready,boolean canApply,List<String> lines) {}
    public record History(long revision,String status,boolean busy,String selectedId,String storageStatus,boolean preview,int page,int total,List<VersionEntry> versions,TransferReview transferReview) {}
    public record TestState(boolean running,boolean paused,boolean reviewReady,List<String> reviewLines,boolean keepObjects,boolean keepBuildings,boolean keepSettings) {}
    public record PrefabEntry(String id,String name) {}
    public record Prefabs(long revision,String status,boolean busy,String selectedId,int page,int total,List<PrefabEntry> entries) {}
    public record ExportEntry(String filename,String name,String description,String category,String created,long bytes,boolean hasThumbnail,String error) {public boolean valid(){return error.isEmpty();}}
    public record Library(long revision,String status,boolean busy,int page,int total,List<ExportEntry> exports) {}
    public record Checkpoint(String label,String versionId,int eventIndex,long elapsedTicks) {}
    public record Timeline(String name,List<Checkpoint> checkpoints) {}
    public record Operation(String id,String actorId,String action,String phase,String status) {}
    private record Advanced(History history,TestState test,Prefabs prefabs,Library library,Timeline timeline,Operation operation) {}
    private record Binding(long authentication,OnlineConnectionClient.ExpectedHost host,MinecraftServer local) {}
    private record View(Binding binding,long revision,String worldId,String worldName,String dimension,TestingWorlds.Template template,
            TestingSettings settings,String status,boolean busy,boolean recoveryBlocked,
            List<TestingScene.ObjectView> scene,List<TestingServer.ZoneView> zones,List<TestingScenarios.TriggerView> triggers,
            List<BlockPos> protectedPreview,List<String> scenarioNames,List<String> spawnerNames,List<String> spawnableTypes,
            String scenarioStatus,JsonObject selectedDocument,Generator generator,Map<String,Boolean> capabilities,
            Map<String,String> reasons,Map<String,String> values,Advanced advanced) {}
    private static final Gson JSON=new Gson();
    private static final AtomicLong REQUESTS=new AtomicLong();
    private static final ThreadPoolExecutor PARSER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
            task->{Thread thread=new Thread(task,"DarkWinter-online-lab-view");thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static volatile View current;
    private static volatile BiConsumer<String,String> action;
    private static volatile String notice="",parseProblem="";
    public static String diagnostic(){return parseProblem;}
    private static String documentName="";
    private static Consumer<JsonObject> documentReceiver;
    private static long documentRequested;
    private OnlineTestingView() {}

    public static void installAction(BiConsumer<String,String> callback){action=callback;}
    /** Called exclusively by the validated native OnlineConnectionClient state consumer. */
    public static void acceptPacket(JsonObject rootPacket){
        var host=OnlineConnectionClient.authenticatedHost();if(host==null)return;
        long generation=OnlineConnectionClient.authenticationGeneration();
        JsonObject actual=child(rootPacket,"host");
        if(!host.hostId().equals(string(actual,"hostId",""))||!host.sessionId().toString().equals(string(actual,"sessionId",""))
                ||host.epoch()!=number(actual,"epoch",-1))return;
        prepare(new Binding(generation,host,null),child(rootPacket,"lab"));
    }
    /** A published local host uses the same actor policy instead of the legacy offline-owner action. */
    public static void acceptLocal(MinecraftServer server,JsonObject lab){
        Minecraft client=Minecraft.getInstance();OnlineGameHost host=OnlineGameHost.get(server);
        if(server==null||client.getSingleplayerServer()!=server||host==null)return;
        String id=host.localIdentityId();
        if(!id.equals(host.ownerId())&&!host.access().trustedLead(id))return;
        prepare(new Binding(-1,new OnlineConnectionClient.ExpectedHost(host.localIdentityId(),host.sessionId(),host.epoch(),host.compatibilityDigest()),server),lab);
    }
    private static void prepare(Binding binding,JsonObject source){
        long request=REQUESTS.incrementAndGet();JsonObject immutable=source.deepCopy();
        int minY=Minecraft.getInstance().level==null?-64:Minecraft.getInstance().level.getMinY();
        int maxY=Minecraft.getInstance().level==null?320:Minecraft.getInstance().level.getMaxY();
        PARSER.execute(()->{
            try{
                View view=parse(binding,immutable,minY,maxY);
                Minecraft.getInstance().execute(()->{
                    if(request!=REQUESTS.get()||!valid(binding))return;
                    current=view;notice="";parseProblem="";deliverDocument();
                });
            }catch(RuntimeException invalid){
                String code=invalid.getClass().getSimpleName();
                for(StackTraceElement frame:invalid.getStackTrace())if(frame.getClassName().equals(OnlineTestingView.class.getName())){code+="@"+frame.getMethodName()+":"+frame.getLineNumber();break;}
                String safeCode=code;
                Minecraft.getInstance().execute(()->{if(request==REQUESTS.get()&&valid(binding)){current=null;parseProblem=safeCode;notice="Данные лаборатории Online отклонены.";}}); 
            }
        });
    }
    private static boolean valid(Binding binding){
        try{
        Minecraft client=Minecraft.getInstance();if(client.level==null||client.player==null)return false;
        if(binding.local()!=null){
            OnlineGameHost host=OnlineGameHost.get(binding.local());
            return client.getSingleplayerServer()==binding.local()&&host!=null&&host.sessionId().equals(binding.host().sessionId())&&host.epoch()==binding.host().epoch()
                    &&(host.localIdentityId().equals(host.ownerId())||host.access().trustedLead(host.localIdentityId()));
        }
        return OnlineConnectionClient.authenticated()&&binding.authentication()==OnlineConnectionClient.authenticationGeneration()
                &&binding.host().equals(OnlineConnectionClient.authenticatedHost());
        }catch(RuntimeException closing){return false;}
    }
    private static View view(){View value=current;return value!=null&&valid(value.binding())&&value.dimension().equals(Minecraft.getInstance().level.dimension().identifier().toString())?value:null;}
    private static OnlineConnectionClient.ExpectedHost pendingHost(){
        Minecraft client=Minecraft.getInstance();
        return client.level!=null&&client.player!=null?OnlineConnectionClient.authenticatedHost():null;
    }
    private static boolean pending(){return pendingHost()!=null;}
    private static TestingSettings neutral(){
        TestingSettings settings=new TestingSettings();settings.mode="TEST_REVIEW";return settings;
    }
    public static boolean active(){return view()!=null||pending();}
    public static boolean remote(){View value=view();return value!=null?value.binding().local()==null:pending();}
    public static String contextKey(){
        View value=view();if(value!=null)return value.binding().local()==null?value.binding().host().sessionId()+"|"+value.binding().authentication():"local-"+value.binding().host().sessionId()+"|"+value.binding().host().epoch();
        var host=pendingHost();return host==null?"":"pending-"+host.sessionId()+"|"+OnlineConnectionClient.authenticationGeneration();
    }
    public static void tick(){
        View value=current;if(value!=null&&!valid(value.binding())){current=null;REQUESTS.incrementAndGet();PARSER.getQueue().clear();documentReceiver=null;documentName="";}
        if(documentReceiver!=null&&System.nanoTime()-documentRequested>10_000_000_000L){documentReceiver=null;notice="Документ сценария не получен от ведущего.";}
    }
    public static long revision(){View value=view();return value==null?-1:31*value.revision()+value.advanced().hashCode();}
    public static String worldId(){View value=view();if(value!=null)return value.worldId();var host=pendingHost();return host==null?"":"pending-"+host.sessionId();}
    public static String worldName(){View value=view();return value==null?pending()?"Подключённый остров":"":value.worldName();}
    public static TestingWorlds.Template template(){View value=view();return value==null?null:value.template();}
    public static TestingSettings settings(){View value=view();return value==null?pending()?neutral():null:copy(value.settings());}
    private static TestingSettings copy(TestingSettings value){
        TestingSettings target=new TestingSettings();target.schema=value.schema;target.modules.putAll(value.modules);
        target.mode=value.mode;target.biome=value.biome;target.weather=value.weather;target.brush=value.brush;
        target.radius=value.radius;target.strength=value.strength;target.density=value.density;target.brushShape=value.brushShape;
        target.grassSpecies=value.grassSpecies;target.bushSpecies=value.bushSpecies;target.treeSpecies=value.treeSpecies;
        target.windSpeed=value.windSpeed;target.windDirection=value.windDirection;target.temperature=value.temperature;target.flySpeed=value.flySpeed;
        target.filmStrength=value.filmStrength;target.hazeDensity=value.hazeDensity;target.shadowStrength=value.shadowStrength;
        target.staminaCapacity=value.staminaCapacity;target.staminaSprintDrain=value.staminaSprintDrain;target.staminaWalkRegen=value.staminaWalkRegen;
        target.staminaIdleRegen=value.staminaIdleRegen;target.staminaJumpCost=value.staminaJumpCost;target.staminaSprintJumpCost=value.staminaSprintJumpCost;
        target.motionWalkSpeed=value.motionWalkSpeed;target.motionCameraWeight=value.motionCameraWeight;target.motionTurnWeight=value.motionTurnWeight;
        target.diagnostics=value.diagnostics;return target;
    }
    public static JsonObject settingsJson(){TestingSettings value=settings();return value==null?new JsonObject():JSON.toJsonTree(value).getAsJsonObject();}
    public static boolean enabled(String module){View value=view();return value!=null&&value.settings().enabled(module);}
    public static String value(String key){
        View value=view();if(value==null)return pending()?neutral().value(key):"";
        return value.values().containsKey(key)?value.values().get(key):value.settings().value(key);
    }
    public static String status(){View value=view();return !notice.isEmpty()?notice:value==null?pending()?"Личность подтверждена; ожидаем параметры острова.":"Лаборатория Online недоступна":value.status();}
    public static boolean busy(){View value=view();return value==null?pending():value.busy();}
    public static boolean recoveryBlocked(){View value=view();return value!=null&&value.recoveryBlocked();}
    public static List<TestingScene.ObjectView> scene(){View value=view();return value==null?List.of():value.scene();}
    public static List<TestingServer.ZoneView> zones(){View value=view();return value==null?List.of():value.zones();}
    public static List<TestingScenarios.TriggerView> triggers(){View value=view();return value==null?List.of():value.triggers();}
    public static List<BlockPos> protectedPreview(){View value=view();return value==null?List.of():value.protectedPreview();}
    public static List<String> scenarioNames(){View value=view();return value==null?List.of():value.scenarioNames();}
    public static List<String> spawnerNames(){View value=view();return value==null?List.of():value.spawnerNames();}
    public static List<String> spawnableTypes(){View value=view();return value==null?List.of():value.spawnableTypes();}
    public static String scenarioStatus(){View value=view();return value==null?"":value.scenarioStatus();}
    public static Generator generator(){View value=view();return value==null?new Generator(false,96,"ROUND",1):value.generator();}
    private static Advanced advanced(){View value=view();return value==null?EMPTY_ADVANCED:value.advanced();}
    public static History history(){return advanced().history();}
    public static TestState test(){return advanced().test();}
    public static Prefabs prefabs(){return advanced().prefabs();}
    public static Library library(){return advanced().library();}
    public static Timeline timeline(){return advanced().timeline();}
    public static Operation operation(){return advanced().operation();}
    public static boolean allowed(String command){View value=view();return value!=null&&Boolean.TRUE.equals(value.capabilities().get(command))&&action!=null;}
    public static String reason(String command){
        View value=view();if(value==null)return pending()?"Личность подтверждена; ожидаем параметры острова.":"Нет подтверждённого игрового соединения.";
        return value.reasons().getOrDefault(command,"Это действие недоступно вашей роли или в текущем состоянии острова.");
    }
    public static boolean action(String command,String argument){
        View value=view();BiConsumer<String,String> callback=action;
        if(value==null||callback==null||!allowed(command)){notice=reason(command);return false;}
        try{callback.accept(command,argument==null?"":argument);return true;}
        catch(RuntimeException rejected){notice="Команда лаборатории не отправлена. Проверьте игровое соединение.";return false;}
    }
    public static void requestDocument(String name,Consumer<JsonObject> receiver){
        View value=view();if(value==null)return;
        JsonObject selected=value.selectedDocument();
        if(name.equals(string(selected,"name",""))&&selected.has("events")){receiver.accept(selected.deepCopy());return;}
        documentName=name;documentReceiver=receiver;documentRequested=System.nanoTime();
        if(!action("scenario_document",name))documentReceiver=null;
    }
    private static void deliverDocument(){
        View value=view();if(value==null||documentReceiver==null)return;JsonObject selected=value.selectedDocument();
        if(documentName.equals(string(selected,"name",""))&&selected.has("events")){
            Consumer<JsonObject> receiver=documentReceiver;documentReceiver=null;receiver.accept(selected.deepCopy());
        }
    }
    private static View parse(Binding binding,JsonObject root,int minY,int maxY){
        if(number(root,"schema",-1)!=1)throw new IllegalArgumentException("Lab schema");
        JsonObject world=child(root,"world");String id=text(world,"id",128),name=text(world,"name",256);
        TestingWorlds.Template template=TestingWorlds.Template.valueOf(text(world,"template",24));
        String dimension=text(world,"dimension",128);
        JsonObject serializedSettings=child(root,"settings");if(serializedSettings.size()>48)throw new IllegalArgumentException("Settings limit");
        TestingSettings settings=JSON.fromJson(serializedSettings,TestingSettings.class);settings.validate();
        if(!Set.of("EDIT","CHARACTER","TEST_RUNNING","TEST_PAUSED","TEST_REVIEW").contains(settings.mode))throw new IllegalArgumentException("Lab mode");
        List<TestingScene.ObjectView> scene=new ArrayList<>();Set<String> sceneIds=new HashSet<>();
        for(JsonElement element:array(root,"scene",8192)){
            JsonObject node=element.getAsJsonObject();String nodeId=text(node,"id",128);
            if(!sceneIds.add(nodeId))throw new IllegalArgumentException("Duplicate scene");
            BlockPos a=position(node.get("min"),minY,maxY),b=position(node.get("max"),minY,maxY);bounds(a,b);
            scene.add(new TestingScene.ObjectView(nodeId,string(node,"parentId",""),text(node,"name",256),text(node,"kind",32),
                    integer(node,"blockCount",0,2_000_000),integer(node,"npcCount",0,4096),bool(node,"loaded"),a,b));
        }
        List<TestingServer.ZoneView> zones=new ArrayList<>();
        for(JsonElement element:array(root,"zones",512)){
            JsonObject zone=element.getAsJsonObject();BlockPos a=minimum(zone,minY,maxY),b=maximum(zone,minY,maxY);bounds(a,b);
            zones.add(new TestingServer.ZoneView(text(zone,"name",256),a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),bool(zone,"natural"),bool(zone,"objects")));
        }
        JsonObject scenarios=child(root,"scenarios");List<TestingScenarios.TriggerView> triggers=new ArrayList<>();
        for(JsonElement element:array(scenarios,"triggers",1024)){
            JsonObject zone=element.getAsJsonObject();BlockPos a=minimum(zone,minY,maxY),b=maximum(zone,minY,maxY);bounds(a,b);
            triggers.add(new TestingScenarios.TriggerView(text(zone,"name",256),a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ()));
        }
        List<BlockPos> protectedPreview=new ArrayList<>();for(JsonElement element:array(root,"protectedPreview",2401))protectedPreview.add(position(element,minY,maxY));
        JsonObject generator=child(root,"generator");boolean island=bool(generator,"island");
        Generator gen=new Generator(island,island?integer(generator,"radius",32,128):96,string(generator,"shape","ROUND"),decimal(generator,"hills",1,0,2));
        if(!Set.of("ROUND","OVAL","CRESCENT").contains(gen.shape()))throw new IllegalArgumentException("Island shape");
        Map<String,Boolean> capabilities=new HashMap<>();for(var entry:child(root,"capabilities").entrySet()){
            if(capabilities.size()>=256||!entry.getKey().matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Capabilities");
            capabilities.put(entry.getKey(),entry.getValue().getAsBoolean());
        }
        Map<String,String> reasons=new HashMap<>();for(var entry:child(root,"reasons").entrySet()){
            String reason=entry.getValue().getAsString();if(reasons.size()>=256||reason.length()>512)throw new IllegalArgumentException("Reasons");reasons.put(entry.getKey(),reason);
        }
        Map<String,String> values=new HashMap<>();JsonObject selection=child(root,"selection");
        values.put("scene_selected",string(selection,"sceneId",""));values.put("selection",string(selection,"description",""));
        for(String category:List.of("grass","bushes","trees"))values.put("vegetation_"+category,Boolean.toString(bool(child(root,"vegetation"),category)));
        JsonObject document=child(scenarios,"selectedDocument").deepCopy();
        if(document.has("document")&&document.get("document").isJsonObject())document=document.getAsJsonObject("document").deepCopy();
        if(document.toString().length()>1048576)throw new IllegalArgumentException("Scenario document limit");
        return new View(binding,number(root,"revision",0),id,name,dimension,template,settings,bounded(root,"status",2048),bool(root,"busy"),bool(root,"recoveryBlocked"),
                List.copyOf(scene),List.copyOf(zones),List.copyOf(triggers),List.copyOf(protectedPreview),
                strings(scenarios,"names",512),strings(scenarios,"spawners",1024),strings(scenarios,"spawnableTypes",512),
                bounded(scenarios,"status",2048),document,gen,Map.copyOf(capabilities),Map.copyOf(reasons),Map.copyOf(values),parseAdvanced(root));
    }
    private static final Advanced EMPTY_ADVANCED=parseAdvanced(new JsonObject());
    /** Optional host-owned catalogs: each packet contains one bounded page, never guest file paths. */
    private static Advanced parseAdvanced(JsonObject root){
        JsonObject h=child(root,"history"),t=child(root,"test"),p=child(root,"prefabs"),l=child(root,"library"),r=child(h,"transferReview"),timeline=child(root,"scenarioTimeline"),op=child(root,"operation");
        List<VersionEntry> versions=new ArrayList<>();
        for(JsonElement element:array(h,"versions",12)){
            JsonObject entry=element.getAsJsonObject();String id=bounded(entry,"id",36);canonicalUuid(id);
            versions.add(new VersionEntry(id,text(entry,"name",256),bounded(entry,"description",1024),category(entry),nonnegative(entry,"bytes"),bool(entry,"hasThumbnail"),bool(entry,"protected")));
        }
        List<PrefabEntry> prefabs=new ArrayList<>();
        for(JsonElement element:array(p,"entries",24)){
            JsonObject entry=element.getAsJsonObject();String id=bounded(entry,"id",36);if(!id.equals("legacy"))canonicalUuid(id);
            prefabs.add(new PrefabEntry(id,text(entry,"name",256)));
        }
        List<ExportEntry> exports=new ArrayList<>();
        for(JsonElement element:array(l,"exports",12)){
            JsonObject entry=element.getAsJsonObject();
            exports.add(new ExportEntry(text(entry,"filename",512),bounded(entry,"name",256),bounded(entry,"description",1024),category(entry),bounded(entry,"created",64),
                    nonnegative(entry,"bytes"),bool(entry,"hasThumbnail"),bounded(entry,"error",2048)));
        }
        List<Checkpoint> points=new ArrayList<>();
        for(String row:boundedStrings(timeline,"checkpoints",32,512)){
            String[] fields=row.split("\\|",4);if(fields.length!=4)throw new IllegalArgumentException("Checkpoint cursor");
            String version=fields[1].strip();canonicalUuid(version);int index=Integer.parseInt(fields[2].strip());long elapsed=Long.parseLong(fields[3].strip());
            if(index<0||index>256||elapsed<0||elapsed>72000)throw new IllegalArgumentException("Checkpoint bounds");
            points.add(new Checkpoint(fields[0].strip(),version,index,elapsed));
        }
        History history=new History(nonnegative(h,"revision"),bounded(h,"status",2048),bool(h,"busy"),bounded(h,"selectedId",36),bounded(h,"storageStatus",2048),
                bool(h,"preview"),page(h),total(h),List.copyOf(versions),new TransferReview(bool(r,"active"),bool(r,"ready"),bool(r,"canApply"),boundedStrings(r,"lines",128,4096)));
        TestState test=new TestState(bool(t,"running"),bool(t,"paused"),bool(t,"reviewReady"),boundedStrings(t,"reviewLines",128,4096),bool(t,"keepObjects"),bool(t,"keepBuildings"),bool(t,"keepSettings"));
        return new Advanced(history,test,new Prefabs(nonnegative(p,"revision"),bounded(p,"status",2048),bool(p,"busy"),bounded(p,"selectedId",36),page(p),total(p),List.copyOf(prefabs)),
                new Library(nonnegative(l,"revision"),bounded(l,"status",2048),bool(l,"busy"),page(l),total(l),List.copyOf(exports)),new Timeline(bounded(timeline,"name",256),List.copyOf(points)),
                new Operation(bounded(op,"id",36),bounded(op,"actorId",64),bounded(op,"action",64),bounded(op,"phase",64),bounded(op,"status",2048)));
    }
    private static void canonicalUuid(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Canonical UUID");}
    private static long nonnegative(JsonObject object,String key){long value=number(object,key,0);if(value<0)throw new IllegalArgumentException("Negative catalog value");return value;}
    private static int page(JsonObject object){long value=nonnegative(object,"page");if(value>4095)throw new IllegalArgumentException("Catalog page");return (int)value;}
    private static int total(JsonObject object){long value=nonnegative(object,"total");if(value>1_000_000)throw new IllegalArgumentException("Catalog total");return (int)value;}
    private static String category(JsonObject object){String value=string(object,"category","general");if(!Set.of("general","terrain","survival","construction","scenarios","showcase").contains(value))throw new IllegalArgumentException("Catalog category");return value;}
    private static List<String> boundedStrings(JsonObject parent,String key,int count,int length){
        List<String> result=new ArrayList<>();for(JsonElement element:array(parent,key,count)){String value=element.getAsString();if(value.length()>length)throw new IllegalArgumentException("Catalog text");result.add(value);}return List.copyOf(result);
    }
    private static JsonObject child(JsonObject parent,String key){return parent.has(key)&&parent.get(key).isJsonObject()?parent.getAsJsonObject(key):new JsonObject();}
    private static String string(JsonObject parent,String key,String fallback){return parent.has(key)&&parent.get(key).isJsonPrimitive()?parent.get(key).getAsString():fallback;}
    private static String bounded(JsonObject parent,String key,int max){String value=string(parent,key,"");if(value.length()>max)throw new IllegalArgumentException("Text limit");return value;}
    private static String text(JsonObject parent,String key,int max){String value=string(parent,key,"");if(value.isBlank()||value.length()>max)throw new IllegalArgumentException("Text limit");return value;}
    private static long number(JsonObject parent,String key,long fallback){return parent.has(key)?parent.get(key).getAsBigDecimal().longValueExact():fallback;}
    private static int integer(JsonObject parent,String key,int min,int max){long value=number(parent,key,Long.MIN_VALUE);if(value<min||value>max)throw new IllegalArgumentException("Integer limit");return (int)value;}
    private static double decimal(JsonObject parent,String key,double fallback,double min,double max){double value=parent.has(key)?parent.get(key).getAsDouble():fallback;if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Number limit");return value;}
    private static boolean bool(JsonObject parent,String key){return parent.has(key)&&parent.get(key).getAsBoolean();}
    private static JsonArray array(JsonObject parent,String key,int max){JsonArray values=parent.has(key)?parent.getAsJsonArray(key):new JsonArray();if(values.size()>max)throw new IllegalArgumentException("Collection limit");return values;}
    private static List<String> strings(JsonObject parent,String key,int max){List<String> values=new ArrayList<>();for(JsonElement element:array(parent,key,max)){String value=element.getAsString();if(value.length()>256)throw new IllegalArgumentException("String limit");values.add(value);}return List.copyOf(values);}
    private static BlockPos position(JsonElement element,int minY,int maxY){
        if(element==null)throw new IllegalArgumentException("Missing position");int x,y,z;
        if(element.isJsonArray()){JsonArray a=element.getAsJsonArray();if(a.size()!=3)throw new IllegalArgumentException("Position");x=a.get(0).getAsBigDecimal().intValueExact();y=a.get(1).getAsBigDecimal().intValueExact();z=a.get(2).getAsBigDecimal().intValueExact();}
        else{JsonObject a=element.getAsJsonObject();x=integer(a,"x",-29999984,29999984);y=integer(a,"y",minY,maxY);z=integer(a,"z",-29999984,29999984);}
        if(Math.abs((long)x)>29999984||Math.abs((long)z)>29999984||y<minY||y>maxY)throw new IllegalArgumentException("Position bounds");return new BlockPos(x,y,z);
    }
    private static BlockPos minimum(JsonObject value,int minY,int maxY){if(value.has("min"))return position(value.get("min"),minY,maxY);return new BlockPos(integer(value,"x0",-29999984,29999984),integer(value,"y0",minY,maxY),integer(value,"z0",-29999984,29999984));}
    private static BlockPos maximum(JsonObject value,int minY,int maxY){if(value.has("max"))return position(value.get("max"),minY,maxY);return new BlockPos(integer(value,"x1",-29999984,29999984),integer(value,"y1",minY,maxY),integer(value,"z1",-29999984,29999984));}
    private static void bounds(BlockPos a,BlockPos b){if(a.getX()>b.getX()||a.getY()>b.getY()||a.getZ()>b.getZ())throw new IllegalArgumentException("Reversed bounds");}
}
