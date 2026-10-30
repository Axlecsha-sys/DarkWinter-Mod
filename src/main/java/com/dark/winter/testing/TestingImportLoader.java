// Loads the bounded native footprint required by a validated library import.
package com.dark.winter.testing;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded native loading for a validated library request. Existing author blocks are never changed during preparation. */
public final class TestingImportLoader {
    private static final Set<String> CATEGORIES=Set.of("terrain","surface","grass","bushes","trees","building","object","settings","scenarios");
    private static final int IN_FLIGHT=8,MAX_CHUNKS=4096;
    private static final Map<MinecraftServer,Job> JOBS=new java.util.concurrent.ConcurrentHashMap<>();
    private static TicketType ticket;
    private static final Map<MinecraftServer,Pool> POOLS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<MinecraftServer,Diagnostics> COMPLETED=new java.util.concurrent.ConcurrentHashMap<>();
    private record Owner(Set<ChunkPos> chunks,long started) {}
    private static final class Pool {
        final Map<String,Owner> owners=new LinkedHashMap<>();
        final Map<ChunkPos,CompletableFuture<?>> futures=new LinkedHashMap<>();
        int tick=-1,starts,peak;
    }
    private static final class Job {
        final MinecraftServer server;final TestingServer.Context context;final Path world;final Map<String,Path> files;
        TestingActors.Captured actor=TestingActors.capture();
        final TestingArchiveScope.Token archive=TestingArchiveScope.capture().orElse(null);
        Map<String,String> hashes=Map.of();List<ChunkPos> chunks=List.of();String permit;
        final TestingIslandGenerator.Configuration config;final String biome,mode;boolean restoreFrozen;
        final String token="library-"+UUID.randomUUID();final boolean preview;
        volatile TestingServer.CategoryPreview result;volatile String phase="DEPENDENCY_IO";
        long phaseStarted=System.nanoTime();int completed,peak,nativeIndex;boolean nativeValidated,published;
        TestingScenarioTransfers.Prepared dependency;
        java.util.concurrent.Future<TestingScenarioTransfers.Prepared> preparing;
        java.util.concurrent.Future<TestingScenarioTransfers.LevelPayload> readingLevel;
        java.util.concurrent.Future<Void> confirming;
        String expectedSignature="",continuationLabel,expectedDependency="";
        Map<String,String> expectedFiles=Map.of();
        java.util.function.Consumer<Confirmation> callback;
        final List<String> inheritedLeases=new ArrayList<>();
        Job(MinecraftServer server,Map<String,Path> files,boolean preview)throws IOException{
            this.server=server;this.preview=preview;this.files=Map.copyOf(files);context=TestingServer.context(server);
            world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();TestingArchiveScope.requireWorld(world);
            config=server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator generator?generator.configuration():null;
            biome=context.settings.biome;mode=context.settings.mode;restoreFrozen=server.tickRateManager().isFrozen();
        }
    }
    public record Diagnostics(String phase,int required,int completed,int peakInFlight,int heldTickets) {}
    private TestingImportLoader() {}
    public static void init() {
        if(ticket!=null)return;
        ticket=Registry.register(BuiltInRegistries.TICKET_TYPE,Identifier.fromNamespaceAndPath("darkwinter","testing_library_load"),
                new TicketType(TicketType.NO_TIMEOUT,TicketType.FLAG_LOADING));
    }
    public static boolean busy(MinecraftServer server){Job job=JOBS.get(server);return job!=null&&!job.phase.equals("HOLD");}
    public static boolean active(MinecraftServer server){return JOBS.containsKey(server);}
    public static Diagnostics diagnostics(MinecraftServer server){Job job=JOBS.get(server);Pool pool=POOLS.get(server);return job==null?COMPLETED.get(server):new Diagnostics(job.phase,job.chunks.size(),job.completed,job.peak,pool==null?0:pool.futures.size());}
    private record PreparedFiles(Map<String,Path> files,Map<String,String> hashes,List<ChunkPos> chunks,String permit) {}
    /** Source validation is shared by preview and the eventual import; it never writes native cells. */
    private static PreparedFiles prepareFiles(MinecraftServer server,Map<String,Path> files)throws IOException {
        var context=authority(server);
        if(files==null||files.isEmpty()||!CATEGORIES.containsAll(files.keySet()))throw new IOException("Неизвестные категории библиотеки");
        Map<String,Path> checked=new LinkedHashMap<>();Map<String,String> hashes=new LinkedHashMap<>();Set<ChunkPos> chunks=new LinkedHashSet<>();
        int extent=0,totalBlocks=0,npcs=0;long nativeBytes=0,totalBytes=0;
        var generator=server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator value?value:null;
        boolean wholeIsland=files.keySet().stream().anyMatch(Set.of("terrain","surface","object")::contains);
        Path transfers=TestingArchive.root().resolve("transfers").toAbsolutePath().normalize();
        String permit=files.containsKey("terrain")?TestingResize.validateTerrainBackup(server):null;
        try {
            for(var file:files.entrySet()) {
                Path path=file.getValue().toAbsolutePath().normalize();
                if(!path.getParent().equals(transfers)||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                        ||Files.size(path)>128L*1024*1024||(totalBytes+=Files.size(path))>256L*1024*1024)
                    throw new IOException("Недопустимый файл библиотеки transfers");
                String hash=TestingArchive.hash(path);JsonObject root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var operation=TestingEdits.decode(root);String category=file.getKey();
                if(!operation.name().equals("category:"+category)||!operation.entities().isEmpty()||operation.resizeId()!=null
                        ||operation.sceneBefore()!=null||operation.sceneAfter()!=null||operation.documentsBefore()!=null||operation.documentsAfter()!=null)
                    throw new IOException("Категория содержит исполняемые ссылки");
                if(Set.of("settings","scenarios").contains(category)) {
                    if(!operation.changes().isEmpty()||root.has("npc")||!root.has("document")||!root.get("document").isJsonObject())throw new IOException("Повреждён документ библиотеки");
                    JsonObject payload=root.getAsJsonObject("document");
                    if(category.equals("scenarios"))payload=preparedScenario(server,path,payload,root.has("dependencyRoot")?root.get("dependencyRoot").getAsString():"").document();
                    JsonObject document=new JsonObject();document.addProperty("schema",1);document.add(category,payload);TestingDocuments.validate(server,document);
                }else if(category.equals("terrain")) {
                    if(!operation.changes().isEmpty()||!root.has("terrain"))throw new IOException("Требуется полный формат рельефа");
                    extent=Math.max(extent,TestingResize.validateTerrainSource(server,root.getAsJsonObject("terrain")).extent);
                }else if(category.equals("surface")) {
                    extent=Math.max(extent,TestingResize.validateSurfaceSource(server,root).extent);
                    String biome=root.get("biome").getAsString();server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(biome.contains(":")?biome:"minecraft:"+biome)));
                }
                Map<BlockPos,BlockState> states=new LinkedHashMap<>();Map<BlockPos,CompoundTag> tags=new LinkedHashMap<>();Set<BlockPos> positions=new HashSet<>();
                for(var cell:operation.changes()) {
                    if(!positions.add(cell.pos())||!category.equals(cell.after().origin())||server.overworld().isOutsideBuildHeight(cell.pos())
                            ||Math.abs((long)cell.pos().getX())>29999984||Math.abs((long)cell.pos().getZ())>29999984)throw new IOException("Недопустимые координаты или принадлежность категории");
                    if(++totalBlocks>TestingEdits.LIMIT)throw new IOException("Категории превышают лимит одной операции");
                    nativeBytes+=(cell.before().entity()==null?0:cell.before().entity().sizeInBytes())+(cell.after().entity()==null?0:cell.after().entity().sizeInBytes());
                    if(nativeBytes>64L*1024*1024)throw new IOException("Контейнеры библиотеки превышают 64 МиБ");
                    BlockState state=cell.after().state();CompoundTag tag=cell.after().entity();
                    if(!state.isAir())states.put(cell.pos(),state);if(tag!=null)tags.put(cell.pos(),tag);
                    if(state.hasBlockEntity()) {
                        var candidate=tag==null&&state.getBlock() instanceof EntityBlock block?block.newBlockEntity(cell.pos(),state):BlockEntity.loadStatic(cell.pos(),state,tag,server.overworld().registryAccess());
                        if(candidate==null||!candidate.getType().isValid(state))throw new IOException("Недопустимые native данные контейнера");
                    }else if(tag!=null)throw new IOException("Контейнер у обычного блока");
                    chunks.add(ChunkPos.containing(cell.pos()));
                }
                if(!category.equals("surface"))TestingStructures.validate(states,tags);
                if(root.has("npc")) {
                    if(!category.equals("object")||root.getAsJsonArray("npc").size()>TestingEntities.LIMIT)throw new IOException("NPC разрешены только в категории объектов");
                    Set<UUID> unique=new HashSet<>();
                    for(JsonElement row:root.getAsJsonArray("npc")) {
                        var npc=TestingEntities.decode(row.getAsJsonObject());
                        if(npc.after()==null||!unique.add(npc.id())||++npcs>TestingEntities.LIMIT)throw new IOException("Повторный или недопустимый NPC");
                        var point=TestingEntities.position(npc.after());BlockPos block=BlockPos.containing(point);
                        if(Math.abs(point.x)>29999984||Math.abs(point.z)>29999984||server.overworld().isOutsideBuildHeight(block))throw new IOException("NPC вне границ мира");
                        nativeBytes+=npc.after().sizeInBytes();if(nativeBytes>64L*1024*1024)throw new IOException("Native данные библиотеки превышают 64 МиБ");
                        chunks.add(ChunkPos.containing(block));
                    }
                }
                if(!hash.equals(TestingArchive.hash(path)))throw new IOException("Файл библиотеки изменился во время проверки");
                checked.put(category,path);hashes.put(category,hash);
            }
            for(var origin:context.edits.origins.entrySet())if(files.containsKey(origin.getValue()))chunks.add(ChunkPos.containing(BlockPos.of(origin.getKey())));
            if(wholeIsland) {
                if(generator==null)throw new IOException("Полное природное/объектное извлечение требует островного генератора");
                extent=Math.max(extent,(int)Math.ceil(generator.radius()*1.45)+4);
                for(long key:context.heights.keySet()){BlockPos point=BlockPos.of(key);extent=Math.max(extent,Math.max(Math.abs(point.getX()),Math.abs(point.getZ())));}
                if(extent>192)throw new IOException("Изменения за пределами 192 м переносите полным островом");
                // All island chunks are necessary to discover native NPC/support, including those outside the current view distance.
                for(int x=Math.floorDiv(-extent,16);x<=Math.floorDiv(extent,16);x++)for(int z=Math.floorDiv(-extent,16);z<=Math.floorDiv(extent,16);z++)chunks.add(new ChunkPos(x,z));
            }
            if(chunks.size()>MAX_CHUNKS)throw new IOException("Библиотека требует больше 4096 чанков");
            if(!permitMatches(server,permit))throw new IOException("Резервная версия библиотеки изменилась");
            return new PreparedFiles(Map.copyOf(checked),Map.copyOf(hashes),List.copyOf(chunks),permit);
        }catch(RuntimeException failure){throw new IOException("Библиотека отклонена до загрузки и изменения острова",failure);}
    }

    /** Called after an explicitly authorized stopped-world workflow. Full dependency I/O never runs here. */
    public static void queue(MinecraftServer server,Map<String,Path> files)throws IOException {start(server,files,false);}
    private static Job start(MinecraftServer server,Map<String,Path> files,boolean preview)throws IOException {
        authority(server);
        if(ticket==null||JOBS.containsKey(server)||TestingServer.busy(server))throw new IOException("Дождитесь завершения предыдущей операции библиотеки");
        if(files==null||files.isEmpty()||!CATEGORIES.containsAll(files.keySet()))throw new IOException("Неизвестные категории библиотеки");
        Map<String,Path> normalized=new LinkedHashMap<>();long total=0;
        Path allowed=TestingArchive.root().resolve("transfers");
        for(var entry:files.entrySet()){
            Path path=entry.getValue().toAbsolutePath().normalize();
            if(!path.getParent().equals(allowed)||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                    ||Files.size(path)>128L*1024*1024||(total+=Files.size(path))>256L*1024*1024)throw new IOException("Недопустимый источник библиотеки");
            normalized.put(entry.getKey(),path);
        }
        Job job=new Job(server,normalized,preview);JOBS.put(server,job);
        if(!preview)server.tickRateManager().setFrozen(true);
        try{
            Path source=normalized.get("scenarios"),target=job.world;
            if(source!=null){job.preparing=TestingScenarioTransferIo.prepare(source,target);phase(job,"DEPENDENCY_IO");}
            else initializeNative(job);
            return job;
        }catch(IOException|RuntimeException error){finish(job,preview);throw error;}
    }
    private static void initializeNative(Job job)throws IOException {
        PreparedFiles prepared=prepareFiles(job.server,job.files);
        if(!job.expectedFiles.isEmpty()&&!job.expectedFiles.equals(prepared.hashes()))throw new IOException("Файлы второго этапа изменились");
        if(job.dependency!=null&&!job.dependency.sourceHash().equals(prepared.hashes().get("scenarios")))throw new IOException("Подготовленный документ сценариев изменился");
        job.hashes=prepared.hashes();job.chunks=prepared.chunks();job.permit=prepared.permit();phase(job,"PRELOAD");
        job.context.message="Библиотека: подготовка "+job.chunks.size()+" чанков";
    }
    /** Idempotent polling starts one worker request; no future is joined while it is pending. */
    public static boolean preparePreview(MinecraftServer server,Map<String,Path> files)throws IOException {
        authority(server);Job job=JOBS.get(server);
        if(job!=null){
            if(!job.preview||!sameFiles(job.files,files))throw new IOException("Уже подготовлен другой перенос");
            return job.phase.equals("PREVIEW_READY");
        }
        start(server,files,true);return false;
    }
    private static boolean sameFiles(Map<String,Path> checked,Map<String,Path> supplied){
        return supplied!=null&&checked.size()==supplied.size()&&supplied.entrySet().stream().allMatch(entry->entry.getValue()!=null&&entry.getValue().toAbsolutePath().normalize().equals(checked.get(entry.getKey())));
    }
    public static boolean previewReady(MinecraftServer server){Job job=JOBS.get(server);return job!=null&&job.preview&&job.phase.equals("PREVIEW_READY");}
    public static TestingServer.CategoryPreview previewResult(MinecraftServer server){Job job=JOBS.get(server);return job!=null&&job.preview?job.result:null;}
    public record Confirmation(boolean applied,TestingServer.CategoryPreview preview,String message){}
    /** Completion is delivered only by this server's tick after a final native target check. */
    public static void confirmPreview(MinecraftServer server,String expectedSignature,boolean wasFrozen,java.util.function.Consumer<Confirmation> callback)throws IOException {
        authority(server);Job job=JOBS.get(server);
        if(job==null||!job.preview||!job.phase.equals("PREVIEW_READY")||job.result==null||!job.result.conflicts().isEmpty())
            throw new IOException("Нет готового бесконфликтного обзора");
        job.restoreFrozen=wasFrozen;job.callback=Objects.requireNonNull(callback);
        TestingServer.CategoryPreview fresh=TestingServer.previewCategories(server,job.files);
        if(!fresh.conflicts().isEmpty()||!fresh.signature().equals(expectedSignature)){job.result=fresh;notifyConfirmation(job,false,"Цель изменилась; требуется новое подтверждение");return;}
        job.expectedSignature=expectedSignature;
        beginConfirm(job);
    }
    private static void beginConfirm(Job job)throws IOException {
        var prepared=job.dependency;Map<String,Path> paths=job.files;Map<String,String> hashes=job.hashes;
        job.confirming=TestingScenarioTransferIo.confirm(prepared,paths,hashes);phase(job,"CONFIRM_IO");
        job.context.message="Проверка SHA-256 источников и зависимостей перед записью";
    }
    /** Compatibility API cannot bypass the asynchronous confirmation protocol. */
    @Deprecated public static void finishPreview(MinecraftServer server)throws IOException {throw new IOException("Используйте асинхронное подтверждение обзора");}
    public static boolean cancelPreview(MinecraftServer server){
        if(server==null||!server.isSameThread())throw new IllegalArgumentException("Cancel preview requires server thread");
        Job job=JOBS.get(server);
        if(job==null)return true;
        if(!job.preview||job.phase.equals("HOLD"))return false; // The explicit confirmation has already accepted a WAL.
        finish(job,true);return true;
    }
    /** A plan is scoped to this exact active world/job and these exact original scenario definitions. */
    static TestingScenarioDependencies.Plan preparedScenario(MinecraftServer server,Path file,JsonObject document,String root)throws IOException {
        Job job=JOBS.get(server);
        if(job==null||job.context!=TestingServer.context(server)||!job.nativeValidated||job.dependency==null
                ||!job.dependency.matches(file,server.getWorldPath(LevelResource.ROOT),document,root))
            throw new IOException("Сценарии требуют асинхронной подготовки в текущем авторском мире");
        return job.dependency.plan();
    }
    static void requirePublished(MinecraftServer server,TestingScenarioDependencies.Plan plan)throws IOException {
        Job job=JOBS.get(server);
        if(job==null||job.context!=TestingServer.context(server)||!job.published||job.dependency==null||job.dependency.plan()!=plan)
            throw new IOException("Зависимости ещё не прошли подтверждённую фоновую проверку");
    }
    static boolean hasPreparedScenario(MinecraftServer server){Job job=JOBS.get(server);return job!=null&&job.nativeValidated&&job.dependency!=null;}
    static String dependencySignature(MinecraftServer server){Job job=JOBS.get(server);return job==null||job.dependency==null?"":job.dependency.plan().signature();}
    static boolean restoreFrozen(MinecraftServer server){Job job=JOBS.get(server);return job==null?server.tickRateManager().isFrozen():job.restoreFrozen;}
    static boolean secondStageReady(MinecraftServer server,String label){
        Job job=JOBS.get(server);return job!=null&&Objects.equals(job.continuationLabel,label)&&job.phase.equals("HOLD")&&job.published;
    }
    /** Every second WAL receives a fresh background SHA proof; already loaded chunks and the pure plan are reused. */
    static void prepareSecondStage(MinecraftServer server,Map<String,Path> files,Map<String,String> hashes,String dependency,String label,boolean frozen)throws IOException {
        authority(server);Job previous=JOBS.get(server);
        if(previous!=null&&!previous.phase.equals("HOLD"))throw new IOException("Дождитесь текущего этапа библиотеки");
        Job job;
        if(previous==null){
            job=start(server,files,false);
        }else{
            job=new Job(server,files,false);job.actor=previous.actor;job.dependency=previous.dependency;job.nativeValidated=previous.nativeValidated;
            job.inheritedLeases.addAll(previous.inheritedLeases);job.inheritedLeases.add(previous.token);
            if(!JOBS.replace(server,previous,job))throw new IOException("Контекст библиотеки изменился");
            previous.callback=null;previous.dependency=null;
        }
        job.expectedFiles=Map.copyOf(hashes);job.expectedDependency=dependency==null?"":dependency;job.continuationLabel=label;job.restoreFrozen=frozen;
        server.tickRateManager().setFrozen(true);
        try{
            if(job.dependency!=null){
                if(!job.expectedDependency.isEmpty()&&!job.expectedDependency.equals(job.dependency.plan().signature()))throw new IOException("Зависимости второго этапа изменились");
                initializeNative(job);
            }else if(!files.containsKey("scenarios")){
                initializeNative(job);
            }
            if(!job.hashes.isEmpty()&&!job.expectedFiles.equals(job.hashes))throw new IOException("Файлы второго этапа изменились");
        }catch(IOException|RuntimeException failure){blockContinuation(job,failure);finish(job,false);throw failure;}
    }
    private static void phase(Job job,String phase){job.phase=phase;job.phaseStarted=System.nanoTime();}
    private static void nativeValid(Job job)throws IOException {
        TestingScenarioTransfers.validateDocuments(job.server,job.dependency);job.nativeValidated=true;
        if(!job.expectedDependency.isEmpty()&&!job.expectedDependency.equals(job.dependency.plan().signature()))throw new IOException("Зависимости второго этапа изменились после подтверждения");
        initializeNative(job);
    }
    public static void tick(MinecraftServer server) {
        Job job=JOBS.get(server);if(job==null)return;
        try(var archive=job.archive==null?null:job.archive.enter();var actor=job.phase.equals("HOLD")?new TestingActors.Scope(null):TestingActors.enterCaptured(server,job.actor)){
            if(JOBS.get(server)!=job||!server.isRunning()||TestingServer.context(server)!=job.context
                    ||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(job.world)||(server.isPublished()&&!job.phase.equals("HOLD")&&!TestingActors.managedScope(server))
                    ||!job.context.settings.mode.equals(job.mode))throw new IOException("Авторский мир изменился во время подготовки");
            if(job.preview&&job.phase.equals("PREVIEW_READY"))return;
            if(job.phase.equals("HOLD")){
                if(TestingServer.recoveryBlocked(server)){finish(job,true);return;}
                Path continuation=job.context.directory.resolve("terrain-followup.json");
                if(Files.isRegularFile(continuation)){
                    if(Files.size(continuation)>16384)throw new IOException("Повреждена очередь библиотеки");
                    if(JsonParser.parseString(Files.readString(continuation)).getAsJsonObject().get("phase").getAsString().equals("BLOCKED")){finish(job,false);return;}
                }
                if(!TestingResize.busy(server)&&!job.context.edits.busy()&&!Files.exists(continuation))finish(job,false);
                return;
            }
            if(System.nanoTime()-job.phaseStarted>600_000_000_000L)throw new IOException("Подготовка библиотеки превысила 600 секунд");
            if(!job.context.settings.biome.equals(job.biome)||job.config!=null&&(!(server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator generator)||!generator.configuration().equals(job.config)))
                throw new IOException("Конфигурация острова изменилась во время подготовки");
            if(job.phase.equals("DEPENDENCY_IO")){
                if(!job.preparing.isDone())return;
                job.dependency=TestingScenarioTransferIo.completed(job.preparing);job.preparing=null;phase(job,"NATIVE_VALIDATE");
                return;
            }
            if(job.phase.equals("NATIVE_VALIDATE")){
                if(job.nativeIndex<job.dependency.levels()){
                    if(job.readingLevel==null){var prepared=job.dependency;int index=job.nativeIndex;job.readingLevel=TestingScenarioTransferIo.level(prepared,index);return;}
                    if(!job.readingLevel.isDone())return;
                    var payload=TestingScenarioTransferIo.completed(job.readingLevel);job.readingLevel=null;
                    TestingScenarioTransfers.validateLevel(server,job.dependency,job.nativeIndex,payload);job.nativeIndex++;
                    job.context.message="Native проверка зависимостей: "+job.nativeIndex+" / "+job.dependency.levels();return;
                }
                nativeValid(job);return;
            }
            if(job.phase.equals("CONFIRM_IO")){
                if(!job.confirming.isDone())return;
                TestingScenarioTransferIo.completed(job.confirming);job.confirming=null;job.published=true;
                var fresh=TestingServer.previewCategories(server,job.files);
                if(!fresh.conflicts().isEmpty()||!fresh.signature().equals(job.expectedSignature)){
                    job.result=fresh;
                    if(job.preview){phase(job,"PREVIEW_READY");notifyConfirmation(job,false,"Цель изменилась во время проверки; подтвердите новый обзор");return;}
                    throw new IOException("Цель изменилась во время проверки; автоматическая запись отменена");
                }
                if(!permitMatches(server,job.permit))throw new IOException("Резервная версия изменилась");
                phase(job,"HOLD");server.tickRateManager().setFrozen(job.restoreFrozen);
                if(job.continuationLabel!=null)TestingServer.resumeTerrainImports(server);else TestingServer.importCategories(server,job.files);
                job.context.event("library_prepared","chunks="+job.chunks.size()+"|peak="+job.peak);
                notifyConfirmation(job,true,"Проверенный перенос принят в журнал изменений");return;
            }
            boolean ready=prepareChunks(server,job.token,job.chunks);
            Pool pool=POOLS.get(server);job.peak=pool==null?0:pool.peak;job.completed=0;
            if(pool!=null)for(ChunkPos chunk:job.chunks)if(chunkReady(server,pool.futures.get(chunk),chunk))job.completed++;
            job.context.message="Библиотека: загружено "+job.completed+" / "+job.chunks.size()+" чанков";
            if(!ready)return;
            for(var file:job.files.entrySet())if(!job.hashes.get(file.getKey()).equals(TestingArchive.hash(file.getValue())))throw new IOException("Источник изменился после подготовки");
            if(!permitMatches(server,job.permit))throw new IOException("Резервная версия изменилась");
            job.result=TestingServer.previewCategories(server,job.files);
            if(job.preview){phase(job,"PREVIEW_READY");job.context.message=job.result.conflicts().isEmpty()?"Обзор готов: требуется явное подтверждение":"Обзор содержит конфликты; запись запрещена";return;}
            if(!job.result.conflicts().isEmpty())throw new IOException(String.join("; ",job.result.conflicts()));
            job.expectedSignature=job.result.signature();beginConfirm(job);
        }catch(Exception failure){
            if(JOBS.get(server)!=job)return;
            job.context.message="Библиотека остановлена до следующей записи: "+failure.getMessage();job.context.event("library_prepare_failed",failure.toString());
            if(job.preview){
                TestingScenarioTransferIo.cancel(job.preparing);TestingScenarioTransferIo.cancel(job.readingLevel);TestingScenarioTransferIo.cancel(job.confirming);
                job.preparing=null;job.readingLevel=null;job.confirming=null;
                job.result=new TestingServer.CategoryPreview(List.of("Проверка остановлена; перенос не применён."),List.of(String.valueOf(failure.getMessage())),"");
                phase(job,"PREVIEW_READY");notifyConfirmation(job,false,job.context.message);
            }else{
                if(job.continuationLabel!=null)blockContinuation(job,failure);
                finish(job,TestingServer.recoveryBlocked(server));
            }
        }
    }
    private static void blockContinuation(Job job,Exception failure){
        Path file=job.context.directory.resolve("terrain-followup.json");
        try{if(Files.isRegularFile(file)){JsonObject row=JsonParser.parseString(Files.readString(file)).getAsJsonObject();row.addProperty("phase","BLOCKED");row.addProperty("error",String.valueOf(failure.getMessage()));TestingEdits.atomic(file,row);}}
        catch(IOException ignored){}
    }
    private static void notifyConfirmation(Job job,boolean applied,String message){
        var callback=job.callback;job.callback=null;
        if(callback!=null)try{callback.accept(new Confirmation(applied,job.result,message));}
        catch(RuntimeException error){job.context.message="Ошибка отображения результата: "+error.getMessage();}
    }
    /** Shared bounded leases for import, ordinary edits and Undo/Redo. Call once per server tick until true. */
    public static boolean prepareChunks(MinecraftServer server,String token,Collection<ChunkPos> required)throws IOException {
        if(server==null||!server.isSameThread()||ticket==null||!TestingServer.isTesting(server)||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))
                ||token==null||token.isBlank()||token.length()>128)throw new IOException("Нет полномочий на native chunk lease");
        if(required==null||required.size()>MAX_CHUNKS||required.stream().anyMatch(Objects::isNull))throw new IOException("Invalid native chunk footprint");
        Set<ChunkPos> chunks=Set.copyOf(required);
        if(chunks.size()>MAX_CHUNKS)throw new IOException("Операция требует больше 4096 чанков");
        for(ChunkPos chunk:chunks)if(Math.abs((long)chunk.x())>1874999||Math.abs((long)chunk.z())>1874999)throw new IOException("Chunk lease вне границ мира");
        if(chunks.isEmpty())return true;
        Pool pool=POOLS.computeIfAbsent(server,key->new Pool());Owner owner=pool.owners.get(token);
        if(owner==null) {
            Set<ChunkPos> union=new HashSet<>(chunks);for(Owner current:pool.owners.values())union.addAll(current.chunks());
            if(union.size()>MAX_CHUNKS)throw new IOException("Совместные операции требуют больше 4096 чанков");
            owner=new Owner(chunks,System.nanoTime());pool.owners.put(token,owner);
        }else if(!owner.chunks().equals(chunks))throw new IOException("Footprint native операции изменился во время подготовки");
        if(System.nanoTime()-owner.started()>180_000_000_000L)throw new IOException("Native chunk IO превысил 180 секунд");
        int tick=server.getTickCount();if(pool.tick!=tick){pool.tick=tick;pool.starts=0;}
        int inFlight=0;
        for(var entry:pool.futures.entrySet())if(!chunkReady(server,entry.getValue(),entry.getKey()))inFlight++;
        List<ChunkPos> ordered=new ArrayList<>(chunks);ordered.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        for(ChunkPos chunk:ordered) {
            if(pool.futures.containsKey(chunk))continue;
            if(pool.starts>=IN_FLIGHT||inFlight>=IN_FLIGHT)break;
            // Loading tickets have no simulation/persist flag; future completion is polled, never joined while pending.
            CompletableFuture<?> future;
            try{future=server.overworld().getChunkSource().addTicketAndLoadWithRadius(ticket,chunk,0);}
            catch(RuntimeException failure){server.overworld().getChunkSource().removeTicketWithRadius(ticket,chunk,0);throw new IOException("Cannot request native chunk "+chunk,failure);}
            pool.futures.put(chunk,future);pool.starts++;inFlight++;pool.peak=Math.max(pool.peak,inFlight);
        }
        for(ChunkPos chunk:chunks)if(!chunkReady(server,pool.futures.get(chunk),chunk))return false;
        return true;
    }
    private static boolean chunkReady(MinecraftServer server,CompletableFuture<?> future,ChunkPos chunk)throws IOException {
        if(future==null||!future.isDone())return false;
        Object result;try{result=future.join();}catch(RuntimeException failure){throw new IOException("Native chunk IO failed: "+chunk,failure);}
        if(!(result instanceof ChunkResult<?> value)||!value.isSuccess())throw new IOException("Native chunk result failed: "+chunk);
        return server.overworld().hasChunk(chunk.x(),chunk.z())&&server.overworld().areEntitiesLoaded(chunk.pack());
    }
    /** Remove only this owner's references; overlapping import/edit leases keep their native tickets. */
    public static void releaseChunks(MinecraftServer server,String token) {
        Pool pool=POOLS.get(server);if(pool==null||pool.owners.remove(token)==null)return;
        Set<ChunkPos> referenced=new HashSet<>();for(Owner owner:pool.owners.values())referenced.addAll(owner.chunks());
        var entries=pool.futures.entrySet().iterator();
        while(entries.hasNext()) {
            ChunkPos chunk=entries.next().getKey();if(referenced.contains(chunk))continue;
            server.overworld().getChunkSource().removeTicketWithRadius(ticket,chunk,0);entries.remove();
        }
        if(pool.owners.isEmpty())POOLS.remove(server);
    }
    private static boolean permitMatches(MinecraftServer server,String expected)throws IOException {return expected==null||expected.equals(TestingResize.validateTerrainBackup(server));}
    private static TestingServer.Context authority(MinecraftServer server)throws IOException {
        var context=TestingServer.context(server);
        if(context==null||!server.isSameThread()||!server.isSingleplayer()||(server.isPublished()&&!TestingActors.managedScope(server))||!TestingServer.isTesting(server)||context.readOnlyPreview||!Set.of("EDIT","CHARACTER").contains(context.settings.mode))
            throw new IOException("Библиотека доступна только в авторском тестовом мире");
        return context;
    }
    private static void finish(Job job,boolean preserveFreeze) {
        if(!JOBS.remove(job.server,job))return;
        TestingScenarioTransferIo.cancel(job.preparing);TestingScenarioTransferIo.cancel(job.readingLevel);TestingScenarioTransferIo.cancel(job.confirming);
        job.preparing=null;job.readingLevel=null;job.confirming=null;job.callback=null;job.dependency=null;
        releaseChunks(job.server,job.token);for(String token:job.inheritedLeases)releaseChunks(job.server,token);job.inheritedLeases.clear();
        COMPLETED.put(job.server,new Diagnostics("DONE",job.chunks.size(),job.completed,job.peak,0));
        if(!preserveFreeze&&!TestingResize.busy(job.server)&&!job.context.edits.busy())job.server.tickRateManager().setFrozen(job.restoreFrozen);
    }
    public static void cancel(MinecraftServer server) {
        Job job=JOBS.get(server);if(job!=null)finish(job,TestingResize.busy(server)||job.context.edits.busy()||TestingServer.recoveryBlocked(server));
        Pool pool=POOLS.remove(server);if(pool!=null)for(ChunkPos chunk:pool.futures.keySet())server.overworld().getChunkSource().removeTicketWithRadius(ticket,chunk,0);
        COMPLETED.remove(server);
    }
}
