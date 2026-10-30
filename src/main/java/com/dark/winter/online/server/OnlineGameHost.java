// Server-thread owner of one explicit, marked Testing session.
package com.dark.winter.online.server;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.authority.*;
import com.dark.winter.online.activation.PendingAttestedStartup;
import com.dark.winter.online.replica.NativeActorAliases;
import com.dark.winter.online.world.IslandAuthorityFiles;
import com.dark.winter.online.friends.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.network.OnlinePayload;
import com.dark.winter.online.network.OnlineCompatibility;
import com.dark.winter.online.world.IslandAccess;
import com.dark.winter.mixin.online.OnlineCommonListenerAccess;
import com.dark.winter.mixin.online.OnlineConfigurationAccess;
import com.dark.winter.mixin.online.OnlineConnectionListenerAccess;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFuture;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Server-thread owner of one explicit, marked Testing session. Transport identity is never inferred from a nickname/IP. */
public final class OnlineGameHost implements AutoCloseable {
    private static final Map<MinecraftServer,OnlineGameHost> HOSTS=new ConcurrentHashMap<>();
    private static final Map<String,Object> AUTHORITY_RESERVATIONS=new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> ATTESTED_FAILURES=ConcurrentHashMap.newKeySet();
    public static final ConfigurationTask.Type AUTH_TASK=new ConfigurationTask.Type("darkwinter:native_identity_v1");
    @FunctionalInterface public interface CommandHandler {
        /** Called on the server thread. The adapter MUST enforce the permission for its individual command. */
        CompletableFuture<JsonObject> handle(ServerPlayer actor,UUID requestId,JsonObject command);
    }
    @FunctionalInterface public interface AdmissionCheck {void validate(String peer,UUID attempt,String token,boolean approved)throws IOException;}
    public static final class Admission {
        private final IdentityProfile peer;private final UUID attemptId,gameId;private final String token;private final boolean approved;
        private final long created=System.nanoTime();private Connection connection;private boolean authenticated,joined;
        private byte[] challenge;private long deadline;private ConfigurationTask task;
        private Admission(IdentityProfile peer,UUID attemptId,String token,boolean approved,UUID nativePlayer)throws IOException {
            this.peer=peer;this.attemptId=attemptId;this.token=token;this.approved=approved;this.gameId=nativePlayer;
        }
        public String peerId(){return peer.id();}public UUID gameId(){return gameId;}public boolean authenticated(){return authenticated;}
    }
    private record Reply(String digest,JsonObject value){}
    private final MinecraftServer server;private final IdentityStore identity;private final InvitationStore invitations;
    private final UUID localPlayer,sessionId;private final IslandAccess access;private final long epoch;
    private final OnlineHostPermit permit;private final Object authorityReservation;
    private final NativeActorAliases nativeActors;
    private volatile boolean authorityFailed;
    private final int sessionLimit;private final String compatibilityDigest;private final JsonObject compatibility;
    private final ChannelFuture listener;private final InetSocketAddress address;
    private final Map<InetSocketAddress,Admission> ingress=new HashMap<>();
    private final Map<Connection,Admission> connections=new IdentityHashMap<>();
    private final Map<UUID,String> actors=new ConcurrentHashMap<>();
    private final Map<UUID,GameType> modesBeforeRestriction=new HashMap<>();
    private Predicate<String> admissionPolicy;private AdmissionCheck admissionCheck;
    private static final Set<IslandAccess.Permission> WORLD_MUTATIONS=EnumSet.of(IslandAccess.Permission.BUILD,IslandAccess.Permission.BREAK,IslandAccess.Permission.INTERACT,IslandAccess.Permission.TERRAIN,IslandAccess.Permission.BIOME,IslandAccess.Permission.WATER,IslandAccess.Permission.VEGETATION,IslandAccess.Permission.OBJECTS,IslandAccess.Permission.SYSTEMS,IslandAccess.Permission.MANAGE_ZONES,IslandAccess.Permission.UNDO_OWN,IslandAccess.Permission.UNDO_ANY,IslandAccess.Permission.IMPORT,IslandAccess.Permission.SCENARIOS);
    private final Map<UUID,OnlinePayload.Assembler> assemblers=new HashMap<>();
    private final Map<UUID,LinkedHashMap<UUID,Reply>> replies=new HashMap<>();
    private final Map<UUID,Map<UUID,String>> seenCommands=new HashMap<>();
    private final Map<UUID,Set<UUID>> pendingCommands=new HashMap<>();
    private final Map<UUID,long[]> rates=new HashMap<>();
    private volatile boolean transitioning;
    private CommandHandler commandHandler;private Function<ServerPlayer,JsonObject> stateHandler;private volatile boolean closed;

    private OnlineGameHost(MinecraftServer server,IdentityStore identity,InvitationStore invites,UUID owner,int limit,JsonObject compatibility,IslandAccess access,ChannelFuture listener,OnlineHostPermit permit,Object authorityReservation,NativeActorAliases nativeActors)throws IOException {
        this.nativeActors=nativeActors;if(nativeActors!=null)nativeActors.requireFor(permit,identity.profile().id(),owner);
        transitioning=PendingAttestedStartup.guarded(server);
        this.permit=permit;this.authorityReservation=authorityReservation;this.sessionId=permit==null?UUID.randomUUID():permit.nativeSessionId();
        this.server=server;this.identity=identity;this.invitations=invites;this.localPlayer=owner;this.sessionLimit=16;
        this.compatibility=compatibility.deepCopy();this.compatibilityDigest=digest(ProfileFiles.bytes(this.compatibility));
        this.access=access;this.epoch=access.epoch();this.listener=listener;
        this.address=(InetSocketAddress)listener.channel().localAddress();actors.put(owner,identity.profile().id());
    }
    public static CompletableFuture<OnlineGameHost> open(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility) {
        return open(server,identity,invitations,localOwnerPlayer,limit,compatibility,null,false);
    }
    /** Reopening is restricted to a captured live authority of this same island; preview remains read-only. */
    public static CompletableFuture<OnlineGameHost> open(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,IslandAccess.View live,boolean controlledPreview) {
        return openInternal(server,identity,invitations,localOwnerPlayer,limit,compatibility,live,controlledPreview,null,null);
    }
    public static CompletableFuture<OnlineGameHost> openAttested(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,OnlineHostPermit permit) {
        return openAttested(server,identity,invitations,localOwnerPlayer,limit,compatibility,null,false,permit);
    }
    public static CompletableFuture<OnlineGameHost> openAttested(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,IslandAccess.View live,boolean controlledPreview,OnlineHostPermit permit) {
        if(permit==null)return CompletableFuture.failedFuture(new IOException("Нет подтверждённого разрешения authority"));
        return openInternal(server,identity,invitations,localOwnerPlayer,limit,compatibility,live,controlledPreview,permit,null);
    }
    /** Opaque whole-checkpoint native aliases; never accepts a raw map from a peer or mutable owner field. */
    public static CompletableFuture<OnlineGameHost> openAttested(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,OnlineHostPermit permit,NativeActorAliases aliases) {
        return openAttested(server,identity,invitations,localOwnerPlayer,limit,compatibility,null,false,permit,aliases);
    }
    public static CompletableFuture<OnlineGameHost> openAttested(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,IslandAccess.View live,boolean controlledPreview,OnlineHostPermit permit,NativeActorAliases aliases) {
        if(permit==null||aliases==null)return CompletableFuture.failedFuture(new IOException("Missing checkpoint-bound native actor evidence"));
        return openInternal(server,identity,invitations,localOwnerPlayer,limit,compatibility,live,controlledPreview,permit,aliases);
    }
    private static CompletableFuture<OnlineGameHost> openInternal(MinecraftServer server,IdentityStore identity,InvitationStore invitations,UUID localOwnerPlayer,int limit,JsonObject compatibility,IslandAccess.View live,boolean controlledPreview,OnlineHostPermit permit,NativeActorAliases suppliedAliases) {
        CompletableFuture<OnlineGameHost> result=new CompletableFuture<>();
        if(server==null||identity==null||invitations==null||localOwnerPlayer==null||compatibility==null||compatibility.isEmpty()||limit<2||limit>16)
            return CompletableFuture.failedFuture(new IOException("Некорректные параметры сеанса Online"));
        server.execute(()->{
            IslandAccess access=null;ChannelFuture channel=null;Object reservation=null;
            try{
                if(permit==null&&(ATTESTED_FAILURES.contains(server)||PendingAttestedStartup.guarded(server)))throw new IOException("Истёкшая authority не переходит в базовый Online; требуется новый подтверждённый lifecycle");
                if(permit!=null) {
                    if(result.isCancelled())throw new CancellationException("Attested host open canceled");
                    permit.requireUsableFor(identity.profile().id(),permit.nativeSessionId());
                    reservation=new Object();
                    if(AUTHORITY_RESERVATIONS.putIfAbsent(permit.authorityId(),reservation)!=null){reservation=null;throw new IOException("Эта authority уже занята другим локальным хостом");}
                    var world=server.getWorldPath(LevelResource.ROOT);
                    if(!java.nio.file.Files.isRegularFile(world.resolve("data/darkwinter-online/access.json"),java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            ||!java.nio.file.Files.isRegularFile(world.resolve("data/darkwinter-online/authority.json"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Attested Online требует существующие публичные ACL и authority");
                }
                NativeActorAliases pendingAliases=PendingAttestedStartup.requireForOpen(server,identity,localOwnerPlayer,permit);
                boolean pendingPromotion=pendingAliases!=null;NativeActorAliases actualAliases=suppliedAliases==null?pendingAliases:suppliedAliases;
                if(actualAliases!=null){if(permit==null)throw new IOException("Native aliases require attested authority");actualAliases.requireFor(permit,identity.profile().id(),localOwnerPlayer);}
                if(pendingPromotion&&(!actualAliases.aliases().equals(pendingAliases.aliases())||!actualAliases.checkpointSha256().equals(pendingAliases.checkpointSha256())))throw new IOException("Pending native alias evidence changed");
                if(!server.isSingleplayer()||(server.isPublished()&&!pendingPromotion)||!com.dark.winter.testing.TestingServer.isTesting(server)||HOSTS.containsKey(server))throw new IOException("Online доступен только в отдельной Testing-сессии");
                if(!controlledPreview&&java.nio.file.Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/darkwinter-testing/preview-source.json"),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("Нельзя открыть Online из read-only предпросмотра версии");
                if(ProfileFiles.bytes(compatibility).length>65536)throw new IOException("Слишком большой fingerprint совместимости");
                access=IslandAccess.open(server.getWorldPath(LevelResource.ROOT),identity.profile(),live);
                String actor=identity.profile().id();
                if(permit!=null)validateAttestedWorld(server,access,identity,permit);
                if(live!=null)access.resume(actor,live);
                else access.advanceEpoch(actor,access.epoch());
                if(!access.ownerId().equals(actor)&&!access.trustedLead(actor))throw new IOException("Владелец острова не доверял этому ведущему");
                if(!access.allowed(actor))throw new IOException("Ведущий не допущен к острову");
                if(limit!=access.limit())access.configure(actor,access.privacy(),limit);
                if(permit!=null){validateAttestedWorld(server,access,identity,permit);if(result.isCancelled())throw new CancellationException("Attested host open canceled");}
                List<ChannelFuture> channels=((OnlineConnectionListenerAccess)server.getConnection()).darkwinter$channels();
                synchronized(channels){
                    int before=channels.size();
                    server.getConnection().startTcpServerListener(InetAddress.getByAddress(new byte[]{127,0,0,1}),0);
                    if(channels.size()!=before+1)throw new IOException("Не получен отдельный нативный listener");
                    channel=channels.get(before);
                }
                if(!(channel.channel().localAddress() instanceof InetSocketAddress bound)||!bound.getAddress().isLoopbackAddress())throw new IOException("Нативный listener должен быть локальным");
                if(permit!=null){permit.requireUsableFor(actor,permit.nativeSessionId());if(result.isCancelled())throw new CancellationException("Attested host open canceled");}
                OnlineGameHost host=new OnlineGameHost(server,identity,invitations,localOwnerPlayer,limit,compatibility,access,channel,permit,reservation,actualAliases);
                boolean clearedFailure=permit!=null&&ATTESTED_FAILURES.remove(server);
                HOSTS.put(server,host);
                try{if(pendingPromotion)PendingAttestedStartup.adopt(server,host,permit);}catch(IOException invalid){host.close();throw invalid;}
                if(!result.complete(host)&&permit!=null){if(clearedFailure)ATTESTED_FAILURES.add(server);host.close();}
            }catch(Throwable failure){
                if(PendingAttestedStartup.guarded(server))PendingAttestedStartup.fail(server);
                if(channel!=null)channel.channel().close();
                if(permit!=null&&reservation!=null)AUTHORITY_RESERVATIONS.remove(permit.authorityId(),reservation);
                if(access!=null)try{access.close();}catch(IOException ignored){}
                result.completeExceptionally(failure);
            }
        });return result;
    }
    public static OnlineGameHost get(MinecraftServer server){return server==null?null:HOSTS.get(server);}
    /** Keep all managed-publication guards active until the fenced native server has actually stopped. */
    public static boolean publicationGuard(MinecraftServer server){return server!=null&&(HOSTS.containsKey(server)||ATTESTED_FAILURES.contains(server)||PendingAttestedStartup.guarded(server));}
    private static void validateAttestedWorld(MinecraftServer server,IslandAccess access,IdentityStore identity,OnlineHostPermit permit)throws IOException {
        permit.requireUsableFor(identity.profile().id(),permit.nativeSessionId());
        WorldAuthority.requireAccess(permit.certificate(),access.islandId(),access.ownerId());
        var document=IslandAuthorityFiles.load(server.getWorldPath(LevelResource.ROOT),access.snapshot())
                .orElseThrow(()->new IOException("Нет публичного сертификата острова"));
        if(!WorldAuthority.certificateToJson(document.certificate()).equals(WorldAuthority.certificateToJson(permit.certificate())))
            throw new IOException("Сертификат мира не совпадает с разрешением authority");
        var policy=WorldAuthority.verifyPolicy(document.certificate(),document.policy());
        if(!policy.sha256().equals(permit.policy().sha256()))throw new IOException("Подписанная политика мира не совпадает с разрешением authority");
        permit.requireUsableFor(identity.profile().id(),permit.nativeSessionId());
    }
    private void thread()throws IOException {
        if(!server.isSameThread()||closed||!authorityAvailable()||access.epoch()!=epoch)throw new IOException("Нет актуальной серверной власти");
    }
    private boolean authorityAvailable() {
        if(closed||PendingAttestedStartup.guarded(server))return false;
        if(authorityFailed||ATTESTED_FAILURES.contains(server))return false;
        if(permit==null)return true;
        if(access.epoch()!=epoch||!permit.usable()) {failAttestation();return false;}
        return true;
    }
    private void failAttestation() {
        if(permit==null||closed)return;
        authorityFailed=true;transitioning=true;ATTESTED_FAILURES.add(server);
        if(server.isSameThread())server.tickRateManager().setFrozen(true);
        else if(!server.isStopped())try{server.executeIfPossible(()->{if(!server.isStopped())server.tickRateManager().setFrozen(true);});}catch(RejectedExecutionException ignored){}
    }
    /** Intentional controller revocation: preserve the fence even if close follows before the next tick. */
    public void revokeAuthority()throws IOException {
        if(!server.isSameThread()||closed||permit==null||HOSTS.get(server)!=this)throw new IOException("Нет действующего attested хоста для отзыва");
        failAttestation();
    }
    public boolean attested(){return permit!=null;}
    public boolean attestationFailed(){return authorityFailed||ATTESTED_FAILURES.contains(server);}
    /** Coordinator fencing epoch; native epoch() remains the IslandAccess epoch. */
    public long nodeLeaseEpoch(){return permit==null?-1:permit.nodeLeaseEpoch();}
    /** Public owner proofs only. This does not replace a guest's independent fresh Node observation. */
    public JsonObject authorityDescriptor()throws IOException {
        thread();JsonObject value=new JsonObject();if(permit!=null){value.add("certificate",WorldAuthority.certificateToJson(permit.certificate()));value.add("policy",OnlineJson.GSON.toJsonTree(permit.policy().proof()));}return value;
    }
    private void releaseReservation(){if(permit!=null&&authorityReservation!=null)AUTHORITY_RESERVATIONS.remove(permit.authorityId(),authorityReservation);}
    /** SERVER_STOPPED hook: failure latches must never retain a closed world's server forever. Does not close its permit. */
    public static void serverStopped(MinecraftServer server) {
        OnlineGameHost host=HOSTS.remove(server);
        if(host!=null){host.closed=true;host.listener.channel().close();host.releaseReservation();try{host.access.close();}catch(IOException ignored){}}
        ATTESTED_FAILURES.remove(server);PendingAttestedStartup.serverStopped(server);
    }
    /** Never bounce a late network callback through a stopped server's inline executor. */
    private boolean liveThread(Runnable action){
        if(closed||server.isStopped())return false;
        if(server.isSameThread())return true;
        try{server.executeIfPossible(action);}catch(RejectedExecutionException ignored){}
        return false;
    }
    public UUID sessionId(){return sessionId;}public String islandId(){return access.islandId();}public String ownerId(){return access.ownerId();}
    public long epoch(){return epoch;}public String compatibilityDigest(){return compatibilityDigest;}
    public InetSocketAddress nativeAddress(){return address;}public IslandAccess access(){return access;}
    public int limit(){return Math.min(sessionLimit,access.limit());}public String localIdentityId(){return identity.profile().id();}
    public void onCommand(CommandHandler handler)throws IOException {thread();commandHandler=Objects.requireNonNull(handler);}
    public void onState(Function<ServerPlayer,JsonObject> handler)throws IOException {thread();stateHandler=Objects.requireNonNull(handler);}
    public void onAdmissionPolicy(Predicate<String> policy)throws IOException {thread();admissionPolicy=Objects.requireNonNull(policy);}

    public void onAdmissionCheck(AdmissionCheck check)throws IOException {thread();admissionCheck=Objects.requireNonNull(check);}

    /** Must run before Binding.start(). The caller has already authenticated signaling and the peer DTLS fingerprint. */
    public Admission registerIngress(InetSocketAddress upstreamLocalAddress,IdentityStore.SignedPayload peerProfileProof,UUID attemptId,String inviteToken,boolean approved,JsonObject peerCompatibility)throws IOException {
        thread();Objects.requireNonNull(attemptId);
        IdentityProfile peer=IdentityStore.validateProfileProof(peerProfileProof);
        if(upstreamLocalAddress==null||upstreamLocalAddress.isUnresolved()||!upstreamLocalAddress.getAddress().isLoopbackAddress()||upstreamLocalAddress.getPort()<1)
            throw new IOException("Неверный локальный адрес игрового моста");
        byte[] suppliedCompatibility=ProfileFiles.bytes(Objects.requireNonNull(peerCompatibility));
        if(suppliedCompatibility.length>65536)throw new IOException("Слишком большой fingerprint участника");
        OnlineCompatibility.requireCompatible(compatibility,peerCompatibility);
        UUID nativePeer=resolveNativeActor(peer.id());
        if(peer.id().equals(identity.profile().id())||nativePeer.equals(localPlayer))throw new IOException("Повтор личности ведущего");
        if(actors.containsKey(nativePeer))throw new IOException("Native player UUID is already bound to another admitted actor");
        if(inviteToken==null||inviteToken.length()>16384)throw new IOException("Некорректное приглашение");
        if(ingress.containsKey(upstreamLocalAddress)||ingress.values().stream().anyMatch(a->a.peer.id().equals(peer.id())))throw new IOException("Участник уже подключается");
        if(actors.containsValue(peer.id()))throw new IOException("Участник уже находится на острове");
        validatePolicy(peer.id(),attemptId,inviteToken,approved);
        if(onlineAndPending()>=limit())throw new IOException("Остров заполнен");
        Admission admission=new Admission(peer,attemptId,inviteToken,approved,nativePeer);ingress.put(upstreamLocalAddress,admission);return admission;
    }
    public void unregisterIngress(InetSocketAddress source) {
        if(!liveThread(()->unregisterIngress(source)))return;
        Admission admission=ingress.remove(source);
        if(admission!=null){if(admission.connection!=null)connections.remove(admission.connection);actors.remove(admission.gameId);clearPlayer(admission.gameId);}
    }
    private int onlineAndPending(){return 1+ingress.size();}
    private boolean friend(String peer)throws IOException {
        JsonObject data=identity.section(IdentityStore.Section.FRIENDS);if(data.isEmpty())return false;
        FriendsStore.validateExport(identity.profile(),data);
        for(JsonElement id:data.getAsJsonArray("blocks"))if(peer.equals(id.getAsString()))return false;
        JsonElement entry=data.getAsJsonObject("peers").get(peer);
        return entry!=null&&"CONFIRMED".equals(entry.getAsJsonObject().get("state").getAsString());
    }
    private boolean globallyBlocked(String peer)throws IOException {
        JsonObject friends=identity.section(IdentityStore.Section.FRIENDS);if(friends.isEmpty())return false;
        if(ProfileFiles.number(friends,"schema")!=1||!friends.get("blocks").isJsonArray())throw new IOException("Повреждена политика блокировок");
        for(JsonElement row:friends.getAsJsonArray("blocks"))if(peer.equals(row.getAsString()))return true;
        return false;
    }
    private InvitationStore.SignedInvitation validatePolicy(String peer,UUID attempt,String token,boolean approved)throws IOException {
        if(admissionCheck!=null)admissionCheck.validate(peer,attempt,token,approved);
        if(access.blocked(peer)||globallyBlocked(peer)||admissionPolicy!=null&&!admissionPolicy.test(peer))throw new IOException("Участник заблокирован или не допущен политикой профиля");
        IslandAccess.Member existing=access.members().get(peer);
        if(existing!=null&&Boolean.FALSE.equals(existing.overrides().get(IslandAccess.Permission.VIEW)))throw new IOException("Просмотр острова явно запрещён");
        if(!token.isEmpty()){
            var invitation=InvitationStore.inspect(token,Clock.systemUTC());
            if(!invitation.islandId().equals(islandId())||!invitation.issuer().id().equals(identity.profile().id())
                    ||!access.can(invitation.issuer().id(),IslandAccess.Permission.INVITE))throw new IOException("Приглашение не принадлежит этому острову/ведущему");
            if(!invitation.targetId().isEmpty()&&!invitation.targetId().equals(peer))throw new IOException("Приглашение предназначено другой личности");
            if(invitation.requireApproval()&&!approved)throw new IOException("Требуется подтверждение ведущего");
            if(!access.allowed(peer)){
                if(!access.can(identity.profile().id(),IslandAccess.Permission.INVITE))throw new IOException("Ведущий не может допустить нового участника");
                if(invitation.role()!=InvitationStore.Role.OBSERVER)access.validateRoleGrant(identity.profile().id(),peer,IslandAccess.Role.valueOf(invitation.role().name()));
                else access.validateAllow(identity.profile().id(),peer);
            }
            return invitation;
        }
        if(access.allowed(peer))return null;
        boolean publicJoin=access.privacy()==IslandAccess.Privacy.PUBLIC;
        boolean friendJoin=access.privacy()==IslandAccess.Privacy.FRIENDS&&friend(peer);
        if((!publicJoin&&!friendJoin)||!access.can(identity.profile().id(),IslandAccess.Permission.INVITE))
            throw new IOException("Требуется действительное приглашение или разрешение владельца");
        access.validateAllow(identity.profile().id(),peer);
        return null;
    }
    public GameProfile claimLogin(Connection connection)throws IOException {
        thread();
        if(connection.isMemoryConnection())throw new IOException("Локальный вход не является сетевым допуском");
        if(!(connection.getRemoteAddress() instanceof InetSocketAddress source))throw new IOException("Неверный адрес входа");
        Admission admission=ingress.get(source);
        if(admission==null||admission.connection!=null||System.nanoTime()-admission.created>TimeUnit.SECONDS.toNanos(30))throw new IOException("Нет подтверждённого транспортного допуска");
        if(server.getPlayerList().getPlayer(admission.gameId)!=null)throw new IOException("Личность уже вошла в мир");
        validatePolicy(admission.peer.id(),admission.attemptId,admission.token,admission.approved);
        admission.connection=connection;connections.put(connection,admission);
        return new GameProfile(admission.gameId,"dw_"+digest(admission.peer.id().getBytes(StandardCharsets.UTF_8)).substring(0,12));
    }
    public void beginConfiguration(ServerConfigurationPacketListenerImpl configuration)throws IOException {
        thread();Connection connection=((OnlineCommonListenerAccess)configuration).darkwinter$connection();
        if(connection.isMemoryConnection())return;
        Admission admission=connections.get(connection);
        if(admission==null||admission.authenticated)throw new IOException("Вход не прошёл транспортную проверку");
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("kind","native_join");
        body.addProperty("hostId",identity.profile().id());body.addProperty("peerId",admission.peer.id());
        body.addProperty("sessionId",sessionId.toString());body.addProperty("islandId",islandId());body.addProperty("epoch",epoch);
        body.addProperty("attemptId",admission.attemptId.toString());body.addProperty("compatibility",compatibilityDigest);
        body.addProperty("nonce",IdentityStore.randomToken());body.addProperty("expiresAt",System.currentTimeMillis()+15000);
        admission.challenge=ProfileFiles.bytes(body);admission.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        JsonObject wrapper=new JsonObject();wrapper.add("profileProof",OnlineJson.GSON.toJsonTree(identity.publicProof()));
        wrapper.add("challenge",OnlineJson.GSON.toJsonTree(identity.sign(IdentityStore.Purpose.AUTH,admission.challenge)));
        List<OnlinePayload> frames=OnlinePayload.frames("auth_challenge",admission.attemptId,epoch,OnlineJson.GSON.toJson(wrapper));
        if(frames.size()!=1)throw new IOException("Слишком большой запрос удостоверения");
        admission.task=new ConfigurationTask(){
            @Override public void start(Consumer<Packet<?>> send){frames.forEach(frame->send.accept(new ClientboundCustomPayloadPacket(frame)));}
            @Override public boolean tick(){
                if(!authorityAvailable()){configuration.disconnect(Component.literal("DarkWinter: authority ведущего истекла"));return false;}
                if(admission.authenticated)return true;
                if(closed||access.epoch()!=epoch||System.nanoTime()>admission.deadline||!connection.isConnected())
                    configuration.disconnect(Component.literal("DarkWinter: личность участника не подтверждена"));
                return false;
            }
            @Override public Type type(){return AUTH_TASK;}
        };
        ((OnlineConfigurationAccess)configuration).darkwinter$tasks().add(admission.task);
    }
    public void configurationPayload(ServerConfigurationPacketListenerImpl configuration,OnlinePayload payload) {
        if(!liveThread(()->configurationPayload(configuration,payload)))return;
        try{
            thread();Connection connection=((OnlineCommonListenerAccess)configuration).darkwinter$connection();
            Admission admission=connections.get(connection);
            if(admission==null||admission.authenticated||admission.challenge==null||System.nanoTime()>admission.deadline
                    ||payload.epoch()!=epoch||!payload.kind().equals("auth_proof")||payload.count()!=1||!payload.requestId().equals(admission.attemptId))
                throw new IOException("Неверный этап подтверждения Online");
            String data=new OnlinePayload.Assembler(16384,1).accept(payload,epoch).orElseThrow(()->new IOException("Неполное удостоверение"));
            IdentityStore.SignedPayload proof=OnlineJson.GSON.fromJson(OnlineJson.object(data,16384),IdentityStore.SignedPayload.class);
            if(!IdentityStore.verify(proof,IdentityStore.Purpose.AUTH)||!proof.signer().equals(admission.peer)
                    ||!MessageDigest.isEqual(IdentityStore.decodeData(proof),admission.challenge))throw new IOException("Подпись свежего вызова не подтверждена");
            var invitation=validatePolicy(admission.peer.id(),admission.attemptId,admission.token,admission.approved);
            if(onlineAndPending()>limit())throw new IOException("Остров заполнен");
            admission.challenge=null; // One native connection consumes its nonce before any admission side effect.
            if(invitation!=null)invitations.consume(admission.token,admission.peer.id(),admission.attemptId,admission.approved);
            if(!access.allowed(admission.peer.id())){
                if(invitation!=null&&invitation.role()!=InvitationStore.Role.OBSERVER)
                    access.setRole(identity.profile().id(),admission.peer.id(),IslandAccess.Role.valueOf(invitation.role().name()));
                else access.allow(identity.profile().id(),admission.peer.id());
            }
            if(!access.can(admission.peer.id(),IslandAccess.Permission.VIEW))throw new IOException("Просмотр острова запрещён");
            admission.authenticated=true;actors.put(admission.gameId,admission.peer.id());
        }catch(Exception failure){configuration.disconnect(Component.literal("DarkWinter: вход отклонён — "+safe(failure)));}
    }
    public boolean configurationReady(Connection connection){Admission a=connections.get(connection);return a!=null&&a.authenticated&&!closed&&authorityAvailable()&&access.epoch()==epoch;}
    public void joined(ServerPlayer player) {
        if(!liveThread(()->joined(player)))return;
        if(!authorityAvailable()){if(!player.getUUID().equals(localPlayer))player.connection.disconnect(Component.literal("DarkWinter: authority ведущего истекла"));return;}
        if(player.getUUID().equals(localPlayer))return;
        Admission admission=connections.values().stream().filter(a->a.gameId.equals(player.getUUID())).findFirst().orElse(null);
        if(admission==null||!admission.authenticated){player.connection.disconnect(Component.literal("DarkWinter: нет действительного допуска"));return;}
        // Fabric JOIN fires before PlayerList publishes playersByUUID in Minecraft 26.3.
        // Keep the live-player check in Lab snapshots; send the first state after this native stack has returned.
        if(server.getPlayerList().getPlayer(player.getUUID())!=player){
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount()-4,()->{
                if(closed)return;
                if(server.getPlayerList().getPlayer(player.getUUID())==player)joined(player);
                else player.connection.disconnect(Component.literal("DarkWinter: игрок не зарегистрирован в мире"));
            }));return;
        }
        admission.joined=true;
        if(access.can(admission.peer.id(),IslandAccess.Permission.BUILD)&&player.isSpectator()&&!access.ownerId().equals(admission.peer.id()))player.setGameMode(GameType.CREATIVE);
        applyMode(player);sendState(player,UUID.randomUUID());
    }
    public String actor(ServerPlayer player){return actors.get(player.getUUID());}
    public String testingMode(){return com.dark.winter.testing.TestingServer.value(server,"mode");}
    public boolean mutationsPaused(){return authorityFailed||ATTESTED_FAILURES.contains(server)||transitioning||Set.of("TEST_PAUSED","TEST_REVIEW").contains(testingMode());}
    public boolean can(ServerPlayer player,IslandAccess.Permission permission){String id=actor(player);return !closed&&authorityAvailable()&&access.epoch()==epoch&&id!=null
            &&server.getPlayerList().getPlayer(player.getUUID())==player&&access.can(id,IslandAccess.Permission.VIEW)&&!(mutationsPaused()&&WORLD_MUTATIONS.contains(permission))&&access.can(id,permission);}
    public boolean isWorldOwner(ServerPlayer player){return can(player,IslandAccess.Permission.VIEW)&&!mutationsPaused()&&Objects.equals(actor(player),access.ownerId());}
    public static boolean nativeGameplayAllowed(ServerPlayer player,IslandAccess.Permission fallback) {
        OnlineGameHost host=get(player.level().getServer());
        if(host==null)return !restricted(player);
        if(host.mutationsPaused())return false;
        if(host.can(player,fallback))return true;
        String actor=host.actor(player);
        IslandAccess.Member member=actor==null?null:host.access.members().get(actor);
        if(member==null||Boolean.FALSE.equals(member.overrides().get(fallback)))return false;
        return "TEST_RUNNING".equals(host.testingMode())&&member.role()!=IslandAccess.Role.OBSERVER
                &&host.can(player,IslandAccess.Permission.INTERACT);
    }
    public void transitionLock(boolean value)throws IOException{thread();transitioning=value;}
    public boolean transitioning(){return transitioning;}
    /** Small authenticated native lifecycle notification, ordered with the normal game stream. */
    public void lifecycle(JsonObject value)throws IOException{
        thread();if(OnlineJson.bytes(value).length>8192)throw new IOException("Lifecycle notice too large");
        for(ServerPlayer player:server.getPlayerList().getPlayers())if(can(player,IslandAccess.Permission.VIEW))send(player,"lifecycle",UUID.randomUUID(),value);
    }
    public boolean nativeOperator(UUID gameId){String actor=actors.get(gameId);return !closed&&authorityAvailable()&&!mutationsPaused()&&access.epoch()==epoch&&actor!=null&&access.can(actor,IslandAccess.Permission.VIEW)&&Objects.equals(actor,access.ownerId());}
    public static boolean restricted(ServerPlayer player) {
        if(publicationGuard(player.level().getServer()))return true;
        return com.dark.winter.testing.TestingServer.isTesting(player.level().getServer())&&player.connection!=null
                &&!((OnlineCommonListenerAccess)player.connection).darkwinter$connection().isMemoryConnection();
    }
    public static boolean nativeAllowed(ServerPlayer player,IslandAccess.Permission permission){
        OnlineGameHost host=get(player.level().getServer());return host==null?!restricted(player):host.can(player,permission);
    }
    public static boolean authenticatedRemote(ServerPlayer player) {
        OnlineGameHost host=get(player.level().getServer());
        return host!=null&&!host.closed&&host.authorityAvailable()&&player.connection!=null&&host.actors.containsKey(player.getUUID())
                &&!((OnlineCommonListenerAccess)player.connection).darkwinter$connection().isMemoryConnection();
    }
    public static boolean nativeOwner(ServerPlayer player){OnlineGameHost host=get(player.level().getServer());return host==null?!restricted(player):host.isWorldOwner(player);}
    public JsonObject snapshot(String actor)throws IOException {
        thread();if(!access.can(actor,IslandAccess.Permission.VIEW))throw new IOException("Просмотр сеанса запрещён");
        JsonObject result=OnlineJson.ok();result.addProperty("sessionId",sessionId.toString());result.addProperty("islandId",islandId());
        result.addProperty("hostId",identity.profile().id());result.addProperty("ownerId",ownerId());result.addProperty("epoch",epoch);
        result.addProperty("transitioning",transitioning);result.addProperty("mode",testingMode());result.addProperty("limit",limit());result.addProperty("compatibility",compatibilityDigest);result.addProperty("privacy",access.privacy().name());
        JsonObject permissions=new JsonObject();for(var permission:IslandAccess.Permission.values())permissions.addProperty(permission.name(),!(mutationsPaused()&&WORLD_MUTATIONS.contains(permission))&&access.can(actor,permission));result.add("permissions",permissions);
        JsonArray peers=new JsonArray();for(var entry:actors.entrySet()){JsonObject peer=new JsonObject();peer.addProperty("gameId",entry.getKey().toString());peer.addProperty("id",entry.getValue());peer.addProperty("role",access.roleOf(entry.getValue()).name());peers.add(peer);}result.add("participants",peers);
        JsonObject capabilities=new JsonObject();capabilities.addProperty("hostAdministration",true);capabilities.addProperty("labCommands",commandHandler!=null);capabilities.addProperty("labState",stateHandler!=null);result.add("capabilities",capabilities);return result;
    }
    public void playPayload(ServerPlayer player,OnlinePayload packet) {
        if(!liveThread(()->playPayload(player,packet)))return;
        try{
            thread();if(!can(player,IslandAccess.Permission.VIEW))throw new IOException("Нет допуска к Online");
            long now=System.nanoTime();long[] rate=rates.computeIfAbsent(player.getUUID(),id->new long[]{now,0});
            if(now-rate[0]>TimeUnit.SECONDS.toNanos(10)){rate[0]=now;rate[1]=0;}if(++rate[1]>256)throw new IOException("Слишком частые запросы Online");
            if(!Set.of("state_request","command").contains(packet.kind()))throw new IOException("Неизвестный игровой канал Online");
            String full=assemblers.computeIfAbsent(player.getUUID(),id->new OnlinePayload.Assembler()).accept(packet,epoch).orElse(null);if(full==null)return;
            JsonObject body=OnlineJson.object(full,OnlinePayload.MAX_BYTES);
            if(packet.kind().equals("state_request")){sendState(player,packet.requestId());return;}
            String hash=digest(ProfileFiles.bytes(body));if(!seenCommands.containsKey(player.getUUID())&&seenCommands.size()>=64)throw new IOException("Журнал этого сеанса заполнен; новое действие не выполнялось");Map<UUID,String> seen=seenCommands.computeIfAbsent(player.getUUID(),id->new HashMap<>());
            String oldDigest=seen.get(packet.requestId());
            if(oldDigest!=null&&!oldDigest.equals(hash))throw new IOException("ID команды повторён с другим содержимым");
            LinkedHashMap<UUID,Reply> cache=replies.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());
            Reply prior=cache.get(packet.requestId());if(prior!=null){if(!prior.digest.equals(hash))throw new IOException("ID команды повторён с другим содержимым");send(player,"reply",packet.requestId(),prior.value);return;}
            if(oldDigest!=null&&!pendingCommands.getOrDefault(player.getUUID(),Set.of()).contains(packet.requestId())){send(player,"reply",packet.requestId(),OnlineJson.error("Ответ больше не хранится; команда повторно не выполнялась"));return;}
            if(oldDigest==null&&seen.size()>=4096)throw new IOException("Журнал команд сеанса заполнен");
            Set<UUID> running=pendingCommands.computeIfAbsent(player.getUUID(),id->new HashSet<>());
            if(!running.add(packet.requestId()))return;if(running.size()>4){running.remove(packet.requestId());throw new IOException("Дождитесь завершения команд");}
            seen.put(packet.requestId(),hash);
            CompletableFuture<JsonObject> result;
            try{JsonObject admin=admin(player,body);result=admin!=null?CompletableFuture.completedFuture(admin):commandHandler==null?CompletableFuture.completedFuture(OnlineJson.error("Редактор Online ещё не подключён")):commandHandler.handle(player,packet.requestId(),body.deepCopy());}
            catch(Exception failure){result=CompletableFuture.completedFuture(OnlineJson.error(safe(failure)));}
            result.whenComplete((reply,failure)->server.execute(()->{
                running.remove(packet.requestId());if(closed||!can(player,IslandAccess.Permission.VIEW)||packet.epoch()!=epoch)return;
                JsonObject value=failure==null&&reply!=null?reply.deepCopy():OnlineJson.error(failure==null?"Пустой ответ команды":safe(failure));
                if(OnlineJson.bytes(value).length>65536)value=OnlineJson.error("Ответ команды превышает 64 KiB; изменения могли быть применены");
                cache.put(packet.requestId(),new Reply(hash,value));
                int cacheBytes=0;for(Reply item:cache.values())cacheBytes+=OnlineJson.bytes(item.value()).length;
                while(cache.size()>64||cacheBytes>262144){UUID first=cache.keySet().iterator().next();cacheBytes-=OnlineJson.bytes(cache.remove(first).value()).length;}
                try{send(player,"reply",packet.requestId(),value);sendState(player,UUID.randomUUID());}catch(IOException invalid){player.connection.disconnect(Component.literal("DarkWinter: ответ превышает лимит"));}
            }));
        }catch(Exception failure){try{send(player,"reply",packet.requestId(),OnlineJson.error(safe(failure)));}catch(IOException ignored){}}
    }
    private JsonObject admin(ServerPlayer player,JsonObject command)throws IOException {
        String op=OnlineJson.optional(command,"op","",64);if(!op.startsWith("host_"))return null;
        if(transitioning)throw new IOException("Управление доступом временно закрыто на время сохранения участников");
        String actor=actor(player),target=OnlineJson.optional(command,"target","",64);
        switch(op){
            case "host_role"->access.setRole(actor,target,IslandAccess.Role.valueOf(OnlineJson.string(command,"role",32)));
            case "host_permission"->access.setPermission(actor,target,IslandAccess.Permission.valueOf(OnlineJson.string(command,"permission",32)),OnlineJson.bool(command,"allowed",false));
            case "host_block"->{access.block(actor,target);kick(target,"Доступ к острову заблокирован");}
            case "host_unblock"->access.unblock(actor,target);
            case "host_kick"->{if(!access.can(actor,IslandAccess.Permission.KICK)||target.equals(ownerId()))throw new IOException("Нельзя исключить этого участника");kick(target,"Вы исключены ведущим");}
            case "host_configure"->access.configure(actor,IslandAccess.Privacy.valueOf(OnlineJson.string(command,"privacy",32)),(int)OnlineJson.integer(command,"limit",2,16));
            default->throw new IOException("Неизвестная команда управления");
        }
        for(ServerPlayer peer:server.getPlayerList().getPlayers())applyMode(peer);return OnlineJson.ok();
    }
    private void kick(String peer,String reason){for(ServerPlayer player:server.getPlayerList().getPlayers())if(peer.equals(actor(player)))player.connection.disconnect(Component.literal("DarkWinter: "+reason));}
    private void sendState(ServerPlayer player,UUID request){
        try{JsonObject body=new JsonObject();body.add("host",snapshot(actor(player)));body.add("lab",stateHandler==null?OnlineJson.error("Состояние лаборатории Online не подключено"):stateHandler.apply(player));send(player,"state",request,body);}
        catch(Exception failure){org.slf4j.LoggerFactory.getLogger("DarkWinter/Online").error("Cannot assemble authenticated session state; no request body is logged",failure);player.connection.disconnect(Component.literal("DarkWinter: состояние сеанса недоступно"));}
    }
    private void send(ServerPlayer player,String kind,UUID request,JsonObject value)throws IOException {for(OnlinePayload frame:OnlinePayload.frames(kind,request,epoch,OnlineJson.GSON.toJson(value)))player.connection.send(new ClientboundCustomPayloadPacket(frame));}
    private void applyMode(ServerPlayer player){
        if(!can(player,IslandAccess.Permission.VIEW)){player.connection.disconnect(Component.literal("DarkWinter: доступ к острову отозван"));return;}
        if(transitioning)return; // Saving a transition must preserve the native game mode.
        GameType forced=null;
        if(testingMode().startsWith("TEST"))forced=can(player,IslandAccess.Permission.INTERACT)?GameType.SURVIVAL:GameType.SPECTATOR;
        else if(!can(player,IslandAccess.Permission.BUILD)&&!can(player,IslandAccess.Permission.BREAK))
            forced=can(player,IslandAccess.Permission.INTERACT)?GameType.ADVENTURE:GameType.SPECTATOR;
        if(forced!=null){
            modesBeforeRestriction.putIfAbsent(player.getUUID(),player.gameMode.getGameModeForPlayer());
            if(player.gameMode.getGameModeForPlayer()!=forced)player.setGameMode(forced);
        }else{
            GameType previous=modesBeforeRestriction.remove(player.getUUID());
            if(previous!=null){
                if(previous==GameType.SPECTATOR&&!Objects.equals(actor(player),ownerId()))previous=GameType.CREATIVE;
                player.setGameMode(previous);
            }
        }
    }
    public void tick(){
        if(closed)return;
        if(permit!=null&&!authorityAvailable()){server.tickRateManager().setFrozen(true);return;}
        if(access.epoch()!=epoch){close();return;}
        for(var entry:new ArrayList<>(ingress.entrySet())){Admission a=entry.getValue();if(a.connection!=null&&!a.connection.isConnected()||a.connection==null&&System.nanoTime()-a.created>TimeUnit.SECONDS.toNanos(30))unregisterIngress(entry.getKey());}
        for(ServerPlayer player:server.getPlayerList().getPlayers())if(actors.containsKey(player.getUUID()))applyMode(player);
        assemblers.values().forEach(OnlinePayload.Assembler::prune);
    }
    public void disconnected(ServerPlayer player){if(player.getUUID().equals(localPlayer))return;for(var entry:new ArrayList<>(ingress.entrySet()))if(entry.getValue().gameId.equals(player.getUUID()))unregisterIngress(entry.getKey());clearPlayer(player.getUUID());}
    private void clearPlayer(UUID id){assemblers.remove(id);pendingCommands.remove(id);rates.remove(id);modesBeforeRestriction.remove(id);/* Keep bounded command digests and replies for this session: reconnect never replays an already accepted UUID. */}
    /** Retain disconnected checkpoint actors too; controllers must merge this table into every later confirmed index. */
    public Map<String,UUID> checkpointActorAliases(){return nativeActors==null?Map.of():nativeActors.aliases();}
    public NativeActorAliases nativeAliasEvidence(){return nativeActors;}
    private UUID resolveNativeActor(String id)throws IOException {UUID derived=gameId(id);return nativeActors==null?derived:nativeActors.resolve(id,derived);}
    private static UUID gameId(String id)throws IOException {byte[] bytes;try{bytes=MessageDigest.getInstance("SHA-256").digest(("DarkWinter native player v1\0"+id).getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException failure){throw new IOException(failure);}bytes[6]=(byte)((bytes[6]&15)|0x50);bytes[8]=(byte)((bytes[8]&63)|0x80);var b=java.nio.ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
    private static String digest(byte[] bytes)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException failure){throw new IOException(failure);}}
    private static String safe(Throwable failure){String message=failure.getMessage();return message==null||message.length()>180?"операция отклонена":message;}
    @Override public void close(){
        if(closed)return; // A stopped MinecraftServer executes submitted tasks inline on the caller.
        if(!server.isSameThread()){try{server.executeIfPossible(this::close);}catch(RejectedExecutionException ignored){}return;}
        if(permit!=null)failAttestation();
        ServerPlayer local=server.getPlayerList().getPlayer(localPlayer);GameType previous=modesBeforeRestriction.get(localPlayer);
        if(local!=null&&previous!=null)local.setGameMode(previous);
        closed=true;listener.channel().close();
        List<ChannelFuture> channels=((OnlineConnectionListenerAccess)server.getConnection()).darkwinter$channels();synchronized(channels){channels.remove(listener);}
        Component reason=Component.literal("DarkWinter: сеанс завершён");
        for(Connection connection:new ArrayList<>(connections.keySet())){
            if(connection.getPacketListener() instanceof ServerCommonPacketListenerImpl handler)handler.disconnect(reason);
            else if(connection.getPacketListener() instanceof ServerLoginPacketListenerImpl handler)handler.disconnect(reason);
            else connection.disconnect(reason);
        }
        HOSTS.remove(server,this);releaseReservation();connections.clear();ingress.clear();actors.clear();assemblers.clear();pendingCommands.clear();replies.clear();rates.clear();seenCommands.clear();modesBeforeRestriction.clear();
        try{access.close();}catch(IOException ignored){}
    }
}
