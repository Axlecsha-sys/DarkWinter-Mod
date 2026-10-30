// Server-thread transactions, block-entity payloads, provenance and durable undo/redo.
package com.dark.winter.testing;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread transactions, block-entity payloads, provenance and durable undo/redo. */
final class TestingEdits {
    static final int LIMIT = 131072;
    record Cell(BlockState state, CompoundTag entity, String origin,boolean legacyJson) {
        Cell(BlockState state,CompoundTag entity,String origin){this(state,entity,origin,false);}
    }
    record Change(BlockPos pos, Cell before, Cell after) {}
    record Operation(String name,List<Change> changes,String biomeBefore,String biomeAfter,String resizeId,
                     List<TestingEntities.Change> entities,String id,String sceneBefore,String sceneAfter,String documentsBefore,String documentsAfter,
                     String actor,String actorSession,String actorPermissions,String historySource) {
        Operation {TestingActors.validate(actor,actorSession,actorPermissions);if(historySource!=null&&!UUID.fromString(historySource).toString().equals(historySource))throw new IllegalArgumentException("Invalid history source");}
        Operation(String name,List<Change> changes,String before,String after,String resize,List<TestingEntities.Change> entities,String id,String sceneBefore,String sceneAfter,String documentsBefore,String documentsAfter) {
            this(name,changes,before,after,resize,entities,id,sceneBefore,sceneAfter,documentsBefore,documentsAfter,TestingActors.actor(),TestingActors.session(),TestingActors.permissions(),null);
        }
        Operation(String name,List<Change> changes,String before,String after,String resize,List<TestingEntities.Change> entities,String id,String sceneBefore,String sceneAfter) {
            this(name,changes,before,after,resize,entities,id,sceneBefore,sceneAfter,null,null);
        }
        Operation(String name,List<Change> changes,String before,String after,String resize,List<TestingEntities.Change> entities,String id) {
            this(name,changes,before,after,resize,entities,id,null,null);
        }
        Operation(String name,List<Change> changes,String before,String after,String resize) {
            this(name,changes,before,after,resize,List.of(),UUID.randomUUID().toString());
        }
        Operation(String name,List<Change> changes,String before,String after) {this(name,changes,before,after,null);}
        Operation(String name,List<Change> changes) {this(name,changes,null,null);}
    }
    final Map<Long,String> origins = new java.util.concurrent.ConcurrentHashMap<>();
    private final Deque<Operation> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final ServerLevel world;
    private final Path directory;
    private Operation active;
    private record Queued(Operation operation,int direction,String token,boolean frozen,
            Set<net.minecraft.world.level.ChunkPos> chunks,
            Map<net.minecraft.world.level.ChunkPos,java.util.concurrent.CompletableFuture<Optional<CompoundTag>>> disk,long started) {}
    private Queued queued;
    private String activeLease;
    private int applied,entityApplied,recoveryBlocks,recoveryEntities;
    private Operation pendingRecovery;
    private boolean recoveryForward,recoveryValidated,recoveryRefsValidated;
    private volatile boolean recoveryFailed;
    private java.util.Map<net.minecraft.world.level.ChunkPos,java.util.concurrent.CompletableFuture<Optional<CompoundTag>>> recoveryDiskChecks;
    private long recoveryStarted;
    private Set<net.minecraft.world.level.ChunkPos> recoveryChunks=Set.of();
    private boolean wasFrozen,resizeReplaying;
    private int direction; // 0 author, 1 undo, 2 redo
    private String status = "Готов";
    private TestingNativeJournal nativeJournal;
    private long nativeBaseSequence;
    private boolean nativeRecovering,nativeCommittedFailure,nativeBarrierFrozen;
    private Runnable nativeDeferred;
    private TestingActors.Captured nativeDeferredActor;
    private record CachedHistory(JsonObject json,String text) {}
    private final Map<String,CachedHistory> nativeHistoryCache=new HashMap<>();
    record NativeMetadata(long sequence,List<String> undo,List<String> redo,Map<Long,String> origins) {
        NativeMetadata {undo=List.copyOf(undo);redo=List.copyOf(redo);origins=Map.copyOf(origins);}
    }

    TestingEdits(ServerLevel world, Path directory) throws IOException {
        this.world=world;this.directory=directory;Files.createDirectories(directory);
        try {
        Path file=directory.resolve("provenance.json");
        if(Files.exists(file)) {
            JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(root.get("schema").getAsInt()!=2) throw new IOException("Unknown provenance schema");
            root.getAsJsonObject("origins").entrySet().forEach(e->origins.put(Long.parseLong(e.getKey()),e.getValue().getAsString()));
        }
        Path history=directory.resolve("undo.json");
        if(Files.exists(history)) {
            JsonObject root=JsonParser.parseString(Files.readString(history)).getAsJsonObject();
            nativeBaseSequence=root.has("nativeSequence")?root.get("nativeSequence").getAsLong():0;
            for(JsonElement e:root.getAsJsonArray("undo")) {Operation op=decode(e.getAsJsonObject());undo.addLast(op);cacheHistory(op,e.getAsJsonObject());}
            for(JsonElement e:root.getAsJsonArray("redo")) {Operation op=decode(e.getAsJsonObject());redo.addLast(op);cacheHistory(op,e.getAsJsonObject());}
        }
        Path pending=directory.resolve("pending-edit.json");boolean pendingNative=false;
        if(Files.exists(pending)) {
            JsonObject payload=JsonParser.parseString(Files.readString(pending)).getAsJsonObject();
            pendingRecovery=decode(payload);pendingNative=payload.has("nativeLedger")&&payload.get("nativeLedger").getAsBoolean();
        }
        nativeJournal=new TestingNativeJournal(this,world,directory,nativeBaseSequence);
        Operation nativeRecovery=nativeJournal.recovery(pendingRecovery,pendingNative);
        if(nativeRecovery!=null){pendingRecovery=nativeRecovery;nativeRecovering=true;recoveryForward=true;}
        else if(pendingRecovery!=null){
            String id=pendingRecovery.id();recoveryForward=undo.stream().anyMatch(op->op.id().equals(id))||redo.stream().anyMatch(op->op.id().equals(id));
        }
        if(pendingRecovery!=null){
            wasFrozen=world.getServer().tickRateManager().isFrozen();world.getServer().tickRateManager().setFrozen(true);
            status="Восстановление незавершённой транзакции";
        }
        }catch(Exception error) {
            recoveryFailed=true;wasFrozen=world.getServer().tickRateManager().isFrozen();world.getServer().tickRateManager().setFrozen(true);
            status="Повреждены метаданные/контрольная сумма журнала. Доступен только полный откат из Истории: "+error.getMessage();
            org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(status,error);
        }
    }
    Cell read(BlockPos pos) {
        if(world.isOutsideBuildHeight(pos)||!world.hasChunkAt(pos))
            throw new IllegalArgumentException("Координаты находятся вне загруженной области редактора: "+pos);
        BlockEntity be=world.getBlockEntity(pos);
        return new Cell(world.getBlockState(pos),be==null?null:be.saveWithFullMetadata(world.registryAccess()), origins.get(pos.asLong()));
    }
    Change change(BlockPos pos,BlockState state,String origin) {
        return new Change(pos.immutable(),read(pos),new Cell(state,null,origin));
    }
    boolean busy() {return nativeDeferred!=null||queued!=null||active!=null||resizeReplaying||pendingRecovery!=null||recoveryFailed;}
    boolean recoveryBlocked(){return recoveryFailed&&active==null&&!resizeReplaying;}
    boolean writing(){boolean failed=recoveryFailed;return nativeDeferred!=null||queued!=null||active!=null||resizeReplaying||pendingRecovery!=null&&!failed;}
    boolean documentsBusy() {return queued!=null&&queued.operation().documentsAfter()!=null||active!=null&&active.documentsAfter()!=null||pendingRecovery!=null&&pendingRecovery.documentsAfter()!=null;}
    String status() { return status+" • Undo "+undo.size()+" / Redo "+redo.size(); }
    private boolean same(Cell a,Cell b) {return a.state().equals(b.state())&&Objects.equals(a.entity(),b.entity())&&Objects.equals(a.origin(),b.origin());}
    /**
     * Adopt a completed native EDIT interaction before returning to vanilla.
     * Native ChunkMap snapshots are made on this thread; the scoped observer
     * defers reentrant saves until the provisional WAL is durably published.
     */
    void recordApplied(String label,List<Change> actual) throws IOException {
        if(!world.getServer().isSameThread())throw new IOException("Native history requires the server thread");
        if(busy())throw new IOException("An editor transaction is already active");
        if(actual.isEmpty())return;
        nativeCommittedFailure=false;boolean committed=false;
        Deque<Operation> undoBefore=new ArrayDeque<>(undo),redoBefore=new ArrayDeque<>(redo);
        Operation candidate=null;
        wasFrozen=world.getServer().tickRateManager().isFrozen();world.getServer().tickRateManager().setFrozen(true);
        try {
            if(actual.size()>LIMIT)throw new IOException("Native action exceeds the editor transaction limit");
            List<Change> changes=new ArrayList<>();Set<BlockPos> unique=new HashSet<>();long bytes=0;
            for(Change change:actual) {
                if(!unique.add(change.pos())||world.isOutsideBuildHeight(change.pos())||!world.hasChunkAt(change.pos())
                        ||!world.getWorldBorder().isWithinBounds(change.pos()))throw new IOException("Invalid native action footprint");
                Cell before=normalize(change.pos(),change.before()),after=normalize(change.pos(),change.after());
                if(!sameNative(read(change.pos()),after))throw new IOException("Native action changed before history capture: "+change.pos());
                if(before.entity()!=null)bytes+=before.entity().sizeInBytes();if(after.entity()!=null)bytes+=after.entity().sizeInBytes();
                if(bytes>64L*1024*1024)throw new IOException("Native container payloads exceed 64 MiB");
                if(!same(before,after))changes.add(new Change(change.pos().immutable(),before,after));
            }
            if(changes.isEmpty()){restoreFrozen();return;}
            // No native chunk snapshot or live scene may be published before this immutable redo commit.
            var scene=TestingScene.prepareNative(world.getServer(),changes);
            candidate=new Operation(label,List.copyOf(changes),null,null,null,List.of(),UUID.randomUUID().toString(),scene.before(),scene.after());
            TestingActors.authorizeFresh(world.getServer(),candidate);
            active=candidate;undo.addFirst(candidate);redo.clear();
            while(undo.size()>64||(undo.size()>1&&undo.stream().mapToLong(operation->operation.changes().size()).sum()>131072))undo.removeLast();
            nativeJournal.commit(candidate);committed=true;TestingNativeEdits.allowSave(world.getServer());
            for(Change change:changes){
                if(change.after().origin()==null)origins.remove(change.pos().asLong());else origins.put(change.pos().asLong(),change.after().origin());
                if("terrain".equals(change.before().origin())||"surface".equals(change.before().origin()))
                    TestingServer.terrainTouched(world.getServer(),change.pos());
            }
            TestingScene.apply(world.getServer(),candidate.sceneAfter());
            active=null;restoreFrozen();status=label+": записано в Undo/Redo";
        }catch(Exception error){
            if(committed){
                nativeCommittedFailure=true;active=null;recoveryFailed=true;world.getServer().tickRateManager().setFrozen(true);
                status="Нативное изменение сохранено в REDO-журнале; нужна проверка хранения: "+error.getMessage();
                throw new IOException(status,error);
            }
            undo.clear();undo.addAll(undoBefore);redo.clear();redo.addAll(redoBefore);
            try{
                // The scoped ChunkMap guard still holds; restore RAM before vanilla may save the chunk.
                List<Change> rollback=candidate==null?actual:candidate.changes();
                for(int i=rollback.size()-1;i>=0;i--){Change change=rollback.get(i);write(change.pos(),change.before());}
                active=null;restoreFrozen();status="Нативное изменение отменено: "+error.getMessage();
            }catch(Exception rollback){
                recoveryFailed=true;active=null;world.getServer().tickRateManager().setFrozen(true);
                status="Нативное изменение остановлено до записи; сохранённый мир не менялся: "+rollback.getMessage();
                error.addSuppressed(rollback);
            }
            throw new IOException("Native edit could not be committed",error);
        }
    }
    void submit(String name,List<Change> changes) throws IOException { submit(new Operation(name,changes)); }
    void submit(String name,List<Change> changes,List<TestingEntities.Change> entities) throws IOException {
        submit(new Operation(name,changes,null,null,null,List.copyOf(entities),UUID.randomUUID().toString()));
    }
    void submitScene(String name,List<Change> changes,List<TestingEntities.Change> entities,String after)throws IOException {
        submit(new Operation(name,changes,null,null,null,List.copyOf(entities),UUID.randomUUID().toString(),null,after));
    }
    void submitBiome(String name,List<Change> changes,String before,String after) throws IOException {
        submit(new Operation(name,changes,before,after));
    }
    void submitBiome(String name,List<Change> changes,String before,String after,List<TestingEntities.Change> entities)throws IOException {
        submit(new Operation(name,changes,before,after,null,List.copyOf(entities),UUID.randomUUID().toString()));
    }
    void submitDocuments(String name,List<Change> changes,String biomeBefore,String biomeAfter,List<TestingEntities.Change> entities,String documentsAfter)throws IOException {
        submit(new Operation(name,changes,biomeBefore,biomeAfter,null,List.copyOf(entities),UUID.randomUUID().toString(),null,null,null,documentsAfter));
    }
    void submitSceneDocuments(String name,List<Change> changes,List<TestingEntities.Change> entities,
            String sceneAfter,JsonObject scenarioDocuments)throws IOException {
        String documentRef=null;
        if(scenarioDocuments!=null) {
            JsonObject document=new JsonObject();document.addProperty("schema",1);document.add("scenarios",scenarioDocuments);
            documentRef=TestingDocuments.store(world.getServer(),document);
        }
        submit(new Operation(name,changes,null,null,null,List.copyOf(entities),UUID.randomUUID().toString(),null,sceneAfter,null,documentRef));
    }
    Set<String> sceneRefs(){
        Set<String> refs=new HashSet<>();List<Operation> operations=new ArrayList<>(undo);operations.addAll(redo);
        if(active!=null)operations.add(active);if(pendingRecovery!=null)operations.add(pendingRecovery);
        for(Operation operation:operations){if(operation.sceneBefore()!=null)refs.add(operation.sceneBefore());if(operation.sceneAfter()!=null)refs.add(operation.sceneAfter());}
        if(nativeJournal!=null)refs.addAll(nativeJournal.sceneRefs());return refs;
    }
    Set<String> documentRefs() {
        Set<String> refs=new HashSet<>();List<Operation> all=new ArrayList<>(undo);all.addAll(redo);
        if(active!=null)all.add(active);if(pendingRecovery!=null)all.add(pendingRecovery);
        for(var operation:all){if(operation.documentsBefore()!=null)refs.add(operation.documentsBefore());if(operation.documentsAfter()!=null)refs.add(operation.documentsAfter());}
        return refs;
    }
    private void submit(Operation operation) throws IOException {submit(operation,0);}
    /** Load only saved history chunks before preflight; no world writes or WAL until the whole footprint is ready. */
    private void submit(Operation operation,int requestedDirection) throws IOException {
        if(TestingActors.actor()!=null)operation=new Operation(operation.name(),operation.changes(),operation.biomeBefore(),operation.biomeAfter(),operation.resizeId(),operation.entities(),operation.id(),operation.sceneBefore(),operation.sceneAfter(),operation.documentsBefore(),operation.documentsAfter(),TestingActors.actor(),TestingActors.session(),TestingActors.permissions(),operation.historySource());
        submitBound(operation,requestedDirection);
    }
    private void submitBound(Operation operation,int requestedDirection) throws IOException {
        TestingActors.authorizeFresh(world.getServer(),operation);
        if(deferNativeAction(()->{try{submitBound(operation,requestedDirection);}catch(IOException error){status="Операция не начата: "+error.getMessage();}}))return;
        if(busy())throw new IOException("Дождитесь окончания операции");
        if(operation.changes().size()>LIMIT||operation.entities().size()>TestingEntities.LIMIT)
            throw new IOException("Операция превышает лимит редактора");
        TestingScene.validateRef(world.getServer(),operation.sceneBefore());
        TestingScene.validateRef(world.getServer(),operation.sceneAfter());
        String target=operation.documentsAfter();
        if(operation.biomeAfter()!=null)target=TestingDocuments.withBiomeTarget(world.getServer(),target,operation.biomeAfter());
        Operation prepared=new Operation(operation.name(),operation.changes(),operation.biomeBefore(),operation.biomeAfter(),
                operation.resizeId(),operation.entities(),operation.id(),operation.sceneBefore(),operation.sceneAfter(),operation.documentsBefore(),target,operation.actor(),operation.actorSession(),operation.actorPermissions(),operation.historySource());
        var chunks=new LinkedHashSet<net.minecraft.world.level.ChunkPos>();
        chunks.addAll(TestingDocuments.requiredChunks(world.getServer(),prepared.documentsBefore()));
        chunks.addAll(TestingDocuments.requiredChunks(world.getServer(),prepared.documentsAfter()));
        Set<BlockPos> unique=new HashSet<>();
        long bytes=0;
        for(Change change:prepared.changes()) {
            BlockPos pos=change.pos();
            if(!unique.add(pos)||world.isOutsideBuildHeight(pos)||Math.abs((long)pos.getX())>29999984
                    ||Math.abs((long)pos.getZ())>29999984||!world.getWorldBorder().isWithinBounds(pos))
                throw new IOException("Недопустимая область операции: "+pos);
            Cell before=normalize(pos,change.before()),after=normalize(pos,change.after());
            if(before.entity()!=null)bytes+=before.entity().sizeInBytes();
            if(after.entity()!=null)bytes+=after.entity().sizeInBytes();
            chunks.add(net.minecraft.world.level.ChunkPos.containing(pos));
        }
        Set<UUID> ids=new HashSet<>();
        for(var change:prepared.entities()) {
            if(!ids.add(change.id()))throw new IOException("Повторяющийся UUID NPC");
            for(CompoundTag tag:new CompoundTag[]{change.before(),change.after()})if(tag!=null) {
                bytes+=tag.sizeInBytes();
                var point=TestingEntities.position(tag);BlockPos pos=BlockPos.containing(point);
                if(Math.abs(point.x)>29999984||Math.abs(point.z)>29999984||world.isOutsideBuildHeight(pos)
                        ||!world.getWorldBorder().isWithinBounds(pos))throw new IOException("NPC вне допустимой области");
                chunks.add(net.minecraft.world.level.ChunkPos.containing(pos));
            }
        }
        if(bytes>64L*1024*1024||chunks.size()>4096)throw new IOException("Данные или область операции превышают лимит");
        var disk=new LinkedHashMap<net.minecraft.world.level.ChunkPos,java.util.concurrent.CompletableFuture<Optional<CompoundTag>>>();
        for(var chunk:chunks)if(!world.hasChunk(chunk.x(),chunk.z()))disk.put(chunk,null);
        String token="edit-"+UUID.randomUUID();
        boolean frozen=world.getServer().tickRateManager().isFrozen();
        queued=new Queued(prepared,requestedDirection,token,frozen,Set.copyOf(chunks),disk,System.nanoTime());
        world.getServer().tickRateManager().setFrozen(true);
        status=operation.name()+": загрузка "+chunks.size()+" чанков";
    }
    private void queuedTick() {
        Queued request=queued;
        try(var actor=TestingActors.enterOperation(world.getServer(),request.operation())) {
            if(System.nanoTime()-request.started()>180_000_000_000L)throw new IOException("Загрузка области операции превысила время ожидания");
            // Bound saved-chunk verification too; completed raw NBT is released immediately.
            int inFlight=0;
            var diskEntries=request.disk().entrySet().iterator();
            while(diskEntries.hasNext()) {
                var entry=diskEntries.next();var future=entry.getValue();
                if(future==null)continue;
                if(!future.isDone()){inFlight++;continue;}
                Optional<CompoundTag> stored;
                try{stored=future.join();}catch(RuntimeException error){throw new IOException("Не удалось прочитать сохранённый чанк",error);}
                if(stored.isEmpty()||stored.get().read("Status",net.minecraft.world.level.chunk.status.ChunkStatus.CODEC)
                        .orElse(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY)!=net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                    throw new IOException("Undo/Redo не создаёт отсутствующие или повреждённые чанки: "+entry.getKey());
                diskEntries.remove();
            }
            int starts=0;
            for(var entry:request.disk().entrySet())if(entry.getValue()==null&&starts<8&&inFlight<8) {
                entry.setValue(world.getChunkSource().chunkMap.read(entry.getKey()));starts++;inFlight++;
            }
            if(!request.disk().isEmpty())return;
            if(!TestingImportLoader.prepareChunks(world.getServer(),request.token(),request.chunks()))return;
            TestingScene.validateRef(world.getServer(),request.operation().sceneBefore());
            TestingScene.validateRef(world.getServer(),request.operation().sceneAfter());
            if(request.operation().documentsBefore()!=null)TestingDocuments.read(world.getServer(),request.operation().documentsBefore());
            if(request.operation().documentsAfter()!=null)TestingDocuments.read(world.getServer(),request.operation().documentsAfter());
            queued=null;activeLease=request.token();
            submitLoaded(request.operation());
            direction=request.direction();wasFrozen=request.frozen();
            if(active==null)restoreFrozen();
        }catch(Exception error) {
            queued=null;wasFrozen=request.frozen();
            TestingImportLoader.releaseChunks(world.getServer(),request.token());activeLease=null;restoreFrozen();
            status="Операция отклонена до записи: "+error.getMessage();
            org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").warn(status,error);
        }
    }
    private void submitLoaded(Operation operation) throws IOException {
        TestingActors.authorizeFresh(world.getServer(),operation);
        List<Change> normalized=new ArrayList<>();
        for(Change cell:operation.changes())normalized.add(new Change(cell.pos(),normalize(cell.pos(),cell.before()),normalize(cell.pos(),cell.after())));
        String name=operation.name();List<Change> changes=List.copyOf(normalized);
        if(busy()) throw new IOException("Дождитесь окончания операции");
        if(changes.isEmpty()&&operation.biomeAfter()==null&&operation.entities().isEmpty()&&operation.sceneAfter()==null&&operation.documentsAfter()==null) { status="Нет разрешённых изменений";return; }
        if(changes.size()>LIMIT) throw new IOException("Операция превышает "+LIMIT+" блоков; уменьшите область");
        if(operation.entities().size()>TestingEntities.LIMIT)throw new IOException("Слишком много NPC в операции");
        Set<UUID> entityIds=new HashSet<>();
        for(var entity:operation.entities()){if(!entityIds.add(entity.id()))throw new IOException("Повторяющийся UUID NPC");TestingEntities.validate(world,entity);}
        long nativeBytes=0;
        for(var entity:operation.entities()) {
            if(entity.before()!=null)nativeBytes+=entity.before().sizeInBytes();
            if(entity.after()!=null)nativeBytes+=entity.after().sizeInBytes();
        }
        Set<BlockPos> unique=new HashSet<>();
        for(Change c:changes) {
            if(c.before().entity()!=null)nativeBytes+=c.before().entity().sizeInBytes();
            if(c.after().entity()!=null)nativeBytes+=c.after().entity().sizeInBytes();
            if(nativeBytes>64L*1024*1024)throw new IOException("Суммарные данные контейнеров и NPC превышают 64 МиБ");
            if(c.after().entity()!=null) {
                BlockEntity candidate=BlockEntity.loadStatic(c.pos(),c.after().state(),c.after().entity(),world.registryAccess());
                if(candidate==null||!candidate.getType().isValid(c.after().state()))throw new IOException("Недопустимые данные контейнера в "+c.pos());
            }
            if(!unique.add(c.pos()) || world.isOutsideBuildHeight(c.pos()) || !world.hasChunkAt(c.pos())
                    || !same(read(c.pos()),c.before())) throw new IOException("Конфликт или незагруженная область: "+c.pos());
        }
        var scene=TestingScene.prepare(world.getServer(),changes,operation.entities(),operation.sceneBefore(),operation.sceneAfter());
        String documentTarget=operation.documentsAfter();
        if(operation.biomeAfter()!=null)documentTarget=TestingDocuments.withBiomeTarget(world.getServer(),documentTarget,operation.biomeAfter());
        var documents=TestingDocuments.prepare(world.getServer(),operation.documentsBefore(),documentTarget);
        if(changes.isEmpty()&&operation.biomeAfter()==null&&operation.entities().isEmpty()&&scene.after()==null&&documents.after()==null){status="Сцена уже соответствует запросу";return;}
        Operation next=new Operation(name,List.copyOf(changes),operation.biomeBefore(),operation.biomeAfter(),operation.resizeId(),operation.entities(),operation.id(),scene.before(),scene.after(),documents.before(),documents.after(),operation.actor(),operation.actorSession(),operation.actorPermissions(),operation.historySource());
        atomic(directory.resolve("pending-edit.json"),encode(next));
        active=next;applied=0;entityApplied=0;direction=0;
        wasFrozen=world.getServer().tickRateManager().isFrozen();
        world.getServer().tickRateManager().setFrozen(true);
        status=name+": 0 / "+changes.size();
    }
    void tick() {
        if(recoveryFailed)return;
        if(!nativeRecovering&&nativeJournal!=null){
            try{
                nativeJournal.tick();
                if(nativeDeferred!=null){
                    if(!nativeJournal.drained())return;
                    Runnable next=nativeDeferred;TestingActors.Captured author=nativeDeferredActor;nativeDeferred=null;nativeDeferredActor=null;
                    var server=world.getServer();var context=TestingServer.context(server);
                    world.getServer().tickRateManager().setFrozen(nativeBarrierFrozen);
                    if(!server.isRunning()||!server.isSingleplayer()||context==null||context.edits!=this||server.overworld()!=world){
                        status="Отложенное действие отменено: полномочия или авторский мир изменились";return;
                    }
                    try(var actor=TestingActors.enterCaptured(server,author)){
                        if(server.isPublished()&&!TestingActors.managedScope(server)){status="Отложенное действие отменено: опубликован обычный LAN-мир";return;}
                        next.run();
                    }catch(IOException revoked){status="Отложенное действие отменено: полномочия автора изменились";}
                    return;
                }
            }catch(Exception failure){
                recoveryFailed=true;world.getServer().tickRateManager().setFrozen(true);
                status="REDO-журнал сохранён; контрольная запись остановлена: "+failure.getMessage();
                org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(status,failure);return;
            }
        }
        if(queued!=null){queuedTick();return;}
        if(pendingRecovery!=null){recoverTick();return;}
        if(active==null) return;
        Deque<Operation> undoBefore=new ArrayDeque<>(undo),redoBefore=new ArrayDeque<>(redo);
        try(var actor=TestingActors.enterOperation(world.getServer(),active)) {
            TestingActors.authorizeActive(world.getServer(),active);
            int stop=Math.min(active.changes().size(),applied+512);
            while(applied<stop) {
                Change c=active.changes().get(applied);
                if(!same(read(c.pos()),c.before())) throw new IOException("Мир изменился в "+c.pos());
                applied++;write(c.pos(),c.after());
            }
            status=active.name()+": "+applied+" / "+active.changes().size();
            if(applied==active.changes().size()) {
                while(entityApplied<active.entities().size()) {var change=active.entities().get(entityApplied++);TestingEntities.apply(world,change,true);}
                Operation done=active;
                if(done.biomeAfter()!=null) {
                    if(TestingDocuments.hasBiomes(world.getServer(),done.documentsAfter()))TestingServer.biomeCommitted(world.getServer(),done.biomeAfter());
                    else TestingTerrain.applyBiome(world,done.biomeAfter());
                }
                TestingScene.apply(world.getServer(),done.sceneAfter());
                TestingDocuments.apply(world.getServer(),done.documentsAfter());
                if(direction==0) {undo.addFirst(done);redo.clear();}
                else if(direction==1) {removeHistory(undo,done.historySource());redo.addFirst(reverse(done));}
                else {removeHistory(redo,done.historySource());undo.addFirst(done);}
                while(undo.size()>64 || (undo.size()>1&&undo.stream().mapToLong(o->o.changes().size()).sum()>131072)) undo.removeLast();
                save();TestingServer.flushMetadata(world.getServer());world.getServer().saveEverything(false,true,true);
                Files.deleteIfExists(directory.resolve("pending-edit.json"));
                active=null;restoreFrozen();
                status=done.name()+": завершено";
            }
        } catch(Exception error) {
            undo.clear();undo.addAll(undoBefore);redo.clear();redo.addAll(redoBefore);
            try(var restoring=new TestingActors.RestoreScope(world.getServer())) {
                for(int i=entityApplied-1;i>=0;i--)TestingEntities.recover(world,active.entities().get(i),false);
                for(int i=applied-1;i>=0;i--){Change c=active.changes().get(i);write(c.pos(),c.before());}
                if(active.biomeBefore()!=null) {
                    if(TestingDocuments.hasBiomes(world.getServer(),active.documentsBefore()))TestingServer.biomeCommitted(world.getServer(),active.biomeBefore());
                    else TestingTerrain.applyBiome(world,active.biomeBefore());
                }
                TestingScene.apply(world.getServer(),active.sceneBefore());
                TestingDocuments.apply(world.getServer(),active.documentsBefore());
                save();TestingServer.flushMetadata(world.getServer());world.getServer().saveEverything(false,true,true);
                Files.deleteIfExists(directory.resolve("pending-edit.json"));
                active=null;restoreFrozen();status="Операция отменена: "+error.getMessage();
            }catch(Exception recoveryError) {
                pendingRecovery=active;active=null;recoveryFailed=true;
                status="Ошибка восстановления. Журнал сохранён; откройте резервную версию: "+recoveryError.getMessage();
                org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(status,recoveryError);
            }
        }
    }
    private void restoreFrozen() {
        if(activeLease!=null){TestingImportLoader.releaseChunks(world.getServer(),activeLease);activeLease=null;}
        var preload=com.dark.winter.loading.WorldPreloadServer.currentProgress(world.getServer());
        world.getServer().tickRateManager().setFrozen(wasFrozen || (preload!=null&&preload.active()));
    }
    private void recoverTick() {
        if(recoveryFailed)return;
        try {
            if(!recoveryValidated&&!validateRecovery())return;
            int stop=Math.min(pendingRecovery.changes().size(),recoveryBlocks+512);
            while(recoveryBlocks<stop) {
                Change c=pendingRecovery.changes().get(recoveryBlocks++);
                world.getChunkAt(c.pos());write(c.pos(),recoveryForward?c.after():c.before());
                if(nativeRecovering&&("terrain".equals(c.before().origin())||"surface".equals(c.before().origin())
                        ||"terrain".equals(c.after().origin())||"surface".equals(c.after().origin())))
                    TestingServer.terrainTouched(world.getServer(),c.pos());
            }
            if(recoveryBlocks<pendingRecovery.changes().size())return;
            if(!TestingEntities.prepareRecovery(world,pendingRecovery.entities()))return;
            while(recoveryEntities<pendingRecovery.entities().size())
                TestingEntities.recover(world,pendingRecovery.entities().get(recoveryEntities++),recoveryForward);
            String biome=recoveryForward?pendingRecovery.biomeAfter():pendingRecovery.biomeBefore();
            if(biome!=null) {
                String documentRef=recoveryForward?pendingRecovery.documentsAfter():pendingRecovery.documentsBefore();
                if(TestingDocuments.hasBiomes(world.getServer(),documentRef))TestingServer.biomeCommitted(world.getServer(),biome);
                else TestingTerrain.applyBiome(world,biome);
            }
            TestingScene.apply(world.getServer(),recoveryForward?pendingRecovery.sceneAfter():pendingRecovery.sceneBefore());
            TestingDocuments.apply(world.getServer(),recoveryForward?pendingRecovery.documentsAfter():pendingRecovery.documentsBefore());
            save();TestingServer.flushMetadata(world.getServer());world.getServer().saveEverything(false,true,true);
            Files.deleteIfExists(directory.resolve("pending-edit.json"));TestingEntities.finishRecovery(world);
            if(nativeRecovering){nativeJournal.recovered();nativeRecovering=false;}
            pendingRecovery=null;restoreFrozen();status="Транзакция восстановлена после сбоя";
        }catch(Exception error) {
            recoveryFailed=true;TestingEntities.finishRecovery(world);
            status="Восстановление заблокировано конфликтом; журнал сохранён: "+error.getMessage();
            org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(status,error);
        }
    }
    /** Validate every immutable reference and every native before/after payload before the first recovery write. */
    private boolean validateRecovery() throws IOException {
        if(!recoveryRefsValidated) {
            TestingScene.validateRef(world.getServer(),pendingRecovery.sceneBefore());
            TestingScene.validateRef(world.getServer(),pendingRecovery.sceneAfter());
            if(pendingRecovery.documentsBefore()!=null)TestingDocuments.read(world.getServer(),pendingRecovery.documentsBefore());
            if(pendingRecovery.documentsAfter()!=null)TestingDocuments.read(world.getServer(),pendingRecovery.documentsAfter());
            var chunks=new LinkedHashSet<net.minecraft.world.level.ChunkPos>();
            chunks.addAll(TestingDocuments.requiredChunks(world.getServer(),pendingRecovery.documentsBefore()));
            chunks.addAll(TestingDocuments.requiredChunks(world.getServer(),pendingRecovery.documentsAfter()));
            for(var change:pendingRecovery.entities())for(CompoundTag tag:new CompoundTag[]{change.before(),change.after()})if(tag!=null) {
                var point=TestingEntities.position(tag);BlockPos pos=BlockPos.containing(point);
                if(Math.abs(point.x)>29999984||Math.abs(point.z)>29999984||world.isOutsideBuildHeight(pos)
                        ||!world.getWorldBorder().isWithinBounds(pos))throw new IOException("NPC recovery coordinate outside world bounds");
                chunks.add(net.minecraft.world.level.ChunkPos.containing(pos));
            }
            for(Change change:pendingRecovery.changes()) {
                BlockPos pos=change.pos();
                if(world.isOutsideBuildHeight(pos)||Math.abs((long)pos.getX())>29999984||Math.abs((long)pos.getZ())>29999984
                        ||!world.getWorldBorder().isWithinBounds(pos))throw new IOException("Recovery coordinate outside world bounds: "+pos);
                chunks.add(net.minecraft.world.level.ChunkPos.containing(pos));
                if(chunks.size()>4096)throw new IOException("Recovery exceeds 4096 chunks");
            }
            if(chunks.size()>4096)throw new IOException("Recovery exceeds 4096 total block and biome chunks");
            recoveryChunks=Set.copyOf(chunks);
            recoveryDiskChecks=new LinkedHashMap<>();recoveryStarted=System.nanoTime();
            for(var chunk:chunks)if(!world.getChunkSource().hasChunk(chunk.x(),chunk.z()))
                recoveryDiskChecks.put(chunk,world.getChunkSource().chunkMap.read(chunk));
            recoveryRefsValidated=true;
        }
        if(System.nanoTime()-recoveryStarted>60_000_000_000L)throw new IOException("Block recovery IO timed out");
        for(var entry:recoveryDiskChecks.entrySet()) {
            var future=entry.getValue();if(!future.isDone())return false;
            final Optional<CompoundTag> stored;
            try{stored=future.join();}catch(RuntimeException error){throw new IOException("Cannot read saved recovery chunk",error);}
            if(stored.isEmpty()||stored.get().read("Status",net.minecraft.world.level.chunk.status.ChunkStatus.CODEC)
                    .orElse(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY)!=net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                throw new IOException("Recovery cannot generate a missing or incomplete chunk: "+entry.getKey());
        }
        if(!TestingEntities.prepareRecovery(world,pendingRecovery.entities(),recoveryChunks))return false;
        for(var chunk:recoveryChunks)world.getChunk(chunk.x(),chunk.z());
        for(Change change:pendingRecovery.changes())world.getChunkAt(change.pos());
        if(nativeRecovering)nativeJournal.validateRecovery();
        List<Change> normalized=new ArrayList<>();
        for(Change change:pendingRecovery.changes()) {
            Cell before=normalize(change.pos(),change.before()),after=normalize(change.pos(),change.after()),current=read(change.pos());
            if(nativeRecovering?!nativeJournal.allowed(change.pos(),current):!sameNative(current,before)&&!sameNative(current,after))
                throw new IOException("Block recovery conflict before any writes: "+change.pos());
            normalized.add(new Change(change.pos(),before,after));
        }
        for(var entity:pendingRecovery.entities())TestingEntities.preflightRecovery(world,entity);
        Operation previous=pendingRecovery;
        pendingRecovery=new Operation(previous.name(),List.copyOf(normalized),previous.biomeBefore(),previous.biomeAfter(),previous.resizeId(),
                previous.entities(),previous.id(),previous.sceneBefore(),previous.sceneAfter(),previous.documentsBefore(),previous.documentsAfter(),previous.actor(),previous.actorSession(),previous.actorPermissions(),previous.historySource());
        recoveryValidated=true;return true;
    }
    private static boolean sameNative(Cell first,Cell second) {
        return first.state().equals(second.state())&&Objects.equals(first.entity(),second.entity());
    }
    void undo() throws IOException {
        if(busy())throw new IOException("Дождитесь окончания операции");
        if(undo.isEmpty()) return;
        Operation original=undo.peekFirst();
        if(original.resizeId()!=null){try{TestingResize.replay(world.getServer(),original.resizeId(),false);resizeReplaying=true;}catch(IOException e){resizeReplaying=false;throw e;}return;}
        Operation reverse=reverse(original);submit(new Operation("Отмена: "+original.name(),reverse.changes(),reverse.biomeBefore(),reverse.biomeAfter(),null,reverse.entities(),UUID.randomUUID().toString(),reverse.sceneBefore(),reverse.sceneAfter(),reverse.documentsBefore(),reverse.documentsAfter()),1);
    }
    void redo() throws IOException {
        if(busy())throw new IOException("Дождитесь окончания операции");
        if(redo.isEmpty()) return;
        Operation original=redo.peekFirst();
        if(original.resizeId()!=null){try{TestingResize.replay(world.getServer(),original.resizeId(),true);resizeReplaying=true;}catch(IOException e){resizeReplaying=false;throw e;}return;}
        submit(new Operation("Повтор: "+original.name(),original.changes(),original.biomeBefore(),original.biomeAfter(),null,original.entities(),UUID.randomUUID().toString(),original.sceneBefore(),original.sceneAfter(),original.documentsBefore(),original.documentsAfter()),2);
    }
    private static void removeHistory(Deque<Operation> history,String source)throws IOException {
        if(source==null){history.removeFirst();return;}
        if(!history.removeIf(operation->operation.id().equals(source)))throw new IOException("Исходное действие истории отсутствует");
    }
    void undoFor(String actor,boolean any)throws IOException {historyFor(actor,any,false);}
    void redoFor(String actor,boolean any)throws IOException {historyFor(actor,any,true);}
    private void historyFor(String actor,boolean any,boolean forward)throws IOException {
        if(busy())throw new IOException("Дождитесь окончания операции");
        Deque<Operation> history=forward?redo:undo;
        Operation original=history.stream().filter(operation->any||Objects.equals(actor,operation.actor())).findFirst().orElse(null);
        if(original==null){status="Нет доступных действий этого автора";return;}
        if(original.resizeId()!=null){if(com.dark.winter.online.server.OnlineGameHost.get(world.getServer())!=null)throw new IOException("Общий откат размера требует отдельного координатора острова");if(original!=history.peekFirst())throw new IOException("Изменение размера конфликтует с более поздней общей историей");if(forward)redo();else undo();return;}
        Operation value=forward?original:reverse(original);
        Operation requested=new Operation((forward?"Повтор: ":"Отмена: ")+original.name(),value.changes(),value.biomeBefore(),value.biomeAfter(),null,value.entities(),UUID.randomUUID().toString(),
                value.sceneBefore(),value.sceneAfter(),value.documentsBefore(),value.documentsAfter(),TestingActors.actor(),TestingActors.session(),TestingActors.permissions(),original.id());
        submit(requested,forward?2:1);
    }
    List<String> historyActors(){return undo.stream().map(operation->operation.id()+"|"+(operation.actor()==null?"legacy":operation.actor())+"|"+operation.name()).toList();}
    void recordResize(String name,String id) throws IOException {
        if(active!=null)throw new IOException("Block operation is active");
        Deque<Operation> u=new ArrayDeque<>(undo),r=new ArrayDeque<>(redo);
        undo.addFirst(new Operation(name,List.of(),null,null,id));redo.clear();
        try{save();}catch(IOException e){undo.clear();undo.addAll(u);redo.clear();redo.addAll(r);throw e;}
    }
    boolean resizeApplied(String id) {return undo.stream().anyMatch(op->id.equals(op.resizeId()));}
    void replayCompleted(String id,boolean forward) throws IOException {
        Deque<Operation> from=forward?redo:undo,to=forward?undo:redo;
        Operation op=from.peekFirst();if(op==null||!id.equals(op.resizeId()))throw new IOException("Resize history does not match");
        from.removeFirst();to.addFirst(op);
        try{save();resizeReplaying=false;}catch(IOException e){to.removeFirst();from.addFirst(op);throw e;}
    }
    void replayFailed(){resizeReplaying=false;}
    private Operation reverse(Operation op) {
        List<Change> list=new ArrayList<>();
        for(int i=op.changes().size()-1;i>=0;i--) {Change c=op.changes().get(i);list.add(new Change(c.pos(),c.after(),c.before()));}
        return new Operation(op.name(),list,op.biomeAfter(),op.biomeBefore(),op.resizeId(),op.entities().stream().map(TestingEntities::reverse).toList(),op.id(),op.sceneAfter(),op.sceneBefore(),op.documentsAfter(),op.documentsBefore(),op.actor(),op.actorSession(),op.actorPermissions(),op.historySource());
    }
    /** Normalize legacy JSON/default payloads through the native block-entity loader before WAL comparison. */
    Cell normalize(BlockPos pos,Cell cell)throws IOException {
        if(!cell.state().hasBlockEntity()){
            if(cell.entity()!=null)throw new IOException("Данные контейнера у обычного блока "+pos);return cell;
        }
        BlockEntity entity;
        if(cell.entity()!=null)entity=BlockEntity.loadStatic(pos,cell.state(),cell.entity(),world.registryAccess());
        else if(cell.state().getBlock() instanceof net.minecraft.world.level.block.EntityBlock block)entity=block.newBlockEntity(pos,cell.state());
        else throw new IOException("Блок не умеет создавать свой контейнер: "+pos);
        if(entity==null||!entity.getType().isValid(cell.state()))throw new IOException("Недопустимый контейнер в "+pos);
        CompoundTag nativeTag=entity.saveWithFullMetadata(world.registryAccess());
        if(cell.entity()!=null) {
            boolean samePayload=cell.legacyJson()
                    ?CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,cell.entity()).getOrThrow().equals(CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,nativeTag).getOrThrow())
                    :cell.entity().equals(nativeTag);
            if(!samePayload)throw new IOException("Native container payload loses data at "+pos);
        }
        return new Cell(cell.state(),nativeTag,cell.origin());
    }
    private static String sceneRef(JsonObject object,String key)throws IOException {
        if(!object.has(key))return null;String hash=object.get(key).getAsString();
        if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Недопустимая ссылка сцены");return hash;
    }

    private void write(BlockPos pos,Cell cell) {
        world.removeBlockEntity(pos);
        world.setBlock(pos,cell.state(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        if(cell.entity()!=null) {
            CompoundTag tag=cell.entity().copy();tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());
            BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),tag,world.registryAccess());
            if(be==null) throw new IllegalStateException("Block entity restore failed at "+pos);
            world.setBlockEntity(be);be.setChanged();
        }
        String previousOrigin=origins.get(pos.asLong());
        if(cell.origin()==null) origins.remove(pos.asLong()); else origins.put(pos.asLong(),cell.origin());
        if("terrain".equals(previousOrigin)||"surface".equals(previousOrigin)||"terrain".equals(cell.origin())||"surface".equals(cell.origin()))TestingServer.terrainTouched(world.getServer(),pos);
    }
    void saveProvenance() throws IOException {
        if(recoveryFailed)return;
        JsonObject root=new JsonObject();root.addProperty("schema",2);JsonObject entries=new JsonObject();
        origins.forEach((k,v)->entries.addProperty(k.toString(),v));root.add("origins",entries);
        atomic(directory.resolve("provenance.json"),root);
    }
    void save() throws IOException {
        if(nativeJournal!=null)nativeJournal.awaitCheckpoint();
        saveProvenance();
        JsonObject history=new JsonObject();history.addProperty("schema",2);
        long sequence=nativeJournal==null?nativeBaseSequence:nativeJournal.sequence();history.addProperty("nativeSequence",sequence);
        JsonArray u=new JsonArray(),r=new JsonArray();undo.forEach(x->u.add(cachedHistory(x).json()));redo.forEach(x->r.add(cachedHistory(x).json()));
        history.add("undo",u);history.add("redo",r);atomic(directory.resolve("undo.json"),history);nativeBaseSaved(sequence);
    }
    private void nativePending(Operation operation)throws IOException {
        JsonObject payload=encode(operation);payload.addProperty("nativeLedger",true);atomic(directory.resolve("pending-edit.json"),payload);
    }
    boolean nativeRestoreAllowed(){return !nativeCommittedFailure;}
    boolean deferNativeAction(Runnable action){
        if(nativeJournal==null||nativeJournal.drained()||nativeRecovering||recoveryFailed)return false;
        if(queued!=null||active!=null||pendingRecovery!=null||resizeReplaying)return false;
        if(nativeDeferred!=null)throw new IllegalStateException("Дождитесь контрольной записи нативных изменений");
        nativeDeferred=Objects.requireNonNull(action);nativeDeferredActor=TestingActors.capture();nativeBarrierFrozen=world.getServer().tickRateManager().isFrozen();
        world.getServer().tickRateManager().setFrozen(true);nativeJournal.drain();return true;
    }
    private void cacheHistory(Operation operation,JsonObject payload){
        nativeHistoryCache.put(operation.id(),new CachedHistory(payload,payload.toString()));
    }
    private CachedHistory cachedHistory(Operation operation){
        CachedHistory cached=nativeHistoryCache.get(operation.id());
        if(cached==null){cacheHistory(operation,encode(operation));cached=nativeHistoryCache.get(operation.id());}
        return cached;
    }
    List<String> nativeUndoIds(){return undo.stream().map(Operation::id).toList();}
    List<String> nativeRedoIds(){return redo.stream().map(Operation::id).toList();}
    Map<String,Operation> nativeOperations(){
        Map<String,Operation> result=new HashMap<>();undo.forEach(op->result.put(op.id(),op));redo.forEach(op->result.put(op.id(),op));return result;
    }
    void nativeRestoreHistory(List<Operation> savedUndo,List<Operation> savedRedo){
        undo.clear();undo.addAll(savedUndo);redo.clear();redo.addAll(savedRedo);
    }
    NativeMetadata nativeMetadata(long sequence){
        NativeMetadata snapshot=new NativeMetadata(sequence,undo.stream().map(op->cachedHistory(op).text()).toList(),
                redo.stream().map(op->cachedHistory(op).text()).toList(),new HashMap<>(origins));
        Set<String> retained=new HashSet<>(nativeUndoIds());retained.addAll(nativeRedoIds());
        if(active!=null)retained.add(active.id());if(pendingRecovery!=null)retained.add(pendingRecovery.id());
        nativeHistoryCache.keySet().retainAll(retained);return snapshot;
    }
    long nativeBaseSequence(){return nativeBaseSequence;}
    void nativeBaseSaved(long sequence){nativeBaseSequence=Math.max(nativeBaseSequence,sequence);}
    void nativeCheckpointMetadata()throws IOException {TestingServer.flushMetadata(world.getServer());}
    void stopNative()throws IOException {if(nativeJournal!=null)nativeJournal.awaitCheckpoint();save();}
    void nativeBeforeSave(net.minecraft.world.level.chunk.ChunkAccess chunk)throws IOException {
        if(nativeJournal!=null&&!nativeRecovering&&!recoveryFailed)nativeJournal.beforeSave(chunk);
    }
    void nativeQueued(net.minecraft.world.level.ChunkPos chunk,java.util.concurrent.CompletableFuture<Void> future){
        if(nativeJournal!=null&&!nativeRecovering&&!recoveryFailed)nativeJournal.queued(chunk,future);
    }
    void nativeSaveFailed(Exception failure){
        recoveryFailed=true;world.getServer().tickRateManager().setFrozen(true);
        status="Запись чанка остановлена; REDO-журнал сохранён: "+failure.getMessage();
        org.slf4j.LoggerFactory.getLogger("DarkWinter/Testing").error(status,failure);
    }
    /** Plain immutable metadata only. This method is called by the bounded journal worker. */
    static void writeNativeMetadata(Path directory,NativeMetadata metadata)throws IOException {
        JsonObject provenance=new JsonObject();provenance.addProperty("schema",2);provenance.addProperty("nativeSequence",metadata.sequence());
        JsonObject values=new JsonObject();metadata.origins().forEach((key,value)->values.addProperty(key.toString(),value));provenance.add("origins",values);
        String history="{\"schema\":2,\"nativeSequence\":"+metadata.sequence()+",\"undo\":["+String.join(",",metadata.undo())+"],\"redo\":["+String.join(",",metadata.redo())+"]}";
        nativeMetadataWrite(directory.resolve("provenance.json"),provenance.toString());
        nativeMetadataWrite(directory.resolve("undo.json"),history);
    }
    private static void nativeMetadataWrite(Path file,String value)throws IOException {
        IOException failure=null;
        for(int attempt=0;attempt<12;attempt++){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Контрольная запись отменена");
            try{
                Path temporary=Files.createTempFile(file.getParent(),"native-metadata-",".tmp");
                try{
                    byte[] data=value.getBytes(StandardCharsets.UTF_8);
                    try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){
                        var bytes=java.nio.ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
                    }
                    try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temporary);}
                return;
            }
            catch(AccessDeniedException sharing){
                failure=sharing;
                try{Thread.sleep(20L*(attempt+1));}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Контрольная запись прервана");}
            }
        }
        throw failure;
    }
    static void atomic(Path file,JsonElement value) throws IOException {
        Files.createDirectories(file.getParent());Path tmp=Files.createTempFile(file.getParent(),"testing-", ".tmp");
        try {
            Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(value),StandardCharsets.UTF_8);
            try {Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e) {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(tmp);}
    }
    private static JsonObject cell(Cell value) {
        JsonObject out=new JsonObject();
        out.add("state",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,value.state()).getOrThrow());
        if(value.entity()!=null) {
            if(value.legacyJson())out.add("entity",CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,value.entity()).getOrThrow());
            else try{out.addProperty("entityNbt",TestingNbt.encode(value.entity()));}catch(IOException e){throw new IllegalStateException("Native block entity cannot be encoded",e);}
        }
        if(value.origin()!=null) out.addProperty("origin",value.origin());return out;
    }
    private static Cell cell(JsonObject value) throws IOException {
        return new Cell(BlockState.CODEC.parse(JsonOps.INSTANCE,value.get("state")).getOrThrow(),
                value.has("entityNbt")?TestingNbt.decode(value.get("entityNbt").getAsString()):value.has("entity")?CompoundTag.CODEC.parse(JsonOps.INSTANCE,value.get("entity")).getOrThrow():null,
                value.has("origin")?value.get("origin").getAsString():null,!value.has("entityNbt")&&value.has("entity"));
    }
    static JsonObject encode(Operation op) {
        JsonObject root=new JsonObject();root.addProperty("schema",2);root.addProperty("name",op.name());root.addProperty("id",op.id());
        JsonArray entities=new JsonArray();op.entities().forEach(e->entities.add(TestingEntities.encode(e)));root.add("entities",entities);
        if(op.resizeId()!=null)root.addProperty("resizeId",op.resizeId());
        if(op.actor()!=null){root.addProperty("actor",op.actor());root.addProperty("actorSession",op.actorSession());root.addProperty("actorPermissions",op.actorPermissions());}
        if(op.historySource()!=null)root.addProperty("historySource",op.historySource());
        if(op.sceneBefore()!=null)root.addProperty("sceneBefore",op.sceneBefore());
        if(op.sceneAfter()!=null)root.addProperty("sceneAfter",op.sceneAfter());
        if(op.documentsBefore()!=null)root.addProperty("documentsBefore",op.documentsBefore());
        if(op.documentsAfter()!=null)root.addProperty("documentsAfter",op.documentsAfter());
        if(op.biomeBefore()!=null)root.addProperty("biomeBefore",op.biomeBefore());
        if(op.biomeAfter()!=null)root.addProperty("biomeAfter",op.biomeAfter());
        JsonArray cells=new JsonArray();
        for(Change c:op.changes()) {
            JsonObject row=new JsonObject();row.addProperty("pos",c.pos().asLong());
            row.add("before",cell(c.before()));row.add("after",cell(c.after()));cells.add(row);
        } root.add("changes",cells);root.addProperty("bodySha256",bodyHash(root));return root;
    }
    private static String bodyHash(JsonObject root) {
        JsonObject selected=new JsonObject();
        for(String key:List.of("schema","name","id","changes","entities","biomeBefore","biomeAfter","resizeId","sceneBefore","sceneAfter","documentsBefore","documentsAfter","actor","actorSession","actorPermissions","historySource"))
            if(root.has(key))selected.add(key,root.get(key));
        try {
            byte[] bytes=new Gson().toJson(canonical(selected)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()){JsonObject sorted=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))sorted.add(key,canonical(value.getAsJsonObject().get(key)));return sorted;}
        if(value.isJsonArray()){JsonArray array=new JsonArray();for(var item:value.getAsJsonArray())array.add(canonical(item));return array;}
        return value;
    }
    static Operation decode(JsonObject root) throws IOException {
        if(root.has("bodySha256")) {
            try {
                String expected=root.get("bodySha256").getAsString();
                if(!expected.matches("[0-9a-f]{64}")||!bodyHash(root).equals(expected))throw new IOException("Operation body SHA-256 mismatch");
            }catch(RuntimeException error){throw new IOException("Invalid operation body checksum",error);}
        }
        if(!root.has("schema")||root.get("schema").getAsInt()!=2) throw new IOException("Unsupported edit format");
        JsonArray rows=root.getAsJsonArray("changes");if(rows.size()>LIMIT) throw new IOException("Too many changes");
        List<Change> out=new ArrayList<>();Set<Long> unique=new HashSet<>();long nativeBytes=0;
        try {
            for(JsonElement e:rows) {JsonObject r=e.getAsJsonObject();long p=r.get("pos").getAsLong();
                if(!unique.add(p)) throw new IOException("Duplicate position");
                Cell before=cell(r.getAsJsonObject("before")),after=cell(r.getAsJsonObject("after"));
                nativeBytes+=(before.entity()==null?0:before.entity().sizeInBytes())+(after.entity()==null?0:after.entity().sizeInBytes());
                if(nativeBytes>64L*1024*1024)throw new IOException("Суммарные данные контейнеров превышают 64 МиБ");
                out.add(new Change(BlockPos.of(p),before,after));
            }
            List<TestingEntities.Change> entities=new ArrayList<>();
            if(root.has("entities"))for(JsonElement e:root.getAsJsonArray("entities"))entities.add(TestingEntities.decode(e.getAsJsonObject()));
            if(entities.size()>TestingEntities.LIMIT||entities.stream().map(TestingEntities.Change::id).distinct().count()!=entities.size())throw new IOException("Too many or duplicate NPC");
            return new Operation(root.get("name").getAsString(),out,root.has("biomeBefore")?root.get("biomeBefore").getAsString():null,root.has("biomeAfter")?root.get("biomeAfter").getAsString():null,root.has("resizeId")?root.get("resizeId").getAsString():null,entities,root.has("id")?root.get("id").getAsString():UUID.randomUUID().toString(),
                    sceneRef(root,"sceneBefore"),sceneRef(root,"sceneAfter"),sceneRef(root,"documentsBefore"),sceneRef(root,"documentsAfter"),
                    root.has("actor")?root.get("actor").getAsString():null,root.has("actorSession")?root.get("actorSession").getAsString():null,
                    root.has("actorPermissions")?root.get("actorPermissions").getAsString():null,root.has("historySource")?root.get("historySource").getAsString():null);
        } catch(RuntimeException e) {throw new IOException("Invalid edit data",e);}
    }
}
