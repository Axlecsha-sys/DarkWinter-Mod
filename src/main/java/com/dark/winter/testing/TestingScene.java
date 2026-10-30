// Persists authored scene nodes and their native block or entity membership.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Retained per-save scene graph. Members are exact authored coordinates and native entity UUIDs. */
public final class TestingScene {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_NODES=8192,MAX_BLOCKS=65536,MAX_DEPTH=32;
    private static final long MAX_BYTES=16L*1024*1024;
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    public record ObjectView(String id,String parentId,String name,String kind,int blockCount,int npcCount,
                             boolean loaded,BlockPos min,BlockPos max) {}
    record Metadata(String before,String after) {}
    private record Node(String id,String name,String parent,String kind,List<Long> blocks,List<UUID> entities) {
        Node {blocks=List.copyOf(blocks);entities=List.copyOf(entities);}
        Node parented(String parent){return new Node(id,name,parent,kind,blocks,entities);}
        Node named(String name){return new Node(id,name,parent,kind,blocks,entities);}
    }
    private record Document(int schema,List<Node> nodes) {}
    private static final class State {
        LinkedHashMap<String,Node> nodes=new LinkedHashMap<>();
        volatile List<ObjectView> views=List.of();
        volatile String selected="";
        String pendingSelected;
    }
    private record Members(Set<BlockPos> blocks,Set<UUID> entities,Set<String> nodes) {}
    private record Summary(int blocks,int entities,boolean loaded,BlockPos min,BlockPos max) {}
    private TestingScene() {}
    public record PrefabSelection(BlockPos origin,BlockPos max,Set<BlockPos> blocks,Set<UUID> entities,JsonObject hierarchy) {
        public PrefabSelection{blocks=Set.copyOf(blocks);entities=Set.copyOf(entities);hierarchy=hierarchy.deepCopy();}
        @Override public JsonObject hierarchy(){return hierarchy.deepCopy();}
    }
    /** Retained groups export only actual descendants and actual members. Empty space has no ownership. */
    public static PrefabSelection prefabSelection(MinecraftServer server,String id)throws IOException {
        var c=context(server);if(TestingServer.busy(server)||c.readOnlyPreview||(server.isPublished()&&!TestingActors.managedScope(server))||!server.isSingleplayer()||!Set.of("EDIT","CHARACTER").contains(c.settings.mode))throw new IOException("Сначала завершите операцию редактора");
        sync(server,true);State state=state(server);Node root=require(state.nodes,id);if(!root.kind().equals("group"))throw new IOException("Выберите именованную группу сцены");
        Members actual=members(state.nodes,id);Summary bounds=summary(server,state,root,new HashMap<>());
        if(!bounds.loaded()||actual.blocks().isEmpty()&&actual.entities().isEmpty())throw new IOException("Загрузите всех фактических членов группы");
        TestingStructures.validate(server.overworld(),actual.blocks());
        LinkedHashMap<String,Node> portable=new LinkedHashMap<>();
        for(String member:actual.nodes()) {
            Node node=state.nodes.get(member);for(long position:node.blocks()){var cell=c.edits.read(BlockPos.of(position));if(!authored(cell.origin())||!node.kind().equals(cell.origin())||cell.state().isAir())throw new IOException("Член группы изменился: "+node.name());}
            portable.put(member,new Node(member,node.name(),member.equals(id)?"":node.parent(),node.kind(),
                    node.blocks().stream().map(p->BlockPos.of(p).subtract(bounds.min()).asLong()).toList(),node.entities()));
        }
        return new PrefabSelection(bounds.min(),bounds.max(),actual.blocks(),actual.entities(),json(portable));
    }
    private static String prefabRoot(Map<String,Node> nodes)throws IOException {
        List<Node> roots=nodes.values().stream().filter(node->node.parent().isEmpty()).toList();
        if(roots.size()!=1||!roots.getFirst().kind().equals("group"))throw new IOException("Prefab должен содержать одно корневое дерево группы");
        String root=roots.getFirst().id();if(members(nodes,root).nodes().size()!=nodes.size())throw new IOException("В Prefab есть посторонние узлы");return root;
    }
    public static void validatePrefabHierarchy(JsonObject hierarchy,int sx,int sy,int sz,Set<BlockPos> blocks,Set<UUID> entities)throws IOException {
        var nodes=parse(hierarchy);String root=prefabRoot(nodes);Members actual=members(nodes,root);
        if(!actual.blocks().equals(blocks)||!actual.entities().equals(entities))throw new IOException("Иерархия Prefab не совпадает с точными блочными/NPC членами");
        for(BlockPos p:actual.blocks())if(p.getX()<0||p.getY()<0||p.getZ()<0||p.getX()>=sx||p.getY()>=sy||p.getZ()>=sz)throw new IOException("Член иерархии вне размеров Prefab");
    }
    /** Relocate a portable tree, generate independent node IDs, then retain it beside the current scene. */
    public static String preparePrefabHierarchy(MinecraftServer server,JsonObject hierarchy,BlockPos target,int sx,int sy,int sz,int turns,
                                                Map<UUID,UUID> npcIds,Set<BlockPos> placedBlocks)throws IOException {
        var incoming=parse(hierarchy);String root=prefabRoot(incoming);Members actual=members(incoming,root);
        validatePrefabHierarchy(hierarchy,sx,sy,sz,actual.blocks(),npcIds.keySet());
        Map<String,String> ids=new HashMap<>();incoming.keySet().forEach(id->ids.put(id,UUID.randomUUID().toString()));
        UnaryOperator<BlockPos> mapping=point->target.offset(switch(Math.floorMod(turns,4)){
            case 1->new BlockPos(sz-1-point.getZ(),point.getY(),point.getX());
            case 2->new BlockPos(sx-1-point.getX(),point.getY(),sz-1-point.getZ());
            case 3->new BlockPos(point.getZ(),point.getY(),sx-1-point.getX());default->point;});
        Set<BlockPos> expected=new HashSet<>();actual.blocks().forEach(point->expected.add(mapping.apply(point)));
        if(!expected.equals(placedBlocks)||!actual.entities().equals(npcIds.keySet())||new HashSet<>(npcIds.values()).size()!=npcIds.size())throw new IOException("План вставки не совпадает с иерархией Prefab");
        sync(server,true);State state=state(server);var merged=copy(state);
        for(Node node:incoming.values()) {
            Node copy=new Node(ids.get(node.id()),node.name(),node.parent().isEmpty()?"":ids.get(node.parent()),node.kind(),
                    node.blocks().stream().map(p->mapping.apply(BlockPos.of(p)).asLong()).toList(),node.entities().stream().map(npcIds::get).toList());
            if(merged.put(copy.id(),copy)!=null)throw new IOException("Коллизия идентификатора группы");
        }
        validate(merged);state.pendingSelected=ids.get(root);return store(server,merged);
    }
    /** Derive exact original categories from the verified scene snapshot once per batch. */
    public static Map<BlockPos,String> prefabOrigins(MinecraftServer server,String snapshot,Set<BlockPos> placedBlocks)throws IOException {
        var nodes=load(server,snapshot);Map<BlockPos,String> result=new HashMap<>();
        for(Node node:nodes.values())for(long packed:node.blocks()){BlockPos pos=BlockPos.of(packed);if(placedBlocks.contains(pos))result.put(pos,node.kind());}
        if(!result.keySet().equals(placedBlocks)||result.values().stream().anyMatch(value->!Set.of("building","object").contains(value)))throw new IOException("Prefab теряет принадлежность точных членов");
        return Map.copyOf(result);
    }
    public static List<ObjectView> views(MinecraftServer server){State s=server==null?null:STATES.get(server);return s==null?List.of():s.views;}
    public static String selected(MinecraftServer server){State s=server==null?null:STATES.get(server);return s==null?"":s.selected;}
    public static void forget(MinecraftServer server){STATES.remove(server);}
    record CursorSelection(String selected,String pending) {}
    static CursorSelection swapCursor(MinecraftServer server,String selected)throws IOException {State state=state(server);CursorSelection previous=new CursorSelection(state.selected,state.pendingSelected);state.selected=selected!=null&&state.nodes.containsKey(selected)?selected:"";state.pendingSelected=null;return previous;}
    static String cursorResult(MinecraftServer server)throws IOException {State state=state(server);return state.pendingSelected==null?state.selected:state.pendingSelected;}
    static void restoreCursor(MinecraftServer server,CursorSelection previous)throws IOException {State state=state(server);state.selected=previous.selected();state.pendingSelected=previous.pending();}
    public static boolean handles(String action){return action.startsWith("scene_");}
    public static boolean mutating(String action){return handles(action)&&!Set.of("scene_select","scene_pick","scene_pick_entity","scene_focus","scene_refresh").contains(action);}
    public static void attach(MinecraftServer server)throws IOException {
        if(STATES.containsKey(server))return;
        var c=context(server);State state=new State();Path file=c.directory.resolve("scene.json");
        if(Files.isRegularFile(file)){if(Files.size(file)>MAX_BYTES)throw new IOException("Сцена слишком велика");state.nodes=parse(JsonParser.parseString(Files.readString(file)));}
        STATES.put(server,state);
        if(!c.edits.busy()&&!Files.isRegularFile(c.directory.resolve("pending-edit.json")))sync(server,true);else publish(server,state);
    }
    public static void tick(MinecraftServer server) {
        if(server.getTickCount()%20!=0||TestingServer.busy(server))return;
        var existing=TestingServer.context(server);if(existing!=null&&Files.isRegularFile(existing.directory.resolve("pending-edit.json")))return;
        try{sync(server,true);}catch(IOException error){var c=TestingServer.context(server);if(c!=null)c.message="Сцена: "+error.getMessage();}
    }
    private static TestingServer.Context context(MinecraftServer server)throws IOException {
        var c=TestingServer.context(server);
        if(c==null||!server.isSameThread()||!TestingServer.isTesting(server))throw new IOException("Нет контекста тестовой сцены");
        return c;
    }
    private static State state(MinecraftServer server)throws IOException {
        if(!STATES.containsKey(server))attach(server);return STATES.get(server);
    }
    private static boolean authored(String origin){return "building".equals(origin)||"object".equals(origin);}
    private static LinkedHashMap<String,Node> copy(State state){return new LinkedHashMap<>(state.nodes);}
    private static Document document(Map<String,Node> nodes){return new Document(2,List.copyOf(nodes.values()));}
    private static JsonObject json(Map<String,Node> nodes){return JSON.toJsonTree(document(nodes)).getAsJsonObject();}
    private static String label(String source) {
        StringBuilder value=new StringBuilder();String forbidden="\\\\/:*?\\\"<>|";
        for(char ch:source.replace(".."," ").toCharArray())value.append(Character.isISOControl(ch)||forbidden.indexOf(ch)>=0?' ':ch);
        String name=value.toString().strip();return name.isEmpty()?"Объект":name.substring(0,Math.min(80,name.length()));
    }
    private static String title(BlockState state){return label(state.getBlock().getName().getString());}
    private static Map<Long,String> blockOwners(Map<String,Node> nodes) {
        Map<Long,String> owners=new HashMap<>();nodes.values().forEach(n->n.blocks().forEach(p->owners.put(p,n.id())));return owners;
    }
    private static Map<UUID,String> entityOwners(Map<String,Node> nodes) {
        Map<UUID,String> owners=new HashMap<>();nodes.values().forEach(n->n.entities().forEach(id->owners.put(id,n.id())));return owners;
    }
    private static void sync(MinecraftServer server,boolean persist)throws IOException {
        State s=state(server);var c=context(server);
        if(Files.isRegularFile(c.directory.resolve("pending-edit.json")))return;
        var world=server.overworld();var nodes=copy(s);
        for(Node node:List.copyOf(nodes.values()))if(!node.kind().equals("group")&&!node.blocks().isEmpty()) {
            List<Long> kept=node.blocks().stream().filter(p->{BlockPos pos=BlockPos.of(p);return !world.hasChunkAt(pos)||(!world.getBlockState(pos).isAir()&&authored(c.edits.origins.get(p)));}).toList();
            if(kept.isEmpty())nodes.remove(node.id());else if(!kept.equals(node.blocks()))nodes.put(node.id(),new Node(node.id(),node.name(),node.parent(),node.kind(),kept,node.entities()));
        }
        repairParents(nodes);
        Map<Long,String> owned=blockOwners(nodes);
        List<Long> positions=c.edits.origins.entrySet().stream().filter(e->authored(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        for(long packed:positions)if(!owned.containsKey(packed)) {
            BlockPos pos=BlockPos.of(packed);if(!world.hasChunkAt(pos)||world.getBlockState(pos).isAir())continue;
            var cell=c.edits.read(pos);List<Long> footprint=new ArrayList<>();
            for(BlockPos part:footprint(pos,cell.state(),cell.entity()))if(world.hasChunkAt(part)&&!world.getBlockState(part).isAir()&&authored(c.edits.origins.get(part.asLong()))&&!owned.containsKey(part.asLong()))footprint.add(part.asLong());
            if(footprint.isEmpty())continue;
            Node node=new Node(UUID.randomUUID().toString(),title(cell.state()),"",c.edits.origins.get(packed),footprint,List.of());
            nodes.put(node.id(),node);footprint.forEach(p->owned.put(p,node.id()));
        }
        Map<UUID,String> entityOwners=entityOwners(nodes);
        for(Entity entity:world.getAllEntities())if(entity.isAlive()&&!entity.isRemoved()&&TestingEntities.supported(entity)&&!entityOwners.containsKey(entity.getUUID())) {
            try{TestingEntities.capture(entity);}catch(IOException unsupported){continue;}
            Node node=new Node(UUID.randomUUID().toString(),label(entity.getName().getString()),"","npc",List.of(),List.of(entity.getUUID()));
            nodes.put(node.id(),node);
        }
        validate(nodes);
        if(!nodes.equals(s.nodes)) {
            if(persist)TestingEdits.atomic(c.directory.resolve("scene.json"),json(nodes));
            s.nodes=nodes;
        }
        if(!nodes.containsKey(s.selected))s.selected="";
        publish(server,s);
    }
    /** Metadata projected before WAL creation; unchanged scenes add no history payload. */
    static Metadata prepare(MinecraftServer server,List<TestingEdits.Change> changes,List<TestingEntities.Change> entities,
                            String expectedBefore,String desiredAfter)throws IOException {
        sync(server,true);State s=state(server);var before=copy(s);LinkedHashMap<String,Node> after;
        if(desiredAfter!=null) {
            var desired=load(server,desiredAfter);
            if(expectedBefore==null)after=desired;
            else {
                var expected=load(server,expectedBefore);after=copy(s);Set<String> ids=new LinkedHashSet<>(expected.keySet());ids.addAll(desired.keySet());
                for(String id:ids)if(!Objects.equals(expected.get(id),desired.get(id))) {
                    if(!Objects.equals(after.get(id),expected.get(id)))throw new IOException("Иерархия изменилась: "+id);
                    if(desired.containsKey(id))after.put(id,desired.get(id));else after.remove(id);
                }
            }
        } else after=project(server,before,changes,entities);
        validate(after);
        if(before.equals(after))return new Metadata(null,null);
        return new Metadata(store(server,before),store(server,after));
    }
    /** Native gestures already changed blocks in RAM: project cached retained metadata without syncing or publishing that uncommitted world. */
    static Metadata prepareNative(MinecraftServer server,List<TestingEdits.Change> changes)throws IOException {
        State current=state(server);var before=copy(current);
        var after=project(server,before,changes,List.of());validate(after);
        if(before.equals(after))return new Metadata(null,null);
        return new Metadata(store(server,before),store(server,after));
    }
    private static LinkedHashMap<String,Node> project(MinecraftServer server,LinkedHashMap<String,Node> before,
            List<TestingEdits.Change> changes,List<TestingEntities.Change> entities)throws IOException {
        var after=new LinkedHashMap<>(before);Map<Long,TestingEdits.Cell> projected=new LinkedHashMap<>();
        changes.forEach(change->projected.put(change.pos().asLong(),change.after()));
        for(Node node:List.copyOf(after.values()))if(!node.blocks().isEmpty()) {
            List<Long> kept=node.blocks().stream().filter(p->{var cell=projected.get(p);return cell==null||(!cell.state().isAir()&&authored(cell.origin()));}).toList();
            if(kept.isEmpty())after.remove(node.id());else if(!kept.equals(node.blocks()))after.put(node.id(),new Node(node.id(),node.name(),node.parent(),node.kind(),kept,node.entities()));
        }
        Map<Long,String> owned=blockOwners(after);
        for(var entry:projected.entrySet())if(!owned.containsKey(entry.getKey())&&!entry.getValue().state().isAir()&&authored(entry.getValue().origin())) {
            BlockPos pos=BlockPos.of(entry.getKey());var cell=entry.getValue();List<Long> parts=new ArrayList<>();
            for(BlockPos part:footprint(pos,cell.state(),cell.entity())) {
                var next=projected.get(part.asLong());
                if(next!=null&&!next.state().isAir()&&authored(next.origin())&&!owned.containsKey(part.asLong()))parts.add(part.asLong());
            }
            if(parts.isEmpty())parts.add(pos.asLong());
            Node node=new Node(UUID.randomUUID().toString(),title(cell.state()),"",cell.origin(),parts,List.of());
            after.put(node.id(),node);parts.forEach(p->owned.put(p,node.id()));
        }
        Map<UUID,String> entityOwners=entityOwners(after);
        for(var change:entities) {
            String owner=entityOwners.get(change.id());
            if(change.after()==null&&owner!=null)after.remove(owner);
            else if(change.after()!=null&&owner==null) {
                Node node=new Node(UUID.randomUUID().toString(),label(change.after().getStringOr("id","NPC")),"","npc",List.of(),List.of(change.id()));
                after.put(node.id(),node);
            }
        }
        repairParents(after);return after;
    }
    public static void validateRef(MinecraftServer server,String hash)throws IOException { if(hash!=null)load(server,hash); }
    static void apply(MinecraftServer server,String hash)throws IOException {
        if(hash==null)return;State s=state(server);var nodes=load(server,hash);
        TestingEdits.atomic(context(server).directory.resolve("scene.json"),json(nodes));s.nodes=nodes;
        if(s.pendingSelected!=null){if(nodes.containsKey(s.pendingSelected))s.selected=s.pendingSelected;s.pendingSelected=null;}
        if(!nodes.containsKey(s.selected))s.selected="";
        publish(server,s);
    }
    public static long collectUnused(MinecraftServer server,boolean confirmed)throws IOException {
        var c=context(server);if(!confirmed)throw new IOException("Очистка требует подтверждения");
        if(TestingServer.busy(server))throw new IOException("Дождитесь окончания операции");c.save();
        Set<String> used=new HashSet<>(c.edits.sceneRefs());Path history=c.directory.resolve("undo.json");
        if(Files.isRegularFile(history)) {
            JsonObject root=JsonParser.parseString(Files.readString(history)).getAsJsonObject();
            for(String stack:List.of("undo","redo"))for(JsonElement row:root.getAsJsonArray(stack)) {
                var op=TestingEdits.decode(row.getAsJsonObject());if(op.sceneBefore()!=null)used.add(op.sceneBefore());if(op.sceneAfter()!=null)used.add(op.sceneAfter());
            }
        }
        for(String hash:used)load(server,hash);
        Path directory=c.directory.resolve("scene-history");if(!Files.isDirectory(directory))return 0;List<Path> garbage=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path file:files.toList()) {
                String name=file.getFileName().toString();
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||!name.matches("[0-9a-f]{64}\\.json"))throw new IOException("Недопустимый файл истории сцены");
                if(!used.contains(name.substring(0,64)))garbage.add(file);
            }
        }
        long bytes=0;for(Path file:garbage){bytes+=Files.size(file);Files.delete(file);}return bytes;
    }

    private static Path snapshotPath(MinecraftServer server,String hash)throws IOException {
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("Недопустимый SHA-256 снимка сцены");
        return context(server).directory.resolve("scene-history").resolve(hash+".json");
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static String store(MinecraftServer server,Map<String,Node> nodes)throws IOException {
        String text=JSON.toJson(document(nodes));byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Снимок сцены превышает 16 МиБ");String hash=hash(bytes);Path file=snapshotPath(server,hash);
        if(Files.exists(file)){if(Files.size(file)>MAX_BYTES||!hash(Files.readAllBytes(file)).equals(hash))throw new IOException("Повреждён неизменяемый снимок сцены");}
        else {Files.createDirectories(file.getParent());if(Files.getFileStore(file.getParent()).getUsableSpace()<bytes.length+32L*1024*1024)throw new IOException("Недостаточно места для истории сцены");TestingArchive.atomicWrite(file,text);}
        return hash;
    }
    private static LinkedHashMap<String,Node> load(MinecraftServer server,String hash)throws IOException {
        Path file=snapshotPath(server,hash);if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)throw new IOException("Снимок сцены отсутствует");
        byte[] bytes=Files.readAllBytes(file);if(!hash(bytes).equals(hash))throw new IOException("SHA-256 снимка сцены не совпадает");
        return parse(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)));
    }
    private static LinkedHashMap<String,Node> parse(JsonElement element)throws IOException {
        try {
            Document doc=JSON.fromJson(element,Document.class);if(doc==null||doc.schema()!=2||doc.nodes()==null)throw new IOException("Неподдерживаемый формат сцены");
            var nodes=new LinkedHashMap<String,Node>();for(Node node:doc.nodes())if(nodes.put(node.id(),node)!=null)throw new IOException("Повторяющийся ID сцены");
            validate(nodes);return nodes;
        }catch(RuntimeException error){throw new IOException("Повреждена сцена",error);}
    }
    private static void validate(Map<String,Node> nodes)throws IOException {
        if(nodes.size()>MAX_NODES)throw new IOException("Сцена поддерживает до "+MAX_NODES+" объектов");Set<Long> blocks=new HashSet<>();Set<UUID> entities=new HashSet<>();
        for(Node node:nodes.values()) {
            if(node==null||!node.id().matches("[0-9a-f-]{36}")||node.parent()==null||!Set.of("group","building","object","npc").contains(node.kind()))throw new IOException("Недопустимый узел сцены");
            TestingServer.safeName(node.name());
            if(!node.parent().isEmpty()&&(!nodes.containsKey(node.parent())||!nodes.get(node.parent()).kind().equals("group")))throw new IOException("Недопустимый родитель сцены");
            if(node.kind().equals("group")&&(!node.blocks().isEmpty()||!node.entities().isEmpty()))throw new IOException("Группа содержит ссылки на дочерние объекты");
            if(node.kind().equals("npc")&&!node.blocks().isEmpty()||Set.of("building","object").contains(node.kind())&&!node.entities().isEmpty())throw new IOException("Смешанный тип объекта сцены");
            if(!node.kind().equals("group")&&node.blocks().isEmpty()&&node.entities().isEmpty())throw new IOException("Пустой объект сцены");
            for(long packed:node.blocks()) {BlockPos p=BlockPos.of(packed);if(!blocks.add(packed)||Math.abs((long)p.getX())>29999984||Math.abs((long)p.getZ())>29999984||p.getY()<-2048||p.getY()>2047)throw new IOException("Повторяющийся или недопустимый блок сцены");}
            for(UUID id:node.entities())if(id==null||!entities.add(id))throw new IOException("Повторяющийся NPC сцены");
            Set<String> seen=new HashSet<>();String id=node.id();while(!id.isEmpty()){if(!seen.add(id)||seen.size()>MAX_DEPTH)throw new IOException("Цикл или слишком глубокая иерархия");id=nodes.get(id).parent();}
        }
        if(blocks.size()>MAX_BLOCKS)throw new IOException("Сцена поддерживает до "+MAX_BLOCKS+" точных блочных членов");
    }
    private static void repairParents(Map<String,Node> nodes) {
        for(Node node:List.copyOf(nodes.values()))if(!node.parent().isEmpty()&&!nodes.containsKey(node.parent()))nodes.put(node.id(),node.parented(""));
    }
    private static Set<BlockPos> footprint(BlockPos pos,BlockState state,CompoundTag tag) {
        Set<BlockPos> parts=new LinkedHashSet<>();parts.add(pos);
        if(state.getBlock() instanceof com.dark.winter.block.FurnitureBlock furniture) {
            BlockPos origin=furniture.origin(pos,state);var facing=state.getValue(com.dark.winter.block.FurnitureBlock.FACING);
            for(int i=0;i<furniture.definition().parts().size();i++)parts.add(origin.offset(furniture.partOffset(i,facing)));
        }
        if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))parts.add(state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER?pos.above():pos.below());
        if(state.hasProperty(BlockStateProperties.BED_PART)&&state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            parts.add(pos.relative(state.getValue(BlockStateProperties.BED_PART)==BedPart.FOOT?state.getValue(BlockStateProperties.HORIZONTAL_FACING):state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()));
        if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE)parts.add(ChestBlock.getConnectedBlockPos(pos,state));
        if(tag!=null&&tag.getLong("MasterPos").isPresent()&&state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            for(var shape:com.dark.winter.component.WallShape.values())if(shape.getSerializedName().equals(tag.getStringOr("WallShape","")))
                parts.addAll(shape.getPositions(BlockPos.of(tag.getLongOr("MasterPos",0)),state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }
        return parts;
    }

    /** Existing temporary-selection transforms retain object IDs at their actual member coordinates. */
    static String relocated(MinecraftServer server,Map<BlockPos,BlockPos> mapping,boolean copy,
                            List<TestingEdits.Change> changes,List<TestingEntities.Change> entities)throws IOException {
        sync(server,true);State s=state(server);var nodes=TestingScene.copy(s);
        for(Node node:List.copyOf(nodes.values())) {
            List<Long> selected=node.blocks().stream().filter(p->mapping.containsKey(BlockPos.of(p))).toList();
            if(selected.isEmpty())continue;
            if(copy) {
                Node clone=new Node(UUID.randomUUID().toString(),label(node.name()+" — копия"),"",node.kind(),
                        selected.stream().map(p->mapping.get(BlockPos.of(p)).asLong()).toList(),List.of());
                nodes.put(clone.id(),clone);
            } else nodes.put(node.id(),new Node(node.id(),node.name(),node.parent(),node.kind(),
                    node.blocks().stream().map(p->mapping.containsKey(BlockPos.of(p))?mapping.get(BlockPos.of(p)).asLong():p).toList(),node.entities()));
        }
        nodes=project(server,nodes,changes,entities);validate(nodes);return store(server,nodes);
    }

    static void action(TestingServer.Context c,ServerPlayer player,String action,String value)throws IOException {
        var server=c.server;sync(server,true);State s=state(server);String[] args=value.split("\\|",2);
        switch(action) {
            case "scene_refresh" -> {return;}
            case "scene_pick" -> {
                s.selected="";BlockPos pos=TestingServer.position(value);String id=blockOwners(s.nodes).get(pos.asLong());
                if(id==null||c.server.overworld().getBlockState(pos).isAir()){s.selected="";return;}
                while(!s.nodes.get(id).parent().isEmpty())id=s.nodes.get(id).parent();
                select(c,id);return;
            }
            case "scene_pick_entity" -> {
                s.selected="";String id=entityOwners(s.nodes).get(UUID.fromString(value));if(id==null){s.selected="";return;}while(!s.nodes.get(id).parent().isEmpty())id=s.nodes.get(id).parent();select(c,id);return;
            }
            case "scene_select" -> {select(c,value);return;}
            case "scene_focus" -> {
                Summary bounds=summary(server,s,s.nodes.get(value),new HashMap<>());
                if(!bounds.loaded()||bounds.blocks()+bounds.entities()==0)throw new IOException("Загрузите фактические члены объекта");
                player.teleportTo(server.overworld(),(bounds.min().getX()+bounds.max().getX()+1)/2d,bounds.max().getY()+4,
                        (bounds.min().getZ()+bounds.max().getZ()+1)/2d,Set.of(),player.getYRot(),35,false);return;
            }
        }
        var after=copy(s);String selected=s.selected;
        switch(action) {
            case "scene_rename" -> {Node node=require(after,args[0]);after.put(node.id(),node.named(TestingServer.safeName(args[1])));}
            case "scene_group" -> {
                String name=TestingServer.safeName(args[0]);Set<String> children=new LinkedHashSet<>(List.of(args[1].split(",")));
                if(children.isEmpty())throw new IOException("Выберите объекты для группы");
                for(String id:children){Node node=require(after,id);String parent=node.parent();while(!parent.isEmpty()){if(children.contains(parent))throw new IOException("Не выбирайте одновременно группу и её потомка");parent=after.get(parent).parent();}}
                String parent=children.stream().map(id->after.get(id).parent()).distinct().count()==1?after.get(children.iterator().next()).parent():"";
                Node group=new Node(UUID.randomUUID().toString(),name,parent,"group",List.of(),List.of());after.put(group.id(),group);
                for(String id:children)after.put(id,after.get(id).parented(group.id()));selected=group.id();
            }
            case "scene_capture" -> {
                TestingServer.requireSelection(c);var zone=TestingServer.zone(c.a,c.b);Set<String> children=new LinkedHashSet<>();
                for(Node node:after.values())if(!node.kind().equals("group")) {
                    boolean any=false,all=true;
                    for(long packed:node.blocks()){boolean inside=zone.contains(BlockPos.of(packed));any|=inside;all&=inside;}
                    for(UUID id:node.entities()){Entity entity=server.overworld().getEntityInAnyDimension(id);AABB selectionBox=new AABB(zone.x0(),zone.y0(),zone.z0(),zone.x1()+1d,zone.y1()+1d,zone.z1()+1d);boolean intersects=entity!=null&&selectionBox.intersects(entity.getBoundingBox());any|=intersects;if(entity==null)all=false;else{AABB box=entity.getBoundingBox();all&=box.minX>=selectionBox.minX&&box.minY>=selectionBox.minY&&box.minZ>=selectionBox.minZ&&box.maxX<=selectionBox.maxX&&box.maxY<=selectionBox.maxY&&box.maxZ<=selectionBox.maxZ;}}
                    if(any&&!all)throw new IOException("Выделите все части объекта "+node.name());if(any)children.add(node.id());
                }
                if(children.isEmpty())throw new IOException("В выделении нет авторских объектов или поддерживаемых NPC");
                Node group=new Node(UUID.randomUUID().toString(),TestingServer.safeName(value),"","group",List.of(),List.of());after.put(group.id(),group);
                for(String id:children)after.put(id,after.get(id).parented(group.id()));selected=group.id();
            }
            case "scene_ungroup" -> {
                Node group=require(after,value);if(!group.kind().equals("group"))throw new IOException("Выберите группу");
                for(Node node:List.copyOf(after.values()))if(node.parent().equals(group.id()))after.put(node.id(),node.parented(group.parent()));
                after.remove(group.id());selected="";
            }
            case "scene_reparent" -> {Node node=require(after,args[0]);String parent=args.length==2?args[1]:"";if(!parent.isEmpty()&&!require(after,parent).kind().equals("group"))throw new IOException("Родитель должен быть группой");after.put(node.id(),node.parented(parent));}
            case "scene_move","scene_copy","scene_rotate","scene_remove" -> {transform(c,args[0],args.length==2?TestingServer.position(args[1]):BlockPos.ZERO,action);return;}
            default -> throw new IOException("Неизвестное действие сцены");
        }
        validate(after);c.edits.submitScene(action,List.of(),List.of(),store(server,after));s.pendingSelected=selected;
    }
    private static Node require(Map<String,Node> nodes,String id)throws IOException {Node node=nodes.get(id);if(node==null)throw new IOException("Объект сцены больше не существует");return node;}
    private static void select(TestingServer.Context c,String id)throws IOException {
        State s=state(c.server);Node node=require(s.nodes,id);Summary bounds=summary(c.server,s,node,new HashMap<>());
        if(!bounds.loaded()||bounds.blocks()+bounds.entities()==0)throw new IOException("Загрузите фактические члены объекта");
        s.selected=id;c.a=bounds.min();c.b=bounds.max();
    }
    private static Members members(Map<String,Node> nodes,String id)throws IOException {
        require(nodes,id);Set<String> descendants=new LinkedHashSet<>();Deque<String> todo=new ArrayDeque<>();todo.add(id);
        Map<String,List<String>> children=new HashMap<>();for(Node node:nodes.values())children.computeIfAbsent(node.parent(),key->new ArrayList<>()).add(node.id());
        Set<BlockPos> blocks=new LinkedHashSet<>();Set<UUID> entities=new LinkedHashSet<>();
        while(!todo.isEmpty()){String next=todo.removeFirst();if(!descendants.add(next))throw new IOException("Цикл сцены");
            Node node=nodes.get(next);node.blocks().forEach(p->blocks.add(BlockPos.of(p)));entities.addAll(node.entities());
            todo.addAll(children.getOrDefault(next,List.of()));}
        return new Members(blocks,entities,descendants);
    }
    private static boolean entityProtected(TestingServer.Context c,CompoundTag tag)throws IOException {
        if(tag==null)return false;AABB bounds=TestingEntities.bounds(c.server.overworld(),tag);
        return c.zones.entrySet().stream().anyMatch(e->{var z=e.getValue();return c.zoneRules.getOrDefault(e.getKey(),new TestingServer.ZoneRule(true,true)).objects()&&bounds.intersects(new AABB(z.x0(),z.y0(),z.z0(),z.x1()+1d,z.y1()+1d,z.z1()+1d));});
    }
    private static void transform(TestingServer.Context c,String id,BlockPos delta,String action)throws IOException {
        State s=state(c.server);var world=c.server.overworld();Node root=require(s.nodes,id);Members members=members(s.nodes,id);
        if(members.blocks().isEmpty()&&members.entities().isEmpty())throw new IOException("Группа пуста");
        if(members.blocks().size()>8192||members.entities().size()>TestingEntities.LIMIT)throw new IOException("За один перенос: до 8192 блоков и 32 NPC");
        boolean copy=action.equals("scene_copy"),remove=action.equals("scene_remove"),rotate=action.equals("scene_rotate");
        if(!rotate&&!remove&&delta.equals(BlockPos.ZERO))throw new IOException("Укажите ненулевое смещение");
        Summary bounds=summary(c.server,s,root,new HashMap<>());if(!bounds.loaded())throw new IOException("Загрузите всех членов группы");
        BlockPos pivot=bounds.min();
        UnaryOperator<BlockPos> mapping=p->rotate?new BlockPos(pivot.getX()-(p.getZ()-pivot.getZ()),p.getY(),pivot.getZ()+(p.getX()-pivot.getX())):p.offset(delta);
        UnaryOperator<Vec3> entityMapping=p->rotate?new Vec3(pivot.getX()+.5-(p.z-(pivot.getZ()+.5)),p.y,pivot.getZ()+.5+(p.x-(pivot.getX()+.5))):p.add(delta.getX(),delta.getY(),delta.getZ());
        TestingStructures.validate(world,members.blocks());
        Map<BlockPos,TestingEdits.Cell> destinations=new LinkedHashMap<>();
        for(BlockPos pos:members.blocks()) {
            var before=c.edits.read(pos);
            if(!authored(before.origin())||before.state().isAir()||TestingServer.zoneProtected(c,pos))throw new IOException("Авторский объект изменён или защищён: "+pos.toShortString());
            if(remove)continue;BlockPos target=mapping.apply(pos);
            if(world.isOutsideBuildHeight(target)||!world.getWorldBorder().isWithinBounds(target)||!world.hasChunkAt(target)||TestingServer.zoneProtected(c,target))throw new IOException("Недопустимая цель: "+target.toShortString());
            if((copy||!members.blocks().contains(target))&&!world.getBlockState(target).canBeReplaced())throw new IOException("Цель занята: "+target.toShortString());
            var after=new TestingEdits.Cell(rotate?before.state().rotate(Rotation.CLOCKWISE_90):before.state(),TestingStructures.relocate(before.entity(),mapping),before.origin());
            if(destinations.put(target,after)!=null)throw new IOException("Члены группы пересекаются");
        }
        if(!remove){Map<BlockPos,BlockState> states=new LinkedHashMap<>();Map<BlockPos,CompoundTag> tags=new LinkedHashMap<>();destinations.forEach((pos,cell)->{states.put(pos,cell.state());if(cell.entity()!=null)tags.put(pos,cell.entity());});TestingStructures.validate(states,tags);}
        List<TestingEdits.Change> changes=new ArrayList<>();Set<BlockPos> all=new LinkedHashSet<>();if(!copy)all.addAll(members.blocks());all.addAll(destinations.keySet());
        for(BlockPos pos:all){var before=c.edits.read(pos);changes.add(new TestingEdits.Change(pos,before,destinations.getOrDefault(pos,new TestingEdits.Cell(Blocks.AIR.defaultBlockState(),null,before.origin()))));}
        List<TestingEntities.Change> entities=new ArrayList<>();Map<UUID,UUID> copiedEntities=new HashMap<>();
        for(UUID oldId:members.entities()) {
            CompoundTag before=TestingEntities.snapshot(world,oldId);if(before==null)throw new IOException("NPC отсутствует или выгружен");
            UUID nextId=copy?UUID.randomUUID():oldId;
            CompoundTag after=remove?null:TestingEntities.copyAt(before,entityMapping.apply(TestingEntities.position(before)),rotate?90:0,nextId);
            if(entityProtected(c,before)||entityProtected(c,after))throw new IOException("NPC находится в защищённой зоне");
            entities.add(new TestingEntities.Change(nextId,copy?null:before,after));if(copy)copiedEntities.put(oldId,nextId);
        }
        var nodes=copy(s);String selection=id;
        if(remove){members.nodes().forEach(nodes::remove);selection="";}
        else if(copy) {
            Map<String,String> ids=new LinkedHashMap<>();members.nodes().forEach(key->ids.put(key,UUID.randomUUID().toString()));
            for(String oldId:members.nodes()) {
                Node node=s.nodes.get(oldId);String name=oldId.equals(id)?node.name().substring(0,Math.min(66,node.name().length()))+" — копия":node.name();
                String parent=oldId.equals(id)?node.parent():ids.get(node.parent());
                Node clone=new Node(ids.get(oldId),name,parent,node.kind(),node.blocks().stream().map(p->mapping.apply(BlockPos.of(p)).asLong()).toList(),node.entities().stream().map(copiedEntities::get).toList());
                nodes.put(clone.id(),clone);
            }selection=ids.get(id);
        } else for(String member:members.nodes()) {
            Node node=nodes.get(member);nodes.put(member,new Node(node.id(),node.name(),node.parent(),node.kind(),node.blocks().stream().map(p->mapping.apply(BlockPos.of(p)).asLong()).toList(),node.entities()));
        }
        repairParents(nodes);validate(nodes);c.edits.submitScene(action+": "+root.name(),changes,entities,store(c.server,nodes));s.pendingSelected=selection;
    }

    private static void publish(MinecraftServer server,State s) {
        Map<String,Summary> cache=new HashMap<>();List<ObjectView> list=new ArrayList<>();Map<String,List<Node>> children=children(s);
        for(Node node:s.nodes.values()){Summary b=summary(server,s,node,cache,children);list.add(new ObjectView(node.id(),node.parent(),node.name(),node.kind(),b.blocks(),b.entities(),b.loaded(),b.min(),b.max()));}
        s.views=List.copyOf(list);
    }
    private static Map<String,List<Node>> children(State s) {
        Map<String,List<Node>> children=new HashMap<>();for(Node node:s.nodes.values())children.computeIfAbsent(node.parent(),key->new ArrayList<>()).add(node);return children;
    }
    private static Summary summary(MinecraftServer server,State s,Node node,Map<String,Summary> cache) {return summary(server,s,node,cache,children(s));}
    private static Summary summary(MinecraftServer server,State s,Node node,Map<String,Summary> cache,Map<String,List<Node>> children) {
        if(node==null)return new Summary(0,0,false,BlockPos.ZERO,BlockPos.ZERO);
        if(cache.containsKey(node.id()))return cache.get(node.id());
        var world=server.overworld();int blocks=0,npc=0;boolean loaded=true;BlockPos min=null,max=null;
        for(long packed:node.blocks()) {
            BlockPos p=BlockPos.of(packed);blocks++;loaded&=world.hasChunkAt(p)&&!world.getBlockState(p).isAir();
            min=min==null?p:new BlockPos(Math.min(min.getX(),p.getX()),Math.min(min.getY(),p.getY()),Math.min(min.getZ(),p.getZ()));
            max=max==null?p:new BlockPos(Math.max(max.getX(),p.getX()),Math.max(max.getY(),p.getY()),Math.max(max.getZ(),p.getZ()));
        }
        for(UUID id:node.entities()) {
            npc++;Entity entity=world.getEntityInAnyDimension(id);
            if(entity==null||entity.level()!=world||!TestingEntities.supported(entity)||entity.isPassenger()||entity.isVehicle()||entity instanceof net.minecraft.world.entity.Leashable leash&&leash.isLeashed()){loaded=false;continue;}
            var box=entity.getBoundingBox();BlockPos lo=BlockPos.containing(box.minX,box.minY,box.minZ),hi=BlockPos.containing(Math.nextDown(box.maxX),Math.nextDown(box.maxY),Math.nextDown(box.maxZ));
            min=min==null?lo:new BlockPos(Math.min(min.getX(),lo.getX()),Math.min(min.getY(),lo.getY()),Math.min(min.getZ(),lo.getZ()));
            max=max==null?hi:new BlockPos(Math.max(max.getX(),hi.getX()),Math.max(max.getY(),hi.getY()),Math.max(max.getZ(),hi.getZ()));
        }
        for(Node child:children.getOrDefault(node.id(),List.of())) {
            Summary b=summary(server,s,child,cache,children);blocks+=b.blocks();npc+=b.entities();loaded&=b.loaded();
            if(b.blocks()+b.entities()>0){min=min==null?b.min():new BlockPos(Math.min(min.getX(),b.min().getX()),Math.min(min.getY(),b.min().getY()),Math.min(min.getZ(),b.min().getZ()));
                max=max==null?b.max():new BlockPos(Math.max(max.getX(),b.max().getX()),Math.max(max.getY(),b.max().getY()),Math.max(max.getZ(),b.max().getZ()));}
        }
        Summary result=new Summary(blocks,npc,loaded,min==null?BlockPos.ZERO:min,max==null?BlockPos.ZERO:max);cache.put(node.id(),result);return result;
    }
}
