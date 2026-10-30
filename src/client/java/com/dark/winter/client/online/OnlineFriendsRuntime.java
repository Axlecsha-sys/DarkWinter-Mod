// Coordinates protected online profiles, saved connections, discovery, and authority requests.
package com.dark.winter.client.online;

import com.dark.winter.DarkWinter;
import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.signaling.IslandJoinFailure;
import com.dark.winter.online.iroh.IrohAsync;
import com.dark.winter.online.iroh.IrohEndpointService;
import com.dark.winter.online.iroh.IrohNodeRoute;
import com.dark.winter.online.rooms.RoomConnectionCode;
import com.dark.winter.online.rooms.ConnectionInfo;
import com.dark.winter.online.rooms.ConnectionCatalogClient;
import com.dark.winter.online.rooms.SavedConnections;
import com.dark.winter.online.friends.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.node.DirectoryClient;
import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.dark.winter.client.online.OnlineUiBridge.*;

/** One shared identity and bounded Friends/authority lanes. No store/network work occurs while reading the UI cache. */
public final class OnlineFriendsRuntime implements OnlineUiBridge.Backend,AutoCloseable {
    public interface GameBackend {
        JsonObject snapshot();
        Set<String> capabilities();
        CompletableFuture<JsonObject> command(String action,JsonObject args);
        default void receive(String from,String kind,JsonObject message){throw new UnsupportedOperationException("No game receiver");}
        default void identityChanged(IdentityStore identity){}
    }
    private static final Set<String> LOCAL=Set.of("identity_update","identity_backup","identity_import","service_configure","privacy_update","room_create","room_join","room_close",
            "connection_create","connection_join","connection_open","connection_close","connection_forget",
            "friend_lookup","friend_request","friend_accept","friend_reject","friend_remove","friend_block","friend_unblock","invite_revoke","invite_reject");
    private final Path profileBase;
    private final Executor clientExecutor;private final GameBackend game;private final Clock clock;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService timer;
    private final ThreadPoolExecutor authorityWorker;
    private final Semaphore authoritySlots=new Semaphore(2);
    private final Set<AuthorityCall> authorityCalls=ConcurrentHashMap.newKeySet();
    private record AuthorityRoute(DirectoryClient client,IdentityStore identity,long generation,String identityId,String nodeId) {}
    private static final class AuthorityCall {
        final CompletableFuture<JsonObject> result=new CompletableFuture<>();
        volatile Future<?> work;volatile CompletableFuture<JsonObject> upstream;volatile ScheduledFuture<?> deadline;
    }
    private volatile AuthorityRoute authorityRoute;
    private final CompletableFuture<IdentityStore> initialized=new CompletableFuture<>();
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    private final AtomicBoolean closed=new AtomicBoolean(),pumpQueued=new AtomicBoolean(),refreshQueued=new AtomicBoolean();
    private final AtomicInteger pending=new AtomicInteger();private final AtomicLong generation=new AtomicLong();
    private final Set<CompletableFuture<?>> futures=ConcurrentHashMap.newKeySet();
    private volatile IdentityStore identity;private volatile FriendsStore friends;private volatile InvitationStore invitations;
    private volatile JsonObject published=loading(),gameView=new JsonObject();
    private volatile Set<String> gameCapabilities=Set.of();
    private volatile DirectoryClient directory;
    private volatile OnlineRoomNetwork room;
    private volatile boolean connectionRelayOnly;
    private static final String STRICT_CONNECTION_REASON="Встроенные соединения недоступны в режиме «Только посредник». Настройка приватности сохранена.";
    private JsonObject preferences,lookup=new JsonObject();
    private OnlineMessageStore communications;
    private SavedConnections savedConnections;
    private final ConnectionCatalogClient connectionCatalog;
    private record CatalogHosting(IdentityStore identity,SavedConnections.Entry saved){}
    private volatile CatalogHosting catalogHosting;
    private volatile CompletableFuture<?> catalogStopping=CompletableFuture.completedFuture(null);
    private final AtomicBoolean catalogHeartbeatPending=new AtomicBoolean();
    private final AtomicLong catalogHeartbeatAt=new AtomicLong();
    private String activeConnectionId="";
    private JsonArray connectionMembers=new JsonArray();
    private long connectionMembersAt;
    private volatile List<SavedConnections.Entry> savedConnectionView=List.of();
    private record ConnectionHealth(boolean reachable,long checkedAt,JsonArray members){}
    private final ConcurrentMap<String,ConnectionHealth> connectionHealth=new ConcurrentHashMap<>();
    private final AtomicReference<Object> connectionProbeToken=new AtomicReference<>();
    private final Object connectionProbeLock=new Object();
    private final AtomicLong connectionProbeGeneration=new AtomicLong();
    private final ExecutorService connectionProbeWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"dw-connection-probe");t.setDaemon(true);return t;});
    private volatile Future<?> connectionProbeJob;
    private volatile CompletableFuture<IrohEndpointService> connectionProbeOpening;
    private volatile IrohEndpointService connectionProbeEndpoint;
    private final JsonArray notifications=new JsonArray();
    private final Map<String,String> presence=new HashMap<>(),issuedCodes=new HashMap<>();
    private final Map<String,InvitationStore.SignedInvitation> remoteIssued=new HashMap<>();
    private final Map<String,Long> lastSent=new HashMap<>();
    private long lastAck,lastRegister;private boolean registered,connected;
    private String mailboxEpoch="",roomDiagnostic="",roomFailurePhase="",roomFailureKind="";
    private String status="Подготовка соединений…",serviceReason="Адрес службы не настроен.";

    public OnlineFriendsRuntime(Path gameDir,Executor clientExecutor,GameBackend game){
        this(gameDir,clientExecutor,game,Clock.systemUTC());
    }
    public OnlineFriendsRuntime(Path gameDir,Executor clientExecutor,GameBackend game,Clock clock){
        this(gameDir,clientExecutor,game,clock,defaultConnectionCatalog());
    }
    private static ConnectionCatalogClient defaultConnectionCatalog(){
        try{return new ConnectionCatalogClient(URI.create("https://darkwinter-connections-20261030.bouncygoose19.chatgpt.site/v1/catalog"));}
        catch(IOException invalidConfiguration){throw new IllegalStateException("Invalid built-in connection catalog configuration");}
    }
    /** Explicit catalog injection keeps native fixtures independent of the public deployment. */
    public OnlineFriendsRuntime(Path gameDir,Executor clientExecutor,GameBackend game,Clock clock,ConnectionCatalogClient connectionCatalog){
        this.connectionCatalog=connectionCatalog;
        this.profileBase=gameDir.toAbsolutePath().normalize().resolve("darkwinter-online/profile");
        this.clientExecutor=Objects.requireNonNull(clientExecutor);this.game=Objects.requireNonNull(game);this.clock=Objects.requireNonNull(clock);
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),
                runnable->{Thread thread=new Thread(runnable,"dw-friends-io");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        authorityWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
                runnable->{Thread thread=new Thread(runnable,"dw-authority-route");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        timer=Executors.newSingleThreadScheduledExecutor(runnable->{Thread thread=new Thread(runnable,"dw-friends-poll");thread.setDaemon(true);return thread;});
        execute(()->{
            try{
                bind(IdentityStore.open(profileBase));if(closed.get()){identity.close();return;}
                status="Создайте соединение или введите его имя и пароль.";
                initialized.complete(identity);identityChanged();refreshGame();publish();
            }catch(Exception failure){
                status="Не удалось открыть защищённый профиль. Проверьте доступ к файлам и отсутствие второй запущенной игры.";
                initialized.completeExceptionally(failure);JsonObject failed=loading();failed.addProperty("status",status);published=failed;
            }
        },null);
        timer.scheduleWithFixedDelay(this::queuePump,1,2,TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::queueConnectionProbe,2,2,TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::queueCatalogHeartbeat,3,3,TimeUnit.SECONDS);
    }
    public CompletableFuture<IdentityStore> ready(){return initialized.copy();}
    public IdentityStore identity(){IdentityStore value=identity;if(value==null)throw new IllegalStateException("Profile not ready");return value;}
    public FriendsStore friends(){FriendsStore value=friends;if(value==null)throw new IllegalStateException("Friends not ready");return value;}
    public InvitationStore invitations(){InvitationStore value=invitations;if(value==null)throw new IllegalStateException("Invitations not ready");return value;}
    public CompletableFuture<Void> terminated(){return terminated.copy();}
    /** Cache-only public node identity; no filesystem read, key access or connection is started here. */
    public IdentityProfile verifiedAuthorityNode()throws IOException {
        AuthorityRoute route=currentAuthorityRoute();
        return route.client().node();
    }
    /** Exact adapter for AuthorityServiceClient.Outbound; the same existing DirectoryClient is reused. */
    public CompletableFuture<JsonObject> authorityRequest(String operation,JsonObject arguments) {
        try {
            if(!"authority".equals(operation)||arguments==null)throw new IOException("Unsupported authority route");
            ProfileFiles.fields(arguments,Set.of("request"),Set.of());
            JsonElement request=arguments.get("request");if(request==null||!request.isJsonObject())throw new IOException("Missing signed authority request");
            return authorityRequest(request.getAsJsonObject());
        }catch(IOException invalid){return CompletableFuture.failedFuture(invalid);}
    }
    /**
     * Direct bounded authority lane, independent of Friends UI/storage work. Cancellation
     * invalidates local completion; the remote coordinator may already have committed a CAS.
     * Never retry a claim with a fresh request merely because its response was canceled/lost.
     */
    public CompletableFuture<JsonObject> authorityRequest(JsonObject alreadySignedInnerRequest) {
        final AuthorityRoute captured;final JsonObject wire;
        try {
            captured=currentAuthorityRoute();
            if(alreadySignedInnerRequest==null||OnlineJson.bytes(alreadySignedInnerRequest).length>98304)throw new IOException("Authority request byte bound");
            wire=alreadySignedInnerRequest.deepCopy();
        }catch(IOException|RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        if(!authoritySlots.tryAcquire())return CompletableFuture.failedFuture(new IOException("Authority route capacity"));
        AuthorityCall call=new AuthorityCall();authorityCalls.add(call);futures.add(call.result);
        call.result.whenComplete((answer,failure)->{
            authorityCalls.remove(call);futures.remove(call.result);authoritySlots.release();
            ScheduledFuture<?> deadline=call.deadline;if(deadline!=null)deadline.cancel(false);
            if(failure!=null){Future<?> work=call.work;if(work!=null)work.cancel(true);CompletableFuture<?> request=call.upstream;if(request!=null)request.cancel(true);}
            authorityWorker.purge();
        });
        try {
            call.deadline=timer.schedule(()->call.result.completeExceptionally(new IOException("Authority route deadline; coordinator outcome may be unknown")),18,TimeUnit.SECONDS);
            call.work=authorityWorker.submit(()->{
                try {
                    checkAuthorityRoute(captured);
                    ProfileFiles.fields(wire,Set.of("schema","purpose","signer","data","signature"),Set.of());
                    if(OnlineJson.integer(wire,"schema",1,1)!=1||!"SERVICE_REQUEST".equals(OnlineJson.string(wire,"purpose",32))
                            ||!wire.has("signer")||!wire.get("signer").isJsonObject()
                            ||!captured.identityId().equals(OnlineJson.string(wire.getAsJsonObject("signer"),"id",64)))
                        throw new IOException("Authority inner signer differs from the active shared identity");
                    JsonObject outer=new JsonObject();outer.add("request",wire);
                    if(call.result.isDone())return;
                    CompletableFuture<JsonObject> upstream=captured.client().request("authority",outer);call.upstream=upstream;
                    if(call.result.isDone()){upstream.cancel(true);return;}
                    upstream.whenComplete((answer,failure)->{
                        if(call.result.isDone())return;
                        try {
                            if(failure!=null)throw failure instanceof CompletionException completion?completion:new CompletionException(failure);
                            checkAuthorityRoute(captured);
                            if(answer==null)throw new IOException("Missing authority node reply");
                            call.result.complete(answer.deepCopy());
                        }catch(Exception invalid){call.result.completeExceptionally(invalid);}
                    });
                }catch(Exception invalid){call.result.completeExceptionally(invalid);}
            });
            if(call.result.isCompletedExceptionally())call.work.cancel(true);
        }catch(RejectedExecutionException stopped){call.result.completeExceptionally(new IOException("Authority route closed or full",stopped));}
        return call.result;
    }
    private AuthorityRoute currentAuthorityRoute()throws IOException {
        AuthorityRoute route=authorityRoute;
        if(route==null)throw new IOException("Registered pinned directory is not ready for authority requests");
        checkAuthorityRoute(route);return route;
    }
    private void checkAuthorityRoute(AuthorityRoute expected)throws IOException {
        AuthorityRoute actual=authorityRoute;
        if(closed.get()||actual==null||identity!=expected.identity()||directory!=expected.client()||generation.get()!=expected.generation()
                ||actual.client()!=expected.client()||actual.identity()!=expected.identity()||actual.generation()!=expected.generation()
                ||!actual.nodeId().equals(expected.nodeId())||!expected.client().connected())
            throw new IOException("Authority route identity, directory or generation changed");
        IdentityProfile node=expected.client().node();
        if(node==null||!expected.nodeId().equals(node.id())||!expected.nodeId().equals(expected.client().pinnedNodeId()))
            throw new IOException("Authority node does not match the durable directory pin");
    }
    private void invalidateAuthorityRoute() {
        authorityRoute=null;
        for(AuthorityCall call:authorityCalls)call.result.completeExceptionally(new IOException("Authority route changed; coordinator outcome may be unknown"));
    }
    public com.dark.winter.online.iroh.IrohProtocolRouter roomRouter(){OnlineRoomNetwork active=room;return closed.get()||active==null?null:active.router();}
    public long generation(){return generation.get();}
    public boolean isReady(){return !closed.get()&&identity!=null&&initialized.isDone()&&!initialized.isCompletedExceptionally();}
    @Override public JsonObject snapshot(){
        JsonObject result=published.deepCopy();result.addProperty("busy",!initialized.isDone()||pending.get()>0);
        if(closed.get()){result.add("capabilities",new JsonObject());result.addProperty("status","Соединения закрыты.");}return result;
    }
    public void refreshGame(){
        if(closed.get()||!refreshQueued.compareAndSet(false,true))return;
        long expected=generation.get();
        try{clientExecutor.execute(()->{
            try{
                if(closed.get()||expected!=generation.get())return;
                JsonObject next=game.snapshot();Set<String> capabilities=game.capabilities();
                gameView=next==null?new JsonObject():next.deepCopy();gameCapabilities=capabilities==null?Set.of():Set.copyOf(capabilities);
                execute(this::publish,null);
            }catch(RuntimeException failure){gameView=new JsonObject();gameCapabilities=Set.of();}
            finally{refreshQueued.set(false);}
        });}catch(RuntimeException rejected){refreshQueued.set(false);}
    }
    @Override public void command(String action,JsonObject args){
        if(closed.get()||identity==null||initialized.isCompletedExceptionally())throw new IllegalStateException("Profile unavailable");
        if(!flag(object(snapshot(),"capabilities"),action,false))throw new IllegalStateException("Action unavailable");
        boolean cancellation=Set.of("online_close","reconnect_cancel").contains(action);
        if(pending.get()>0&&!cancellation)throw new IllegalStateException("Action in progress");
        JsonObject request=args==null?new JsonObject():args.deepCopy();
        char[] password=null;
        if(action.equals("identity_backup")||action.equals("identity_import")||action.equals("connection_create")||action.equals("connection_join")){
            JsonElement secret=request.remove("password");
            if(secret==null&&(action.equals("connection_create")||action.equals("connection_join")))password=new char[0];
            else{
                if(secret==null||!secret.isJsonPrimitive()||!secret.getAsJsonPrimitive().isString()||((action.equals("connection_create")||action.equals("connection_join"))&&secret.getAsString().length()>128))throw new IllegalArgumentException("Missing password");
                password=secret.getAsString().toCharArray();
            }
        }
        char[] consumed=password;long expected=generation.get();pending.incrementAndGet();
        execute(()->{
            boolean delegated=false;
            try{
                check(expected);
                if(action.equals("invite_revoke")&&remoteIssued.containsKey(string(request,"id",""))){delegated=true;delegate(action,request,expected);return;}
                if(action.equals("chat_send")&&!string(request,"channel","").equals("direct")){delegated=true;delegate(action,request,expected);return;}
                if(action.equals("privacy_update")&&string(request,"scope","profile").equals("island")){delegated=true;delegate(action,request,expected);return;}
                if(!LOCAL.contains(action)&&!action.equals("chat_send")){delegated=true;delegate(action,request,expected);return;}
                local(action,request,consumed);publish();
            }catch(Exception failure){
                if(action.equals("room_join")||action.equals("connection_join")||action.equals("connection_open")){
                    RoomFailure diagnostic=diagnoseRoomFailure(failure);
                    roomFailurePhase=diagnostic.phaseCode();roomFailureKind=diagnostic.kindCode();
                    roomDiagnostic=roomFailurePhase+"/"+roomFailureKind;
                    // Never attach the Throwable: native/parser messages may contain input, keys or paths.
                    DarkWinter.LOGGER.warn("Online room join failed: phase={} reason={} {} {} {}",roomFailurePhase,roomFailureKind,roomFailureStats(failure).summary(),roomFailureTransportStats(failure).summary(),roomFailureSocketStats(failure).summary());
                }
                status=action.equals("service_configure")?OnlineServiceDiagnostics.describe(failure):connectionError(action,failure);publish();
            }
            finally{
                if(consumed!=null)Arrays.fill(consumed,'\0');
                if(!delegated){pending.decrementAndGet();publish();queuePump();}
            }
        },()->{if(consumed!=null)Arrays.fill(consumed,'\0');pending.decrementAndGet();});
    }
    private void delegate(String action,JsonObject request,long expected){
        if(!gameCapabilities.contains(action)){status="Это действие недоступно в текущем сеансе.";pending.decrementAndGet();publish();return;}
        try{clientExecutor.execute(()->{
            try{
                check(expected);CompletableFuture<JsonObject> result=game.command(action,request.deepCopy());
                if(result==null)throw new IllegalStateException("Missing game completion");
                result.whenComplete((answer,failure)->execute(()->{
                    try{
                        check(expected);
                        if(failure!=null||answer==null||!flag(answer,"ok",true)){status=gameError(action,failure);return;}
                        if(action.equals("invite_create"))adoptCreatedInvitation(request,answer);
                        if(action.equals("invite_revoke")){remoteIssued.remove(string(request,"id",""));issuedCodes.remove(string(request,"id",""));}
                        // Coordinator replies can contain private chat or bearer invitation data: never echo them.
                        status="Запрос подтверждён. Текущее состояние показано в интерфейсе.";
                        refreshGame();
                    }catch(Exception invalid){status=gameError(action,invalid);}
                    finally{pending.decrementAndGet();publish();}
                },()->pending.decrementAndGet()));
            }catch(Exception failure){execute(()->{if(expected==generation.get())status=gameError(action,failure);pending.decrementAndGet();publish();},()->pending.decrementAndGet());}
        });}catch(RuntimeException rejected){pending.decrementAndGet();status=error(action);publish();}
    }
    private static String gameError(String action,Throwable failure){
        if(action.equals("connect_invite")){
            IslandJoinFailure reason=IslandJoinFailure.find(failure);
            DarkWinter.LOGGER.warn("Online island join failed: reason={}",reason==null?"UNCLASSIFIED":reason.kind().name());
            if(reason!=null)return reason.publicMessage();
        }
        return error(action);
    }
    private void local(String action,JsonObject args,char[] password)throws Exception{
        switch(action){
            case "identity_update"->{
                String nickname=ProfileFiles.string(args,"nickname",128).strip();IdentityProfile.validateNickname(nickname);
                IdentityProfile.Model model=IdentityProfile.Model.valueOf(ProfileFiles.string(args,"model",16).toUpperCase(Locale.ROOT));
                identity.updateProfile(nickname,model,identity.profile().privacy());registered=false;identityChanged();status="Имя и модель сохранены. DarkWinter ID не изменился.";
            }
            case "identity_backup"->{
                Path target=backupPath(ProfileFiles.string(args,"name",128));identity.exportBackup(target,password,FriendsStore::validateExport);status="Защищённая копия сохранена: "+target.getFileName();
            }
            case "identity_import"->importIdentity(backupPath(ProfileFiles.string(args,"name",128)),password);
            case "service_configure"->configure(ProfileFiles.string(args,"endpoint",2048).strip());
            case "room_create"->openRoom(null);
            case "room_join"->openRoom(ProfileFiles.string(args,"code",8192));
            case "room_close","connection_close"->closeRoom();
            case "connection_create"->createConnection(ProfileFiles.string(args,"name",96),password);
            case "connection_join"->{
                if(args.has("name"))joinConnectionByName(ProfileFiles.string(args,"name",96),password);
                else joinConnection(ProfileFiles.string(args,"info",ConnectionInfo.MAX_LENGTH),password);
            }
            case "connection_open"->openConnection(ProfileFiles.string(args,"id",36));
            case "connection_forget"->{String id=ProfileFiles.string(args,"id",36);if(id.equals(activeConnectionId))throw new IOException("Disconnect before forgetting");savedConnections.forget(id);savedConnectionView=savedConnections.list();connectionHealth.remove(id);status="Соединение удалено из сохранённого списка.";}

            case "privacy_update"->privacy(args);
            case "friend_lookup"->{String id=ProfileFiles.string(args,"id",64);IdentityStore.SignedPayload proof=lookupProofNow(id);lookup=profileView(IdentityStore.validateProfileProof(proof));status="Профиль найден и его подпись подтверждена.";}
            case "friend_request"->{IdentityStore.SignedPayload proof=lookupProofNow(ProfileFiles.string(args,"id",64));friends.createRequest(IdentityStore.validateProfileProof(proof));registered=false;status="Заявка сохранена и ожидает подтверждения второй стороны.";}
            case "friend_accept"->{friends.accept(ProfileFiles.string(args,"id",64));status="Принятие заявки сохранено; ожидается подписанное подтверждение второй стороны.";}
            case "friend_reject"->{friends.decline(ProfileFiles.string(args,"id",64));status="Заявка отклонена.";}
            case "friend_remove"->{friends.remove(ProfileFiles.string(args,"id",64));registered=false;status="Дружба прекращена локально; уведомление ожидает доставки.";}
            case "friend_block"->{friends.block(ProfileFiles.string(args,"id",64));registered=false;status="Пользователь заблокирован.";}
            case "friend_unblock"->{friends.unblock(ProfileFiles.string(args,"id",64));registered=false;status="Блокировка снята. Дружба автоматически не восстанавливается.";}
            case "invite_revoke"->{invitations.revoke(ProfileFiles.string(args,"id",36));issuedCodes.remove(string(args,"id",""));status="Приглашение отозвано.";}
            case "invite_reject"->{communications.rejectInvitation(ProfileFiles.string(args,"id",36));status="Приглашение отклонено.";}
            case "chat_send"->sendChat(args);
            default->throw new IOException("Unsupported local action");
        }
    }
    private void bind(IdentityStore opened)throws IOException{
        IdentityStore previous=identity;identity=opened;
        try{
            friends=FriendsStore.open(opened.base(),opened,clock);invitations=InvitationStore.open(opened.base(),opened,clock);
            Path prefs=preferencePath();
            if(Files.exists(prefs,LinkOption.NOFOLLOW_LINKS))preferences=ProfileFiles.read(prefs,1024*1024);
            else{
                preferences=new JsonObject();preferences.addProperty("schema",1);preferences.addProperty("owner",opened.profile().id());preferences.addProperty("endpoint","");
                preferences.add("nodePins",new JsonObject());preferences.addProperty("status","online");preferences.addProperty("history",false);preferences.addProperty("relayOnly",false);savePreferences();
            }
            ProfileFiles.fields(preferences,Set.of("schema","owner","endpoint","nodePins","status","history","relayOnly"),Set.of());
            if(ProfileFiles.number(preferences,"schema")!=1||!ProfileFiles.string(preferences,"owner",64).equals(opened.profile().id()))throw new IOException("Unknown preferences format");
            String endpoint=ProfileFiles.string(preferences,"endpoint",2048);if(!endpoint.isEmpty())DirectoryClient.validate(URI.create(endpoint));
            String mode=ProfileFiles.string(preferences,"status",24);if(!Set.of("online","busy","invisible").contains(mode))throw new IOException("Invalid presence mode");
            communications=new OnlineMessageStore(opened,clock,flag(preferences,"history",false));
            connectionRelayOnly=flag(preferences,"relayOnly",false);
            savedConnections=SavedConnections.open(opened);savedConnectionView=savedConnections.list();connectionHealth.clear();activeConnectionId="";connectionMembers=new JsonArray();connectionMembersAt=0;
            connected=false;registered=false;lastAck=0;lastRegister=0;presence.clear();lastSent.clear();lookup=new JsonObject();issuedCodes.clear();remoteIssued.clear();
        }catch(IOException|RuntimeException failure){identity=previous;opened.close();throw failure;}
    }
    private Path preferencePath(){return identity.base().resolve("preferences-"+identity.profile().id()+".json");}
    private void savePreferences()throws IOException{ProfileFiles.atomic(preferencePath(),preferences,true);}
    private Path backupPath(String name)throws IOException{
        if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")||name.contains(".."))throw new IOException("Invalid backup name");
        Path base=ProfileFiles.directory(identity.base().resolve("backups")),target=base.resolve(name).normalize();
        if(!target.getParent().equals(base)||Files.isSymbolicLink(target))throw new IOException("Invalid backup path");return target;
    }
    private void importIdentity(Path source,char[] password)throws Exception{
        if(flag(object(gameView,"session"),"active",false))throw new IOException("Close the session before replacing identity");
        JsonObject originalBundle=ProfileFiles.read(identity.base().resolve("profile.json"),8*1024*1024);
        Path rollback=backupPath("before-import-"+UUID.randomUUID()+".dwidentity");
        identity.exportBackup(rollback,password.clone(),FriendsStore::validateExport);
        stopCatalogHosting();cancelConnectionProbe();generation.incrementAndGet();closeDirectory();OnlineRoomNetwork previousRoom=room;room=null;if(previousRoom!=null)previousRoom.close();IdentityStore old=identity;old.close();
        try{bind(IdentityStore.importBackup(profileBase,source,password,true,FriendsStore::validateExport));status="Профиль восстановлен. Подключения и приглашения требуют новой проверки.";}
        catch(Exception failure){
            IdentityStore current=identity;if(current!=null)try{current.close();}catch(IOException closeFailure){failure.addSuppressed(closeFailure);}
            ProfileFiles.atomic(profileBase.resolve("profile.json"),originalBundle,true);
            bind(IdentityStore.open(profileBase));throw failure;
        }
        finally{identityChanged();refreshGame();}
    }
    enum RoomPhase { BOOTSTRAP, PRECONDITION, ENDPOINT_OPEN, RELAY_ONLINE, ROUTE_OPEN, VERIFY_NODE, CONNECT_PEER, OPEN_STREAM, PIPE_NODE, UNKNOWN }
    enum RoomKind { INVALID_CODE, CODE_CLOCK, OWN_ROOM, BOOTSTRAP_REJECTED, TIMEOUT, CANCELLED, CAPACITY,
        TRANSPORT_NATIVE, TRANSPORT_ABI, INVALID_ARGUMENT, NATIVE_LOAD, LOG_INIT, KEY_STORAGE, SERVICE_BIND,
        NODE_ADDRESS, NODE_HTTPS, NODE_CHANGED, NODE_CLOCK_SKEW, NODE_REJECTED, NODE_INVALID_RESPONSE, IO, OTHER }
    record RoomFailure(RoomPhase phase,RoomKind kind){
        String phaseCode(){return phase.name().toLowerCase(Locale.ROOT);}
        String kindCode(){return kind.name().toLowerCase(Locale.ROOT);}
    }
    /** Bounded enum-only diagnostic surface. Throwable text, class names and causes never enter snapshots/logs. */
    static RoomFailure diagnoseRoomFailure(Throwable failure){
        List<Throwable> chain=new ArrayList<>(12);
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&chain.size()<12&&seen.add(current);){
            chain.add(current);
            try{current=current.getCause();}catch(RuntimeException unreadable){break;}
        }
        RoomPhase phase=RoomPhase.PRECONDITION;
        for(Throwable current:chain)if(current instanceof OnlineRoomNetwork.Failure room){
            phase=roomPhase(room.phase());
            if(room.code()==OnlineRoomNetwork.Code.OWN_ROOM)return new RoomFailure(phase,RoomKind.OWN_ROOM);
            break;
        }
        for(Throwable current:chain){
            if(current instanceof RoomConnectionCode.Failure code)
                return new RoomFailure(RoomPhase.BOOTSTRAP,code.code()==RoomConnectionCode.Code.CLOCK?RoomKind.CODE_CLOCK:RoomKind.INVALID_CODE);
            if(current instanceof IrohEndpointService.Failure endpoint){
                if(endpoint.transportCode()!=null)return new RoomFailure(phase,irohKind(endpoint.transportCode()));
                return new RoomFailure(phase,switch(endpoint.code()){
                    case NATIVE_LOAD->RoomKind.NATIVE_LOAD;case LOG_INIT->RoomKind.LOG_INIT;
                    case KEY_STORAGE->RoomKind.KEY_STORAGE;case SERVICE_BIND->RoomKind.SERVICE_BIND;
                });
            }
            if(current instanceof IrohAsync.Failure transport)return new RoomFailure(phase,irohKind(transport.code()));
            if(current instanceof DirectoryClient.Failure directory)return new RoomFailure(phase,switch(directory.kind()){
                case INVALID_ADDRESS->RoomKind.NODE_ADDRESS;case HTTPS_REQUIRED->RoomKind.NODE_HTTPS;
                case NODE_CHANGED->RoomKind.NODE_CHANGED;case CLOCK_SKEW->RoomKind.NODE_CLOCK_SKEW;
                case REJECTED->RoomKind.NODE_REJECTED;case INVALID_RESPONSE->RoomKind.NODE_INVALID_RESPONSE;
            });
        }
        for(Throwable current:chain){
            if(current instanceof TimeoutException||current instanceof java.net.http.HttpTimeoutException)return new RoomFailure(phase,RoomKind.TIMEOUT);
            if(current instanceof CancellationException||current instanceof InterruptedException)return new RoomFailure(phase,RoomKind.CANCELLED);
        }
        if(phase==RoomPhase.BOOTSTRAP)return new RoomFailure(phase,RoomKind.BOOTSTRAP_REJECTED);
        for(Throwable current:chain){
            if(current instanceof IllegalArgumentException)return new RoomFailure(phase,RoomKind.INVALID_ARGUMENT);
            if(current instanceof IOException)return new RoomFailure(phase,RoomKind.IO);
        }
        return new RoomFailure(phase,RoomKind.OTHER);
    }
    static IrohNodeRoute.Diagnostics roomFailureStats(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.size()<12&&seen.add(current);){
            if(current instanceof OnlineRoomNetwork.Failure room)return room.diagnostics();
            try{current=current.getCause();}catch(Throwable unreadable){break;}
        }
        return IrohNodeRoute.Diagnostics.empty();
    }
    static IrohNodeRoute.TransportStats roomFailureTransportStats(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.size()<12&&seen.add(current);){
            if(current instanceof OnlineRoomNetwork.Failure room)return room.transportStats();
            try{current=current.getCause();}catch(Throwable unreadable){break;}
        }
        return IrohNodeRoute.TransportStats.empty();
    }
    static IrohEndpointService.SocketStats roomFailureSocketStats(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.size()<12&&seen.add(current);){
            if(current instanceof OnlineRoomNetwork.Failure room)return room.socketStats();
            try{current=current.getCause();}catch(Throwable unreadable){break;}
        }
        return IrohEndpointService.SocketStats.empty();
    }
    private static RoomKind irohKind(IrohAsync.Code code){
        return switch(code){
            case INVALID_ARGUMENT->RoomKind.INVALID_ARGUMENT;case CAPACITY->RoomKind.CAPACITY;
            case TIMEOUT->RoomKind.TIMEOUT;case CANCELLED->RoomKind.CANCELLED;
            case NATIVE->RoomKind.TRANSPORT_NATIVE;case ABI->RoomKind.TRANSPORT_ABI;
        };
    }
    private static RoomPhase roomPhase(String value){
        if(value==null||value.length()>128)return RoomPhase.UNKNOWN;
        int slash=value.indexOf('/');String primary=slash<0?value:value.substring(0,slash);
        RoomPhase phase=switch(primary){
            case "bootstrap_rejected"->RoomPhase.BOOTSTRAP;case "endpoint_open"->RoomPhase.ENDPOINT_OPEN;
            case "relay_online"->RoomPhase.RELAY_ONLINE;case "route_open"->RoomPhase.ROUTE_OPEN;
            case "verify_node"->RoomPhase.VERIFY_NODE;default->RoomPhase.UNKNOWN;
        };
        if(phase!=RoomPhase.VERIFY_NODE||slash<0)return phase;
        String route=value.substring(slash+1);int colon=route.indexOf(':');if(colon>=0)route=route.substring(0,colon);
        return switch(route){
            case "connecting_peer"->RoomPhase.CONNECT_PEER;case "opening_stream"->RoomPhase.OPEN_STREAM;
            case "piping_node"->RoomPhase.PIPE_NODE;case "idle"->phase;default->RoomPhase.UNKNOWN;
        };
    }
    private void clearRoomFailure(){roomDiagnostic=roomFailurePhase=roomFailureKind="";}
    private void requireNetworkIdle()throws IOException{
        if(flag(object(gameView,"session"),"active",false))throw new IOException("Close the active island session before changing its service route");
    }
    private String serviceEndpoint(){OnlineRoomNetwork active=room;return active==null?string(preferences,"endpoint",""):active.endpoint().toASCIIString();}
    private String servicePinKey(String endpoint){OnlineRoomNetwork active=room;return active==null?endpoint:"room:"+active.routeId();}
    private void openRoom(String code)throws Exception{
        requireBuiltinConnectionMode();clearRoomFailure();cancelConnectionProbe();
        requireNetworkIdle();if(room!=null)throw new IOException("Close the current network room first");
        long expected=generation.get();OnlineRoomNetwork opened=code==null?OnlineRoomNetwork.create(identity):OnlineRoomNetwork.join(identity,code);
        boolean adopted=false;
        try{
            check(expected);requireNetworkIdle();
            String pinKey="room:"+opened.routeId();JsonObject pins=preferences.getAsJsonObject("nodePins");
            String previous=string(pins,pinKey,"");
            if(!previous.isEmpty()&&!previous.equals(opened.nodeId()))throw new IOException("Known room Node identity changed");
            if(!pins.has(pinKey)){
                if(pins.size()>=512)throw new IOException("Service route pin capacity");
                JsonObject old=pins.deepCopy();pins.addProperty(pinKey,opened.nodeId());
                try{savePreferences();}catch(IOException failure){preferences.add("nodePins",old);throw failure;}
            }
            room=opened;adopted=true;generation.incrementAndGet();closeDirectory();
            serviceReason="Проверяется подключение к общей службе через встроенный транспорт.";
            status=code==null?"Сеть создана. Передайте код второму участнику.":"Сеть подключена. Можно найти профиль и отправить заявку в друзья.";
            refreshGame();
        }finally{if(!adopted)opened.close();}
    }
    private void createConnection(String name,char[] password)throws Exception{
        requireBuiltinConnectionMode();clearRoomFailure();requireNetworkIdle();if(room!=null)throw new IOException("Disconnect the current connection first");
        ConnectionInfo.validateName(name);
        OnlineRoomNetwork opened=OnlineRoomNetwork.createConnection(identity,name,password,takeConnectionProbeEndpoint());
        adoptCatalogConnection(opened,true);
    }
    private void joinConnectionByName(String name,char[] password)throws Exception{
        requireBuiltinConnectionMode();clearRoomFailure();requireNetworkIdle();if(room!=null)throw new IOException("Disconnect the current connection first");
        if(connectionCatalog==null)throw new IOException("Connection catalog is not configured");
        ConnectionCatalogClient.Result resolved=await(connectionCatalog.resolve(identity,name));
        ConnectionInfo.Created opened=resolved.open(password);
        SavedConnections.Entry saved=new SavedConnections.Entry(opened.descriptor(),opened.shareInfo(),opened.passwordRequired(),false);
        // A human name must never replace a previously verified owner, island coordinator or route.
        for(SavedConnections.Entry previous:savedConnections.list()){
            if(!ConnectionCatalogClient.canonicalName(previous.descriptor().name()).equals(ConnectionCatalogClient.canonicalName(saved.descriptor().name())))continue;
            if(!previous.id().equals(saved.id())||!previous.descriptor().ownerId().equals(saved.descriptor().ownerId())
                    ||!previous.descriptor().nodeId().equals(saved.descriptor().nodeId()))
                throw new IOException("Known named connection identity changed");
        }
        adoptConnection(OnlineRoomNetwork.openConnection(identity,saved,takeConnectionProbeEndpoint()),true);
    }
    private void joinConnection(String info,char[] password)throws Exception{
        requireBuiltinConnectionMode();clearRoomFailure();requireNetworkIdle();if(room!=null)throw new IOException("Disconnect the current connection first");
        ConnectionInfo.Created opened=ConnectionInfo.open(info,password);
        SavedConnections.Entry saved=new SavedConnections.Entry(opened.descriptor(),opened.shareInfo(),opened.passwordRequired(),false);
        adoptConnection(OnlineRoomNetwork.openConnection(identity,saved,takeConnectionProbeEndpoint()),true);
    }
    private void openConnection(String id)throws Exception{
        requireBuiltinConnectionMode();clearRoomFailure();requireNetworkIdle();if(room!=null)throw new IOException("Disconnect the current connection first");
        SavedConnections.Entry saved=savedConnections.get(id);
        adoptCatalogConnection(OnlineRoomNetwork.openConnection(identity,saved,takeConnectionProbeEndpoint()),false);
    }
    private void adoptCatalogConnection(OnlineRoomNetwork opened,boolean remember)throws Exception{
        SavedConnections.Entry saved=opened.savedConnection();boolean published=false,adopted=false;
        try{
            String warning="";
            if(connectionCatalog!=null&&saved!=null&&saved.hosting()){
                try{await(connectionCatalog.publish(identity,saved));published=true;}
                catch(Exception failure){
                    // Existing verified routes remain usable even if global discovery is unavailable or a legacy name is occupied.
                    if(remember)throw failure;
                    warning=connectionError("connection_open",failure);
                }
            }
            adoptConnection(opened,remember);adopted=true;
            if(published){catalogHosting=new CatalogHosting(identity,saved);catalogHeartbeatAt.set(clock.millis());}
            else if(!warning.isEmpty())status="Соединение включено для сохранённых участников. Имя не опубликовано. "+warning;
        }finally{
            if(!adopted){
                opened.close();
                if(connectionCatalog!=null&&saved!=null&&saved.hosting())unpublishCatalog(new CatalogHosting(identity,saved));
            }
        }
    }
    /** Separate asynchronous lane: a slow catalog never stalls the local node heartbeat or block edits. */
    private void queueCatalogHeartbeat(){
        CatalogHosting hosting=catalogHosting;long now=clock.millis();
        if(closed.get()||hosting==null||connectionCatalog==null||now-catalogHeartbeatAt.get()<15000
                ||!catalogHeartbeatPending.compareAndSet(false,true))return;
        catalogHeartbeatAt.set(now);
        try{
            connectionCatalog.heartbeat(hosting.identity(),hosting.saved().descriptor().name(),hosting.saved().descriptor().id())
                    .whenComplete((answer,failure)->catalogHeartbeatPending.set(false));
        }catch(RuntimeException rejected){catalogHeartbeatPending.set(false);}
    }
    private void unpublishCatalog(CatalogHosting hosting){
        if(connectionCatalog==null||hosting==null)return;
        try{catalogStopping=connectionCatalog.unpublish(hosting.identity(),hosting.saved().descriptor().name(),hosting.saved().descriptor().id())
                .exceptionally(failure->null);}catch(RuntimeException ignored){}
    }
    private void stopCatalogHosting(){
        CatalogHosting previous=catalogHosting;catalogHosting=null;unpublishCatalog(previous);
    }
    private void requireBuiltinConnectionMode()throws IOException{
        if(connectionRelayOnly)throw new IOException(STRICT_CONNECTION_REASON);
    }
    private void adoptConnection(OnlineRoomNetwork opened,boolean remember)throws Exception{
        boolean adopted=false;
        try{
            requireNetworkIdle();if(closed.get()||room!=null)throw new IOException("Connection request changed");
            var saved=opened.savedConnection();if(saved==null)throw new IOException("Missing persistent connection");
            String pinKey="room:"+opened.routeId();JsonObject pins=preferences.getAsJsonObject("nodePins");
            String previous=string(pins,pinKey,"");if(!previous.isEmpty()&&!previous.equals(opened.nodeId()))throw new IOException("Known connection Node identity changed");
            if(!pins.has(pinKey)){
                if(pins.size()>=512)throw new IOException("Service route pin capacity");
                JsonObject before=pins.deepCopy();pins.addProperty(pinKey,opened.nodeId());
                try{savePreferences();}catch(IOException failure){preferences.add("nodePins",before);throw failure;}
            }
            if(remember)savedConnections.remember(saved);
            savedConnectionView=savedConnections.list();
            room=opened;activeConnectionId=saved.id();connectionMembers=new JsonArray();connectionMembersAt=0;adopted=true;
            generation.incrementAndGet();closeDirectory();
            serviceReason="Проверяется соединение.";status="Соединение сохранено. Проверяются подключённые участники.";refreshGame();
        }finally{if(!adopted)opened.close();}
    }
    private void refreshConnectionMembers()throws Exception{
        if(activeConnectionId.isEmpty())return;
        JsonObject response=await(directory.request("connection_members",new JsonObject()));
        connectionMembers=validatedConnectionMembers(response);connectionMembersAt=clock.millis();
    }
    private JsonArray validatedConnectionMembers(JsonObject response)throws IOException{
        JsonArray members=array(response,"members"),validated=new JsonArray();
        if(members.size()>128)throw new IOException("Connection member bound");
        for(JsonElement value:members){
            if(!value.isJsonObject())throw new IOException("Invalid connection member");
            JsonObject member=value.getAsJsonObject();String id=ProfileFiles.string(member,"id",64),name=ProfileFiles.string(member,"name",128);
            OnlineMessageStore.peer(id);IdentityProfile.validateNickname(name);
            if(!flag(member,"online",false))continue;
            JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("name",name);row.addProperty("online",true);
            row.addProperty("self",identity.profile().id().equals(id));validated.add(row);
        }
        return validated;
    }
    private JsonArray connectionRows(){
        JsonArray rows=new JsonArray();long now=clock.millis();OnlineRoomNetwork live=room;
        for(SavedConnections.Entry saved:savedConnections.list()){
            boolean active=live!=null&&saved.id().equals(activeConnectionId);
            boolean reachable=active&&connected&&directory!=null&&directory.connected()&&connectionMembersAt>0&&now>=connectionMembersAt&&now-connectionMembersAt<=45000;
            ConnectionHealth checked=connectionHealth.get(saved.id());boolean fresh=checked!=null&&now>=checked.checkedAt()&&now-checked.checkedAt()<=45000;
            if(!active)reachable=fresh&&checked.reachable();
            if(connectionRelayOnly)reachable=false;
            JsonObject row=new JsonObject();row.addProperty("id",saved.id());row.addProperty("name",saved.descriptor().name());
            row.addProperty("active",active);row.addProperty("hosting",saved.hosting());row.addProperty("reachable",reachable);
            row.addProperty("passwordRequired",saved.passwordRequired());row.addProperty("shareInfo",saved.shareInfo());
            row.addProperty("status",connectionRelayOnly?"Недоступно в режиме «Только посредник»":reachable?(active&&saved.hosting()&&connectionCatalog!=null&&catalogHosting==null?"Включено; имя не опубликовано":"Соединение доступно"):active?"Проверяется связь":saved.hosting()?"Выключено":fresh?"Недоступно":"Проверяется доступность");
            row.add("members",reachable?(active?connectionMembers.deepCopy():checked.members().deepCopy()):new JsonArray());rows.add(row);
        }
        return rows;
    }
    /** Independent bounded lane: a slow offline peer cannot block the active directory heartbeat. */
    private void queueConnectionProbe(){
        if(closed.get()||connectionRelayOnly||identity==null||!initialized.isDone()||initialized.isCompletedExceptionally()||pending.get()>0)return;
        Object token=new Object();if(!connectionProbeToken.compareAndSet(null,token))return;
        long expected=connectionProbeGeneration.get(),runtimeGeneration=generation.get(),now=clock.millis();
        SavedConnections.Entry candidate=null;long oldest=Long.MAX_VALUE;OnlineRoomNetwork live=room;
        for(var saved:savedConnectionView){
            if(saved.hosting()||live!=null&&saved.id().equals(live.routeId().toString()))continue;
            ConnectionHealth health=connectionHealth.get(saved.id());long at=health==null?0:health.checkedAt();
            if((at==0||now>=at&&now-at>=20000)&&at<oldest){candidate=saved;oldest=at;}
        }
        if(candidate==null){connectionProbeToken.compareAndSet(token,null);return;}
        SavedConnections.Entry selected=candidate;
        try{connectionProbeJob=connectionProbeWorker.submit(()->{
            try{
                if(!probeCurrent(expected,runtimeGeneration))return;
                OnlineRoomNetwork active=room;IrohEndpointService endpoint=active==null?idleProbeEndpoint(expected,runtimeGeneration):active.transport();
                if(endpoint==null||!probeCurrent(expected,runtimeGeneration))return;
                JsonObject answer=OnlineRoomNetwork.probeConnection(identity,selected,endpoint);
                JsonArray members=validatedConnectionMembers(answer);
                if(probeSavedCurrent(expected,runtimeGeneration,selected.id()))connectionHealth.put(selected.id(),new ConnectionHealth(true,clock.millis(),members));
            }catch(Exception unavailable){
                if(probeCurrent(expected,runtimeGeneration))connectionHealth.put(selected.id(),new ConnectionHealth(false,clock.millis(),new JsonArray()));
            }finally{
                connectionProbeToken.compareAndSet(token,null);execute(this::publish,null);
            }
        });}catch(RejectedExecutionException stopped){connectionProbeToken.compareAndSet(token,null);}
    }
    private boolean probeCurrent(long expected,long runtimeGeneration){
        return !closed.get()&&!connectionRelayOnly&&expected==connectionProbeGeneration.get()&&runtimeGeneration==generation.get()&&pending.get()==0&&!Thread.currentThread().isInterrupted();
    }
    private boolean probeSavedCurrent(long expected,long runtimeGeneration,String id){
        return probeCurrent(expected,runtimeGeneration)&&savedConnectionView.stream().anyMatch(saved->saved.id().equals(id));
    }
    private IrohEndpointService idleProbeEndpoint(long expected,long runtimeGeneration)throws Exception{
        CompletableFuture<IrohEndpointService> opening;
        synchronized(connectionProbeLock){
            if(!probeCurrent(expected,runtimeGeneration))return null;
            if(connectionProbeEndpoint!=null)return connectionProbeEndpoint;
            opening=OnlineRoomNetwork.openProbeTransport(identity);connectionProbeOpening=opening;
        }
        IrohEndpointService endpoint=null;
        try{
            endpoint=opening.get(27,TimeUnit.SECONDS);
            synchronized(connectionProbeLock){
                // Explicit connect may take ownership of this in-flight bind; never close its new endpoint here.
                if(connectionProbeOpening!=opening)return null;
                connectionProbeOpening=null;
                if(!probeCurrent(expected,runtimeGeneration)){endpoint.close();return null;}
                connectionProbeEndpoint=endpoint;
            }
            endpoint.online(java.time.Duration.ofSeconds(25)).get(27,TimeUnit.SECONDS);return endpoint;
        }catch(Exception failure){
            synchronized(connectionProbeLock){
                if(connectionProbeOpening==opening){connectionProbeOpening=null;opening.cancel(true);}
                if(endpoint!=null&&connectionProbeEndpoint==endpoint){connectionProbeEndpoint=null;endpoint.close();}
            }
            throw failure;
        }
    }
    /** Transfer one existing bind into the explicit connection, instead of binding the same transport identity twice. */
    private IrohEndpointService takeConnectionProbeEndpoint()throws Exception{
        CompletableFuture<IrohEndpointService> opening;IrohEndpointService endpoint;
        synchronized(connectionProbeLock){
            connectionProbeGeneration.incrementAndGet();connectionProbeToken.set(null);
            Future<?> job=connectionProbeJob;connectionProbeJob=null;if(job!=null)job.cancel(true);
            opening=connectionProbeOpening;connectionProbeOpening=null;
            endpoint=connectionProbeEndpoint;connectionProbeEndpoint=null;
        }
        if(endpoint!=null)return endpoint;
        if(opening==null)return null;
        try{
            endpoint=opening.get(27,TimeUnit.SECONDS);
            if(closed.get()){endpoint.close();throw new IOException("Connection transport closed");}
            return endpoint;
        }catch(Exception failure){
            opening.whenComplete((late,error)->{if(late!=null)late.close();});opening.cancel(true);throw failure;
        }
    }
    private void cancelConnectionProbe(){
        synchronized(connectionProbeLock){
            connectionProbeGeneration.incrementAndGet();connectionProbeToken.set(null);
            CompletableFuture<IrohEndpointService> opening=connectionProbeOpening;connectionProbeOpening=null;if(opening!=null)opening.cancel(true);
            Future<?> job=connectionProbeJob;connectionProbeJob=null;if(job!=null)job.cancel(true);
            IrohEndpointService endpoint=connectionProbeEndpoint;connectionProbeEndpoint=null;if(endpoint!=null)endpoint.close();
        }
    }
    private void closeRoom()throws IOException{
        clearRoomFailure();cancelConnectionProbe();
        requireNetworkIdle();stopCatalogHosting();OnlineRoomNetwork previous=room;room=null;activeConnectionId="";connectionMembers=new JsonArray();connectionMembersAt=0;
        generation.incrementAndGet();closeDirectory();if(previous!=null)previous.close();
        serviceReason=serviceEndpoint().isEmpty()?"Создайте соединение или подключитесь по данным от друга.":"Восстанавливается заданная служба.";
        status="Соединение выключено. Сохранённый список остался.";refreshGame();
    }
    private void configure(String requested)throws IOException{
        requireNetworkIdle();stopCatalogHosting();cancelConnectionProbe();activeConnectionId="";connectionMembers=new JsonArray();connectionMembersAt=0;
        String endpoint=requested.isEmpty()?"":DirectoryClient.validate(URI.create(requested)).toASCIIString();
        OnlineRoomNetwork previous=room;room=null;if(previous!=null)previous.close();
        preferences.addProperty("endpoint",endpoint);savePreferences();generation.incrementAndGet();closeDirectory();
        serviceReason=endpoint.isEmpty()?"Адрес службы не настроен. Локальные данные доступны.":"Подключение к заданной службе ещё не подтверждено.";
        status=endpoint.isEmpty()?"Служба отключена. Локальный профиль и друзья сохранены.":"Адрес сохранён. Выполняется проверка связи.";refreshGame();
    }
    private void privacy(JsonObject args)throws IOException{
        if(!ProfileFiles.string(args,"scope",16).equals("profile"))throw new IOException("Wrong privacy scope");
        String key=ProfileFiles.string(args,"key",32);JsonElement requested=args.get("value");if(requested==null||!requested.isJsonPrimitive())throw new IOException("Missing privacy value");
        IdentityProfile.Privacy old=identity.profile().privacy();
        if(Set.of("presence","requests","messages","invitations").contains(key)){
            String value=requested.getAsString();IdentityProfile.Visibility presence=old.presence();
            IdentityProfile.ContactPolicy requests=old.requests(),messages=old.messages(),invites=old.invitations();
            if(key.equals("presence"))presence=switch(value){case "nobody"->IdentityProfile.Visibility.NOBODY;case "friends"->IdentityProfile.Visibility.FRIENDS;case "everyone"->IdentityProfile.Visibility.PUBLIC;default->throw new IOException("Invalid visibility");};
            else{
                IdentityProfile.ContactPolicy policy=switch(value){case "nobody"->IdentityProfile.ContactPolicy.NOBODY;case "friends"->IdentityProfile.ContactPolicy.FRIENDS;case "everyone"->IdentityProfile.ContactPolicy.ANYONE;default->throw new IOException("Invalid contact policy");};
                switch(key){case "requests"->requests=policy;case "messages"->messages=policy;case "invitations"->invites=policy;default->throw new IOException("Invalid privacy key");}
            }
            identity.updateProfile(identity.profile().nickname(),identity.profile().model(),new IdentityProfile.Privacy(presence,requests,messages,invites));registered=false;
        }else if(key.equals("status")){
            String value=requested.getAsString();if(!Set.of("online","busy","invisible").contains(value))throw new IOException("Invalid presence status");
            preferences.addProperty("status",value);savePreferences();registered=false;
        }else if(key.equals("history")||key.equals("relayOnly")){
            if(!requested.getAsJsonPrimitive().isBoolean())throw new IOException("Expected boolean");
            if(key.equals("relayOnly")&&flag(object(gameView,"session"),"active",false))throw new IOException("Close the active session before changing its network privacy policy");
            if(key.equals("relayOnly")&&requested.getAsBoolean()&&room!=null)throw new IOException("Disconnect the active built-in connection before enabling relay-only privacy");
            JsonElement previous=preferences.get(key).deepCopy();preferences.addProperty(key,requested.getAsBoolean());
            try{savePreferences();}catch(IOException failure){preferences.add(key,previous);throw failure;}
            if(key.equals("history"))communications.history(requested.getAsBoolean());
            else{connectionRelayOnly=requested.getAsBoolean();if(connectionRelayOnly)cancelConnectionProbe();}
            refreshGame();
        }else throw new IOException("Unknown privacy key");
        status="Настройки приватности сохранены.";
    }
    private void identityChanged(){
        IdentityStore current=identity;long expected=generation.get();
        try{clientExecutor.execute(()->{if(!closed.get()&&expected==generation.get())game.identityChanged(current);});}catch(RuntimeException ignored){}
    }
    private void queuePump(){
        if(closed.get()||!initialized.isDone()||initialized.isCompletedExceptionally()||!pumpQueued.compareAndSet(false,true))return;
        execute(()->{
            try{refreshGame();pump();}
            catch(Exception failure){invalidateAuthorityRoute();connected=false;registered=false;lastAck=0;serviceReason=OnlineServiceDiagnostics.describe(failure);presence.clear();}
            finally{pumpQueued.set(false);publish();}
        },()->pumpQueued.set(false));
    }
    private void ensureDirectory()throws Exception{
        long expected=generation.get();check(expected);
        String endpoint=serviceEndpoint();if(endpoint.isEmpty())throw new IOException("Service not configured");
        String pinKey=servicePinKey(endpoint);
        if(directory==null){
            String pin=string(object(preferences,"nodePins"),pinKey,"");
            OnlineRoomNetwork active=room;if(active!=null&&!active.nodeId().equals(pin))throw new IOException("Room Node pin is not durable");
            directory=new DirectoryClient(URI.create(endpoint),identity,clock,pin);registered=false;lastAck=0;
        }
        if(!registered||clock.millis()-lastRegister>20_000){
            JsonObject register=new JsonObject();register.addProperty("status",presenceMode());JsonArray audience=new JsonArray(),blocked=new JsonArray();
            for(FriendsStore.PeerView peer:friends.snapshot())if(peer.blocked())blocked.add(peer.profile().id());else if(peer.state()==FriendsStore.State.CONFIRMED)audience.add(peer.profile().id());
            for(JsonElement id:friends.exportState().getAsJsonArray("blocks"))if(!blocked.contains(id))blocked.add(id.deepCopy());
            register.add("audience",audience);register.add("blocked",blocked);register.add("profileProof",OnlineJson.GSON.toJsonTree(identity.publicProof()));
            JsonObject response=await(directory.request("register",register));check(expected);observeMailbox(response);registered=true;lastRegister=clock.millis();connected=true;serviceReason="";
            String pinned=directory.pinnedNodeId();JsonObject pins=preferences.getAsJsonObject("nodePins");
            if(!pinned.equals(string(pins,pinKey,""))){
                JsonObject previousPins=pins.deepCopy();pins.addProperty(pinKey,pinned);
                try{savePreferences();}catch(IOException failed){preferences.add("nodePins",previousPins);throw failed;}
            }
            check(expected);DirectoryClient current=directory;IdentityProfile node=current==null?null:current.node();
            if(current==null||node==null||!current.connected()||!pinned.equals(node.id())||!pinned.equals(string(pins,pinKey,"")))
                throw new IOException("Registered directory pin is not durable/verified");
            authorityRoute=new AuthorityRoute(current,identity,expected,identity.profile().id(),pinned);
        }
    }
    private void pump()throws Exception{
        if(serviceEndpoint().isEmpty()){connected=false;serviceReason="Адрес службы не настроен. Локальный профиль и друзья доступны.";return;}
        ensureDirectory();long expected=generation.get();
        JsonObject poll=new JsonObject();poll.addProperty("ack",lastAck);poll.addProperty("status",presenceMode());
        JsonObject result=await(directory.request("poll",poll));check(expected);observeMailbox(result);connected=true;serviceReason="";presence.clear();
        for(JsonElement value:array(result,"presence"))if(value.isJsonObject()){
            JsonObject row=value.getAsJsonObject();String id=ProfileFiles.string(row,"id",64),state=ProfileFiles.string(row,"status",24);OnlineMessageStore.peer(id);
            if(Set.of("online","on_island","busy","invisible","offline").contains(state))presence.put(id,state);
        }
        for(JsonElement value:array(result,"messages")){
            JsonObject row=value.getAsJsonObject();long sequence=ProfileFiles.number(row,"sequence");
            if(sequence<=lastAck)continue;
            try{receiveMail(ProfileFiles.string(row,"from",64),ProfileFiles.string(row,"kind",16),row.getAsJsonObject("message"));check(expected);lastAck=sequence;}
            catch(Exception failure){
                status="Входящее сообщение не принято. Оно не подтверждено и остаётся для повторной проверки.";
                notifyOnce("rejected:"+string(row,"from","")+":"+sequence,"Не принято сообщение от ID "+string(row,"from","")+". Оно сохранено службой для повторной проверки.");break;
            }
        }
        refreshConnectionMembers();flush(expected);
    }
    private void observeMailbox(JsonObject response)throws IOException{
        String epoch=string(response,"mailboxEpoch","");
        if(!epoch.isBlank()){
            OnlineMessageStore.uuid(epoch);
            if(!mailboxEpoch.equals(epoch)){lastAck=0;mailboxEpoch=epoch;}
        }
    }
    private void flush(long expected)throws Exception{
        int sent=0;long started=System.nanoTime(),now=clock.millis();
        for(FriendsStore.SignedFriendMessage message:friends.outbox()){
            FriendsStore.Body body=FriendsStore.inspect(message);if(now-lastSent.getOrDefault(body.id(),0L)<6000)continue;
            check(expected);
            try{nodeSend(body.to(),"friend",body.id(),OnlineJson.GSON.toJsonTree(message).getAsJsonObject());}
            catch(Exception failure){status="Часть сообщений друзьям не подтверждена. Сохранённая очередь будет повторена.";}
            lastSent.put(body.id(),clock.millis());
            if(++sent>=6||System.nanoTime()-started>5_000_000_000L)return;
        }
        for(OnlineMessageStore.Queued queued:communications.outbox()){
            if(queued.expiresAt()<=now){communications.failed(queued.id(),"Срок доставки истёк.");continue;}
            if(now-lastSent.getOrDefault(queued.id(),0L)<6000)continue;
            check(expected);
            try{JsonObject accepted=nodeSend(queued.recipient(),queued.kind(),queued.id(),queued.wire());communications.queued(queued.id(),flag(accepted,"acknowledged",false));}
            catch(Exception failure){communications.failed(queued.id(),"Доставка не подтверждена; сообщение сохранено для повтора.");status="Часть сообщений не подтверждена и остаётся в очереди.";}
            lastSent.put(queued.id(),clock.millis());
            if(++sent>=6||System.nanoTime()-started>5_000_000_000L)return;
        }
    }
    private JsonObject nodeSend(String recipient,String kind,String id,JsonObject wire)throws Exception{
        if(friends.isBlocked(recipient))throw new IOException("Peer blocked");
        JsonObject args=new JsonObject();args.addProperty("recipient",recipient);args.addProperty("kind",kind);args.addProperty("messageId",id);args.add("message",wire.deepCopy());
        JsonObject answer=await(directory.request("send",args));if(!flag(answer,"queued",false)&&!flag(answer,"acknowledged",false))throw new IOException("Service did not accept the message");return answer;
    }
    private IdentityStore.SignedPayload lookupProofNow(String id)throws Exception{
        OnlineMessageStore.peer(id);ensureDirectory();JsonObject answer=await(directory.request("lookup",arg("id",id)));
        IdentityProfile profile=DirectoryClient.profile(answer);if(!profile.id().equals(id))throw new IOException("Lookup identity mismatch");
        IdentityStore.SignedPayload proof=OnlineJson.GSON.fromJson(answer.getAsJsonObject("profileProof"),IdentityStore.SignedPayload.class);
        IdentityStore.validateProfileProof(proof);return proof;
    }
    public CompletableFuture<IdentityStore.SignedPayload> lookupProfileProof(String id){return task(()->lookupProofNow(id));}
    /** Signed and node-pinned ICE response. Kept out of the published UI/cache because it can contain short-lived relay credentials. */
    public CompletableFuture<JsonObject> iceConfiguration(){return task(()->{ensureDirectory();return await(directory.request("ice",new JsonObject())).deepCopy();});}
    public CompletableFuture<JsonObject> sendEnvelope(String recipient,String kind,JsonObject message){
        JsonObject wire=message.deepCopy();return task(()->{
            if(!Set.of("signal","control").contains(kind))throw new IOException("Reserved transport kind");
            OnlineMessageStore.peer(recipient);ensureDirectory();return nodeSend(recipient,kind,UUID.randomUUID().toString(),wire);
        });
    }
    public CompletableFuture<InvitationStore.CreatedInvitation> issueInvitation(InvitationStore.InvitationOptions options){
        return task(()->{
            if(!gameCapabilities.contains("invite_create"))throw new IOException("Host invitation permission required");
            String host=string(object(gameView,"session"),"host",string(object(gameView,"session"),"owner",""));
            if(!host.equals(identity.profile().id()))throw new IOException("Only the active issuer host can create this invitation");
            InvitationStore.CreatedInvitation created=invitations.create(options);issuedCodes.put(created.invitation().id(),created.token());publish();
            if(!options.targetId().isBlank()){
                JsonObject request=new JsonObject();request.addProperty("recipient",options.targetId());JsonObject answer=new JsonObject();answer.addProperty("token",created.token());
                adoptCreatedInvitation(request,answer);
            }
            publish();return created;
        });
    }
    public CompletableFuture<Void> revokeInvitation(String id){return task(()->{invitations.revoke(id);issuedCodes.remove(id);publish();return null;});}
    private void sendChat(JsonObject args)throws Exception{
        if(!string(args,"channel","").equals("direct"))throw new IOException("Not a direct message");
        String recipient=ProfileFiles.string(args,"recipient",64),text=ProfileFiles.string(args,"text",2048);
        if(text.isBlank()||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new IOException("Invalid message text");
        IdentityStore.SignedPayload proof=lookupProofNow(recipient);String id=UUID.randomUUID().toString();long now=clock.millis();
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type","message");body.addProperty("id",id);body.addProperty("sender",identity.profile().id());
        body.addProperty("recipient",recipient);body.addProperty("nickname",identity.profile().nickname());body.addProperty("channel","direct");body.addProperty("text",text);
        body.addProperty("createdAt",now);body.addProperty("expiresAt",now+24*3600000L);
        JsonObject wire=OnlineJson.GSON.toJsonTree(identity.seal(proof,IdentityStore.Purpose.CHAT,OnlineJson.bytes(body))).getAsJsonObject();
        communications.outgoing(id,recipient,"chat",wire,body,now+24*3600000L);status="Сообщение сохранено в очереди. Доставка ещё не подтверждена.";
    }
    private void adoptCreatedInvitation(JsonObject request,JsonObject answer)throws Exception{
        String token=string(answer,"token",string(answer,"code",""));if(token.isBlank())throw new IOException("Host did not return an invitation");
        InvitationStore.SignedInvitation invitation=InvitationStore.inspect(token,clock);issuedCodes.put(invitation.id(),token);
        if(!invitation.issuer().id().equals(identity.profile().id())){
            String host=string(object(gameView,"session"),"host","");
            if(!host.equals(invitation.issuer().id())||!flag(answer,"deliveryHandled",false))throw new IOException("Only the authenticated issuing host may deliver this invitation");
            String target=string(request,"recipient","");if(!invitation.targetId().isBlank()&&!invitation.targetId().equals(target))throw new IOException("Invitation audience mismatch");
            remoteIssued.put(invitation.id(),invitation);return;
        }
        if(flag(answer,"deliveryHandled",false))return;
        String recipient=string(request,"recipient","");if(recipient.isBlank())return;
        if(!invitation.targetId().isBlank()&&!invitation.targetId().equals(recipient))throw new IOException("Invitation audience mismatch");
        IdentityStore.SignedPayload proof=lookupProofNow(recipient);String id=UUID.randomUUID().toString();
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type","invitation");body.addProperty("id",id);body.addProperty("sender",identity.profile().id());
        body.addProperty("recipient",recipient);body.addProperty("invitationId",invitation.id());body.addProperty("token",token);
        body.addProperty("createdAt",clock.millis());body.addProperty("expiresAt",invitation.expiresAt());
        JsonObject wire=OnlineJson.GSON.toJsonTree(identity.seal(proof,IdentityStore.Purpose.INVITATION,OnlineJson.bytes(body))).getAsJsonObject();
        communications.outgoing(id,recipient,"invite",wire,null,invitation.expiresAt());
    }
    private void receiveMail(String from,String kind,JsonObject wire)throws Exception{
        OnlineMessageStore.peer(from);if(friends.isBlocked(from))return;
        if(kind.equals("control")&&string(wire,"type","").equals("delivery_receipt")){receiveReceipt(from,wire);return;}
        if(kind.equals("friend")){
            FriendsStore.SignedFriendMessage incoming=OnlineJson.GSON.fromJson(wire,FriendsStore.SignedFriendMessage.class);
            FriendsStore.Body body=FriendsStore.inspect(incoming);if(!body.from().equals(from)||!body.to().equals(identity.profile().id()))throw new IOException("Friend sender mismatch");
            if(body.type()==FriendsStore.Type.REQUEST&&!accepts(identity.profile().privacy().requests(),from))throw new IOException("Requests blocked by privacy");
            if(communications.duplicate(from,body.id(),wire))return;
            FriendsStore.ReceiveResult result=friends.receive(incoming);
            communications.accept(body.id(),from,"friend",wire,incoming.envelope().signer(),null,body.expiresAt());
            if(result.changed()){registered=false;notify("friend:"+body.id(),body.type()==FriendsStore.Type.REQUEST?"Получена заявка в друзья от "+incoming.envelope().signer().nickname():"Обновлено подтверждённое состояние дружбы с "+incoming.envelope().signer().nickname());}
            return;
        }
        if(kind.equals("chat")||kind.equals("invite")){
            IdentityStore.Purpose purpose=kind.equals("chat")?IdentityStore.Purpose.CHAT:IdentityStore.Purpose.INVITATION;
            IdentityStore.SignedPayload envelope=OnlineMessageStore.payload(wire);
            JsonObject body=ProfileFiles.parse(identity.openSealed(envelope,purpose),32768);
            if(!envelope.signer().id().equals(from)||!ProfileFiles.string(body,"sender",64).equals(from)
                    ||!ProfileFiles.string(body,"recipient",64).equals(identity.profile().id()))throw new IOException("Encrypted audience mismatch");
            String id=ProfileFiles.string(body,"id",36);OnlineMessageStore.uuid(id);
            long created=ProfileFiles.number(body,"createdAt"),expires=ProfileFiles.number(body,"expiresAt"),now=clock.millis();
            if(created<0||created>now+30000||expires<=now||expires<=created||expires-created>7*24*3600000L)throw new IOException("Expired encrypted message");
            if(!accepts(kind.equals("chat")?identity.profile().privacy().messages():identity.profile().privacy().invitations(),from))throw new IOException("Message blocked by privacy");
            if(communications.duplicate(from,id,wire))return;
            if(kind.equals("chat")){
                if(!ProfileFiles.string(body,"type",32).equals("message")||!ProfileFiles.string(body,"channel",16).equals("direct"))throw new IOException("Invalid chat type");
                String text=ProfileFiles.string(body,"text",2048);if(text.isBlank()||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new IOException("Invalid chat text");
                if(!ProfileFiles.string(body,"nickname",128).equals(envelope.signer().nickname()))throw new IOException("Nickname mismatch");
            }else{
                if(!ProfileFiles.string(body,"type",32).equals("invitation"))throw new IOException("Invalid invitation type");
                InvitationStore.SignedInvitation invitation=InvitationStore.inspect(ProfileFiles.string(body,"token",16384),clock);
                if(!invitation.issuer().id().equals(from)||!ProfileFiles.string(body,"invitationId",36).equals(invitation.id())
                        ||!invitation.targetId().isBlank()&&!invitation.targetId().equals(identity.profile().id()))throw new IOException("Invitation issuer/audience mismatch");
            }
            communications.accept(id,from,kind,wire,envelope.signer(),body,expires);
            notify(kind+":"+id,kind.equals("chat")?"Получено личное сообщение от "+envelope.signer().nickname():"Получено приглашение от "+envelope.signer().nickname());return;
        }
        long expected=generation.get();CompletableFuture<Void> handled=new CompletableFuture<>();
        clientExecutor.execute(()->{try{check(expected);game.receive(from,kind,wire.deepCopy());handled.complete(null);}catch(Exception failure){handled.completeExceptionally(failure);}});
        await(handled);
    }
    private void receiveReceipt(String from,JsonObject wire)throws Exception{
        ProfileFiles.fields(wire,Set.of("type","envelope"),Set.of());
        IdentityStore.SignedPayload signed=OnlineMessageStore.payload(wire.getAsJsonObject("envelope"));
        JsonObject receipt=ProfileFiles.parse(identity.openSealed(signed,IdentityStore.Purpose.CHAT),4096);
        ProfileFiles.fields(receipt,Set.of("type","sender","recipient","messageId","kind","hash","createdAt"),Set.of());
        if(!ProfileFiles.string(receipt,"type",32).equals("delivery_receipt")||!signed.signer().id().equals(from)
                ||!ProfileFiles.string(receipt,"sender",64).equals(from)||!ProfileFiles.string(receipt,"recipient",64).equals(identity.profile().id()))throw new IOException("Receipt audience mismatch");
        String id=ProfileFiles.string(receipt,"messageId",36),kind=ProfileFiles.string(receipt,"kind",16),digest=ProfileFiles.string(receipt,"hash",64);OnlineMessageStore.uuid(id);
        long created=ProfileFiles.number(receipt,"createdAt");if(created<clock.millis()-8*24*3600000L||created>clock.millis()+30000||!digest.matches("[0-9a-f]{64}"))throw new IOException("Invalid receipt");
        if(kind.equals("friend")){
            for(FriendsStore.SignedFriendMessage outgoing:friends.outbox()){
                FriendsStore.Body body=FriendsStore.inspect(outgoing);if(!body.id().equals(id))continue;
                JsonObject original=OnlineJson.GSON.toJsonTree(outgoing).getAsJsonObject();
                if(!body.to().equals(from)||!OnlineMessageStore.hash(original).equals(digest))throw new IOException("Friend receipt mismatch");
                friends.markDelivered(id);return;
            }
        }else if(kind.equals("chat")||kind.equals("invite"))communications.receipt(from,id,kind,digest);
        else throw new IOException("Unknown receipt kind");
    }
    private boolean accepts(IdentityProfile.ContactPolicy policy,String id)throws IOException{return policy==IdentityProfile.ContactPolicy.ANYONE||policy==IdentityProfile.ContactPolicy.FRIENDS&&friends.isConfirmed(id);}
    private String presenceMode(){
        String desired=string(preferences,"status","online");
        return desired.equals("online")&&flag(object(gameView,"session"),"active",false)?"on_island":desired;
    }
    private void notifyOnce(String id,String text){for(JsonElement entry:notifications)if(string(entry.getAsJsonObject(),"id","").equals(id))return;notify(id,text);}
    private void notify(String id,String text){
        JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("state","new");row.addProperty("status",text);notifications.add(row);
        while(notifications.size()>128)notifications.remove(0);
    }
    private void publish(){
        if(closed.get())return;
        if(identity==null||friends==null||preferences==null||communications==null)return;
        try{
            JsonObject next=gameView.deepCopy();next.addProperty("schema",1);next.add("profile",profileView(identity.profile()));
            JsonObject profile=next.getAsJsonObject("profile");profile.addProperty("needsSetup",identity.profile().revision()==0);
            JsonObject service=new JsonObject();service.addProperty("configured",!serviceEndpoint().isEmpty());service.addProperty("connected",connected);
            service.addProperty("endpoint",string(preferences,"endpoint",""));service.addProperty("reason",serviceReason);service.addProperty("transport",room==null?"configured":"iroh");next.add("service",service);
            next.add("connections",connectionRows());next.addProperty("activeConnectionId",activeConnectionId);
            OnlineRoomNetwork activeRoom=room;next.add("room",activeRoom==null?new JsonObject():activeRoom.snapshot());next.addProperty("roomDiagnostic",roomDiagnostic);next.addProperty("roomFailurePhase",roomFailurePhase);next.addProperty("roomFailureKind",roomFailureKind);
            JsonArray contactRows=new JsonArray(),requestRows=new JsonArray();Set<String> included=new HashSet<>();
            boolean incoming=false,confirmed=false,blocked=false;
            for(FriendsStore.PeerView peer:friends.snapshot()){
                JsonObject row=profileView(peer.profile());row.addProperty("requestId",peer.requestId());included.add(peer.profile().id());
                row.addProperty("blocked",peer.blocked());row.addProperty("confirmed",peer.state()==FriendsStore.State.CONFIRMED&&!peer.blocked());
                if(peer.blocked()){row.addProperty("state","blocked");row.addProperty("status","Локальная блокировка");contactRows.add(row);blocked=true;}
                else if(peer.state()==FriendsStore.State.CONFIRMED){
                    row.addProperty("state",connected?presence.getOrDefault(peer.profile().id(),"unknown"):"unknown");
                    row.addProperty("status",connected?(presence.containsKey(peer.profile().id())?"Статус получен от службы":"Свежий статус не получен"):"Связь со службой не подтверждена");contactRows.add(row);confirmed=true;
                }else{
                    String state=switch(peer.state()){case INCOMING_PENDING->"incoming";case OUTGOING_PENDING->"outgoing";case ACCEPTED_PENDING->"pending";case DECLINED->"declined";case CANCELLED->"cancelled";case EXPIRED->"expired";default->"removed";};
                    row.addProperty("state",state);row.addProperty("direction",peer.state()==FriendsStore.State.INCOMING_PENDING?"incoming":"outgoing");
                    row.addProperty("status",switch(peer.state()){case INCOMING_PENDING->"Ожидает вашего решения";case OUTGOING_PENDING->"Ожидает ответа второй стороны";case ACCEPTED_PENDING->"Принятие отправлено; дружба ещё не подтверждена";case EXPIRED->"Срок заявки истёк";default->"Заявка завершена";});
                    requestRows.add(row);incoming|=peer.state()==FriendsStore.State.INCOMING_PENDING;
                }
            }
            for(JsonElement id:friends.exportState().getAsJsonArray("blocks"))if(!included.contains(id.getAsString())){JsonObject row=new JsonObject();row.addProperty("id",id.getAsString());row.addProperty("state","blocked");row.addProperty("status","Имя не получено; ID заблокирован локально");contactRows.add(row);blocked=true;}
            next.add("friends",contactRows);next.add("requests",requestRows);next.add("lookup",lookup.deepCopy());
            JsonArray invites=communications.invitations();
            for(InvitationStore.InvitationView invitation:invitations.list())
                invites.add(localInvitationRow(invitation,issuedCodes.getOrDefault(invitation.id(),""),clock.millis()));
            for(InvitationStore.SignedInvitation invitation:remoteIssued.values())if(invitation.expiresAt()>clock.millis())
                invites.add(remoteInvitationRow(invitation,issuedCodes.getOrDefault(invitation.id(),""),clock.millis()));
            next.add("invites",invites);JsonArray messages=communications.messages();for(JsonElement message:array(gameView,"messages"))messages.add(message.deepCopy());next.add("messages",messages);
            next.add("notifications",notifications.deepCopy());
            JsonObject privacy=new JsonObject();IdentityProfile.Privacy policy=identity.profile().privacy();
            privacy.addProperty("presence",policy.presence()==IdentityProfile.Visibility.PUBLIC?"everyone":policy.presence().name().toLowerCase(Locale.ROOT));
            privacy.addProperty("requests",policy(policy.requests()));privacy.addProperty("messages",policy(policy.messages()));privacy.addProperty("invitations",policy(policy.invitations()));
            privacy.addProperty("status",string(preferences,"status","online"));privacy.addProperty("history",flag(preferences,"history",false));privacy.addProperty("relayOnly",flag(preferences,"relayOnly",false));next.add("privacy",privacy);
            JsonObject caps=new JsonObject();for(String action:gameCapabilities)caps.addProperty(action,true);
            for(String action:List.of("identity_update","identity_backup","service_configure","privacy_update","friend_block"))caps.addProperty(action,true);
            boolean networkIdle=!flag(object(gameView,"session"),"active",false);
            caps.addProperty("identity_import",networkIdle);
            caps.addProperty("service_configure",networkIdle);
            caps.addProperty("room_create",!connectionRelayOnly&&networkIdle&&room==null);
            caps.addProperty("room_join",!connectionRelayOnly&&networkIdle&&room==null);
            caps.addProperty("room_close",networkIdle&&room!=null);
            caps.addProperty("connection_create",!connectionRelayOnly&&networkIdle&&room==null&&savedConnections.list().size()<SavedConnections.MAX_CONNECTIONS);
            caps.addProperty("connection_join",!connectionRelayOnly&&networkIdle&&room==null&&savedConnections.list().size()<SavedConnections.MAX_CONNECTIONS);
            caps.addProperty("connection_open",!connectionRelayOnly&&networkIdle&&room==null&&!savedConnections.list().isEmpty());
            caps.addProperty("connection_close",networkIdle&&room!=null);
            caps.addProperty("connection_forget",true);
            if(connectionRelayOnly){
                JsonObject reasons=object(next,"capabilityReasons");
                for(String action:List.of("connection_create","connection_join","connection_open","room_create","room_join"))reasons.addProperty(action,STRICT_CONNECTION_REASON);
                next.add("capabilityReasons",reasons);
            }
            caps.addProperty("friend_lookup",connected);caps.addProperty("friend_request",connected);caps.addProperty("friend_accept",incoming);caps.addProperty("friend_reject",incoming);
            caps.addProperty("friend_remove",confirmed);caps.addProperty("friend_unblock",blocked);
            caps.addProperty("invite_revoke",!invitations.list().isEmpty()||!remoteIssued.isEmpty()&&gameCapabilities.contains("invite_revoke"));caps.addProperty("invite_reject",!communications.invitations().isEmpty());
            caps.addProperty("chat_send",connected||gameCapabilities.contains("chat_send"));next.add("capabilities",caps);
            next.addProperty("islandPrivacyEditable",gameCapabilities.contains("privacy_update"));
            next.addProperty("busy",pending.get()>0);next.addProperty("status",status);published=next;
        }catch(Exception failure){status="Не удалось прочитать защищённые локальные данные. Они не перезаписаны.";JsonObject failed=published.deepCopy();failed.addProperty("status",status);failed.add("capabilities",new JsonObject());published=failed;}
    }
    /** Actual issuer-book usage is authoritative; an exhausted capability must never remain copyable. */
    static JsonObject localInvitationRow(InvitationStore.InvitationView invitation,String issuedCode,long now){
        boolean used=invitation.maxUses()>0&&invitation.uses()>=invitation.maxUses();
        boolean actionable=!invitation.revoked()&&invitation.expiresAt()>now&&!used&&issuedCode!=null&&!issuedCode.isBlank();
        JsonObject row=invitationMetadata(invitation.id(),invitation.islandId(),invitation.targetId(),invitation.createdAt(),invitation.expiresAt(),invitation.maxUses());
        row.addProperty("nickname","Ваше приглашение");row.addProperty("uses",invitation.uses());row.addProperty("usesKnown",true);
        row.addProperty("used",used);row.addProperty("revoked",invitation.revoked());row.addProperty("actionable",actionable);
        row.addProperty("state",invitation.revoked()?"revoked":invitation.expiresAt()<=now?"expired":used?"used":"outgoing");
        row.addProperty("status","Использований: "+invitation.uses()+" / "+(invitation.maxUses()==0?"без ограничения":invitation.maxUses()));
        if(actionable)row.addProperty("code",issuedCode);return row;
    }
    /** A remote issuer's signed creation proof carries no current usage counter; do not fabricate one. */
    static JsonObject remoteInvitationRow(InvitationStore.SignedInvitation invitation,String issuedCode,long now){
        boolean actionable=invitation.expiresAt()>now&&issuedCode!=null&&!issuedCode.isBlank();
        JsonObject row=invitationMetadata(invitation.id(),invitation.islandId(),invitation.targetId(),invitation.createdAt(),invitation.expiresAt(),invitation.maxUses());
        row.addProperty("nickname","Приглашение, выпущенное ведущим");row.addProperty("usesKnown",false);
        row.addProperty("actionable",actionable);row.addProperty("state",invitation.expiresAt()<=now?"expired":"outgoing");
        row.addProperty("status","Код проверен; фактическое использование учитывает ведущий.");
        if(actionable)row.addProperty("code",issuedCode);return row;
    }
    private static JsonObject invitationMetadata(String id,String islandId,String recipient,long createdAt,long expiresAt,int maxUses){
        JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("direction","outgoing");
        row.addProperty("islandId",islandId);row.addProperty("recipient",recipient);row.addProperty("createdAt",createdAt);
        row.addProperty("expiresAt",expiresAt);row.addProperty("maxUses",maxUses);return row;
    }
    private static JsonObject profileView(IdentityProfile profile){
        JsonObject row=new JsonObject();row.addProperty("id",profile.id());row.addProperty("nickname",profile.nickname());row.addProperty("model",profile.model().name().toLowerCase(Locale.ROOT));return row;
    }
    private static String policy(IdentityProfile.ContactPolicy policy){return policy==IdentityProfile.ContactPolicy.ANYONE?"everyone":policy.name().toLowerCase(Locale.ROOT);}
    private <T> CompletableFuture<T> task(Callable<T> operation){
        CompletableFuture<T> result=new CompletableFuture<>();futures.add(result);result.whenComplete((value,failure)->futures.remove(result));long expected=generation.get();
        execute(()->{try{check(expected);result.complete(operation.call());}catch(Exception failure){result.completeExceptionally(failure);}},()->result.completeExceptionally(new IOException("Friends worker queue unavailable")));return result;
    }
    private void execute(Runnable work,Runnable rejected){
        if(closed.get()){if(rejected!=null)rejected.run();return;}
        try{worker.execute(work);}catch(RejectedExecutionException full){if(rejected!=null)rejected.run();}
    }
    private void check(long expected)throws IOException{if(closed.get()||expected!=generation.get()||Thread.currentThread().isInterrupted())throw new IOException("Stale or cancelled Friends operation");}
    private static <T> T await(CompletableFuture<T> future)throws Exception{
        try{return future.get(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw interrupted;}
    }
    private void closeDirectory(){
        invalidateAuthorityRoute();DirectoryClient previous=directory;directory=null;if(previous!=null)previous.close();registered=false;connected=false;lastAck=0;lastRegister=0;mailboxEpoch="";presence.clear();lastSent.clear();
    }
    private static JsonObject arg(String key,String value){JsonObject row=new JsonObject();row.addProperty(key,value);return row;}
    private static String connectionError(String action,Throwable failure){
        Throwable current=failure;
        for(int i=0;current!=null&&i<12;i++,current=current.getCause()){
            if(current instanceof ConnectionCatalogClient.Failure catalog){
                DarkWinter.LOGGER.warn("Connection catalog failed: action={} code={} phase={} http={} content={}",
                        action,catalog.code().name(),catalog.phase().name(),catalog.httpStatus(),catalog.contentKind().name());
                if(catalog.contentKind()==ConnectionCatalogClient.ContentKind.HTML)
                    return "Вместо ответа каталога получена веб-страница (HTTP "+catalog.httpStatus()+"). Проверьте доступность службы с этого ПК.";
                if(catalog.httpStatus()==403)return "Служба отклонила доступ с этого ПК (HTTP 403).";
                return switch(catalog.code()){
                case NAME_TAKEN->"Это имя соединения уже занято. Выберите другое.";
                case NOT_FOUND->"Соединение с таким именем не найдено. Проверьте имя у создателя.";
                case INVALID_PASSWORD->"Неверный пароль соединения.";
                case RATE_LIMITED->"Слишком много попыток. Подождите и попробуйте снова.";
                case NETWORK->"Каталог соединений сейчас недоступен. Проверьте интернет и повторите попытку.";
                case INVALID_REQUEST->"Каталог отклонил запрос (HTTP "+catalog.httpStatus()+").";
                case UNAUTHORIZED->"Не подтверждён владелец соединения. Сохранённые данные не изменены.";
                default->"Каталог не подтвердил данные соединения. Повторите попытку.";
                };
            }
        }
        return error(action);
    }
    private static String error(String action){
        return switch(action){
            case "identity_update"->"Не удалось сохранить профиль. Имя должно содержать 1–32 видимых символа; выберите доступную модель.";
            case "identity_backup"->"Не удалось сохранить защищённую копию. Проверьте имя файла, пароль и свободное место.";
            case "identity_import"->"Копия не восстановлена. Проверьте файл и пароль; исходные данные сохранены.";
            case "connection_create"->"Соединение не создано. Проверьте название, пароль и доступность интернета.";
            case "connection_join","connection_open"->"Не удалось подключиться. Проверьте данные соединения, пароль и доступность создателя.";
            case "connection_close"->"Сначала закройте совместный сеанс острова.";
            case "connection_forget"->"Не удалось удалить соединение. Сначала отключитесь от него.";
            case "room_create","room_join"->"Сеть не подключена. Проверьте интернет, срок кода и доступность ретранслятора; дружба и остров не изменены.";
            case "room_close"->"Сначала закройте активный совместный сеанс острова.";
            case "friend_lookup"->"Профиль не найден, недоступен или его подпись не подтверждена.";
            case "friend_request"->"Заявка не создана. Проверьте ID, доступность службы и уже ожидающие заявки.";
            case "chat_send"->"Сообщение не подтверждено. Проверьте адресата, приватность и подключение.";
            case "invite_create","connect_invite"->"Приглашение не подтверждено. Проверьте срок, адресата, права и подключение.";
            default->"Действие не подтверждено. Проверьте текущее состояние и разрешения.";
        };
    }
    private static JsonObject loading(){
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("busy",true);value.addProperty("status","Подготовка соединений…");
        value.add("capabilities",new JsonObject());return value;
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;stopCatalogHosting();cancelConnectionProbe();connectionProbeWorker.shutdownNow();generation.incrementAndGet();invalidateAuthorityRoute();timer.shutdownNow();worker.shutdownNow();authorityWorker.shutdownNow();
        initialized.completeExceptionally(new IOException("Friends closed"));for(CompletableFuture<?> future:futures)future.completeExceptionally(new IOException("Friends closed"));
        Thread.ofVirtual().name("dw-friends-close").start(()->{
            try{DirectoryClient client=directory;directory=null;if(client!=null)client.close();connectionProbeWorker.awaitTermination(5,TimeUnit.SECONDS);worker.awaitTermination(60,TimeUnit.SECONDS);OnlineRoomNetwork active=room;room=null;if(active!=null)active.close();authorityWorker.awaitTermination(5,TimeUnit.SECONDS);IdentityStore store=identity;if(store!=null)store.close();if(connectionCatalog!=null){try{catalogStopping.get(2,TimeUnit.SECONDS);}catch(Exception ignored){}connectionCatalog.close();}terminated.complete(null);}
            catch(Exception failure){terminated.completeExceptionally(failure);}
        });
    }
}
