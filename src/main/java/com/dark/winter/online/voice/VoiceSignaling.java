// E2E encrypted voice signaling, strictly inside an already admitted native Minecraft session.
package com.dark.winter.online.voice;

import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** E2E encrypted voice signaling, strictly inside an already admitted native Minecraft session. */
public final class VoiceSignaling implements AutoCloseable {
    @FunctionalInterface public interface Outbound { CompletableFuture<JsonObject> send(String peer, JsonObject sealed); }
    public interface Listener {
        CompletableFuture<Void> connected(Context context, String verifiedPeer, VoicePeer peer);
        default void disconnected(String verifiedPeer, VoicePeer peer, String reason) {}
        default void status(String verifiedPeer, String state, String reason) {}
    }
    public record Context(UUID sessionId, long epoch, boolean localHost, String hostId, String localId,
                          Map<String, IdentityStore.SignedPayload> nativeParticipants) {
        public Context {
            Objects.requireNonNull(sessionId); VoiceRoutePolicy.requireId(hostId); VoiceRoutePolicy.requireId(localId);
            nativeParticipants = Map.copyOf(nativeParticipants);
            if (epoch < 0 || nativeParticipants.size() < 1 || nativeParticipants.size() > 16
                    || !nativeParticipants.containsKey(localId) || !nativeParticipants.containsKey(hostId)
                    || localHost != hostId.equals(localId)) throw new IllegalArgumentException("Native voice session context");
        }
    }
    private static final Gson JSON = new Gson();
    private static final long NEGOTIATION = TimeUnit.SECONDS.toNanos(30), RETRY = TimeUnit.SECONDS.toNanos(2),
            RETIRED = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_BODY = 32768;
    private final Supplier<IdentityStore> identities;
    private final WebRtcVoiceTransport transport;
    private final Outbound outbound;
    private final Listener listener;
    private final Clock clock;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512), Thread.ofVirtual().name("DW-voice-control-",0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task,"DW-voice-control-clock");thread.setDaemon(true);return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, Long> retired = new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile Context context;
    private long blockedUntil;
    private static final class Attempt {
        final String peerId; final UUID id; final Context session;
        final Map<String, VoicePeer.Candidate> localCandidates = new LinkedHashMap<>();
        final Set<String> deliveredCandidates = new HashSet<>(), appliedCandidates = new HashSet<>();
        final Map<String, VoicePeer.Candidate> remoteCandidates = new LinkedHashMap<>();
        final Map<UUID, String> messages = new HashMap<>();
        volatile VoicePeer peer;
        VoicePeer.Description localDescription, remoteDescription;
        long deadline = System.nanoTime() + NEGOTIATION, retryAt;
        int sends; boolean creating, connecting, ready; volatile boolean removed;
        Attempt(String peerId,UUID id,Context session) {this.peerId=peerId;this.id=id;this.session=session;}
    }
    public VoiceSignaling(Supplier<IdentityStore> identities, WebRtcVoiceTransport transport, Outbound outbound, Listener listener, Clock clock) {
        this.identities=Objects.requireNonNull(identities);this.transport=Objects.requireNonNull(transport);
        this.outbound=Objects.requireNonNull(outbound);this.listener=Objects.requireNonNull(listener);this.clock=Objects.requireNonNull(clock);
        timer.setRemoveOnCancelPolicy(true);timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.scheduleWithFixedDelay(()->execute(this::tick,null),100,100,TimeUnit.MILLISECONDS);
    }
    public CompletableFuture<Void> update(Context next) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        execute(()->{
            try {
                if(next==null){context=null;for(Attempt state:List.copyOf(attempts.values()))fail(state,"Native session closed",false);retired.clear();result.complete(null);return;}
                IdentityStore identity=identity();
                if(!identity.profile().id().equals(next.localId()))throw new IOException("Voice local identity differs from native admission");
                if(next.equals(context)){result.complete(null);return;}
                for(var entry:next.nativeParticipants().entrySet()){
                    IdentityProfile peer=IdentityStore.validateProfileProof(entry.getValue());
                    if(!peer.id().equals(entry.getKey()))throw new IOException("Native participant proof/key mismatch");
                }
                Context previous=context;context=next;
                if(previous==null||!sameSession(previous,next)){
                    for(Attempt state:List.copyOf(attempts.values()))fail(state,"Voice authority changed",false);
                    retired.clear();blockedUntil=0;
                }else for(Attempt state:List.copyOf(attempts.values()))
                    if(!permitted(next,state.peerId)||!sameKeys(previous.nativeParticipants().get(state.peerId),next.nativeParticipants().get(state.peerId)))
                        fail(state,"Native participant left or changed key",true);
                result.complete(null);tick();
            }catch(Exception failure){result.completeExceptionally(new IOException("Voice native context rejected",failure));}
        },result);return result.copy();
    }
    /** Root's control envelope wrapper has already been routed here; this verifies/decrypts its inner voice envelope. */
    public CompletableFuture<Void> receive(String from,JsonObject sealed) {
        CompletableFuture<Void> result=new CompletableFuture<>();JsonObject copy;
        try{VoiceRoutePolicy.requireId(from);if(sealed==null||ProfileFiles.bytes(sealed).length>65536)throw new IOException("Voice control exceeds bounds");copy=sealed.deepCopy();}
        catch(Exception invalid){return CompletableFuture.failedFuture(new IOException("Invalid voice control",invalid));}
        execute(()->{try{receiveInternal(from,copy);result.complete(null);}catch(Exception failure){result.completeExceptionally(new IOException("Voice control rejected",failure));}},result);
        return result.copy();
    }
    private void receiveInternal(String from,JsonObject wire)throws Exception {
        Context session=requireContext();if(!permitted(session,from))throw new IOException("Voice sender is not a current native participant");
        IdentityStore identity=identity();
        IdentityStore.SignedPayload sealed=JSON.fromJson(wire,IdentityStore.SignedPayload.class);
        if(!IdentityStore.verify(sealed,IdentityStore.Purpose.SIGNAL)||!from.equals(sealed.signer().id())
                ||!sameKeys(session.nativeParticipants().get(from),sealed))throw new IOException("Voice signer not pinned to native profile keys");
        JsonObject body=ProfileFiles.parse(identity.openSealed(sealed,IdentityStore.Purpose.SIGNAL),MAX_BODY);
        ProfileFiles.fields(body,Set.of("schema","kind","type","messageId","sessionId","epoch","hostId","from","to","attempt",
                "issuedAt","expiresAt","profileProof","payload"),Set.of());
        String type=ProfileFiles.string(body,"type",16);
        if(!Set.of("offer","answer","candidate","close").contains(type))throw new IOException("Unknown voice control");
        long issued=ProfileFiles.number(body,"issuedAt"),expires=ProfileFiles.number(body,"expiresAt"),now=clock.millis();
        if(ProfileFiles.number(body,"schema")!=1||!ProfileFiles.string(body,"kind",32).equals("dw_voice_v1")
                ||!ProfileFiles.string(body,"sessionId",36).equals(session.sessionId().toString())
                ||ProfileFiles.number(body,"epoch")!=session.epoch()||!ProfileFiles.string(body,"hostId",64).equals(session.hostId())
                ||!ProfileFiles.string(body,"from",64).equals(from)||!ProfileFiles.string(body,"to",64).equals(session.localId())
                ||issued<0||issued>now+5000||expires<=now||expires<=issued||expires-issued>30000)
            throw new IOException("Expired or foreign voice control context");
        IdentityStore.SignedPayload proof=JSON.fromJson(body.get("profileProof"),IdentityStore.SignedPayload.class);
        IdentityProfile peer=IdentityStore.validateProfileProof(proof);
        if(!peer.equals(sealed.signer())||!sameKeys(session.nativeParticipants().get(from),proof))
            throw new IOException("Voice proof differs from authenticated envelope");
        UUID attempt=uuid(ProfileFiles.string(body,"attempt",36)),message=uuid(ProfileFiles.string(body,"messageId",36));
        if(retired.containsKey(from+":"+attempt)||System.nanoTime()<blockedUntil)throw new IOException("Voice attempt retired");
        JsonObject payload=body.getAsJsonObject("payload");if(payload==null)throw new IOException("Voice payload missing");
        Attempt state=attempts.get(from);
        if(type.equals("offer")){
            if(session.localHost()||!from.equals(session.hostId()))throw new IOException("Only admitted session host may offer voice");
            VoicePeer.Description offer=description(payload,"offer");
            if(state==null||!state.id.equals(attempt)){
                if(state!=null)fail(state,"Host replaced voice channel",false);
                state=new Attempt(from,attempt,session);attempts.put(from,state);
            }
            seen(state,message,body);
            if(state.remoteDescription!=null&&!state.remoteDescription.equals(offer))throw new IOException("Voice offer changed within attempt");
            state.remoteDescription=offer;create(state);
            if(state.localDescription!=null)sendDescription(state);
            return;
        }
        if(type.equals("candidate") && (state==null || !state.id.equals(attempt)) && !session.localHost() && from.equals(session.hostId())) {
            if(state!=null)fail(state,"Host replaced voice channel",false);
            state=new Attempt(from,attempt,session);attempts.put(from,state);
        }
        if(state==null||!state.id.equals(attempt)||!sameSession(state.session,session))throw new IOException("Unknown voice attempt");
        seen(state,message,body);
        switch(type){
            case "answer"->{
                if(!session.localHost()||state.peer==null||state.localDescription==null)throw new IOException("No host voice offer awaits answer");
                VoicePeer.Description answer=description(payload,"answer");
                if(state.remoteDescription!=null){if(!state.remoteDescription.equals(answer))throw new IOException("Voice answer changed");return;}
                state.remoteDescription=answer;Attempt answering=state;
                state.peer.acceptAnswer(answer).whenComplete((value,error)->execute(()->{if(alive(answering)&&error!=null)fail(answering,"Voice answer could not be applied",true);},null));
            }
            case "candidate"->{
                ProfileFiles.fields(payload,Set.of("sdpMid","sdpMLineIndex","sdp"),Set.of());
                VoicePeer.Candidate candidate=new VoicePeer.Candidate(ProfileFiles.string(payload,"sdpMid",256),
                        Math.toIntExact(ProfileFiles.number(payload,"sdpMLineIndex")),ProfileFiles.string(payload,"sdp",8192));
                String digest=hash(ProfileFiles.bytes(payload));
                if(!state.remoteCandidates.containsKey(digest)){
                    if(state.remoteCandidates.size()>=128)throw new IOException("Voice remote ICE limit");
                    state.remoteCandidates.put(digest,candidate);applyRemoteCandidates(state);
                }
            }
            case "close"->{ProfileFiles.fields(payload,Set.of(),Set.of());fail(state,"Peer closed voice channel",false);}
            default->throw new IOException("Unexpected voice control");
        }
    }
    private void seen(Attempt state,UUID message,JsonObject body)throws IOException {
        String digest=hash(ProfileFiles.bytes(body)),prior=state.messages.get(message);
        if(prior!=null&&!prior.equals(digest))throw new IOException("Voice message ID reused with different content");
        if(prior==null){if(state.messages.size()>=512)throw new IOException("Voice replay ledger capacity");state.messages.put(message,digest);}
    }
    private void create(Attempt state) {
        if(!alive(state)||state.creating||state.peer!=null||!state.session.localHost()&&state.remoteDescription==null)return;state.creating=true;
        transport.createPeer("voice_"+state.id.toString().replace("-","")).whenComplete((peer,error)->{
            if(closed.get()||!aliveOutsideWorker(state)){if(peer!=null)peer.close();return;}
            execute(()->{
                if(!alive(state)){if(peer!=null)peer.close();return;}
                if(error!=null||peer==null){fail(state,"No usable voice transport route",true);return;}
                state.peer=peer;state.creating=false;pumpCandidate(state);applyRemoteCandidates(state);
                peer.closed().whenComplete((ignored,failure)->execute(()->{if(alive(state))fail(state,"Voice channel disconnected",false);},null));
                peer.opened().whenComplete((ignored,failure)->execute(()->opened(state,failure),null));
                CompletableFuture<VoicePeer.Description> description=state.session.localHost()?peer.offer():peer.answer(state.remoteDescription);
                description.whenComplete((sdp,failure)->execute(()->{
                    if(!alive(state))return;if(failure!=null){fail(state,"Voice media negotiation failed",true);return;}
                    try{if(ProfileFiles.bytes(JSON.toJsonTree(sdp)).length>MAX_BODY-8192)throw new IOException("Voice SDP budget");
                        state.localDescription=sdp;sendDescription(state);
                    }catch(Exception rejected){fail(state,"Voice description could not be delivered",true);}
                },null));
            },null);
        });
    }
    private void applyRemoteCandidates(Attempt state) {
        if(state.peer==null)return;
        for(var candidate:state.remoteCandidates.entrySet())if(state.appliedCandidates.add(candidate.getKey()))
            state.peer.addRemoteCandidate(candidate.getValue()).whenComplete((value,error)->execute(()->{
                if(alive(state)&&error!=null)fail(state,"Voice ICE rejected",true);
            },null));
    }
    private void opened(Attempt state,Throwable error) {
        if(!alive(state))return;
        if(error!=null){fail(state,"Voice route did not connect",true);return;}
        if(state.connecting||state.ready)return;state.connecting=true;
        CompletableFuture<Void> accepted;
        try{accepted=Objects.requireNonNull(listener.connected(context,state.peerId,state.peer));}
        catch(Exception rejected){fail(state,"Voice application rejected participant",true);return;}
        accepted.whenComplete((ignored,failure)->execute(()->{
            if(!alive(state))return;
            if(failure!=null){fail(state,"Voice application could not attach participant",true);return;}
            state.ready=true;state.connecting=false;listener.status(state.peerId,"connected","Защищённый голосовой канал подключён.");
        },null));
    }
    private void pumpCandidate(Attempt state) {
        if(!alive(state)||state.peer==null)return;
        state.peer.nextLocalCandidate().whenComplete((candidate,error)->execute(()->{
            if(!alive(state))return;
            if(error!=null){if(!state.ready)fail(state,"Voice ICE collection ended",false);return;}
            try{
                String digest=hash(ProfileFiles.bytes(JSON.toJsonTree(candidate)));
                if(state.localCandidates.size()>=128)throw new IOException("Voice local ICE limit");
                state.localCandidates.putIfAbsent(digest,candidate);pumpSends(state);pumpCandidate(state);
            }catch(Exception rejected){fail(state,"Voice ICE capacity exceeded",true);}
        },null));
    }
    private void pumpSends(Attempt state)throws IOException {
        if(state.localDescription==null)return; // Remote must receive the authenticated offer/answer before candidates.
        for(var entry:state.localCandidates.entrySet()){
            if(state.sends>=8)break;if(state.deliveredCandidates.contains(entry.getKey()))continue;
            state.deliveredCandidates.add(entry.getKey());
            send(state,"candidate",JSON.toJsonTree(entry.getValue()).getAsJsonObject(),success->{
                if(!success)state.deliveredCandidates.remove(entry.getKey());
            });
        }
    }
    private void sendDescription(Attempt state)throws IOException {
        if(state.localDescription==null||state.sends>=8)return;
        send(state,state.session.localHost()?"offer":"answer",JSON.toJsonTree(state.localDescription).getAsJsonObject(),ignored->{});
        state.retryAt=System.nanoTime()+RETRY;
        // Candidates may be delivered out of order by the service. Receiver queues them until SDP is applied.
        pumpSends(state);
    }
    private void send(Attempt state,String type,JsonObject payload,java.util.function.Consumer<Boolean> completion)throws IOException {
        Context session=requireContext();if(!alive(state)||!permitted(session,state.peerId))throw new IOException("Voice native admission ended");
        IdentityStore identity=identity();IdentityStore.SignedPayload proof=session.nativeParticipants().get(state.peerId);
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("kind","dw_voice_v1");body.addProperty("type",type);
        body.addProperty("messageId",UUID.randomUUID().toString());body.addProperty("sessionId",session.sessionId().toString());
        body.addProperty("epoch",session.epoch());body.addProperty("hostId",session.hostId());body.addProperty("from",session.localId());
        body.addProperty("to",state.peerId);body.addProperty("attempt",state.id.toString());
        long now=clock.millis();body.addProperty("issuedAt",now);body.addProperty("expiresAt",now+30000);
        body.add("profileProof",JSON.toJsonTree(identity.publicProof()));body.add("payload",payload);
        byte[] bytes=ProfileFiles.bytes(body);if(bytes.length>MAX_BODY)throw new IOException("Voice control body limit");
        JsonObject sealed=JSON.toJsonTree(identity.seal(proof,IdentityStore.Purpose.SIGNAL,bytes)).getAsJsonObject();
        state.sends++;
        CompletableFuture<JsonObject> delivery;
        try{delivery=Objects.requireNonNull(outbound.send(state.peerId,sealed));}
        catch(Exception unavailable){state.sends--;completion.accept(false);return;}
        delivery.orTimeout(10,TimeUnit.SECONDS).whenComplete((reply,error)->execute(()->{
            state.sends=Math.max(0,state.sends-1);
            if(alive(state))completion.accept(error==null&&reply!=null&&reply.has("ok")&&reply.get("ok").isJsonPrimitive()&&reply.get("ok").getAsBoolean());
        },null));
    }
    private void tick() {
        long now=System.nanoTime();retired.entrySet().removeIf(entry->entry.getValue()<now);
        Context session=context;if(session==null)return;
        try{if(!identity().profile().id().equals(session.localId())){context=null;for(Attempt state:List.copyOf(attempts.values()))fail(state,"Local identity changed",false);return;}}
        catch(IOException unavailable){return;}
        for(Attempt state:List.copyOf(attempts.values())){
            if(!permitted(session,state.peerId)||!sameSession(session,state.session)){fail(state,"Native admission ended",true);continue;}
            if(!state.ready&&now>=state.deadline){fail(state,"Voice route/signaling timed out",true);continue;}
            try{if(!state.ready&&state.localDescription!=null&&now>=state.retryAt)sendDescription(state);else pumpSends(state);}
            catch(IOException unavailable){listener.status(state.peerId,"waiting","Голосовой сигнал ожидает служебной доставки.");}
        }
        if(session.localHost()&&now>=blockedUntil)for(String id:session.nativeParticipants().keySet()){
            if(id.equals(session.localId())||attempts.containsKey(id))continue;
            // Bounded retry after a transport failure; no peer outside the native admission map can trigger JNI.
            boolean cooling=retired.keySet().stream().anyMatch(key->key.startsWith(id+":"));
            if(cooling)continue;
            Attempt state=new Attempt(id,UUID.randomUUID(),session);attempts.put(id,state);create(state);
        }
    }
    private Context requireContext()throws IOException {Context value=context;if(value==null||!identity().profile().id().equals(value.localId()))throw new IOException("No current native voice session");return value;}
    private IdentityStore identity()throws IOException {try{return Objects.requireNonNull(identities.get());}catch(RuntimeException failure){throw new IOException("Voice identity unavailable",failure);}}
    private static boolean sameSession(Context a,Context b){return a.sessionId().equals(b.sessionId())&&a.epoch()==b.epoch()&&a.localHost()==b.localHost()&&a.hostId().equals(b.hostId())&&a.localId().equals(b.localId());}
    private static boolean permitted(Context context,String peer){return !peer.equals(context.localId())&&context.nativeParticipants().containsKey(peer)&&(context.localHost()||peer.equals(context.hostId()));}
    private static boolean sameKeys(IdentityStore.SignedPayload a,IdentityStore.SignedPayload b){
        return a!=null&&b!=null&&a.signer()!=null&&b.signer()!=null&&a.signer().id().equals(b.signer().id())
                &&a.signer().publicKey().equals(b.signer().publicKey())&&a.signer().encryptionPublicKey().equals(b.signer().encryptionPublicKey());
    }
    private boolean alive(Attempt state){Context current=context;return !closed.get()&&!state.removed&&attempts.get(state.peerId)==state&&current!=null&&sameSession(current,state.session)&&permitted(current,state.peerId);}
    private boolean aliveOutsideWorker(Attempt state){Context current=context;return !closed.get()&&!state.removed&&current!=null&&sameSession(current,state.session)&&permitted(current,state.peerId);}
    private static VoicePeer.Description description(JsonObject value,String expected)throws IOException {
        ProfileFiles.fields(value,Set.of("type","sdp"),Set.of());
        VoicePeer.Description description=new VoicePeer.Description(ProfileFiles.string(value,"type",16),ProfileFiles.string(value,"sdp",24000));
        if(!description.type().equals(expected))throw new IOException("Unexpected voice SDP type");return description;
    }
    private static UUID uuid(String value)throws IOException {try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException invalid){throw new IOException("Noncanonical voice UUID",invalid);}}
    private static String hash(byte[] bytes)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private void fail(Attempt state,String reason,boolean notify) {
        if(state.removed)return;
        if(notify&&alive(state))try{send(state,"close",new JsonObject(),ignored->{});}catch(IOException ignored){}
        state.removed=true;attempts.remove(state.peerId,state);
        if(retired.size()<1024)retired.put(state.peerId+":"+state.id,System.nanoTime()+RETIRED);
        else blockedUntil=System.nanoTime()+RETIRED;
        if(state.peer!=null){state.peer.close();listener.disconnected(state.peerId,state.peer,reason);}
        listener.status(state.peerId,"disconnected",reason);
    }
    private void execute(Runnable task,CompletableFuture<?> target) {
        if(closed.get()){if(target!=null)target.completeExceptionally(new IOException("Voice signaling closed"));return;}
        if(target!=null){pending.add(target);target.whenComplete((value,error)->pending.remove(target));}
        try{worker.execute(()->{if(closed.get()){if(target!=null)target.completeExceptionally(new IOException("Voice signaling closed"));return;}try{task.run();}catch(Throwable failure){if(target!=null)target.completeExceptionally(failure);}});}
        catch(RejectedExecutionException full){if(target!=null)target.completeExceptionally(new IOException("Voice control queue limit"));}
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;context=null;timer.shutdownNow();
        for(CompletableFuture<?> future:pending)future.completeExceptionally(new IOException("Voice signaling closed"));
        for(Attempt state:List.copyOf(attempts.values())){
            state.removed=true;VoicePeer peer=state.peer;if(peer!=null)peer.close();
        }
        attempts.clear();worker.shutdownNow();
    }
}
