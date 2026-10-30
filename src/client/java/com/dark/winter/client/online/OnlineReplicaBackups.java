// Coordinates verified checkpoint backup transfers without activating a game world.
package com.dark.winter.client.online;

import com.dark.winter.online.activation.PromotionWorkspace;
import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.dark.winter.online.replica.*;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.transport.WebRtcMultiplex;
import com.dark.winter.online.world.*;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Application backup lane only. A verified READY and a guarded inactive copy are not a host lease.
 * This class never opens a world, claims authority, changes ACL/aliases or closes the native Mux lane.
 */
public final class OnlineReplicaBackups implements AutoCloseable {
    private static final long MIB=1024L*1024, GIB=1024*MIB;
    private static final int MAX_CONNECTIONS=16,MAX_STAGES=8,MAX_RECORDS=32;
    private final OnlineFriendsRuntime runtime;
    private final Path saves;
    private final ReplicaProtocol.Compatibility compatibility;
    private final Runnable changed;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),task->{Thread t=new Thread(task,"DarkWinter-replica-backups");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private final Object lock=new Object();
    private final Map<UUID,Connection> connections=new LinkedHashMap<>();
    private final Map<UUID,Standby> retained=new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> results=ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> jobs=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> ready=new CompletableFuture<>();
    private volatile IdentityStore identity;
    private volatile Settings settings=Settings.defaults();
    private volatile boolean consent;
    private volatile String status="Подготовка локального хранилища резервов";
    private Path directory;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private ReplicaStore store;
    private final Map<String,Verified> verified=new TreeMap<>();
    private final List<Reservation> reservations=new ArrayList<>();

    /** Separate cache and inactive-native-copy budgets; all limits are local, never supplied by a peer. */
    public record Settings(boolean enabled,long cacheBytes,long stageBytes,long freeReserveBytes,int retainedReady) {
        public Settings {
            if(cacheBytes<512*MIB||cacheBytes>4*GIB||stageBytes<512*MIB||stageBytes>4*GIB
                ||freeReserveBytes<64*MIB||freeReserveBytes>GIB||retainedReady<1||retainedReady>MAX_STAGES)
                throw new IllegalArgumentException("Local replica disk limits");
        }
        public static Settings defaults(){return new Settings(false,GIB,2*GIB,128*MIB,4);}
        ReplicaProtocol.Limits limits(){return new ReplicaProtocol.Limits(512*MIB,128*MIB,4096,4*1024*1024,cacheBytes,freeReserveBytes,retainedReady);}
        JsonObject document(String owner){
            JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("owner",owner);
            value.addProperty("enabled",enabled);value.addProperty("cacheBytes",cacheBytes);value.addProperty("stageBytes",stageBytes);
            value.addProperty("freeReserveBytes",freeReserveBytes);value.addProperty("retainedReady",retainedReady);return value;
        }
        static Settings read(JsonObject value,String owner)throws IOException {
            ProfileFiles.fields(value,Set.of("schema","owner","enabled","cacheBytes","stageBytes","freeReserveBytes","retainedReady"),Set.of());
            if(ProfileFiles.number(value,"schema")!=1||!ProfileFiles.string(value,"owner",64).equals(owner)
                ||!value.get("enabled").isJsonPrimitive()||!value.getAsJsonPrimitive("enabled").isBoolean())throw new IOException("Foreign/unsupported backup preferences");
            try{return new Settings(value.get("enabled").getAsBoolean(),ProfileFiles.number(value,"cacheBytes"),ProfileFiles.number(value,"stageBytes"),
                ProfileFiles.number(value,"freeReserveBytes"),Math.toIntExact(ProfileFiles.number(value,"retainedReady")));}
            catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid local replica limits",invalid);}
        }
    }
    /** Opaque proven objects stay intact. The caller must still obtain a new Node lease before any activation. */
    public record Standby(ReplicaStore.Ready ready,PromotionWorkspace.Staged staged) {
        public Standby {
            Objects.requireNonNull(ready);Objects.requireNonNull(staged);
            if(!ready.index().contentRoot().equals(staged.checkpointSha256())||ready.header().sequence()!=staged.checkpointSequence())
                throw new IllegalArgumentException("Standby checkpoint mismatch");
        }
    }
    private record Verified(long sequence,String root){}
    private record Reservation(UUID id,String authority,long sequence,String root,long bytes){}
    private static final class Connection {
        final UUID attempt;final SignedPayload proof;final IdentityProfile peer;final WebRtcMultiplex mux;final long generation;final CompletableFuture<Void> muxClosed;
        final AtomicBoolean detached=new AtomicBoolean();
        volatile ReplicaTransferChannel.Endpoint endpoint;
        volatile ReplicaStore.Ready backup;
        volatile Standby standby;
        volatile String status="Канал резерва ещё не запрошен";
        volatile boolean receiving,publishing;
        volatile CompletableFuture<?> pending;
        final Set<CompletableFuture<?>> work=ConcurrentHashMap.newKeySet();
        volatile ReplicaTransferChannel.Receipt acknowledged;
        Connection(UUID attempt,SignedPayload proof,IdentityProfile peer,WebRtcMultiplex mux,long generation){this.attempt=attempt;this.proof=proof;this.peer=peer;this.mux=mux;this.generation=generation;muxClosed=mux.closed();}
    }

    public OnlineReplicaBackups(OnlineFriendsRuntime runtime,Path nativeSaves,ReplicaProtocol.Compatibility compatibility,Runnable changed) {
        this.runtime=Objects.requireNonNull(runtime);saves=Objects.requireNonNull(nativeSaves).toAbsolutePath().normalize();
        this.compatibility=Objects.requireNonNull(compatibility);this.changed=Objects.requireNonNull(changed);
        runtime.ready().whenComplete((actual,failure)->{
            if(failure!=null){ready.completeExceptionally(failure);status="Локальное хранилище недоступно";notifyChanged();return;}
            execute(()->{identity=actual;initialize();ready.complete(null);return null;})
                .whenComplete((v,error)->{if(error!=null){ready.completeExceptionally(error);status="Локальное хранилище недоступно";notifyChanged();}});
        });
    }
    public CompletableFuture<Void> ready(){return ready.copy();}
    public Settings settings(){return settings;}
    public CompletableFuture<Void> configure(Settings requested) {
        Objects.requireNonNull(requested);
        if(!requested.enabled()){consent=false;cancelReceivers();}
        return afterReady(()->{
            guard();
            if(store!=null&&(requested.cacheBytes()!=settings.cacheBytes()||requested.retainedReady()!=settings.retainedReady()
                ||requested.freeReserveBytes()!=settings.freeReserveBytes()))
                throw new IOException("Измените лимит кэша после закрытия Online и повторного открытия профиля");
            if(reservedBytes()>requested.stageBytes()||reservations.size()>requested.retainedReady())
                throw new IOException("Сохранённые резервные копии превышают выбранный лимит");
            ProfileFiles.atomic(directory.resolve("preferences.json"),requested.document(identity.profile().id()),true);
            settings=requested;consent=requested.enabled();status=consent?"Приём резервов разрешён локально":"Приём резервов выключен";notifyChanged();return null;
        });
    }
    /** Root calls only for a real connected peer and its already authenticated Mux. No transport/identity is created here. */
    public void attach(UUID attempt,SignedPayload actualPeerProfile,WebRtcMultiplex existingMux)throws IOException {
        Objects.requireNonNull(attempt);Objects.requireNonNull(existingMux);
        IdentityProfile peer=IdentityStore.validateProfileProof(actualPeerProfile);
        synchronized(lock){
            if(closed.get())throw new IOException("Backup manager closed");
            Connection previous=connections.get(attempt);
            if(previous!=null){
                if(previous.mux!=existingMux||!previous.peer.equals(peer))throw new IOException("Attempt transport/profile changed");
                if(previous.detached.get())throw new IOException("Replica lane closed; reconnect with a new attempt");return;
            }
            connections.entrySet().removeIf(row->row.getValue().detached.get()&&!row.getValue().receiving&&!row.getValue().publishing);
            if(connections.size()>=MAX_CONNECTIONS)throw new IOException("Replica connection capacity");
            connections.put(attempt,new Connection(attempt,actualPeerProfile,peer,existingMux,runtime.generation()));
        }
        existingMux.closed().whenComplete((v,f)->detach(attempt));notifyChanged();
    }
    /**
     * Subscriber and its READY waiter exist BEFORE the authenticated RPC. Never wait for opened() here:
     * the publisher's handshake starts only after that RPC reaches the actual native host.
     */
    public CompletableFuture<Standby> requestBackup(UUID attempt,OnlineGuestAuthority observer,Supplier<CompletableFuture<JsonObject>> authenticatedBackupAcceptRPC) {
        Objects.requireNonNull(observer);Objects.requireNonNull(authenticatedBackupAcceptRPC);
        Connection connection;
        try{
            connection=connection(attempt);
            synchronized(lock){
                if(!consent||!settings.enabled())throw new IOException("Приём резервов выключен локально");
                if(connection.receiving||connection.publishing)throw new IOException("Дождитесь текущей передачи резерва");
                connection.receiving=true;
            }
        }catch(IOException rejected){return CompletableFuture.failedFuture(rejected);}
        CompletableFuture<Standby> result=track(new CompletableFuture<>());
        result.whenComplete((v,f)->{if(result.isCancelled())closeLane(connection,"Приём резерва отменён; проверенный кэш сохранён");});
        CompletableFuture<Void> preparing=afterReady(()->{
            guestCurrent(connection,observer);
            LeaseFence.Fence fence=observer.fence();
            if(connection.backup!=null&&connection.backup.header().sequence()==fence.checkpointSequence()
                &&connection.backup.index().contentRoot().equals(fence.checkpointSha256())){
                prepareStage(connection,observer,result);return null;
            }
            ReplicaTransferChannel.Subscriber subscriber;
            if(connection.endpoint==null){
                ReplicaStore ownStore=cache();ReplicaStore.Ready base=base(observer.certificate());
                subscriber=ReplicaTransferChannel.subscriber(connection.mux.replicaStream(),identity,connection.proof,observer.certificate(),
                    ()->{guestCurrent(connection,observer);return observer.policy();},
                    ()->{guestCurrent(connection,observer);return observer.fence();},ownStore,base,options());
                connection.endpoint=subscriber;
            }else if(connection.endpoint instanceof ReplicaTransferChannel.Subscriber existing)subscriber=existing;
            else throw new IOException("Replica lane already used for publishing");
            CompletableFuture<ReplicaStore.Ready> incoming=subscriber.nextReady();
            connection.pending=incoming;connection.status="Ожидание подтверждённого снимка ведущего";notifyChanged();
            // Light completion callbacks only; native copying and durable metadata always run on our bounded worker.
            incoming.whenComplete((complete,failed)->{
                if(failed!=null){failReceive(connection,result,"Передача резерва остановлена; проверенные файлы сохранены");return;}
                connection.backup=complete;
                CompletableFuture<Void> staging=execute(()->{
                    guard();
                    remember(complete);connection.status="Резерв проверен; подготовка неактивной копии";notifyChanged();
                    prepareStage(connection,observer,result);return null;
                });
                connectionWork(connection,staging);
                staging.whenComplete((v,error)->{if(error!=null)failReceive(connection,result,"Резерв сохранён, но неактивная копия не подготовлена");});
            });
            CompletableFuture<JsonObject> rpc=Objects.requireNonNull(authenticatedBackupAcceptRPC.get(),"No native backup RPC");
            rpc.orTimeout(20,TimeUnit.SECONDS).whenComplete((reply,failed)->{
                if((failed!=null||reply==null||!isAccepted(reply))&&connection.backup==null){
                    closeLane(connection,"Ведущий не разрешил резерв; повторите после переподключения");
                    failReceive(connection,result,"Ведущий не разрешил резерв");
                }
            });
            return null;
        });
        connectionWork(connection,preparing);
        preparing.whenComplete((v,error)->{if(error!=null){
            if(connection.backup==null)closeLane(connection,"Передача резерва не началась; требуется переподключение");
            failReceive(connection,result,consent?"Резерв недоступен; ведущему может требоваться контрольная точка":"Приём резервов выключен локально");
        }});
        return result;
    }

    /** Server-thread admission only; returns accepted immediately, not READY and not an authority grant. */
    public JsonObject acceptBackup(UUID attempt,OnlineGameHost actualHost,ServerPlayer actualReceiver,OnlineAuthoritySession authority)throws IOException {
        Connection connection=connection(attempt);guard();
        MinecraftServer server=actualReceiver.level().getServer();
        if(!server.isSameThread()||OnlineGameHost.get(server)!=actualHost||!actualHost.can(actualReceiver,IslandAccess.Permission.VIEW)
            ||!actualHost.can(actualReceiver,IslandAccess.Permission.TRANSFER_HOST))throw new IOException("Нет текущего допуска к резерву");
        HostGrant grant=new HostGrant(connection,actualHost,actualReceiver,authority,server);
        OnlineHostPermit permit=grant.current();
        ReplicaPublisher.Prepared source=authority.publisher();
        UUID checkpointPlayer=source.index().actorAliases().get(connection.peer.id());
        if(checkpointPlayer==null)throw new IOException("Ведущему нужно создать контрольную точку");
        if(!checkpointPlayer.equals(actualReceiver.getUUID()))throw new IOException("Нативный участник не совпадает с контрольной точкой");
        if(source.index().totalBytes()>settings.limits().snapshotBytes()||!source.index().contentRoot().equals(permit.checkpointSha256()))
            throw new IOException("Текущая контрольная точка недоступна для резерва");
        synchronized(lock){
            if(connection.receiving||connection.publishing)throw new IOException("Дождитесь текущей передачи резерва");
            if(connection.acknowledged!=null&&connection.acknowledged.header().sequence()==permit.checkpointSequence()
                &&connection.acknowledged.index().contentRoot().equals(source.index().contentRoot()))throw new IOException("Этот резерв уже подтверждён другом");
            connection.publishing=true;
        }
        connection.status="Отправка подтверждённой контрольной точки";notifyChanged();
        execute(()->{
            grant.current();
            ReplicaTransferChannel.Publisher publisher;
            if(connection.endpoint==null){
                publisher=ReplicaTransferChannel.publisher(connection.mux.replicaStream(),identity,connection.proof,permit.certificate(),
                    ()->grant.current().policy(),()->grant.current().fence(),options());
                connection.endpoint=publisher;
            }else if(connection.endpoint instanceof ReplicaTransferChannel.Publisher existing)publisher=existing;
            else throw new IOException("Replica lane already used for receiving");
            ReplicaTransferChannel.Receipt previous=connection.acknowledged;
            boolean delta=previous!=null&&previous.header().sequence()<permit.checkpointSequence();
            CompletableFuture<ReplicaTransferChannel.Receipt> transmission=delta
                ?publisher.publish(source,permit.checkpointSequence(),previous.index(),previous.header().sequence())
                :publisher.publish(source,permit.checkpointSequence());
            connection.pending=transmission;
            transmission.whenComplete((receipt,failed)->{
                synchronized(lock){
                    connection.publishing=false;
                    if(failed==null&&!connection.detached.get()){connection.acknowledged=receipt;connection.status="Друг подтвердил проверенный резерв";}
                    else connection.status="Передача остановлена; подтверждённые файлы друга сохраняются";
                }notifyChanged();
            });return null;
        }).whenComplete((v,f)->{if(f!=null){synchronized(lock){connection.publishing=false;}closeLane(connection,"Отправка резерва недоступна");}});
        JsonObject accepted=new JsonObject();accepted.addProperty("ok",true);accepted.addProperty("phase","accepted");return accepted;
    }
    /** No HTTP/native world reads here: immutable ACL snapshots, concurrent actor binding and cached opaque permit only. */
    private final class HostGrant {
        final Connection connection;final OnlineGameHost host;final ServerPlayer receiver;final OnlineAuthoritySession authority;
        final MinecraftServer server;final UUID session;final long epoch;final OnlineHostPermit originalPermit;
        HostGrant(Connection connection,OnlineGameHost host,ServerPlayer receiver,OnlineAuthoritySession authority,MinecraftServer server)throws IOException {
            this.connection=connection;this.host=host;this.receiver=receiver;this.authority=Objects.requireNonNull(authority);this.server=server;
            session=host.sessionId();epoch=host.epoch();originalPermit=authority.permit();
        }
        OnlineHostPermit current()throws IOException {
            currentConnection(connection);
            if(!server.isRunning()||OnlineGameHost.get(server)!=host||host.transitioning()||host.epoch()!=epoch
                ||!host.sessionId().equals(session)||!connection.peer.id().equals(host.actor(receiver)))throw new IOException("Native backup admission changed");
            IslandAccess access=host.access();IslandAccess.View view=access.snapshot();String peer=connection.peer.id();
            if(view.epoch()!=epoch||!view.allowlist().contains(peer)||view.blocked().contains(peer)||!view.trustedLeads().contains(peer)
                ||!access.can(peer,IslandAccess.Permission.VIEW)||!access.can(peer,IslandAccess.Permission.TRANSFER_HOST)||access.snapshot()!=view)
                throw new IOException("Backup receiver is no longer a trusted permitted participant");
            OnlineHostPermit permit=authority.permit();
            if(permit!=originalPermit||!permit.nativeSessionId().equals(session)||!host.localIdentityId().equals(identity.profile().id()))
                throw new IOException("Backup host authority changed");
            permit.requireUsableFor(identity.profile().id(),session);
            if(!permit.islandId().toString().equals(view.islandId())||!permit.ownerId().equals(view.ownerId()))throw new IOException("Backup ACL authority changed");
            WorldAuthority.authorizeHost(permit.policy(),peer,System.currentTimeMillis());return permit;
        }
    }

    public Optional<Standby> standby(UUID attempt) {
        synchronized(lock){Connection connection=connections.get(attempt);return Optional.ofNullable(connection!=null&&connection.standby!=null?connection.standby:retained.get(attempt));}
    }
    public Optional<ReplicaStore.Ready> verifiedBackup(UUID attempt) {
        synchronized(lock){Connection connection=connections.get(attempt);Standby retainedCopy=retained.get(attempt);
            return Optional.ofNullable(connection!=null&&connection.backup!=null?connection.backup:retainedCopy==null?null:retainedCopy.ready());}
    }
    public void detach(UUID attempt) {
        Connection connection;synchronized(lock){connection=connections.get(attempt);}
        if(connection!=null)closeLane(connection,"Связь закрыта; проверенный локальный резерв сохранён");
    }
    /** Only whitelisted progress. No profile proofs, keys, capabilities or filesystem paths leave this manager. */
    public JsonObject snapshot(UUID attempt) {
        Connection connection;synchronized(lock){connection=connections.get(attempt);}
        JsonObject value=new JsonObject();value.addProperty("enabled",consent);value.addProperty("status",connection==null?status:connection.status);
        value.addProperty("busy",connection!=null&&(connection.receiving||connection.publishing));
        ReplicaTransferChannel.Endpoint endpoint=connection==null?null:connection.endpoint;
        ReplicaTransferChannel.Stats stats=endpoint==null?null:endpoint.stats();
        value.addProperty("bytesSent",stats==null?0:stats.wireSent());value.addProperty("bytesReceived",stats==null?0:stats.wireReceived());
        ReplicaStore.Ready backup=connection==null?null:connection.backup;
        ReplicaTransferChannel.Receipt ack=connection==null?null:connection.acknowledged;
        value.addProperty("sequence",backup!=null?backup.header().sequence():ack!=null?ack.header().sequence():-1);
        value.addProperty("contentRoot",backup!=null?backup.index().contentRoot():ack!=null?ack.index().contentRoot():"");
        value.addProperty("verified",backup!=null||ack!=null);value.addProperty("staged",standby(attempt).isPresent());return value;
    }
    public JsonObject snapshot() {
        JsonObject value=new JsonObject();value.addProperty("enabled",consent);value.addProperty("ready",ready.isDone()&&!ready.isCompletedExceptionally());
        value.addProperty("status",status);JsonArray transfers=new JsonArray();List<UUID> ids;
        synchronized(lock){ids=List.copyOf(connections.keySet());}for(UUID id:ids)transfers.add(snapshot(id));value.add("transfers",transfers);return value;
    }

    private void prepareStage(Connection connection,OnlineGuestAuthority observer,CompletableFuture<Standby> result)throws IOException {
        guestCurrent(connection,observer);
        if(result.isDone())throw new IOException("Backup request canceled");
        if(connection.standby!=null&&connection.standby.ready()==connection.backup){finishReceive(connection,result,connection.standby);return;}
        ReplicaStore.Ready complete=Objects.requireNonNull(connection.backup);
        LeaseFence.Fence fresh=observer.fence();
        ReplicaProtocol.fresh(observer.certificate(),fresh);ReplicaProtocol.sameFence(complete.header(),fresh);
        if(!complete.index().contentRoot().equals(fresh.checkpointSha256())||complete.header().sequence()!=fresh.checkpointSequence())
            throw new IOException("Stage checkpoint is no longer the independently observed current checkpoint");
        long allocation=Math.addExact(complete.index().totalBytes(),MIB);
        requireStageBudget(settings,reservedBytes(),reservations.size(),allocation,Files.getFileStore(saves).getUsableSpace());
        Reservation reservation=new Reservation(UUID.randomUUID(),complete.certificate().authorityId(),complete.header().sequence(),complete.index().contentRoot(),allocation);
        // Conservatively retain this charge even if copying fails; never delete a user's/verified native world to reclaim it.
        ProfileFiles.atomic(directory.resolve("stage-"+reservation.id()+".json"),reservationDocument(reservation),false);reservations.add(reservation);
        PromotionWorkspace.Staged staged=PromotionWorkspace.stageReady(complete,identity,saves,observer.policy(),fresh);
        Standby standby=new Standby(complete,staged);
        synchronized(lock){
            connection.standby=standby;retained.put(connection.attempt,standby);
            if(retained.size()>MAX_STAGES)throw new IOException("Retained backup capability capacity");
        }
        if(closed.get()){staged.close();throw new IOException("Backup manager closed after stage");}
        // A late game disconnect never destroys a completed, verified inactive copy.
        connection.status="Проверенный резерв и неактивная копия готовы";finishReceive(connection,result,standby);
    }
    private void finishReceive(Connection connection,CompletableFuture<Standby> result,Standby standby) {
        synchronized(lock){connection.receiving=false;connection.pending=null;}
        result.complete(standby);notifyChanged();
    }
    private void failReceive(Connection connection,CompletableFuture<Standby> result,String message) {
        synchronized(lock){connection.receiving=false;connection.pending=null;connection.status=message;}
        result.completeExceptionally(new IOException(message));notifyChanged();
    }
    private void guestCurrent(Connection connection,OnlineGuestAuthority observer)throws IOException {
        currentConnection(connection);
        if(!consent||!settings.enabled())throw new IOException("Receiving disabled locally");
        observer.requireUsable();LeaseFence.Fence fence=observer.fence();
        if(!connection.peer.id().equals(fence.holderId()))throw new IOException("Observed host differs from authenticated peer");
        WorldAuthority.authorizeHost(observer.policy(),identity.profile().id(),System.currentTimeMillis());
    }
    private Connection connection(UUID attempt)throws IOException {
        synchronized(lock){Connection connection=connections.get(attempt);if(connection==null)throw new IOException("No authenticated replica connection");currentConnection(connection);return connection;}
    }
    private void currentConnection(Connection connection)throws IOException {
        guard();if(connection.detached.get()||connection.muxClosed.isDone()||runtime.generation()!=connection.generation)throw new IOException("Replica attempt is closed");
        synchronized(lock){if(connections.get(connection.attempt)!=connection)throw new IOException("Replica attempt generation changed");}
    }
    private void guard()throws IOException {
        if(closed.get()||identity==null||runtime.identity()!=identity||Thread.currentThread().isInterrupted())
            throw new IOException("Replica profile/generation closed or changed");
    }
    private ReplicaTransferChannel.Options options(){return new ReplicaTransferChannel.Options(Duration.ofSeconds(30),settings.limits(),compatibility);}
    private static boolean isAccepted(JsonObject reply){return reply.has("ok")&&reply.get("ok").isJsonPrimitive()
        &&reply.getAsJsonPrimitive("ok").isBoolean()&&reply.get("ok").getAsBoolean();}
    private void closeLane(Connection connection,String message) {
        if(!connection.detached.compareAndSet(false,true))return;
        connection.status=message;ReplicaTransferChannel.Endpoint endpoint=connection.endpoint;
        if(endpoint!=null)endpoint.close();else connection.mux.replicaStream().close();
        CompletableFuture<?> pending=connection.pending;if(pending!=null)pending.cancel(true);
        for(CompletableFuture<?> preparing:List.copyOf(connection.work))preparing.cancel(true);notifyChanged();
    }
    private void connectionWork(Connection connection,CompletableFuture<?> task){
        connection.work.add(task);task.whenComplete((v,f)->connection.work.remove(task));if(connection.detached.get())task.cancel(true);
    }
    private void cancelReceivers(){List<Connection> copy;synchronized(lock){copy=List.copyOf(connections.values());}
        for(Connection connection:copy)if(connection.receiving||connection.endpoint instanceof ReplicaTransferChannel.Subscriber)closeLane(connection,"Приём резервов выключен локально");}
    private ReplicaStore cache()throws IOException {
        guard();if(store==null)store=ReplicaStore.open(directory.resolve("cache"),settings.limits(),compatibility);return store;
    }
    private ReplicaStore.Ready base(WorldAuthority.Certificate certificate)throws IOException {
        Verified prior=verified.get(certificate.authorityId());return prior==null?null:cache().inspectReady(certificate,prior.root(),prior.sequence());
    }
    private void remember(ReplicaStore.Ready complete)throws IOException {
        guard();String authority=complete.certificate().authorityId();
        if(!verified.containsKey(authority)&&verified.size()>=MAX_RECORDS)throw new IOException("Verified backup history capacity");
        Verified prior=verified.get(authority);long sequence=complete.header().sequence();String root=complete.index().contentRoot();
        if(prior!=null&&(sequence<prior.sequence()||sequence==prior.sequence()&&!root.equals(prior.root())))throw new IOException("Verified backup rollback/fork");
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("owner",identity.profile().id());
        value.addProperty("authority",authority);value.addProperty("sequence",sequence);value.addProperty("root",root);
        ProfileFiles.atomic(directory.resolve("verified-"+authority+".json"),value,true);verified.put(authority,new Verified(sequence,root));
    }
    private long reservedBytes()throws IOException {long bytes=0;try{for(Reservation row:reservations)bytes=Math.addExact(bytes,row.bytes());return bytes;}
        catch(ArithmeticException invalid){throw new IOException("Replica staging reservation overflow",invalid);}}
    private JsonObject reservationDocument(Reservation row){
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("owner",identity.profile().id());value.addProperty("id",row.id().toString());
        value.addProperty("authority",row.authority());value.addProperty("sequence",row.sequence());value.addProperty("root",row.root());value.addProperty("bytes",row.bytes());return value;
    }
    private void initialize()throws IOException {
        guard();safe(saves);if(!Files.isDirectory(saves,LinkOption.NOFOLLOW_LINKS))throw new IOException("Native saves directory missing");
        directory=ProfileFiles.directory(identity.base().resolve("replica-backups").resolve(identity.profile().id()));
        Path lockPath=directory.resolve(".manager.lock");safe(lockPath);lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        ProfileFiles.protect(lockPath,false);fileLock=lockChannel.tryLock();if(fileLock==null)throw new IOException("Replica backups already open");
        settings=loadPreferences(directory.resolve("preferences.json"),identity.profile().id());
        try(var paths=Files.list(directory)){
            List<Path> files=paths.limit(2L*MAX_RECORDS+MAX_STAGES+8).toList();
            if(files.size()>2*MAX_RECORDS+MAX_STAGES+7)throw new IOException("Replica preference inventory limit");
            for(Path file:files){
                String name=file.getFileName().toString();
                if(!name.startsWith("verified-")&&!name.startsWith("stage-"))continue;
                safe(file);JsonObject value=ProfileFiles.read(file,4096);
                if(ProfileFiles.number(value,"schema")!=1||!ProfileFiles.string(value,"owner",64).equals(identity.profile().id()))throw new IOException("Foreign backup metadata");
                String authority=ProfileFiles.string(value,"authority",64),root=ProfileFiles.string(value,"root",64);long sequence=ProfileFiles.number(value,"sequence");
                if(!authority.matches("dwa1_[A-Za-z0-9_-]{43}")||!root.matches("[0-9a-f]{64}")||sequence<0)throw new IOException("Invalid backup binding");
                if(name.startsWith("verified-")){
                    ProfileFiles.fields(value,Set.of("schema","owner","authority","sequence","root"),Set.of());
                    if(!name.equals("verified-"+authority+".json")||verified.putIfAbsent(authority,new Verified(sequence,root))!=null||verified.size()>MAX_RECORDS)throw new IOException("Duplicate backup metadata");
                }else{
                    ProfileFiles.fields(value,Set.of("schema","owner","id","authority","sequence","root","bytes"),Set.of());
                    UUID id=UUID.fromString(ProfileFiles.string(value,"id",36));long bytes=ProfileFiles.number(value,"bytes");
                    if(!name.equals("stage-"+id+".json")||bytes<1||bytes>513*MIB||reservations.size()>=MAX_STAGES)throw new IOException("Stage reservation bounds");
                    reservations.add(new Reservation(id,authority,sequence,root,bytes));
                }
            }
        }
        if(reservedBytes()>settings.stageBytes())throw new IOException("Stored inactive replicas exceed selected quota");
        consent=settings.enabled();status=consent?"Приём резервов разрешён локально":"Приём резервов выключен";notifyChanged();
    }
    /** Same bounded preference reader used by initialization and the isolated disk-safety fixture. */
    static Settings loadPreferences(Path file,String owner)throws IOException {
        if(owner==null||!owner.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid local backup owner");
        safe(file);ProfileFiles.requireOutsideWorld(file);
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Settings.read(ProfileFiles.read(file,4096),owner);
        Settings defaults=Settings.defaults();ProfileFiles.atomic(file,defaults.document(owner),false);return defaults;
    }
    static void requireStageBudget(Settings local,long used,int count,long allocation,long usable)throws IOException {
        if(used<0||used>4*GIB||count<0||count>=local.retainedReady()||allocation<1||allocation>513*MIB||usable<0
            ||used>local.stageBytes()-allocation||usable<allocation+local.freeReserveBytes())
            throw new IOException("Inactive backup copy disk quota");
    }
    private static void safe(Path requested)throws IOException {
        for(Path path=requested.toAbsolutePath().normalize();path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw new IOException("Linked backup path");
    }
    private <T> CompletableFuture<T> afterReady(IO<T> action){
        CompletableFuture<T> result=track(new CompletableFuture<>());
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<T>> running=new java.util.concurrent.atomic.AtomicReference<>();
        result.whenComplete((v,f)->{if(result.isCancelled()){CompletableFuture<T> task=running.get();if(task!=null)task.cancel(true);}});
        ready.whenComplete((v,error)->{
            if(result.isDone())return;
            if(error!=null){result.completeExceptionally(error);return;}
            CompletableFuture<T> task=execute(action);running.set(task);
            if(result.isCancelled())task.cancel(true);
            task.whenComplete((value,failure)->{if(failure==null)result.complete(value);else result.completeExceptionally(failure);});
        });return result;
    }
    @FunctionalInterface private interface IO<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> execute(IO<T> action) {
        CompletableFuture<T> result=track(new CompletableFuture<>());
        if(closed.get()){result.completeExceptionally(new IOException("Replica backups closed"));return result;}
        FutureTask<Void> task=new FutureTask<>(()->{try{if(!result.isDone())result.complete(action.run());}catch(Throwable failed){result.completeExceptionally(failed);}return null;});
        jobs.add(task);result.whenComplete((v,f)->{if(result.isCancelled())task.cancel(true);jobs.remove(task);});
        try{worker.execute(task);}catch(RejectedExecutionException full){jobs.remove(task);result.completeExceptionally(new IOException("Replica worker capacity",full));}
        return result;
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> result){results.add(result);result.whenComplete((v,f)->results.remove(result));return result;}
    private void notifyChanged(){try{changed.run();}catch(RuntimeException ignored){/* UI callback cannot alter storage/authority outcomes. */}}
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;consent=false;
        List<Connection> copy;synchronized(lock){copy=List.copyOf(connections.values());}for(Connection connection:copy)closeLane(connection,"Online закрыт; проверенные резервные файлы сохранены");
        for(Future<?> job:List.copyOf(jobs))job.cancel(true);worker.shutdownNow();
        for(CompletableFuture<?> result:List.copyOf(results))result.completeExceptionally(new IOException("Replica backups closed"));
        ready.completeExceptionally(new IOException("Replica backups closed"));
        Thread.ofVirtual().name("DarkWinter-replica-backups-close").start(()->{
            try{
                if(!worker.awaitTermination(15,TimeUnit.SECONDS))return; // never close a cache underneath a still running native copy.
                synchronized(lock){for(Standby standby:retained.values())standby.staged().close();retained.clear();}
                if(store!=null)store.close();
                if(fileLock!=null&&fileLock.isValid())fileLock.release();if(lockChannel!=null)lockChannel.close();
            }catch(IOException|InterruptedException ignored){if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();}
        });notifyChanged();
    }
}
