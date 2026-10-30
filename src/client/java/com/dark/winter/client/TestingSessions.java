// Runs isolated test sessions in independent save copies and restores the authoring save afterward.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import com.dark.winter.testing.TestingArchiveScope;
import com.dark.winter.testing.TestingServer;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/**
 * Authoring saves are never simulated in-place. A test runs in an independent
 * save copy. The durable manifest survives a JVM failure and points back to the
 * untouched authoring save. Disk work starts only after the server has stopped.
 */
public final class TestingSessions {
    /** Optional host coordinator. Offline callers preserve the existing transition and authority path. */
    public interface OnlineHooks {
        CompletableFuture<Void> beforeTransition(String action,Path source);
        CompletableFuture<Void> opened(MinecraftServer server,Path source,boolean internalExtraction);
        /** Nonblocking client-thread gate after all stopped-save disk work and before any native target open. */
        default CompletableFuture<Void> prepareStopped(Path target,String action,boolean internalExtraction){return CompletableFuture.completedFuture(null);}
        void runNative(MinecraftServer server,Runnable work)throws Exception;
        default Runnable bind(MinecraftServer server,Runnable work){return ()->{try{runNative(server,work);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}};}
        boolean action(MinecraftServer server,UUID target,String action,String value)throws IOException;
        void failed(String message);
        default boolean failedInternalExtraction(MinecraftServer server){return false;}
        default boolean allowAutomaticDiscard(MinecraftServer server){return true;}
        default java.util.Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{return java.util.Optional.empty();}
    }
    private static volatile OnlineHooks onlineHooks;
    private static volatile CompletableFuture<Void> onlineBefore,onlineOpened;
    public static void installOnlineHooks(OnlineHooks hooks){if(onlineHooks!=null&&onlineHooks!=hooks)throw new IllegalStateException("Online session coordinator is already installed");onlineHooks=java.util.Objects.requireNonNull(hooks);}
    public static void removeOnlineHooks(OnlineHooks hooks){
        if(onlineHooks==hooks){onlineHooks=null;StoppedOpening stopped=stoppedOpening;
            if(stopped!=null&&stopped.start().hooks()==hooks){TRANSITION_GENERATION.compareAndSet(stopped.start().generation(),stopped.start().generation()+1);stopped.future().cancel(true);}}
    }
    public static boolean onlineCoordinated(){return onlineHooks!=null;}
    public static boolean internalExtraction(MinecraftServer server){Extraction request=extraction;return request!=null&&server!=null&&server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(saves().resolve(request.copy()));}
    public static Path authorWorld(MinecraftServer server){
        if(server==null)return null;Path current=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if(session!=null&&current.equals(saves().resolve(session.copy())))return saves().resolve(session.author());
        if(previewSession!=null&&current.equals(saves().resolve(previewSession.copy())))return saves().resolve(previewSession.author());
        if(extraction!=null&&current.equals(saves().resolve(extraction.copy())))return saves().resolve(extraction.author());return current;
    }
    public static void dispatchNative(MinecraftServer server,Runnable work){dispatch(server,work);}
    private static void dispatch(MinecraftServer server,Runnable work){
        TestingArchiveScope.Token context=TestingArchiveScope.capture().orElse(null);Runnable scoped=context==null?work:context.wrap(work);
        OnlineHooks hooks=onlineHooks;Runnable bound=hooks==null?scoped:hooks.bind(server,scoped);
        server.execute(()->{try{bound.run();}catch(Exception failure){SourcePreparation source=sourcePreparation;if(source!=null&&source.server()==server){sourcePreparation=null;sourceScheduled=false;com.dark.winter.testing.TestingExportLoader.cancel(server);}preparingTransfer=false;transferReviewPreparing=false;transferReviewScheduled=false;fail("Продолжение операции не авторизовано; мир сохранён",failure);}});
    }
    private static void nativeAction(MinecraftServer server,UUID player,String action,String value){
        try {OnlineHooks hooks=onlineHooks;if(hooks==null||!hooks.action(server,player,action,value))TestingServer.action(server,player,action,value);}
        catch(IOException failure){fail("Продолжение действия не авторизовано",failure);}
    }
    private static <T>T archive(Minecraft client,TestingArchiveScope.IO<T> action)throws IOException{
        TestingArchiveScope.Token token=TestingArchiveScope.capture().orElse(null);
        MinecraftServer server=client==null?null:client.getSingleplayerServer();
        Path world=server==null?null:server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        WorkStart start=workStart;
        if(token==null&&start!=null&&busy())token=start.archive();
        if(token==null&&authorityRequest!=null)token=authorityRequest.archive;
        if(token==null&&onlineHooks!=null&&world!=null)token=onlineHooks.archiveContext(world).orElse(null);
        if(token!=null)return token.call(action);
        if(world!=null)TestingArchiveScope.requireWorld(world);return action.run();
    }
    private static void archiveAction(Minecraft client,Runnable action){
        try{archive(client,()->{action.run();return null;});}catch(IOException|java.io.UncheckedIOException denied){fail("Контекст архива недоступен",denied);}
    }
    private static void executeArchive(Runnable action){
        TestingArchiveScope.Token token=TestingArchiveScope.capture().orElse(null);
        IO.execute(()->{try{if(token==null)action.run();else token.call(()->{action.run();return null;});}
            catch(Exception denied){fail("Фоновый доступ к архиву отменён",denied);}});
    }
    private static <T>java.util.function.Supplier<T> archiveSupplier(java.util.function.Supplier<T> action){
        TestingArchiveScope.Token token=TestingArchiveScope.capture().orElse(null);
        return ()->{try{return token==null?action.get():token.call(action::get);}catch(IOException denied){throw new java.util.concurrent.CompletionException(denied);}};
    }
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("DarkWinter/TestSessions");
    private static final Gson JSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DarkWinter archive IO"); thread.setDaemon(true); return thread;
    });
    private record Session(int schema, String author, String copy, String baseline, String created, String review) {}
    private record Preview(int schema,String author,String copy,String version,String created) {}
    private record Work(String action, String name, Path source, MinecraftServer server) {}
    private record WorkStart(Work request,OnlineHooks hooks,long generation,TestingArchiveScope.Token archive) {}
    private record StoppedOpening(WorkStart start,Path target,String world,String message,CompletableFuture<Void> future) {}
    private static final java.util.concurrent.atomic.AtomicLong TRANSITION_GENERATION=new java.util.concurrent.atomic.AtomicLong();
    private static volatile WorkStart workStart;
    private static volatile StoppedOpening stoppedOpening;
    /** Immutable snapshot returned only after the same local author has genuinely reopened. No identity or lease is created here. */
    public record AuthorityCheckpoint(TestingArchive.Version version,Path authorPath,UUID localPlayerId,boolean priorFrozen) {
        public AuthorityCheckpoint {java.util.Objects.requireNonNull(version);authorPath=authorPath.toAbsolutePath().normalize();java.util.Objects.requireNonNull(localPlayerId);}
    }
    private static final java.util.concurrent.atomic.AtomicLong AUTHORITY_GENERATION=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor AUTHORITY_IO=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.SynchronousQueue<>(),r->{Thread thread=new Thread(r,"DarkWinter authority checkpoint prepare");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final class AuthorityRequest {
        final long generation;final MinecraftServer original;final Path author;final UUID player;final String name;
        final TestingArchiveScope.Token archive=TestingArchiveScope.capture().orElse(null);
        final java.util.function.Function<AuthorityCheckpoint,CompletableFuture<Void>> prepare;
        final CompletableFuture<AuthorityCheckpoint> result=new CompletableFuture<>();
        volatile boolean frozenCaptured,priorFrozen,stopping,cancelled;
        volatile AuthorityCheckpoint checkpoint;volatile Throwable failure;
        volatile java.util.concurrent.Future<?> prepareTask;volatile CompletableFuture<Void> prepareStage,restoreAck;
        AuthorityRequest(MinecraftServer original,Path author,UUID player,String name,java.util.function.Function<AuthorityCheckpoint,CompletableFuture<Void>> prepare){
            generation=AUTHORITY_GENERATION.incrementAndGet();this.original=original;this.author=author;this.player=player;this.name=name;this.prepare=prepare;
        }
    }
    private static volatile AuthorityRequest authorityRequest;
    private record Extraction(String author, String copy, List<String> categories) {}
    private record Guarded(String action, String value, Path source) {}
    private record TransferConfirmation(MinecraftServer server,Path target,java.util.Map<String,Path> files,boolean wasFrozen) {}
    @FunctionalInterface private interface SourceStep {void run() throws Exception;}
    private record SourcePreparation(MinecraftServer server,Path world,java.util.Set<String> categories,SourceStep success,java.util.function.Consumer<Exception> failure) {}
    private static volatile SourcePreparation sourcePreparation;
    private static volatile TransferConfirmation transferConfirmation;
    private static volatile TestingTransferReview.Snapshot transferReview;
    private static volatile boolean transferReviewPreparing,transferReviewScheduled,pendingTransferReview;
    private static volatile boolean sourceScheduled;
    private static volatile MinecraftServer activeSessionServer;
    private record TemplateRequest(String filename,List<String> categories,Path source,MinecraftServer server) {}
    private record ReplayRequest(Path source,String version,String document,int eventIndex,long elapsed,String reviewId) {}
    private record ReplayCursor(String document,int eventIndex,long elapsed,boolean paused) {}
    private record ReplayCheckpoint(Path source,String document,String label,int eventIndex,long elapsed,boolean paused) {}
    private static volatile ReplayRequest replayRequest;
    private static volatile ReplayCheckpoint replayCheckpoint;
    private static volatile ReplayCursor replayCursor;
    private static volatile Path replayTarget;
    private static boolean automaticDiscard;
    private static volatile String status = "Готово";
    private static volatile String historyStorageStatus="Размер архива ещё не рассчитан";
    private static volatile TestingSessionReview.Review reviewState;
    private static volatile List<String> versions = List.of();
    private static volatile Work pending;
    private static volatile CompletableFuture<String> work;
    private static volatile CompletableFuture<TestingArchive.Version> templateValidation;
    private static volatile TemplateRequest templateRequest;
    private static volatile String selected;
    private static volatile Session session;
    private static volatile Preview previewSession;
    private static boolean previewActive;
    private static Path restoreClientFile;
    private static boolean loadedManifest, running, paused, keepSettings, keepObjects, keepBuildings;
    private static boolean opening, initializedTest;
    private static Path openingPath, transferObjects, transferBuildings;
    private static String confirmation;
    private static Path observedSave;
    private static final java.util.Map<Path, String> selections = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Extraction extraction;
    private static volatile boolean extracting,preparingTransfer;
    private static volatile Path importTarget;
    private static volatile Guarded guarded;
    private static String openedMessage;
    private static volatile boolean restoreClientOnOpen;
    private static volatile java.util.Map<String, Path> pendingImports = java.util.Map.of();
    static {
        // A read-only extraction world must not advance NPC AI or environmental events before export.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Extraction request=extraction;
            Path source=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if(TestingServer.isTesting(server)&&(request!=null && source.equals(saves().resolve(request.copy())) || Files.isRegularFile(cursorFile(source)) || Files.isRegularFile(previewFile(source))))
                server.tickRateManager().setFrozen(true);
        });
    }
    private TestingSessions() {}
    private static Path saves() { return FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize(); }
    private static Path manifest() { return TestingArchive.root().resolve("session.json"); }
    public static String status() { return status; }
    public static List<String> versions() { return versions; }
    public static String historyStorageStatus() { return historyStorageStatus; }
    public static boolean busy() { return stoppedOpening!=null || authorityRequest!=null || onlineBefore!=null || pending != null || work != null || opening || extraction != null || templateValidation != null || preparingTransfer || sourcePreparation!=null || transferReviewPreparing; }
    public static boolean isTestRunning() { return running; }
    public static boolean hasTestSession() { return session!=null; }
    public static boolean automaticDiscardPending(MinecraftServer server){
        Session current=session;return automaticDiscard&&running&&current!=null&&server!=null&&server==activeSessionServer
            &&!keepObjects&&!keepBuildings&&!keepSettings&&server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(saves().resolve(current.copy()));
    }
    public static boolean isPreview() { return previewActive; }
    public static boolean isTestPaused() {
        MinecraftServer server=activeSessionServer;
        return running&&server!=null&&server.tickRateManager().isFrozen()&&TestingServer.settings(server).mode.equals("TEST_PAUSED");
    }
    public static boolean transferReviewActive(){return transferConfirmation!=null;}
    public static boolean transferReviewReady(){return transferConfirmation!=null&&transferReview!=null&&!transferReviewPreparing;}
    public static boolean transferReviewCanApply(){return transferReviewReady()&&transferReview.canApply();}
    public static List<String> transferReviewLines(){
        var review=transferReview;if(review==null)return List.of("Подготовка выбранных категорий и целевых чанков; авторский остров заморожен.");
        var lines=new java.util.ArrayList<>(review.lines());
        if(review.conflicts().isEmpty())lines.add("Известные конфликты не обнаружены. Окончательные проверки повторятся перед записью.");
        else{lines.add("Перенос запрещён: "+review.conflicts().size()+" конфликтов.");lines.addAll(review.conflicts().stream().limit(30).toList());}
        return List.copyOf(lines);
    }
    public static boolean keepObjects() { return keepObjects; }
    public static boolean keepBuildings() { return keepBuildings; }
    public static boolean keepSettings() { return keepSettings; }
    public static String selectedVersion() { return selected == null ? "" : selected; }
    public static void clearConfirmation(){confirmation=null;}
    public static List<String> reviewLines() { var review=reviewState;return review==null?List.of():review.lines(); }
    public static boolean reviewReady() { return running && reviewState!=null && !preparingTransfer; }

    /**
     * The caller may prepare public authority metadata from this stopped immutable snapshot.
     * Its callback must not publish a Host; cancellation/late completion never authorizes one.
     */
    public static CompletableFuture<AuthorityCheckpoint> checkpointForAuthority(Minecraft client,String name,
            java.util.function.Function<AuthorityCheckpoint,CompletableFuture<Void>> prepare) {
        try {
            if(client==null||!client.isSameThread())throw new IOException("Authority checkpoint requires the client owner thread");
            MinecraftServer server=client.getSingleplayerServer();
            if(name==null||name.isBlank()||name.length()>80||prepare==null)throw new IOException("Invalid authority checkpoint request");
            if(busy()||transferReviewActive()||onlineHooks!=null||session!=null||previewSession!=null||running||previewActive
                    ||Files.exists(manifest())||server==null||client.level==null||client.player==null
                    ||!TestingWorlds.isTesting()||!TestingServer.isTesting(server)
                    ||com.dark.winter.online.server.OnlineGameHost.get(server)!=null)
                throw new IOException("Authority checkpoint requires an idle marked local author without Online, test or preview");
            Path author=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            AuthorityRequest request=new AuthorityRequest(server,author,client.player.getUUID(),name,prepare);authorityRequest=request;
            request.result.whenComplete((value,error)->{
                if(!request.result.isCancelled())return;
                request.cancelled=true;request.failure=new java.util.concurrent.CancellationException("Authority preparation cancelled");
                AUTHORITY_GENERATION.compareAndSet(request.generation,request.generation+1);
                var task=request.prepareTask;if(task!=null)task.cancel(true);
                var stage=request.prepareStage;if(stage!=null)stage.cancel(true);
            });
            try{server.execute(()->{
                try {
                    requireAuthorityOwner(request,server);
                    if(!authorityCurrent(request))throw new java.util.concurrent.CancellationException("Authority preparation cancelled");
                    if(TestingServer.busy(server))throw new IOException("Finish the current editor transaction first");
                    request.priorFrozen=server.tickRateManager().isFrozen();request.frozenCaptured=true;server.tickRateManager().setFrozen(true);
                    client.execute(()->{
                        if(!authorityCurrent(request)||client.getSingleplayerServer()!=server){request.failure=new IOException("Authority checkpoint was cancelled before close");return;}
                        try {request.stopping=true;disconnect(client,new Work("authority_checkpoint",name,author,server));}
                        catch(Exception failure){request.failure=failure;request.stopping=false;}
                    });
                }catch(Exception failure){request.failure=failure;}
            });}catch(Exception enqueueFailure){request.failure=enqueueFailure;}
            return request.result;
        }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
    }
    private static boolean authorityCurrent(AuthorityRequest request){
        return authorityRequest==request&&!request.cancelled&&AUTHORITY_GENERATION.get()==request.generation&&!request.result.isDone();
    }
    private static void requireAuthorityOwner(AuthorityRequest request,MinecraftServer server)throws IOException {
        if(server==null||!server.isSameThread()||!server.isRunning()||!server.isSingleplayer()||server.isPublished()
                ||onlineHooks!=null||com.dark.winter.online.server.OnlineGameHost.get(server)!=null
                ||!TestingServer.isTesting(server)
                ||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(request.author)
                ||!java.util.Set.of("EDIT","CHARACTER").contains(TestingServer.settings(server).mode))
            throw new IOException("The exact marked local author is no longer available");
        var player=server.getPlayerList().getPlayer(request.player);
        if(player==null||!server.isSingleplayerOwner(player.nameAndId())||!player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
            throw new IOException("The actual local author player changed");
    }
    private static void prepareAuthority(AuthorityRequest request,AuthorityCheckpoint checkpoint)throws Exception {
        if(!authorityCurrent(request))throw new java.util.concurrent.CancellationException("Authority preparation cancelled");
        long deadline=System.nanoTime()+45_000_000_000L;
        var task=AUTHORITY_IO.submit(()->{try{
            if(request.archive!=null){request.archive.call(()->{runAuthorityPrepare(request,checkpoint,deadline);return null;});return;}
            runAuthorityPrepare(request,checkpoint,deadline);
        }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}});

        request.prepareTask=task;
        try {
            if(!authorityCurrent(request)){task.cancel(true);throw new java.util.concurrent.CancellationException("Authority preparation cancelled");}
            task.get(Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);
        }finally {
            if(!task.isDone())task.cancel(true);
            var stage=request.prepareStage;if(stage!=null&&!stage.isDone())stage.cancel(true);
        }
    }
    private static void runAuthorityPrepare(AuthorityRequest request,AuthorityCheckpoint checkpoint,long deadline){
            try {
                if(!authorityCurrent(request))throw new java.util.concurrent.CancellationException("Authority preparation cancelled");
                CompletableFuture<Void> stage=java.util.Objects.requireNonNull(request.prepare.apply(checkpoint),"Authority prepare future");
                request.prepareStage=stage;
                if(!authorityCurrent(request)){stage.cancel(true);throw new java.util.concurrent.CancellationException("Authority preparation cancelled");}
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new java.util.concurrent.TimeoutException("Authority preparation exceeded 45 seconds");
                stage.get(remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
                if(!authorityCurrent(request))throw new java.util.concurrent.CancellationException("Authority preparation cancelled");
            }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}

    }
    private static void abortAuthority(Throwable failure){
        AuthorityRequest request=authorityRequest;if(request==null)return;
        authorityRequest=null;request.failure=failure;request.cancelled=true;
        AUTHORITY_GENERATION.compareAndSet(request.generation,request.generation+1);
        var task=request.prepareTask;if(task!=null)task.cancel(true);
        var stage=request.prepareStage;if(stage!=null)stage.cancel(true);
        request.result.completeExceptionally(failure);
    }
    private static void pollAuthority(Minecraft client,MinecraftServer server,Path current) {
        AuthorityRequest request=authorityRequest;if(request==null)return;
        boolean beforeClose=!request.stopping&&request.failure!=null;
        boolean reopened=request.stopping&&server!=null&&server!=request.original&&request.author.equals(current)
            &&client.level!=null&&client.player!=null&&TestingWorlds.isTesting()&&!opening&&pending==null&&work==null
            &&!preparingTransfer&&!com.dark.winter.client.loading.WorldPreloadClient.active()&&!TestingServer.busy(server);
        if(!beforeClose&&!reopened)return;
        if(beforeClose&&(server==null||server!=request.original||!request.author.equals(current))){abortAuthority(request.failure);return;}
        if(request.restoreAck==null) {
            CompletableFuture<Void> ack=new CompletableFuture<>();request.restoreAck=ack;
            try{server.execute(()->{
                try{requireAuthorityOwner(request,server);if(request.frozenCaptured)server.tickRateManager().setFrozen(request.priorFrozen);ack.complete(null);}
                catch(Exception failure){ack.completeExceptionally(failure);}
            });}catch(Exception enqueueFailure){ack.completeExceptionally(enqueueFailure);}return;
        }
        if(!request.restoreAck.isDone())return;
        try{request.restoreAck.join();}catch(Exception failure){abortAuthority(failure);return;}
        authorityRequest=null;
        if(request.failure!=null||request.cancelled||request.checkpoint==null){
            Throwable failure=request.failure==null?new IOException("Authority checkpoint was not prepared"):request.failure;
            request.result.completeExceptionally(failure);status="Подготовка Online отменена; авторский мир открыт";
        }else{status="Исходная версия Online подготовлена; авторский мир открыт";request.result.complete(request.checkpoint);}
    }

    public static void tick(Minecraft client) {archiveAction(client,()->tickScoped(client));}
    private static void tickScoped(Minecraft client) {
        if (!loadedManifest) {
            loadedManifest = true;
            try {
                if (Files.isRegularFile(manifest()) && Files.size(manifest()) < 16384) {
                    Session recovered = JSON.fromJson(Files.readString(manifest()), Session.class);
                    if (recovered.schema() != 2) throw new IOException("Неподдерживаемая сессия");
                    folder(recovered.author()); folder(recovered.copy());
                    session = recovered;
                    status = "Незавершённый тест сохранён; авторский мир цел. Откройте копию для завершения.";
                }
            } catch (IOException | RuntimeException error) { fail("Манифест сессии требует проверки", error); }
        }
        MinecraftServer server = client.getSingleplayerServer();
        Path current = server == null ? null : server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        pollAuthority(client,server,current);
        pollStoppedOpening(client);
        if(current!=null&&TestingServer.isTesting(server)&&previewSession==null&&Files.isRegularFile(previewFile(current))) {
            try {
                if(Files.size(previewFile(current))>16384)throw new IOException("Манифест предпросмотра слишком велик");
                Preview recovered=JSON.fromJson(Files.readString(previewFile(current)),Preview.class);
                if(recovered==null||recovered.schema()!=1||!folder(recovered.copy()).equals(current))throw new IOException("Манифест предпросмотра не совпадает с миром");
                folder(recovered.author());TestingArchive.read(recovered.version());previewSession=recovered;
            }catch(Exception error){fail("Копия предпросмотра доступна только для чтения; источник требует проверки",error);}
        }
        previewActive=previewSession!=null&&current!=null&&current.equals(saves().resolve(previewSession.copy()));
        running = session != null && current != null && current.equals(saves().resolve(session.copy())) && TestingWorlds.isTesting();
        activeSessionServer=running?server:null;
        if (running) paused = server.tickRateManager().isFrozen();
        pollSourcePreparation(client,server,current);
        pollTransferReview(client,server,current);
        if(!running)automaticDiscard=false;
        else if(!busy()&&!TestingServer.busy(server)) {
            if(!automaticDiscard&&com.dark.winter.testing.TestingScenarios.discardRequested(server)
                    &&(onlineHooks==null||onlineHooks.allowAutomaticDiscard(server))) {
                automaticDiscard=true;keepObjects=false;keepBuildings=false;keepSettings=false;
                dispatch(server,()->{try{com.dark.winter.testing.TestingScenarios.acknowledgeDiscard(server);}
                    catch(IOException error){fail("Не удалось подтвердить завершение сценария",error);}});
                beginReview(client,server,current);
            }else if(automaticDiscard&&reviewReady())action(client,"finish","");
        }
        pollTemplateValidation(client,server,current);
        if (current != null && TestingWorlds.isTesting() && !current.equals(observedSave)) {
            observedSave = current; selected = selections.get(current); versions = List.of();
            Path changedWorld = current;
            executeArchive(() -> { try { refresh(changedWorld); } catch (IOException error) { fail("История недоступна", error); } });
        }
        if(running&&!initializedTest&&!opening&&client.level!=null&&client.player!=null&&Files.isRegularFile(cursorFile(current))) {
            try {
                if(Files.size(cursorFile(current))>1024*1024)throw new IOException("Курсор сценария превышает 1 МиБ");
                ReplayCursor recovered=JSON.fromJson(Files.readString(cursorFile(current)),ReplayCursor.class);
                if(recovered==null||recovered.document()==null||recovered.eventIndex()<0||recovered.elapsed()<0)throw new IOException("Повреждён курсор сценария");
                replayCursor=recovered;replayTarget=current;opening=true;openingPath=current;
            }catch(Exception error){initializedTest=true;fail("Сохранённый курсор требует проверки; тест остаётся на паузе",error);}
        }
        if (opening && current != null && current.equals(openingPath) && client.level != null && client.player != null
                && TestingWorlds.isTesting() && !com.dark.winter.client.loading.WorldPreloadClient.active()) {
            OnlineHooks hooks=onlineHooks;
            if(hooks!=null) {
                if(onlineOpened==null){try{onlineOpened=hooks.opened(server,current,internalExtraction(server));}catch(Exception failure){onlineOpened=CompletableFuture.failedFuture(failure);}return;}
                if(!onlineOpened.isDone())return;
                try{onlineOpened.join();}catch(Exception failure){opening=false;onlineOpened=null;fail("Сетевой сеанс не восстановлен; все сохранения сохранены",failure);return;}
                onlineOpened=null;
            }
            opening = false;
            if (restoreClientOnOpen) {
                restoreClientOnOpen = false;
                try { TestingTransientState.restore(restoreClientFile==null?TestingArchive.root().resolve("session-client-state.json"):restoreClientFile);restoreClientFile=null; }
                catch (IOException error) { fail("Не удалось восстановить клиентское состояние", error); }
            }
            if(previewActive) {
                dispatch(server,()->{
                    for(var player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SPECTATOR);
                    }
                    server.tickRateManager().setFrozen(true);
                });
                status="Визуальный предпросмотр: независимая копия, симуляция на паузе. Вернуться — в панели История.";
            } else if (extraction != null && current.equals(saves().resolve(extraction.copy())) && !extracting) {
                beginExtraction(client, server);
            } else if (running && !initializedTest) {
                initializedTest = true;
                ReplayCursor cursor=current.equals(replayTarget)?replayCursor:null;
                replayCursor=null;replayTarget=null;
                dispatch(server,() -> {
                    try {
                    for (var player : server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SURVIVAL);
                        nativeAction(server, player.getUUID(), "mode", cursor!=null&&cursor.paused()?"TEST_PAUSED":"TEST_RUNNING");
                    }
                    if(cursor!=null) {
                        com.dark.winter.testing.TestingScenarios.saveDocument(server,cursor.document());
                        com.dark.winter.testing.TestingScenarios.resume(server,cursor.document(),cursor.eventIndex(),cursor.elapsed());
                        Files.deleteIfExists(cursorFile(current));
                        server.tickRateManager().setFrozen(cursor.paused());
                    }
                    } catch(Exception error) {
                        server.tickRateManager().setFrozen(true);
                        for(var player:server.getPlayerList().getPlayers())nativeAction(server,player.getUUID(),"mode","TEST_PAUSED");
                        fail("Воспроизведение остановлено; изолированная копия и авторский мир сохранены",error);
                    }
                });
                status = "TEST_RUNNING: изолированная копия; F8 — панель";
            } else if (!running) {
                if (openedMessage != null) { status = openedMessage; openedMessage = null; }
                if (guarded != null) {
                    Guarded deferred = guarded; guarded = null;
                    if (current.equals(deferred.source())) {
                        if (deferred.action().equals("prefab_paste")) {
                            String[] data = deferred.value().split("\\|");
                            String[] coordinates = data[1].split(",");
                            TestingPrefabs.pasteAfterCheckpoint(client, new net.minecraft.core.BlockPos(
                                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])),
                                    data[0], Integer.parseInt(data[2]));
                        } else nativeAction(server, client.player.getUUID(), deferred.action(), deferred.value());
                    }
                    else status = "Отложенная операция отменена: открыт другой мир";
                }
                if ((transferObjects != null || transferBuildings != null || !pendingImports.isEmpty())
                        && (importTarget==null || !current.equals(importTarget))) {
                    transferObjects=null;transferBuildings=null;pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;
                    status="Перенос категорий отменён: открыт другой авторский мир";
                }
                if (transferObjects != null || transferBuildings != null) {
                    var transfers = new java.util.LinkedHashMap<String,Path>(pendingImports);
                    if (transferObjects != null) transfers.put("object", transferObjects);
                    if (transferBuildings != null) transfers.put("building", transferBuildings);
                    pendingImports = java.util.Map.copyOf(transfers);
                    transferObjects = null; transferBuildings = null;
                }
                if (!pendingImports.isEmpty()) {
                    var transfers = pendingImports;boolean reviewRequired=pendingTransferReview;
                    pendingImports = java.util.Map.of();importTarget=null;pendingTransferReview=false;
                    if(reviewRequired)beginTransferReview(client,server,current,transfers);
                    else dispatch(server,() -> {
                        try {
                            com.dark.winter.testing.TestingImportLoader.queue(server, transfers);
                            status = transferAppliedMessage(transfers);
                        } catch (Exception error) { fail("Выборочный перенос отменён до изменения мира", error); }
                    });
                }
                initializedTest = false;
            }
        }
        Work request = pending;
        if (request != null && !request.server().isRunning() && request.server().isStopped()
                && client.getSingleplayerServer() == null) {
            pending = null;
            work = CompletableFuture.supplyAsync(archiveSupplier(() -> {
                try { return perform(request); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }), IO);
        }
        CompletableFuture<String> future = work;
        if (future != null && future.isDone()) {
            work = null;
            try {
                String world = future.join();
                if (world != null) {
                    WorkStart completed=workStart;
                    if(completed!=null&&completed.hooks()!=null){
                        if(!stoppedContext(client,completed))throw new IOException("Stopped Online transition was cancelled or changed");
                        Path target=TestingArchive.checkedSave(saves().resolve(world),true);
                        Extraction scratch=extraction;boolean internal=scratch!=null&&target.equals(saves().resolve(scratch.copy()));
                        String completedMessage=status;
                        CompletableFuture<Void> gate=java.util.Objects.requireNonNull(completed.hooks().prepareStopped(target,completed.request().action(),internal),"Stopped target preparation future");
                        stoppedOpening=new StoppedOpening(completed,target,world,completedMessage,gate);
                        status="Подготовка права ведущего для сохранённого состояния";
                        pollStoppedOpening(client);
                    }else openStoredWorld(client,world);
                }else workStart=null;
            } catch (Exception error) {
                guarded = null;extraction=null;extracting=false;replayRequest=null;replayCheckpoint=null;replayCursor=null;replayTarget=null;
                pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;transferObjects=null;transferBuildings=null;
                invalidateStoppedOpening();
                fail("Операция архива не завершена; исходный мир сохранён", error);
                abortAuthority(error);
                client.gui.setScreen(new TitleScreen());
            }
        }
    }

    private static boolean stoppedContext(Minecraft client,WorkStart start) {
        return workStart==start&&TRANSITION_GENERATION.get()==start.generation()&&onlineHooks==start.hooks()
            &&client.getSingleplayerServer()==null&&client.level==null
            &&start.request().server().isStopped()&&!start.request().server().isRunning();
    }
    private static void invalidateStoppedOpening() {
        StoppedOpening stopped=stoppedOpening;stoppedOpening=null;
        WorkStart start=workStart;workStart=null;
        if(start!=null)TRANSITION_GENERATION.compareAndSet(start.generation(),start.generation()+1);
        if(stopped!=null&&!stopped.future().isDone())stopped.future().cancel(true);
    }
    private static void pollStoppedOpening(Minecraft client) {
        StoppedOpening stopped=stoppedOpening;if(stopped==null)return;
        try {
            if(!stoppedContext(client,stopped.start()))throw new IOException("Stopped target preparation no longer belongs to the active transition");
            if(!stopped.future().isDone())return;
            stopped.future().join();
            if(!stoppedContext(client,stopped.start()))throw new IOException("Stopped target preparation completed after cancellation");
            TestingArchive.checkedSave(stopped.target(),true);
            stoppedOpening=null;status=stopped.message();openStoredWorld(client,stopped.world());
        }catch(Exception failure) {
            invalidateStoppedOpening();
            guarded=null;extraction=null;extracting=false;replayRequest=null;replayCheckpoint=null;replayCursor=null;replayTarget=null;
            pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;transferObjects=null;transferBuildings=null;
            fail("Подготовка ведущего отменена; все сохранения и снимки сохранены",failure);
            if(client.getSingleplayerServer()==null&&client.level==null)client.gui.setScreen(new TitleScreen());
        }
    }
    private static void openStoredWorld(Minecraft client,String world) {
        workStart=null;
        opening=true;openingPath=saves().resolve(world).toAbsolutePath().normalize();openedMessage=status;status="Загрузка "+world;
        client.createWorldOpenFlows().openWorld(world,()->{
            opening=false;guarded=null;extraction=null;extracting=false;replayRequest=null;replayCheckpoint=null;replayCursor=null;replayTarget=null;
            pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;transferObjects=null;transferBuildings=null;
            status="Открытие отменено. Все сохранения и архивы сохранены.";
            abortAuthority(new IOException("Opening the saved author was cancelled"));client.gui.setScreen(new TitleScreen());
        });
    }

    private static String transferAppliedMessage(java.util.Map<String,Path> files){
        return files.containsKey("terrain")&&files.keySet().stream().anyMatch(key->!key.equals("terrain")&&!key.equals("surface"))
                ?"Импорт имеет два этапа истории: рельеф, затем остальные категории; отмена выполняется по этапам"
                :"Выбранные категории переданы в одну транзакцию Undo/Redo";
    }
    private static void beginTransferReview(Minecraft client,MinecraftServer server,Path target,java.util.Map<String,Path> files){
        preparingTransfer=true;status="Подготовка обзора различий: до подтверждения категории не применяются";
        dispatch(server,()->{
            boolean wasFrozen=server.tickRateManager().isFrozen();server.tickRateManager().setFrozen(true);
            transferConfirmation=new TransferConfirmation(server,target,java.util.Map.copyOf(files),wasFrozen);
            transferReview=null;transferReviewPreparing=true;transferReviewScheduled=false;preparingTransfer=false;
        });
    }
    private static void clearTransferReview(){
        transferConfirmation=null;transferReview=null;transferReviewPreparing=false;transferReviewScheduled=false;
    }
    private static void pollTransferReview(Minecraft client,MinecraftServer server,Path current){
        TransferConfirmation request=transferConfirmation;if(request==null)return;
        if(server!=request.server()||current==null||!current.equals(request.target())||!server.isRunning()||client.level==null||client.player==null){
            clearTransferReview();
            if(request.server().isRunning())request.server().execute(()->{
                boolean cancelled=com.dark.winter.testing.TestingImportLoader.cancelPreview(request.server());
                if(cancelled&&!TestingServer.busy(request.server())&&!TestingServer.recoveryBlocked(request.server()))
                    request.server().tickRateManager().setFrozen(request.wasFrozen());
            });
            status="Обзор переноса отменён: открытый авторский мир изменился";return;
        }
        // Poll only the initial loader preparation. Confirmations must never republish its cached earlier target signature.
        if(transferReview!=null||!transferReviewPreparing||transferReviewScheduled)return;
        transferReviewScheduled=true;dispatch(server,()->{
            boolean handedToIO=false;
            try{
                if(transferConfirmation!=request)return;
                if(!com.dark.winter.testing.TestingImportLoader.preparePreview(server,request.files())){status="Подготовка целевых чанков для обзора различий";return;}
                var result=com.dark.winter.testing.TestingImportLoader.previewResult(server);handedToIO=true;
                executeArchive(()->{
                    TestingTransferReview.Snapshot captured;
                    try{captured=TestingTransferReview.capture(request.target(),request.files(),result.lines(),result.conflicts(),result.signature(),request.wasFrozen());}
                    catch(Exception error){captured=new TestingTransferReview.Snapshot(request.target(),request.files(),java.util.Map.of(),
                            List.of("Обзор недоступен: "+error.getMessage()),List.of("Не удалось связать обзор с SHA-256 категорий"),"",request.wasFrozen());}
                    var ready=captured;client.execute(()->{
                        if(transferConfirmation!=request)return;
                        transferReview=ready;transferReviewPreparing=false;transferReviewScheduled=false;
                        status=ready.canApply()?"Обзор готов. Проверьте различия и явно подтвердите перенос.":"Обзор содержит конфликты. Применение запрещено; доступна отмена.";
                        TestingEditorScreen.openTransferReview(client);
                    });
                });
            }catch(Exception error){
                transferReview=new TestingTransferReview.Snapshot(request.target(),request.files(),java.util.Map.of(),
                        List.of("Обзор переноса недоступен: "+error.getMessage()),List.of("Подготовка не завершена; авторские категории не применены"),"",request.wasFrozen());
                transferReviewPreparing=false;status="Перенос ожидает отмены: "+error.getMessage();
                client.execute(()->TestingEditorScreen.openTransferReview(client));
            }finally{if(!handedToIO)transferReviewScheduled=false;}
        });
    }
    private static void transferAction(Minecraft client,String action){
        TransferConfirmation request=transferConfirmation;var reviewed=transferReview;
        MinecraftServer server=client.getSingleplayerServer();
        if(request==null||server!=request.server()||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(request.target())){
            status="Нет обзора переноса для текущего автора";return;
        }
        if(action.equals("transfer_cancel")){
            transferReviewPreparing=true;dispatch(server,()->{
                if(transferConfirmation!=request){if(transferConfirmation==null)transferReviewPreparing=false;return;}
                boolean cancelled=com.dark.winter.testing.TestingImportLoader.cancelPreview(server);
                if(!cancelled){transferReviewPreparing=false;status="Перенос уже принят в журнал; отмена выполняется через Undo";return;}
                clearTransferReview();
                if(!TestingServer.busy(server)&&!TestingServer.recoveryBlocked(server))server.tickRateManager().setFrozen(request.wasFrozen());
                status="Выборочный перенос отменён. Авторские категории не изменены.";
            });return;
        }
        if(transferReviewPreparing||reviewed==null){status="Дождитесь фактического обзора";return;}
        if(!reviewed.canApply()){status="В обзоре есть конфликты. Применение запрещено.";return;}
        transferReviewPreparing=true;dispatch(server,()->{
            try{
                if(transferConfirmation!=request)throw new IOException("Обзор заменён");
                status="Подтверждение: полная SHA-256 проверка зависимостей в фоне; категории ещё не применены";
                com.dark.winter.testing.TestingImportLoader.confirmPreview(server,reviewed.signature(),request.wasFrozen(),outcome->{
                    if(transferConfirmation!=request)return;
                    if(outcome.applied()){
                        clearTransferReview();status=transferAppliedMessage(request.files());
                    }else{
                        var fresh=outcome.preview();
                        transferReview=fresh==null?reviewed.failed(outcome.message()):
                                new TestingTransferReview.Snapshot(request.target(),request.files(),reviewed.hashes(),
                                        fresh.lines(),fresh.conflicts(),fresh.signature(),request.wasFrozen());
                        transferReviewPreparing=false;status=outcome.message();
                    }
                });
            }catch(Exception error){
                if(!com.dark.winter.testing.TestingImportLoader.active(server)){
                    clearTransferReview();
                    if(!TestingServer.recoveryBlocked(server))server.tickRateManager().setFrozen(request.wasFrozen());
                }else{
                    transferReview=reviewed.failed(error.getMessage()==null?"Ошибка проверки":error.getMessage());
                    transferReviewPreparing=false;
                }
                status="Подтверждение переноса отклонено: "+error.getMessage();
            }
        });
    }

    private static void prepareSource(MinecraftServer server,Path world,java.util.Set<String> categories,SourceStep success,java.util.function.Consumer<Exception> failure) {
        if(sourcePreparation!=null)throw new IllegalStateException("Source preparation already active");
        server.tickRateManager().setFrozen(true);
        sourcePreparation=new SourcePreparation(server,world,java.util.Set.copyOf(categories),success,failure);sourceScheduled=false;
        status="Подготовка полного источника: сохранённые чанки и сущности";
    }
    private static void pollSourcePreparation(Minecraft client,MinecraftServer server,Path current) {
        SourcePreparation request=sourcePreparation;if(request==null)return;
        if(server!=request.server()||current==null||client.level==null||client.player==null||!current.equals(request.world())||!server.isRunning()) {
            sourcePreparation=null;preparingTransfer=false;sourceScheduled=false;
            if(request.server().isRunning())request.server().execute(()->com.dark.winter.testing.TestingExportLoader.cancel(request.server()));
            status="Подготовка источника отменена: открытый мир изменился";return;
        }
        if(sourceScheduled)return;
        sourceScheduled=true;dispatch(server,()->{
            try {
                if(sourcePreparation!=request)return;
                if(!com.dark.winter.testing.TestingExportLoader.prepare(server,request.categories())){status=com.dark.winter.testing.TestingExportLoader.status(server);return;}
                sourcePreparation=null;
                try{request.success().run();}finally{com.dark.winter.testing.TestingExportLoader.finish(server);}
            }catch(Exception error) {
                sourcePreparation=null;com.dark.winter.testing.TestingExportLoader.cancel(server);request.failure().accept(error);
            }finally{sourceScheduled=false;}
        });
    }
    private static void verifyAuthorReview(Minecraft client,MinecraftServer server,Path current,TestingSessionReview.Review nativeReview,boolean finish) {
        Session expected=session;
        executeArchive(()->{
            TestingSessionReview.Review checked;
            try{checked=TestingSessionReview.withAuthorCheck(nativeReview,expected.baseline(),folder(expected.author()));TestingSessionReview.writeFinal(expected.review(),checked);}
            catch(Exception error){var lines=new java.util.ArrayList<>(nativeReview.lines());lines.add("Сверка авторского мира недоступна: "+error.getMessage()+". Выборочное сохранение запрещено.");
                checked=new TestingSessionReview.Review(List.copyOf(lines),nativeReview.signature(),java.util.Set.of());}
            TestingSessionReview.Review result=checked;
            client.execute(()->{
                if(session!=expected||client.getSingleplayerServer()!=server||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(current)) {
                    preparingTransfer=false;status="Обзор отменён: текущий мир изменился";return;
                }
                reviewState=result;
                if(finish) {
                    if(keepObjects&&!result.available().contains("object")||keepBuildings&&!result.available().contains("building")||keepSettings&&!result.available().contains("settings")) {
                        transferObjects=null;transferBuildings=null;pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;preparingTransfer=false;
                        status="Обнаружен конфликт до подтверждения. Снимите недоступные категории; авторский мир не изменён.";return;
                    }
                    disconnect(client,new Work("finish","",current,server));
                }else{preparingTransfer=false;status="TEST_REVIEW: известные конфликты и SHA-256 автора проверены; окончательные native-проверки выполняются перед записью";}
            });
        });
    }
    /** External replay timelines never mutate authoring-world definitions during simulation. */
    public static String scenarioDocument(MinecraftServer server,String name) throws IOException {
        String current=com.dark.winter.testing.TestingScenarios.doc(server,name);
        Path source=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path author=session!=null&&source.equals(saves().resolve(session.copy()))?folder(session.author()):source;
        Path file=timelineFile(author,name);
        if(!Files.isRegularFile(file))return current;
        if(Files.isSymbolicLink(file)||Files.size(file)>1024*1024)throw new IOException("Неподдерживаемая внешняя история сценария");
        String external=Files.readString(file);
        var currentDocument=com.google.gson.JsonParser.parseString(current).getAsJsonObject();
        var externalDocument=com.google.gson.JsonParser.parseString(external).getAsJsonObject();
        return JSON.toJson(com.dark.winter.testing.TestingScenarioDependencies.mergeTimeline(currentDocument,externalDocument));
    }
    public static List<String> scenarioCheckpoints(MinecraftServer server,String name) throws IOException {
        var document=com.google.gson.JsonParser.parseString(scenarioDocument(server,name)).getAsJsonObject();
        if(!document.has("replay")||document.get("replay").isJsonNull())return List.of();
        var result=new java.util.ArrayList<String>();
        for(var row:document.getAsJsonObject("replay").getAsJsonArray("checkpoints")) {
            var point=row.getAsJsonObject();
            result.add(point.get("label").getAsString()+" | "+point.get("versionId").getAsString()+" | "+point.get("eventIndex").getAsInt()+" | "+point.get("elapsedTicks").getAsLong());
        }
        return List.copyOf(result);
    }
    private static Path timelineFile(Path author,String name) throws IOException {
        return TestingArchive.root().resolve("scenario-timelines").resolve(TestingArchiveScope.canonicalSource(TestingArchive.worldKey(author)))
                .resolve(com.dark.winter.testing.TestingServer.safeName(name)+".json");
    }
    /** Replays only a saved world/event pair in a new isolated copy; the authoring world is never simulated. */
    public static void scenarioReplay(Minecraft client,String versionId,String document,int eventIndex,long elapsedTicks) {archiveAction(client,()->scenarioReplayScoped(client,versionId,document,eventIndex,elapsedTicks));}
    private static void scenarioReplayScoped(Minecraft client,String versionId,String document,int eventIndex,long elapsedTicks) {
        MinecraftServer server=scenarioServer(client,false);if(server==null)return;
        Path source=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        boolean first=session==null;
        if(!first&&!running){status="Сначала завершите сохранённое испытание";return;}
        try{
            if(first)TestingTransientState.save(TestingArchive.root().resolve("session-client-state.json"));
            preparingTransfer=true;
            dispatch(server,()->{
                try{
                    com.dark.winter.testing.TestingScenarios.validateReplay(server,document,versionId,eventIndex,elapsedTicks);
                    Path author=first?source:folder(session.author());
                    TestingArchive.Version target=TestingArchive.read(versionId);
                    if(!TestingArchiveScope.sameSource(target.source(),TestingArchive.worldKey(author)))throw new IOException("Версия воспроизведения относится к другому авторскому миру");
                    String reviewId=first?UUID.randomUUID().toString():session.review();
                    if(first) {
                        boolean wasFrozen=server.tickRateManager().isFrozen();
                        prepareSource(server,source,java.util.Set.of("building","object"),()->{
                            TestingSessionReview.baseline(server,reviewId);
                            queueReplayDisconnect(client,server,source,versionId,document,eventIndex,elapsedTicks,reviewId);
                        },error->{
                            try {
                                TestingSessionReview.baseline(server,reviewId);TestingSessionReview.markBaselineUnavailable(reviewId,error.getMessage());
                                queueReplayDisconnect(client,server,source,versionId,document,eventIndex,elapsedTicks,reviewId);
                            }catch(Exception failure){server.tickRateManager().setFrozen(wasFrozen);preparingTransfer=false;fail("Не удалось сохранить исходный обзор воспроизведения",failure);}
                        });
                    }else queueReplayDisconnect(client,server,source,versionId,document,eventIndex,elapsedTicks,reviewId);
                }catch(Exception error){preparingTransfer=false;fail("Воспроизведение отклонено до закрытия мира",error);}
            });
        }catch(Exception error){preparingTransfer=false;fail("Не удалось подготовить воспроизведение",error);}
    }
    private static void queueReplayDisconnect(Minecraft client,MinecraftServer server,Path source,String version,String document,int eventIndex,long elapsed,String reviewId) throws IOException {
        String selectedDocument=selectReplayDocument(document,version,eventIndex,elapsed);
        server.tickRateManager().setFrozen(true);replayRequest=new ReplayRequest(source,version,selectedDocument,eventIndex,elapsed,reviewId);
        client.execute(()->disconnect(client,new Work("scenario_replay",version,source,server)));
    }
    /** The existing cursor protocol selects the first matching time: order the explicitly chosen immutable world first, retaining every point. */
    private static String selectReplayDocument(String document,String version,int eventIndex,long elapsed)throws IOException {
        if(eventIndex==0&&elapsed==0)return document;
        try{
            var root=com.google.gson.JsonParser.parseString(document).getAsJsonObject();
            var replay=root.getAsJsonObject("replay");var points=replay.getAsJsonArray("checkpoints");
            com.google.gson.JsonElement selectedPoint=null;
            for(var row:points){
                var point=row.getAsJsonObject();
                if(point.get("versionId").getAsString().equals(version)&&point.get("eventIndex").getAsInt()==eventIndex
                        &&point.get("elapsedTicks").getAsLong()==elapsed){
                    if(selectedPoint!=null){
                        var before=selectedPoint.getAsJsonObject().deepCopy();before.remove("label");
                        var after=point.deepCopy();after.remove("label");
                        if(!before.equals(after))throw new IOException("Одна версия содержит противоречащие курсоры сценария");
                    }else selectedPoint=row;
                }
            }
            if(selectedPoint==null)throw new IOException("Нет выбранной пары версии мира и курсора сценария");
            var ordered=new com.google.gson.JsonArray();ordered.add(selectedPoint);
            for(var row:points)if(row!=selectedPoint)ordered.add(row);
            replay.add("checkpoints",ordered);return JSON.toJson(root);
        }catch(RuntimeException failure){throw new IOException("Повреждён выбранный курсор сценария",failure);}
    }
    /** Captures the exact live event cursor before a genuine stopped-world full snapshot. */
    public static void scenarioCheckpoint(Minecraft client,String name,String label) {archiveAction(client,()->scenarioCheckpointScoped(client,name,label));}
    private static void scenarioCheckpointScoped(Minecraft client,String name,String label) {
        MinecraftServer server=scenarioServer(client,true);if(server==null)return;
        Path source=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        preparingTransfer=true;
        dispatch(server,()->{
            boolean wasPaused=server.tickRateManager().isFrozen();
            try{
                String document=com.dark.winter.testing.TestingScenarios.captureReplayCursor(server,name,label);
                var points=com.google.gson.JsonParser.parseString(document).getAsJsonObject().getAsJsonObject("replay").getAsJsonArray("checkpoints");
                var point=points.get(points.size()-1).getAsJsonObject();
                replayCheckpoint=new ReplayCheckpoint(source,document,label,point.get("eventIndex").getAsInt(),point.get("elapsedTicks").getAsLong(),wasPaused);
                server.tickRateManager().setFrozen(true);
                client.execute(()->disconnect(client,new Work("scenario_checkpoint",label,source,server)));
            }catch(Exception error){preparingTransfer=false;fail("Курсор сценария не сохранён; испытание открыто",error);}
        });
    }
    private static MinecraftServer scenarioServer(Minecraft client,boolean requireRunning) {
        if(busy()){status="Дождитесь текущей операции архива";return null;}
        MinecraftServer server=client.getSingleplayerServer();
        if(server==null||!TestingWorlds.isTesting()||TestingServer.busy(server)||previewActive) {
            status="Сценарий требует свободный одиночный тестовый мир";return null;
        }
        if(requireRunning&&!running){status="Контрольная точка сценария доступна внутри изолированного испытания";return null;}
        return server;
    }

    /** A complete checkpoint precedes a large editor mutation; requested mutation is replayed once. */
    public static void guardedAction(Minecraft client, String action, String value) {archiveAction(client,()->guardedActionScoped(client,action,value));}
    private static void guardedActionScoped(Minecraft client, String action, String value) {
        if (busy()) { status = "Дождитесь текущей операции архива"; return; }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !TestingWorlds.isTesting() || running || TestingServer.settings(server).mode.startsWith("TEST")) {
            status = "Изменение территории доступно только в редакторе"; return;
        }
        if (TestingServer.busy(server)) { status = "Дождитесь окончания операции редактора"; return; }
        if (!java.util.Set.of("biome", "vegetation", "brush", "move", "copy", "group_rotate", "resize", "prefab_paste", "scene_move", "scene_copy", "scene_rotate", "scene_remove").contains(action)) {
            status = "Эта операция не поддерживает автоматическую контрольную точку"; return;
        }
        Path source = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        guarded = new Guarded(action, value == null ? "" : value, source);
        disconnect(client, new Work("guard", "До " + action, source, server));
    }

    public static void guardedPrefab(Minecraft client, net.minecraft.core.BlockPos target, String id, int quarterTurns) {
        guardedAction(client, "prefab_paste", id + "|" + target.getX() + "," + target.getY() + "," + target.getZ() + "|" + quarterTurns);
    }
    public static void action(Minecraft client, String action, String name) {archiveAction(client,()->actionScoped(client,action,name));}
    private static void actionScoped(Minecraft client, String action, String name) {
        if(transferConfirmation!=null){
            if(action.equals("transfer_apply")||action.equals("transfer_cancel"))transferAction(client,action);
            else status="Сначала подтвердите или отмените показанный выборочный перенос";
            return;
        }
        if (busy()) { status = "Дождитесь окончания текущей операции"; return; }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !TestingWorlds.isTesting()) { status = "Доступно только в тестовом одиночном мире"; return; }
        Path current = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        boolean blocked=TestingServer.recoveryBlocked(server);
        if(TestingServer.busy(server)&&!blocked){status="Дождитесь окончания операции редактора";return;}
        if(blocked&&!java.util.Set.of("select","refresh","preview","restore","recover_copy","finish","preview_exit").contains(action)) {
            status="Восстановление остановлено: доступен выбор полной версии, восстановление или независимая аварийная копия";return;
        }
        if(previewActive&&!action.equals("preview_exit")){status="Предпросмотр доступен только для чтения; вернитесь в авторский редактор";return;}
        try {
            switch (action) {
                case "template_apply" -> { beginTemplateValidation(server,current,name); return; }
                case "template_terrain","template_surface","template_vegetation","template_buildings","template_objects" -> {
                    String categories=switch(action) {
                        case "template_terrain"->"terrain";case "template_surface"->"surface";
                        case "template_vegetation"->"grass,bushes,trees";case "template_buildings"->"building";default->"object";
                    };
                    beginTemplateValidation(server,current,(name==null?"":name)+"|"+categories);return;
                }
                case "select" -> {
                    TestingArchive.Version version = TestingArchive.read(name);
                    selected = version.id(); selections.put(current, selected);
                    status = version.name() + " | " + version.files().size() + " файлов | " + version.bytes() + " байт";
                    return;
                }
                case "refresh" -> { refreshAsync(current); return; }
                case "keep_settings" -> { if(!keepSettings&&reviewState!=null&&!reviewState.available().contains("settings")){status="Настройки недоступны: смотрите конфликты в обзоре";return;} keepSettings = !keepSettings; status = "Оставить настройки: " + keepSettings; return; }
                case "keep_objects" -> { if(!keepObjects&&reviewState!=null&&!reviewState.available().contains("object")){status="Объекты недоступны: смотрите конфликты в обзоре";return;} keepObjects = !keepObjects; status = "Оставить объекты: " + keepObjects; return; }
                case "keep_buildings" -> { if(!keepBuildings&&reviewState!=null&&!reviewState.available().contains("building")){status="Постройки недоступны: смотрите конфликты в обзоре";return;} keepBuildings = !keepBuildings; status = "Оставить постройки: " + keepBuildings; return; }
                case "pause" -> {
                    if (!running) { status = "Испытание не запущено"; return; }
                    final boolean requestedPause=!server.tickRateManager().isFrozen();
                    paused=requestedPause;if(!requestedPause)reviewState=null; dispatch(server,() -> {
                        for (var player : server.getPlayerList().getPlayers()) nativeAction(server, player.getUUID(), "mode", requestedPause ? "TEST_PAUSED" : "TEST_RUNNING");
                        server.tickRateManager().setFrozen(requestedPause);
                        status=requestedPause?"TEST_PAUSED":"TEST_RUNNING";
                    });
                    status = requestedPause ? "Пауза запрошена" : "Продолжение запрошено"; log("pause", Boolean.toString(requestedPause)); return;
                }
                case "protect_version" -> {
                    TestingArchive.Version version = chosen(current);
                    TestingArchive.protect(version.id(), !version.protectedVersion());
                    status = "Защита версии: " + !version.protectedVersion(); refreshAsync(current); return;
                }
                case "cleanup_history" -> {
                    if (!"cleanup".equals(confirmation)) {
                        confirmation = "cleanup"; status = "Повторите для удаления данных, не используемых ни одной версией"; return;
                    }
                    confirmation=null;preparingTransfer=true;status="Очистка только данных, не используемых версиями и Undo/Redo";
                    dispatch(server,()->{
                        try {
                            long documents=TestingServer.cleanDocumentHistory(server,true);
                            executeArchive(()->{
                                try {
                                    long freed=TestingArchive.collectUnused(true);refresh(current);
                                    status="Освобождено "+freed+" байт архива и "+documents+" байт документов; версии и история Undo/Redo сохранены";
                                }catch(Exception error){fail("Очистка архива не завершена",error);}
                                finally{preparingTransfer=false;}
                            });
                        }catch(Exception error){preparingTransfer=false;fail("Документы истории не очищены",error);}
                    });return;
                }
                case "delete_version" -> {
                    TestingArchive.Version version = chosen(current);
                    if (!("delete:" + version.id()).equals(confirmation)) {
                        confirmation = "delete:" + version.id();
                        status = "Подтвердите удаление повторным нажатием: " + version.name(); return;
                    }
                    TestingArchive.delete(version.id(), true); confirmation = null; selected = null;
                    selections.remove(current);
                    status = "Версия удалена; общие данные других версий сохранены"; refreshAsync(current); return;
                }
                case "open_preview" -> {
                    if(running||session!=null||previewSession!=null){status="Завершите текущую сессию или предпросмотр";return;}
                    TestingArchive.Version version=chosen(current);
                    if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(current)))throw new IOException("Версия относится к другому острову");
                    TestingTransientState.save(TestingArchive.root().resolve("preview-client-state.json"));
                    disconnect(client,new Work("open_preview",version.id(),current,server));return;
                }
                case "preview_exit" -> {
                    if(!previewActive||previewSession==null){status="Предпросмотр не открыт";return;}
                    disconnect(client,new Work("preview_exit","",current,server));return;
                }
                case "preview" -> {
                    if (running) { status = "Предпросмотр версии доступен после испытания"; return; }
                    TestingArchive.Version version = chosen(current);
                    disconnect(client, new Work("preview", version.id(), current, server)); return;
                }
                case "recover_copy" -> {
                    if(!blocked){status="Аварийная копия доступна только при остановленном восстановлении";return;}
                    TestingArchive.Version version=restorableVersion(current);
                    disconnect(client,new Work("recover_copy",version.id(),current,server));return;
                }
                case "restore" -> {
                    TestingArchive.Version version = restorableVersion(current);
                    if (!("restore:" + version.id()).equals(confirmation)) {
                        if (running) { status = "Восстановление доступно после испытания"; return; }
                        disconnect(client, new Work("preview_restore", version.id(), current, server)); return;
                    }
                    confirmation = null;
                }
                case "start" -> {
                    if (session != null) { status = "Сначала завершите существующее испытание"; return; }
                    keepObjects=false;keepBuildings=false;keepSettings=false;initializedTest=false;reviewState=null;
                    TestingTransientState.save(TestingArchive.root().resolve("session-client-state.json"));
                    String reviewId=UUID.randomUUID().toString();preparingTransfer=true;
                    dispatch(server,()->{
                        boolean wasFrozen=server.tickRateManager().isFrozen();
                        prepareSource(server,current,java.util.Set.of("building","object"),()->{
                            TestingSessionReview.baseline(server,reviewId);
                            client.execute(()->disconnect(client,new Work("start",reviewId,current,server)));
                        },error->{
                            try {
                                TestingSessionReview.baseline(server,reviewId);
                                TestingSessionReview.markBaselineUnavailable(reviewId,error.getMessage());
                                client.execute(()->disconnect(client,new Work("start",reviewId,current,server)));
                            }catch(Exception failure){server.tickRateManager().setFrozen(wasFrozen);preparingTransfer=false;fail("Не удалось подготовить исходный обзор; авторский мир открыт",failure);}
                        });
                    });return;
                }
                case "review" -> { beginReview(client,server,current);return; }
                case "finish" -> {
                    if (session == null || !running) { status = "Открыто не активное испытание"; return; }
                    if(blocked) {
                        keepObjects=false;keepBuildings=false;keepSettings=false;transferObjects=null;transferBuildings=null;
                        pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;
                        log("failed-test-full-rollback",current.getFileName().toString());
                        disconnect(client,new Work("finish","",current,server));return;
                    }
                    if(reviewState==null){beginReview(client,server,current);return;}
                    if(!keepObjects&&!keepBuildings&&!keepSettings){disconnect(client,new Work("finish","",current,server));return;}
                    preparingTransfer=true;
                    dispatch(server,() -> prepareSource(server,current,keepObjects?(keepBuildings?java.util.Set.of("building","object"):java.util.Set.of("object")):(keepBuildings?java.util.Set.of("building"):java.util.Set.of("settings")),()->{
                        try {
                            var previous=reviewState;var fresh=captureReview(server);reviewState=fresh;
                            if(previous==null||!selectedSignatureMatches(previous,fresh)) {
                                verifyAuthorReview(client,server,current,fresh,false);return;
                            }
                            if(keepObjects&&(!fresh.available().contains("object")||!previous.available().contains("object"))||keepBuildings&&(!fresh.available().contains("building")||!previous.available().contains("building"))||keepSettings&&(!fresh.available().contains("settings")||!previous.available().contains("settings")))
                                throw new IOException("Выбранная категория недоступна для безопасного сохранения; снимите её флажок");
                            paused=false;
                            importTarget=saves().resolve(session.author());
                            if (keepObjects) {
                                transferObjects = TestingArchive.root().resolve("transfers").resolve(session.copy() + "-objects.json");
                                TestingServer.exportCategory(server, "object", transferObjects);
                            }
                            if(keepSettings) {
                                Path settingsFile=TestingArchive.root().resolve("transfers").resolve(session.copy()+"-settings.json");
                                TestingServer.exportCategory(server,"settings",settingsFile);
                                pendingImports=java.util.Map.of("settings",settingsFile);
                            }
                            if (keepBuildings) {
                                transferBuildings = TestingArchive.root().resolve("transfers").resolve(session.copy() + "-buildings.json");
                                TestingServer.exportCategory(server, "building", transferBuildings);
                            }
                            verifyAuthorReview(client,server,current,fresh,true);
                        } catch (Exception error) { preparingTransfer=false;fail("Не удалось подготовить выборочный перенос; тест продолжается", error); }
                    },error->{preparingTransfer=false;fail("Полный источник выборочного сохранения недоступен; полный откат разрешён",error);}));
                    return;
                }
                case "checkpoint", "branch", "export", "import", "restore_settings", "restore_scenarios", "restore_objects", "restore_buildings",
                        "restore_terrain", "restore_surface", "restore_vegetation" -> { }
                default -> { status = "Неизвестная операция: " + action; return; }
            }
            if (running && !action.equals("finish")) { status = "Архивные операции доступны после завершения испытания"; return; }
            TestingArchive.checkedSave(current, true);
            String value = name == null ? "" : name;
            disconnect(client, new Work(action, value, current, server));
        } catch (Exception error) { fail("Операция отменена", error); }
    }
    private static boolean selectedSignatureMatches(TestingSessionReview.Review before,TestingSessionReview.Review after) {
        try {
            var left=com.google.gson.JsonParser.parseString(before.signature()).getAsJsonObject();
            var right=com.google.gson.JsonParser.parseString(after.signature()).getAsJsonObject();
            if(keepSettings&&!java.util.Objects.equals(left.get("settings"),right.get("settings")))return false;
            if(keepBuildings&&!java.util.Objects.equals(left.get("building"),right.get("building")))return false;
            return !keepObjects||java.util.Objects.equals(left.get("object"),right.get("object"))&&java.util.Objects.equals(left.get("npc"),right.get("npc"));
        }catch(RuntimeException unavailable){return false;}
    }
    private static TestingSessionReview.Review captureReview(MinecraftServer server) {
        try{return TestingSessionReview.capture(server,session.review());}
        catch(Exception error){return new TestingSessionReview.Review(List.of(
                "Подробный обзор недоступен: "+error.getMessage(),
                "Полный откат сохраняет авторский мир. Выборочное сохранение объектов недоступно."),
                "unavailable:"+error.getMessage(),java.util.Set.of());}
    }
    private static void beginReview(Minecraft client,MinecraftServer server,Path current) {
        if(session==null||!running){status="Открыто не активное испытание";return;}
        preparingTransfer=true;dispatch(server,()->{
            server.tickRateManager().setFrozen(true);paused=true;
            for(var player:server.getPlayerList().getPlayers())nativeAction(server,player.getUUID(),"mode","TEST_REVIEW");
            prepareSource(server,current,java.util.Set.of("building","object"),()->{
                TestingSessionReview.Review nativeReview=captureReview(server);
                log("review",String.join("; ",nativeReview.lines()));verifyAuthorReview(client,server,current,nativeReview,false);
            },error->{
                var captured=captureReview(server);var lines=new java.util.ArrayList<>(captured.lines());
                lines.add("Полный источник недоступен: "+error.getMessage()+". Разрешён полный откат.");
                verifyAuthorReview(client,server,current,new TestingSessionReview.Review(List.copyOf(lines),captured.signature(),java.util.Set.of()),false);
            });
        });
    }
    private static void disconnect(Minecraft client, Work request) {
        if (pending != null||onlineBefore!=null) return;
        OnlineHooks hooks=onlineHooks;
        if(hooks==null){disconnectPrepared(client,request);return;}
        Work selected=request;Extraction scratch=extraction;
        if(request.action().equals("extract_finish")&&scratch!=null&&request.server()==client.getSingleplayerServer()
                &&request.source().equals(saves().resolve(scratch.copy()))&&internalExtraction(request.server())&&hooks.failedInternalExtraction(request.server()))
            selected=new Work("extract_abort",scratch.author(),request.source(),request.server());
        final Work transition=selected;
        preparingTransfer=true;status="Подготовка участников перед "+transition.action();
        CompletableFuture<Void> gate;
        try{gate=java.util.Objects.requireNonNull(hooks.beforeTransition(transition.action(),transition.source()));}catch(Exception failure){preparingTransfer=false;fail("Сетевой переход не подготовлен; исходный мир открыт",failure);return;}
        onlineBefore=gate;gate.whenComplete((ignored,failure)->client.execute(()->{
            if(onlineBefore!=gate)return;onlineBefore=null;preparingTransfer=false;
            if(failure!=null){fail("Сетевой переход отменён; исходный мир открыт",failure);return;}
            if(onlineHooks!=hooks||client.getSingleplayerServer()!=transition.server()||!transition.server().isRunning()){fail("Исходный сервер перехода изменился",new IOException("Переход не выполнен"));return;}
            disconnectPrepared(client,transition);
        }));
    }
    private static void disconnectPrepared(Minecraft client,Work request){
        if(stoppedOpening!=null)throw new IllegalStateException("Stopped target preparation is still pending");
        workStart=new WorkStart(request,onlineHooks,TRANSITION_GENERATION.incrementAndGet(),TestingArchiveScope.capture().orElse(null));
        pending=request;preparingTransfer=false;onlineOpened=null;
        status="Сохранение и штатное закрытие мира перед "+request.action();
        com.dark.winter.client.TestingDrone.stop();client.disconnectWithSavingScreen();
    }

    private static String perform(Work request) throws IOException {
        Path source = TestingArchive.checkedSave(request.source(), true);
        String sourceName = source.getFileName().toString();
        switch (request.action()) {
            case "open_preview" -> {
                TestingArchive.Version version=TestingArchive.read(request.name());
                if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(source)))throw new IOException("Версия относится к другому авторскому миру");
                TestingArchive.capture(source,"Перед визуальным предпросмотром","automatic",selected);
                String copy="DW-Preview-"+UUID.randomUUID().toString().substring(0,12);Path target=folder(copy);
                TestingArchive.materialize(version,target,true);neutralizeExtraction(target);setMode(target,"TEST_REVIEW");
                previewSession=new Preview(1,sourceName,copy,version.id(),Instant.now().toString());
                Path previewState=TestingArchive.root().resolve("preview-states").resolve(copy+".json");
                Files.createDirectories(previewState.getParent());
                Files.copy(TestingArchive.root().resolve("preview-client-state.json"),previewState);
                TestingArchive.atomicWrite(previewFile(target),JSON.toJson(previewSession));
                TestingArchive.atomicWrite(TestingArchive.root().resolve("preview.json"),JSON.toJson(previewSession));
                log("preview-open",version.id());status="Визуальный предпросмотр сохранённой версии "+version.name();
                return copy;
            }
            case "preview_exit" -> {
                Preview ended=previewSession;
                if(ended==null||!sourceName.equals(ended.copy()))throw new IOException("Предпросмотр не совпадает с открытой копией");
                TestingArchive.checkedSave(folder(ended.author()),true);
                TestingArchive.atomicWrite(TestingArchive.root().resolve("preview-history").resolve(ended.copy()+".json"),JSON.toJson(ended));
                Files.deleteIfExists(TestingArchive.root().resolve("preview.json"));
                previewSession=null;previewActive=false;
                Path savedClientState=TestingArchive.root().resolve("preview-states").resolve(ended.copy()+".json");
                restoreClientFile=Files.isRegularFile(savedClientState)?savedClientState:TestingArchive.root().resolve("preview-client-state.json");
                restoreClientOnOpen=true;
                log("preview-exit",ended.author());status="Авторский редактор открыт; копия предпросмотра сохранена отдельно";
                return ended.author();
            }
            case "scenario_replay" -> {
                ReplayRequest replay=replayRequest;
                if(replay==null||!source.equals(replay.source()))throw new IOException("Запрос воспроизведения не совпадает с закрытым миром");
                Session previous=session;
                Path author=previous==null?source:folder(previous.author());
                TestingArchive.Version target=TestingArchive.read(replay.version());
                if(!TestingArchiveScope.sameSource(target.source(),TestingArchive.worldKey(author)))throw new IOException("Версия воспроизведения относится к другому миру");
                String baseline,created;
                if(previous==null) {
                    baseline=TestingArchive.capture(source,"Перед воспроизведением сценария","test-baseline",selected).id();
                    created=Instant.now().toString();keepObjects=false;keepBuildings=false;keepSettings=false;
                }else{
                    if(!sourceName.equals(previous.copy()))throw new IOException("Текущая копия не совпадает с сессией");
                    TestingArchive.Version result=TestingArchive.capture(source,"Перед переходом сценария","test-result",previous.baseline());
                    TestingArchive.adopt(result,author,"test-result");copyJournal(source,previous.copy());
                    baseline=previous.baseline();created=previous.created();
                }
                String copy="DW-Test-"+UUID.randomUUID().toString().substring(0,12);
                Path copyPath=folder(copy);TestingArchive.materialize(target,copyPath,false);setMode(copyPath,"TEST_RUNNING");
                session=new Session(2,author.getFileName().toString(),copy,baseline,created,replay.reviewId());
                replayCursor=new ReplayCursor(replay.document(),replay.eventIndex(),replay.elapsed(),false);replayTarget=copyPath;
                TestingArchive.atomicWrite(cursorFile(copyPath),JSON.toJson(replayCursor));
                TestingArchive.atomicWrite(manifest(),JSON.toJson(session));
                initializedTest=false;reviewState=null;restoreClientOnOpen=previous==null;replayRequest=null;
                log("scenario-replay",replay.version()+" events="+replay.eventIndex()+" elapsed="+replay.elapsed());
                status="Открывается изолированная версия мира с сохранённым курсором сценария";
                return copy;
            }
            case "scenario_checkpoint" -> {
                ReplayCheckpoint point=replayCheckpoint;
                if(point==null||!source.equals(point.source())||session==null||!sourceName.equals(session.copy()))
                    throw new IOException("Контрольная точка сценария не совпадает с сессией");
                TestingArchive.Version captured=TestingArchive.capture(source,point.label(),"scenario-checkpoint",session.baseline());
                Path author=folder(session.author());
                TestingArchive.Version version=TestingArchive.adopt(captured,author,"scenario-checkpoint");
                String document=selectReplayDocument(com.dark.winter.testing.TestingScenarios.bindReplayCheckpoint(point.document(),version.id()),
                        version.id(),point.eventIndex(),point.elapsed());
                mergeScenarioDocument(source,document);
                TestingArchive.atomicWrite(timelineFile(author,com.google.gson.JsonParser.parseString(document).getAsJsonObject().get("name").getAsString()),document);
                replayCursor=new ReplayCursor(document,point.eventIndex(),point.elapsed(),point.paused());replayTarget=source;
                TestingArchive.atomicWrite(cursorFile(source),JSON.toJson(replayCursor));
                initializedTest=false;reviewState=null;replayCheckpoint=null;selections.put(author,version.id());
                log("scenario-checkpoint",version.id());status="Мир и точный курсор сценария сохранены: "+point.label();
                return sourceName;
            }
            case "guard" -> {
                TestingArchive.Version automatic = TestingArchive.capture(source, request.name(), "automatic", selected);
                Guarded deferred = guarded;
                if (deferred != null && deferred.action().equals("resize") && source.equals(deferred.source())) {
                    writeResizePermit(source,automatic);
                }
                status = "Автоматическая точка " + automatic.name() + " сохранена; применение операции после загрузки";
                refresh(source); return sourceName;
            }
            case "preview", "preview_restore" -> {
                TestingArchive.Version target = TestingArchive.read(request.name());
                TestingArchive.Version currentVersion = TestingArchive.capture(source, "Перед предпросмотром: " + target.name(), "automatic", target.id());
                List<String> changes = TestingArchive.differences(currentVersion, target);
                String text = "Сравнение полного сохранения " + currentVersion.name() + " → " + target.name() + "\n"
                        + String.join("\n", changes);
                TestingArchive.atomicWrite(TestingArchive.root().resolve("previews").resolve(target.id() + ".txt"), text);
                status = "«" + target.name() + "»: " + changes.size() + " различий файлов сохранения. "
                        + (changes.isEmpty() ? "Содержимое совпадает." : String.join("; ", changes.subList(0, Math.min(3, changes.size()))))
                        + (request.action().equals("preview_restore") ? " Повторите Восстановить для подтверждения." : "");
                if (request.action().equals("preview_restore")) confirmation = "restore:" + target.id();
                refresh(source); return sourceName;
            }
            case "authority_checkpoint" -> {
                AuthorityRequest authority=authorityRequest;
                if(authority==null||authority.original!=request.server()||!authority.author.equals(source))throw new IOException("Authority checkpoint request changed");
                try {
                    if(!authority.original.isStopped()||authority.original.isRunning())throw new IOException("Author has not finished its native save");
                    if(!authorityCurrent(authority))throw new java.util.concurrent.CancellationException("Authority preparation cancelled");
                    TestingArchive.Version version=TestingArchive.capture(source,request.name(),"main",selected);
                    AuthorityCheckpoint checkpoint=new AuthorityCheckpoint(version,source,authority.player,authority.priorFrozen);authority.checkpoint=checkpoint;
                    prepareAuthority(authority,checkpoint);
                    status="Исходная версия Online подготовлена; открывается авторский мир";
                }catch(Exception failure){authority.failure=failure;status="Подготовка Online не завершена; открывается сохранённый авторский мир";}
                return sourceName;
            }
            case "checkpoint" -> {
                TestingArchive.Version version = TestingArchive.capture(source, request.name(), "main", selected);
                selected = version.id(); selections.put(source, selected); status = "Контрольная точка создана: " + version.name(); refresh(source);
                return sourceName;
            }
            case "start" -> {
                TestingArchive.Version baseline = TestingArchive.capture(source, "Перед испытанием", "test-baseline", selected);
                String copy = "DW-Test-" + UUID.randomUUID().toString().substring(0, 12);
                TestingArchive.materialize(baseline, saves().resolve(copy), false);
                setMode(saves().resolve(copy), "TEST_RUNNING");
                restoreClientOnOpen = true;
                session = new Session(2, sourceName, copy, baseline.id(), Instant.now().toString(),request.name());
                TestingArchive.atomicWrite(manifest(), JSON.toJson(session));
                log("start", sourceName + " -> " + copy);
                return copy;
            }
            case "finish" -> {
                Session ended = session;
                if (ended == null || !sourceName.equals(ended.copy())) throw new IOException("Сессия мира не совпадает");
                Path author = TestingArchive.checkedSave(folder(ended.author()), true);
                TestingArchive.Version result = TestingArchive.capture(source, "Результат испытания " + ended.created(), "test-result", ended.baseline());
                TestingArchive.adopt(result, author, "test-result");
                copyJournal(source, ended.copy());
                log("finish", "settings=" + keepSettings + ", objects=" + keepObjects + ", buildings=" + keepBuildings);
                Path history = TestingArchive.root().resolve("session-history").resolve(ended.copy() + ".json");
                TestingArchive.atomicWrite(history, JSON.toJson(ended));
                Files.deleteIfExists(manifest());
                session = null; running = false; paused = false; reviewState=null; restoreClientOnOpen = true;
                status = "Авторский мир восстановлен; копия испытания и журналы сохранены";
                return ended.author();
            }
            case "recover_copy" -> {
                TestingArchive.Version version=TestingArchive.read(request.name());
                if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(source)))throw new IOException("Резервная версия относится к другому миру");
                TestingArchive.capture(source,"Повреждённый мир перед аварийной копией","recovery-safety",version.id());
                String copy="DW-Recovered-"+UUID.randomUUID().toString().substring(0,12);Path target=folder(copy);
                TestingArchive.Version adopted=TestingArchive.copyIndependent(version,target,false,"recovered",version.name());
                neutralizeExtraction(target);
                selections.put(target,adopted.id());status="Открывается независимая копия полной версии; повреждённый исходный мир сохранён";
                return copy;
            }
            case "branch" -> {
                TestingArchive.Version version;
                if(selected!=null) {
                    version=TestingArchive.read(selected);
                    if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(source)))throw new IOException("Выбранная версия относится к другому острову");
                    TestingArchive.capture(source,"Перед ветвлением от "+version.name(),"automatic",version.id());
                }else version=TestingArchive.capture(source,request.name(),request.name(),null);
                selected = version.id();
                String copy = "DW-Branch-" + UUID.randomUUID().toString().substring(0, 12);
                TestingArchive.Version fork = TestingArchive.copyIndependent(version, folder(copy), true,
                        request.name().isBlank() ? "branch" : request.name(), request.name().isBlank() ? version.name() : request.name());
                selections.put(folder(copy), fork.id());
                status = "Создана независимая ветка: " + copy; refresh(source);
                return copy;
            }
            case "restore" -> {
                TestingArchive.Version version = chosen(source);
                TestingArchive.capture(source, "Перед восстановлением", "automatic", selected);
                Path recovery = TestingArchive.restore(version, source);
                setMode(source, "EDIT");
                status = "Версия восстановлена; предыдущий мир: " + recovery.getFileName(); refresh(source);
                return sourceName;
            }
            case "export" -> {
                TestingArchive.Version version = TestingArchive.capture(source,
                        request.name().isBlank() ? "Остров для экспорта" : request.name(), "library", selected);
                selected = version.id(); selections.put(source, selected);
                Path output = TestingArchive.exportVersion(version.id());
                status = "Экспорт: " + output.getFileName(); refresh(source);
                return sourceName;
            }
            case "import" -> {
                TestingArchive.Version version = TestingArchive.importVersion(request.name());
                String copy = "DW-Island-" + UUID.randomUUID().toString().substring(0, 12);
                TestingArchive.Version adopted = TestingArchive.copyIndependent(version, folder(copy), true, "import", version.name());
                selections.put(folder(copy), adopted.id());
                selected = adopted.id(); status = "Импортирована независимая копия: " + copy;
                return copy;
            }
            case "restore_settings", "restore_scenarios", "restore_objects", "restore_buildings", "restore_terrain", "restore_surface", "restore_vegetation" -> {
                TestingArchive.Version version = chosen(source);
                TestingArchive.Version safety=TestingArchive.capture(source, "Перед выборочным восстановлением", "automatic", selected);
                if(request.action().equals("restore_terrain"))writeResizePermit(source,safety);
                String copy = "DW-Extract-" + UUID.randomUUID().toString().substring(0, 12);
                TestingArchive.materialize(version, folder(copy), false);
                com.dark.winter.testing.TestingScenarioDependencies.markSource(folder(copy),version.id());
                List<String> categories = switch (request.action()) {
                    case "restore_settings" -> List.of("settings"); case "restore_scenarios" -> List.of("scenarios");
                    case "restore_objects" -> List.of("object"); case "restore_buildings" -> List.of("building");
                    case "restore_terrain" -> List.of("terrain"); case "restore_surface" -> List.of("surface");
                    default -> List.of("grass", "bushes", "trees");
                };
                preserveExtractionSettings(folder(copy),categories);
                neutralizeExtraction(folder(copy));
                extraction = new Extraction(sourceName, copy, categories); extracting = false;
                TestingArchive.atomicWrite(TestingArchive.root().resolve("extraction.json"), JSON.toJson(extraction));
                return copy;
            }
            case "template_apply" -> {
                String[] data=request.name().split("\\|",-1);
                if(data.length!=2)throw new IOException("Некорректная операция шаблона");
                TestingArchive.Version version=TestingArchive.read(data[0]);
                List<String> categories=categories(data[1]);
                TestingArchive.Version safety=TestingArchive.capture(source,"Перед применением шаблона "+version.name(),"automatic",selected);
                if(categories.contains("terrain"))writeResizePermit(source,safety);
                String copy="DW-Template-"+UUID.randomUUID().toString().substring(0,12);
                TestingArchive.materialize(version,folder(copy),false);
                com.dark.winter.testing.TestingScenarioDependencies.markSource(folder(copy),version.id());
                preserveExtractionSettings(folder(copy),categories);
                neutralizeExtraction(folder(copy));
                extraction=new Extraction(sourceName,copy,categories);extracting=false;
                TestingArchive.atomicWrite(TestingArchive.root().resolve("extraction.json"),JSON.toJson(extraction));
                status="Шаблон проверен; извлечение категорий "+categories+" из независимой копии";
                return copy;
            }
            case "extract_abort" -> {
                // Returning from a failed private source must never replay an earlier editor/import continuation.
                guarded=null;transferObjects=null;transferBuildings=null;
                extraction=null;extracting=false;pendingImports=java.util.Map.of();pendingTransferReview=false;importTarget=null;
                Files.deleteIfExists(TestingArchive.root().resolve("extraction.json"));
                TestingArchive.checkedSave(folder(request.name()),true);
                status="Извлечение отменено; авторский остров открыт без применения категорий";
                return request.name();
            }
            case "extract_finish" -> {
                Extraction done = extraction;
                if (done == null || !sourceName.equals(done.copy())) throw new IOException("Извлечение не совпадает");
                extraction = null; extracting = false;
                Files.deleteIfExists(TestingArchive.root().resolve("extraction.json"));
                return done.author();
            }
            default -> throw new IOException("Недопустимая операция архива");
        }
    }
    private static TestingArchive.Version restorableVersion(Path source) throws IOException {
        TestingArchive.Version version=chosen(source);
        if(!TestingArchiveScope.sameSource(version.source(),TestingArchive.worldKey(source)))throw new IOException("Полная версия относится к другому миру");
        if(version.files().stream().anyMatch(file->java.util.Set.of("data/darkwinter-testing/pending-edit.json","data/darkwinter-testing/resize-pending.json").contains(file.path())))
            throw new IOException("В этой версии есть незавершённый журнал; выберите завершённую контрольную точку");
        return version;
    }
    private static void beginExtraction(Minecraft client, MinecraftServer server) {
        extracting = true;
        Extraction request = extraction;
        dispatch(server,() -> prepareSource(server,saves().resolve(request.copy()),java.util.Set.copyOf(request.categories()),()->{
            try {
                var transfers = new java.util.LinkedHashMap<String,Path>();
                for (String category : request.categories()) {
                    Path file = TestingArchive.root().resolve("transfers").resolve(request.copy() + "-" + category + ".json");
                    if(category.equals("settings")) {
                        if(!Files.isRegularFile(file))throw new IOException("Исходный профиль извлечения отсутствует");
                    }else TestingServer.exportCategory(server, category, file);
                    transfers.put(category, file);
                }
                pendingImports = java.util.Map.copyOf(transfers);importTarget=saves().resolve(request.author());pendingTransferReview=true;
                client.execute(() -> disconnect(client, new Work("extract_finish", "", saves().resolve(request.copy()), server)));
            } catch (Exception error) {
                fail("Извлечение отменено; авторский мир сохранён в "+request.author(),error);
                pendingImports=java.util.Map.of();pendingTransferReview=false;
                client.execute(()->disconnect(client,new Work("extract_abort",request.author(),saves().resolve(request.copy()),server)));
            }
        },error->{
            pendingImports=java.util.Map.of();pendingTransferReview=false;fail("Источник шаблона недоступен; авторский мир сохранён",error);
            client.execute(()->disconnect(client,new Work("extract_abort",request.author(),saves().resolve(request.copy()),server)));
        }));
    }
    private static void beginTemplateValidation(MinecraftServer server,Path source,String value) throws IOException {
        if(running || TestingServer.settings(server).mode.startsWith("TEST"))
            throw new IOException("Шаблон применяется только в авторском редакторе");
        TestingArchive.checkedSave(source,true);
        String[] data=(value==null?"":value).split("\\|",-1);
        if(data.length!=2)throw new IOException("Выберите файл .dwisland и хотя бы одну категорию");
        String filename=data[0].strip();
        if(!filename.matches("[a-zA-Z0-9_-]{1,128}\\.dwisland"))throw new IOException("Укажите имя .dwisland из библиотеки exports");
        TemplateRequest request=new TemplateRequest(filename,categories(data[1]),source,server);
        templateRequest=request;status="Проверка всех файлов и SHA-256 шаблона; авторский мир остаётся открыт";
        templateValidation=CompletableFuture.supplyAsync(archiveSupplier(()->{
            TestingArchive.Version imported=null;
            try{
                imported=TestingArchive.importVersion(request.filename());validateTemplateMetadata(imported);return imported;
            }catch(Exception error){
                if(imported!=null)try{TestingArchive.delete(imported.id(),true);}catch(IOException cleanup){error.addSuppressed(cleanup);}
                throw new java.util.concurrent.CompletionException(error);
            }
        }),IO);
    }
    private static Path templateFile(TestingArchive.Version version,String name,long maxBytes) throws IOException {
        var entry=version.files().stream().filter(file->file.path().equals(name)).findFirst()
                .orElseThrow(()->new IOException("В шаблоне отсутствует "+name));
        if(entry.size()>maxBytes)throw new IOException("Файл шаблона слишком велик: "+name);
        Path blob=TestingArchive.root().resolve("objects").resolve(entry.sha256().substring(0,2)).resolve(entry.sha256());
        if(!Files.isRegularFile(blob)||Files.size(blob)!=entry.size()||!TestingArchive.hash(blob).equals(entry.sha256()))
            throw new IOException("SHA-256 файла шаблона не совпадает: "+name);
        return blob;
    }
    private static void validateTemplateMetadata(TestingArchive.Version version) throws IOException {
        var marker=new java.util.Properties();
        try(var input=Files.newInputStream(templateFile(version,"data/darkwinter-testing.properties",65536))){marker.load(input);}
        if(!"testing".equals(marker.getProperty("mode"))||!java.util.Set.of("1","2").contains(marker.getProperty("schema","")))
            throw new IOException("Шаблон не является поддерживаемым тестовым островом");
        try{TestingWorlds.Template.valueOf(marker.getProperty("template","NORMAL"));}
        catch(IllegalArgumentException error){throw new IOException("Неизвестный тип шаблона",error);}
        Path level=templateFile(version,"level.dat",64L*1024*1024);
        var root=net.minecraft.nbt.NbtIo.readCompressed(level,net.minecraft.nbt.NbtAccounter.create(16L*1024*1024));
        var data=root.getCompound("Data").orElseThrow(()->new IOException("В level.dat отсутствуют данные мира"));
        if(data.getIntOr("DataVersion",-1)!=net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version())
            throw new IOException("Версия данных шаблона не совпадает с текущей Minecraft 26.3");
        if(version.files().stream().anyMatch(file->file.path().equals("data/darkwinter-testing/settings.json"))){
            Path settings=templateFile(version,"data/darkwinter-testing/settings.json",1024*1024);
            try{
                var parsed=JSON.fromJson(Files.readString(settings),com.dark.winter.testing.TestingSettings.class);
                if(parsed==null)throw new IOException("Пустые настройки шаблона");
                parsed.validate();
            }catch(RuntimeException error){throw new IOException("Неподдерживаемые настройки шаблона",error);}
        }
    }
    private static List<String> categories(String value) throws IOException {
        var categories=new java.util.LinkedHashSet<String>();
        for(String raw:value.split(",")){
            String name=raw.strip();
            if(!java.util.Set.of("terrain","surface","grass","bushes","trees","building","object","settings","scenarios").contains(name))
                throw new IOException("Неизвестная категория шаблона: "+name);
            categories.add(name);
        }
        if(categories.isEmpty())throw new IOException("Выберите хотя бы одну категорию");
        return List.copyOf(categories);
    }
    private static void pollTemplateValidation(Minecraft client,MinecraftServer server,Path current) {
        var future=templateValidation;
        if(future==null || !future.isDone())return;
        TemplateRequest request=templateRequest;
        if(request!=null && server==request.server() && current!=null && current.equals(request.source())
                && TestingServer.busy(server)){
            status="Архив проверяется/проверен; ожидается завершение текущей операции острова";return;
        }
        templateValidation=null;templateRequest=null;
        try{
            TestingArchive.Version version=future.join();
            if(request==null || server!=request.server() || current==null || !current.equals(request.source())
                    || !TestingWorlds.isTesting() || running || TestingServer.settings(server).mode.startsWith("TEST")){
                status="Проверка шаблона завершена; применение отменено, потому что авторский мир изменился";return;
            }
            disconnect(client,new Work("template_apply",version.id()+"|"+String.join(",",request.categories()),current,server));
        }catch(Exception error){fail("Шаблон отклонён до закрытия и изменения авторского мира",error);}
    }
    private static void writeResizePermit(Path source,TestingArchive.Version checkpoint) throws IOException {
        var permit=new com.google.gson.JsonObject();
        permit.addProperty("schema",2);permit.addProperty("version",checkpoint.id());
        permit.addProperty("source",source.toAbsolutePath().normalize().toString());
        permit.addProperty("created",Instant.now().toString());
        TestingArchive.atomicWrite(source.resolve("data/darkwinter-testing/resize-permit.json"),JSON.toJson(permit));
    }
    private static Path previewFile(Path world) { return world.resolve("data/darkwinter-testing/preview-source.json"); }
    private static Path cursorFile(Path world) { return world.resolve("data/darkwinter-testing/replay-cursor.json"); }
    private static void mergeScenarioDocument(Path world,String document) throws IOException {
        var scenario=com.google.gson.JsonParser.parseString(document).getAsJsonObject();
        String name=com.dark.winter.testing.TestingServer.safeName(scenario.get("name").getAsString());
        Path data=world.resolve("data/darkwinter-testing"),aggregate=data.resolve("scenario-documents.json");
        if(Files.isRegularFile(aggregate)) {
            if(Files.size(aggregate)>8L*1024*1024)throw new IOException("Определения сценариев слишком велики");
            var root=com.google.gson.JsonParser.parseString(Files.readString(aggregate)).getAsJsonObject();
            if(root.get("schema").getAsInt()!=1||!root.has("devices")||!root.has("scenarios"))throw new IOException("Неподдерживаемый набор определений сценариев");
            var merged=new com.google.gson.JsonArray();boolean replaced=false;
            for(var element:root.getAsJsonArray("scenarios")) {
                if(element.getAsJsonObject().get("name").getAsString().equals(name)){merged.add(scenario);replaced=true;}
                else merged.add(element);
            }
            if(!replaced)merged.add(scenario);
            if(merged.size()>128)throw new IOException("Лимит: 128 сценариев");
            root.add("scenarios",merged);TestingArchive.atomicWrite(aggregate,JSON.toJson(root));
        }
        TestingArchive.atomicWrite(data.resolve("scenarios").resolve(name+".json"),JSON.toJson(scenario));
    }
    private static void preserveExtractionSettings(Path world,List<String> categories) throws IOException {
        if(!categories.contains("settings"))return;
        Path source=world.resolve("data/darkwinter-testing/settings.json");
        if(!Files.isRegularFile(source)||Files.size(source)>1024*1024)throw new IOException("В версии отсутствует допустимый профиль v2");
        var settings=com.google.gson.JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        JSON.fromJson(settings,com.dark.winter.testing.TestingSettings.class).validate();
        settings.remove("mode");settings.remove("biome");
        var transfer=new com.google.gson.JsonObject();transfer.addProperty("schema",2);transfer.addProperty("name","category:settings");
        transfer.addProperty("id",UUID.randomUUID().toString());transfer.addProperty("category","settings");
        transfer.add("changes",new com.google.gson.JsonArray());transfer.add("entities",new com.google.gson.JsonArray());transfer.add("document",settings);
        Path file=TestingArchive.root().resolve("transfers").resolve(world.getFileName()+"-settings.json");
        TestingArchive.atomicWrite(file,JSON.toJson(transfer));
    }
    private static void neutralizeExtraction(Path world) throws IOException {
        Path file=world.resolve("data/darkwinter-testing/settings.json");
        if(Files.isRegularFile(file)&&Files.size(file)>1024*1024)throw new IOException("Настройки шаблона слишком велики");
        var settings=Files.isRegularFile(file)?com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject():
                JSON.toJsonTree(new com.dark.winter.testing.TestingSettings()).getAsJsonObject();
        var modules=new com.google.gson.JsonObject();
        for(String module:com.dark.winter.testing.TestingSettings.MODULES)modules.addProperty(module,false);
        settings.add("modules",modules);settings.addProperty("mode","EDIT");settings.addProperty("diagnostics",false);
        TestingArchive.atomicWrite(file,JSON.toJson(settings));
    }
    private static void setMode(Path world, String mode) throws IOException {
        Path file = world.resolve("data/darkwinter-testing/settings.json");
        com.google.gson.JsonObject settings = Files.isRegularFile(file)
                ? com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                : JSON.toJsonTree(new com.dark.winter.testing.TestingSettings()).getAsJsonObject();
        settings.addProperty("mode", mode);
        TestingArchive.atomicWrite(file, JSON.toJson(settings));
    }
    private static void copyJournal(Path source, String sessionName) throws IOException {
        Path data = source.resolve("data/darkwinter-testing");
        if (!Files.isDirectory(data)) return;
        Path target = TestingArchive.root().resolve("session-journals").resolve(sessionName);
        Files.createDirectories(target);
        try (var files = Files.list(data)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")
                    || p.getFileName().toString().endsWith(".csv") || p.getFileName().toString().contains("journal")).toList()) {
                if (Files.isRegularFile(file) && Files.size(file) <= 64L * 1024 * 1024)
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path reports=data.resolve("reports"),reportTarget=target.resolve("reports");
        var copied=new com.google.gson.JsonArray();var omitted=new com.google.gson.JsonArray();
        if(Files.exists(reports)) {
            if(Files.isSymbolicLink(reports)||!Files.isDirectory(reports,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Недопустимый каталог серверных отчётов");
            List<Path> paths;
            try(var walk=Files.walk(reports,8)){paths=walk.limit(4097).toList();}
            if(paths.size()>4096) {
                var entry=new com.google.gson.JsonObject();entry.addProperty("path","reports/");entry.addProperty("reason","Индекс ограничен 4096 путями; полный каталог сохранён в копии испытания и архиве");omitted.add(entry);
            }
            int count=0;
            for(Path file:paths) {
                if(Files.isSymbolicLink(file))throw new IOException("Ссылка в каталоге серверных отчётов");
                if(!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!file.getFileName().toString().endsWith(".json"))continue;
                String relative=reports.relativize(file).toString().replace('\\','/');
                Path destination=reportTarget.resolve(relative).normalize();
                if(!destination.startsWith(reportTarget)||relative.contains(".."))throw new IOException("Путь отчёта вышел за пределы каталога");
                long size=Files.size(file);
                if(++count>512||size>1024*1024) {
                    var entry=new com.google.gson.JsonObject();entry.addProperty("path","reports/"+relative);
                    entry.addProperty("reason",count>512?"Индекс ограничен 512 файлами":"Отчёт превышает 1 МиБ");
                    entry.addProperty("preservedInSave",source.getFileName().toString());omitted.add(entry);continue;
                }
                String hash=TestingArchive.hash(file);Files.createDirectories(destination.getParent());
                Path temporary=Files.createTempFile(destination.getParent(),"server-report-",".tmp");
                try {
                    Files.copy(file,temporary,StandardCopyOption.REPLACE_EXISTING);
                    if(!TestingArchive.hash(temporary).equals(hash))throw new IOException("SHA-256 серверного отчёта изменился");
                    try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temporary);}
                var entry=new com.google.gson.JsonObject();entry.addProperty("path","reports/"+relative);entry.addProperty("bytes",size);entry.addProperty("sha256",hash);copied.add(entry);
            }
        }
        var index=new com.google.gson.JsonObject();index.addProperty("schema",1);index.addProperty("utc",Instant.now().toString());
        index.addProperty("sourceSave",source.getFileName().toString());index.add("copied",copied);index.add("omitted",omitted);
        TestingArchive.atomicWrite(target.resolve("server-reports-index.json"),JSON.toJson(index));
    }
    private static TestingArchive.Version chosen(Path source) throws IOException {
        if (selected != null) return TestingArchive.read(selected);
        List<TestingArchive.Version> all = TestingArchive.versions(source);
        if (all.isEmpty()) throw new IOException("Сначала создайте контрольную точку");
        selected = all.getFirst().id(); selections.put(source, selected); return all.getFirst();
    }
    private static void refreshAsync(Path source) {
        executeArchive(()->{try{refresh(source);}catch(IOException error){fail("История недоступна",error);}});
    }
    private static void refresh(Path source) throws IOException {
        long stored=TestingArchive.storedBytes();
        Path archive=TestingArchive.root();
        Path existing=archive;
        while(existing!=null&&!Files.isDirectory(existing,java.nio.file.LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(existing==null)throw new IOException("Нет существующего тома для оценки свободного места");
        long available=Files.getFileStore(existing).getUsableSpace();
        historyStorageStatus=String.format(java.util.Locale.ROOT,"Архив: %.1f МиБ · свободно: %.1f ГиБ",stored/(1024.0*1024),available/(1024.0*1024*1024));
        versions = TestingArchive.versions(source).stream().map(v -> v.id() + " | " + v.name()
                + " | " + v.bytes() + " B" + (v.protectedVersion() ? " | защищена" : "")).toList();
    }
    private static Path folder(String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.equals(".") || name.equals("..")) throw new IOException("Недопустимое имя сохранения");
        return TestingArchive.checkedSave(saves().resolve(name), false);
    }
    private static void log(String action, String value) throws IOException {
        Path path = TestingArchive.root().resolve("session-events.jsonl");
        Files.createDirectories(path.getParent());
        var event = new com.google.gson.JsonObject();
        event.addProperty("time", Instant.now().toString()); event.addProperty("action", action); event.addProperty("value", value);
        Files.writeString(path, JSON.toJson(event) + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    private static void fail(String message, Throwable error) {
        status = message + ": " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
        LOG.error(message, error);
        OnlineHooks hooks=onlineHooks;if(hooks!=null)try{hooks.failed(status);}catch(RuntimeException ignored){}
    }
}
