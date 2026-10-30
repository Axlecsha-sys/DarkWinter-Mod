// Bounded repeatable events and real named zone/spawn devices, isolated to marked local saves.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded repeatable events and real named zone/spawn devices, isolated to marked local saves. */
public final class TestingScenarios {
    public record TriggerView(String name,int x0,int y0,int z0,int x1,int y1,int z1) {
        boolean contains(ServerPlayer p) {
            return p.getX()>=x0&&p.getX()<x1+1d&&p.getY()>=y0&&p.getY()<y1+1d&&p.getZ()>=z0&&p.getZ()<z1+1d;
        }
    }
    private record Spawner(String name,String type,double x,double y,double z,int count,boolean noAI,boolean invulnerable) {}
    private record Devices(int schema,List<TriggerView> triggers,List<Spawner> spawners,Map<String,List<UUID>> generated) {}
    private record Event(String when,String action,String value) {}
    private record Measurements(int intervalTicks,List<String> metrics) {}
    private record Checkpoint(String label,String versionId,int eventIndex,long elapsedTicks,List<Integer> firedIndices,Map<String,Boolean> inside) {}
    private record Replay(String baselineVersionId,String rollbackPolicy,List<Checkpoint> checkpoints) {}
    private record Scenario(int schema,String name,TestingSettings profile,List<Event> events,Devices devices,Replay replay,Measurements measurements) {
        Scenario(int schema,String name,TestingSettings profile,List<Event> events,Devices devices){this(schema,name,profile,events,devices,null,null);}
    }
    private static final class State {
        Devices devices;
        final Map<String,Scenario> scenarios=new LinkedHashMap<>();
        volatile String message="Сценарий не запущен";
        volatile String completedRollbackPolicy="review";
        volatile boolean discardRequested;
        volatile List<TriggerView> triggerViews=List.of();
        volatile List<String> spawnerNames=List.of(),scenarioNames=List.of();
        State(Devices d){devices=d;refreshViews();}
        void refreshViews(){
            triggerViews=List.copyOf(devices.triggers());
            LinkedHashSet<String> names=new LinkedHashSet<>();for(Spawner s:devices.spawners())names.add(s.name());
            names.addAll(devices.generated().keySet());spawnerNames=List.copyOf(names);
        }
    }
    private static final class Recording {
        final String name;final long started;final TestingSettings profile;final Path log;
        Devices devices;final List<Event> events=new ArrayList<>();volatile int count;volatile String failure="";
        Recording(String name,long started,TestingSettings profile,Devices devices,Path log){
            this.name=name;this.started=started;this.profile=profile;this.devices=devices;this.log=log;
        }
    }
    private static final class Run {
        final Scenario scenario;final UUID player;final long started;final TestingActors.Captured actor=TestingActors.capture();
        final BitSet fired=new BitSet();
        final Map<String,Boolean> inside=new HashMap<>();
        int firedCount;long lastMeasurement=-1;
        Run(Scenario s,ServerPlayer p,long tick){
            scenario=s;player=p.getUUID();started=tick;
            for(TriggerView t:s.devices().triggers())inside.put(t.name(),t.contains(p));
        }
    }
    private static final Map<MinecraftServer,State> STATES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer,Run> RUNS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer,Recording> RECORDINGS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> ACTIONS=Set.of("scenario_save","scenario_run","scenario_stop","scenario_export","scenario_import",
            "trigger_add","trigger_remove","spawner_add","spawner_remove","spawner_activate","spawner_clear",
            "scenario_record_start","scenario_record_stop","scenario_record_play","scenario_metadata","report");
    private static final Set<String> ENTITY_TYPES=Set.of("minecraft:villager","minecraft:wandering_trader","minecraft:wolf","minecraft:fox",
            "minecraft:cow","minecraft:pig","minecraft:sheep","minecraft:chicken","minecraft:rabbit","minecraft:goat",
            "minecraft:iron_golem","minecraft:snow_golem","minecraft:zombie","minecraft:husk","minecraft:skeleton","minecraft:stray","minecraft:spider");
    private TestingScenarios(){}
    public static boolean handles(String action){return ACTIONS.contains(action);}
    public static List<String> spawnableTypes(){return ENTITY_TYPES.stream().sorted().toList();}
    public static String status(MinecraftServer server){State s=STATES.get(server);Recording r=RECORDINGS.get(server);return (r==null?"":"Запись "+r.name+": "+r.count+" событий. ")+(s==null?"Устройства ещё не загружены":s.message);}
    public static boolean recording(MinecraftServer server){return RECORDINGS.containsKey(server);}
    public static List<TriggerView> triggerViews(MinecraftServer server){State s=STATES.get(server);return s==null?List.of():s.triggerViews;}
    public static List<String> spawnerNames(MinecraftServer server){State s=STATES.get(server);return s==null?List.of():s.spawnerNames;}
    public static List<String> names(MinecraftServer server){State s=STATES.get(server);return s==null?List.of():s.scenarioNames;}
    public static boolean running(MinecraftServer server){return RUNS.containsKey(server);}
    private static Path directory(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing");}
    private static Path exports(){return FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/scenarios");}
    private static ServerPlayer owner(MinecraftServer server) throws IOException {
        if(server==null||!server.isSameThread()||!TestingServer.isTesting(server)||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server)))
            throw new IOException("Устройства доступны только на потоке одиночного тестового сервера");
        return server.getPlayerList().getPlayers().stream().filter(p->server.isSingleplayerOwner(p.nameAndId())
                &&p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)).findFirst()
                .orElseThrow(()->new IOException("Владелец тестового мира отсутствует в Overworld"));
    }
    private static void requireDocumentContext(MinecraftServer server) throws IOException {
        if(server==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))
                ||!TestingServer.isTesting(server)||TestingServer.context(server)==null)
            throw new IOException("Определения доступны только на потоке отмеченного локального тестового сервера");
    }
    private static Path documentsFile(MinecraftServer server){return directory(server).resolve("scenario-documents.json");}
    private record Documents(Devices devices,Map<String,Scenario> scenarios) {}
    private static State state(MinecraftServer server) throws IOException {
        State old=STATES.get(server);if(old!=null)return old;
        State fresh;
        if(Files.isRegularFile(documentsFile(server))){
            if(Files.size(documentsFile(server))>8*1024*1024)throw new IOException("Определения сценариев превышают 8 MiB");
            JsonObject root=JsonParser.parseString(Files.readString(documentsFile(server))).getAsJsonObject();
            Documents docs=parseDocuments(server,root,false);
            fresh=new State(docs.devices());fresh.scenarios.putAll(docs.scenarios());
        }else{
            Path file=directory(server).resolve("devices.json");Devices devices=emptyDevices();
            if(Files.isRegularFile(file)){
                if(Files.size(file)>512*1024)throw new IOException("Файл устройств слишком большой");
                devices=GSON.fromJson(Files.readString(file),Devices.class);validate(devices,server);
            }
            fresh=new State(devices);Path folder=directory(server).resolve("scenarios");
            if(Files.isDirectory(folder))try(var files=Files.list(folder)){
                for(Path scenario:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList()){
                    if(fresh.scenarios.size()>=128)throw new IOException("Лимит: 128 сценариев");
                    Scenario doc=readScenarioFile(server,scenario,fresh.devices);
                    if(fresh.scenarios.putIfAbsent(doc.name(),doc)!=null)throw new IOException("Повтор имени сценария");
                }
            }
        }
        refreshScenarioNames(server,fresh);STATES.put(server,fresh);return fresh;
    }
    /** Complete portable definitions. Runtime-generated entity UUIDs are deliberately absent. */
    public static JsonObject exportDocuments(MinecraftServer server) throws IOException {
        requireDocumentContext(server);State state=state(server);return documents(snapshot(state.devices),state.scenarios);
    }
    public static void validateDocuments(MinecraftServer server,JsonObject payload) throws IOException {
        requireDocumentContext(server);parseDocuments(server,payload,true);
    }
    /** One authoritative atomic file publishes the complete definition set; callers own the outer geometry WAL. */
    public static void applyDocuments(MinecraftServer server,JsonObject payload) throws IOException {
        requireDocumentContext(server);Documents docs=parseDocuments(server,payload,true);State previous=state(server);
        Devices devices=new Devices(3,docs.devices().triggers(),docs.devices().spawners(),previous.devices.generated());
        State next=new State(devices);next.scenarios.putAll(docs.scenarios());
        persist(server,next);
        RUNS.remove(server);RECORDINGS.remove(server);STATES.put(server,next);
        next.message="Определения сценариев и устройств восстановлены";refreshScenarioNames(server,next);
    }
    private static JsonObject documents(Devices devices,Map<String,Scenario> scenarios){
        JsonObject root=new JsonObject();root.addProperty("schema",1);root.add("devices",GSON.toJsonTree(devices));
        JsonArray docs=new JsonArray();scenarios.values().stream().sorted(Comparator.comparing(Scenario::name)).forEach(doc->docs.add(GSON.toJsonTree(doc)));
        root.add("scenarios",docs);return root;
    }
    private static Documents parseDocuments(MinecraftServer server,JsonObject payload,boolean portable) throws IOException {
        try{
            if(payload==null||payload.toString().length()>8*1024*1024||payload.get("schema").getAsInt()!=1
                    ||!payload.has("devices")||!payload.has("scenarios"))throw new IOException("Неверный пакет определений");
            Devices devices=GSON.fromJson(payload.get("devices"),Devices.class);validate(devices,server);
            if(portable&&!devices.generated().isEmpty())throw new IOException("Пакет определений не может содержать UUID созданных NPC");
            JsonArray array=payload.getAsJsonArray("scenarios");if(array.size()>128)throw new IOException("Лимит: 128 сценариев");
            Map<String,Scenario> scenarios=new LinkedHashMap<>();
            for(JsonElement element:array){
                Scenario scenario=parseScenario(server,element.getAsJsonObject(),devices);
                if(!scenario.devices().generated().isEmpty())throw new IOException("Сценарий содержит runtime UUID");
                if(scenarios.putIfAbsent(scenario.name(),scenario)!=null)throw new IOException("Повтор имени сценария: "+scenario.name());
            }
            return new Documents(devices,scenarios);
        }catch(RuntimeException error){throw new IOException("Определения отклонены до применения: "+error.getMessage(),error);}
    }
    private static void persist(MinecraftServer server,State state) throws IOException {
        JsonObject aggregate=documents(state.devices,state.scenarios);
        if(aggregate.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8*1024*1024)
            throw new IOException("Определения сценариев превышают 8 MiB");
        TestingEdits.atomic(documentsFile(server),aggregate);
        // Legacy mirrors keep exported tools/readers compatible. The aggregate above is authoritative after migration.
        try{
            TestingEdits.atomic(directory(server).resolve("devices.json"),GSON.toJsonTree(state.devices));
            for(Scenario doc:state.scenarios.values())TestingEdits.atomic(directory(server).resolve("scenarios").resolve(doc.name()+".json"),GSON.toJsonTree(doc));
        }catch(IOException mirror){org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").warn("Definition mirror could not be updated",mirror);}
    }
    private static void saveScenario(MinecraftServer server,State state,Scenario doc) throws IOException {
        validateScenario(doc,server);Map<String,Scenario> before=new LinkedHashMap<>(state.scenarios);
        state.scenarios.put(doc.name(),doc);
        try{persist(server,state);}catch(IOException failure){state.scenarios.clear();state.scenarios.putAll(before);throw failure;}
        refreshScenarioNames(server,state);
    }
    /** Relocates definitions only; it never creates NPCs or applies settings/world state. */
    public static JsonObject relocateDocuments(MinecraftServer server,JsonObject payload,BlockPos source,BlockPos target,int turns) throws IOException {
        return relocateDocuments(server,payload,source,target,turns,"");
    }
    public static JsonObject relocateDocuments(MinecraftServer server,JsonObject payload,BlockPos source,BlockPos target,int turns,String prefix) throws IOException {
        requireDocumentContext(server);Documents docs=parseDocuments(server,payload,true);int quarter=Math.floorMod(turns,4);
        Map<String,Scenario> scenarios=new LinkedHashMap<>();
        Devices devices=relocateDevices(docs.devices(),source,target,quarter,prefix);
        for(Scenario doc:docs.scenarios().values()){
            if(hasWorldReferences(doc)){
                if(quarter!=0||!source.equals(target)||prefix!=null&&!prefix.isBlank())
                    throw new IOException("Сценарий привязан к полному состоянию мира и не переносится пространственным Prefab. Используйте пакет .dwisland с зависимостями.");
                TestingScenarioDependencies.validateOwned(payload,server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            }
            List<Event> events=doc.events().stream().map(event->new Event(
                    event.when().startsWith("enter@")?"enter@"+prefixed(prefix,event.when().substring(6)):event.when(),
                    event.action(),event.action().equals("spawn")?prefixed(prefix,event.value()):event.value())).toList();
            Scenario moved=new Scenario(doc.schema(),prefixed(prefix,doc.name()),doc.profile(),events,
                    relocateDevices(doc.devices(),source,target,quarter,prefix),doc.replay(),doc.measurements());
            scenarios.put(moved.name(),moved);
        }
        JsonObject result=documents(devices,scenarios);parseDocuments(server,result,true);return result;
    }
    private static String prefixed(String prefix,String name){
        if(prefix==null||prefix.isBlank())return name;
        String merged=prefix+name;if(merged.length()<=80)return merged;
        return merged.substring(0,70)+"_"+Integer.toUnsignedString(name.hashCode(),36);
    }
    private static Devices relocateDevices(Devices devices,BlockPos source,BlockPos target,int turns,String prefix){
        List<TriggerView> triggers=new ArrayList<>();
        for(TriggerView trigger:devices.triggers()){
            BlockPos a=relocate(new BlockPos(trigger.x0(),trigger.y0(),trigger.z0()),source,target,turns);
            BlockPos b=relocate(new BlockPos(trigger.x1(),trigger.y1(),trigger.z1()),source,target,turns);
            triggers.add(new TriggerView(prefixed(prefix,trigger.name()),Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()),
                    Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ())));
        }
        List<Spawner> spawners=new ArrayList<>();
        for(Spawner device:devices.spawners()){
            double x=device.x()-source.getX(),z=device.z()-source.getZ(),rx=x,rz=z;
            switch(turns){case 1->{rx=1-z;rz=x;}case 2->{rx=1-x;rz=1-z;}case 3->{rx=z;rz=1-x;}default->{}}
            spawners.add(new Spawner(prefixed(prefix,device.name()),device.type(),target.getX()+rx,target.getY()+device.y()-source.getY(),
                    target.getZ()+rz,device.count(),device.noAI(),device.invulnerable()));
        }
        return new Devices(3,List.copyOf(triggers),List.copyOf(spawners),Map.of());
    }
    private static BlockPos relocate(BlockPos pos,BlockPos source,BlockPos target,int turns){
        int x=pos.getX()-source.getX(),z=pos.getZ()-source.getZ(),rx=x,rz=z;
        switch(turns){case 1->{rx=-z;rz=x;}case 2->{rx=-x;rz=-z;}case 3->{rx=z;rz=-x;}default->{}}
        return target.offset(rx,pos.getY()-source.getY(),rz);
    }
    private static Devices emptyDevices(){return new Devices(3,List.of(),List.of(),Map.of());}
    private static Devices snapshot(Devices d){return new Devices(3,List.copyOf(d.triggers()),List.copyOf(d.spawners()),Map.of());}
    private static void saveDevices(MinecraftServer server,State state,Devices next) throws IOException {
        validate(next,server);Devices old=state.devices;state.devices=next;
        try{persist(server,state);}catch(IOException failure){state.devices=old;throw failure;}
        state.refreshViews();
    }
    private static void validate(Devices devices,MinecraftServer server) throws IOException {
        if(devices==null||devices.schema()!=3||devices.triggers()==null||devices.spawners()==null||devices.generated()==null
                ||devices.triggers().size()>64||devices.spawners().size()>64||devices.generated().size()>128)throw new IOException("Неверный формат устройств");
        Set<String> names=new HashSet<>();
        for(TriggerView t:devices.triggers()){
            TestingServer.safeName(t.name());
            if(!names.add(t.name())||t.x0()>t.x1()||t.y0()>t.y1()||t.z0()>t.z1()||Math.abs((long)t.x0())>30000000
                    ||Math.abs((long)t.x1())>30000000||Math.abs((long)t.z0())>30000000||Math.abs((long)t.z1())>30000000
                    ||t.y0()<server.overworld().getMinY()||t.y1()>=server.overworld().getMaxY()
                    ||(long)t.x1()-t.x0()>1024||(long)t.y1()-t.y0()>384||(long)t.z1()-t.z0()>1024)
                throw new IOException("Неверная зона "+t.name());
        }
        names.clear();
        for(Spawner s:devices.spawners()){
            TestingServer.safeName(s.name());
            if(!names.add(s.name())||!ENTITY_TYPES.contains(s.type())||!Double.isFinite(s.x()+s.y()+s.z())
                    ||Math.abs(s.x())>30000000||Math.abs(s.z())>30000000||s.y()<server.overworld().getMinY()
                    ||s.y()>=server.overworld().getMaxY()-3||s.count()<1||s.count()>16)
                throw new IOException("Неверный спавнер "+s.name());
        }
        long generated=0;
        for(var e:devices.generated().entrySet()){
            TestingServer.safeName(e.getKey());if(e.getValue()==null||e.getValue().size()>128)throw new IOException("Лимит созданных сущностей");
            generated+=e.getValue().size();for(UUID id:e.getValue())if(id==null)throw new IOException("Неверный UUID сущности");
        }
        if(generated>256)throw new IOException("Лимит: 256 отслеживаемых сущностей");
    }
    public static void action(MinecraftServer server,String action,String value) throws IOException {
        ServerPlayer player=owner(server);State state=state(server);String input=value==null?"":value;
        if(TestingServer.busy(server)&&!Set.of("report","scenario_stop").contains(action))throw new IOException("Дождитесь завершения операции редактора");
        if(Set.of("trigger_add","trigger_remove","spawner_add","spawner_remove","spawner_activate","spawner_clear","scenario_save","scenario_run","scenario_import","scenario_metadata").contains(action)
                &&!Set.of("EDIT","CHARACTER","TEST_RUNNING","TEST_PAUSED").contains(TestingServer.settings(server).mode))
            throw new IOException("Изменение устройств запрещено в текущем режиме");
        switch(action){
            case "trigger_add"->{
                String[] p=input.split("\\|",3);if(p.length!=3)throw new IOException("Зона: name|x,y,z|x,y,z");
                String name=TestingServer.safeName(p[0]);BlockPos a=TestingServer.position(p[1]),b=TestingServer.position(p[2]);
                var list=new ArrayList<>(state.devices.triggers());list.removeIf(t->t.name().equals(name));
                list.add(new TriggerView(name,Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()),
                        Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ())));
                saveDevices(server,state,new Devices(3,list,state.devices.spawners(),state.devices.generated()));
                state.message="Зона входа сохранена: "+name;
            }
            case "trigger_remove"->{
                String name=TestingServer.safeName(input);var list=new ArrayList<>(state.devices.triggers());list.removeIf(t->t.name().equals(name));
                saveDevices(server,state,new Devices(3,list,state.devices.spawners(),state.devices.generated()));state.message="Зона удалена: "+name;
            }
            case "spawner_add"->{
                String[] p=input.split("\\|");if(p.length!=6)throw new IOException("Спавнер: name|entity|x,y,z|count|noAI|invulnerable");
                String name=TestingServer.safeName(p[0]);String[] xyz=p[2].split(",");if(xyz.length!=3)throw new IOException("Нужны x,y,z");
                if(!(p[4].equals("true")||p[4].equals("false"))||!(p[5].equals("true")||p[5].equals("false")))throw new IOException("Флаги должны быть true/false");
                Spawner next=new Spawner(name,p[1].contains(":")?p[1]:"minecraft:"+p[1],Double.parseDouble(xyz[0]),Double.parseDouble(xyz[1]),
                        Double.parseDouble(xyz[2]),Integer.parseInt(p[3]),Boolean.parseBoolean(p[4]),Boolean.parseBoolean(p[5]));
                var list=new ArrayList<>(state.devices.spawners());list.removeIf(s->s.name().equals(name));list.add(next);
                saveDevices(server,state,new Devices(3,state.devices.triggers(),list,state.devices.generated()));state.message="Спавнер сохранён: "+name;
            }
            case "spawner_remove"->{
                String name=TestingServer.safeName(input);var list=new ArrayList<>(state.devices.spawners());list.removeIf(s->s.name().equals(name));
                saveDevices(server,state,new Devices(3,state.devices.triggers(),list,state.devices.generated()));state.message="Устройство удалено; созданные сущности сохранены";
            }
            case "spawner_activate"->{
                if(TestingServer.settings(server).mode.equals("TEST_REVIEW"))throw new IOException("Во время обзора испытания создание сущностей запрещено");
                spawn(server,state,findSpawner(state.devices,input));state.message="Созданы реальные сущности: "+input;
            }
            case "spawner_clear"->{clear(server,state,TestingServer.safeName(input));state.message="Удалены только сущности устройства: "+input;}
            case "scenario_record_start"->{
                String name=TestingServer.safeName(input);
                if(RECORDINGS.containsKey(server))throw new IOException("Запись уже идёт; сначала остановите её");
                Path destination=directory(server).resolve("scenarios").resolve(name+".json");
                if(state.scenarios.containsKey(name))throw new IOException("Имя сценария уже используется; выберите новое имя записи");
                Path external=exports().resolve("recordings").resolve(UUID.randomUUID()+".jsonl");
                Files.createDirectories(external.getParent());
                Recording recording=new Recording(name,server.overworld().getGameTime(),copy(TestingServer.settings(server)),snapshot(state.devices),external);
                JsonObject header=new JsonObject();header.addProperty("schema",3);header.addProperty("kind","recording-start");
                header.addProperty("name",name);header.addProperty("utc",java.time.Instant.now().toString());header.addProperty("startTick",recording.started);
                header.add("profile",GSON.toJsonTree(recording.profile));header.add("devices",GSON.toJsonTree(recording.devices));
                Files.writeString(external,new Gson().toJson(header)+"\n",StandardOpenOption.CREATE_NEW);
                RECORDINGS.put(server,recording);state.message="Записываются принятые параметры, переключатели, отчёты и устройства";
            }
            case "scenario_record_stop"->{
                Recording recording=RECORDINGS.get(server);if(recording==null)throw new IOException("Запись не идёт");
                if(recording.events.isEmpty()){RECORDINGS.remove(server);state.message="Принятых поддерживаемых событий не было; внешний журнал сохранён";break;}
                String name=input.isBlank()?recording.name:TestingServer.safeName(input);
                Scenario scenario=new Scenario(3,name,recording.profile,List.copyOf(recording.events),snapshot(recording.devices));validateScenario(scenario,server);
                Path destination=directory(server).resolve("scenarios").resolve(name+".json");
                if(state.scenarios.containsKey(name))throw new IOException("Такой сценарий уже существует; задайте новое имя перед остановкой записи");
                saveScenario(server,state,scenario);
                TestingEdits.atomic(exports().resolve("recorded-"+UUID.randomUUID()+".scenario.json"),GSON.toJsonTree(scenario));
                RECORDINGS.remove(server);refreshScenarioNames(server,state);
                state.message="Запись сохранена: "+name+" ("+recording.events.size()+" событий)"+(recording.failure.isEmpty()?"":" • "+recording.failure);
            }
            case "scenario_record_play"->{
                action(server,"scenario_run",TestingServer.safeName(input));state.message="Воспроизведение записи: "+input;
            }
            case "scenario_save"->{
                String[] p=input.split("\\|",2);if(p.length!=2)throw new IOException("Сценарий: name|условие:действие:значение; ...");
                String name=TestingServer.safeName(p[0]);List<Event> events=new ArrayList<>();
                if(p[1].stripLeading().startsWith("[")){
                    if(p[1].length()>131072)throw new IOException("События превышают 128 KiB");
                    JsonArray rows=JsonParser.parseString(p[1]).getAsJsonArray();
                    for(JsonElement row:rows)events.add(GSON.fromJson(row,Event.class));
                }else for(String row:p[1].split(";")){
                    String[] fields=row.strip().split(":",3);if(fields.length!=3)throw new IOException("Неверное событие "+row);
                    events.add(new Event(fields[0].strip(),fields[1].strip(),fields[2].strip()));
                }
                TestingSettings profile=copy(TestingServer.settings(server));
                Devices devices=snapshot(state.devices);Scenario old=state.scenarios.get(name);Replay replay=old==null?null:old.replay();
                if(replay!=null&&(!events.equals(old.events())||!devices.equals(old.devices())||!GSON.toJsonTree(profile).equals(GSON.toJsonTree(old.profile()))))
                    replay=new Replay(replay.baselineVersionId(),replay.rollbackPolicy(),List.of());
                Scenario scenario=new Scenario(4,name,profile,events,devices,replay,old==null?null:old.measurements());validateScenario(scenario,server);
                saveScenario(server,state,scenario);
                refreshScenarioNames(server,state);state.message="Сценарий сохранён: "+name;
            }
            case "scenario_run"->{
                Scenario scenario=readScenario(server,directory(server).resolve("scenarios").resolve(TestingServer.safeName(input)+".json"),state);
                if(scenario.replay()!=null&&!scenario.replay().baselineVersionId().isEmpty())
                    throw new IOException("Сценарий привязан к версии мира; запустите через изолированное воспроизведение");
                applyProfile(TestingServer.settings(server),scenario.profile());
                Run run=new Run(scenario,player,server.overworld().getGameTime());state.discardRequested=false;state.completedRollbackPolicy="review";RUNS.put(server,run);
                journal(server,"start",scenario.name(),-1,"","");state.message="Сценарий запущен: "+scenario.name();
            }
            case "scenario_metadata"->{
                String[] fields=input.split("\\|",-1);if(fields.length!=5)throw new IOException("Метаданные: name|baselineVersion|review/discard|intervalTicks|metricsCSV");
                String name=TestingServer.safeName(fields[0]);Scenario current=readScenario(server,directory(server).resolve("scenarios").resolve(name+".json"),state);
                List<String> metrics=fields[4].isBlank()?List.of():Arrays.stream(fields[4].split(",")).map(String::strip).toList();
                Replay replay=new Replay(fields[1].strip(),fields[2].strip(),current.replay()==null?List.of():current.replay().checkpoints());
                Scenario updated=new Scenario(4,name,current.profile(),current.events(),current.devices(),replay,new Measurements(Integer.parseInt(fields[3]),metrics));
                saveScenario(server,state,updated);state.message="Начальная версия, политика отката и измерения сохранены";
            }
            case "scenario_stop"->{finish(server,"manual-stop",false);state.message="Сценарий остановлен";}
            case "scenario_export"->{
                String name=TestingServer.safeName(input);
                Scenario scenario=readScenario(server,directory(server).resolve("scenarios").resolve(name+".json"),state);
                requireStandaloneScenario(scenario);
                Path file=exports().resolve(name+".scenario.json");TestingEdits.atomic(file,GSON.toJsonTree(scenario));
                state.message="Экспорт: "+file.getFileName();
            }
            case "scenario_import"->{
                String[] p=input.split("\\|",2);String filename=TestingServer.safeName(p[0]);
                if(!filename.endsWith(".json"))throw new IOException("Импортируется JSON из darkwinter-testing/scenarios");
                Scenario original=readScenario(server,exports().resolve(filename),state);requireStandaloneScenario(original);
                String name=p.length==2&&!p[1].isBlank()?TestingServer.safeName(p[1]):original.name();
                Scenario imported=new Scenario(4,name,original.profile(),original.events(),snapshot(original.devices()),original.replay(),original.measurements());
                validateScenario(imported,server);Path destination=directory(server).resolve("scenarios").resolve(name+".json");
                if(state.scenarios.containsKey(name))throw new IOException("Сценарий уже существует; задайте новое имя");
                saveScenario(server,state,imported);refreshScenarioNames(server,state);state.message="Сценарий импортирован: "+name;
            }
            case "report"->{report(server,input);state.message="Диагностический отчёт сохранён";}
            default->throw new IOException("Неизвестное действие сценария: "+action);
        }
        journal(server,"action",action,-1,"",input);
    }
    /** Called only after server authority accepted the action; logging can never roll back that verdict. */
    public static void observe(MinecraftServer server,String action,String value){
        Recording recording=RECORDINGS.get(server);if(recording==null)return;
        if(!Set.of("toggle","parameter","setting","report","spawner_activate").contains(action))return;
        try{
            if(!server.isSameThread()||!TestingServer.isTesting(server))return;
            long offset=server.overworld().getGameTime()-recording.started;
            if(offset>72000||recording.events.size()>=256)throw new IOException("Лимит записи: 256 событий / 72000 тиков");
            Devices recordedDevices=recording.devices;
            String eventAction=action.equals("setting")?"parameter":action;
            String eventValue=value==null?"":value;
            if(eventAction.equals("parameter")&&!eventValue.contains("=")){
                String[] parameter=eventValue.split("\\|",2);if(parameter.length==2)eventValue=parameter[0]+"="+parameter[1];
            }
            if(eventAction.equals("spawner_activate")){
                State state=state(server);Spawner current;
                try{current=findSpawner(state.devices,eventValue);}
                catch(IOException missing){Run run=RUNS.get(server);if(run==null)throw missing;current=findSpawner(run.scenario.devices(),eventValue);}
                Spawner captured=current;
                if(recording.devices.spawners().stream().noneMatch(device->device.equals(captured))){
                    String alias="recorded_"+recording.events.size()+"_"+current.name().substring(0,Math.min(48,current.name().length()));
                    current=new Spawner(alias,current.type(),current.x(),current.y(),current.z(),current.count(),current.noAI(),current.invulnerable());
                    var devices=new ArrayList<>(recording.devices.spawners());devices.add(current);
                    recordedDevices=new Devices(3,recording.devices.triggers(),devices,Map.of());eventValue=alias;
                }
                eventAction="spawn";
            }
            Event event=new Event(Long.toString(offset),eventAction,eventValue);
            var candidate=new ArrayList<>(recording.events);candidate.add(event);
            validateScenario(new Scenario(3,recording.name,recording.profile,candidate,recordedDevices),server);
            recording.devices=recordedDevices;recording.events.add(event);recording.count=recording.events.size();
            JsonObject row=new JsonObject();row.addProperty("kind","accepted-event");row.addProperty("tick",server.overworld().getGameTime());
            row.addProperty("offset",offset);row.addProperty("action",eventAction);row.addProperty("value",eventValue);
            Files.writeString(recording.log,new Gson().toJson(row)+"\n",StandardOpenOption.APPEND);
        }catch(Exception failure){
            recording.failure="Ошибка записи: "+failure.getMessage();State state=STATES.get(server);if(state!=null)state.message=recording.failure;
            org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error("Accepted action could not be recorded",failure);
        }
    }
    private static Scenario readScenario(MinecraftServer server,Path file,State state) throws IOException {
        if(file.getParent().equals(directory(server).resolve("scenarios"))){
            String name=file.getFileName().toString().replaceFirst("\\.json$","");
            Scenario known=state.scenarios.get(name);if(known!=null)return known;
            throw new IOException("Сценарий отсутствует: "+name);
        }
        return readScenarioFile(server,file,state.devices);
    }
    private static Scenario readScenarioFile(MinecraftServer server,Path file,Devices fallback) throws IOException {
        if(!Files.isRegularFile(file)||Files.size(file)>512*1024)throw new IOException("Сценарий отсутствует или превышает 512 KiB");
        try{return parseScenario(server,JsonParser.parseString(Files.readString(file)).getAsJsonObject(),fallback);}
        catch(RuntimeException error){throw new IOException("Сценарий отклонён до применения: "+error.getMessage(),error);}
    }
    private static Scenario parseScenario(MinecraftServer server,JsonObject root,Devices fallback) throws IOException {
        Scenario scenario;
        if(root.get("schema").getAsInt()==2){
            List<Event> events=new ArrayList<>();
            for(JsonElement row:root.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject();events.add(new Event(e.get("tick").getAsString(),e.get("action").getAsString(),e.get("value").getAsString()));}
            scenario=new Scenario(3,root.get("name").getAsString(),GSON.fromJson(root.get("profile"),TestingSettings.class),events,snapshot(fallback));
        }else scenario=GSON.fromJson(root,Scenario.class);
        validateScenario(scenario,server);return scenario;
    }
    private static void validateScenario(Scenario scenario,MinecraftServer server) throws IOException {
        if(scenario==null||!Set.of(3,4).contains(scenario.schema())||scenario.profile()==null||scenario.events()==null||scenario.events().isEmpty()
                ||scenario.events().size()>256)throw new IOException("Сценарий: 1–256 событий, schema 3/4");
        TestingServer.safeName(scenario.name());scenario.profile().validate();validate(scenario.devices(),server);
        if(scenario.replay()!=null){
            Replay replay=scenario.replay();
            if(replay.baselineVersionId()==null||replay.rollbackPolicy()==null||replay.checkpoints()==null||replay.checkpoints().size()>32
                    ||!Set.of("review","discard").contains(replay.rollbackPolicy()))throw new IOException("Неверная политика воспроизведения");
            if(!replay.baselineVersionId().isEmpty())versionId(replay.baselineVersionId());
            Set<String> labels=new HashSet<>();
            for(Checkpoint point:replay.checkpoints()){
                TestingServer.safeName(point.label());versionId(point.versionId());
                if(!labels.add(point.label())||point.elapsedTicks()<0||point.elapsedTicks()>72000||point.eventIndex()<0||point.eventIndex()>scenario.events().size()
                        ||point.firedIndices()==null||point.inside()==null||point.eventIndex()!=point.firedIndices().size()
                        ||new HashSet<>(point.firedIndices()).size()!=point.firedIndices().size())throw new IOException("Неверная контрольная точка сценария");
                for(int index:point.firedIndices())if(index<0||index>=scenario.events().size())throw new IOException("Неверный индекс события");
                for(var entry:point.inside().entrySet())if(entry.getValue()==null||scenario.devices().triggers().stream().noneMatch(t->t.name().equals(entry.getKey())))
                    throw new IOException("Контрольная точка содержит неизвестную зону");
            }
        }
        if(scenario.measurements()!=null){
            Measurements metrics=scenario.measurements();
            if(metrics.intervalTicks()<20||metrics.intervalTicks()>1200||metrics.metrics()==null||metrics.metrics().size()>4
                    ||new HashSet<>(metrics.metrics()).size()!=metrics.metrics().size()
                    ||!Set.of("health","food","heap","loaded_chunks").containsAll(metrics.metrics()))throw new IOException("Измерения: интервал 20–1200, health/food/heap/loaded_chunks");
        }
        for(Event e:scenario.events()){
            if(e.when()==null||e.action()==null||e.value()==null||e.value().length()>256)throw new IOException("Неверное событие");
            if(e.when().startsWith("enter@")){
                String name=e.when().substring(6);
                if(scenario.devices().triggers().stream().noneMatch(t->t.name().equals(name)))throw new IOException("Нет зоны "+name);
            }else if(e.when().startsWith("health<=")){
                float limit=Float.parseFloat(e.when().substring(8));if(!Float.isFinite(limit)||limit<0||limit>1024)throw new IOException("Порог здоровья 0–1024");
            }else{
                long tick=Long.parseLong(e.when());if(tick<0||tick>72000)throw new IOException("Таймер 0–72000 тиков");
            }
            switch(e.action()){
                case "toggle"->{if(!List.of(TestingSettings.MODULES).contains(e.value()))throw new IOException("Неизвестный модуль");}
                case "parameter"->{TestingSettings candidate=copy(scenario.profile());parameter(candidate,e.value());}
                case "spawn"->findSpawner(scenario.devices(),e.value());
                case "report","finish"->{}
                default->throw new IOException("Действия: toggle / parameter / spawn / report / finish");
            }
        }
    }

    private static boolean hasWorldReferences(Scenario scenario){
        Replay replay=scenario.replay();
        return replay!=null&&(!replay.baselineVersionId().isEmpty()||!replay.checkpoints().isEmpty());
    }
    private static void requireStandaloneScenario(Scenario scenario)throws IOException {
        if(hasWorldReferences(scenario))throw new IOException("Сценарий содержит версии полного мира. Переносите его вместе с зависимостями в пакете .dwisland; отдельный .scenario.json не включает сохранения.");
    }
    private static void versionId(String value) throws IOException {
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Неверный ID версии мира");
    }
    public static String doc(MinecraftServer server,String name) throws IOException {
        owner(server);return GSON.toJson(readScenario(server,directory(server).resolve("scenarios").resolve(TestingServer.safeName(name)+".json"),state(server)));
    }
    public static void validateReplay(MinecraftServer server,String document,int eventIndex,long elapsedTicks) throws IOException {
        owner(server);Scenario scenario=parseScenario(server,JsonParser.parseString(document).getAsJsonObject(),emptyDevices());
        replayPoint(scenario,eventIndex,elapsedTicks);
    }
    public static String replayVersion(MinecraftServer server,String document,int eventIndex,long elapsedTicks) throws IOException {
        owner(server);Scenario scenario=parseScenario(server,JsonParser.parseString(document).getAsJsonObject(),emptyDevices());
        Checkpoint point=replayPoint(scenario,eventIndex,elapsedTicks);
        String version=point==null?(scenario.replay()==null?"":scenario.replay().baselineVersionId()):point.versionId();
        if(version.isEmpty())throw new IOException("Сценарий не привязан к начальной версии мира");
        return version;
    }
    public static void validateReplay(MinecraftServer server,String document,String version,int eventIndex,long elapsedTicks) throws IOException {
        owner(server);versionId(version);Scenario scenario=parseScenario(server,JsonParser.parseString(document).getAsJsonObject(),emptyDevices());
        if(eventIndex==0&&elapsedTicks==0){
            if(scenario.replay()!=null&&version.equals(scenario.replay().baselineVersionId()))return;
        }else if(scenario.replay()!=null)for(Checkpoint point:scenario.replay().checkpoints())
            if(point.versionId().equals(version)&&point.eventIndex()==eventIndex&&point.elapsedTicks()==elapsedTicks)return;
        throw new IOException("Версия мира не соответствует точному сохранённому курсору сценария");
    }
    private static Checkpoint replayPoint(Scenario scenario,int eventIndex,long elapsedTicks) throws IOException {
        if(eventIndex==0&&elapsedTicks==0)return null;
        if(scenario.replay()!=null)for(Checkpoint point:scenario.replay().checkpoints())
            if(point.eventIndex()==eventIndex&&point.elapsedTicks()==elapsedTicks)return point;
        throw new IOException("Переход возможен только к сохранённой контрольной точке мира и событий");
    }
    /** Only after Sessions materialized the matching immutable version into a disposable test copy. */
    public static void resume(MinecraftServer server,String document,int eventIndex,long elapsedTicks) throws IOException {
        ServerPlayer player=owner(server);
        if(!Set.of("TEST_RUNNING","TEST_PAUSED").contains(TestingServer.settings(server).mode))throw new IOException("Воспроизведение требует изолированную тестовую копию");
        Scenario scenario=parseScenario(server,JsonParser.parseString(document).getAsJsonObject(),emptyDevices());
        Checkpoint point=replayPoint(scenario,eventIndex,elapsedTicks);Run run=new Run(scenario,player,server.overworld().getGameTime()-elapsedTicks);
        if(point==null)applyProfile(TestingServer.settings(server),scenario.profile());
        else{for(int index:point.firedIndices())run.fired.set(index);run.firedCount=point.eventIndex();run.inside.clear();run.inside.putAll(point.inside());}
        RUNS.put(server,run);State state=state(server);state.discardRequested=false;state.completedRollbackPolicy="review";state.message="Изолированное воспроизведение: "+scenario.name();
        journal(server,"replay-resume",scenario.name(),elapsedTicks,"",Integer.toString(eventIndex));
    }
    /** Capture this before closing the server. The zero UUID is replaced with the actual offline snapshot ID. */
    public static String captureReplayCursor(MinecraftServer server,String name,String label) throws IOException {
        owner(server);Run run=RUNS.get(server);
        if(run==null||!run.scenario.name().equals(name))throw new IOException("Для точки воспроизведения запустите выбранный сценарий");
        long elapsed=server.overworld().getGameTime()-run.started;
        if(elapsed==0&&run.firedCount==0)throw new IOException("Дождитесь первого тика или события перед созданием точки");
        List<Integer> fired=run.fired.stream().boxed().toList();
        Checkpoint point=new Checkpoint(TestingServer.safeName(label),"00000000-0000-0000-0000-000000000000",run.firedCount,elapsed,fired,Map.copyOf(run.inside));
        Replay before=run.scenario.replay();List<Checkpoint> points=new ArrayList<>(before==null?List.of():before.checkpoints());
        points.removeIf(existing->existing.label().equals(label));points.add(point);
        Replay replay=new Replay(before==null?"":before.baselineVersionId(),before==null?"review":before.rollbackPolicy(),List.copyOf(points));
        Scenario result=new Scenario(4,run.scenario.name(),run.scenario.profile(),run.scenario.events(),run.scenario.devices(),replay,run.scenario.measurements());
        validateScenario(result,server);return GSON.toJson(result);
    }
    public static String bindReplayCheckpoint(String document,String versionId) throws IOException {
        versionId(versionId);
        try{
            JsonObject result=JsonParser.parseString(document).getAsJsonObject();
            JsonArray points=result.getAsJsonObject("replay").getAsJsonArray("checkpoints");
            if(points.isEmpty())throw new IOException("Нет захваченного курсора");
            JsonObject point=points.get(points.size()-1).getAsJsonObject();
            if(!point.get("versionId").getAsString().equals("00000000-0000-0000-0000-000000000000"))throw new IOException("Точка уже привязана к версии");
            point.addProperty("versionId",versionId);return GSON.toJson(result);
        }catch(RuntimeException error){throw new IOException("Неверный курсор воспроизведения",error);}
    }
    public static void saveDocument(MinecraftServer server,String document) throws IOException {
        owner(server);Scenario scenario=parseScenario(server,JsonParser.parseString(document).getAsJsonObject(),emptyDevices());saveScenario(server,state(server),scenario);
    }
    public static List<String> checkpoints(MinecraftServer server,String name){
        State state=STATES.get(server);if(state==null)return List.of();Scenario scenario=state.scenarios.get(name);
        return scenario==null||scenario.replay()==null?List.of():scenario.replay().checkpoints().stream()
                .map(point->point.label()+" | "+point.versionId()+" | "+point.eventIndex()+" | "+point.elapsedTicks()).toList();
    }
    public static boolean discardRequested(MinecraftServer server){State state=STATES.get(server);return state!=null&&state.discardRequested;}
    public static void acknowledgeDiscard(MinecraftServer server) throws IOException {
        requireDocumentContext(server);State state=STATES.get(server);if(state!=null)state.discardRequested=false;
    }
    public static String rollbackPolicy(MinecraftServer server){
        Run run=RUNS.get(server);State state=STATES.get(server);
        return run==null?(state==null?"review":state.completedRollbackPolicy):run.scenario.replay()==null?"review":run.scenario.replay().rollbackPolicy();
    }
    private static Spawner findSpawner(Devices d,String name) throws IOException {
        return d.spawners().stream().filter(s->s.name().equals(name)).findFirst().orElseThrow(()->new IOException("Нет спавнера "+name));
    }
    private static void spawn(MinecraftServer server,State state,Spawner spawner) throws IOException {
        var world=server.overworld();
        long existing=state.devices.generated().values().stream().mapToLong(List::size).sum();
        if(existing+spawner.count()>256)throw new IOException("Лимит созданных сущностей; сначала очистите устройства");
        var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(spawner.type()));List<Mob> entities=new ArrayList<>();
        for(int i=0;i<spawner.count();i++){
            double x=spawner.x()+(i%4)*2.5,z=spawner.z()+(i/4)*2.5;BlockPos pos=BlockPos.containing(x,spawner.y(),z);
            if(!world.hasChunkAt(pos)||TestingServer.zoneProtected(context(server),pos))throw new IOException("Спавнер пересекает незагруженную или защищённую зону");
            var entity=type.create(world,EntitySpawnReason.COMMAND);
            if(!(entity instanceof Mob mob))throw new IOException("Тип сущности не является поддерживаемым Mob");
            mob.setPos(x,spawner.y(),z);mob.setNoAi(spawner.noAI());mob.setPermanentlyInvulnerable(spawner.invulnerable());
            mob.setPersistenceRequired();mob.setCustomName(Component.literal(spawner.name()));
            if(!world.noCollision(mob,mob.getBoundingBox()))throw new IOException("Место спавна занято блоком или сущностью: "+pos);
            entities.add(mob);
        }
        Map<String,List<UUID>> generated=new LinkedHashMap<>(state.devices.generated());
        List<UUID> ids=new ArrayList<>(generated.getOrDefault(spawner.name(),List.of()));
        if(ids.size()+entities.size()>128)throw new IOException("Лимит устройства: 128 сущностей");
        try{
            for(Mob mob:entities){if(!world.addFreshEntity(mob))throw new IOException("Minecraft отклонил добавление сущности");ids.add(mob.getUUID());}
            generated.put(spawner.name(),List.copyOf(ids));
            saveDevices(server,state,new Devices(3,state.devices.triggers(),state.devices.spawners(),generated));
        }catch(IOException|RuntimeException e){for(Mob mob:entities)mob.discard();throw e;}
    }
    // Context remains server-private; this lookup is only used for the common protection predicate.
    private static TestingServer.Context context(MinecraftServer server) throws IOException {
        TestingServer.Context c=TestingServer.context(server);
        if(c==null||!server.isSameThread())throw new IOException("Контекст устройств недоступен");
        return c;
    }
    private static void clear(MinecraftServer server,State state,String name) throws IOException {
        List<UUID> ids=state.devices.generated().getOrDefault(name,List.of());
        for(UUID id:ids){var entity=server.overworld().getEntity(id);if(entity!=null)entity.discard();}
        Map<String,List<UUID>> generated=new LinkedHashMap<>(state.devices.generated());generated.remove(name);
        saveDevices(server,state,new Devices(3,state.devices.triggers(),state.devices.spawners(),generated));
    }
    static void tick(MinecraftServer server) {
        if(!TestingServer.isTesting(server)||TestingServer.busy(server))return;
        State state;
        try{state=state(server);}catch(Exception e){return;}
        Run run=RUNS.get(server);if(run==null)return;
        String mode=TestingServer.settings(server).mode;
        if(mode.startsWith("TEST")&&(server.tickRateManager().isFrozen()||mode.equals("TEST_PAUSED")||mode.equals("TEST_REVIEW")))return;
        try(var actor=TestingActors.enterCaptured(server,run.actor)){
            ServerPlayer player=owner(server);
            if(!run.player.equals(player.getUUID()))throw new IOException("Владелец сценария изменился");
            long elapsed=server.overworld().getGameTime()-run.started;
            if(elapsed>72000){finish(server,"timeout-72000-ticks",true);return;}
            Measurements measurements=run.scenario.measurements();
            if(measurements!=null&&!measurements.metrics().isEmpty()&&TestingServer.settings(server).diagnostics
                    &&(run.lastMeasurement<0||elapsed-run.lastMeasurement>=measurements.intervalTicks())){
                JsonObject sample=new JsonObject();for(String metric:measurements.metrics())switch(metric){
                    case "health"->sample.addProperty(metric,player.getHealth());case "food"->sample.addProperty(metric,player.getFoodData().getFoodLevel());
                    case "heap"->sample.addProperty(metric,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
                    case "loaded_chunks"->sample.addProperty(metric,server.overworld().getChunkSource().getLoadedChunksCount());default->{}
                }
                journal(server,"measurement",run.scenario.name(),elapsed,"",sample.toString());run.lastMeasurement=elapsed;
            }
            Set<String> entered=new HashSet<>();
            for(TriggerView t:run.scenario.devices().triggers()){
                boolean inside=t.contains(player),before=run.inside.getOrDefault(t.name(),false);
                if(inside&&!before)entered.add(t.name());run.inside.put(t.name(),inside);
            }
            for(int i=0;i<run.scenario.events().size();i++){
                if(run.fired.get(i))continue;Event e=run.scenario.events().get(i);boolean fire;
                if(e.when().startsWith("enter@"))fire=entered.contains(e.when().substring(6));
                else if(e.when().startsWith("health<="))fire=player.getHealth()<=Float.parseFloat(e.when().substring(8));
                else fire=elapsed>=Long.parseLong(e.when());
                if(!fire)continue;
                run.fired.set(i);run.firedCount++;journal(server,"event",run.scenario.name(),elapsed,e.when(),e.action()+":"+e.value());
                switch(e.action()){
                    case "toggle"->{var profile=TestingServer.settings(server);profile.modules.put(e.value(),!profile.enabled(e.value()));}
                    case "parameter"->{TestingSettings candidate=copy(TestingServer.settings(server));parameter(candidate,e.value());applyProfile(TestingServer.settings(server),candidate);}
                    case "spawn"->spawn(server,state,findSpawner(run.scenario.devices(),e.value()));
                    case "report"->report(server,e.value());
                    case "finish"->{finish(server,e.value(),true);return;}
                    default->throw new IOException("Неподдерживаемое событие");
                }
                observe(server,e.action().equals("spawn")?"spawner_activate":e.action(),e.value());
            }
            state.message=run.scenario.name()+": "+run.firedCount+"/"+run.scenario.events().size()+" событий, "+elapsed+" тиков";
            if(run.firedCount==run.scenario.events().size())finish(server,"all-events-complete",false);
        }catch(Exception e){
            RUNS.remove(server);state.message="Сценарий остановлен: "+e.getMessage();
            try{journal(server,"error",run.scenario.name(),-1,"",e.toString());report(server,"scenario-error");}catch(IOException ignored){}
        }
    }
    private static void finish(MinecraftServer server,String reason,boolean review) throws IOException {
        Run run=RUNS.remove(server);State state=state(server);
        if(run!=null){state.completedRollbackPolicy=run.scenario.replay()==null?"review":run.scenario.replay().rollbackPolicy();
            state.discardRequested=state.completedRollbackPolicy.equals("discard")&&TestingServer.settings(server).mode.startsWith("TEST");}
        state.message="Сценарий завершён: "+reason;journal(server,"finish",run==null?"":run.scenario.name(),-1,"",reason);
        if(review&&TestingServer.settings(server).mode.startsWith("TEST")){
            TestingServer.settings(server).mode="TEST_REVIEW";server.tickRateManager().setFrozen(true);
        }
        report(server,reason);
    }
    private static TestingSettings copy(TestingSettings source){return GSON.fromJson(GSON.toJson(source),TestingSettings.class);}
    private static void applyProfile(TestingSettings to,TestingSettings from){
        to.modules.clear();to.modules.putAll(from.modules);to.weather=from.weather;to.windSpeed=from.windSpeed;to.temperature=from.temperature;
        to.flySpeed=from.flySpeed;to.radius=from.radius;to.strength=from.strength;to.density=from.density;to.brush=from.brush;
        to.windDirection=from.windDirection;to.filmStrength=from.filmStrength;to.hazeDensity=from.hazeDensity;to.shadowStrength=from.shadowStrength;to.diagnostics=from.diagnostics;
        to.brushShape=from.brushShape;to.grassSpecies=from.grassSpecies;to.bushSpecies=from.bushSpecies;to.treeSpecies=from.treeSpecies;
        to.staminaCapacity=from.staminaCapacity;to.staminaSprintDrain=from.staminaSprintDrain;to.staminaWalkRegen=from.staminaWalkRegen;to.staminaIdleRegen=from.staminaIdleRegen;
        to.staminaJumpCost=from.staminaJumpCost;to.staminaSprintJumpCost=from.staminaSprintJumpCost;
        to.motionWalkSpeed=from.motionWalkSpeed;to.motionCameraWeight=from.motionCameraWeight;to.motionTurnWeight=from.motionTurnWeight;
    }
    private static void parameter(TestingSettings profile,String value) throws IOException {
        String[] p=value.split("=",2);if(p.length!=2)throw new IOException("Параметр: key=value");
        switch(p[0]){
            case "weather"->profile.weather=p[1];case "windSpeed"->profile.windSpeed=Float.parseFloat(p[1]);
            case "windDirection"->profile.windDirection=Float.parseFloat(p[1]);case "filmStrength"->profile.filmStrength=Float.parseFloat(p[1]);
            case "hazeDensity"->profile.hazeDensity=Float.parseFloat(p[1]);case "shadowStrength"->profile.shadowStrength=Float.parseFloat(p[1]);
            case "temperature"->profile.temperature=Float.parseFloat(p[1]);case "flySpeed"->profile.flySpeed=Float.parseFloat(p[1]);
            case "radius"->profile.radius=Integer.parseInt(p[1]);case "strength"->profile.strength=Integer.parseInt(p[1]);case "brush"->profile.brush=p[1];
            case "density"->profile.density=Integer.parseInt(p[1]);
            case "staminaCapacity"->profile.staminaCapacity=Float.parseFloat(p[1]);case "staminaSprintDrain"->profile.staminaSprintDrain=Float.parseFloat(p[1]);
            case "staminaWalkRegen"->profile.staminaWalkRegen=Float.parseFloat(p[1]);case "staminaIdleRegen"->profile.staminaIdleRegen=Float.parseFloat(p[1]);
            case "staminaJumpCost"->profile.staminaJumpCost=Float.parseFloat(p[1]);case "staminaSprintJumpCost"->profile.staminaSprintJumpCost=Float.parseFloat(p[1]);
            case "motionWalkSpeed"->profile.motionWalkSpeed=Double.parseDouble(p[1]);case "motionCameraWeight"->profile.motionCameraWeight=Double.parseDouble(p[1]);case "motionTurnWeight"->profile.motionTurnWeight=Double.parseDouble(p[1]);
            case "diagnostics"->{if(!Set.of("true","false").contains(p[1]))throw new IOException("Диагностика: true/false");profile.diagnostics=Boolean.parseBoolean(p[1]);}
            case "brushShape"->profile.brushShape=p[1];case "grassSpecies"->profile.grassSpecies=p[1];
            case "bushSpecies"->profile.bushSpecies=p[1];case "treeSpecies"->profile.treeSpecies=p[1];
            default->throw new IOException("Недопустимый параметр сценария");
        }
        profile.validate();
    }
    private static void refreshScenarioNames(MinecraftServer server,State state) throws IOException {
        state.scenarioNames=state.scenarios.keySet().stream().sorted().toList();
    }
    private static void journal(MinecraftServer server,String kind,String scenario,long elapsed,String when,String value) throws IOException {
        JsonObject row=new JsonObject();row.addProperty("utc",java.time.Instant.now().toString());row.addProperty("tick",server.overworld().getGameTime());
        row.addProperty("kind",kind);row.addProperty("scenario",scenario);row.addProperty("elapsed",elapsed);row.addProperty("condition",when);row.addProperty("value",value);
        Path path=directory(server).resolve("scenario-events.jsonl");Files.createDirectories(path.getParent());
        Files.writeString(path,new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static void report(MinecraftServer server,String name) throws IOException {
        JsonObject result=new JsonObject();result.addProperty("schema",3);result.addProperty("name",name);
        result.addProperty("utc",java.time.Instant.now().toString());result.addProperty("tick",server.overworld().getGameTime());
        result.addProperty("loadedChunks",server.overworld().getChunkSource().getLoadedChunksCount());
        result.addProperty("heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        result.addProperty("heapMaxBytes",Runtime.getRuntime().maxMemory());result.addProperty("playerCount",server.getPlayerList().getPlayerCount());
        result.add("settings",GSON.toJsonTree(TestingServer.settings(server)));
        State state=STATES.get(server);if(state!=null){result.addProperty("scenarioStatus",state.message);result.add("devices",GSON.toJsonTree(state.devices));}
        var player=server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if(player!=null){result.addProperty("health",player.getHealth());result.addProperty("food",player.getFoodData().getFoodLevel());}
        result.addProperty("gpuTiming","unavailable");result.addProperty("heating","not implemented");
        Path report=directory(server).resolve("reports").resolve(System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");TestingEdits.atomic(report,result);
    }
}
