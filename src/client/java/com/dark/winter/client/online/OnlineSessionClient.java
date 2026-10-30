// Coordinates authenticated online sessions, native Minecraft admission, and authoritative actions.
package com.dark.winter.client.online;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.client.TestingSessions;
import java.nio.file.Path;
import com.dark.winter.online.friends.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.network.OnlineCompatibility;
import com.dark.winter.online.server.OnlineGameHost;
import com.dark.winter.online.server.OnlineVoiceWorld;
import com.dark.winter.online.signaling.PeerSignaling;
import com.dark.winter.online.signaling.IslandJoinFailure;
import com.dark.winter.online.iroh.IrohGameTransport;
import com.dark.winter.online.transport.*;
import com.dark.winter.online.world.IslandAccess;
import com.dark.winter.online.world.IslandPolicy;
import net.minecraft.world.level.storage.LevelResource;
import com.dark.winter.testing.*;
import com.google.gson.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Application owner: one profile, authenticated signaling, bounded bridge, then native Minecraft admission.
 * UI caches are observations only. Every mutation is checked again on the authoritative server. */
public final class OnlineSessionClient implements OnlineFriendsRuntime.GameBackend,AutoCloseable {
    private final Minecraft client;
    private final OnlineVoiceClient voice;
    private final OnlineWorldLifecycle lifecycle;
    private final OnlineLabCoordinator coordinator;
    private boolean detachPending;
    private record RemoteTransition(UUID id,String nonce,UUID session,String island,String host,long epoch,long deadline){}
    private RemoteTransition remoteTransition;
    private volatile OnlineVoiceWorld voiceWorld;
    private final Map<String,IdentityStore.SignedPayload> profileProofs=new ConcurrentHashMap<>();
    private volatile OnlineFriendsRuntime runtime;
    private volatile IdentityStore identity;
    private WebRtcTransport transport;
    private PeerSignaling signaling;
    private IrohGameTransport roomGame;
    private WebRtcTransport.Config networkOptions=WebRtcTransport.Config.localOnly();
    private CompletableFuture<Void> preparingNetwork;private long iceRefreshAt;
    private volatile OnlineConnectionClient.ExpectedHost expected;
    private volatile OnlineGameHost host;
    private volatile IslandPolicy policy;
    private volatile OnlineAuthoritySession authority;
    private volatile OnlineGuestAuthority guestAuthority;
    private OnlineNativeAlias.Scope nativeAliasScope;
    private record JoinAuthority(String peer,long policyRevision,boolean manualApproval){}
    private final Map<UUID,JoinAuthority> joinAuthorities=new ConcurrentHashMap<>();
    private volatile MinecraftServer hostServer;
    private volatile JsonObject manifest;
    private CompletableFuture<JsonObject> compatibility;
    private JsonObject packet=new JsonObject();
    private final Map<UUID,CompletableFuture<JsonObject>> requests=new HashMap<>();
    private final Map<UUID,Long> deadlines=new HashMap<>();
    private final Map<UUID,PendingJoin> approvals=new LinkedHashMap<>();
    private final Map<UUID,JsonObject> peerManifests=new ConcurrentHashMap<>();
    private final Map<UUID,WebRtcSocketBridge.Binding> bridges=new ConcurrentHashMap<>();
    private final Map<UUID,WebRtcMultiplex> multiplexes=new ConcurrentHashMap<>();
    private final Map<String,IdentityProfile> profiles=new ConcurrentHashMap<>();
    private final Map<String,Deque<JsonObject>> chats=new ConcurrentHashMap<>();
    private CompletableFuture<?> joining;
    private IdentityStore.SignedPayload lastHostProof;
    private ResumeTarget resumeTarget;private ResumeState resume;
    private record ResumeTarget(IdentityStore.SignedPayload proof,String islandId,long epoch){}
    private static final class ResumeState {final long deadline;long next=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);int attempts;ResumeState(){this(TimeUnit.MINUTES.toNanos(2));}ResumeState(long duration){deadline=System.nanoTime()+duration;}}
    enum HostOpeningStage {
        NONE("none",""),
        NETWORK_SETTINGS("network_settings","Получение сетевых настроек общего острова."),
        COMPATIBILITY("compatibility","Проверка совместимости сборки и игровых данных."),
        LOCAL_POLICY("local_policy","Ожидание встроенного сервера и сохранение настроек доступа."),
        SAVE_STOP("save_stop","Сохранение, остановка острова и создание начального снимка."),
        BOOTSTRAP("bootstrap","Подтверждение снимка и полномочий ведущего служебным узлом."),
        REOPEN("reopen","Повторное открытие сохранённого острова."),
        INSTALL("install","Подключение подтверждённого ведущего к открытому острову.");
        private final String code,status;
        HostOpeningStage(String code,String status){this.code=code;this.status=status;}
        String code(){return code;}
        String status(){return status;}
    }
    private record HostOpeningProgress(long generation,HostOpeningStage stage){}
    private final java.util.concurrent.atomic.AtomicReference<HostOpeningProgress> hostOpeningProgress=new java.util.concurrent.atomic.AtomicReference<>();
    private String phase="offline",status="Совместный сеанс не открыт.",stateProblem="";
    private volatile long generation;private long ticks;private volatile boolean closed;private boolean opening,stateQueued;
    private Object observedLevel,observedServer;private boolean observedTesting,networkRelayOnly;
    private volatile Object networkToken;
    private record PendingJoin(IdentityProfile peer,UUID attempt,JsonObject manifest,String token,
                               CompletableFuture<PeerSignaling.Decision> result,long expires) {
        @Override public String toString(){return "PendingJoin[attempt="+attempt+"]";}
    }
    public OnlineSessionClient(Minecraft client){
        this.client=Objects.requireNonNull(client);
        lifecycle=new OnlineWorldLifecycle(client,new OnlineWorldLifecycle.Backend(){
            public OnlineGameHost host(){return host;}
            public MinecraftServer server(){return hostServer;}
            public IslandPolicy policy(){return policy;}
            public CompletableFuture<OnlineGameHost> reopen(MinecraftServer target,IslandAccess.View access,IslandPolicy.View settings){return reopenAfterTransition(target,access,settings);}
            public CompletableFuture<Void> prepareStopped(Path target,boolean internal,IslandAccess.View access,Map<String,UUID> aliases){
                OnlineAuthoritySession current=authority;
                if(current==null)return CompletableFuture.failedFuture(new IOException("Полномочия ведущего отсутствуют"));
                return current.prepareStopped(target,internal,access,aliases).thenCompose(ignored->onClient(()->{
                    if(closed||authority!=current||!lifecycle.active())throw new IOException("Переоткрытие отменено");
                    var ticket=current.takeOpeningTicket(target);
                    if(ticket.isPresent()){
                        closeNativeAlias();
                        try{nativeAliasScope=OnlineNativeAlias.arm(client,ticket.get());}
                        catch(Exception failed){ticket.get().close();throw failed;}
                    }
                    return null;
                }));
            }
            public Optional<TestingArchiveScope.Token> archiveContext(Path actualWorld)throws IOException{
                OnlineAuthoritySession current=authority;
                if(current==null){TestingArchiveScope.requireWorld(actualWorld);return Optional.empty();}
                return current.archiveContext(actualWorld);
            }
            public void status(String text){OnlineSessionClient.this.status=text;refresh();}
        });
        coordinator=new OnlineLabCoordinator(client,lifecycle);
        voice=new OnlineVoiceClient(client,()->identity,(peer,sealed)->{
            JsonObject wrapper=new JsonObject();wrapper.addProperty("type","dw_voice_v1");wrapper.add("envelope",sealed.deepCopy());
            return runtime==null?CompletableFuture.failedFuture(new IOException("Профиль не готов")):runtime.sendEnvelope(peer,"control",wrapper);
        });
        voice.onHostSnapshot(()->{OnlineVoiceWorld current=voiceWorld;return current==null?CompletableFuture.failedFuture(new IOException("Нет голосового мира")):current.capture();});
        voice.onSettings(args->{JsonObject body=new JsonObject();body.addProperty("op","game");body.addProperty("action","voice_parameters");body.add("args",args.deepCopy());return rpc(body);});
        OnlineConnectionClient.init(()->identity,()->expected,this::acceptState,this::reply);
        OnlineConnectionClient.setLifecycleHandler(this::remoteLifecycle);
        OnlineConnectionClient.setRemoteDisconnectHandler(this::remoteDisconnected);
        OnlineConnectionClient.setChatHandler(text->{
            if(host==null&&!OnlineConnectionClient.authenticated())return false;
            JsonObject args=new JsonObject();args.addProperty("channel","island");args.addProperty("text",text);
            command("chat_send",args).whenComplete((value,failure)->onClient(()->{
                if(failure!=null||value==null||!truth(value,"ok"))client.gui.hud.getChat().addClientSystemMessage(Component.literal("DarkWinter: сообщение не доставлено"));
                return null;
            }));return true;
        });
        OnlineTestingView.installAction((action,value)->{
            JsonObject body=new JsonObject();body.addProperty("op","lab");body.addProperty("action",action);body.addProperty("value",value);
            rpc(body).whenComplete((answer,failure)->onClient(()->{
                if(failure!=null||answer==null||!truth(answer,"ok"))status="Лаборатория: операция отклонена сервером.";
                refresh();return null;
            }));
        });
    }
    public void bind(OnlineFriendsRuntime runtime){if(this.runtime!=null)throw new IllegalStateException("Already bound");this.runtime=Objects.requireNonNull(runtime);}
    @Override public void identityChanged(IdentityStore value){
        if(closed)return;
        if(value!=identity){closeNetwork(false);identity=value;profiles.clear();profileProofs.clear();}
        profiles.put(value.profile().id(),value.profile());
        try{
            IdentityStore.SignedPayload proof=value.publicProof();profileProofs.put(value.profile().id(),proof);
            if(host!=null||OnlineConnectionClient.authenticated()){
                JsonObject args=new JsonObject();args.add("proof",OnlineJson.GSON.toJsonTree(proof));JsonObject body=new JsonObject();
                body.addProperty("op","game");body.addProperty("action","profile_update");body.add("args",args);
                rpc(body).whenComplete((reply,failure)->onClient(()->{if(failure!=null||reply==null||!truth(reply,"ok"))status="Профиль сохранён локально; обновление в сеансе пока не подтверждено.";return null;}));
            }
        }catch(IOException unavailable){status="Публичное удостоверение профиля пока недоступно.";}

    }
    private CompletableFuture<JsonObject> compatibility(){
        if(compatibility==null)compatibility=OnlineCompatibility.build().thenApply(value->{manifest=value.deepCopy();return value;});
        return compatibility.copy();
    }
    private void requireReady()throws IOException{
        if(closed||runtime==null||!runtime.isReady()||identity==null)throw new IOException("Профиль ещё не готов");
    }
    private CompletableFuture<Void> prepareNetwork(){return onClient(this::prepareNetworkOnClient).thenCompose(Function.identity());}
    private CompletableFuture<Void> prepareNetworkOnClient()throws IOException{
        requireReady();
        if(signaling!=null&&System.nanoTime()<iceRefreshAt)return CompletableFuture.completedFuture(null);
        if(preparingNetwork!=null)return preparingNetwork.copy();
        long fence=generation,profileGeneration=runtime.generation();
        boolean configured=truth(child(runtime.snapshot(),"service"),"configured");
        CompletableFuture<JsonObject> source=configured?runtime.iceConfiguration():CompletableFuture.completedFuture(new JsonObject());
        CompletableFuture<Void> pending=source.thenCompose(answer->onClient(()->{
            if(closed||fence!=generation||profileGeneration!=runtime.generation())throw new IOException("Запрос сетевых настроек отменён");
            List<WebRtcTransport.IceServer> servers=parseIce(answer);
            boolean relay=truth(child(runtime.snapshot(),"privacy"),"relayOnly");
            if(signaling!=null&&relay!=networkRelayOnly)throw new IOException("Закройте сеанс перед изменением сетевой приватности");
            WebRtcTransport.Config basic=WebRtcTransport.Config.localOnly();
            networkOptions=new WebRtcTransport.Config(servers,relay?WebRtcTransport.IcePolicy.RELAY_ONLY:WebRtcTransport.IcePolicy.ALL,
                basic.connectTimeout(),basic.idleWriteTimeout(),16,basic.frameBytes(),basic.receiveWindowBytes(),basic.maxQueuedWriteBytes(),basic.nativeHighWaterBytes());
            if(relay&&servers.stream().flatMap(server->server.urls().stream()).noneMatch(url->url.startsWith("turn:")||url.startsWith("turns:")))
                throw new IOException("Служба не предоставила разрешённого посредника");
            long expires=answer.has("expiresAt")?OnlineJson.integer(answer,"expiresAt",0,Long.MAX_VALUE):0;
            long seconds=expires==0?30:Math.max(1,Math.min(300,expires-System.currentTimeMillis()/1000-20));
            iceRefreshAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            if(signaling==null){networkRelayOnly=relay;createNetwork();}
            else {transport.refreshIceServers(servers);voice.refreshIceServers(servers);}
            return (Void)null;
        }));
        preparingNetwork=pending;
        pending.whenComplete((value,failure)->onClient(()->{if(preparingNetwork==pending)preparingNetwork=null;return null;}));
        return pending.copy();
    }
    private static List<WebRtcTransport.IceServer> parseIce(JsonObject answer)throws IOException{
        if(!answer.has("servers"))return List.of();JsonElement raw=answer.get("servers");
        if(!raw.isJsonArray()||raw.getAsJsonArray().size()>8)throw new IOException("Неверный список сетевых узлов");
        List<WebRtcTransport.IceServer> servers=new ArrayList<>();
        for(JsonElement entry:raw.getAsJsonArray()){
            if(!entry.isJsonObject())throw new IOException("Неверный узел");JsonObject server=entry.getAsJsonObject();OnlineJson.keys(server,"urls","username","credential");
            JsonElement links=server.get("urls");if(links==null||!links.isJsonArray()||links.getAsJsonArray().size()>8)throw new IOException("Неверные адреса ICE");
            List<String> urls=new ArrayList<>();for(JsonElement url:links.getAsJsonArray()){if(!url.isJsonPrimitive()||!url.getAsJsonPrimitive().isString())throw new IOException("Неверный адрес ICE");urls.add(url.getAsString());}
            try{servers.add(new WebRtcTransport.IceServer(urls,OnlineJson.optional(server,"username","",512),OnlineJson.optional(server,"credential","",2048)));}
            catch(IllegalArgumentException invalid){throw new IOException("Служба вернула недопустимый узел ICE");}
        }return List.copyOf(servers);
    }
    private void createNetwork()throws IOException{
        requireReady();transport=new WebRtcTransport(networkOptions);voice.configureNetwork(networkOptions);
        var router=runtime.roomRouter();
        if(router!=null&&!networkRelayOnly)roomGame=new IrohGameTransport(router,identity);
        Object networkFence=new Object();networkToken=networkFence;
        signaling=new PeerSignaling(()->identity,(peer,sealed)->runtime.sendEnvelope(peer,"signal",sealed),transport,new PeerSignaling.Listener(){
            @Override public CompletableFuture<PeerSignaling.Decision> decideJoin(IdentityProfile peer,UUID attempt,String token,JsonObject remote){
                return networkToken==networkFence?decide(peer,attempt,token,remote):CompletableFuture.completedFuture(new PeerSignaling.Decision(false,null,"Сеанс закрыт"));
            }
            @Override public CompletableFuture<Void> hostConnected(PeerSignaling.Connected connected){return attachHost(connected,networkFence);}
            @Override public CompletableFuture<Void> clientConnected(PeerSignaling.Connected connected){return attachClient(connected,networkFence);}
            @Override public void status(String peer,UUID attempt,String state,String message){
                onClient(()->{if(!closed&&networkToken==networkFence){if(state.equals("failed")){peerManifests.remove(attempt);joinAuthorities.remove(attempt);PendingJoin pending=approvals.remove(attempt);if(pending!=null)pending.result().complete(new PeerSignaling.Decision(false,null,"Запрос завершён"));}phase=state;IslandJoinFailure reason=IslandJoinFailure.fromWire(message);status=reason==null?message:reason.publicMessage();refresh();}return null;});
            }
        },Clock.systemUTC(),roomGame);
    }
    @Override public void receive(String from,String kind,JsonObject message){
        if(kind.equals("control")&&"dw_voice_v1".equals(string(message,"type",""))){
            JsonObject sealed=child(message,"envelope");voice.acceptControl(from,sealed).exceptionally(failure->null);return;
        }
        if(!kind.equals("signal"))throw new IllegalArgumentException("Неизвестный канал совместного сеанса");
        try{
            // Signaling is ephemeral and independently retries JOIN/OFFER/ANSWER. Terminal invalid/expired
            // envelopes are consumed rather than blocking durable friends/chat behind them in the mailbox.
            JsonObject incoming=message.deepCopy();prepareNetwork().thenCompose(ignored->{PeerSignaling broker=signaling;return broker==null?CompletableFuture.failedFuture(new IOException("Сетевой сеанс закрыт")):broker.receive(from,incoming);}).exceptionally(failure->null);
        }catch(RuntimeException unavailable){throw new IllegalStateException("Нет профиля для проверки сигнала");}
    }
    @Override public JsonObject snapshot(){
        JsonObject out=new JsonObject(),session=new JsonObject();
        boolean local=host!=null&&hostServer==client.getSingleplayerServer();
        boolean remote=OnlineConnectionClient.authenticated();
        JsonObject online=child(child(packet,"lab"),"online");
        if(online.has("session"))session=child(online,"session").deepCopy();
        session.addProperty("active",local||remote||opening||joining!=null||resume!=null);
        HostOpeningProgress progress=hostOpeningProgress.get();
        HostOpeningStage openingStage=progress!=null&&progress.generation()==generation?progress.stage():HostOpeningStage.NONE;
        session.addProperty("phase",phase);session.addProperty("openingStage",openingStage.code());
        session.addProperty("status",opening&&openingStage!=HostOpeningStage.NONE?openingStage.status():status);
        session.addProperty("stateProblem",stateProblem);session.addProperty("viewProblem",OnlineTestingView.diagnostic());
        session.add("signaling",OnlineJson.GSON.toJsonTree(signaling==null?List.of():signaling.protocolSnapshot()));
        if(!session.has("host"))session.addProperty("host",expected==null?identity==null?"":identity.profile().id():expected.hostId());
        if(!session.has("owner")){
            OnlineGameHost actual=host;OnlineGuestAuthority observed=guestAuthority;
            session.addProperty("owner",actual!=null?actual.access().ownerId():observed!=null?observed.ownerId():"");
        }
        if(local){session.addProperty("islandId",host.islandId());session.addProperty("limit",host.limit());session.addProperty("route","local");}
        else if(remote){WebRtcSocketBridge.Binding binding=bridges.values().stream().findFirst().orElse(null);
            session.addProperty("route",binding==null?"unknown":route(binding));}
        out.add("session",session);
        JsonObject reasons=new JsonObject();
        String invitationReason=invitationUnavailableReason(closed,identity!=null,host!=null,opening,remote,
                child(child(packet,"host"),"permissions"),!stateProblem.isBlank());
        if(!invitationReason.isBlank())reasons.addProperty("invite_create",invitationReason);
        out.add("capabilityReasons",reasons);
        out.add("participants",array(online,"participants").deepCopy());out.add("messages",array(online,"messages").deepCopy());
        JsonArray joins=new JsonArray();
        for(PendingJoin join:approvals.values()){
            JsonObject row=new JsonObject();row.addProperty("id",join.attempt().toString());row.addProperty("peerId",join.peer().id());
            row.addProperty("nickname",join.peer().nickname());row.addProperty("status","Ожидает вашего подтверждения");joins.add(row);
        }out.add("joinRequests",joins);
        JsonArray roles=new JsonArray();for(String role:List.of("observer","tester","editor","assistant")){
            JsonObject row=new JsonObject();row.addProperty("id",role);roles.add(row);
        }out.add("roles",roles);
        JsonObject audio=voice.snapshot();audio.add("authority",child(online,"voiceState").deepCopy());out.add("voice",audio);return out;
    }
    /** Describes the same invitation facts as capabilities(); never grants authority or echoes diagnostics. */
    static String invitationUnavailableReason(boolean closed,boolean profileReady,boolean hosting,boolean opening,
            boolean authenticated,JsonObject rights,boolean stateProblem){
        if(closed)return "Онлайн-сеанс закрыт.";
        if(!profileReady)return "Профиль ещё не готов.";
        if(!hosting&&!authenticated)return opening
                ?"Дождитесь подтверждённого открытия общего острова."
                :"Текущий совместный сеанс острова не открыт.";
        if(rights!=null&&truth(rights,"INVITE"))return "";
        if(stateProblem)return "Не удалось обновить права текущего сеанса. Дождитесь подтверждения.";
        JsonElement invitation=rights==null?null:rights.get("INVITE");
        if(invitation==null||!invitation.isJsonPrimitive()||!invitation.getAsJsonPrimitive().isBoolean())
            return "Права текущего сеанса ещё не получены.";
        return "У вас нет права приглашать на этот остров.";
    }
    @Override public Set<String> capabilities(){
        if(closed||identity==null)return Set.of();
        Set<String> result=new HashSet<>();result.add("voice_settings");
        if(host==null&&expected==null&&!opening&&joining==null){
            result.add("connect_invite");
            if(authority==null&&client.player!=null&&TestingServer.isTesting(client.getSingleplayerServer())&&!client.getSingleplayerServer().isPublished())result.add("online_host");
        }
        if(host!=null||expected!=null||joining!=null||opening||resume!=null){result.add("online_close");result.add("reconnect_cancel");}
        JsonObject rights=child(child(packet,"host"),"permissions");
        if(host!=null||OnlineConnectionClient.authenticated()){
            result.add("chat_send");
            if(truth(rights,"INVITE")){result.add("invite_create");result.add("invite_revoke");}
            if(truth(rights,"ASSIGN_ROLES")){result.add("participant_role");result.add("participant_permission");result.add("privacy_update");}
            if(truth(rights,"KICK"))result.add("participant_kick");
            if(truth(rights,"INVITE")){result.add("island_allow");result.add("island_block");result.add("island_unblock");}
            if(host!=null&&!approvals.isEmpty()){result.add("join_approve");result.add("join_reject");}
        }
        return Set.copyOf(result);
    }
    @Override public CompletableFuture<JsonObject> command(String action,JsonObject args){
        try{
            requireReady();JsonObject safe=args==null?new JsonObject():args.deepCopy();
            return switch(action){
                case "voice_settings"->voice.localCommand(safe);
                case "online_host"->open(safe);
                case "connect_invite"->connect(safe);
                case "online_close","reconnect_cancel"->{closeNetwork(true);yield CompletableFuture.completedFuture(OnlineJson.ok());}
                case "join_approve","join_reject"->approve(safe,action.equals("join_approve"));
                default->{JsonObject body=new JsonObject();body.addProperty("op","game");body.addProperty("action",action);body.add("args",safe);yield rpc(body);}
            };
        }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
    }
    private CompletableFuture<JsonObject> open(JsonObject args)throws IOException{
        if(host!=null||authority!=null||opening||expected!=null||client.player==null||!TestingServer.isTesting(client.getSingleplayerServer()))throw new IOException("Откройте собственный тестовый остров");
        try{runtime.verifiedAuthorityNode();}catch(IOException unavailable){throw new IOException("Сначала подключитесь к службе друзей DarkWinter",unavailable);}
        int limit=(int)OnlineJson.integer(args,"limit",2,16);
        String requested=OnlineJson.optional(args,"privacy","invited",16);
        String requestedLead=args.has("trustedLead")?OnlineJson.optional(args,"trustedLead","",64):null;
        if(requestedLead!=null&&!requestedLead.isEmpty()&&(!requestedLead.matches("dw1_[A-Za-z0-9_-]{43}")
                ||requestedLead.equals(identity.profile().id())||!runtime.friends().isConfirmed(requestedLead)))
            throw new IOException("Доверенным ведущим можно выбрать подтверждённого друга");
        IslandAccess.Privacy privacy=switch(requested){case "friends"->IslandAccess.Privacy.FRIENDS;case "invited","code"->IslandAccess.Privacy.INVITE_ONLY;default->throw new IOException("Неизвестная политика входа");};
        resume=null;resumeTarget=null;lastHostProof=null;remoteTransition=null;
        opening=true;phase="preparing";status="Сохранение острова и подтверждение ведущего служебным узлом.";
        long version=generation;hostOpeningProgress.set(new HostOpeningProgress(version,HostOpeningStage.NETWORK_SETTINGS));
        MinecraftServer original=client.getSingleplayerServer();UUID player=client.player.getUUID();refresh();
        CompletableFuture<JsonObject> result=prepareNetwork().thenCompose(ignored->{
            observeHostOpeningStage(version,HostOpeningStage.COMPATIBILITY);return compatibility();
        }).thenApply(definitions->{observeHostOpeningStage(version,HostOpeningStage.LOCAL_POLICY);return definitions;}).thenCompose(definitions->
            onServer(original,()->{
                if(closed||generation!=version||!original.isRunning()||OnlineGameHost.get(original)!=null)throw new IOException("Исходный мир изменился");
                ServerPlayer local=original.getPlayerList().getPlayer(player);
                if(local==null||!original.isSingleplayerOwner(local.nameAndId()))throw new IOException("Требуется настоящий локальный владелец Minecraft");
                try(IslandAccess access=IslandAccess.open(original.getWorldPath(LevelResource.ROOT),identity.profile())){
                    if(!access.ownerId().equals(identity.profile().id()))throw new IOException("Первое открытие выполняет постоянный владелец острова");
                    if(requestedLead!=null){
                        for(String previous:Set.copyOf(access.snapshot().trustedLeads()))if(!previous.equals(access.ownerId()))access.trustLead(identity.profile().id(),previous,false);
                        if(!requestedLead.isEmpty()){
                            if(!access.snapshot().members().containsKey(requestedLead))access.setRole(identity.profile().id(),requestedLead,IslandAccess.Role.EDITOR);
                            access.allow(identity.profile().id(),requestedLead);
                            access.setPermission(identity.profile().id(),requestedLead,IslandAccess.Permission.TRANSFER_HOST,true);
                            access.trustLead(identity.profile().id(),requestedLead,true);
                        }
                    }
                    access.configure(identity.profile().id(),privacy,limit);
                    IslandPolicy settings=IslandPolicy.open(original.getWorldPath(LevelResource.ROOT),access);
                    settings.update(identity.profile().id(),"access",requested);
                }
                return null;
            }).thenCompose(ignored->onClient(()->{
                if(closed||generation!=version||client.getSingleplayerServer()!=original)throw new IOException("Открытие отменено");
                observeHostOpeningStage(version,HostOpeningStage.SAVE_STOP);
                java.util.concurrent.atomic.AtomicReference<OnlineAuthoritySession> source=new java.util.concurrent.atomic.AtomicReference<>();
                OnlineAuthoritySession created=new OnlineAuthoritySession(runtime,player,definitions,client.getLevelSource().getBaseDir(),message->onClient(()->{
                    if(authority!=source.get())return null;closeNetwork(false);status=message;refresh();return null;
                }));source.set(created);authority=created;
                return TestingSessions.checkpointForAuthority(client,"Онлайн: начальный снимок",checkpoint->{
                    observeHostOpeningStage(version,HostOpeningStage.BOOTSTRAP);
                    return created.bootstrap(checkpoint).whenComplete((prepared,bootstrapFailure)->{
                        if(bootstrapFailure==null)observeHostOpeningStage(version,HostOpeningStage.REOPEN);
                    });
                });
            })).thenCompose(Function.identity()).thenCompose(checkpoint->onClient(()->{
                if(closed||generation!=version||authority==null||client.player==null||!client.player.getUUID().equals(player))throw new IOException("Владелец не вернулся в сохранённый остров");
                MinecraftServer server=client.getSingleplayerServer();
                if(server==null||!server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(checkpoint.authorPath()))throw new IOException("Открыт другой остров");
                observeHostOpeningStage(version,HostOpeningStage.INSTALL);
                return OnlineGameHost.openAttested(server,identity,runtime.invitations(),player,limit,definitions,authority.permit(),authority.nativeAliases())
                    .thenCompose(opened->onServer(server,()->{
                        try{
                            if(closed||generation!=version){opened.close();throw new IOException("Открытие отменено");}
                            return installHost(server,opened,requested,null);
                        }catch(Exception failure){opened.close();policy=null;voiceWorld=null;throw failure;}
                    })).thenCompose(opened->onClient(()->{
                        if(version!=generation||host!=opened){opened.close();throw new IOException("Открытие отменено");}
                        opening=false;hostOpeningProgress.set(null);phase="hosting";status="Остров открыт. Личность, права и полномочия ведущего подтверждены.";refreshLocal();refresh();return OnlineJson.ok();
                    }));
            })).thenCompose(Function.identity()));
        result.whenComplete((answer,failure)->{if(failure!=null)onClient(()->{
            if(version==generation){OnlineAuthoritySession previous=authority;authority=null;if(previous!=null)previous.close();opening=false;status="Не удалось подтвердить открытие общего острова; сохранения сохранены.";refresh();}return null;
        });});
        return result;
    }
    /** Observation only: no new thread handoff, deadline, permission or authority operation. */
    private void observeHostOpeningStage(long version,HostOpeningStage stage){
        if(closed||generation!=version)return;
        HostOpeningProgress observed=hostOpeningProgress.updateAndGet(previous->previous!=null&&previous.generation()==version
                ?new HostOpeningProgress(version,stage):previous);
        if(observed!=null&&observed.generation()==version)refresh();
    }
    private OnlineGameHost installHost(MinecraftServer server,OnlineGameHost opened,String requested,IslandPolicy.View livePolicy)throws IOException{
        IslandPolicy settings=IslandPolicy.open(server.getWorldPath(LevelResource.ROOT),opened.access());
        if(livePolicy!=null)settings.resume(identity.profile().id(),livePolicy);
        else {settings.update(identity.profile().id(),"access",requested);opened.access().configure(identity.profile().id(),accessPrivacy(settings.snapshot().admission()),opened.limit());}
        policy=settings;
        opened.onAdmissionCheck((peer,attempt,invite,approved)->{
            JoinAuthority decision=joinAuthorities.get(attempt);IslandPolicy.View current=settings.snapshot();
            if(decision==null||!decision.peer().equals(peer)||decision.policyRevision()!=current.revision()||!policyAllows(opened,settings,peer))throw new IOException("Настройки допуска изменились; повторите запрос");
            if(current.approval()&&!opened.access().allowed(peer)&&!decision.manualApproval())throw new IOException("Вход ещё не подтверждён ведущим");
        });
        TestingOnlineBridge.attach(server,opened);coordinator.attach(server,opened);detachPending=false;
        opened.onCommand((actor,id,body)->serverCommand(server,opened,actor,id,body));
        opened.onState(actor->labState(server,opened,actor));
        voiceWorld=new OnlineVoiceWorld(server,opened,peer->{try{return runtime.friends().isBlocked(peer);}catch(IOException unavailable){return true;}},voice::actualSpeaking);
        UUID previous=lifecycle.previousSession();if(previous!=null){
            Deque<JsonObject> messages=chats.get(previous.toString());if(messages!=null){Deque<JsonObject> transferred=new ConcurrentLinkedDeque<>();for(JsonObject message:messages)transferred.add(message.deepCopy());chats.clear();chats.put(opened.sessionId().toString(),transferred);}
        }
        hostServer=server;host=opened;return opened;
    }
    private OnlineAuthoritySession requireAuthority()throws IOException{
        OnlineAuthoritySession current=authority;if(current==null)throw new IOException("Нет полномочий ведущего");current.permit();return current;
    }
    private CompletableFuture<OnlineGameHost> reopenAfterTransition(MinecraftServer server,IslandAccess.View live,IslandPolicy.View livePolicy){
        if(closed||host!=null||opening||client.getSingleplayerServer()!=server||client.player==null)return CompletableFuture.failedFuture(new IOException("Мир не готов к повторному открытию"));
        com.dark.winter.online.authority.OnlineHostPermit confirmed;com.dark.winter.online.replica.NativeActorAliases nativeAliases;
        try{OnlineAuthoritySession current=requireAuthority();confirmed=current.permit();nativeAliases=current.nativeAliases();}catch(IOException denied){return CompletableFuture.failedFuture(denied);}
        opening=true;long version=generation;UUID local=client.player.getUUID();phase="reopening";refresh();
        return prepareNetwork().thenCompose(ignored->compatibility()).thenCompose(definitions->OnlineGameHost.openAttested(server,identity,runtime.invitations(),local,live.limit(),definitions,live,true,confirmed,nativeAliases))
            .thenCompose(opened->onServer(server,()->{
                try{if(closed||generation!=version||!lifecycle.active()){opened.close();throw new IOException("Переход отменён");}installHost(server,opened,null,livePolicy);lifecycle.holdOpened(server,opened);return opened;}
                catch(Exception failure){opened.close();throw failure;}
            })).thenCompose(opened->onClient(()->{
                if(closed||generation!=version||host!=opened){opened.close();throw new IOException("Переход отменён");}
                if(nativeAliasScope!=null&&nativeAliasScope.adopted())closeNativeAlias();
                opening=false;phase="hosting";refreshLocal();refresh();return opened;
            })).whenComplete((opened,failure)->{if(failure!=null)onClient(()->{if(generation==version){opening=false;status="Повторное открытие не подтверждено; сохранения сохранены.";refresh();}return null;});});
    }
    private void remoteLifecycle(JsonObject event){
        try{
            if(!OnlineConnectionClient.authenticated()||expected==null||resumeTarget==null)return;
            OnlineJson.keys(event,"type","id","nonce","sessionId","islandId","hostId","epoch","action");
            UUID id=UUID.fromString(OnlineJson.string(event,"id",36)),session=UUID.fromString(OnlineJson.string(event,"sessionId",36));
            String nonce=OnlineJson.string(event,"nonce",36);UUID.fromString(nonce);
            String island=OnlineJson.string(event,"islandId",36),leading=OnlineJson.string(event,"hostId",64);long epoch=OnlineJson.integer(event,"epoch",0,Long.MAX_VALUE);
            if(!session.equals(expected.sessionId())||epoch!=expected.epoch()||!leading.equals(expected.hostId())||!island.equals(resumeTarget.islandId()))return;
            String type=OnlineJson.string(event,"type",16);
            if(type.equals("cancel")){if(remoteTransition!=null&&remoteTransition.id().equals(id)&&remoteTransition.nonce().equals(nonce))remoteTransition=null;return;}
            if(!type.equals("prepare"))return;
            RemoteTransition previous=remoteTransition;
            if(previous!=null&&(!previous.id().equals(id)||!previous.nonce().equals(nonce)))return;
            remoteTransition=new RemoteTransition(id,nonce,session,island,leading,epoch,System.nanoTime()+TimeUnit.MINUTES.toNanos(10));
            JsonObject args=new JsonObject();args.addProperty("id",id.toString());args.addProperty("nonce",nonce);JsonObject body=new JsonObject();
            body.addProperty("op","game");body.addProperty("action","transition_ack");body.add("args",args);
            OnlineConnectionClient.command(body);status="Ведущий сохраняет общую сессию. После смены копии вход проверится заново.";refresh();
        }catch(IOException|IllegalArgumentException invalid){/* A malformed event cannot grant a reconnect exception. */}
    }
    private CompletableFuture<JsonObject> connect(JsonObject args)throws IOException{
        if(client.level!=null||host!=null||opening||joining!=null||expected!=null)throw new IOException("Перед подключением сохраните и закройте текущий мир");
        String token;InvitationStore.SignedInvitation invite;
        try{token=OnlineJson.string(args,"code",16384);invite=InvitationStore.inspect(token,Clock.systemUTC());}
        catch(IOException|RuntimeException invalid){throw new IslandJoinFailure(IslandJoinFailure.Kind.INVALID_INVITATION);}
        if(invite.issuer().id().equals(identity.profile().id()))throw new IslandJoinFailure(IslandJoinFailure.Kind.SELF_INVITATION);
        if(!invite.targetId().isEmpty()&&!invite.targetId().equals(identity.profile().id()))throw new IslandJoinFailure(IslandJoinFailure.Kind.WRONG_RECIPIENT);
        if(runtime.friends().isBlocked(invite.issuer().id()))throw new IOException("Ведущий заблокирован");
        resume=null;resumeTarget=null;lastHostProof=null;remoteTransition=null;
        phase="joining";status="Проверка приглашения и ожидание ведущего.";long version=generation;
        CompletableFuture<JsonObject> task=prepareNetwork().thenCompose(ignored->compatibility()).thenCombine(runtime.lookupProfileProof(invite.issuer().id()),(defs,proof)->Map.entry(defs,proof))
                .thenCompose(pair->{if(version!=generation)return CompletableFuture.failedFuture(new IOException("Подключение отменено"));return signaling.connect(pair.getValue(),token,pair.getKey());})
                .thenApply(connected->OnlineJson.ok());
        joining=task;task.whenComplete((value,failure)->onClient(()->{
            if(version==generation){joining=null;if(failure!=null){phase="failed";IslandJoinFailure reason=IslandJoinFailure.find(failure);status=reason==null?"Подключение не подтверждено. Проверьте доступность ведущего и срок приглашения.":reason.publicMessage();refresh();}}
            return null;
        }));refresh();return task;
    }
    private CompletableFuture<PeerSignaling.Decision> decide(IdentityProfile peer,UUID attempt,String token,JsonObject remote){
        OnlineGameHost current=host;MinecraftServer server=hostServer;
        if(current==null||server==null)return CompletableFuture.completedFuture(new PeerSignaling.Decision(false,null,IslandJoinFailure.Kind.HOST_UNAVAILABLE.wireCode()));
        return onServer(server,()->{
            if(current!=host||current.epoch()!=current.access().epoch())throw new IOException("Ведущий сменился");
            OnlineCompatibility.requireCompatible(manifest,remote);
            if(runtime.friends().isBlocked(peer.id())||current.access().blocked(peer.id()))throw new IOException("Вход запрещён");
            if(current.access().members().containsKey(peer.id())&&!current.access().can(peer.id(),IslandAccess.Permission.VIEW))throw new IOException("Просмотр запрещён");
            IslandPolicy settings=policy;
            if(settings==null||!policyAllows(current,settings,peer.id()))throw new IOException("Вход запрещён настройками острова");
            boolean approval=settings.snapshot().approval()&&!current.access().allowed(peer.id());
            if(!token.isEmpty()){
                var invite=InvitationStore.inspect(token,Clock.systemUTC());
                if(!invite.islandId().equals(current.islandId())||!invite.issuer().id().equals(identity.profile().id())
                        ||!invite.targetId().isEmpty()&&!invite.targetId().equals(peer.id()))throw new IOException("Другое приглашение");
                approval|=invite.requireApproval();
            }else if(!current.access().allowed(peer.id())
                    &&current.access().privacy()!=IslandAccess.Privacy.PUBLIC
                    &&!(current.access().privacy()==IslandAccess.Privacy.FRIENDS&&runtime.friends().isConfirmed(peer.id())))throw new IOException("Требуется приглашение");
            if(server.getPlayerList().getPlayerCount()>=current.limit())throw new IOException("Остров заполнен");
            profiles.put(peer.id(),peer);peerManifests.put(attempt,remote.deepCopy());
            joinAuthorities.put(attempt,new JoinAuthority(peer.id(),settings.snapshot().revision(),false));
            return Map.entry(approval,descriptor(current));
        }).thenCompose(decision->onClient(()->{
            if(current!=host)throw new IOException("Сеанс закрыт");
            boolean needsApproval=decision.getKey();PeerSignaling.Descriptor descriptor=decision.getValue();
            if(!needsApproval)return CompletableFuture.completedFuture(new PeerSignaling.Decision(true,descriptor,""));
            if(approvals.size()>=16)throw new IOException("Очередь подтверждений заполнена");
            CompletableFuture<PeerSignaling.Decision> answer=new CompletableFuture<>();
            approvals.put(attempt,new PendingJoin(peer,attempt,remote.deepCopy(),token,answer,System.nanoTime()+TimeUnit.MINUTES.toNanos(5)));
            refresh();return answer;
        })).thenCompose(Function.identity()).exceptionally(failure->{
            IslandJoinFailure reason=IslandJoinFailure.find(failure);
            return new PeerSignaling.Decision(false,null,(reason==null?IslandJoinFailure.Kind.HOST_REJECTED:reason.kind()).wireCode());
        });
    }
    private PeerSignaling.Descriptor descriptor(OnlineGameHost current)throws IOException{return new PeerSignaling.Descriptor(current.sessionId(),current.epoch(),current.islandId(),manifest,current.compatibilityDigest(),current.authorityDescriptor());}
    private CompletableFuture<JsonObject> approve(JsonObject args,boolean allow)throws IOException{
        UUID id=UUID.fromString(OnlineJson.string(args,"id",36));PendingJoin join=approvals.remove(id);
        if(join==null||host==null)throw new IOException("Запрос уже завершён");
        OnlineGameHost current=host;
        return onServer(hostServer,()->{
            if(!current.access().can(identity.profile().id(),IslandAccess.Permission.INVITE))throw new IOException("Подтверждать вход запрещено");
            if(policy==null||!policyAllows(current,policy,join.peer().id()))throw new IOException("Настройки входа изменились");
            joinAuthorities.put(join.attempt(),new JoinAuthority(join.peer().id(),policy.snapshot().revision(),allow));
            return descriptor(current);
        }).thenCompose(desc->(allow?prepareNetwork():CompletableFuture.<Void>completedFuture(null)).thenCompose(ignored->onClient(()->{
            join.result().complete(new PeerSignaling.Decision(allow,allow?desc:null,allow?"":"Вход отклонён"));refresh();return OnlineJson.ok();
        }))).whenComplete((value,failure)->{if(failure!=null)join.result().complete(new PeerSignaling.Decision(false,null,"Нет права подтверждения"));});
    }
    /** One owner of the physical stream; both peers start HELLO before any independent Node HTTP wait. */
    private WebRtcMultiplex openMultiplex(PeerSignaling.Connected connected,Object fence)throws IOException{
        if(closed||networkToken!=fence)throw new IOException("Подключение отменено");
        final WebRtcMultiplex multiplex;
        synchronized(multiplexes){
            // Reserve before starting a reader. A rejected duplicate must never touch the existing physical link.
            if(multiplexes.containsKey(connected.attempt()))throw new IOException("Игровой канал уже создан");
            multiplex=WebRtcMultiplex.open(connected.reliableLink());multiplexes.put(connected.attempt(),multiplex);
        }
        multiplex.closed().whenComplete((v,f)->multiplexes.remove(connected.attempt(),multiplex));
        if(closed||networkToken!=fence){multiplexes.remove(connected.attempt(),multiplex);multiplex.close();throw new IOException("Подключение отменено");}
        return multiplex;
    }
    private CompletableFuture<Void> attachHost(PeerSignaling.Connected connected,Object fence){
        OnlineGameHost current=host;MinecraftServer server=hostServer;
        if(networkToken!=fence||current==null||server==null||connected.descriptor().epoch()!=current.epoch()||!connected.descriptor().sessionId().equals(current.sessionId()))return CompletableFuture.failedFuture(new IOException("Остров закрыт"));
        final WebRtcMultiplex multiplex;try{multiplex=openMultiplex(connected,fence);}catch(IOException denied){return CompletableFuture.failedFuture(denied);}
        return WebRtcSocketBridge.attach(multiplex.nativeStream(),current.nativeAddress()).thenCompose(binding->onServer(server,()->{
            try{
                if(networkToken!=fence||current!=host||connected.descriptor().epoch()!=current.access().epoch())throw new IOException("Подключение отменено");
                JsonObject peer=peerManifests.remove(connected.attempt());if(peer==null)throw new IOException("Нет проверенного fingerprint");
                current.registerIngress(binding.upstreamLocalAddress(),connected.peerProof(),connected.attempt(),connected.invitationToken(),connected.approved(),peer);
                profileProofs.put(connected.peerProof().signer().id(),connected.peerProof());
                if(current!=host)throw new IOException("Сеанс сменился");
                bridges.put(connected.attempt(),binding);
                binding.closed().whenComplete((value,failure)->{multiplex.close();bridges.remove(connected.attempt(),binding);joinAuthorities.remove(connected.attempt());if(!server.isStopped())try{server.executeIfPossible(()->current.unregisterIngress(binding.upstreamLocalAddress()));}catch(RejectedExecutionException ignored){}});
                binding.start();return (Void)null;
            }catch(Exception failure){binding.close();throw failure;}
        })).whenComplete((value,failure)->{if(failure!=null)multiplex.close();});
    }
    private CompletableFuture<Void> attachClient(PeerSignaling.Connected connected,Object fence){
        final WebRtcMultiplex multiplex;try{multiplex=openMultiplex(connected,fence);}catch(IOException denied){return CompletableFuture.failedFuture(denied);}
        java.util.concurrent.atomic.AtomicReference<OnlineGuestAuthority> source=new java.util.concurrent.atomic.AtomicReference<>();
        return onClient(()->{
            if(closed||networkToken!=fence||client.level!=null||host!=null||expected!=null)throw new IOException("Подключение отменено");
            OnlineGuestAuthority previous=guestAuthority;guestAuthority=null;if(previous!=null)previous.close();
            OnlineGuestAuthority observer=new OnlineGuestAuthority(runtime,connected.descriptor(),connected.peerProof().signer().id(),message->onClient(()->{
                if(guestAuthority!=source.get())return null;closeNetwork(true);status=message;refresh();return null;
            }));
            source.set(observer);guestAuthority=observer;return observer.ready();
        }).thenCompose(Function.identity()).thenCompose(observer->onClient(()->{
            if(guestAuthority!=observer)throw new IOException("Проверка ведущего отменена");observer.requireUsable();
            if(closed||networkToken!=fence||client.level!=null||host!=null||expected!=null)throw new IOException("Игровой клиент уже занят");
            OnlineCompatibility.requireCompatible(manifest,connected.descriptor().hostCompatibility());
            if(resume!=null&&(resumeTarget==null||!resumeTarget.proof().signer().id().equals(connected.peerProof().signer().id())
                    ||!resumeTarget.islandId().equals(connected.descriptor().islandId())||connected.descriptor().epoch()<resumeTarget.epoch()))throw new IOException("Восстановление относится к другому острову");
            lastHostProof=connected.peerProof();profileProofs.put(connected.peerProof().signer().id(),connected.peerProof());
            WebRtcSocketBridge.Binding binding=WebRtcSocketBridge.listen(multiplex.nativeStream());
            OnlineConnectionClient.ExpectedHost attachedExpected=new OnlineConnectionClient.ExpectedHost(connected.peerProof().signer().id(),connected.descriptor().sessionId(),connected.descriptor().epoch(),connected.descriptor().compatDigest(),observer);expected=attachedExpected;
            profiles.put(connected.peerProof().signer().id(),connected.peerProof().signer());bridges.put(connected.attempt(),binding);
            binding.closed().whenComplete((value,failure)->{multiplex.close();onClient(()->{
                bridges.remove(connected.attempt(),binding);if(networkToken==fence&&expected==attachedExpected){
                    status="Игровой канал закрыт. Повторный вход требует новой проверки.";phase="disconnected";refresh();
                }return null;
            });});
            binding.start();phase="native_login";status="Защищённый канал готов. Minecraft проверяет допуск до загрузки игрока.";
            String address="127.0.0.1:"+binding.localAddress().getPort();
            ServerData data=new ServerData("DarkWinter",address,ServerData.Type.OTHER);
            data.setResourcePackStatus(ServerData.ServerPackStatus.DISABLED);
            ConnectScreen.startConnecting(client.gui.screen()==null?new TitleScreen():client.gui.screen(),client,new ServerAddress("127.0.0.1",binding.localAddress().getPort()),data,false,null);
            refresh();return (Void)null;
        })).whenComplete((value,failure)->{if(failure!=null){multiplex.close();onClient(()->{
            if(guestAuthority==source.get()&&networkToken==fence){closeNetwork(true);phase="failed";status="Служебный узел не подтвердил подключение к острову.";refresh();}return null;
        });}});
    }
    /** Same authenticated action path used by the editor; completion is the authoritative server reply. */
    public CompletableFuture<JsonObject> labCommand(String action,String value){
        if(action==null||action.length()>64||value!=null&&value.length()>262144)return CompletableFuture.failedFuture(new IOException("Слишком большая команда лаборатории"));
        JsonObject body=new JsonObject();body.addProperty("op","lab");body.addProperty("action",action);body.addProperty("value",value==null?"":value);return rpc(body);
    }
    private CompletableFuture<JsonObject> rpc(JsonObject body){
        OnlineGameHost current=host;MinecraftServer server=hostServer;
        if(current!=null&&server!=null){
            UUID local=client.player==null?null:client.player.getUUID();
            return onServer(server,()->{
                ServerPlayer player=local==null?null:server.getPlayerList().getPlayer(local);
                if(player==null||current!=host)throw new IOException("Нет участника сеанса");
                return serverCommand(server,current,player,UUID.randomUUID(),body);
            }).thenCompose(Function.identity()).whenComplete((value,failure)->onClient(()->{refreshLocal();return null;}));
        }
        try{
            if(requests.size()>=4)throw new IOException("Дождитесь ответа");
            UUID id=OnlineConnectionClient.command(body);CompletableFuture<JsonObject> reply=new CompletableFuture<>();
            requests.put(id,reply);deadlines.put(id,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));return reply;
        }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
    }
    private CompletableFuture<JsonObject> serverCommand(MinecraftServer server,OnlineGameHost current,ServerPlayer player,UUID request,JsonObject body){
        try{
            if(current!=host||OnlineGameHost.get(server)!=current||!current.can(player,IslandAccess.Permission.VIEW))throw new IOException("Нет актуального допуска");
            String op=OnlineJson.string(body,"op",16);
            if(op.equals("lab"))return TestingOnlineBridge.command(server,player,request,body);
            if(!op.equals("game"))throw new IOException("Неизвестная команда");
            String actor=current.actor(player),action=OnlineJson.string(body,"action",64);JsonObject args=OnlineJson.child(body,"args");
            if(current.transitioning()&&!Set.of("transition_ack","profile_update","chat_send").contains(action))throw new IOException("Общий мир сохраняется; дождитесь возвращения участников");
            IslandAccess acl=current.access();String target=OnlineJson.optional(args,"id","",64);
            switch(action){
                case "transition_ack"->{lifecycle.acknowledge(server,current,actor,args);}
                case "profile_update"->{
                    OnlineJson.keys(args,"proof");IdentityStore.SignedPayload proof=OnlineJson.GSON.fromJson(OnlineJson.child(args,"proof"),IdentityStore.SignedPayload.class);
                    IdentityProfile updated=IdentityStore.validateProfileProof(proof),previous=profiles.get(actor);
                    if(!updated.id().equals(actor)||previous!=null&&(updated.revision()<previous.revision()||!updated.publicKey().equals(previous.publicKey())||!updated.encryptionPublicKey().equals(previous.encryptionPublicKey())))throw new IOException("Другая или устаревшая личность");
                    profiles.put(actor,updated);profileProofs.put(actor,proof);
                }
                case "voice_parameters"->{OnlineVoiceWorld state=voiceWorld;if(state==null)throw new IOException("Нет голосового мира");return CompletableFuture.completedFuture(state.settings(player,args));}
                case "privacy_update"->{
                    if(!OnlineJson.string(args,"scope",16).equals("island")||policy==null)throw new IOException("Неверная область");
                    String key=OnlineJson.string(args,"key",32);JsonElement value=args.get("value");if(value==null||!value.isJsonPrimitive())throw new IOException("Нет значения");
                    try{policy.update(actor,key,value.getAsString());acl.configure(actor,accessPrivacy(policy.snapshot().admission()),acl.limit());}
                    catch(IOException failure){current.close();throw failure;}
                    for(ServerPlayer peer:List.copyOf(server.getPlayerList().getPlayers()))if(!policyAllows(current,policy,current.actor(peer)))kick(server,current,current.actor(peer),"Доступ к острову изменён");
                }
                case "participant_role"->{IslandAccess.Role role=IslandAccess.Role.valueOf(OnlineJson.string(args,"role",16).toUpperCase(Locale.ROOT));if(role==IslandAccess.Role.OWNER)throw new IOException("Владелец неизменяем");acl.setRole(actor,target,role);}
                case "participant_permission"->acl.setPermission(actor,target,IslandAccess.Permission.valueOf(OnlineJson.string(args,"permission",32).toUpperCase(Locale.ROOT)),OnlineJson.bool(args,"allowed",false));
                case "island_allow"->acl.allow(actor,target);
                case "island_block"->{acl.block(actor,target);kick(server,current,target,"Доступ к острову запрещён");}
                case "island_unblock"->acl.unblock(actor,target);
                case "participant_kick"->{if(!acl.can(actor,IslandAccess.Permission.KICK)||target.equals(acl.ownerId()))throw new IOException("Нет права исключения");kick(server,current,target,"Вы исключены из сеанса");}
                case "invite_revoke"->{
                    if(!acl.can(actor,IslandAccess.Permission.INVITE))throw new IOException("Нет права отзыва");
                    return runtime.revokeInvitation(OnlineJson.string(args,"id",36)).thenApply(value->OnlineJson.ok());
                }
                case "invite_create"->{
                    if(!acl.can(actor,IslandAccess.Permission.INVITE))throw new IOException("Нет права приглашения");
                    InvitationStore.Role role=InvitationStore.Role.valueOf(OnlineJson.string(args,"role",16).toUpperCase(Locale.ROOT));
                    String recipient=OnlineJson.optional(args,"recipient","",64);if(!recipient.isEmpty()&&!recipient.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Некорректный адресат");
                    String grantTarget=recipient.isEmpty()?"dw1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA":recipient;
                    acl.validateRoleGrant(actor,grantTarget,IslandAccess.Role.valueOf(role.name()));
                    long ttl=OnlineJson.integer(args,"ttlMinutes",1,10080)*60000;
                    int uses=OnlineJson.bool(args,"oneUse",true)?1:(int)OnlineJson.integer(args,"maxUses",1,10000);
                    boolean approve=OnlineJson.bool(args,"approval",true);
                    return runtime.issueInvitation(new InvitationStore.InvitationOptions(current.islandId(),role,recipient,ttl,uses,approve)).thenApply(created->{
                        JsonObject value=OnlineJson.ok();value.addProperty("id",created.invitation().id());value.addProperty("token",created.token());value.addProperty("deliveryHandled",true);return value;
                    });
                }
                case "chat_send"->{
                    String channel=OnlineJson.string(args,"channel",16),text=OnlineJson.string(args,"text",2048);
                    if(!Set.of("island","editor").contains(channel)||text.isBlank()||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new IOException("Недопустимое сообщение");
                    if(channel.equals("editor")&&!acl.can(actor,IslandAccess.Permission.OBJECTS))throw new IOException("Канал редактора недоступен");
                    Deque<JsonObject> messages=chats.computeIfAbsent(current.sessionId().toString(),id->new ConcurrentLinkedDeque<>());
                    JsonObject row=new JsonObject();row.addProperty("id",request.toString());row.addProperty("sender",actor);row.addProperty("nickname",profiles.containsKey(actor)?profiles.get(actor).nickname():actor);
                    row.addProperty("channel",channel);row.addProperty("text",text);row.addProperty("state","received_by_host");row.addProperty("createdAt",System.currentTimeMillis());
                    messages.addLast(row);while(messages.size()>128)messages.removeFirst();
                    // Reply means authoritative host consumption. Other clients observe the message through authenticated state.
                }
                default->throw new IOException("Этот маршрут сеанса пока недоступен");
            }return CompletableFuture.completedFuture(OnlineJson.ok());
        }catch(Exception failure){return CompletableFuture.completedFuture(OnlineJson.error("Действие отклонено: проверьте права и текущее состояние сеанса."));}
    }
    private static void kick(MinecraftServer server,OnlineGameHost host,String target,String reason){
        if(target.equals(host.ownerId()))return;
        for(ServerPlayer peer:server.getPlayerList().getPlayers())if(target.equals(host.actor(peer)))peer.connection.disconnect(Component.literal("DarkWinter: "+reason));
    }
    private JsonObject labState(MinecraftServer server,OnlineGameHost current,ServerPlayer player){
        JsonObject lab=TestingOnlineBridge.snapshot(server,player),online=new JsonObject(),session=new JsonObject();
        IslandAccess acl=current.access();String actor=current.actor(player);
        session.addProperty("active",true);session.addProperty("host",current.localIdentityId());session.addProperty("owner",current.ownerId());
        session.addProperty("islandId",current.islandId());session.addProperty("limit",current.limit());
        session.add("allowlist",OnlineJson.GSON.toJsonTree(acl.snapshot().allowlist()));session.add("blocked",OnlineJson.GSON.toJsonTree(acl.snapshot().blocked()));
        JsonObject privacy=new JsonObject();IslandPolicy settings=policy;
        if(settings!=null){var preferences=settings.snapshot();privacy.addProperty("visibility",preferences.visibility().name().toLowerCase(Locale.ROOT));privacy.addProperty("access",preferences.admission().name().toLowerCase(Locale.ROOT));privacy.addProperty("approval",preferences.approval());}
        session.add("privacy",privacy);online.add("session",session);
        OnlineVoiceWorld audio=voiceWorld;if(audio!=null)online.add("voiceState",audio.state(player));
        JsonArray participants=new JsonArray();
        for(ServerPlayer peer:server.getPlayerList().getPlayers()){
            String id=current.actor(peer);if(id==null||id.isEmpty())continue;JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("gameId",peer.getUUID().toString());
            IdentityProfile profile=profiles.get(id);row.addProperty("nickname",profile==null?id:profile.nickname());row.addProperty("model",profile==null?"":profile.model().name().toLowerCase(Locale.ROOT));row.addProperty("profileRevision",profile==null?0:profile.revision());
            row.addProperty("role",acl.roleOf(id).name().toLowerCase(Locale.ROOT));row.addProperty("status","В игровом мире");
            JsonArray permissions=new JsonArray();for(IslandAccess.Permission permission:IslandAccess.Permission.values()){
                JsonObject grant=new JsonObject();grant.addProperty("id",permission.name().toLowerCase(Locale.ROOT));grant.addProperty("label",permissionLabel(permission));
                grant.addProperty("allowed",acl.can(id,permission));grant.addProperty("editable",acl.can(actor,IslandAccess.Permission.ASSIGN_ROLES)&&!id.equals(acl.ownerId()));permissions.add(grant);
            }row.add("permissions",permissions);participants.add(row);
        }online.add("participants",participants);
        JsonArray messages=new JsonArray();for(JsonObject message:chats.getOrDefault(current.sessionId().toString(),new ConcurrentLinkedDeque<>())){
            if("editor".equals(message.get("channel").getAsString())&&!acl.can(actor,IslandAccess.Permission.OBJECTS))continue;messages.add(message.deepCopy());
        }online.add("messages",messages);lab.add("online",online);return lab;
    }
    private void refreshLocal(){
        MinecraftServer server=hostServer;OnlineGameHost current=host;if(current==null||server==null||stateQueued||client.player==null)return;
        UUID local=client.player.getUUID();stateQueued=true;
        onServer(server,()->{ServerPlayer player=server.getPlayerList().getPlayer(local);if(current!=host||player==null)throw new IOException("Сеанс сменился");
            JsonObject root=new JsonObject();root.add("host",current.snapshot(current.actor(player)));root.add("lab",labState(server,current,player));return root;
        }).whenComplete((value,failure)->onClient(()->{
            stateQueued=false;if(failure!=null&&host==current)stateProblem=failureCode(failure);
            if(failure==null&&host==current){stateProblem="";packet=value.deepCopy();OnlineTestingView.acceptLocal(server,child(packet,"lab"));OnlineAvatarSkins.acceptLocal(server,packet);refresh();}return null;
        }));
    }
    private static String failureCode(Throwable failure){
        Throwable cause=failure;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();
        String code=cause.getClass().getSimpleName();
        for(StackTraceElement frame:cause.getStackTrace())if(frame.getClassName().startsWith("com.dark.winter.")){code+="@"+frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber();break;}
        return code;
    }
    private void acceptState(JsonObject value){
        packet=value.deepCopy();OnlineTestingView.acceptPacket(value);OnlineAvatarSkins.acceptPacket(value);
        if(OnlineConnectionClient.authenticated()){
            if(lastHostProof!=null){JsonObject core=child(value,"host");try{resumeTarget=new ResumeTarget(lastHostProof,OnlineJson.string(core,"islandId",36),OnlineJson.integer(core,"epoch",0,Long.MAX_VALUE));}catch(IOException invalid){throw new IllegalArgumentException(invalid);}}
            resume=null;remoteTransition=null;phase="connected";status="Личность подтверждена. Игровой мир подключён.";
        }
        refresh();
    }
    private void reply(JsonObject value){
        try{UUID id=UUID.fromString(OnlineJson.string(value,"requestId",36));CompletableFuture<JsonObject> pending=requests.remove(id);deadlines.remove(id);if(pending!=null)pending.complete(value.deepCopy());}
        catch(IOException|IllegalArgumentException ignored){}
    }
    public void tick(){
        if(closed)return;ticks++;coordinator.tick();lifecycle.tick();if(detachPending&&!coordinator.busy()){coordinator.detach();detachPending=false;}OnlineTestingView.tick();OnlineAvatarSkins.tick();updateVoice();voice.tick();
        OnlineGuestAuthority observedAuthority=guestAuthority;
        if(observedAuthority!=null&&expected!=null&&!observedAuthority.usable()){closeNetwork(true);status="Подтверждение ведущего истекло; подключение закрыто.";refresh();}
        if(authority!=null&&host==null&&!opening&&!lifecycle.active()){String message=status;closeNetwork(false);status=message;refresh();}
        boolean testing=TestingServer.isTesting(client.getSingleplayerServer());
        if(observedLevel!=client.level||observedServer!=client.getSingleplayerServer()||observedTesting!=testing){
            observedLevel=client.level;observedServer=client.getSingleplayerServer();observedTesting=testing;refresh();
        }
        if(host!=null&&(hostServer!=client.getSingleplayerServer()||OnlineGameHost.get(hostServer)!=host))closeNetwork(false,lifecycle.active());
        if(host!=null&&ticks%20==0)refreshLocal();
        if((host!=null||expected!=null)&&ticks%200==0&&System.nanoTime()>=iceRefreshAt&&preparingNetwork==null)prepareNetwork().exceptionally(unavailable->null);
        if(expected!=null&&OnlineConnectionClient.authenticated()&&ticks%40==0)try{OnlineConnectionClient.requestState();}catch(IOException ignored){}
        long now=System.nanoTime();
        reconnect(now);
        for(UUID id:List.copyOf(deadlines.keySet()))if(now>deadlines.get(id)){deadlines.remove(id);CompletableFuture<JsonObject> request=requests.remove(id);if(request!=null)request.completeExceptionally(new IOException("Нет подтверждения сервера; повтор может изменить результат"));}
        for(PendingJoin join:List.copyOf(approvals.values()))if(now>join.expires()){approvals.remove(join.attempt());join.result().complete(new PeerSignaling.Decision(false,null,"Время подтверждения истекло"));refresh();}
        if(resume==null&&expected!=null&&joining==null&&client.level==null&&bridges.isEmpty()){expected=null;OnlineConnectionClient.clear();packet=new JsonObject();refresh();}
    }
    private void updateVoice(){
        if(identity==null){voice.update(null);return;}
        JsonObject core=child(packet,"host");boolean local=host!=null&&OnlineGameHost.get(hostServer)==host,remote=OnlineConnectionClient.authenticated();
        if(!local&&!remote||!core.has("sessionId")){voice.update(null);return;}
        try{
            Map<String,IdentityStore.SignedPayload> participants=new HashMap<>();
            for(JsonElement entry:array(child(child(packet,"lab"),"online"),"participants")){
                if(!entry.isJsonObject())continue;String id=OnlineJson.string(entry.getAsJsonObject(),"id",64);IdentityStore.SignedPayload proof=profileProofs.get(id);
                if(proof!=null)participants.put(id,proof);
            }
            IdentityStore.SignedPayload own=profileProofs.get(identity.profile().id());if(own==null){voice.update(null);return;}participants.put(identity.profile().id(),own);
            String leading=OnlineJson.string(core,"hostId",64);if(!participants.containsKey(leading)){voice.update(null);return;}
            voice.update(new OnlineVoiceClient.SessionView(UUID.fromString(OnlineJson.string(core,"sessionId",36)),OnlineJson.integer(core,"epoch",0,Long.MAX_VALUE),local,leading,identity.profile().id(),participants));
        }catch(IOException|IllegalArgumentException invalid){voice.update(null);}
    }
    private static String string(JsonObject value,String key,String fallback){JsonElement item=value.get(key);return item!=null&&item.isJsonPrimitive()&&item.getAsJsonPrimitive().isString()?item.getAsString():fallback;}
    private static IslandAccess.Privacy accessPrivacy(IslandPolicy.Admission admission){return switch(admission){
        case FRIENDS->IslandAccess.Privacy.FRIENDS;case INVITED,CODE->IslandAccess.Privacy.INVITE_ONLY;case OWNER_ONLY->IslandAccess.Privacy.PRIVATE;
    };}
    private boolean policyAllows(OnlineGameHost current,IslandPolicy settings,String peer)throws IOException{
        if(peer==null||!lifecycle.admissionAllowed(current,peer)||runtime.friends().isBlocked(peer)||current.access().blocked(peer))return false;
        return switch(settings.snapshot().admission()){
            case OWNER_ONLY->peer.equals(current.ownerId());
            case FRIENDS->peer.equals(current.ownerId())||current.access().allowed(peer)||runtime.friends().isConfirmed(peer);
            case INVITED,CODE->true; // The native admission layer still requires an authenticated allowance or valid signed invitation.
        };
    }
    private void remoteDisconnected(DisconnectionDetails details){
        if(closed||resumeTarget==null||host!=null)return;
        String key=details!=null&&details.reason().getContents() instanceof TranslatableContents text?text.getKey():"";
        boolean transition=remoteTransition!=null&&System.nanoTime()<remoteTransition.deadline()&&resumeTarget.islandId().equals(remoteTransition.island())&&resumeTarget.proof().signer().id().equals(remoteTransition.host());
        if(!transition&&!Set.of("disconnect.endOfStream","disconnect.timeout","disconnect.genericReason","disconnect.closed","disconnect.lost","multiplayer.disconnect.generic").contains(key)){
            closeNetwork(false);return; // A deliberate leave/kick/ban/host close must stay closed.
        }
        for(WebRtcSocketBridge.Binding binding:bridges.values())binding.close();bridges.clear();
        OnlineConnectionClient.clear();expected=null;packet=new JsonObject();
        for(CompletableFuture<JsonObject> request:requests.values())request.completeExceptionally(new IOException("Соединение потеряно; команда не повторялась автоматически"));
        requests.clear();deadlines.clear();
        if(resume==null)resume=new ResumeState(transition?Math.max(1,remoteTransition.deadline()-System.nanoTime()):TimeUnit.MINUTES.toNanos(2));
        phase="reconnecting";status="Связь потеряна. Восстановление проверенного острова; команды повторно не отправляются.";refresh();
    }
    private void reconnect(long now){
        ResumeState current=resume;ResumeTarget target=resumeTarget;
        if(current==null||target==null)return;
        if(now>=current.deadline){closeNetwork(false);phase="disconnected";status="Время восстановления истекло. Можно подключиться заново.";refresh();return;}
        if(client.level!=null||joining!=null||!bridges.isEmpty()||now<current.next)return;
        if(signaling==null||manifest==null){resume=null;status="Нет проверенного канала восстановления.";refresh();return;}
        expected=null;OnlineConnectionClient.clear();current.attempts++;
        current.next=now+TimeUnit.SECONDS.toNanos(Math.min(20,1L<<Math.min(current.attempts,4)));
        long version=generation;PeerSignaling broker=signaling;
        CompletableFuture<PeerSignaling.Connected> task=prepareNetwork().thenCompose(ignored->{if(version!=generation||resume!=current)return CompletableFuture.failedFuture(new IOException("Восстановление отменено"));return broker.connect(target.proof(),"",manifest);});
        joining=task;phase="reconnecting";status="Повторная проверка личности и сохранённого допуска. Попытка "+current.attempts;refresh();
        task.whenComplete((answer,failure)->onClient(()->{
            if(version!=generation)return null;
            if(joining==task)joining=null;
            if(resume!=current)return null;
            if(failure!=null){phase="reconnecting";status="Ведущий пока недоступен. Повтор ограничен временем; можно отменить.";refresh();}
            return null;
        }));
    }
    private static String permissionLabel(IslandAccess.Permission permission){return switch(permission){
        case VIEW->"Просмотр острова";case VOICE_SPEAK->"Передача голоса";case VOICE_LISTEN->"Прослушивание голоса";case BUILD->"Строительство";case BREAK->"Разрушение блоков";case INTERACT->"Взаимодействие с миром";
        case TERRAIN->"Редактирование рельефа";case BIOME->"Биом и покрытие";case WATER->"Вода";case VEGETATION->"Растительность";
        case OBJECTS->"Объекты редактора";case SYSTEMS->"Настройки игровых систем";case START_TEST->"Запуск испытания";case STOP_TEST->"Остановка испытания";case KEEP_TEST->"Сохранение результатов испытания";
        case MANAGE_ZONES->"Защищённые зоны";case HISTORY->"Просмотр журнала";case UNDO_OWN->"Отмена своих действий";case UNDO_ANY->"Отмена любых действий";
        case VERSIONS->"Версии острова";case IMPORT->"Импорт";case EXPORT->"Экспорт";case INVITE->"Приглашение участников";case KICK->"Исключение участников";
        case ASSIGN_ROLES->"Роли и разрешения";case TRANSFER_HOST->"Передача ведения";case GLOBAL_VOICE->"Общий голосовой канал";case SCENARIOS->"Сценарии";
    };}
    private static String route(WebRtcSocketBridge.Binding binding){return "encrypted";} // Native ICE route is reported only when measured, never guessed from connectivity.
    private void refresh(){if(runtime!=null)runtime.refreshGame();}
    private void closeNativeAlias(){
        OnlineNativeAlias.Scope scope=nativeAliasScope;nativeAliasScope=null;
        if(scope!=null)try{scope.close();}catch(IOException failure){status="Подготовленная копия сохранена; её открытие отменено.";}
    }
    private void closeNetwork(boolean leave){closeNetwork(leave,false);}
    private void closeNetwork(boolean leave,boolean preserveTransition){
        if(!preserveTransition){lifecycle.cancel("Совместный переход отменён");remoteTransition=null;if(coordinator.busy())detachPending=true;else coordinator.detach();}
        if(!preserveTransition||nativeAliasScope!=null&&nativeAliasScope.adopted())closeNativeAlias();
        generation++;networkToken=null;preparingNetwork=null;iceRefreshAt=0;networkOptions=WebRtcTransport.Config.localOnly();opening=false;resume=null;resumeTarget=null;lastHostProof=null;
        if(joining!=null){joining.cancel(false);joining=null;}
        for(PendingJoin join:approvals.values())join.result().complete(new PeerSignaling.Decision(false,null,"Сеанс закрыт"));approvals.clear();
        if(signaling!=null){signaling.close();signaling=null;}for(WebRtcSocketBridge.Binding binding:bridges.values())binding.close();bridges.clear();
        for(WebRtcMultiplex multiplex:multiplexes.values())multiplex.close();multiplexes.clear();
        if(roomGame!=null){roomGame.close();roomGame=null;}
        if(transport!=null){transport.close();transport=null;}
        OnlineGameHost previous=host;host=null;policy=null;voiceWorld=null;joinAuthorities.clear();voice.update(null);OnlineAvatarSkins.clear();MinecraftServer server=hostServer;hostServer=null;
        if(previous!=null)previous.close();
        if(!preserveTransition){OnlineAuthoritySession previousAuthority=authority;authority=null;if(previousAuthority!=null)previousAuthority.close();}
        OnlineGuestAuthority previousGuest=guestAuthority;guestAuthority=null;if(previousGuest!=null)previousGuest.close();
        boolean remote=expected!=null;expected=null;OnlineConnectionClient.clear();peerManifests.clear();
        for(CompletableFuture<JsonObject> request:requests.values())request.completeExceptionally(new IOException("Сеанс закрыт"));requests.clear();deadlines.clear();
        packet=new JsonObject();if(!preserveTransition)chats.clear();phase="offline";status=previous!=null&&previous.attested()&&!preserveTransition?"Совместный сеанс закрыт. Для одиночного редактирования сохраните и заново откройте остров.":"Совместный сеанс закрыт.";refresh();
        if(leave&&remote&&client.level!=null)client.disconnectWithSavingScreen();
    }
    @Override public void close(){if(closed)return;closeNetwork(false);closed=true;coordinator.close();voice.close();OnlineConnectionClient.setRemoteDisconnectHandler(null);OnlineConnectionClient.setLifecycleHandler(null);OnlineConnectionClient.setChatHandler(null);OnlineTestingView.installAction(null);}
    private <T> CompletableFuture<T> onClient(Callable<T> work){
        CompletableFuture<T> result=new CompletableFuture<>();client.execute(()->{try{result.complete(work.call());}catch(Throwable failure){result.completeExceptionally(failure);}});return result;
    }
    private static <T> CompletableFuture<T> onServer(MinecraftServer server,Callable<T> work){
        CompletableFuture<T> result=new CompletableFuture<>();server.execute(()->{try{result.complete(work.call());}catch(Throwable failure){result.completeExceptionally(failure);}});return result;
    }
    private static JsonObject child(JsonObject root,String key){JsonElement value=root.get(key);return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private static JsonArray array(JsonObject root,String key){JsonElement value=root.get(key);return value!=null&&value.isJsonArray()?value.getAsJsonArray():new JsonArray();}
    private static boolean truth(JsonObject root,String key){JsonElement value=root.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()&&value.getAsBoolean();}
}
