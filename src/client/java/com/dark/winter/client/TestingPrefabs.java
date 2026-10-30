// Versioned named block prefabs, including container/block-entity payloads.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import com.dark.winter.testing.TestingEntities;
import com.dark.winter.testing.TestingServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/** Versioned named block prefabs, including container/block-entity payloads. */
public final class TestingPrefabs {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("DarkWinter/Prefabs");
    private static final int EDGE_LIMIT = 64, MAX_BLOCKS = 32768, MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static BlockPos first, second;
    private static volatile String selected, message = "";
    private TestingPrefabs() {}
    public static void clearSelection() { first = null; second = null; }
    public static void mark(BlockPos position, boolean secondCorner) {
        if (position == null || !TestingWorlds.isTesting()) return;
        if (secondCorner) second = position.immutable(); else first = position.immutable();
    }
    public static String status() {
        return "Выделение: " + (first == null ? "—" : first.toShortString()) + " / "
                + (second == null ? "—" : second.toShortString()) + (message.isEmpty() ? "" : " | " + message);
    }
    private static void requireArchiveContext(){
        var server=Minecraft.getInstance().getSingleplayerServer();if(server==null)return;
        try{com.dark.winter.testing.TestingArchiveScope.requireWorld(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));}
        catch(IOException denied){throw new java.io.UncheckedIOException(denied);}
    }
    private static Path library() {
        requireArchiveContext();
        return com.dark.winter.testing.TestingArchiveScope.prefabs().orElseGet(()->FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/prefabs").toAbsolutePath().normalize());
    }
    public static List<String> list() {
        try {
            if (!Files.isDirectory(library())) {
                return Files.isRegularFile(legacyFile()) ? List.of("legacy | Prefab прототипа") : List.of();
            }
            try (var paths = Files.list(library())) {
                List<String> result = new ArrayList<>();
                for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                    JsonObject root = read(file);
                    result.add(root.get("id").getAsString() + " | " + root.get("name").getAsString());
                }
                if (Files.isRegularFile(legacyFile())) result.add("legacy | Prefab прототипа");
                return List.copyOf(result);
            }
        } catch (Exception error) { reject("Ошибка библиотеки", error); return List.of(); }
    }
    public static void select(String id) {
        if (id == null || !(id.equals("legacy") || id.matches("[0-9a-f-]{36}"))) { message = "Недопустимый Prefab"; return; }
        selected = id;
    }
    public static void exportSelection(Minecraft client) { exportSelection(client, "Prefab"); }
    public static void exportSelection(Minecraft client,String name) {
        if(!validClient(client)||first==null||second==null){message="Отметьте оба угла выделения";return;}
        BlockPos a=first,b=second;MinecraftServer server=client.getSingleplayerServer();
        TestingSessions.dispatchNative(server,()->{try {
            BlockPos min=new BlockPos(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()));
            int sx=Math.abs(a.getX()-b.getX())+1,sy=Math.abs(a.getY()-b.getY())+1,sz=Math.abs(a.getZ()-b.getZ())+1;
            checkSize(sx,sy,sz);writeSelection(server,name,min,sx,sy,sz,null,null,null);
        }catch(Exception error){reject("Экспорт отменён",error);}});
    }
    /** Exact server-thread selection owned by the requesting participant; no shared client selection is read. */
    public static String exportSelection(MinecraftServer server,BlockPos first,BlockPos second,String name)throws IOException {
        requireAuthority(server);if(first==null||second==null)throw new IOException("Отметьте оба угла выделения");
        BlockPos min=new BlockPos(Math.min(first.getX(),second.getX()),Math.min(first.getY(),second.getY()),Math.min(first.getZ(),second.getZ()));
        int sx=Math.abs(first.getX()-second.getX())+1,sy=Math.abs(first.getY()-second.getY())+1,sz=Math.abs(first.getZ()-second.getZ())+1;
        checkSize(sx,sy,sz);return writeSelection(server,name,min,sx,sy,sz,null,null,null);
    }
    public static void exportSceneGroup(Minecraft client,String sceneId,String name) {
        if(!validClient(client))return;var server=client.getSingleplayerServer();
        TestingSessions.dispatchNative(server,()->{try{exportSceneGroup(server,sceneId,name);}catch(Exception error){reject("Экспорт группы отменён",error);}});
    }
    public static String exportSceneGroup(MinecraftServer server,String sceneId,String name)throws IOException {
        requireAuthority(server);var selected=com.dark.winter.testing.TestingScene.prefabSelection(server,sceneId);
        BlockPos min=selected.origin(),max=selected.max();int sx=max.getX()-min.getX()+1,sy=max.getY()-min.getY()+1,sz=max.getZ()-min.getZ()+1;
        checkSparseSize(sx,sy,sz);if(selected.blocks().size()>MAX_BLOCKS)throw new IOException("В Prefab допускается до32768 точных блоков");
        return writeSelection(server,name,min,sx,sy,sz,selected.blocks(),selected.entities(),selected.hierarchy());
    }
    private static String writeSelection(MinecraftServer server,String name,BlockPos min,int sx,int sy,int sz,
                                         Set<BlockPos> exactBlocks,Set<UUID> exactEntities,JsonObject hierarchy)throws IOException {
        requireAuthority(server);var world=server.overworld();JsonObject payload=new JsonObject();
        payload.addProperty("sizeX",sx);payload.addProperty("sizeY",sy);payload.addProperty("sizeZ",sz);
        JsonArray entries=new JsonArray();Map<BlockPos,BlockState> states=new LinkedHashMap<>();Map<BlockPos,CompoundTag> tags=new LinkedHashMap<>();
        Iterable<BlockPos> positions=exactBlocks==null?BlockPos.betweenClosed(min,min.offset(sx-1,sy-1,sz-1)):exactBlocks;
        for(BlockPos pos:positions) {
            if(!world.hasChunkAt(pos)||world.isOutsideBuildHeight(pos))throw new IOException("Незагруженная область");
            BlockState state=world.getBlockState(pos);if(state.isAir()){if(exactBlocks!=null)throw new IOException("Член группы исчез");continue;}
            BlockPos relative=pos.subtract(min);states.put(relative,state);JsonObject row=new JsonObject();
            row.addProperty("x",relative.getX());row.addProperty("y",relative.getY());row.addProperty("z",relative.getZ());row.add("state",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());
            BlockEntity entity=world.getBlockEntity(pos);
            if(entity!=null){CompoundTag tag=com.dark.winter.testing.TestingStructures.relocate(entity.saveWithFullMetadata(world.registryAccess()),point->point.subtract(min));tags.put(relative,tag);row.addProperty("entityNbt",com.dark.winter.testing.TestingNbt.encode(tag));}
            entries.add(row);
        }
        JsonArray npcs=new JsonArray();var area=new net.minecraft.world.phys.AABB(min.getX(),min.getY(),min.getZ(),min.getX()+sx,min.getY()+sy,min.getZ()+sz);
        List<net.minecraft.world.entity.Entity> selectedEntities=new ArrayList<>();
        if(exactEntities==null)selectedEntities.addAll(world.getEntities((net.minecraft.world.entity.Entity)null,area,candidate->!(candidate instanceof net.minecraft.world.entity.player.Player)));
        else for(UUID id:exactEntities){var entity=world.getEntityInAnyDimension(id);if(entity==null||entity.level()!=world)throw new IOException("NPC группы отсутствует или выгружен");selectedEntities.add(entity);}
        for(var entity:selectedEntities) {
            if(npcs.size()>=TestingEntities.LIMIT)throw new IOException("В Prefab допускается не более32 NPC");
            var bounds=entity.getBoundingBox();if(bounds.minX<area.minX||bounds.minY<area.minY||bounds.minZ<area.minZ||bounds.maxX>area.maxX||bounds.maxY>area.maxY||bounds.maxZ>area.maxZ)throw new IOException("Выделите NPC целиком");
            CompoundTag tag=TestingEntities.capture(entity);tag.put("Pos",net.minecraft.world.phys.Vec3.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,entity.position().subtract(min.getX(),min.getY(),min.getZ())).getOrThrow());npcs.add(com.dark.winter.testing.TestingNbt.encode(tag));
        }
        JsonObject scenarios=hierarchy==null?com.dark.winter.testing.TestingScenarioDocuments.exportArea(server,min,min.offset(sx-1,sy-1,sz-1)):null;
        if(entries.isEmpty()&&npcs.isEmpty()&&(scenarios==null||!hasAreaDocuments(scenarios)))throw new IOException("Выделение пустое");
        com.dark.winter.testing.TestingStructures.validate(states,tags);payload.add("blocks",entries);payload.add("entities",npcs);
        if(scenarios!=null&&hasAreaDocuments(scenarios))payload.add("scenarioDocuments",scenarios);if(hierarchy!=null)payload.add("scene",hierarchy);
        JsonObject root=new JsonObject();String id=UUID.randomUUID().toString();root.addProperty("schema",hierarchy==null?5:6);root.addProperty("minecraft","26.3");root.addProperty("id",id);
        root.addProperty("name",TestingArchive.safeName(name));root.addProperty("created",Instant.now().toString());root.addProperty("category",npcs.isEmpty()?"construction":"construction_npc");
        root.addProperty("npcPolicy","new_uuid_reset_navigation");root.addProperty("sha256",hash(payload.toString()));root.add("payload",payload);
        if(root.toString().getBytes(StandardCharsets.UTF_8).length>MAX_FILE_BYTES)throw new IOException("Prefab слишком велик");
        TestingArchive.atomicWrite(library().resolve(id+".json"),root.toString());TestingArchive.atomicWrite(library().resolve("latest.txt"),id);selected=id;
        message="Сохранено: "+root.get("name").getAsString()+" ("+entries.size()+" точных блоков, "+npcs.size()+" NPC)";return id;
    }
    public static void paste(Minecraft client, BlockPos target) { paste(client, target, selected, 0); }
    public static void paste(Minecraft client, BlockPos target, String id, int quarterTurns) {
        pasteChecked(client, target, id, quarterTurns, false);
    }
    static void pasteAfterCheckpoint(Minecraft client, BlockPos target, String id, int quarterTurns) {
        pasteChecked(client, target, id, quarterTurns, true);
    }
    /** Full import preflight shared by the interactive path and native integration checks. */
    public record Placement(String id,String name,Map<BlockPos,BlockState> states,
                            Map<BlockPos,CompoundTag> entities,List<TestingEntities.Change> npcChanges,JsonObject scenarioDocuments,String sceneAfter) {
        public Placement(String id,String name,Map<BlockPos,BlockState> states,Map<BlockPos,CompoundTag> entities,List<TestingEntities.Change> npcChanges,JsonObject scenarioDocuments) {
            this(id,name,states,entities,npcChanges,scenarioDocuments,null);
        }
        public Placement(String id,String name,Map<BlockPos,BlockState> states,Map<BlockPos,CompoundTag> entities,List<TestingEntities.Change> npcChanges) {
            this(id,name,states,entities,npcChanges,null,null);
        }
        public Placement {
            states=Map.copyOf(states);
            var copy=new LinkedHashMap<BlockPos,CompoundTag>(); entities.forEach((p,t)->copy.put(p,t.copy()));
            entities=Map.copyOf(copy); npcChanges=List.copyOf(npcChanges);
            scenarioDocuments=scenarioDocuments==null?null:scenarioDocuments.deepCopy();
        }
        @Override public JsonObject scenarioDocuments(){return scenarioDocuments==null?null:scenarioDocuments.deepCopy();}
    }
    public static Placement preparePaste(MinecraftServer server,BlockPos target,String id,int quarterTurns) throws IOException {
        try{return preparePasteData(server,target,id,quarterTurns);}
        catch(RuntimeException error){throw new IOException("Повреждённые или неподдерживаемые данные Prefab",error);}
    }
    private static Placement preparePasteData(MinecraftServer server,BlockPos target,String id,int quarterTurns) throws IOException {
        requireAuthority(server);
        String chosen = id;
        if (chosen == null) {
            List<String> entries = list();
            if (entries.isEmpty()) throw new IOException("Библиотека пуста; сначала сохраните Prefab");
            Path latest = library().resolve("latest.txt");
            chosen = Files.isRegularFile(latest) && Files.size(latest) < 100 ? Files.readString(latest).strip()
                    : entries.getFirst().split(" \\| ", 2)[0];
        }
        if (!(chosen.equals("legacy") || chosen.matches("[0-9a-f-]{36}"))) throw new IOException("Недопустимый Prefab");
        JsonObject root = chosen.equals("legacy") ? readLegacy() : read(library().resolve(chosen + ".json"));
        JsonObject payload = root.getAsJsonObject("payload");
        int sx = payload.get("sizeX").getAsInt(), sy = payload.get("sizeY").getAsInt(), sz = payload.get("sizeZ").getAsInt();
        if(payload.has("scene"))checkSparseSize(sx,sy,sz);else checkSize(sx,sy,sz);
        JsonArray blocks = payload.getAsJsonArray("blocks");
        JsonArray npcData = payload.has("entities") ? payload.getAsJsonArray("entities") : new JsonArray();
        if (blocks.size() > MAX_BLOCKS || blocks.size() > (long)sx * sy * sz || npcData.size() > TestingEntities.LIMIT
                || (blocks.isEmpty() && npcData.isEmpty() && (!payload.has("scenarioDocuments")||!hasAreaDocuments(payload.getAsJsonObject("scenarioDocuments")))))
            throw new IOException("Количество блоков/NPC/устройств неверно");
        Rotation rotation = Rotation.values()[Math.floorMod(quarterTurns, 4)];
        Set<BlockPos> unique = new HashSet<>();
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
        var world = server.overworld();
        // Validate the complete file and destination before submitting one undoable transaction.
        for (var element : blocks) {
            JsonObject entry = element.getAsJsonObject();
            int x = entry.get("x").getAsInt(), y = entry.get("y").getAsInt(), z = entry.get("z").getAsInt();
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) throw new IOException("Координаты за пределами Prefab");
            BlockPos relative = switch (Math.floorMod(quarterTurns, 4)) {
                case 1 -> new BlockPos(sz - 1 - z, y, x);
                case 2 -> new BlockPos(sx - 1 - x, y, sz - 1 - z);
                case 3 -> new BlockPos(z, y, sx - 1 - x);
                default -> new BlockPos(x, y, z);
            };
            BlockPos pos = target.offset(relative);
            if (!unique.add(pos) || world.isOutsideBuildHeight(pos) || !world.hasChunkAt(pos))
                throw new IOException("Повтор координат, высота или незагруженная область");
            var encodedState=entry.get("state");
            if(encodedState!=null&&encodedState.isJsonObject()&&encodedState.getAsJsonObject().has("Name")) {
                JsonObject legacy=encodedState.getAsJsonObject();
                if(legacy.has("id"))throw new IOException("Неоднозначный идентификатор block state");
                JsonObject converted=new JsonObject();converted.add("id",legacy.get("Name"));
                if(legacy.has("Properties"))converted.add("properties",legacy.get("Properties"));
                encodedState=converted;
            }
            String key;
            if(encodedState!=null && encodedState.isJsonPrimitive() && encodedState.getAsJsonPrimitive().isString())
                key=encodedState.getAsString();
            else if(encodedState!=null && encodedState.isJsonObject() && encodedState.getAsJsonObject().has("id")
                    && encodedState.getAsJsonObject().get("id").isJsonPrimitive()
                    && encodedState.getAsJsonObject().getAsJsonPrimitive("id").isString())
                key=encodedState.getAsJsonObject().get("id").getAsString();
            else throw new IOException("Неподдерживаемый формат block state");
            if (!BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.parse(key)))
                throw new IOException("Отсутствует игровой ресурс: " + key);
            BlockState state = BlockState.CODEC.parse(JsonOps.INSTANCE, encodedState).getOrThrow().rotate(rotation);
            if (state.isAir()) continue;
            if (!world.getBlockState(pos).canBeReplaced() || world.getBlockEntity(pos) != null)
                throw new IOException("Место занято в " + pos.toShortString());
            states.put(pos, state);
            if (entry.has("entityNbt") || entry.has("entity")) {
                CompoundTag tag = entry.has("entityNbt") ? com.dark.winter.testing.TestingNbt.decode(entry.get("entityNbt").getAsString())
                        : CompoundTag.CODEC.parse(JsonOps.INSTANCE, entry.get("entity")).getOrThrow();
                tag = com.dark.winter.testing.TestingStructures.relocate(tag, point -> transformPoint(point, target, sx, sz, quarterTurns));
                tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
                BlockEntity candidate=BlockEntity.loadStatic(pos,state,tag,world.registryAccess());
                if (!state.hasBlockEntity() || candidate==null || !candidate.getType().isValid(state))
                    throw new IOException("Данные block entity несовместимы с блоком");
                CompoundTag roundTrip=candidate.saveWithFullMetadata(world.registryAccess());
                if (entry.has("entityNbt") ? !tag.equals(roundTrip) :
                        !CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,tag).getOrThrow().equals(
                                CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,roundTrip).getOrThrow()))
                    throw new IOException("Данные контейнера содержат неизвестные или теряемые ресурсы");
                entities.put(pos, roundTrip);
            } else if (state.hasBlockEntity()) {
                throw new IOException("В Prefab отсутствуют данные block entity");
            }
        }
        com.dark.winter.testing.TestingStructures.validate(states, entities);
        List<TestingEntities.Change> npcChanges = new ArrayList<>();
        List<net.minecraft.world.phys.AABB> npcBoxes = new ArrayList<>();
        Set<UUID> sourceIds = new HashSet<>();Map<UUID,UUID> npcIds=new LinkedHashMap<>();
        for (var encoded : npcData) {
            CompoundTag source = encoded.isJsonPrimitive() ? com.dark.winter.testing.TestingNbt.decode(encoded.getAsString())
                    : CompoundTag.CODEC.parse(JsonOps.INSTANCE,encoded).getOrThrow();
            if (!sourceIds.add(TestingEntities.uuid(source))) throw new IOException("Повтор UUID в Prefab");
            var relative = TestingEntities.position(source);
            if (relative.x < 0 || relative.y < 0 || relative.z < 0 || relative.x > sx || relative.y > sy || relative.z > sz)
                throw new IOException("NPC находится вне размеров Prefab");
            var destination = transformEntityPoint(relative,target,sx,sz,quarterTurns);
            UUID npcId = UUID.randomUUID();npcIds.put(TestingEntities.uuid(source),npcId);
            CompoundTag npc = TestingEntities.copyAt(source,destination,Math.floorMod(quarterTurns,4)*90f,npcId);
            var change = new TestingEntities.Change(npcId,null,npc);
            TestingEntities.validate(world,change);
            var box = TestingEntities.bounds(world,npc);
            int rotatedX = Math.floorMod(quarterTurns,2) == 0 ? sx : sz;
            int rotatedZ = Math.floorMod(quarterTurns,2) == 0 ? sz : sx;
            if (box.minX < target.getX() || box.minY < target.getY() || box.minZ < target.getZ()
                    || box.maxX > target.getX()+rotatedX || box.maxY > target.getY()+sy || box.maxZ > target.getZ()+rotatedZ)
                throw new IOException("Габариты NPC выходят за размеры Prefab");
            for (var other : npcBoxes) if (box.intersects(other)) throw new IOException("NPC пересекаются в Prefab");
            for (BlockPos pos : BlockPos.betweenClosed((int)Math.floor(box.minX),(int)Math.floor(box.minY),(int)Math.floor(box.minZ),
                    (int)Math.floor(box.maxX),(int)Math.floor(box.maxY),(int)Math.floor(box.maxZ))) {
                BlockState planned = states.get(pos);
                if (planned != null && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                        planned.getCollisionShape(world,pos).move(pos.getX(),pos.getY(),pos.getZ()),
                        net.minecraft.world.phys.shapes.Shapes.create(box),net.minecraft.world.phys.shapes.BooleanOp.AND))
                    throw new IOException("NPC пересекает блоки вставляемого Prefab");
            }
            npcChanges.add(change); npcBoxes.add(box);
        }

        JsonObject scenarioDocuments=null;
        if(payload.has("scenarioDocuments"))scenarioDocuments=com.dark.winter.testing.TestingScenarioDocuments.prepareArea(
                server,payload.getAsJsonObject("scenarioDocuments"),target,sx,sy,sz,quarterTurns);
        String sceneAfter=payload.has("scene")?com.dark.winter.testing.TestingScene.preparePrefabHierarchy(server,payload.getAsJsonObject("scene"),target,sx,sy,sz,quarterTurns,npcIds,states.keySet()):null;
        return new Placement(chosen,root.get("name").getAsString(),states,entities,npcChanges,scenarioDocuments,sceneAfter);
    }
    private static void pasteChecked(Minecraft client, BlockPos target, String id, int quarterTurns, boolean checkpointed) {
        if (!validClient(client) || target == null) return;
        MinecraftServer server = client.getSingleplayerServer();
        TestingSessions.dispatchNative(server,() -> {
            try {
                Placement prepared=preparePaste(server,target,id,quarterTurns);
                if (!checkpointed && (prepared.states().size()>128 || !prepared.npcChanges().isEmpty())) {
                    message = "Создание полной контрольной точки перед вставкой Prefab";
                    client.execute(() -> TestingSessions.guardedPrefab(client,target,prepared.id(),quarterTurns));
                    return;
                }
                TestingServer.applyBatch(server,"Prefab: "+prepared.name(),prepared.states(),prepared.entities(),"building",prepared.npcChanges(),prepared.scenarioDocuments(),prepared.sceneAfter());
                message="Вставка "+prepared.states().size()+" блоков, "+prepared.npcChanges().size()+" NPC"+(prepared.scenarioDocuments()==null?"":" и определений сценариев/устройств")+" передана в одну транзакцию Undo/Redo";
            } catch(Exception error) { reject("Импорт отменён до изменения мира",error); }
        });
    }
    private static net.minecraft.world.phys.Vec3 transformEntityPoint(net.minecraft.world.phys.Vec3 point,
            BlockPos target,int sx,int sz,int quarterTurns) {
        var rotated = switch(Math.floorMod(quarterTurns,4)) {
            case 1 -> new net.minecraft.world.phys.Vec3(sz-point.z,point.y,point.x);
            case 2 -> new net.minecraft.world.phys.Vec3(sx-point.x,point.y,sz-point.z);
            case 3 -> new net.minecraft.world.phys.Vec3(point.z,point.y,sx-point.x);
            default -> point;
        };
        return rotated.add(target.getX(),target.getY(),target.getZ());
    }
    private static BlockPos transformPoint(BlockPos point, BlockPos target, int sx, int sz, int quarterTurns) {
        return target.offset(switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new BlockPos(sz - 1 - point.getZ(), point.getY(), point.getX());
            case 2 -> new BlockPos(sx - 1 - point.getX(), point.getY(), sz - 1 - point.getZ());
            case 3 -> new BlockPos(point.getZ(), point.getY(), sx - 1 - point.getX());
            default -> point;
        });
    }
    private static boolean hasAreaDocuments(JsonObject document) {
        JsonObject devices=document.getAsJsonObject("devices");
        return !devices.getAsJsonArray("triggers").isEmpty()||!devices.getAsJsonArray("spawners").isEmpty()
                ||!document.getAsJsonArray("scenarios").isEmpty();
    }
    private static Path legacyFile() {
        requireArchiveContext();
        return com.dark.winter.testing.TestingArchiveScope.legacy().orElseGet(()->FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/latest-blocks.json"));
    }
    private static JsonObject readLegacy() throws IOException {
        Path file = legacyFile();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 1024 * 1024)
            throw new IOException("Prefab прототипа отсутствует");
        JsonObject legacy = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (legacy.get("schema").getAsInt() != 1) throw new IOException("Неизвестная версия прототипа");
        JsonObject payload = new JsonObject();
        for (String key : List.of("sizeX", "sizeY", "sizeZ", "blocks")) payload.add(key, legacy.get(key));
        JsonObject root = new JsonObject(); root.addProperty("name", "Prefab прототипа"); root.add("payload", payload);
        return root;
    }
    private static void validateDependencies(Map<BlockPos, BlockState> states) throws IOException {
        com.dark.winter.testing.TestingStructures.validate(states);
    }
    private static JsonObject read(Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().getParent().equals(library()) || !Files.isRegularFile(file)
                || Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("Недопустимый файл Prefab");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            validatePortableRoot(root);return root;
        } catch (RuntimeException error) { throw new IOException("Повреждён Prefab", error); }
    }
    /** Validate one shared portable schema. Destination collisions/native round-trip are checked again before paste. */
    public static void validatePortableRoot(JsonObject root)throws IOException {
        try {
            if(!Set.of(2,3,4,5,6).contains(integer(root.get("schema"),2,6))||!"26.3".equals(root.get("minecraft").getAsString())
                    ||!root.get("id").getAsString().matches("[0-9a-f-]{36}")||!hash(root.get("payload").toString()).equals(root.get("sha256").getAsString()))
                throw new IOException("Версия, зависимости или SHA-256 Prefab не совпадают");
            TestingArchive.safeName(root.get("name").getAsString());JsonObject payload=root.getAsJsonObject("payload");
            int sx=integer(payload.get("sizeX"),1,64),sy=integer(payload.get("sizeY"),1,64),sz=integer(payload.get("sizeZ"),1,64);
            if(payload.has("scene"))checkSparseSize(sx,sy,sz);else checkSize(sx,sy,sz);
            JsonArray rows=payload.getAsJsonArray("blocks"),npcs=payload.has("entities")?payload.getAsJsonArray("entities"):new JsonArray();
            if(rows==null||rows.size()>MAX_BLOCKS||rows.size()>(long)sx*sy*sz||npcs.size()>TestingEntities.LIMIT)throw new IOException("Лимит членов Prefab");
            Set<BlockPos> unique=new HashSet<>();Map<BlockPos,BlockState> states=new LinkedHashMap<>();Map<BlockPos,CompoundTag> tags=new LinkedHashMap<>();long bytes=0;
            for(var value:rows) {
                JsonObject row=value.getAsJsonObject();BlockPos p=new BlockPos(integer(row.get("x"),0,sx-1),integer(row.get("y"),0,sy-1),integer(row.get("z"),0,sz-1));
                if(!unique.add(p))throw new IOException("Повторный блочный член Prefab");BlockState state=portableState(row.get("state"));
                if(!state.isAir())states.put(p,state);
                if(row.has("entityNbt")||row.has("entity")) {
                    CompoundTag tag=row.has("entityNbt")?com.dark.winter.testing.TestingNbt.decode(row.get("entityNbt").getAsString()):CompoundTag.CODEC.parse(JsonOps.INSTANCE,row.get("entity")).getOrThrow();
                    if(!state.hasBlockEntity()||!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block))throw new IOException("Данные контейнера у обычного блока");
                    var candidate=block.newBlockEntity(p,state);if(candidate==null||!candidate.getType().isValid(state)
                            ||!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(candidate.getType()).toString().equals(tag.getStringOr("id","")))throw new IOException("Тип native контейнера не совпадает");
                    tags.put(p,tag);bytes+=tag.sizeInBytes();
                }else if(state.hasBlockEntity())throw new IOException("Нет данных native контейнера");
            }
            com.dark.winter.testing.TestingStructures.validate(states,tags);Set<UUID> ids=new HashSet<>();
            for(var encoded:npcs) {
                CompoundTag tag=encoded.isJsonPrimitive()?com.dark.winter.testing.TestingNbt.decode(encoded.getAsString()):CompoundTag.CODEC.parse(JsonOps.INSTANCE,encoded).getOrThrow();
                UUID id=TestingEntities.uuid(tag);if(!ids.add(id))throw new IOException("Повторный NPC Prefab");
                TestingEntities.decode(TestingEntities.encode(new TestingEntities.Change(id,null,tag)));
                var p=TestingEntities.position(tag);if(p.x<0||p.y<0||p.z<0||p.x>sx||p.y>sy||p.z>sz)throw new IOException("NPC вне размеров Prefab");bytes+=tag.sizeInBytes();
            }
            if(bytes>32L*1024*1024)throw new IOException("Native данные Prefab превышают 32 МиБ");
            if(payload.has("scene"))com.dark.winter.testing.TestingScene.validatePrefabHierarchy(payload.getAsJsonObject("scene"),sx,sy,sz,states.keySet(),ids);
            if(payload.has("scenarioDocuments"))validateScenarioSource(payload.getAsJsonObject("scenarioDocuments"),sx,sy,sz);
            if(states.isEmpty()&&ids.isEmpty()&&(!payload.has("scenarioDocuments")||!hasAreaDocuments(payload.getAsJsonObject("scenarioDocuments"))))throw new IOException("Prefab пуст");
            if(root.toString().getBytes(StandardCharsets.UTF_8).length>MAX_FILE_BYTES)throw new IOException("Prefab превышает 32 МиБ");
        }catch(RuntimeException failure){throw new IOException("Неподдерживаемый portable Prefab",failure);}
    }
    private static BlockState portableState(com.google.gson.JsonElement encoded)throws IOException {
        if(encoded==null)throw new IOException("Нет block state");
        if(encoded.isJsonPrimitive()) {
            Identifier id=Identifier.parse(encoded.getAsString());if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IOException("Нет игрового ресурса "+id);
            return BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        }
        JsonObject state=encoded.getAsJsonObject().deepCopy();
        if(state.has("Name")){if(state.has("id"))throw new IOException("Неоднозначный block state");JsonObject converted=new JsonObject();converted.add("id",state.get("Name"));if(state.has("Properties"))converted.add("properties",state.get("Properties"));state=converted;}
        Identifier id=Identifier.parse(state.get("id").getAsString());if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IOException("Нет игрового ресурса "+id);
        return BlockState.CODEC.parse(JsonOps.INSTANCE,state).getOrThrow();
    }
    private static void validateScenarioSource(JsonObject root,int sx,int sy,int sz)throws IOException {
        if(integer(root.get("schema"),1,1)!=1||root.getAsJsonArray("scenarios").size()>128)throw new IOException("Неверный пакет сценариев Prefab");
        validateDeviceSource(root.getAsJsonObject("devices"),sx,sy,sz);
        for(var row:root.getAsJsonArray("scenarios")) {
            JsonObject scenario=row.getAsJsonObject();TestingArchive.safeName(scenario.get("name").getAsString());
            if(!Set.of(3,4).contains(integer(scenario.get("schema"),3,4))||scenario.getAsJsonArray("events").isEmpty()||scenario.getAsJsonArray("events").size()>256)throw new IOException("Неверный сценарий Prefab");
            new com.google.gson.Gson().fromJson(scenario.get("profile"),com.dark.winter.testing.TestingSettings.class).validate();validateDeviceSource(scenario.getAsJsonObject("devices"),sx,sy,sz);
        }
    }
    private static void validateDeviceSource(JsonObject root,int sx,int sy,int sz)throws IOException {
        if(integer(root.get("schema"),3,3)!=3||root.getAsJsonObject("generated").size()!=0||root.getAsJsonArray("triggers").size()>64||root.getAsJsonArray("spawners").size()>64)throw new IOException("Неверные portable устройства");
        Set<String> names=new HashSet<>();
        for(var value:root.getAsJsonArray("triggers")) {
            JsonObject row=value.getAsJsonObject();String name=row.get("name").getAsString();TestingArchive.safeName(name);if(!names.add(name))throw new IOException("Повтор зоны");
            for(String axis:List.of("x","y","z")){int size=axis.equals("x")?sx:axis.equals("y")?sy:sz;int min=integer(row.get(axis+"0"),0,size-1),max=integer(row.get(axis+"1"),0,size-1);if(min>max)throw new IOException("Неверные границы зоны");}
        }
        names.clear();
        for(var value:root.getAsJsonArray("spawners")) {
            JsonObject row=value.getAsJsonObject();String name=row.get("name").getAsString();TestingArchive.safeName(name);if(!names.add(name))throw new IOException("Повтор спавнера");
            if(!com.dark.winter.testing.TestingScenarios.spawnableTypes().contains(row.get("type").getAsString()))throw new IOException("Неподдерживаемый ресурс спавнера");
            integer(row.get("count"),1,16);
            for(String key:List.of("noAI","invulnerable"))if(!row.get(key).isJsonPrimitive()||!row.getAsJsonPrimitive(key).isBoolean())throw new IOException("Неверные флаги устройства");
            for(String axis:List.of("x","y","z")){double p=row.get(axis).getAsDouble(),size=axis.equals("x")?sx:axis.equals("y")?sy:sz;if(!Double.isFinite(p)||p<0||p>=size)throw new IOException("Спавнер вне размеров Prefab");}
        }
    }
    private static int integer(com.google.gson.JsonElement value,int min,int max)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Ожидалось целое число Prefab");
        double number=value.getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)throw new IOException("Число вне границ Prefab");return (int)number;
    }
    public static synchronized Path exportPortable(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("Выберите сохранённый Prefab");JsonObject root=read(library().resolve(id+".json"));
        Path directory=TestingArchive.root().resolve("exports");Files.createDirectories(directory);Path target=directory.resolve(id+".dwprefab");
        if(Files.isSymbolicLink(target))throw new IOException("Ссылки экспорта Prefab запрещены");TestingArchive.atomicWrite(target,root.toString());message="Экспорт Prefab: "+target.getFileName();return target;
    }
    public static synchronized String importPortable(String filename)throws IOException {
        if(filename==null||!filename.matches("[a-zA-Z0-9_-]{1,128}\\.dwprefab"))throw new IOException("Поместите .dwprefab в archive/imports");
        Path file=TestingArchive.root().resolve("imports").resolve(filename);
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_FILE_BYTES)throw new IOException("Недопустимый файл portable Prefab");
        JsonObject root;try{root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException error){throw new IOException("Повреждён portable Prefab",error);}
        validatePortableRoot(root);root=root.deepCopy();String id=UUID.randomUUID().toString();Path destination=library().resolve(id+".json");
        if(Files.exists(destination))throw new IOException("Коллизия независимого Prefab");root.addProperty("importedFrom",root.get("id").getAsString());root.addProperty("id",id);root.addProperty("created",Instant.now().toString());
        TestingArchive.atomicWrite(destination,root.toString());TestingArchive.atomicWrite(library().resolve("latest.txt"),id);selected=id;
        message="Независимый Prefab импортирован; место, native NBT/NPC и коллизии проверяются перед вставкой";return id;
    }
    private static boolean validClient(Minecraft client) {
        return client != null && TestingWorlds.isTesting() && client.getSingleplayerServer() != null
                && client.level != null && client.level.dimension().equals(Level.OVERWORLD);
    }
    private static void requireAuthority(MinecraftServer server) throws IOException {
        if (!TestingServer.isTesting(server) || !server.isSameThread() || (server.isPublished()&&!com.dark.winter.testing.TestingOnlineBridge.managedWorkbench(server))
                || TestingServer.settings(server).mode.startsWith("TEST")) throw new IOException("Prefab доступен только в редакторе тестового мира");
    }
    private static void checkSparseSize(int sx,int sy,int sz)throws IOException {
        if(sx<1||sy<1||sz<1||sx>EDGE_LIMIT||sy>EDGE_LIMIT||sz>EDGE_LIMIT)throw new IOException("Габариты Prefab: 1–64 по оси");
    }
    private static void checkSize(int sx, int sy, int sz) throws IOException {
        if (sx < 1 || sy < 1 || sz < 1 || sx > EDGE_LIMIT || sy > EDGE_LIMIT || sz > EDGE_LIMIT
                || (long)sx * sy * sz > MAX_BLOCKS) throw new IOException("Лимит Prefab: 64 по оси и 32768 блоков");
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void reject(String text, Throwable error) {
        message = text + ": " + error.getMessage(); LOG.warn(message, error);
    }
}
