// Signed, session-bound game transport over the caller's existing shared endpoint.
package com.dark.winter.online.iroh;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.signaling.PeerSignaling;
import com.dark.winter.online.transport.ReliableTransportLink;
import com.google.gson.JsonObject;
import computer.iroh.*;
import kotlin.Unit;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Signed, session-bound game transport over the caller's existing shared endpoint.
 * expect() requires prior actual caller approval; this component does not check or grant island ACL,
 * invitations, Native ingress or Node lease authority. Those checks remain mandatory after connectivity.
 */
public final class IrohGameTransport implements AutoCloseable {
    public static final String ALPN="darkwinter/game/1";
    private static final int MAX_FRAME=8192,MAX_PENDING=16,MAX_RETIRED=512,MAX_CONNECTIONS=32;
    private static final long RETIRE_NS=TimeUnit.MINUTES.toNanos(5);
    private static final Duration STREAM_TIMEOUT=Duration.ofSeconds(30);
    private static final Set<String> BODY_FIELDS=Set.of("schema","type","from","to","attempt","descriptorDigest",
            "requestHash","nonce","localIrohKey","remoteIrohKey","issuedAt","expiresAt");
    private final IrohProtocolRouter router;
    private final IdentityStore identity;
    private final String localId,transportKey;
    private final AutoCloseable registration;
    private final Object gate=new Object();
    private final Map<Key,Entry> expected=new HashMap<>(),connecting=new HashMap<>();
    private final Map<Key,Long> retired=new HashMap<>();
    private final Set<OwnedConnection> connections=ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots=new Semaphore(MAX_CONNECTIONS),incomingHandshakes=new Semaphore(MAX_PENDING);
    private final ExecutorService io=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Iroh-game-",0).factory());
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,task->{
        Thread thread=new Thread(task,"Iroh-game-deadlines");thread.setDaemon(true);return thread;
    });
    private final AtomicBoolean closed=new AtomicBoolean();
    private long replayBlockedUntil;

    private record Key(UUID attempt,String peer,String nonce){
        @Override public String toString(){return "IrohGameAttempt[redacted]";}
    }
    private record Binding(IdentityProfile peer,IdentityProfile local,String peerKey,Key key,String descriptorDigest,
                           String requestHash,long deadline){
        @Override public String toString(){return "IrohGameBinding[redacted]";}
    }
    private static final class Entry {
        final Binding binding;final boolean host;
        final CompletableFuture<ReliableTransportLink> result=new CompletableFuture<>();
        volatile CompletableFuture<?> connectingCall;
        volatile OwnedConnection owner;
        volatile ScheduledFuture<?> deadline;
        boolean finished,claimed;
        Entry(Binding binding,boolean host){this.binding=binding;this.host=host;}
    }
    private record Received(IdentityStore.SignedPayload envelope,JsonObject body){
        @Override public String toString(){return "IrohGameFrame[redacted]";}
    }

    public IrohGameTransport(IrohProtocolRouter router,IdentityStore realSharedIdentity)throws IOException{
        this.router=Objects.requireNonNull(router);identity=Objects.requireNonNull(realSharedIdentity);
        localId=IdentityStore.validateProfileProof(identity.publicProof()).id();transportKey=router.publicKeyHex();
        timer.setRemoveOnCancelPolicy(true);timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        registration=router.register(ALPN,this::incoming);
    }
    public String publicKeyHex(){if(closed.get())throw new IllegalStateException("Game transport closed");return transportKey;}
    public int pendingCount(){synchronized(gate){return expected.size()+connecting.size();}}
    public int activeCount(){return connections.size();}

    /**
     * The actual approved host must register before sending its authenticated stream offer.
     * Registering an expectation grants no game/Node authority and sends no native game bytes.
     */
    public CompletableFuture<ReliableTransportLink> expect(IdentityStore.SignedPayload peerProfile,String peerTransportHex,
            UUID attempt,PeerSignaling.Descriptor descriptor,String requestHash,String nonce64hex,Duration timeout){
        try{return register(binding(peerProfile,peerTransportHex,attempt,descriptor,requestHash,nonce64hex,timeout),true).result;}
        catch(Exception invalid){return failed();}
    }
    /** peerProfile/peerTransportHex are the approved host's exact PROFILE proof and Iroh public key. */
    public CompletableFuture<ReliableTransportLink> connect(IdentityStore.SignedPayload peerProfile,String peerTransportHex,
            UUID attempt,PeerSignaling.Descriptor descriptor,String requestHash,String nonce64hex,Duration timeout){
        try{
            Entry entry=register(binding(peerProfile,peerTransportHex,attempt,descriptor,requestHash,nonce64hex,timeout),false);
            execute(()->connect(entry));return entry.result;
        }catch(Exception invalid){return failed();}
    }
    /** Explicit host-key overload; disagreement is rejected rather than choosing either key. */
    public CompletableFuture<ReliableTransportLink> connect(IdentityStore.SignedPayload peerProfile,String peerTransportHex,
            UUID attempt,PeerSignaling.Descriptor descriptor,String requestHash,String nonce64hex,String hostHex,Duration timeout){
        if(!Objects.equals(peerTransportHex,hostHex))return failed();
        return connect(peerProfile,peerTransportHex,attempt,descriptor,requestHash,nonce64hex,timeout);
    }
    private Binding binding(IdentityStore.SignedPayload proof,String peerKey,UUID attempt,PeerSignaling.Descriptor descriptor,
                            String requestHash,String nonce,Duration timeout)throws Exception{
        IdentityProfile peer=IdentityStore.validateProfileProof(proof),local=IdentityStore.validateProfileProof(identity.publicProof());
        if(!local.id().equals(localId)||peer.id().equals(localId)||attempt==null||descriptor==null
                ||!hex(peerKey)||peerKey.equals(transportKey)||!hex(requestHash)||!hex(nonce)
                ||timeout==null||timeout.compareTo(Duration.ofMillis(1))<0||timeout.compareTo(Duration.ofMinutes(2))>0)throw failure();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ProfileFiles.bytes(OnlineJson.GSON.toJsonTree(descriptor))));
        return new Binding(peer,local,peerKey,new Key(attempt,peer.id(),nonce),digest,requestHash,System.nanoTime()+timeout.toNanos());
    }
    private Entry register(Binding binding,boolean host)throws IOException{
        Entry entry=new Entry(binding,host);
        synchronized(gate){
            long now=System.nanoTime();retired.entrySet().removeIf(value->value.getValue()<=now);
            Map<Key,Entry> selected=host?expected:connecting;
            if(closed.get()||now<replayBlockedUntil||selected.size()>=MAX_PENDING||expected.containsKey(binding.key())
                    ||connecting.containsKey(binding.key())||retired.containsKey(binding.key())
                    ||retired.size()+expected.size()+connecting.size()>=MAX_RETIRED)throw failure();
            selected.put(binding.key(),entry);
            entry.deadline=timer.schedule(()->execute(()->fail(entry)),Math.max(1,binding.deadline()-now),TimeUnit.NANOSECONDS);
        }
        entry.result.whenComplete((value,error)->{if(entry.result.isCancelled())execute(()->fail(entry));});
        return entry;
    }
    private void connect(Entry entry){
        Connection raw=null;OwnedConnection owner=null;
        try{
            ensure(entry);
            CompletableFuture<Connection> pending=router.service().connect(entry.binding.peerKey(),ALPN,remaining(entry.binding.deadline()));
            entry.connectingCall=pending;
            if(entry.result.isDone()||closed.get())pending.cancel(true);
            raw=pending.get(remainingNanos(entry.binding.deadline()),TimeUnit.NANOSECONDS);
            ensure(entry);owner=adopt(raw);raw=null;entry.owner=owner;
            ensure(entry);verifyConnection(owner.connection,entry.binding.peerKey());
            BiStream stream=owner.await(IrohAsync.<BiStream>call(remaining(entry.binding.deadline()),owner.connection::openBi),entry.binding.deadline());
            owner.setStream(stream);
            try(SendStream send=stream.send();RecvStream recv=stream.recv()){
                send(owner,send,signed("iroh_game_hello",entry.binding),entry.binding.deadline());
                Received ack=receive(owner,recv,entry.binding.deadline());
                verify(ack,"iroh_game_ack",entry.binding,entry.binding.peerKey());
            }
            ensure(entry);
            publish(entry,owner.link());
        }catch(Throwable rejected){fail(entry);if(owner!=null)owner.close();}
        finally{if(raw!=null)end(raw);}
    }
    private void incoming(Connection connection){
        if(closed.get()||!incomingHandshakes.tryAcquire()){end(connection);return;}
        OwnedConnection owner;
        try{owner=adopt(connection);}
        catch(Exception rejected){incomingHandshakes.release();end(connection);return;}
        execute(()->{
            Entry selected=null;boolean transferred=false,claimed=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try{
                verifyAlpn(connection);
                BiStream stream=owner.await(IrohAsync.<BiStream>call(remaining(deadline),connection::acceptBi),deadline);
                owner.setStream(stream);
                try(RecvStream recv=stream.recv();SendStream send=stream.send()){
                    Received hello=receive(owner,recv,deadline);
                    JsonObject body=hello.body();
                    Key key=new Key(uuid(ProfileFiles.string(body,"attempt",36)),hello.envelope().signer().id(),ProfileFiles.string(body,"nonce",64));
                    synchronized(gate){selected=expected.get(key);}
                    if(selected==null)throw failure();
                    verifyConnection(connection,selected.binding.peerKey());
                    verify(hello,"iroh_game_hello",selected.binding,selected.binding.peerKey());
                    claim(selected,owner);claimed=true;
                    long permitted=Math.min(deadline,selected.binding.deadline());
                    send(owner,send,signed("iroh_game_ack",selected.binding),permitted);
                }
                ensure(selected);
                transferred=publish(selected,owner.link());
            }catch(Throwable rejected){if(selected!=null&&claimed)fail(selected);}
            finally{incomingHandshakes.release();if(!transferred)owner.close();}
        });
    }
    private void claim(Entry entry,OwnedConnection owner)throws IOException{
        synchronized(entry){
            ensure(entry);
            synchronized(gate){
                if(expected.remove(entry.binding.key(),entry)==false||entry.claimed)throw failure();
                entry.claimed=true;retire(entry.binding.key());
            }
            entry.owner=owner;
        }
    }
    private boolean publish(Entry entry,ReliableTransportLink link){
        boolean delivered=false;
        synchronized(entry){
            try{
                ensure(entry);entry.finished=true;remove(entry);
                delivered=entry.result.complete(link);
            }catch(Exception rejected){/* Failure publication is handled below without exposing wire text. */}
        }
        if(!delivered){link.close();fail(entry);}
        return delivered;
    }
    private void fail(Entry entry){
        synchronized(entry){
            if(entry.finished)return;
            entry.finished=true;remove(entry);
            CompletableFuture<?> pending=entry.connectingCall;if(pending!=null)pending.cancel(true);
            OwnedConnection owner=entry.owner;if(owner!=null)owner.close();
            entry.result.completeExceptionally(failure());
        }
    }
    private void remove(Entry entry){
        ScheduledFuture<?> deadline=entry.deadline;if(deadline!=null)deadline.cancel(false);
        synchronized(gate){
            expected.remove(entry.binding.key(),entry);connecting.remove(entry.binding.key(),entry);retire(entry.binding.key());
        }
    }
    private void retire(Key key){
        if(retired.containsKey(key))return;
        if(retired.size()<MAX_RETIRED)retired.put(key,System.nanoTime()+RETIRE_NS);
        else replayBlockedUntil=Math.max(replayBlockedUntil,System.nanoTime()+RETIRE_NS);
    }
    private void ensure(Entry entry)throws IOException{
        if(closed.get()||entry==null||entry.finished||entry.result.isDone()||System.nanoTime()>=entry.binding.deadline()
                ||!identity.profile().equals(entry.binding.local()))throw failure();
    }

    private IdentityStore.SignedPayload signed(String type,Binding binding)throws IOException{
        if(!identity.profile().equals(binding.local()))throw failure();
        long now=System.currentTimeMillis(),left=TimeUnit.NANOSECONDS.toMillis(remainingNanos(binding.deadline()));
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type",type);
        body.addProperty("from",localId);body.addProperty("to",binding.peer().id());
        body.addProperty("attempt",binding.key().attempt().toString());body.addProperty("descriptorDigest",binding.descriptorDigest());
        body.addProperty("requestHash",binding.requestHash());body.addProperty("nonce",binding.key().nonce());
        body.addProperty("localIrohKey",transportKey);body.addProperty("remoteIrohKey",binding.peerKey());
        body.addProperty("issuedAt",now);body.addProperty("expiresAt",now+Math.max(1,Math.min(30000,left)));
        return identity.sign(IdentityStore.Purpose.AUTH,ProfileFiles.bytes(body));
    }
    private void verify(Received received,String type,Binding binding,String actualPeerKey)throws IOException{
        IdentityStore.SignedPayload signed=received.envelope();JsonObject body=received.body();
        long now=System.currentTimeMillis(),issued=ProfileFiles.number(body,"issuedAt"),expires=ProfileFiles.number(body,"expiresAt");
        if(!IdentityStore.verify(signed,IdentityStore.Purpose.AUTH)||!signed.signer().equals(binding.peer())
                ||!actualPeerKey.equals(binding.peerKey())||ProfileFiles.number(body,"schema")!=1
                ||!ProfileFiles.string(body,"type",32).equals(type)
                ||!ProfileFiles.string(body,"from",64).equals(binding.peer().id())||!ProfileFiles.string(body,"to",64).equals(localId)
                ||!ProfileFiles.string(body,"attempt",36).equals(binding.key().attempt().toString())
                ||!ProfileFiles.string(body,"descriptorDigest",64).equals(binding.descriptorDigest())
                ||!ProfileFiles.string(body,"requestHash",64).equals(binding.requestHash())
                ||!ProfileFiles.string(body,"nonce",64).equals(binding.key().nonce())
                ||!ProfileFiles.string(body,"localIrohKey",64).equals(binding.peerKey())
                ||!ProfileFiles.string(body,"remoteIrohKey",64).equals(transportKey)
                ||issued<0||issued>now+15000||expires<=now||expires<=issued||expires-issued>30000)throw failure();
    }
    private static void send(OwnedConnection owner,SendStream stream,IdentityStore.SignedPayload signed,long deadline)throws Exception{
        byte[] payload=ProfileFiles.bytes(OnlineJson.GSON.toJsonTree(signed));
        if(payload.length<1||payload.length>MAX_FRAME)throw failure();
        byte[] framed=ByteBuffer.allocate(4+payload.length).putInt(payload.length).put(payload).array();
        owner.await(IrohAsync.<Unit>call(remaining(deadline),continuation->stream.writeAll(framed,continuation)),deadline);
    }
    private static Received receive(OwnedConnection owner,RecvStream stream,long deadline)throws Exception{
        byte[] header=owner.await(IrohAsync.<byte[]>call(remaining(deadline),IrohAsync.readExact(stream,4)),deadline);
        int length=ByteBuffer.wrap(header).getInt();if(length<1||length>MAX_FRAME)throw failure();
        byte[] payload=owner.await(IrohAsync.<byte[]>call(remaining(deadline),IrohAsync.readExact(stream,length)),deadline);
        JsonObject document=ProfileFiles.parse(payload,MAX_FRAME);
        ProfileFiles.fields(document,Set.of("schema","purpose","signer","data","signature"),Set.of());
        JsonObject profile=document.getAsJsonObject("signer");
        ProfileFiles.fields(profile,Set.of("schema","id","publicKey","encryptionPublicKey","nickname","model","privacy","revision","createdAt"),Set.of());
        ProfileFiles.fields(profile.getAsJsonObject("privacy"),Set.of("presence","requests","messages","invitations"),Set.of());
        IdentityStore.SignedPayload envelope=OnlineJson.GSON.fromJson(document,IdentityStore.SignedPayload.class);
        if(!IdentityStore.verify(envelope,IdentityStore.Purpose.AUTH))throw failure();
        JsonObject body=ProfileFiles.parse(IdentityStore.decodeData(envelope),4096);
        ProfileFiles.fields(body,BODY_FIELDS,Set.of());
        return new Received(envelope,body);
    }
    private static void verifyAlpn(Connection connection)throws IOException{
        if(!Arrays.equals(connection.alpn(),ALPN.getBytes(StandardCharsets.US_ASCII)))throw failure();
    }
    private static void verifyConnection(Connection connection,String expected)throws IOException{
        verifyAlpn(connection);
        try(EndpointId actual=connection.remoteId()){
            if(!HexFormat.of().formatHex(actual.toBytes()).equals(expected))throw failure();
        }
    }
    private OwnedConnection adopt(Connection connection)throws IOException{
        synchronized(gate){
            if(closed.get()||!connectionSlots.tryAcquire())throw failure();
            OwnedConnection owned=new OwnedConnection(connection);connections.add(owned);return owned;
        }
    }
    private final class OwnedConnection {
        final Connection connection;
        final Set<CompletableFuture<?>> calls=ConcurrentHashMap.newKeySet();
        final AtomicBoolean ended=new AtomicBoolean();
        final CompletableFuture<Void> parentClosed=new CompletableFuture<>();
        private BiStream raw;private IrohStreamLink link;
        OwnedConnection(Connection connection){this.connection=connection;}
        synchronized void setStream(BiStream stream)throws IOException{
            if(ended.get()){try{stream.close();}catch(Throwable ignored){}throw failure();}
            if(raw!=null||link!=null){try{stream.close();}catch(Throwable ignored){}throw failure();}
            raw=stream;
        }
        <T>T await(CompletableFuture<T> call,long deadline)throws Exception{
            calls.add(call);if(ended.get())call.cancel(true);
            try{return call.get(remainingNanos(deadline),TimeUnit.NANOSECONDS);}
            catch(Exception rejected){call.cancel(true);throw rejected;}
            finally{calls.remove(call);}
        }
        synchronized ReliableTransportLink link()throws IOException{
            if(ended.get()||raw==null||link!=null)throw failure();
            IrohStreamLink actual=new IrohStreamLink(raw,STREAM_TIMEOUT);raw=null;link=actual;
            actual.closed().whenComplete((value,error)->close());
            return new ReliableTransportLink(){
                public CompletableFuture<Void> opened(){return actual.opened();}
                public CompletableFuture<Void> closed(){return actual.closed().thenCombine(parentClosed,(left,right)->(Void)null);}
                public CompletableFuture<Void> send(byte[] bytes){return actual.send(bytes);}
                public CompletableFuture<byte[]> read(){return actual.read();}
                public void close(){OwnedConnection.this.close();}
                @Override public String toString(){return "IrohAuthenticatedGameStream[transportOnly]";}
            };
        }
        void close(){
            if(!ended.compareAndSet(false,true))return;
            for(CompletableFuture<?> call:calls)call.cancel(true);
            execute(()->{
                IrohStreamLink ownedLink;BiStream ownedRaw;
                synchronized(this){ownedLink=link;ownedRaw=raw;raw=null;}
                if(ownedLink!=null)ownedLink.close();
                end(connection);
                if(ownedRaw!=null)try{ownedRaw.close();}catch(Throwable ignored){}
                connections.remove(this);connectionSlots.release();parentClosed.complete(null);
            });
        }
    }
    private void execute(Runnable work){
        try{io.execute(work);}
        catch(RuntimeException stopped){Thread.ofVirtual().name("Iroh-game-cleanup").start(work);}
    }
    private static long remainingNanos(long deadline)throws IOException{
        long left=deadline-System.nanoTime();if(left<TimeUnit.MILLISECONDS.toNanos(1))throw failure();return left;
    }
    private static Duration remaining(long deadline)throws IOException{return Duration.ofNanos(Math.min(TimeUnit.MINUTES.toNanos(2),remainingNanos(deadline)));}
    private static UUID uuid(String value)throws IOException{
        try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw failure();return id;}catch(IllegalArgumentException invalid){throw failure();}
    }
    private static boolean hex(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void end(Connection connection){
        if(connection==null)return;
        try{connection.close(0,new byte[0]);}catch(Throwable ignored){}
        try{connection.close();}catch(Throwable ignored){}
    }
    private static IOException failure(){return new IOException("Iroh game transport handshake unavailable");}
    private static <T>CompletableFuture<T> failed(){return CompletableFuture.failedFuture(failure());}
    @Override public void close(){
        List<Entry> pending;
        synchronized(gate){
            if(!closed.compareAndSet(false,true))return;
            pending=new ArrayList<>(expected.values());pending.addAll(connecting.values());
        }
        try{registration.close();}catch(Exception ignored){}
        timer.shutdownNow();
        execute(()->{for(Entry entry:pending)fail(entry);for(OwnedConnection owner:connections)owner.close();});
        io.shutdown();
    }
    @Override public String toString(){return "IrohGameTransport[transportOnly]";}
}
