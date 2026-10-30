// Dispatches marked-save editor actions through the owning integrated server.
package com.dark.winter.testing;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Integrated-server authority. All world mutations require a marked save and its local owner. */
public final class TestingServer {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger("DarkWinter/Testing");
    private static final Map<MinecraftServer,Context> STATES=new ConcurrentHashMap<>();
    private static final Map<MinecraftServer,Boolean> IDENTITIES=new ConcurrentHashMap<>();
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final TestingSettings OFF=new TestingSettings();
    private static java.util.function.BiPredicate<Level,String> clientModules=(level,module)->true;
    public static void setClientModules(java.util.function.BiPredicate<Level,String> source){clientModules=source;}
    public static boolean enabledFor(Level level,String module){return level.isClientSide()?clientModules.test(level,module):enabled(level.getServer(),module);}
    static void terrainTouched(MinecraftServer server,BlockPos pos){Context c=STATES.get(server);if(c!=null)c.heights.put(TestingTerrain.column(pos.getX(),pos.getZ()),pos.getY());}
    static void biomeCommitted(MinecraftServer server,String biome){Context c=STATES.get(server);if(c!=null)c.settings.biome=biome;}
    public static boolean vegetated(MinecraftServer server,String category){Context c=STATES.get(server);return c!=null&&c.edits.origins.containsValue(category);}
    private TestingServer() {}
    static void flushMetadata(MinecraftServer server)throws IOException{Context c=STATES.get(server);if(c!=null)c.save();}
    static Context context(MinecraftServer server){return server==null?null:STATES.get(server);}
    static final class Context {
        final MinecraftServer server;final Path directory;final TestingEdits edits;final boolean readOnlyPreview;
        volatile TestingSettings settings;
        volatile String message="Лаборатория готова";
        BlockPos a,b;
        final Map<String,Zone> zones=new ConcurrentHashMap<>();
        final Map<String,ZoneRule> zoneRules=new ConcurrentHashMap<>();
        final Map<Long,Support> support=new HashMap<>();
        volatile List<BlockPos> previewProtected=List.of();
        final Map<Long,Integer> heights=new HashMap<>();
        final Set<String> vegetation=new HashSet<>();
        final List<Runnable> jobs=new ArrayList<>();
        long sequence;
        Context(MinecraftServer server) throws IOException {
            this.server=server;directory=server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing");
            readOnlyPreview=Files.isRegularFile(directory.resolve("preview-source.json"));
            Files.createDirectories(directory);Path path=directory.resolve("settings.json");
            settings=Files.exists(path)?GSON.fromJson(Files.readString(path),TestingSettings.class):new TestingSettings();
            settings.validate();edits=new TestingEdits(server.overworld(),directory);
            Path zonesFile=directory.resolve("island.json");
            if(Files.exists(zonesFile)) {
                JsonObject root=JsonParser.parseString(Files.readString(zonesFile)).getAsJsonObject();
                if(root.get("schema").getAsInt()!=2) throw new IOException("Unsupported island schema");
                root.getAsJsonObject("heights").entrySet().forEach(e->heights.put(Long.parseLong(e.getKey()),e.getValue().getAsInt()));
                TestingWorldDocuments.loadLegacyZones(this,root);
                for(JsonElement e:root.getAsJsonArray("vegetation")) vegetation.add(e.getAsString());
            }
            save();
        }
        void save() throws IOException {
            TestingEdits.atomic(directory.resolve("settings.json"),GSON.toJsonTree(settings));
            JsonObject root=new JsonObject();root.addProperty("schema",2);
            root.add("heights",GSON.toJsonTree(heights));root.add("zones",GSON.toJsonTree(zones));root.add("zoneRules",GSON.toJsonTree(zoneRules));
            root.add("vegetation",GSON.toJsonTree(vegetation));TestingEdits.atomic(directory.resolve("island.json"),root);
            edits.saveProvenance();
        }
        void event(String action,String value) {
            sequence++;
            JsonObject row=new JsonObject();row.addProperty("sequence",sequence);
            row.addProperty("utc",java.time.Instant.now().toString());row.addProperty("tick",server.overworld().getGameTime());
            row.addProperty("action",action);row.addProperty("value",value);
            if(TestingActors.actor()!=null){row.addProperty("actor",TestingActors.actor());row.addProperty("actorSession",TestingActors.session());}
            try {Files.writeString(directory.resolve("events.jsonl"),new Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
            catch(IOException e) {LOG.error("Cannot write test journal",e);message="Ошибка записи журнала: "+e.getMessage();}
        }
    }
    record Support(int min,int max) {}
    public record ZoneRule(boolean natural,boolean objects) {}
    public record ZoneView(String name,int x0,int y0,int z0,int x1,int y1,int z1,boolean natural,boolean objects) {
        public ZoneView(String name,int x0,int y0,int z0,int x1,int y1,int z1){this(name,x0,y0,z0,x1,y1,z1,true,true);}
    }
    public static List<ZoneView> protectedZones(MinecraftServer server){Context c=STATES.get(server);if(c==null)return List.of();return c.zones.entrySet().stream().map(e->{Zone z=e.getValue();ZoneRule rule=c.zoneRules.getOrDefault(e.getKey(),new ZoneRule(true,true));return new ZoneView(e.getKey(),z.x0,z.y0,z.z0,z.x1,z.y1,z.z1,rule.natural(),rule.objects());}).toList();}
    public static List<BlockPos> previewProtected(MinecraftServer server){Context c=STATES.get(server);return c==null?List.of():c.previewProtected;}
    record Zone(int x0,int y0,int z0,int x1,int y1,int z1) {
        boolean contains(BlockPos p) {return p.getX()>=x0&&p.getX()<=x1&&p.getY()>=y0&&p.getY()<=y1&&p.getZ()>=z0&&p.getZ()<=z1;}
    }
    public static void init() {
        TestingNativeEdits.init();
        TestingImportLoader.init();
        ServerLifecycleEvents.SERVER_STARTED.register(TestingServer::attach);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            Context c=STATES.get(server);if(c==null)return;
            TestingImportLoader.tick(server);
            TestingNaturalLoader.tick(server);
            c.edits.tick();
            TestingResize.tick(server);
            resumeTerrainImports(server);
            TestingScene.tick(server);
            c.vegetation.removeIf(category->!c.edits.origins.containsValue(category));
            for(String category:List.of("grass","bushes","trees"))if(c.edits.origins.containsValue(category))c.vegetation.add(category);
            if(!busy(server)&&!c.jobs.isEmpty()) c.jobs.removeFirst().run();
            TestingScenarios.tick(server);
            if(server.getTickCount()%100==0) {
                if(!enabled(server,"weather")) server.setWeatherParameters(6000,0,false,false);
                try {c.edits.saveProvenance();} catch(IOException e) {c.message="Ошибка сохранения лаборатории: "+e.getMessage();LOG.error(c.message,e);}
            }
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world,player,pos,state,entity)->!busy(world.getServer()));
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,entity)->authored(world,pos));
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player,world,hand,entity,hit)->
                busy(world.getServer())?net.minecraft.world.InteractionResult.FAIL:net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->
                busy(world.getServer())?net.minecraft.world.InteractionResult.FAIL:net.minecraft.world.InteractionResult.PASS);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{TestingImportLoader.cancel(server);TestingNaturalLoader.cancel(server);Context c=STATES.get(server);if(c!=null)try{c.save();}catch(IOException e){throw new IllegalStateException(e);}});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{TestingNaturalLoader.cancel(server);TestingExportLoader.cancel(server);TestingImportLoader.cancel(server);for(var level:server.getAllLevels())TestingEntities.finishRecovery(level);TestingResize.forget(server);TestingScene.forget(server);STATES.remove(server);IDENTITIES.remove(server);});
    }
    public static boolean isTesting(MinecraftServer server) {
        if(server==null)return false;
        return IDENTITIES.computeIfAbsent(server,s->{
            Path marker=s.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing.properties");
            try {
                if(!Files.isRegularFile(marker))return false;
                Properties p=new Properties();try(var in=Files.newInputStream(marker)){p.load(in);}
                return ("1".equals(p.getProperty("schema"))||"2".equals(p.getProperty("schema")))&&"testing".equals(p.getProperty("mode"));
            } catch(Exception e){LOG.error("Test-world identity rejected",e);return false;}
        });
    }
    public static void attach(MinecraftServer server) {
        if(server==null)return;
        if(!server.isSameThread()) {server.execute(()->attach(server));return;}
        IDENTITIES.remove(server);
        if(!isTesting(server)||STATES.containsKey(server))return;
        try {Context c=new Context(server);STATES.put(server,c);TestingScene.attach(server);TestingResize.recover(server);if(Files.isRegularFile(c.directory.resolve("terrain-followup.json")))server.tickRateManager().setFrozen(true);c.event("attach","schema2");}
        catch(Exception e){LOG.error("Testing metadata rejected; editing is disabled, original files preserved",e);}
    }
    public static boolean enabled(MinecraftServer server,String module) {
        if(!isTesting(server))return true;
        Context c=STATES.get(server);return c!=null&&c.settings.enabled(module);
    }
    public static TestingSettings settings(MinecraftServer server) {Context c=server==null?null:STATES.get(server);return c==null?OFF:c.settings;}
    public static String value(MinecraftServer server,String key) {
        Context c=STATES.get(server);if(c==null)return OFF.value(key);
        if(key.equals("scene_selected"))return TestingScene.selected(server);
        if(key.startsWith("vegetation_"))return ""+vegetated(server,key.substring(11));
        if(key.equals("selection"))return (c.a==null?"—":c.a.toShortString())+" → "+(c.b==null?"—":c.b.toShortString());
        return c.settings.value(key);
    }
    public static String status(MinecraftServer server) {
        Context c=STATES.get(server);return c==null?"Лаборатория недоступна":c.message+" • "+c.edits.status()+" • "+TestingResize.status(server);
    }
    public static boolean busy(MinecraftServer server) {Context c=server==null?null:STATES.get(server);return c!=null&&(c.edits.busy()||TestingResize.busy(server)||TestingImportLoader.busy(server)||TestingExportLoader.busy(server)||TestingNaturalLoader.busy(server));}
    public static boolean recoveryBlocked(MinecraftServer server) {
        Context c=server==null?null:STATES.get(server);
        return c!=null&&(c.edits.recoveryBlocked()||TestingResize.recoveryBlocked(server))
                &&!c.edits.writing()&&!TestingResize.writing(server);
    }
    private static Context authorized(MinecraftServer server,UUID player) throws IOException {
        Context c=STATES.get(server);ServerPlayer p=server.getPlayerList().getPlayer(player);
        if(c==null||!server.isSingleplayer()||server.isPublished()||p==null||!server.isSingleplayerOwner(p.nameAndId())
                ||!p.level().dimension().equals(Level.OVERWORLD))throw new IOException("Редактор доступен только владельцу тестового мира в Overworld");
        return c;
    }
    public static void action(MinecraftServer server,UUID player,String action,String value) {
        if(server==null)return;
        server.execute(()->{
            Context c=null;
            try {
                c=authorized(server,player);
                if(c.readOnlyPreview&&!Set.of("mode","teleport","toggle","all_on","all_off","group_on","group_off","parameter","setting","profile_load","diagnostics","report").contains(action))
                    throw new IOException("Предпросмотр: геометрия и определения доступны только для чтения");
                if(c.edits.documentsBusy()&&!action.equals("preview_brush"))throw new IOException("Дождитесь транзакции документов");
                if(action.equals("mode")&&(busy(server)||TestingImportLoader.active(server)||TestingNaturalLoader.active(server)))throw new IOException("Дождитесь завершения операции перед сменой режима");
                rebuildSupport(c);
                boolean destructive=Set.of("place","rotate","remove","move","copy","brush","biome","vegetation","protect","unprotect","zone_rules","spawn","undo","redo","group_rotate","resize").contains(action)||TestingScene.mutating(action);
                if(destructive&&!"EDIT".equals(c.settings.mode)&&!"CHARACTER".equals(c.settings.mode))
                    throw new IOException("Редактирование территории запрещено во время полноценного теста");
                if(destructive&&busy(server))throw new IOException("Дождитесь окончания предыдущей операции");
                if(c.edits.deferNativeAction(()->action(server,player,action,value)))return;
                ServerPlayer actorPlayer=server.getPlayerList().getPlayer(player);
                java.util.Set<com.dark.winter.online.world.IslandAccess.Permission> actorPermissions=com.dark.winter.online.server.OnlineGameHost.get(server)==null?java.util.Set.of(com.dark.winter.online.world.IslandAccess.Permission.VIEW):TestingOnlineBridge.required(action,value==null?"":value);
                try(var actor=TestingActors.enter(server,actorPlayer,actorPermissions)){
                    perform(c,actorPlayer,action,value==null?"":value);
                    if(action.equals("preview_brush"))return;
                    TestingScenarios.observe(server,action,value);
                    c.event(action,value);c.save();
                }
            } catch(Exception e) {if(c!=null){c.message=e.getMessage();c.event("rejected:"+action,e.getMessage());}LOG.warn("Testing action rejected: {} {}",action,e.toString());}
        });
    }
    static void perform(Context c,ServerPlayer p,String action,String value) throws IOException {
        var world=c.server.overworld();
        switch(action) {
            case "toggle" -> {if(!List.of(TestingSettings.MODULES).contains(value))throw new IOException("Неизвестный модуль");c.settings.modules.put(value,!c.settings.enabled(value));}
            case "all_on" -> c.settings.all(true);
            case "all_off" -> c.settings.all(false);
            case "reset" -> {TestingSettings clean=new TestingSettings();clean.mode=c.settings.mode;clean.biome=c.settings.biome;c.settings=clean;}
            case "group_on","group_off" -> {
                String[] modules=switch(value){case "visual"->new String[]{"hud","visuals","atmosphere"};case "environment"->new String[]{"weather","wind","precipitation"};case "character"->new String[]{"motion","camera","stamina"};case "survival"->new String[]{"survival"};default->throw new IOException("Неизвестная группа");};
                for(String module:modules)c.settings.modules.put(module,action.equals("group_on"));
            }
            case "parameter","setting" -> parameter(c,value);
            case "profile_save" -> TestingEdits.atomic(profile(value),GSON.toJsonTree(c.settings));
            case "profile_load" -> {
                Path file=profile(value);if(Files.size(file)>65536)throw new IOException("Профиль слишком большой");
                JsonObject data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                var known=GSON.toJsonTree(new TestingSettings()).getAsJsonObject().keySet();
                List<String> warnings=new ArrayList<>();
                for(String key:data.keySet())if(!known.contains(key))warnings.add("неизвестный "+key);
                for(String key:known)if(!data.has(key))warnings.add("значение по умолчанию "+key);
                if(data.has("modules")&&data.get("modules").isJsonObject())
                    for(String key:data.getAsJsonObject("modules").keySet())if(!List.of(TestingSettings.MODULES).contains(key))warnings.add("модуль "+key);
                TestingSettings next=GSON.fromJson(data,TestingSettings.class);next.validate();
                next.mode=c.settings.mode;next.biome=c.settings.biome;c.settings=next;
                if(!warnings.isEmpty()) {
                    c.message="Профиль применён; предупреждения: "+String.join(", ",warnings);
                    c.event("profile_warning",c.message);LOG.warn("{}",c.message);return;
                }
            }
            case "preview_brush" -> {
                String[] args=value.split("\\|");BlockPos center=position(args[0]);int radius=Math.clamp(Integer.parseInt(args[1]),1,24);
                List<BlockPos> blocked=new ArrayList<>();
                for(int x=center.getX()-radius;x<=center.getX()+radius;x++)for(int z=center.getZ()-radius;z<=center.getZ()+radius;z++) {
                    int y=center.getY();
                    if(world.getChunkSource().getGenerator() instanceof TestingIslandGenerator g)y=TestingTerrain.height(c,g,x,z);
                    BlockPos point=new BlockPos(x,y,z);
                    if(naturalProtectedAt(c,point))blocked.add(point);
                }c.previewProtected=List.copyOf(blocked);return;
            }
            case "select_a" -> c.a=position(value);
            case "select_b" -> c.b=position(value);
            case "protect","unprotect","zone_rules" -> {
                JsonObject payload=TestingWorldDocuments.captureZones(c.server);JsonObject zones=payload.getAsJsonObject("zones");
                String[] fields=value.split("\\|",-1);String name=safeName(fields[0]);
                if(action.equals("unprotect")){if(fields.length!=1)throw new IOException("Удаление зоны: только имя");zones.remove(name);}
                else {
                    if(fields.length!=1&&fields.length!=3||action.equals("zone_rules")&&fields.length!=3)throw new IOException("Зона: имя|natural true/false|objects true/false");
                    boolean natural=true,objects=true;
                    if(fields.length==3){if(!Set.of("true","false").contains(fields[1])||!Set.of("true","false").contains(fields[2]))throw new IOException("Политики зоны: true/false");natural=Boolean.parseBoolean(fields[1]);objects=Boolean.parseBoolean(fields[2]);}
                    JsonObject row;
                    if(action.equals("protect")){requireSelection(c);row=GSON.toJsonTree(zone(c.a,c.b)).getAsJsonObject();}
                    else {if(!zones.has(name))throw new IOException("Зона отсутствует");row=zones.getAsJsonObject(name).deepCopy();}
                    row.addProperty("natural",natural);row.addProperty("objects",objects);zones.add(name,row);
                }
                JsonObject documents=new JsonObject();documents.addProperty("schema",1);documents.add("zones",payload);applyDocumentBatch(c.server,action+" "+name,documents);
            }
            case "undo" -> c.edits.undo();
            case "redo" -> c.edits.redo();
            case "place" -> {
                String[] v=value.split("\\|");BlockPos pos=position(v[1]);BlockState state=block(v[0]);
                if(world.isOutsideBuildHeight(pos)||!world.hasChunkAt(pos))throw new IOException("Размещение разрешено только в загруженной области редактора");
                if(protectedAt(c,pos)||!world.getBlockState(pos).canBeReplaced())throw new IOException("Место занято или защищено");
                List<TestingEdits.Change> changes=new ArrayList<>();changes.add(c.edits.change(pos,state,category(state)));
                if(state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF))
                    throw new IOException("Многоблочный объект размещайте обычным предметом из каталога");
                if(state.getBlock() instanceof com.dark.winter.block.FurnitureBlock furniture&&furniture.definition().parts().size()>1)throw new IOException("Многоблочную мебель размещайте обычным предметом из каталога");
                if(state.getBlock() instanceof BedBlock)throw new IOException("Кровать размещайте обычным предметом");
                c.edits.submit("Разместить "+v[0],changes);
            }
            case "rotate","remove" -> {
                BlockPos pos=position(value);if(zoneProtected(c,pos))throw new IOException("Объект находится в ручной защищённой зоне");
                BlockState before=world.getBlockState(pos);
                TestingStructures.validate(world,Set.of(pos));
                if(before.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)||before.getBlock() instanceof BedBlock)
                    throw new IOException("Выделите целую многоблочную конструкцию и используйте групповое перемещение");
                TestingEdits.Cell old=c.edits.read(pos);
                BlockState next=action.equals("remove")?Blocks.AIR.defaultBlockState():before.rotate(Rotation.CLOCKWISE_90);
                c.edits.submit(action,List.of(new TestingEdits.Change(pos,old,new TestingEdits.Cell(next,action.equals("remove")?null:old.entity(),old.origin()==null?"building":old.origin()))));
            }
            case "move","copy" -> transform(c,value,action.equals("copy"));
            case "group_rotate" -> {if(value.contains("|")){String[] corners=value.split("\\|");c.a=position(corners[0]);c.b=position(corners[1]);}rotateGroup(c);}
            case "brush" -> TestingTerrain.brush(c,value);
            case "resize" -> {String[] args=value.split("\\|");TestingResize.begin(c.server,Integer.parseInt(args[0]),args[1],Double.parseDouble(args[2]));}
            case "biome" -> TestingNaturalLoader.queue(c.server,"biome",value);
            case "vegetation" -> TestingNaturalLoader.queue(c.server,"vegetation",value);
            case "camera" -> {
                if(c.settings.mode.startsWith("TEST"))throw new IOException("Камера-дрон доступна только в редакторе");
                p.setGameMode(value.equals("drone")?GameType.SPECTATOR:GameType.CREATIVE);
            }
            case "mode" -> {
                if(c.readOnlyPreview&&!value.equals("TEST_REVIEW"))throw new IOException("Выйдите из предпросмотра через панель версий");
                if(c.settings.mode.startsWith("TEST")&&!value.startsWith("TEST"))throw new IOException("Завершите полноценный тест через экран испытания");
                if(!Set.of("EDIT","CHARACTER","TEST_RUNNING","TEST_PAUSED","TEST_REVIEW").contains(value))throw new IOException("Неизвестный режим");
                c.settings.mode=value;p.setGameMode(c.readOnlyPreview?GameType.SPECTATOR:value.equals("EDIT")?GameType.CREATIVE:GameType.SURVIVAL);
            }
            case "teleport" -> {
                String[] v=value.split(",");if(v.length<5)throw new IOException("Нужны x,y,z,yaw,pitch");
                double x=Double.parseDouble(v[0]),y=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]);
                if(!Double.isFinite(x+y+z)||Math.abs(x)>30000000||Math.abs(z)>30000000||y<world.getMinY()||y>world.getMaxY())throw new IOException("Недопустимые координаты");
                p.teleportTo(world,x,y,z,Set.of(),Float.parseFloat(v[3]),Float.parseFloat(v[4]),false);
            }
            case "spawn" -> {
                JsonObject target=new JsonObject();target.addProperty("schema",1);target.add("data",net.minecraft.world.level.storage.LevelData.RespawnData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,net.minecraft.world.level.storage.LevelData.RespawnData.of(p.level().dimension(),p.blockPosition(),p.getYRot(),p.getXRot())).getOrThrow());
                JsonObject documents=new JsonObject();documents.addProperty("schema",1);documents.add("spawn",target);applyDocumentBatch(c.server,"Точка появления",documents);
            }
            case "give" -> {if(!"EDIT".equals(c.settings.mode))throw new IOException("Каталог предметов доступен в EDIT");
                Identifier id=Identifier.parse(value);if(!BuiltInRegistries.ITEM.containsKey(id))throw new IOException("Неизвестный предмет");
                p.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.getValue(id)));}
            case "diagnostics" -> c.settings.diagnostics=!c.settings.diagnostics;
            case "report","scenario_save","scenario_run","scenario_stop","scenario_event" -> TestingScenarios.action(c.server,action,value);
            default -> {if(TestingScene.handles(action))TestingScene.action(c,p,action,value);else if(TestingScenarios.handles(action))TestingScenarios.action(c.server,action,value);else throw new IOException("Неизвестное действие: "+action);}
        }
        c.message=action+": "+value;
    }
    private static void parameter(Context c,String value) throws IOException {
        String[] p=value.contains("=")?value.split("=",2):value.split("\\|",2);
        if(p.length!=2)throw new IOException("Нужны имя и значение");TestingSettings s=GSON.fromJson(GSON.toJson(c.settings),TestingSettings.class);
        switch(p[0]) {
            case "radius" -> s.radius=Integer.parseInt(p[1]);case "strength" -> s.strength=Integer.parseInt(p[1]);
            case "density" -> s.density=Integer.parseInt(p[1]);case "brush" -> s.brush=p[1];
            case "brushShape" -> s.brushShape=p[1];case "grassSpecies" -> s.grassSpecies=p[1];case "bushSpecies" -> s.bushSpecies=p[1];case "treeSpecies" -> s.treeSpecies=p[1];
            case "weather" -> s.weather=p[1];case "windSpeed" -> s.windSpeed=Float.parseFloat(p[1]);
            case "temperature" -> s.temperature=Float.parseFloat(p[1]);case "flySpeed" -> s.flySpeed=Float.parseFloat(p[1]);
            case "windDirection" -> s.windDirection=Float.parseFloat(p[1]);case "filmStrength" -> s.filmStrength=Float.parseFloat(p[1]);
            case "hazeDensity" -> s.hazeDensity=Float.parseFloat(p[1]);case "shadowStrength" -> s.shadowStrength=Float.parseFloat(p[1]);
            case "staminaCapacity"->s.staminaCapacity=Float.parseFloat(p[1]);case "staminaSprintDrain"->s.staminaSprintDrain=Float.parseFloat(p[1]);
            case "staminaWalkRegen"->s.staminaWalkRegen=Float.parseFloat(p[1]);case "staminaIdleRegen"->s.staminaIdleRegen=Float.parseFloat(p[1]);
            case "staminaJumpCost"->s.staminaJumpCost=Float.parseFloat(p[1]);case "staminaSprintJumpCost"->s.staminaSprintJumpCost=Float.parseFloat(p[1]);
            case "motionWalkSpeed"->s.motionWalkSpeed=Double.parseDouble(p[1]);case "motionCameraWeight"->s.motionCameraWeight=Double.parseDouble(p[1]);case "motionTurnWeight"->s.motionTurnWeight=Double.parseDouble(p[1]);
            case "diagnostics" -> {if(!Set.of("true","false").contains(p[1]))throw new IOException("Диагностика: true/false");s.diagnostics=Boolean.parseBoolean(p[1]);}
            default -> throw new IOException("Неизвестный параметр: "+p[0]);
        } s.validate();c.settings=s;
        if(p[0].equals("flySpeed"))for(ServerPlayer player:c.server.getPlayerList().getPlayers()){player.getAbilities().setFlyingSpeed(s.flySpeed);player.onUpdateAbilities();}
    }
    private static Path profile(String name) throws IOException {return FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/profiles").resolve(safeName(name)+".json");}
    public static String safeName(String name) throws IOException {
        if(name==null||name.isBlank()||name.length()>80||name.contains("..")||name.matches(".*[\\\\/:*?\"<>|].*"))throw new IOException("Недопустимое имя");
        return name;
    }
    static BlockPos position(String value) {
        String[] p=value.replace('|',',').split(",");return new BlockPos(Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim()),Integer.parseInt(p[2].trim()));
    }
    static Zone zone(BlockPos a,BlockPos b) {return new Zone(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()),Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ()));}
    static BlockState block(String id) throws IOException {
        Identifier key=Identifier.parse(id);if(!BuiltInRegistries.BLOCK.containsKey(key))throw new IOException("Неизвестный блок: "+id);
        return BuiltInRegistries.BLOCK.getValue(key).defaultBlockState();
    }
    static boolean zoneProtected(Context c,BlockPos pos) {return c.zones.entrySet().stream().anyMatch(e->c.zoneRules.getOrDefault(e.getKey(),new ZoneRule(true,true)).objects()&&e.getValue().contains(pos));}
    static boolean naturalZoneProtected(Context c,BlockPos pos) {return c.zones.entrySet().stream().anyMatch(e->c.zoneRules.getOrDefault(e.getKey(),new ZoneRule(true,true)).natural()&&e.getValue().contains(pos));}
    static boolean naturalZoneColumn(Context c,int x,int z) {return c.zones.entrySet().stream().anyMatch(e->{Zone bounds=e.getValue();return c.zoneRules.getOrDefault(e.getKey(),new ZoneRule(true,true)).natural()&&x>=bounds.x0()&&x<=bounds.x1()&&z>=bounds.z0()&&z<=bounds.z1();});}
    private static void support(Context c,int x,int y,int z,int top) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            long key=TestingTerrain.column(x+dx,z+dz);Support previous=c.support.get(key);
            c.support.put(key,previous==null?new Support(y-16,top):new Support(Math.min(previous.min,y-16),Math.max(previous.max,top)));
        }
    }
    static void rebuildSupport(Context c) {
        c.support.clear();
        for(var entry:c.edits.origins.entrySet())if("building".equals(entry.getValue())||"object".equals(entry.getValue())) {
            BlockPos p=BlockPos.of(entry.getKey());if(c.server.overworld().hasChunkAt(p)&&c.server.overworld().getBlockState(p).isAir())continue;support(c,p.getX(),p.getY(),p.getZ(),p.getY());
        }
        for(var entity:c.server.overworld().getAllEntities())if(TestingEntities.supported(entity)) {
            BlockPos p=entity.blockPosition();support(c,p.getX(),p.getY()-1,p.getZ(),(int)Math.ceil(entity.getBoundingBox().maxY));
        }
    }
    static boolean protectedAt(Context c,BlockPos pos) {return zoneProtected(c,pos)||supportProtected(c,pos);}
    static boolean naturalProtectedAt(Context c,BlockPos pos) {return naturalZoneProtected(c,pos)||supportProtected(c,pos);}
    private static boolean supportProtected(Context c,BlockPos pos) {
        Support support=c.support.get(TestingTerrain.column(pos.getX(),pos.getZ()));
        return support!=null&&pos.getY()>=support.min&&pos.getY()<=support.max;
    }
    private static net.minecraft.server.level.ServerLevel worldFor(Context c){return c.server.overworld();}
    static void requireSelection(Context c) throws IOException {if(c.a==null||c.b==null)throw new IOException("Сначала выделите углы A и B");}
    private static void transform(Context c,String value,boolean copy) throws IOException {
        String[] v=value.split("\\|");BlockPos a=position(v[0]),b=position(v[1]),delta=position(v[2]);
        Zone z=zone(a,b);long count=(long)(z.x1-z.x0+1)*(z.y1-z.y0+1)*(z.z1-z.z0+1);
        if(count>8192)throw new IOException("Группа больше 8192 блоков");
        Map<BlockPos,TestingEdits.Cell> source=new LinkedHashMap<>(),dest=new LinkedHashMap<>();
        for(BlockPos p:BlockPos.betweenClosed(z.x0,z.y0,z.z0,z.x1,z.y1,z.z1)) {
            if(zoneProtected(c,p))throw new IOException("Группа пересекает защищённую зону");
            TestingEdits.Cell cell=c.edits.read(p);
            if(!cell.state().isAir())source.put(p.immutable(),cell);
        }
        TestingStructures.validate(worldFor(c),source.keySet());
        for(var e:source.entrySet()) {
            BlockPos target=e.getKey().offset(delta);
            if(zoneProtected(c,target))throw new IOException("Цель пересекает защищённую зону");
            if((copy||!source.containsKey(target))&&!c.server.overworld().getBlockState(target).canBeReplaced())throw new IOException("Цель занята: "+target);
            TestingEdits.Cell old=e.getValue();dest.put(target,new TestingEdits.Cell(old.state(),TestingStructures.relocate(old.entity(),pos->pos.offset(delta)),old.origin()==null?category(old.state()):old.origin()));
        }
        LinkedHashSet<BlockPos> positions=new LinkedHashSet<>();if(!copy)positions.addAll(source.keySet());positions.addAll(dest.keySet());
        List<TestingEdits.Change> changes=new ArrayList<>();
        for(BlockPos pos:positions) {
            TestingEdits.Cell after=dest.getOrDefault(pos,new TestingEdits.Cell(Blocks.AIR.defaultBlockState(),null,"building"));
            changes.add(new TestingEdits.Change(pos,c.edits.read(pos),after));
        }
        var entities=selectedEntities(c,z,point->point.add(delta.getX(),delta.getY(),delta.getZ()),0,copy);
        Map<BlockPos,BlockPos> mapping=new LinkedHashMap<>();source.keySet().forEach(pos->mapping.put(pos,pos.offset(delta)));
        c.edits.submitScene(copy?"Копировать группу":"Переместить группу",changes,entities,TestingScene.relocated(c.server,mapping,copy,changes,entities));
    }
    private static void rotateGroup(Context c) throws IOException {
        requireSelection(c);Zone z=zone(c.a,c.b);
        if((long)(z.x1-z.x0+1)*(z.y1-z.y0+1)*(z.z1-z.z0+1)>8192)throw new IOException("Группа больше 8192 блоков");
        Map<BlockPos,TestingEdits.Cell> dest=new LinkedHashMap<>();Set<BlockPos> source=new LinkedHashSet<>();
        for(BlockPos pos:BlockPos.betweenClosed(z.x0,z.y0,z.z0,z.x1,z.y1,z.z1)) if(!c.server.overworld().getBlockState(pos).isAir())source.add(pos.immutable());
        TestingStructures.validate(worldFor(c),source);
        for(BlockPos pos:source) {
            BlockPos t=new BlockPos(c.a.getX()-(pos.getZ()-c.a.getZ()),pos.getY(),c.a.getZ()+(pos.getX()-c.a.getX()));
            if(zoneProtected(c,pos)||zoneProtected(c,t)||(!source.contains(t)&&!c.server.overworld().getBlockState(t).canBeReplaced()))throw new IOException("Конфликт поворота: "+t);
            TestingEdits.Cell old=c.edits.read(pos);dest.put(t,new TestingEdits.Cell(old.state().rotate(Rotation.CLOCKWISE_90),TestingStructures.relocate(old.entity(),point->new BlockPos(c.a.getX()-(point.getZ()-c.a.getZ()),point.getY(),c.a.getZ()+(point.getX()-c.a.getX()))),old.origin()==null?category(old.state()):old.origin()));
        }
        Set<BlockPos> all=new LinkedHashSet<>(source);all.addAll(dest.keySet());List<TestingEdits.Change> changes=new ArrayList<>();
        for(BlockPos pos:all)changes.add(new TestingEdits.Change(pos,c.edits.read(pos),dest.getOrDefault(pos,new TestingEdits.Cell(Blocks.AIR.defaultBlockState(),null,"building"))));
        var entities=selectedEntities(c,z,point->new net.minecraft.world.phys.Vec3(c.a.getX()+.5-(point.z-(c.a.getZ()+.5)),point.y,c.a.getZ()+.5+(point.x-(c.a.getX()+.5))),90,false);
        Map<BlockPos,BlockPos> mapping=new LinkedHashMap<>();source.forEach(pos->mapping.put(pos,new BlockPos(c.a.getX()-(pos.getZ()-c.a.getZ()),pos.getY(),c.a.getZ()+(pos.getX()-c.a.getX()))));
        c.edits.submitScene("Повернуть группу",changes,entities,TestingScene.relocated(c.server,mapping,false,changes,entities));
    }
    private static boolean entityProtected(Context c,net.minecraft.nbt.CompoundTag tag)throws IOException {
        if(tag==null)return false;var box=TestingEntities.bounds(c.server.overworld(),tag);
        return c.zones.values().stream().anyMatch(zone->box.intersects(new net.minecraft.world.phys.AABB(zone.x0,zone.y0,zone.z0,zone.x1+1d,zone.y1+1d,zone.z1+1d)));
    }
    private static List<TestingEntities.Change> selectedEntities(Context c,Zone selection,
            java.util.function.UnaryOperator<net.minecraft.world.phys.Vec3> transform,float yaw,boolean copy) throws IOException {
        var box=new net.minecraft.world.phys.AABB(selection.x0,selection.y0,selection.z0,selection.x1+1d,selection.y1+1d,selection.z1+1d);
        List<TestingEntities.Change> changes=new ArrayList<>();
        for(var entity:c.server.overworld().getAllEntities()) {
            if(entity instanceof net.minecraft.world.entity.player.Player||!entity.getBoundingBox().intersects(box))continue;
            var bounds=entity.getBoundingBox();
            if(bounds.minX<box.minX||bounds.maxX>box.maxX||bounds.minY<box.minY||bounds.maxY>box.maxY||bounds.minZ<box.minZ||bounds.maxZ>box.maxZ)
                throw new IOException("Выделите NPC целиком");
            if(!TestingEntities.supported(entity))throw new IOException("Эта сущность пока переносится только с полным островом: "+entity.getType());
            var before=TestingEntities.capture(entity);UUID id=copy?UUID.randomUUID():entity.getUUID();
            var after=TestingEntities.copyAt(before,transform.apply(entity.position()),yaw,id);
            if(entityProtected(c,before)||entityProtected(c,after))throw new IOException("NPC находится в защищённой зоне");
            changes.add(new TestingEntities.Change(id,copy?null:before,after));
            if(changes.size()>TestingEntities.LIMIT)throw new IOException("Не больше 32 NPC в операции");
        }return changes;
    }
    private static String category(BlockState state){return state.getBlock() instanceof com.dark.winter.block.FurnitureBlock?"object":"building";}
    public static void authored(net.minecraft.world.level.Level level,BlockPos pos) {
        if(level.isClientSide()||level.getServer()==null)return;Context c=STATES.get(level.getServer());if(c==null)return;
        String previous=c.edits.origins.get(pos.asLong());
        c.edits.origins.put(pos.asLong(),("building".equals(previous)||"object".equals(previous))?previous:category(level.getBlockState(pos)));c.event("author",pos.toShortString());
    }
    public static void applyBatch(MinecraftServer server,String label,Map<BlockPos,BlockState> states,
            Map<BlockPos,net.minecraft.nbt.CompoundTag> entities,String origin) throws IOException {
        applyBatch(server,label,states,entities,origin,List.of());
    }
    public static void applyBatch(MinecraftServer server,String label,Map<BlockPos,BlockState> states,
            Map<BlockPos,net.minecraft.nbt.CompoundTag> entities,String origin,List<TestingEntities.Change> entityChanges) throws IOException {
        applyBatch(server,label,states,entities,origin,entityChanges,null);
    }
    public static void applyBatch(MinecraftServer server,String label,Map<BlockPos,BlockState> states,
            Map<BlockPos,net.minecraft.nbt.CompoundTag> entities,String origin,List<TestingEntities.Change> entityChanges,JsonObject scenarioDocuments) throws IOException {
        applyBatch(server,label,states,entities,origin,entityChanges,scenarioDocuments,null);
    }
    public static void applyBatch(MinecraftServer server,String label,Map<BlockPos,BlockState> states,
            Map<BlockPos,net.minecraft.nbt.CompoundTag> entities,String origin,List<TestingEntities.Change> entityChanges,
            JsonObject scenarioDocuments,String sceneAfter) throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!isTesting(server)||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||c.settings.mode.startsWith("TEST"))throw new IOException("No server authority");
        TestingStructures.validate(states,entities);
        Map<BlockPos,String> prefabOrigins=sceneAfter==null?Map.of():TestingScene.prefabOrigins(server,sceneAfter,states.keySet());
        List<TestingEdits.Change> changes=new ArrayList<>();
        for(var entry:states.entrySet()) {
            BlockPos pos=entry.getKey();String entryOrigin=prefabOrigins.getOrDefault(pos,origin);
            if(zoneProtected(c,pos))throw new IOException("Protected region at "+pos);
            TestingEdits.Cell old=c.edits.read(pos);
            if(!old.state().canBeReplaced()&&!entryOrigin.equals(old.origin()))throw new IOException("Occupied destination at "+pos);
            changes.add(new TestingEdits.Change(pos,old,new TestingEdits.Cell(entry.getValue(),entities.get(pos),entryOrigin)));
        }
        if(scenarioDocuments==null&&sceneAfter==null)c.edits.submit(label,changes,entityChanges);
        else c.edits.submitSceneDocuments(label,changes,entityChanges,sceneAfter,scenarioDocuments);
    }
    public static void applyDocumentBatch(MinecraftServer server,String label,JsonObject documents)throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||c.settings.mode.startsWith("TEST"))
            throw new IOException("Документы изменяются только в авторском редакторе");
        c.edits.submitDocuments(label,List.of(),null,null,List.of(),TestingDocuments.store(server,documents));
    }
    public static long cleanDocumentHistory(MinecraftServer server,boolean confirmed)throws IOException {
        Context c=STATES.get(server);if(c==null||!server.isSameThread()||busy(server))throw new IOException("Дождитесь окончания операции");
        return TestingDocuments.collectUnused(server,c.edits.documentRefs(),confirmed);
    }
    public static void exportCategory(MinecraftServer server,String category,Path file) throws IOException {
        Context c=STATES.get(server);if(c==null||!server.isSameThread())throw new IOException("No server context");
        if(Set.of("settings","scenarios").contains(category)) {
            JsonObject root=TestingEdits.encode(new TestingEdits.Operation("category:"+category,List.of()));
            root.addProperty("category",category);
            JsonObject document=TestingDocuments.capture(server,Set.of(category)).getAsJsonObject(category);
            if(category.equals("scenarios")){
                Path source=server.getWorldPath(LevelResource.ROOT);
                document=TestingScenarioDependencies.portableDocuments(source,document);
                root.addProperty("dependencyRoot",TestingScenarioDependencies.dependencyRoot(source));
            }
            root.add("document",document);
            TestingEdits.atomic(file,root);return;
        }
        List<TestingEdits.Change> changes=new ArrayList<>();
        for(var e:c.edits.origins.entrySet()) if(!category.equals("terrain")&&category.equals(e.getValue())) {
            BlockPos pos=BlockPos.of(e.getKey());TestingEdits.Cell cell=c.edits.read(pos);
            if(category.equals("surface")) {
                if(cell.state().is(Blocks.WATER))continue;
                if(cell.state().isAir()&&server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator generator
                        &&pos.getY()!=TestingTerrain.height(c,generator,pos.getX(),pos.getZ())+1)continue;
            }
            changes.add(new TestingEdits.Change(pos,cell,cell));
        }
        JsonObject root=TestingEdits.encode(new TestingEdits.Operation("category:"+category,changes));
        root.addProperty("category",category);
        if(category.equals("terrain"))root.add("terrain",TestingResize.captureTerrain(server));
        if(category.equals("surface"))root.add("surfaceGeometry",TestingResize.captureTerrain(server));
        if(category.equals("object")) {
            JsonArray npc=new JsonArray();int count=0;
            for(var entity:server.overworld().getAllEntities())if(TestingEntities.supported(entity)) {
                if(++count>TestingEntities.LIMIT)throw new IOException("Выборочный перенос поддерживает не больше 32 NPC");
                npc.add(TestingEntities.encode(new TestingEntities.Change(entity.getUUID(),null,TestingEntities.capture(entity))));
            }
            root.add("npc",npc);
        }
        if(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator g) {
            root.addProperty("radius",g.radius());root.addProperty("shape",g.shape());root.addProperty("hills",g.hills());root.addProperty("terrainSeed",g.terrainSeed());
        }
        root.addProperty("biome",c.settings.biome);
        TestingEdits.atomic(file,root);
    }
    public static void importCategory(MinecraftServer server,String category,Path file) throws IOException {
        importCategories(server,Map.of(category,file));
    }
    public static void importCategories(MinecraftServer server,Map<String,Path> files) throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||c.readOnlyPreview||c.settings.mode.startsWith("TEST"))
            throw new IOException("Импорт разрешён только в авторском редакторе");
        if(files.containsKey("terrain")){importTerrainCategories(server,files);return;}
        importStandardCategories(server,files,false,null,null);
    }
    private static final Set<String> IMPORT_CATEGORIES=Set.of("terrain","surface","grass","bushes","trees","building","object","settings","scenarios");
    private static JsonObject importDocument(Path file,String category)throws IOException {
        if(!Files.isRegularFile(file)||Files.size(file)>128L*1024*1024)throw new IOException("Недопустимый размер категории "+category);
        try {
            JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var data=TestingEdits.decode(root);
            if(!data.name().equals("category:"+category)||!data.entities().isEmpty()||data.resizeId()!=null||data.sceneBefore()!=null||data.sceneAfter()!=null||data.documentsBefore()!=null||data.documentsAfter()!=null)
                throw new IOException("Файл не является самостоятельной категорией "+category);
            return root;
        }catch(RuntimeException error){throw new IOException("Повреждён файл категории "+category,error);}
    }
    private static String importFileSha(Path file)throws IOException {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int read;(read=input.read(bytes))>=0;)if(read>0)digest.update(bytes,0,read);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private record TerrainImport(TestingResize.TerrainData geometry,String biome,Map<String,Path> others,
                                 Map<String,String> hashes,PlannedImport planned) {}
    private static TerrainImport planTerrainCategories(MinecraftServer server,Map<String,Path> files)throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!isTesting(server)||c.edits.busy()||TestingResize.busy(server))throw new IOException("Нет доступного контекста редактора");
        if(files.isEmpty()||!IMPORT_CATEGORIES.containsAll(files.keySet()))throw new IOException("Неизвестная категория импорта");
        Map<String,String> hashes=new LinkedHashMap<>();for(var entry:files.entrySet()){importDocument(entry.getValue(),entry.getKey());hashes.put(entry.getKey(),importFileSha(entry.getValue()));}
        JsonObject terrain=importDocument(files.get("terrain"),"terrain");
        if(!terrain.has("terrain")||!terrain.getAsJsonArray("changes").isEmpty())throw new IOException("Старый разреженный формат рельефа не восстанавливает остров полностью; экспортируйте полный рельеф заново");
        var geometry=TestingResize.validateTerrain(server,terrain.getAsJsonObject("terrain"));
        String biome=null;
        if(files.containsKey("surface")) {
            JsonObject surface=importDocument(files.get("surface"),"surface");
            if(!surface.has("biome"))throw new IOException("В исходном покрытии нет биома");
            biome=surface.get("biome").getAsString();
            TestingResize.attachCoverage(server,geometry,surface);
            server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,Identifier.parse(biome.contains(":")?biome:"minecraft:"+biome)));
            for(var row:TestingEdits.decode(surface).changes()) {
                if(!"surface".equals(row.after().origin())||row.after().entity()!=null||row.after().state().hasBlockEntity()
                        ||Math.abs((long)row.pos().getX())>geometry.extent||Math.abs((long)row.pos().getZ())>geometry.extent)
                    throw new IOException("Повреждённая или несовместимая категория покрытия");
            }
        }
        Map<String,Path> others=new LinkedHashMap<>(files);others.remove("terrain");others.remove("surface");
        PlannedImport planned=planStandardCategories(server,others,geometry);
        // Verify all validated inputs again before creating a geometry job or continuation.
        for(var entry:files.entrySet())if(!hashes.get(entry.getKey()).equals(importFileSha(entry.getValue())))throw new IOException("Файл категории изменился во время проверки");
        return new TerrainImport(geometry,biome,Map.copyOf(others),Map.copyOf(hashes),planned);
    }
    private static void importTerrainCategories(MinecraftServer server,Map<String,Path> files)throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!isTesting(server)||busy(server))throw new IOException("Нет доступного контекста редактора");
        TerrainImport plan=planTerrainCategories(server,files);
        var geometry=plan.geometry();String biome=plan.biome();Map<String,Path> others=plan.others();Map<String,String> hashes=plan.hashes();
        JsonObject continuation=null;
        if(!others.isEmpty()) {
            continuation=new JsonObject();continuation.addProperty("schema",2);continuation.addProperty("phase","WAIT_GEOMETRY");
            continuation.addProperty("token",UUID.randomUUID().toString());JsonObject entries=new JsonObject();
            Path transferRoot=TestingArchive.root().resolve("transfers").toAbsolutePath().normalize();
            for(var entry:others.entrySet()) {
                Path path=entry.getValue().toAbsolutePath().normalize();
                if(!path.getParent().equals(transferRoot))throw new IOException("Составной импорт использует проверенные файлы в библиотеке transfers");
                JsonObject file=new JsonObject();file.addProperty("path",path.toString());file.addProperty("sha256",hashes.get(entry.getKey()));entries.add(entry.getKey(),file);
            }
            continuation.add("files",entries);
            continuation.addProperty("dependencySignature",TestingImportLoader.dependencySignature(server));
            continuation.addProperty("restoreFrozen",TestingImportLoader.restoreFrozen(server));
        }
        TestingResize.beginTerrainRestore(server,geometry,biome,continuation);
        if(continuation!=null)c.event("import_two_stages","Рельеф и остальные категории имеют отдельные шаги Undo");
    }
    /** A durable second WAL stage; never silently drops other selected categories. */
    static void resumeTerrainImports(MinecraftServer server) {
        Context c=STATES.get(server);if(c==null||busy(server))return;
        Path file=c.directory.resolve("terrain-followup.json");if(!Files.isRegularFile(file))return;
        JsonObject work=null;
        try {
            if(Files.size(file)>16384)throw new IOException("Слишком большая очередь импорта");
            work=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(work.get("schema").getAsInt()!=2)throw new IOException("Неизвестная очередь импорта");
            String phase=work.get("phase").getAsString();if(phase.equals("BLOCKED"))return;
            UUID token=UUID.fromString(work.get("token").getAsString());String label="Выборочный импорт "+token;
            if(phase.equals("STARTING")||phase.equals("APPLYING")) {
                if(!committedImport(c,label))throw new IOException("Второй этап не был зафиксирован; резервная версия первого этапа сохранена");
                Files.delete(file);c.message="Импорт завершён в два этапа. Undo категорий и рельефа выполняется отдельно";c.event("import_two_stages_complete",token.toString());return;
            }
            if(!phase.equals("WAIT_GEOMETRY"))throw new IOException("Неизвестный этап импорта");
            String geometry=work.get("resizeId").getAsString();
            if(!c.edits.resizeApplied(geometry))throw new IOException("Геометрия не завершилась; остальные категории не применялись");
            Path transferRoot=TestingArchive.root().resolve("transfers").toAbsolutePath().normalize();Map<String,Path> files=new LinkedHashMap<>();Map<String,String> expectedFiles=new LinkedHashMap<>();
            for(var entry:work.getAsJsonObject("files").entrySet()) {
                if(!Set.of("grass","bushes","trees","building","object","settings","scenarios").contains(entry.getKey()))throw new IOException("Недопустимая следующая категория");
                JsonObject value=entry.getValue().getAsJsonObject();Path source=Path.of(value.get("path").getAsString()).toAbsolutePath().normalize();
                String sha=value.get("sha256").getAsString();
                if(!source.getParent().equals(transferRoot)||Files.isSymbolicLink(source)||!sha.matches("[0-9a-f]{64}")||!Files.isRegularFile(source)||!sha.equals(importFileSha(source)))throw new IOException("Проверенный файл категории изменился или исчез");
                files.put(entry.getKey(),source);expectedFiles.put(entry.getKey(),sha);
            }
            if(files.isEmpty())throw new IOException("Пустой второй этап");
            if(!TestingImportLoader.secondStageReady(server,label)){
                TestingImportLoader.prepareSecondStage(server,files,expectedFiles,work.has("dependencySignature")?work.get("dependencySignature").getAsString():"",label,work.has("restoreFrozen")&&work.get("restoreFrozen").getAsBoolean());
                c.message="Подготовка второго этапа; исходные SHA проверяются в фоне";return;
            }
            work.addProperty("phase","STARTING");TestingEdits.atomic(file,work);
            importStandardCategories(server,files,false,null,label);
            if(!c.edits.busy()){Files.delete(file);c.message="Импорт завершён: остальные категории уже соответствовали исходным данным";return;}
            work.addProperty("phase","APPLYING");TestingEdits.atomic(file,work);c.message="Второй этап импорта категорий; отдельный шаг Undo";
        }catch(Exception error) {
            c.message="Составной импорт остановлен: "+error.getMessage();c.event("import_two_stages_blocked",error.toString());
            if(work!=null)try{work.addProperty("phase","BLOCKED");work.addProperty("error",error.getMessage());TestingEdits.atomic(file,work);}catch(IOException ignored){}
            LOG.error(c.message,error);
        }
    }
    private static boolean committedImport(Context c,String label)throws IOException {
        Path history=c.directory.resolve("undo.json");if(!Files.isRegularFile(history))return false;
        try(var reader=new com.google.gson.stream.JsonReader(Files.newBufferedReader(history))) {
            reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();if(!key.equals("undo")&&!key.equals("redo")){reader.skipValue();continue;}
                reader.beginArray();while(reader.hasNext()) {
                    reader.beginObject();while(reader.hasNext()){String name=reader.nextName();if(name.equals("name")){if(reader.nextString().equals(label))return true;}else reader.skipValue();}
                    reader.endObject();
                }reader.endArray();
            }reader.endObject();return false;
        }
    }
    /** Read-only, deterministic native plan. No document store, WAL, history or world setter is invoked here. */
    public record CategoryPreview(List<String> lines,List<String> conflicts,String signature) {
        public CategoryPreview {lines=List.copyOf(lines);conflicts=List.copyOf(conflicts);}
    }
    public static CategoryPreview previewCategories(MinecraftServer server,Map<String,Path> files)throws IOException {
        Context c=STATES.get(server);
        if(c==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||c.readOnlyPreview
                ||!isTesting(server)||!Set.of("EDIT","CHARACTER").contains(c.settings.mode)||c.edits.busy()||TestingResize.busy(server))
            throw new IOException("Предпросмотр доступен только в свободном авторском редакторе");
        if(files==null||files.isEmpty()||!IMPORT_CATEGORIES.containsAll(files.keySet()))throw new IOException("Неизвестные категории предпросмотра");
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new IOException(error);}
        List<String> lines=new ArrayList<>(),conflicts=new ArrayList<>();
        previewHash(digest,server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        Map<String,String> sourceHashes=new TreeMap<>();
        for(var entry:new TreeMap<>(files).entrySet()){
            String sourceHash=importFileSha(entry.getValue());sourceHashes.put(entry.getKey(),sourceHash);
            previewHash(digest,entry.getKey());previewHash(digest,sourceHash);
        }
        rebuildSupport(c);
        previewHash(digest,GSON.toJson(previewCanonical(GSON.toJsonTree(c.zones))));
        previewHash(digest,GSON.toJson(previewCanonical(GSON.toJsonTree(c.zoneRules))));
        previewHash(digest,GSON.toJson(previewCanonical(GSON.toJsonTree(c.support))));
        previewHash(digest,GSON.toJson(previewCanonical(GSON.toJsonTree(c.heights))));
        previewHash(digest,GSON.toJson(previewCanonical(GSON.toJsonTree(c.edits.origins))));
        previewHash(digest,c.settings.biome);
        lines.add("Категории: "+String.join(", ",new TreeSet<>(files.keySet())));
        try {
            TestingResize.TerrainData geometry=null;PlannedImport plan;
            if(files.containsKey("terrain")){
                previewHash(digest,TestingResize.validateTerrainBackup(server));
                TerrainImport combined=planTerrainCategories(server,files);geometry=combined.geometry();plan=combined.planned();
                previewTerrain(c,geometry,digest,lines);
                if(files.containsKey("surface")){
                    previewHash(digest,GSON.toJson(previewCanonical(TestingBiomeDocuments.capture(server))));
                    lines.add("Покрытие: материал и биом источника привязаны к восстанавливаемым высотам.");
                }
                if(!combined.others().isEmpty())lines.add("Рельеф и прочие категории: 2 этапа Undo; второй этап повторяет проверки после геометрии.");
            }else plan=planStandardCategories(server,files,null);
            for(var dependency:plan.dependencies())previewHash(digest,dependency.signature());
            if(!plan.dependencies().isEmpty())lines.add("Зависимости сценариев: сохранённые состояния мира и точные курсоры проверены; будут опубликованы перед транзакцией.");
            var operation=new TestingEdits.Operation("category-preview",plan.changes(),plan.biomeBefore(),plan.biomeAfter(),null,plan.entities(),"00000000-0000-0000-0000-000000000000");
            previewHash(digest,GSON.toJson(previewCanonical(TestingEdits.encode(operation))));
            long added=plan.changes().stream().filter(change->change.before().state().isAir()&&!change.after().state().isAir()).count();
            long removed=plan.changes().stream().filter(change->!change.before().state().isAir()&&change.after().state().isAir()).count();
            long containers=plan.changes().stream().filter(change->change.before().state().hasBlockEntity()||change.after().state().hasBlockEntity()).count();
            lines.add((geometry==null?"Точный план":"Предварительный план прочих категорий")+": "+plan.changes().size()+" ячеек; добавить "+added+", удалить "+removed+", прочие "+(plan.changes().size()-added-removed)+".");
            lines.add("Ячеек контейнеров: "+containers+"; NPC: "+plan.entities().size()+" (добавить "+plan.entities().stream().filter(change->change.before()==null).count()+", удалить "+plan.entities().stream().filter(change->change.after()==null).count()+").");
            if(plan.documents()!=null){
                Set<String> fields=new TreeSet<>(plan.documents().keySet());fields.remove("schema");
                JsonObject current=TestingDocuments.capture(server,fields);
                previewHash(digest,GSON.toJson(previewCanonical(current)));previewHash(digest,GSON.toJson(previewCanonical(plan.documents())));
                for(String field:fields)lines.add("Документ "+field+": "+(previewCanonical(current.get(field)).equals(previewCanonical(plan.documents().get(field)))?"без изменений":"будет заменён")+"; прочие документы сохраняются.");
            }
            if(plan.biomeAfter()!=null){
                previewHash(digest,GSON.toJson(previewCanonical(TestingBiomeDocuments.capture(server))));
                previewHash(digest,GSON.toJson(previewCanonical(TestingBiomeDocuments.uniform(server,plan.biomeAfter()))));
                lines.add("Палитры биомов: "+plan.biomeBefore()+" → "+plan.biomeAfter()+"; высоты покрытия сохраняются.");
            }
            for(var entry:files.entrySet())if(!sourceHashes.get(entry.getKey()).equals(importFileSha(entry.getValue())))throw new IOException("Файл категории изменился во время предпросмотра");
            lines.add("Коллизии, защита и нативные данные проверены; подтверждение повторно сверяет этот план.");
        }catch(IOException|RuntimeException error){
            String reason=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            conflicts.add(reason.length()>1024?reason.substring(0,1024):reason);
            lines.add("Проверка остановилась на первом конфликте; остров не изменён.");
            previewHash(digest,"conflict:"+reason);
        }
        return new CategoryPreview(lines,conflicts,HexFormat.of().formatHex(digest.digest()));
    }
    private static void previewTerrain(Context c,TestingResize.TerrainData data,java.security.MessageDigest digest,List<String> lines)throws IOException {
        var g=(TestingIslandGenerator)c.server.overworld().getChunkSource().getGenerator();var world=c.server.overworld();
        int extent=Math.max(data.extent,(int)Math.ceil(g.radius()*1.45)+4);
        for(long key:c.heights.keySet()){BlockPos p=BlockPos.of(key);extent=Math.max(extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        if(extent>192)throw new IOException("Рельеф выходит за область предпросмотра 192 м");
        previewHash(digest,GSON.toJson(g.configuration()));previewHash(digest,GSON.toJson(data.configuration));
        int columns=0,heightDifferences=0,protectedColumns=0,waterDifferences=0;
        for(int z=-extent;z<=extent;z++)for(int x=-extent;x<=extent;x++){
            if(!world.hasChunkAt(new BlockPos(x,64,z)))throw new IOException("Рельеф предпросмотра ещё не загружен");
            int current=TestingTerrain.height(c,g,x,z),target=data.height(g,data.configuration,x,z);
            TestingResize.destinationFreeAfterTerrain(c,data,new BlockPos(x,target,z));
            boolean keep=Boolean.TRUE.equals(data.blocked.get(TestingTerrain.column(x,z)));
            columns++;if(keep)protectedColumns++;else if(current!=target)heightDifferences++;
            previewHash(digest,x+","+z+"|"+current+"|"+target+"|"+keep);
            int old=g.surfaceHeight(x,z),next=g.surfaceHeight(data.configuration,x,z);
            int bottom=Math.max(world.getMinY()+1,Math.min(Math.min(old,next),Math.min(current,target))-4);
            int top=Math.min(world.getMaxY()-1,Math.max(TestingIslandGenerator.SEA_LEVEL,Math.max(Math.max(old,next),Math.max(current,target)))+1);
            int[] water=data.waterBounds.get(TestingTerrain.column(x,z));if(water!=null){bottom=Math.min(bottom,water[0]);top=Math.max(top,water[1]);}
            for(int y=bottom;y<=top;y++){
                BlockPos p=new BlockPos(x,y,z);var cell=c.edits.read(p);
                previewHash(digest,cell.state().toString());previewHash(digest,cell.origin()==null?"":cell.origin());
                if(cell.entity()!=null)previewHash(digest,TestingNbt.encode(cell.entity()));
            }
        }
        for(var entry:new TreeMap<>(data.water).entrySet()){
            BlockPos p=BlockPos.of(entry.getKey());boolean wet=world.getBlockState(p).is(Blocks.WATER);
            if(wet!=entry.getValue())waterDifferences++;previewHash(digest,p.asLong()+"|"+wet+"|"+entry.getValue());
        }
        lines.add("Рельеф: "+g.configuration()+" → "+data.configuration+"; область "+columns+" столбцов.");
        lines.add("Различающихся разрешённых высот: "+heightDifferences+"; защищённых пропускаемых столбцов: "+protectedColumns+".");
        lines.add("Явных водных записей источника: "+data.water.size()+"; отличаются от текущего мира: "+waterDifferences+".");
        lines.add("Это единицы области, не точное число блоковых записей: окончательный потоковый план рельефа проверяется перед применением.");
    }
    private static void previewHash(java.security.MessageDigest digest,String text){
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int size=bytes.length;digest.update((byte)(size>>>24));digest.update((byte)(size>>>16));digest.update((byte)(size>>>8));digest.update((byte)size);digest.update(bytes);
    }
    private static JsonElement previewCanonical(JsonElement value){
        if(value==null||value.isJsonNull())return JsonNull.INSTANCE;
        if(value.isJsonObject()){JsonObject out=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))out.add(key,previewCanonical(value.getAsJsonObject().get(key)));return out;}
        if(value.isJsonArray()){JsonArray out=new JsonArray();for(JsonElement child:value.getAsJsonArray())out.add(previewCanonical(child));return out;}
        return value;
    }

    private record PlannedImport(List<TestingEdits.Change> changes,List<TestingEntities.Change> entities,
                                 String biomeBefore,String biomeAfter,JsonObject documents,List<TestingScenarioDependencies.Plan> dependencies) {}
    private static PlannedImport planStandardCategories(MinecraftServer server,Map<String,Path> files,
                                                        TestingResize.TerrainData forecast)throws IOException {
        Context c=STATES.get(server);if(c==null||!server.isSameThread()||!isTesting(server))throw new IOException("No server context");
        List<TestingEdits.Change> changes=new ArrayList<>();Set<BlockPos> unique=new HashSet<>();
        String biomeBefore=null,biomeAfter=null;
        JsonObject documents=files.keySet().stream().anyMatch(key->Set.of("settings","scenarios").contains(key))?new JsonObject():null;
        if(documents!=null)documents.addProperty("schema",1);
        List<TestingEntities.Change> npcChanges=new ArrayList<>();
        List<TestingScenarioDependencies.Plan> dependencies=new ArrayList<>();
        for(var selected:new TreeMap<>(files).entrySet()) {
            String category=selected.getKey();Path file=selected.getValue();
            if(!IMPORT_CATEGORIES.contains(category))throw new IOException("Unknown category");
            if(!Files.isRegularFile(file)||Files.size(file)>128L*1024*1024)throw new IOException("Invalid import size");
            JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            TestingEdits.Operation data=TestingEdits.decode(root);
            if(!data.name().equals("category:"+category)||!data.entities().isEmpty()||data.resizeId()!=null||data.sceneBefore()!=null||data.sceneAfter()!=null||data.documentsBefore()!=null||data.documentsAfter()!=null)throw new IOException("Wrong category or executable metadata");
            if(Set.of("settings","scenarios").contains(category)) {
                if(!data.changes().isEmpty()||root.has("npc")||!root.has("document")||!root.get("document").isJsonObject())
                    throw new IOException("Повреждён переносимый документ "+category);
                if(category.equals("scenarios")){
                    var dependency=TestingImportLoader.preparedScenario(server,file,root.getAsJsonObject("document"),root.has("dependencyRoot")?root.get("dependencyRoot").getAsString():"");
                    dependencies.add(dependency);documents.add(category,dependency.document());
                }else documents.add(category,root.get("document").deepCopy());
                continue;
            }
            if(category.equals("surface")) {
                for(var change:TestingResize.surfaceChanges(server,root)){if(!unique.add(change.pos()))throw new IOException("Покрытие пересекается с выбранными объектами");changes.add(change);}
                biomeBefore=c.settings.biome;biomeAfter=root.get("biome").getAsString();continue;
            }
            Map<BlockPos,BlockState> sourceStates=new LinkedHashMap<>();Map<BlockPos,net.minecraft.nbt.CompoundTag> sourceTags=new LinkedHashMap<>();
            for(var cell:data.changes()) {
                var state=cell.after().state();if(!state.isAir())sourceStates.put(cell.pos(),state);
                if(cell.after().entity()!=null)sourceTags.put(cell.pos(),cell.after().entity());
                if(state.hasBlockEntity()) {
                    var candidate=cell.after().entity()==null&&state.getBlock() instanceof EntityBlock block?block.newBlockEntity(cell.pos(),state):net.minecraft.world.level.block.entity.BlockEntity.loadStatic(cell.pos(),state,cell.after().entity(),server.overworld().registryAccess());
                    if(candidate==null||!candidate.getType().isValid(state))throw new IOException("Недопустимый контейнер в категории");
                } else if(cell.after().entity()!=null)throw new IOException("Данные контейнера у обычного блока");
            }TestingStructures.validate(sourceStates,sourceTags);
            if(category.equals("object")&&root.has("npc")) {
                Map<UUID,net.minecraft.nbt.CompoundTag> targetNpc=new LinkedHashMap<>();
                for(JsonElement row:root.getAsJsonArray("npc")) {
                    var change=TestingEntities.decode(row.getAsJsonObject());
                    if(change.after()==null||targetNpc.put(change.id(),change.after())!=null)throw new IOException("Invalid NPC category");
                }
                for(var entity:server.overworld().getAllEntities())if(TestingEntities.supported(entity))targetNpc.putIfAbsent(entity.getUUID(),null);
                for(var npc:targetNpc.entrySet()) {
                    var before=TestingEntities.snapshot(server.overworld(),npc.getKey());
                    if(!Objects.equals(before,npc.getValue())){
                        if(entityProtected(c,before)||entityProtected(c,npc.getValue()))throw new IOException("NPC находится в защищённой зоне");
                        npcChanges.add(new TestingEntities.Change(npc.getKey(),before,npc.getValue()));
                    }
                }
            }
            Map<BlockPos,TestingEdits.Cell> target=new LinkedHashMap<>();
            for(TestingEdits.Change row:data.changes()) {
                if(!category.equals(row.after().origin()))throw new IOException("Mismatched category ownership");
                target.put(row.pos(),row.after());
            }
            TestingIslandGenerator generator=server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator g?g:null;
            for(var existing:c.edits.origins.entrySet()) if(category.equals(existing.getValue())) {
                BlockPos pos=BlockPos.of(existing.getKey());
                if(target.containsKey(pos))continue;
                BlockState baseline=(category.equals("terrain")||category.equals("surface"))&&generator!=null
                        ?generator.naturalState(pos.getX(),pos.getY(),pos.getZ()):Blocks.AIR.defaultBlockState();
                target.put(pos,new TestingEdits.Cell(baseline,null,null));
            }
            for(var row:target.entrySet()) {
                if(!unique.add(row.getKey()))throw new IOException("Overlapping categories");
                if(!server.overworld().hasChunkAt(row.getKey()))throw new IOException("Целевая область категории ещё не загружена");
                TestingEdits.Cell old=c.edits.read(row.getKey());
                if(old.equals(row.getValue()))continue;
                boolean natural=Set.of("terrain","surface").contains(old.origin()==null?"":old.origin())
                        ||generator!=null&&old.origin()==null&&generator.naturalState(row.getKey().getX(),row.getKey().getY(),row.getKey().getZ()).equals(old.state());
                boolean futureFree=forecast!=null&&TestingResize.destinationFreeAfterTerrain(c,forecast,row.getKey());
                if((Set.of("grass","bushes","trees").contains(category)?naturalProtectedAt(c,row.getKey()):zoneProtected(c,row.getKey()))||forecast!=null&&!category.equals(old.origin())&&!futureFree
                        ||!old.state().canBeReplaced()&&!category.equals(old.origin())&&!(forecast==null&&generator!=null&&old.origin()==null&&generator.naturalState(row.getKey().getX(),row.getKey().getY(),row.getKey().getZ()).equals(old.state())||forecast!=null&&natural&&futureFree))
                    throw new IOException("Import conflict at "+row.getKey()+"; исходный мир не изменён");
                if(!old.equals(row.getValue()))changes.add(new TestingEdits.Change(row.getKey(),old,row.getValue()));

            }
            if(category.equals("surface")&&root.has("biome")) {biomeBefore=c.settings.biome;biomeAfter=root.get("biome").getAsString();}
        }
        if(changes.size()>TestingEdits.LIMIT||npcChanges.size()>TestingEntities.LIMIT
                ||npcChanges.stream().map(TestingEntities.Change::id).distinct().count()!=npcChanges.size())
            throw new IOException("Категории превышают лимит одной транзакции");
        long nativeBytes=0;
        Map<BlockPos,BlockState> planned=new HashMap<>();
        for(var change:changes){
            nativeBytes+=(change.before().entity()==null?0:change.before().entity().sizeInBytes())+(change.after().entity()==null?0:change.after().entity().sizeInBytes());
            if(nativeBytes>64L*1024*1024)throw new IOException("Данные контейнеров категорий превышают 64 МиБ");
            planned.put(change.pos(),change.after().state());
        }
        for(var npc:npcChanges) {
            TestingEntities.validate(server.overworld(),npc);
            if(npc.after()==null)continue;
            var point=TestingEntities.position(npc.after());var chunk=net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(point));
            if(!server.overworld().areEntitiesLoaded(chunk.pack()))throw new IOException("Загрузите исходную и целевую область NPC перед переносом");
            var box=TestingEntities.bounds(server.overworld(),npc.after());
            for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(Math.nextDown(box.maxX),Math.nextDown(box.maxY),Math.nextDown(box.maxZ)))) {
                BlockState state=planned.get(pos);
                if(state!=null)for(var shape:state.getCollisionShape(server.overworld(),pos).toAabbs())if(shape.move(pos).intersects(box))throw new IOException("NPC пересекает выбранный объект");
                if(forecast!=null&&state==null&&!TestingResize.destinationFreeAfterTerrain(c,forecast,pos))throw new IOException("NPC пересекает восстановленный рельеф; примените безопасную полную копию острова");
            }
        }
        if(biomeAfter!=null)server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,Identifier.parse(biomeAfter.contains(":")?biomeAfter:"minecraft:"+biomeAfter)));
        if(documents!=null){
            TestingDocuments.validate(server,documents);
            if(documents.has("scenarios")&&(TestingScenarios.running(server)||TestingScenarios.recording(server)))throw new IOException("Остановите сценарий и запись перед заменой определений");
        }
        changes.sort(Comparator.comparingLong(change->change.pos().asLong()));
        npcChanges.sort(Comparator.comparing(change->change.id().toString()));
        return new PlannedImport(List.copyOf(changes),List.copyOf(npcChanges),biomeBefore,biomeAfter,documents,List.copyOf(dependencies));
    }
    private static void importStandardCategories(MinecraftServer server,Map<String,Path> files,boolean preflight,
                                                TestingResize.TerrainData forecast,String label) throws IOException {
        PlannedImport plan=planStandardCategories(server,files,forecast);
        if(preflight)return;
        // Native geometry, NPC, document and conflict validation above has completed.
        // Dependency manifests are immutable and published before a WAL may reference them.
        for(var dependency:plan.dependencies())TestingImportLoader.requirePublished(server,dependency);
        Context c=STATES.get(server);String operation=label==null?"Импорт категорий "+files.keySet():label;
        if(plan.documents()!=null)c.edits.submitDocuments(operation,plan.changes(),plan.biomeBefore(),plan.biomeAfter(),plan.entities(),TestingDocuments.store(server,plan.documents()));
        else if(plan.biomeAfter()!=null)c.edits.submitBiome(operation,plan.changes(),plan.biomeBefore(),plan.biomeAfter(),plan.entities());
        else c.edits.submit(operation,plan.changes(),plan.entities());
    }
}
