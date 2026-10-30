// Coordinates verified host leases with stopped-save checkpoints and native startup.
package com.dark.winter.client.online;

import com.dark.winter.client.TestingSessions;
import com.dark.winter.online.authority.*;
import com.dark.winter.online.activation.PromotionWorkspace;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.replica.*;
import com.dark.winter.online.world.*;
import com.dark.winter.testing.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Application-owned node lease. Archive reads and node requests run off the render/server thread.
 * A native listener may use only permit(), after the genuinely stopped snapshot has been confirmed.
 * Closing never releases a lease while an old simulation might still be alive: it expires at the node.
 */
final class OnlineAuthoritySession implements AutoCloseable {
    private final OnlineFriendsRuntime runtime;
    private final IdentityStore identity;
    private final long runtimeGeneration;
    private final String actor,node;
    private final UUID localGameId;
    private final Path nativeSaves;
    private final JsonObject definitions;
    private final Consumer<String> failed;
    private final Object network=new Object(),permitLock=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),notified=new AtomicBoolean();
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
        task->{Thread t=new Thread(task,"DarkWinter-authority-snapshot");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService renewals=Executors.newSingleThreadScheduledExecutor(task->{
        Thread t=new Thread(task,"DarkWinter-authority-renewal");t.setDaemon(true);return t;});
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final Map<String,UUID> aliases=new HashMap<>();
    private volatile OnlineHostPermit permit;
    private volatile AuthorityServiceClient service;
    private volatile ReplicaPublisher.Prepared publisher;
    private volatile IslandAuthorityFiles.Document document;
    private AuthorityPins pins;
    private AuthorityHostJournal journal;
    private volatile Path currentWorld;
    private volatile Path permanentAuthor;
    private volatile String currentVersion;
    private volatile TestingArchiveNamespaces.Binding archiveBinding;
    private volatile PromotionWorkspace.Ticket promotionTicket;
    private PromotionWorkspace.Ticket openingTicket;

    OnlineAuthoritySession(OnlineFriendsRuntime runtime,UUID actualLocalGameId,JsonObject definitions,Path actualNativeSaves,Consumer<String> failed)throws IOException {
        this.runtime=Objects.requireNonNull(runtime);identity=runtime.identity();runtimeGeneration=runtime.generation();
        actor=identity.profile().id();node=runtime.verifiedAuthorityNode().id();localGameId=Objects.requireNonNull(actualLocalGameId);
        nativeSaves=Objects.requireNonNull(actualNativeSaves).toAbsolutePath().normalize();
        this.definitions=Objects.requireNonNull(definitions).deepCopy();this.failed=Objects.requireNonNull(failed);
        aliases.put(actor,localGameId);
        renewals.scheduleWithFixedDelay(this::renew,1,1,TimeUnit.SECONDS);
    }
    OnlineHostPermit permit()throws IOException {synchronized(permitLock){guard();OnlineHostPermit value=permit;if(value==null)throw new IOException("Полномочия ведущего ещё не подтверждены");value.requireUsableFor(actor,value.nativeSessionId());return value;}}
    ReplicaPublisher.Prepared publisher()throws IOException {permit();ReplicaPublisher.Prepared value=publisher;if(value==null)throw new IOException("Нет подтверждённого снимка");return value;}
    IslandAuthorityFiles.Document document()throws IOException {permit();return document;}
    NativeActorAliases nativeAliases()throws IOException {
        OnlineHostPermit current=permit();ReplicaPublisher.Prepared snapshot=publisher();
        return NativeActorAliases.bind(current,snapshot.index(),actor,localGameId);
    }

    Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{
        guard();TestingArchiveNamespaces.Binding binding=archiveBinding;
        if(binding==null){TestingArchiveScope.requireWorld(actualWorld);return Optional.empty();}
        return Optional.of(binding.token(actualWorld).call(()->TestingArchiveScope.capture().orElseThrow(()->new IOException("Контекст архива не установлен"))));
    }
    private void installArchive(TestingReplicationArchive.Restored restored,Path world)throws IOException{
        OnlineHostPermit current=permit();TestingArchiveNamespaces.Binding installed=TestingArchiveNamespaces.install(identity.base(),restored,world,document.certificate(),current.fence());
        synchronized(permitLock){
            if(closed.get()||permit!=current||archiveBinding!=null){installed.close();throw new IOException("Подключение архива отменено");}
            archiveBinding=installed;
        }
    }

    Optional<PromotionWorkspace.Ticket> takeOpeningTicket(Path target)throws IOException{
        synchronized(permitLock){guard();PromotionWorkspace.Ticket ticket=openingTicket;
            if(ticket==null)return Optional.empty();
            if(!ticket.world().equals(target.toAbsolutePath().normalize()))throw new IOException("Открытие относится к другому острову");
            openingTicket=null;return Optional.of(ticket);
        }
    }

    CompletableFuture<Void> bootstrap(TestingSessions.AuthorityCheckpoint checkpoint) {
        return submit(()->{
            guard();Path world=checkpoint.authorPath().toAbsolutePath().normalize();
            if(!checkpoint.localPlayerId().equals(localGameId))throw new IOException("Локальная личность Minecraft изменилась");
            IslandAccess.View access;
            try(IslandAccess store=IslandAccess.open(world,identity.profile())){access=store.snapshot();}
            if(!access.ownerId().equals(actor))throw new IOException("Первое открытие выполняет постоянный владелец острова");
            ReplicaPublisher.Prepared prepared=prepare(checkpoint.version(),Map.of(actor,localGameId));
            synchronized(network){
                guard();
                document=IslandAuthorityFiles.load(world,access).orElse(null);
                boolean newRoot=document==null;
                if(newRoot)document=IslandAuthorityFiles.create(world,access,identity,prepared.index().contentRoot(),node,Instant.now());
                WorldAuthority.Genesis genesis=WorldAuthority.verify(document.certificate());
                if(!node.equals(genesis.coordinatorId()))throw new IOException("Для острова закреплён другой служебный узел");
                pins=AuthorityPins.open(identity.base());
                var known=pins.get(genesis.islandId());
                service=new AuthorityServiceClient(document.certificate(),()->identity,runtime::authorityRequest,Clock.systemUTC(),System::nanoTime);
                if(known.isPresent()){
                    AuthorityPins.Pin pin=known.get();
                    WorldAuthority.requirePinned(document.certificate(),pin.authorityId(),pin.ownerId());
                    service.requireMinimum(pin.minEpoch(),pin.minPolicy());
                }
                JsonObject status;
                if(newRoot||known.isEmpty()){
                    try{await(service.enroll(document.policy()));}
                    catch(IOException uncertain){guard();}
                    status=await(service.status());
                }else status=await(service.status());
                WorldAuthority.Policy policy=reconcileOwnerPolicy(status,world,access);guard();
                var plan=OwnerPolicyFromAccess.plan(document.certificate(),policy,access,actor,Instant.now());
                if(plan.changed()){
                    var signed=WorldAuthority.policy(identity,document.certificate(),plan.revision(),plan.grants(),Instant.now());
                    JsonObject confirmed=await(service.policy(signed));
                    WorldAuthority.Policy expected=WorldAuthority.verifyPolicy(document.certificate(),signed);
                    WorldAuthority.Policy actual=WorldAuthority.verifyPolicy(document.certificate(),WorldAuthority.signedFromJson(confirmed.getAsJsonObject("policy")));
                    if(actual.revision()!=expected.revision()||!actual.sha256().equals(expected.sha256()))throw new IOException("Доверие изменилось на другом устройстве");
                    document=IslandAuthorityFiles.updatePolicy(world,access,signed);
                    status=await(service.status());policy=verifiedPolicy(status);
                }
                if(!newRoot){
                    ReplicaProtocol.Index previous=readCheckpointIndex(ProfileFiles.string(status,"checkpointSha256",64));
                    mergeAliases(previous.actorAliases());
                    Map<String,UUID> knownAliases;synchronized(aliases){knownAliases=Map.copyOf(aliases);}
                    prepared=prepare(checkpoint.version(),knownAliases);
                }
                persistCheckpointIndex(prepared.index());
                pins.pinVerified(document.certificate(),node,number(status,"epoch"),policy.revision());
                journal=AuthorityHostJournal.open(identity.base(),document.certificate());
                journal.compareNodeStatus(status,world);
                permanentAuthor=world;currentWorld=world;currentVersion=checkpoint.version().id();
                claim(status,world,currentVersion);
                // The genesis root precedes its own certificate. Publish a bound schema2 companion
                // only after the real Node has confirmed that certificate and exact initial snapshot.
                var bound=TestingReplicationArchive.sameIsland(world,world,world,document.certificate(),permit().fence());
                var boundArchive=TestingReplicationArchive.prepare(checkpoint.version(),nextSnapshotDirectory(),TestingReplicationArchive.Limits.defaults(),bound);
                Map<String,UUID> completeAliases;synchronized(aliases){completeAliases=Map.copyOf(aliases);}
                prepared=ReplicaPublisher.prepare(boundArchive.sources(),completeAliases,ReplicaProtocol.Limits.defaults(),ReplicaProtocol.Compatibility.fromManifest(definitions));
                if(!prepared.index().contentRoot().equals(permit().checkpointSha256()))advance(prepared,world,currentVersion);
                IslandAuthorityFiles.installVerified(world,access,document,permit().fence());
                publisher=prepared;guard();
            }
        });
    }

    /**
     * Candidate activation begins from an already verified, proactively staged local checkpoint.
     * This method does not open Minecraft, change its account, or release a live previous server.
     */
    CompletableFuture<PromotionWorkspace.Ticket> promote(ReplicaStore.Ready ready,PromotionWorkspace.Staged staged){
        CompletableFuture<Void> prepared=submit(()->{
            guard();if(permit!=null||document!=null||!staged.checkpointSha256().equals(ready.index().contentRoot())
                    ||staged.checkpointSequence()!=ready.header().sequence())throw new IOException("Резервная копия или власть кандидата изменились");
            Path world=TestingArchive.checkedSave(staged.world(),true);
            WorldAuthority.Certificate certificate=ready.certificate();var genesis=WorldAuthority.verify(certificate);
            if(!node.equals(genesis.coordinatorId()))throw new IOException("Другой служебный узел резервной копии");
            pins=AuthorityPins.open(identity.base());AuthorityPins.Pin pin=pins.get(genesis.islandId()).orElseThrow(()->new IOException("Нет независимой внешней привязки острова"));
            WorldAuthority.requirePinned(certificate,pin.authorityId(),pin.ownerId());
            if(!node.equals(pin.nodeId()))throw new IOException("Другой закреплённый узел резервной копии");
            service=new AuthorityServiceClient(certificate,()->identity,runtime::authorityRequest,Clock.systemUTC(),System::nanoTime);
            service.requireMinimum(pin.minEpoch(),pin.minPolicy());
            JsonObject status=await(service.status());
            WorldAuthority.Policy currentPolicy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(status.getAsJsonObject("policy")));
            WorldAuthority.authorizeHost(currentPolicy,actor,System.currentTimeMillis());
            if(number(status,"checkpointSequence")!=staged.checkpointSequence()
                    ||!ProfileFiles.string(status,"checkpointSha256",64).equals(staged.checkpointSha256()))
                throw new IOException("Служебный узел требует более новую резервную копию");
            document=new IslandAuthorityFiles.Document(certificate,currentPolicy.proof());
            mergeAliases(ready.index().actorAliases());
            if(!localGameId.equals(ready.index().actorAliases().get(actor)))throw new IOException("Нет сохранённой нативной личности кандидата");
            byte[] manifest;try(var input=ready.open(TestingReplicationArchive.MANIFEST)){manifest=input.readNBytes(4*1024*1024+1);}
            String version=ProfileFiles.string(ProfileFiles.parse(manifest,4*1024*1024),"rootVersionId",36);UUID.fromString(version);
            Map<String,UUID> complete;synchronized(aliases){complete=Map.copyOf(aliases);}
            ReplicaPublisher.Prepared snapshot=ReplicaPublisher.prepare(ready.sources(),complete,ReplicaProtocol.Limits.defaults(),ReplicaProtocol.Compatibility.fromManifest(definitions));
            if(!snapshot.index().contentRoot().equals(ready.index().contentRoot()))throw new IOException("Полная резервная копия изменилась перед выбором ведущего");
            persistCheckpointIndex(snapshot.index());
            journal=AuthorityHostJournal.open(identity.base(),certificate);
            synchronized(network){
                guard();if(journal.snapshot().isEmpty())journal.compareCandidateStatus(status,staged.baseline());else journal.compareNodeStatus(status,world);
                permanentAuthor=world;currentWorld=world;currentVersion=version;
                // Journal intent and complete actor table are durable BEFORE the actual Node claim.
                claim(status,world,version);publisher=snapshot;
            }
            PromotionWorkspace.Ticket ticket=staged.activate(permit());boolean retained=false;
            try{synchronized(permitLock){guard();promotionTicket=ticket;retained=true;}}
            finally{if(!retained)ticket.close();}
            Path imported=ProfileFiles.directory(identity.base().resolve("replica-restored")).resolve(UUID.randomUUID().toString());
            var restored=TestingReplicationArchive.restoreExternal(ready.index(),ready::open,imported,world,TestingReplicationArchive.Limits.defaults(),certificate,permit().fence());
            installArchive(restored,world);ticket.prepareOwnerPointer();guard();permit();
        });
        CompletableFuture<PromotionWorkspace.Ticket> result=prepared.thenApply(ignored->{
            PromotionWorkspace.Ticket ticket=promotionTicket;
            if(ticket==null||closed.get())throw new CompletionException(new IOException("Подготовка ведущего отменена"));return ticket;
        });
        result.whenComplete((value,failure)->{if(result.isCancelled())prepared.cancel(true);if(failure!=null)close();});
        return result;
    }

    /** Flow supplies only real server-captured aliases; UI and peer-provided UUIDs never enter this table. */
    CompletableFuture<Void> prepareStopped(Path target,boolean internalExtraction,IslandAccess.View live,Map<String,UUID> capturedAliases) {
        if(internalExtraction){
            if(archiveBinding==null)return CompletableFuture.completedFuture(null);
            return CompletableFuture.failedFuture(new IOException("Извлечение категорий в копии переданного острова требует отдельного защищённого перехода"));
        }
        Map<String,UUID> actual=Map.copyOf(capturedAliases);
        return submit(()->{
            guard();OnlineHostPermit old=permit();Path world=TestingArchive.checkedSave(target,true);
            IslandAccess.View access;
            try(IslandAccess store=IslandAccess.open(world,identity.profile(),live)){access=store.snapshot();}
            WorldAuthority.requireAccess(document.certificate(),access.islandId(),access.ownerId());
            TestingArchiveNamespaces.Binding namespace=archiveBinding;
            if(namespace!=null)namespace.bindDerived(world,document.certificate(),old.fence());
            // Scratch extraction gets file context only; it never receives a listener, claim or presence.
            if(internalExtraction)return;
            mergeAliases(actual);
            TestingArchive.Version version=TestingArchive.capture(world,"Онлайн: перед продолжением","online-checkpoint",null);
            Map<String,UUID> roster;synchronized(aliases){roster=Map.copyOf(aliases);}
            var binding=TestingReplicationArchive.sameIsland(permanentAuthor,currentWorld,world,document.certificate(),old.fence());
            var archive=TestingReplicationArchive.prepare(version,nextSnapshotDirectory(),TestingReplicationArchive.Limits.defaults(),binding);
            guard();ReplicaPublisher.Prepared prepared=ReplicaPublisher.prepare(archive.sources(),roster,ReplicaProtocol.Limits.defaults(),ReplicaProtocol.Compatibility.fromManifest(definitions));
            synchronized(network){
                guard();if(permit!=old)throw new IOException("Власть перехода изменилась");
                JsonObject status=await(service.status());verifiedPolicy(status);journal.compareNodeStatus(status,currentWorld);
                advance(prepared,world,version.id());
                LeaseFence.Fence previous=permit().fence();
                await(service.release(previous));guard();synchronized(permitLock){guard();if(permit!=old)throw new IOException("Власть перехода изменилась");permit.close();permit=null;}
                JsonObject released=await(service.status());verifiedPolicy(released);journal.compareNodeStatus(released,currentWorld);
                claim(released,world,version.id());
                IslandAuthorityFiles.installVerified(world,access,document,permit().fence());
                publisher=prepared;currentWorld=world;currentVersion=version.id();guard();
            }
            if(namespace!=null){
                PromotionWorkspace.Ticket ticket=PromotionWorkspace.reopenStopped(namespace.token(world),identity,permit(),nativeAliases(),nativeSaves);
                boolean retained=false;try{synchronized(permitLock){guard();if(openingTicket!=null)throw new IOException("Предыдущее открытие ещё ожидается");openingTicket=ticket;retained=true;}}
                finally{if(!retained)ticket.close();}
            }
        });
    }
    private ReplicaPublisher.Prepared prepare(TestingArchive.Version version,Map<String,UUID> actualAliases)throws IOException {
        guard();Path target=nextSnapshotDirectory();
        TestingReplicationArchive.Prepared archive=TestingReplicationArchive.prepare(version,target,TestingReplicationArchive.Limits.defaults());
        guard();return ReplicaPublisher.prepare(archive.sources(),actualAliases,ReplicaProtocol.Limits.defaults(),ReplicaProtocol.Compatibility.fromManifest(definitions));
    }
    private Path nextSnapshotDirectory()throws IOException {guard();return ProfileFiles.directory(identity.base().resolve("authority-snapshots")).resolve(UUID.randomUUID().toString());}
    private void claim(JsonObject status,Path world,String version)throws IOException {
        guard();JsonObject lease=status.getAsJsonObject("lease");
        if(status.get("quarantined").getAsBoolean()||lease.get("present").getAsBoolean()&&lease.get("active").getAsBoolean())
            throw new IOException("Предыдущий ведущий ещё удерживает остров; дождитесь подтверждения узла");
        UUID nextSession=UUID.randomUUID();long epoch=number(status,"epoch"),sequence=number(status,"checkpointSequence");
        String sha=ProfileFiles.string(status,"checkpointSha256",64);
        journal.prepared(world,version,nextSession,epoch,sequence,sha);
        LeaseFence.Fence fence=await(service.claim(epoch,nextSession,sequence,sha,30000,WorldAuthority.verifyPolicy(document.certificate(),document.policy()).revision()));
        guard();OnlineHostPermit next=OnlineHostPermit.open(document.certificate(),WorldAuthority.verifyPolicy(document.certificate(),document.policy()),fence,actor,nextSession);
        try{pins.advanceVerified(document.certificate(),fence);journal.confirmed(world,version,fence);synchronized(permitLock){guard();permit=next;}}
        catch(IOException invalid){next.close();throw invalid;}
    }
    private void advance(ReplicaPublisher.Prepared prepared,Path world,String version)throws IOException {
        OnlineHostPermit current=permit();long sequence=current.checkpointSequence();
        persistCheckpointIndex(prepared.index());
        if(sequence==Long.MAX_VALUE)throw new IOException("Исчерпана последовательность снимков");
        LeaseFence.Fence previous=current.fence();
        journal.checkpointPrepared(world,version,previous,sequence+1,prepared.index().contentRoot());
        LeaseFence.Fence fence=await(service.renew(previous,sequence+1,prepared.index().contentRoot(),30000));
        guard();
        try{pins.advanceVerified(document.certificate(),fence);journal.confirmed(world,version,fence);
            current.renew(WorldAuthority.verifyPolicy(document.certificate(),document.policy()),fence);
        }catch(IOException durabilityFailure){invalidate();throw durabilityFailure;}
        currentWorld=world;currentVersion=version;
    }
    /** Complete actor table is committed outside the world before any Node checkpoint CAS. */
    private void persistCheckpointIndex(ReplicaProtocol.Index index)throws IOException {
        Path file=checkpointIndexPath(index.contentRoot());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            ReplicaProtocol.Index existing=readCheckpointIndex(index.contentRoot());
            if(!Arrays.equals(existing.encoded(),index.encoded()))throw new IOException("Полный индекс снимка изменился");
            return;
        }
        JsonObject encoded=ProfileFiles.parse(index.encoded(),ReplicaProtocol.Limits.defaults().indexBytes());
        ProfileFiles.atomic(file,encoded,false);
        if(!Arrays.equals(readCheckpointIndex(index.contentRoot()).encoded(),index.encoded()))
            throw new IOException("Индекс снимка не подтверждён после записи");
    }
    private Path checkpointIndexPath(String contentRoot)throws IOException {
        guard();if(contentRoot==null||!contentRoot.matches("[0-9a-f]{64}"))throw new IOException("Неверный корень индекса");
        WorldAuthority.verify(document.certificate());String authorityId=document.certificate().authorityId();
        return ProfileFiles.directory(identity.base().resolve("authority-index").resolve(authorityId)).resolve(contentRoot+".json");
    }
    private ReplicaProtocol.Index readCheckpointIndex(String contentRoot)throws IOException {
        Path file=checkpointIndexPath(contentRoot);
        ProfileFiles.requireOutsideWorld(file);
        byte[] encoded=ProfileFiles.bytes(ProfileFiles.read(file,ReplicaProtocol.Limits.defaults().indexBytes()));
        ReplicaProtocol.Index index=ReplicaProtocol.decodeIndex(encoded,ReplicaProtocol.Limits.defaults());
        if(!index.contentRoot().equals(contentRoot)||!index.actorAliases().containsKey(WorldAuthority.verify(document.certificate()).ownerId()))
            throw new IOException("Нет полного подтверждённого индекса и нативных личностей острова");
        return index;
    }
    private void mergeAliases(Map<String,UUID> actual)throws IOException {
        synchronized(aliases){
            Map<String,UUID> next=new HashMap<>(aliases);
            for(var entry:actual.entrySet()){
                if(entry.getKey()==null||!entry.getKey().matches("dw1_[A-Za-z0-9_-]{43}")||entry.getValue()==null)throw new IOException("Неверная личность в индексе");
                UUID previous=next.putIfAbsent(entry.getKey(),entry.getValue());
                if(previous!=null&&!previous.equals(entry.getValue()))throw new IOException("Нативная личность участника изменилась");
            }
            if(next.size()>ReplicaProtocol.MAX_ACTOR_ALIASES||new HashSet<>(next.values()).size()!=next.size())
                throw new IOException("Слишком много сохранённых участников или повторные нативные личности");
            aliases.clear();aliases.putAll(next);
        }
    }
    private WorldAuthority.Policy reconcileOwnerPolicy(JsonObject status,Path world,IslandAccess.View access)throws IOException{
        guard();WorldAuthority.Policy actual=WorldAuthority.verifyPolicy(document.certificate(),WorldAuthority.signedFromJson(status.getAsJsonObject("policy")));
        WorldAuthority.Policy expected=WorldAuthority.verifyPolicy(document.certificate(),document.policy());
        if(actual.revision()<expected.revision()||actual.revision()==expected.revision()&&!actual.sha256().equals(expected.sha256()))
            throw new IOException("Политика служебного узла отличается от сохранённой");
        if(actual.revision()>expected.revision())document=IslandAuthorityFiles.updatePolicy(world,access,actual.proof());
        return actual;
    }
    private WorldAuthority.Policy verifiedPolicy(JsonObject status)throws IOException {
        guard();var signed=WorldAuthority.signedFromJson(status.getAsJsonObject("policy"));
        WorldAuthority.Policy actual=WorldAuthority.verifyPolicy(document.certificate(),signed);
        WorldAuthority.Policy expected=WorldAuthority.verifyPolicy(document.certificate(),document.policy());
        if(actual.revision()!=expected.revision()||!actual.sha256().equals(expected.sha256()))throw new IOException("Подписанная политика ведущего изменилась");
        return actual;
    }
    private void renew(){
        if(closed.get())return;
        try{
            guard();OnlineHostPermit current=permit;if(current==null)return;
            if(!current.usable())throw new IOException("Полномочия ведущего истекли");
            if(current.remainingMillis()>20000)return;
            synchronized(network){
                guard();if(current!=permit)return;
                LeaseFence.Fence next=await(service.renew(current.fence(),current.checkpointSequence(),current.checkpointSha256(),30000));
                guard();
                try{pins.advanceVerified(document.certificate(),next);journal.confirmed(currentWorld,currentVersion,next);
                    current.renew(WorldAuthority.verifyPolicy(document.certificate(),document.policy()),next);
                }catch(IOException durabilityFailure){invalidate();throw durabilityFailure;}
            }
        }catch(Exception failure){
            OnlineHostPermit current=permit;
            if(current==null||!current.usable()||!routeCurrent()||service!=null&&(service.observedPolicyRevision()>current.policyRevision()||service.observedEpoch()>current.nodeLeaseEpoch())){invalidate();if(notified.compareAndSet(false,true))failed.accept("Служебный узел не подтвердил полномочия ведущего; общий мир остановлен.");}
        }
    }
    private boolean routeCurrent(){
        try{return !closed.get()&&runtime.generation()==runtimeGeneration&&runtime.identity()==identity&&node.equals(runtime.verifiedAuthorityNode().id());}
        catch(Exception unavailable){return false;}
    }
    private void guard()throws IOException {if(!routeCurrent())throw new IOException("Профиль или служебный узел изменился");}
    private <T>T await(CompletableFuture<T> stage)throws IOException {
        pending.add(stage);
        try{guard();T value=stage.get(18,TimeUnit.SECONDS);guard();return value;}
        catch(InterruptedException canceled){Thread.currentThread().interrupt();stage.cancel(true);throw new IOException("Подтверждение отменено; исход запроса узла требует проверки",canceled);}
        catch(ExecutionException|TimeoutException|CancellationException failed){stage.cancel(true);throw new IOException("Узел не подтвердил запрос; повторное применение не выполняется",failed);}
        finally{pending.remove(stage);}
    }
    private static long number(JsonObject value,String key)throws IOException {long result=ProfileFiles.number(value,key);if(result<0||!Long.toString(result).equals(value.get(key).getAsString()))throw new IOException("Неверное число в подтверждении узла");return result;}
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private CompletableFuture<Void> submit(Work work){
        CompletableFuture<Void> result=new CompletableFuture<>();pending.add(result);
        result.whenComplete((v,f)->{pending.remove(result);if(result.isCancelled())close();});
        final TestingArchiveScope.Token context;
        try{context=TestingArchiveScope.capture().orElse(null);}catch(RuntimeException unavailable){result.completeExceptionally(unavailable);return result;}
        try{io.execute(()->{try(TestingArchiveScope.Scope ignored=context==null?null:context.enter()){
            guard();if(result.isCancelled())return;work.run();guard();TestingArchiveScope.checkCurrent();result.complete(null);
        }catch(Exception failure){result.completeExceptionally(failure);}});}
        catch(RejectedExecutionException capacity){result.completeExceptionally(new IOException("Операция полномочий уже выполняется",capacity));}
        return result;
    }
    private void invalidate(){synchronized(permitLock){OnlineHostPermit current=permit;if(current!=null)current.close();}}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;invalidate();renewals.shutdownNow();io.shutdownNow();
        TestingArchiveNamespaces.Binding binding=archiveBinding;archiveBinding=null;if(binding!=null)try{binding.close();}catch(IOException ignored){}
        PromotionWorkspace.Ticket ticket=promotionTicket;promotionTicket=null;if(ticket!=null)try{ticket.close();}catch(IOException ignored){}
        PromotionWorkspace.Ticket reopen;synchronized(permitLock){reopen=openingTicket;openingTicket=null;}if(reopen!=null)try{reopen.close();}catch(IOException ignored){}
        for(CompletableFuture<?> task:List.copyOf(pending))task.cancel(true);
        AuthorityServiceClient current=service;if(current!=null)current.close();
        // No release: caller first fences/stops its native simulation; the remote node's bounded TTL remains authoritative.
        Thread cleanup=new Thread(()->{synchronized(network){if(journal!=null)try{journal.close();}catch(IOException ignored){}if(pins!=null)pins.close();}},"DarkWinter-authority-close");
        cleanup.setDaemon(true);cleanup.start();
    }
}
