// Binds authenticated online admission to the native Minecraft client connection.
package com.dark.winter.client.online;

import com.dark.winter.client.mixin.client.OnlineClientCommonAccess;
import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.network.OnlinePayload;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import com.dark.winter.online.server.OnlineGameHost;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

/** Native client admission only. Identity stores and transport expected-host context are owned by the application. */
public final class OnlineConnectionClient {
    public record ExpectedHost(String hostId,UUID sessionId,long epoch,String compatDigest,OnlineGuestAuthority authority) {
        public ExpectedHost(String hostId,UUID sessionId,long epoch,String compatDigest){this(hostId,sessionId,epoch,compatDigest,null);}
        boolean authorityUsable(){return authority==null||authority.usable();}
        public ExpectedHost {
            if(hostId==null||!hostId.matches("dw1_[A-Za-z0-9_-]{43}")||sessionId==null||epoch<0||compatDigest==null||!compatDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid expected host");
        }
    }
    private static Supplier<IdentityStore> identity;
    private static Supplier<ExpectedHost> expected;
    private static Consumer<JsonObject> states,replies;
    private static volatile Consumer<JsonObject> lifecycle;
    private static volatile Consumer<DisconnectionDetails> remoteDisconnect;
    private static volatile Connection authenticatedConnection;
    private static volatile ExpectedHost authenticatedHost;
    private record DisconnectBinding(Connection connection,ExpectedHost host,long generation,long expires){}
    private static volatile DisconnectBinding disconnectedBinding;
    private static final java.util.concurrent.atomic.AtomicLong AUTH_GENERATION=new java.util.concurrent.atomic.AtomicLong();
    private static final OnlinePayload.Assembler ASSEMBLER=new OnlinePayload.Assembler();
    private static final ThreadPoolExecutor SIGNER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"DarkWinter-native-auth");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static boolean initialized;
    private static volatile Predicate<String> chatHandler;
    private OnlineConnectionClient(){}
    public static synchronized void init(Supplier<IdentityStore> identitySupplier,Supplier<ExpectedHost> expectedSupplier,Consumer<JsonObject> state,Consumer<JsonObject> reply) {
        identity=Objects.requireNonNull(identitySupplier);expected=Objects.requireNonNull(expectedSupplier);states=Objects.requireNonNull(state);replies=Objects.requireNonNull(reply);
        if(initialized)return;initialized=true;
        ClientConfigurationNetworking.registerGlobalReceiver(OnlinePayload.TYPE,(packet,context)->{
            if(!packet.kind().equals("auth_challenge"))return;
            ExpectedHost target=expected.get();
            Connection connection=((OnlineClientCommonAccess)context.packetListener()).darkwinter$connection();
            try {
                if(target==null||!target.authorityUsable()||packet.epoch()!=target.epoch()||packet.count()!=1)throw new IOException("Не запрошен этот сеанс Online");
                String full=new OnlinePayload.Assembler(16384,1).accept(packet,target.epoch()).orElseThrow();
                JsonObject wrapper=OnlineJson.object(full,16384);OnlineJson.keys(wrapper,"profileProof","challenge");
                IdentityStore.SignedPayload publicProof=OnlineJson.GSON.fromJson(OnlineJson.child(wrapper,"profileProof"),IdentityStore.SignedPayload.class);
                IdentityProfile host=IdentityStore.validateProfileProof(publicProof);
                IdentityStore.SignedPayload challenge=OnlineJson.GSON.fromJson(OnlineJson.child(wrapper,"challenge"),IdentityStore.SignedPayload.class);
                if(!host.id().equals(target.hostId())||!IdentityStore.verify(challenge,IdentityStore.Purpose.AUTH)||!challenge.signer().equals(host))throw new IOException("Ведущий не подтвердил свой ключ");
                byte[] challengeBytes=IdentityStore.decodeData(challenge);JsonObject body=OnlineJson.object(challengeBytes,4096);
                OnlineJson.keys(body,"schema","kind","hostId","peerId","sessionId","islandId","epoch","attemptId","compatibility","nonce","expiresAt");
                IdentityStore store=identity.get();
                if(store==null||OnlineJson.integer(body,"schema",1,1)!=1||!OnlineJson.string(body,"kind",32).equals("native_join")
                        ||!OnlineJson.string(body,"hostId",64).equals(target.hostId())||!OnlineJson.string(body,"peerId",64).equals(store.profile().id())
                        ||!OnlineJson.string(body,"sessionId",36).equals(target.sessionId().toString())||OnlineJson.integer(body,"epoch",0,Long.MAX_VALUE)!=target.epoch()
                        ||!OnlineJson.string(body,"compatibility",64).equals(target.compatDigest())
                        ||!OnlineJson.string(body,"attemptId",36).equals(packet.requestId().toString())
                        ||IdentityStore.decode(OnlineJson.string(body,"nonce",64),32).length!=32)throw new IOException("Вызов относится к другой личности или сессии");
                long expires=OnlineJson.integer(body,"expiresAt",0,Long.MAX_VALUE),now=System.currentTimeMillis();
                if(expires<=now||expires>now+30000)throw new IOException("Вызов удостоверения устарел");
                String island=OnlineJson.string(body,"islandId",36);UUID.fromString(island);
                if(target.authority()!=null&&!target.authority().islandId().equals(island))throw new IOException("Вызов относится к другому подтверждённому острову");
                SIGNER.execute(()->{
                    try {
                        if(!target.authorityUsable()||!target.equals(expected.get())||identity.get()!=store||System.currentTimeMillis()>=expires)throw new IOException("Сеанс сменился до подписи");
                        var signed=store.sign(IdentityStore.Purpose.AUTH,challengeBytes);
                        context.client().execute(()->{
                            if(!target.authorityUsable()||!target.equals(expected.get())||identity.get()!=store||!connection.isConnected())return;
                            try {
                                var frames=OnlinePayload.frames("auth_proof",packet.requestId(),target.epoch(),OnlineJson.GSON.toJson(signed));
                                if(frames.size()!=1)throw new IOException("Удостоверение превышает лимит");
                                frames.forEach(context.responseSender()::sendPacket);
                                disconnectedBinding=null;authenticatedConnection=connection;authenticatedHost=target;AUTH_GENERATION.incrementAndGet();ASSEMBLER.clear();
                            }catch(Exception failure){connection.disconnect(Component.literal("DarkWinter: удостоверение не отправлено"));}
                        });
                    }catch(Exception failure){context.client().execute(()->connection.disconnect(Component.literal("DarkWinter: подпись личности недоступна")));}
                });
            } catch(Exception failure) {connection.disconnect(Component.literal("DarkWinter: проверка ведущего отклонена"));}
        });
        ClientPlayNetworking.registerGlobalReceiver(OnlinePayload.TYPE,(packet,context)->{
            context.client().execute(()->{
                try {
                    if(context.client().getConnection()==null||context.client().getConnection().getConnection()!=authenticatedConnection
                            ||authenticatedHost==null||!authenticatedHost.authorityUsable()||!authenticatedHost.equals(expected.get())||!Set.of("state","reply","lifecycle").contains(packet.kind()))return;
                    String full=ASSEMBLER.accept(packet,authenticatedHost.epoch()).orElse(null);if(full==null)return;
                    JsonObject body=packet.kind().equals("state")?OnlineJson.state(full,OnlinePayload.MAX_BYTES):OnlineJson.object(full,OnlinePayload.MAX_BYTES);
                    if(packet.kind().equals("state")){
                        JsonObject host=OnlineJson.child(body,"host");
                        if(!OnlineJson.string(host,"sessionId",36).equals(authenticatedHost.sessionId().toString())
                                ||!OnlineJson.string(host,"hostId",64).equals(authenticatedHost.hostId())
                                ||OnlineJson.integer(host,"epoch",0,Long.MAX_VALUE)!=authenticatedHost.epoch())throw new IOException("Состояние другой сессии");
                        states.accept(body.deepCopy());
                    }else if(packet.kind().equals("lifecycle")){
                        if(OnlineJson.bytes(body).length>8192)throw new IOException("Lifecycle notice too large");Consumer<JsonObject> handler=lifecycle;if(handler!=null)handler.accept(body.deepCopy());
                    }else {
                        JsonObject value=body.deepCopy();value.addProperty("requestId",packet.requestId().toString());replies.accept(value);
                    }
                }catch(Exception invalid){Connection active=authenticatedConnection;if(active!=null)active.disconnect(Component.literal("DarkWinter: некорректное состояние Online"));}
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->earlyDisconnected(handler.getConnection()));
        ClientSendMessageEvents.ALLOW_CHAT.register(message->{
            Minecraft client=Minecraft.getInstance();
            boolean local=client.getSingleplayerServer()!=null&&OnlineGameHost.get(client.getSingleplayerServer())!=null;
            boolean remote=authenticatedHost!=null&&authenticatedHost.equals(expected.get())&&client.getConnection()!=null
                    &&client.getConnection().getConnection()==authenticatedConnection;
            if(!local&&!remote)return true;
            Predicate<String> handler=chatHandler;
            try {if(handler!=null&&handler.test(message))return false;}
            catch(RuntimeException ignored){}
            if(remote&&client.player!=null)client.gui.hud.getChat().addClientSystemMessage(Component.literal("DarkWinter: сообщение не доставлено — канал чата Online не подключён"));
            return true; // Suppress vanilla only when an installed handler actually accepted this message.
        });
    }
    public static boolean authenticated() {
        Minecraft client=Minecraft.getInstance();ExpectedHost host=authenticatedHost;Connection connection=authenticatedConnection;
        return host!=null&&host.authorityUsable()&&connection!=null&&connection.isConnected()&&expected!=null&&host.equals(expected.get())
                &&client.getConnection()!=null&&client.getConnection().getConnection()==connection;
    }
    public static ExpectedHost authenticatedHost(){return authenticated()?authenticatedHost:null;}
    public static long authenticationGeneration(){return AUTH_GENERATION.get();}
    public static void setLifecycleHandler(Consumer<JsonObject> handler){lifecycle=handler;}
    public static void setRemoteDisconnectHandler(Consumer<DisconnectionDetails> handler){remoteDisconnect=handler;}
    /** Fabric may invalidate play authentication on Netty channelInactive before the native reason is dispatched. */
    private static synchronized void earlyDisconnected(Connection connection){
        if(connection==null||connection!=authenticatedConnection)return;
        ExpectedHost host=authenticatedHost;long generation=invalidateAuthentication();
        disconnectedBinding=host!=null&&expected!=null&&host.equals(expected.get())
                ?new DisconnectBinding(connection,host,generation,System.nanoTime()+TimeUnit.SECONDS.toNanos(30)):null;
    }
    /** One-shot context is only for classifying this exact closed connection; it never authenticates packets or RPC. */
    public static void remoteDisconnected(Connection connection,DisconnectionDetails details){
        DisconnectBinding binding;
        synchronized(OnlineConnectionClient.class){
            binding=disconnectedBinding;disconnectedBinding=null;
            if(binding==null&&connection!=null&&connection==authenticatedConnection&&authenticatedHost!=null)
                binding=new DisconnectBinding(connection,authenticatedHost,AUTH_GENERATION.get(),System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
        }
        Consumer<DisconnectionDetails> handler=remoteDisconnect;
        if(handler!=null&&binding!=null&&connection==binding.connection()&&binding.generation()==AUTH_GENERATION.get()
                &&System.nanoTime()<binding.expires()&&expected!=null&&binding.host().equals(expected.get()))handler.accept(details);
    }
    public static void setChatHandler(Predicate<String> handler){chatHandler=handler;}
    private static long invalidateAuthentication(){long generation=AUTH_GENERATION.incrementAndGet();authenticatedConnection=null;authenticatedHost=null;ASSEMBLER.clear();SIGNER.getQueue().clear();return generation;}
    public static synchronized void clear(){disconnectedBinding=null;invalidateAuthentication();}
    public static UUID command(JsonObject body)throws IOException {return send("command",body);}
    public static UUID requestState()throws IOException {return send("state_request",new JsonObject());}
    private static UUID send(String kind,JsonObject body)throws IOException {
        Minecraft client=Minecraft.getInstance();ExpectedHost target=expected==null?null:expected.get();
        if(target==null||authenticatedHost==null||!target.equals(authenticatedHost)||authenticatedConnection==null||!authenticatedConnection.isConnected()
                ||client.getConnection()==null||client.getConnection().getConnection()!=authenticatedConnection)throw new IOException("Нет подтверждённого игрового соединения Online");
        UUID request=UUID.randomUUID();List<OnlinePayload> frames=OnlinePayload.frames(kind,request,target.epoch(),OnlineJson.GSON.toJson(body));
        client.execute(()->{if(target.equals(expected.get())&&authenticatedConnection!=null&&authenticatedConnection.isConnected())frames.forEach(ClientPlayNetworking::send);});return request;
    }
}
