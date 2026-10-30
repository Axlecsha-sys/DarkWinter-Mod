// Exchanges authenticated end-to-end session signals before native game admission.
package com.dark.winter.online.signaling;

import com.dark.winter.online.authority.WorldAuthority;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.iroh.IrohGameTransport;
import com.dark.winter.online.transport.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Authenticated E2E signaling only. Native game admission and the supplied transport remain owned by the caller. */
public final class PeerSignaling implements AutoCloseable {
    @FunctionalInterface public interface Outbound {CompletableFuture<JsonObject> send(String recipient,JsonObject sealed);}
    public interface Listener {
        CompletableFuture<Decision> decideJoin(IdentityProfile peer,UUID attempt,String invitationToken,JsonObject manifest);
        CompletableFuture<Void> hostConnected(Connected connected);
        CompletableFuture<Void> clientConnected(Connected connected);
        default void status(String peer,UUID attempt,String state,String message){}
    }
    public record Descriptor(UUID sessionId,long epoch,String islandId,JsonObject hostCompatibility,String compatDigest,JsonObject authority) {
        public Descriptor(UUID sessionId,long epoch,String islandId,JsonObject hostCompatibility,String compatDigest){
            this(sessionId,epoch,islandId,hostCompatibility,compatDigest,new JsonObject());
        }
        public Descriptor {
            Objects.requireNonNull(sessionId);Objects.requireNonNull(islandId);Objects.requireNonNull(compatDigest);
            if(epoch<0||!UUID.fromString(islandId).toString().equals(islandId)||!compatDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid signaling descriptor");
            hostCompatibility=Objects.requireNonNull(hostCompatibility).deepCopy();
            try{authority=PeerSignaling.authority(authority,islandId);}
            catch(IOException invalid){throw new IllegalArgumentException("Invalid signaling authority",invalid);}
        }
        @Override public JsonObject hostCompatibility(){return hostCompatibility.deepCopy();}
        @Override public JsonObject authority(){return authority.deepCopy();}
        @Override public String toString(){return "Descriptor[session="+sessionId+",epoch="+epoch+"]";}
    }
    public record Decision(boolean approved,Descriptor descriptor,String reason) {
        public Decision {if(approved&&descriptor==null)throw new IllegalArgumentException("Approved join requires a descriptor");reason=reason==null?"":reason;if(reason.length()>160)throw new IllegalArgumentException("Reason length");}
    }
    public record Connected(IdentityStore.SignedPayload peerProof,UUID attempt,Descriptor descriptor,WebRtcLink link,String invitationToken,boolean approved,ReliableTransportLink reliableLink) {
        /** Legacy WebRTC callers retain their exact link and constructor. link() is null for an Iroh connection. */
        public Connected(IdentityStore.SignedPayload peerProof,UUID attempt,Descriptor descriptor,WebRtcLink link,String invitationToken,boolean approved){
            this(peerProof,attempt,descriptor,link,invitationToken,approved,link);
        }
        public Connected {
            Objects.requireNonNull(peerProof);Objects.requireNonNull(attempt);Objects.requireNonNull(descriptor);Objects.requireNonNull(invitationToken);Objects.requireNonNull(reliableLink);
            if(link!=null&&link!=reliableLink||link==null&&reliableLink instanceof WebRtcLink)throw new IllegalArgumentException("A connected session must identify exactly one transport");
        }
        @Override public String toString(){return "Connected[peer="+peerProof.signer().id()+",attempt="+attempt+",approved="+approved+"]";}
    }
    private static final Gson JSON=new Gson();
    private static final SecureRandom NONCES=new SecureRandom();
    private static final int MAX_PENDING=16,MAX_CANDIDATES=64,MAX_BODY=32768,MAX_OUTBOUND=8,CANDIDATE_OUTBOUND=6;
    public record ProtocolState(String state,String code,int localCandidates,int acknowledgedCandidates,int remoteCandidates,int appliedCandidates,int inFlight,int peakInFlight,int candidateTransmissions,int deliveryFailures) {}
    private volatile List<ProtocolState> protocolSnapshot=List.of();
    private final Deque<ProtocolState> terminalProtocols=new ArrayDeque<>();
    public List<ProtocolState> protocolSnapshot(){return protocolSnapshot;}
    private static final long WAIT_NS=TimeUnit.MINUTES.toNanos(5),ROUTE_NS=TimeUnit.SECONDS.toNanos(30),RETRY_NS=TimeUnit.SECONDS.toNanos(2);
    private final Supplier<IdentityStore> identities;private final Outbound outbound;private final WebRtcTransport transport;private final Listener listener;private final Clock clock;private final IrohGameTransport gameTransport;
    private final ThreadPoolExecutor worker;private final ScheduledThreadPoolExecutor timer;
    private final AtomicBoolean closed=new AtomicBoolean();private final Map<String,Attempt> attempts=new HashMap<>();
    private final Map<String,Long> retired=new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> operations=ConcurrentHashMap.newKeySet();
    private long replayBlockedUntil;
    private static final class LocalCandidate {
        final WebRtcLink.Candidate value;long lastSent;boolean sending,acknowledged;
        LocalCandidate(WebRtcLink.Candidate value){this.value=value;}
    }
    private static final class Attempt {
        final UUID id;final String key,localId,peerId,token,requestHash;final JsonObject manifest;final boolean host;
        final CompletableFuture<Connected> result=new CompletableFuture<>();
        IdentityStore.SignedPayload peerProof,streamPeerProof;Descriptor descriptor;WebRtcLink link;WebRtcLink.Description description,remoteDescription;
        CompletableFuture<ReliableTransportLink> streamPending;ReliableTransportLink streamLink;
        String localStreamKey="",peerStreamKey="",streamNonce="";boolean streamMode;
        final Map<String,WebRtcLink.Candidate> remoteCandidates=new LinkedHashMap<>();
        final Set<String> deliveredCandidates=new HashSet<>();
        final Map<String,LocalCandidate> pendingCandidates=new LinkedHashMap<>();
        final Set<String> pendingCandidateAcks=new LinkedHashSet<>();
        String phase="joining",code="";int peakInFlight,candidateTransmissions,deliveryFailures,appliedCandidates,candidateOffset;
        long deadline=System.nanoTime()+WAIT_NS,lastRetry;int localCandidates,inFlight;boolean decisionPending,creating,negotiating,answerAccepted,finishing,finished,removed;
        Attempt(UUID id,String localId,IdentityStore.SignedPayload peerProof,String token,JsonObject manifest,boolean host,String requestHash) {
            this.id=id;this.localId=localId;this.peerId=peerProof.signer().id();this.peerProof=peerProof;this.token=token;this.manifest=manifest.deepCopy();this.host=host;this.requestHash=requestHash;this.key=peerId+":"+id;
        }
    }
    public PeerSignaling(Supplier<IdentityStore> identities,Outbound outbound,WebRtcTransport transport,Listener listener,Clock clock) {
        this(identities,outbound,transport,listener,clock,null);
    }
    public PeerSignaling(Supplier<IdentityStore> identities,Outbound outbound,WebRtcTransport transport,Listener listener,Clock clock,IrohGameTransport gameTransport) {
        this.identities=Objects.requireNonNull(identities);this.outbound=Objects.requireNonNull(outbound);this.transport=Objects.requireNonNull(transport);this.listener=Objects.requireNonNull(listener);this.clock=Objects.requireNonNull(clock);this.gameTransport=gameTransport;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(512),Thread.ofVirtual().name("DW-PeerSignaling-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        timer=new ScheduledThreadPoolExecutor(1,r->{Thread thread=new Thread(r,"DW-PeerSignaling-clock");thread.setDaemon(true);return thread;});
        timer.setRemoveOnCancelPolicy(true);timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.scheduleWithFixedDelay(()->execute(this::tick,null),250,250,TimeUnit.MILLISECONDS);
    }
    public CompletableFuture<Connected> connect(IdentityStore.SignedPayload hostProof,String invitationToken,JsonObject localCompatibility) {
        CompletableFuture<Connected> result=new CompletableFuture<>();
        execute(()->{
            try {
                IdentityProfile peer=IdentityStore.validateProfileProof(hostProof);IdentityStore identity=identity();String local=identity.profile().id();
                if(peer.id().equals(local))throw new IOException("Cannot signal this identity");
                String token=token(invitationToken);JsonObject manifest=manifest(localCompatibility);
                if(attempts.size()>=MAX_PENDING||System.nanoTime()<replayBlockedUntil)throw new IOException("Signaling peer or replay-window capacity reached");
                String streamKey=gameTransport==null?"":streamKey(gameTransport.publicKeyHex());
                UUID attempt=UUID.randomUUID();String digest=requestHash(local,peer.id(),attempt,token,manifest,streamKey);
                Attempt state=new Attempt(attempt,local,hostProof,token,manifest,false,digest);state.localStreamKey=streamKey;attempts.put(state.key,state);
                state.result.whenComplete((value,failure)->{if(failure==null)result.complete(value);else result.completeExceptionally(failure);});
                result.whenComplete((value,failure)->{if(result.isCancelled()){state.result.cancel(false);execute(()->{try{send(state,message(state,"cancel"));}catch(Exception ignored){}fail(state,"Connection canceled",false);},null);}});
                status(state,"joining","Запрошено подключение; подтверждение хоста ещё не получено.");
                try{sendJoin(state);}catch(IOException unavailable){fail(state,"Signaling delivery unavailable",false);}
            }catch(Exception failure){result.completeExceptionally(safe(failure));}
        },result);return result;
    }
    /** Receipt validates on the broker worker, never on JNI or render callbacks. */
    public CompletableFuture<Void> receive(String authenticatedFrom,JsonObject sealed) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        final JsonObject copy;
        try{if(sealed==null||ProfileFiles.bytes(sealed).length>65536)throw new IOException("Signaling envelope too large");copy=sealed.deepCopy();}
        catch(Exception invalid){return CompletableFuture.failedFuture(safe(invalid));}
        execute(()->{
            try {receiveInternal(authenticatedFrom,copy);result.complete(null);}
            catch(Exception failure){result.completeExceptionally(safe(failure));}
        },result);return result;
    }
    public void cancel(UUID attempt){Objects.requireNonNull(attempt);execute(()->{for(Attempt state:List.copyOf(attempts.values()))if(state.id.equals(attempt))cancelInternal(state,true);},null);}
    private IdentityStore identity()throws IOException {try{return Objects.requireNonNull(identities.get());}catch(RuntimeException failure){throw new IOException("Local identity is unavailable",failure);}}
    private void receiveInternal(String from,JsonObject sealed)throws Exception {
        id(from);IdentityStore identity=identity();if(from.equals(identity.profile().id()))throw new IOException("Cannot signal this identity");
        IdentityStore.SignedPayload envelope=JSON.fromJson(sealed,IdentityStore.SignedPayload.class);
        if(!IdentityStore.verify(envelope,IdentityStore.Purpose.SIGNAL)||!envelope.signer().id().equals(from))throw new IOException("Signaling sender mismatch");
        JsonObject body=ProfileFiles.parse(identity.openSealed(envelope,IdentityStore.Purpose.SIGNAL),MAX_BODY);
        String type=ProfileFiles.string(body,"type",16);
        Set<String> fields=new HashSet<>(Set.of("schema","type","attempt","from","to","issuedAt","expiresAt","profileProof"));
        switch(type){
            case "join"->fields.addAll(Set.of("token","manifest"));
            case "wait"->fields.addAll(Set.of("requestHash"));
            case "offer","answer"->fields.addAll(Set.of("requestHash","descriptor","description"));
            case "stream_offer"->fields.addAll(Set.of("requestHash","descriptor","streamKey","nonce"));
            case "candidate"->fields.addAll(Set.of("requestHash","descriptor","candidate"));
            case "candidate_ack"->fields.addAll(Set.of("requestHash","descriptor","candidateHash"));
            case "cancel"->fields.add("requestHash");
            case "reject"->fields.addAll(Set.of("requestHash","reason"));
            default->throw new IOException("Unknown signaling message");
        }
        ProfileFiles.fields(body,fields,type.equals("join")?Set.of("streamKey"):Set.of());
        long issued=ProfileFiles.number(body,"issuedAt"),expires=ProfileFiles.number(body,"expiresAt"),now=clock.millis();
        if(ProfileFiles.number(body,"schema")!=1||issued<0||issued>now+30000||expires<=now||expires<=issued||expires-issued>30000
                ||!ProfileFiles.string(body,"from",64).equals(from)||!ProfileFiles.string(body,"to",64).equals(identity.profile().id()))throw new IOException("Expired or foreign signaling message");
        UUID attempt=uuid(ProfileFiles.string(body,"attempt",36));String key=from+":"+attempt;
        IdentityStore.SignedPayload proof=JSON.fromJson(body.get("profileProof"),IdentityStore.SignedPayload.class);
        IdentityProfile peer=IdentityStore.validateProfileProof(proof);
        if(!peer.equals(envelope.signer()))throw new IOException("Signaling profile proof differs from envelope signer");
        Attempt state=attempts.get(key);
        if(type.equals("join")){
            String token=token(ProfileFiles.string(body,"token",16384));JsonObject manifest=manifest(body.getAsJsonObject("manifest"));
            String peerStreamKey=body.has("streamKey")?streamKey(ProfileFiles.string(body,"streamKey",64)):"";
            String digest=requestHash(from,identity.profile().id(),attempt,token,manifest,peerStreamKey);
            if(state!=null){
                validatePeer(state,proof);
                if(!state.host||!state.requestHash.equals(digest))throw new IOException("Join attempt was reused with different consent");
                if(state.streamMode)sendStreamOffer(state);else if(state.description!=null)sendDescription(state);else sendWait(state);return;
            }
            if(retired.containsKey(key)||System.nanoTime()<replayBlockedUntil)throw new IOException("Signaling attempt has ended or replay window is full");
            if(attempts.size()>=MAX_PENDING)throw new IOException("Signaling peer capacity reached");
            Attempt current=new Attempt(attempt,identity.profile().id(),proof,token,manifest,true,digest);current.peerStreamKey=peerStreamKey;current.decisionPending=true;attempts.put(key,current);
            try{sendWait(current);}catch(IOException unavailable){status(current,"delivery_pending","Подтверждение запроса ожидает служебной доставки.");}status(current,"approval_wait","Ожидается решение владельца; транспорт ещё не открыт.");
            CompletableFuture<Decision> decision;
            try{decision=Objects.requireNonNull(listener.decideJoin(peer,attempt,token,manifest.deepCopy()));}
            catch(Exception failure){fail(current,"Host approval failed",true);return;}
            decision.whenComplete((value,failure)->execute(()->decided(current,value,failure),current.result));return;
        }
        if(state==null)throw new IOException("Unknown signaling attempt");
        validatePeer(state,proof);
        if(!ProfileFiles.string(body,"requestHash",64).equals(state.requestHash))throw new IOException("Signaling attempt content mismatch");
        switch(type){
            case "wait"->{if(state.host)throw new IOException("Unexpected approval wait");if(state.remoteDescription==null&&!state.streamMode)status(state,"approval_wait","Хост подтвердил запрос; ожидается разрешение.");}
            case "reject"->{
                IslandJoinFailure reason=IslandJoinFailure.fromWire(ProfileFiles.string(body,"reason",160));
                if(reason!=null)fail(state,reason,false);else fail(state,"Peer rejected the connection",false);
            }
            case "cancel"->fail(state,"Peer canceled the connection",false);
            case "stream_offer"->receiveStreamOffer(state,body);
            case "offer"->{
                if(state.host||state.streamMode)throw new IOException("Only the designated WebRTC host may offer");
                Descriptor descriptor=descriptor(body.getAsJsonObject("descriptor"));compatible(state.manifest,descriptor.hostCompatibility());bind(state,descriptor);
                WebRtcLink.Description description=description(body.getAsJsonObject("description"),"offer");
                if(state.remoteDescription!=null){if(!state.remoteDescription.equals(description))throw new IOException("Offer changed within an attempt");if(state.description!=null)sendDescription(state);return;}
                state.remoteDescription=description;state.deadline=System.nanoTime()+ROUTE_NS;create(state);
            }
            case "answer"->{
                if(state.streamMode||!state.host||state.descriptor==null||state.link==null||state.description==null)throw new IOException("No WebRTC host offer awaits an answer");
                bind(state,descriptor(body.getAsJsonObject("descriptor")));WebRtcLink.Description answer=description(body.getAsJsonObject("description"),"answer");
                if(state.remoteDescription!=null){if(!state.remoteDescription.equals(answer))throw new IOException("Answer changed within an attempt");return;}
                state.remoteDescription=answer;state.answerAccepted=true;
                state.link.acceptAnswer(answer).whenComplete((value,failure)->execute(()->{if(alive(state)&&failure!=null)fail(state,"Authenticated answer could not be applied",true);},state.result));
            }
            case "candidate"->{
                if(state.streamMode)throw new IOException("ICE cannot substitute the approved stream transport");
                if(state.host&&state.descriptor==null)throw new IOException("Candidates cannot precede host approval");
                Descriptor descriptor=descriptor(body.getAsJsonObject("descriptor"));if(!state.host)compatible(state.manifest,descriptor.hostCompatibility());bind(state,descriptor);
                WebRtcLink.Candidate candidate=candidate(body.getAsJsonObject("candidate"));String digest=sha(ProfileFiles.bytes(JSON.toJsonTree(candidate)));
                if(!state.remoteCandidates.containsKey(digest)){if(state.remoteCandidates.size()>=MAX_CANDIDATES)throw new IOException("Signaling candidate limit");state.remoteCandidates.put(digest,candidate);applyCandidates(state);}
                // ACK means this authenticated peer retained the bounded candidate, not that a
                // mailbox queued it or that native ICE/Minecraft admission already succeeded.
                state.pendingCandidateAcks.add(digest);drainCandidates(state);publishProtocol();
            }
            case "candidate_ack"->{
                if(state.streamMode)throw new IOException("ICE receipts do not apply to stream transport");
                if(state.descriptor==null)throw new IOException("No descriptor awaits a candidate receipt");
                bind(state,descriptor(body.getAsJsonObject("descriptor")));
                String digest=ProfileFiles.string(body,"candidateHash",64);if(!digest.matches("[0-9a-f]{64}"))throw new IOException("Invalid candidate receipt");
                LocalCandidate candidate=state.pendingCandidates.get(digest);
                if(candidate==null)throw new IOException("Unknown candidate receipt");
                candidate.acknowledged=true;drainCandidates(state);publishProtocol();
            }
        }
    }
    private void validatePeer(Attempt state,IdentityStore.SignedPayload proof)throws IOException {
        IdentityProfile previous=state.peerProof.signer(),next=proof.signer();
        if(!previous.id().equals(next.id())||!previous.publicKey().equals(next.publicKey())||!previous.encryptionPublicKey().equals(next.encryptionPublicKey())
                ||next.revision()==previous.revision()&&!next.equals(previous))throw new IOException("Peer key or profile changed during signaling");
        if(state.streamMode&&!state.finished&&state.streamPeerProof!=null&&!next.equals(state.streamPeerProof.signer()))throw new IOException("Peer profile changed during stream authentication");
        if(next.revision()>=previous.revision())state.peerProof=proof;
        if(!identity().profile().id().equals(state.localId))throw new IOException("Local identity changed during signaling");
    }
    private void decided(Attempt state,Decision decision,Throwable failure) {
        if(!alive(state)||!state.decisionPending)return;
        if(System.nanoTime()>=state.deadline){fail(state,"Host approval timed out",true);return;}
        state.decisionPending=false;
        try{
            if(failure!=null||decision==null||!decision.approved()){
                IslandJoinFailure reason=decision==null?null:IslandJoinFailure.fromWire(decision.reason());
                if(reason!=null)fail(state,reason,true);else fail(state,"Host did not approve this join",true);return;
            }
            Descriptor descriptor=descriptor(JSON.toJsonTree(decision.descriptor()).getAsJsonObject());compatible(descriptor.hostCompatibility(),state.manifest);
            state.descriptor=descriptor;state.deadline=System.nanoTime()+ROUTE_NS;
            if(gameTransport!=null&&!state.peerStreamKey.isEmpty())createHostStream(state);else create(state);
        }catch(Exception invalid){
            IslandJoinFailure reason=IslandJoinFailure.find(invalid);
            if(reason!=null)fail(state,reason,true);else fail(state,"Host compatibility or session descriptor is invalid",true);
        }
    }
    private void bind(Attempt state,Descriptor descriptor)throws IOException {
        if(state.descriptor!=null&&!state.descriptor.equals(descriptor))throw new IOException("Session or authority epoch changed within signaling attempt");
        state.descriptor=descriptor;
    }
    private void create(Attempt state) {
        if(!alive(state)||state.streamMode||state.creating||state.link!=null)return;state.creating=true;
        final String linkId;try{linkId="signal_"+sha(state.key.getBytes(StandardCharsets.UTF_8));}catch(IOException impossible){fail(state,"Signaling identity hash is unavailable",false);return;}
        transport.createLink(linkId).whenComplete((link,failure)->{
            // Even a delayed JNI completion after broker shutdown must release its new native peer.
            if(link!=null)state.result.whenComplete((value,problem)->{if(problem!=null)link.close();});
            execute(()->{
            if(!alive(state)){if(link!=null)link.close();return;}
            if(failure!=null||link==null){fail(state,"Peer transport could not be created",true);return;}
            state.link=link;state.creating=false;state.negotiating=true;status(state,"negotiating","Проверенный запрос принят; устанавливается защищённый канал.");
            pumpCandidate(state);applyCandidates(state);
            link.closed().whenComplete((value,problem)->execute(()->{if(alive(state))fail(state,"Peer data channel closed",false);},null));
            link.opened().whenComplete((value,problem)->execute(()->opened(state,problem),state.result));
            CompletableFuture<WebRtcLink.Description> sdp=state.host?link.offer():link.answer(state.remoteDescription);
            sdp.whenComplete((description,problem)->execute(()->{
                if(!alive(state))return;if(problem!=null){fail(state,"Session description negotiation failed",true);return;}
                try{if(ProfileFiles.bytes(JSON.toJsonTree(description)).length>MAX_BODY-8192)throw new IOException("SDP exceeds signaling envelope budget");state.description=description;sendDescription(state);drainCandidates(state);}
                catch(Exception invalid){fail(state,"Session description exceeds the safe signaling limit",true);}
            },state.result));
        },state.result);
        });
    }
    private void createHostStream(Attempt state)throws IOException {
        if(!alive(state)||state.streamMode||state.creating||state.link!=null)throw new IOException("Stream transport already selected");
        state.localStreamKey=streamKey(gameTransport.publicKeyHex());
        if(state.localStreamKey.equals(state.peerStreamKey))throw new IOException("Stream peers must have distinct keys");
        byte[] nonce=new byte[32];NONCES.nextBytes(nonce);state.streamNonce=HexFormat.of().formatHex(nonce);
        state.streamMode=true;state.streamPeerProof=state.peerProof;state.creating=true;state.negotiating=true;
        CompletableFuture<ReliableTransportLink> pending=gameTransport.expect(state.streamPeerProof,state.peerStreamKey,
                state.id,state.descriptor,state.requestHash,state.streamNonce,streamDeadline(state));
        watchStream(state,pending);
        if(pending.isCompletedExceptionally()||pending.isCancelled()){fail(state,"Approved stream expectation could not be registered",true);return;}
        // The actual transport expectation is installed before a signed peer can learn this nonce.
        sendStreamOffer(state);
        status(state,"negotiating","Проверенный запрос принят; устанавливается защищённый поток.");
    }
    private void receiveStreamOffer(Attempt state,JsonObject body)throws IOException {
        if(state.host||gameTransport==null||state.localStreamKey.isEmpty())throw new IOException("No stream-capable join awaits an offer");
        String hostKey=streamKey(ProfileFiles.string(body,"streamKey",64)),nonce=streamKey(ProfileFiles.string(body,"nonce",64));
        if(!state.localStreamKey.equals(streamKey(gameTransport.publicKeyHex()))||hostKey.equals(state.localStreamKey))
            throw new IOException("Local or remote stream key changed");
        Descriptor descriptor=descriptor(body.getAsJsonObject("descriptor"));compatible(state.manifest,descriptor.hostCompatibility());
        if(state.streamMode){
            if(!hostKey.equals(state.peerStreamKey)||!nonce.equals(state.streamNonce))throw new IOException("Stream offer changed within an attempt");
            bind(state,descriptor);return;
        }
        if(state.link!=null||state.creating||state.description!=null||state.remoteDescription!=null
                ||!state.remoteCandidates.isEmpty()||!state.pendingCandidates.isEmpty())
            throw new IOException("A stream offer cannot replace WebRTC negotiation");
        bind(state,descriptor);
        state.peerStreamKey=hostKey;state.streamNonce=nonce;state.streamMode=true;state.streamPeerProof=state.peerProof;
        state.deadline=System.nanoTime()+ROUTE_NS;state.creating=true;state.negotiating=true;
        CompletableFuture<ReliableTransportLink> pending=gameTransport.connect(state.streamPeerProof,hostKey,state.id,
                state.descriptor,state.requestHash,nonce,streamDeadline(state));
        watchStream(state,pending);
        status(state,"negotiating","Подпись ведущего проверена; устанавливается защищённый поток.");
    }
    private void watchStream(Attempt state,CompletableFuture<ReliableTransportLink> pending) {
        state.streamPending=Objects.requireNonNull(pending);
        pending.whenComplete((link,failure)->{
            if(link!=null)state.result.whenComplete((connected,error)->{if(error!=null)link.close();});
            execute(()->{
                if(!alive(state)||state.streamPending!=pending){if(link!=null)link.close();return;}
                state.streamPending=null;
                if(failure!=null||link==null){fail(state,"Authenticated stream transport did not connect",true);return;}
                state.streamLink=link;state.creating=false;
                link.closed().whenComplete((value,error)->execute(()->{if(alive(state))fail(state,"Peer stream channel closed",false);},null));
                link.opened().whenComplete((value,error)->execute(()->opened(state,error),state.result));
            },state.result,()->{if(link!=null)link.close();});
        });
    }
    private static Duration streamDeadline(Attempt state)throws IOException {
        long remaining=state.deadline-System.nanoTime();
        if(remaining<TimeUnit.MILLISECONDS.toNanos(1))throw new IOException("Stream route deadline expired");
        return Duration.ofNanos(Math.min(remaining,ROUTE_NS));
    }
    private void sendStreamOffer(Attempt state)throws IOException {
        if(!state.host||!state.streamMode||state.descriptor==null||state.streamNonce.isEmpty()
                ||state.streamPending==null&&state.streamLink==null)throw new IOException("No approved stream offer exists");
        JsonObject body=message(state,"stream_offer");body.add("descriptor",JSON.toJsonTree(state.descriptor));
        body.addProperty("streamKey",state.localStreamKey);body.addProperty("nonce",state.streamNonce);
        if(send(state,body))state.lastRetry=System.nanoTime();
    }
    private void closeTransport(Attempt state) {
        CompletableFuture<ReliableTransportLink> pending=state.streamPending;state.streamPending=null;
        if(pending!=null)pending.cancel(true);
        if(state.streamLink!=null)state.streamLink.close();
        if(state.link!=null)state.link.close();
    }
    private void pumpCandidate(Attempt state) {
        if(!alive(state)||state.streamMode||state.link==null)return;
        state.link.nextLocalCandidate().whenComplete((candidate,failure)->execute(()->{
            if(!alive(state))return;if(failure!=null)return;
            try{queueCandidate(state,candidate);}
            catch(Exception invalid){fail(state,"Candidate could not be authenticated",true);return;}
            pumpCandidate(state);
        },state.result));
    }
    private void queueCandidate(Attempt state,WebRtcLink.Candidate candidate)throws IOException{
        String digest=sha(ProfileFiles.bytes(JSON.toJsonTree(candidate)));
        if(state.pendingCandidates.containsKey(digest))return;
        if(state.pendingCandidates.size()>=MAX_CANDIDATES){fail(state,"Local candidate limit reached",true);return;}
        state.pendingCandidates.put(digest,new LocalCandidate(candidate));state.localCandidates=state.pendingCandidates.size();
        drainCandidates(state);publishProtocol();
    }
    private void drainCandidates(Attempt state){
        if(!alive(state)||state.streamMode||state.descriptor==null||!state.finished&&System.nanoTime()>=state.deadline)return;
        try{
            while(!state.pendingCandidateAcks.isEmpty()&&state.inFlight<CANDIDATE_OUTBOUND){
                String digest=state.pendingCandidateAcks.iterator().next();
                JsonObject body=message(state,"candidate_ack");body.add("descriptor",JSON.toJsonTree(state.descriptor));body.addProperty("candidateHash",digest);
                if(!send(state,body))return;state.pendingCandidateAcks.remove(digest);
            }
            if(state.finished||state.description==null)return;
            long now=System.nanoTime();
            var candidates=new ArrayList<>(state.pendingCandidates.values());int size=candidates.size(),start=size==0?0:state.candidateOffset%size;
            for(int offset=0;offset<size;offset++){
                if(state.inFlight>=CANDIDATE_OUTBOUND)return;
                int index=(start+offset)%size;LocalCandidate candidate=candidates.get(index);
                if(candidate.acknowledged||candidate.sending||candidate.lastSent!=0&&now-candidate.lastSent<RETRY_NS)continue;
                JsonObject body=message(state,"candidate");body.add("descriptor",JSON.toJsonTree(state.descriptor));body.add("candidate",JSON.toJsonTree(candidate.value));
                if(!send(state,body,()->candidate.sending=false))return;
                candidate.sending=true;candidate.lastSent=now;state.candidateTransmissions++;state.candidateOffset=(index+1)%size;
            }
        }catch(IOException invalid){fail(state,"Candidate could not be authenticated",true);}
    }
    private void applyCandidates(Attempt state) {
        if(state.link==null)return;
        for(var entry:state.remoteCandidates.entrySet())if(state.deliveredCandidates.add(entry.getKey()))
            state.link.addRemoteCandidate(entry.getValue()).whenComplete((value,failure)->execute(()->{if(!alive(state))return;if(failure!=null)fail(state,"Authenticated candidate could not be applied",true);else{state.appliedCandidates++;publishProtocol();}},state.result));
    }
    private void opened(Attempt state,Throwable problem) {
        if(!alive(state)||state.finishing||state.finished)return;
        if(problem!=null||System.nanoTime()>=state.deadline){fail(state,"Peer data channel did not open within its route deadline",true);return;}
        state.finishing=true;
        ReliableTransportLink reliable=state.streamMode?state.streamLink:state.link;
        if(reliable==null){fail(state,"No authenticated transport is available",true);return;}
        Connected connected=new Connected(state.streamMode?state.streamPeerProof:state.peerProof,state.id,state.descriptor,state.link,state.token,true,reliable);
        CompletableFuture<Void> attached;
        try{attached=Objects.requireNonNull(state.host?listener.hostConnected(connected):listener.clientConnected(connected));}
        catch(Exception failure){fail(state,"Native session bridge did not accept the channel",true);return;}
        attached.whenComplete((value,failure)->execute(()->{
            if(!alive(state))return;
            if(failure!=null){fail(state,"Native session bridge did not accept the channel",true);return;}
            state.finished=true;state.deadline=Long.MAX_VALUE;state.result.complete(connected);
            status(state,"datalink_ready","Защищённый канал и локальный мост готовы; вход в Minecraft проверяется отдельно.");
        },state.result));
    }
    private JsonObject message(Attempt state,String type)throws IOException {
        IdentityStore identity=identity();if(!identity.profile().id().equals(state.localId))throw new IOException("Local identity changed");
        long now=clock.millis();if(now<0||now>Long.MAX_VALUE-30000)throw new IOException("Invalid signaling clock");
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type",type);body.addProperty("attempt",state.id.toString());
        body.addProperty("from",state.localId);body.addProperty("to",state.peerId);body.addProperty("issuedAt",now);body.addProperty("expiresAt",now+30000);
        body.add("profileProof",JSON.toJsonTree(identity.publicProof()));if(!type.equals("join"))body.addProperty("requestHash",state.requestHash);return body;
    }
    private void sendJoin(Attempt state)throws IOException {JsonObject body=message(state,"join");body.addProperty("token",state.token);body.add("manifest",state.manifest.deepCopy());if(!state.localStreamKey.isEmpty())body.addProperty("streamKey",state.localStreamKey);if(send(state,body))state.lastRetry=System.nanoTime();}
    private void sendWait(Attempt state)throws IOException {if(send(state,message(state,"wait")))state.lastRetry=System.nanoTime();}
    private void sendDescription(Attempt state)throws IOException {
        JsonObject body=message(state,state.host?"offer":"answer");body.add("descriptor",JSON.toJsonTree(state.descriptor));body.add("description",JSON.toJsonTree(state.description));if(send(state,body))state.lastRetry=System.nanoTime();
    }
    private boolean send(Attempt state,JsonObject body)throws IOException {return send(state,body,()->{});}
    private boolean send(Attempt state,JsonObject body,Runnable completed)throws IOException {
        // Backpressure is temporary. Two slots remain available for OFFER/ANSWER/WAIT.
        if(state.inFlight>=MAX_OUTBOUND)return false;
        byte[] plaintext=ProfileFiles.bytes(body);if(plaintext.length>MAX_BODY)throw new IOException("Signaling body exceeds 32 KiB");
        IdentityStore.SignedPayload sealed=identity().seal(state.peerProof,IdentityStore.Purpose.SIGNAL,plaintext);
        state.inFlight++;state.peakInFlight=Math.max(state.peakInFlight,state.inFlight);CompletableFuture<JsonObject> delivery;
        try{delivery=Objects.requireNonNull(outbound.send(state.peerId,JSON.toJsonTree(sealed).getAsJsonObject()));}
        catch(Exception failure){state.inFlight--;state.deliveryFailures++;publishProtocol();return false;}
        delivery.copy().orTimeout(15,TimeUnit.SECONDS).whenComplete((reply,failure)->execute(()->{
            state.inFlight--;completed.run();
            if(alive(state)){
                if(failure!=null){state.deliveryFailures++;if(!state.finished)status(state,"delivery_pending","Служебная доставка не подтверждена; ожидание ограничено таймером.");}
                drainCandidates(state);publishProtocol();
            }
        },null));publishProtocol();return true;
    }
    private void tick() {
        long now=System.nanoTime();retired.entrySet().removeIf(entry->entry.getValue()<=now);
        for(Attempt state:List.copyOf(attempts.values())){
            if(state.result.isCompletedExceptionally()){fail(state,"Signaling operation canceled or capacity exceeded",false);continue;}
            if(!state.finished&&now>=state.deadline){fail(state,state.decisionPending?"Host approval timed out":"Peer signaling timed out",true);continue;}
            drainCandidates(state);
            if(state.finished)continue;
            if(now-state.lastRetry<RETRY_NS||state.inFlight>=MAX_OUTBOUND)continue;
            try{
                if(state.host){if(state.streamMode)sendStreamOffer(state);else if(state.description!=null&&!state.answerAccepted)sendDescription(state);else if(state.decisionPending)sendWait(state);}
                else if(!state.streamMode){if(state.remoteDescription==null)sendJoin(state);else if(state.description!=null)sendDescription(state);}
            }catch(Exception failure){status(state,"delivery_pending","Повтор служебного сообщения ожидает доступного канала.");}
        }
    }
    private boolean alive(Attempt state){return !closed.get()&&!state.removed&&!state.result.isCompletedExceptionally()&&attempts.get(state.key)==state;}
    private void cancelInternal(Attempt state,boolean notify){if(!alive(state))return;if(notify)try{send(state,message(state,"cancel"));}catch(Exception ignored){}fail(state,"Connection canceled",false);}
    private void fail(Attempt state,IslandJoinFailure failure,boolean notify){fail(state,failure.kind().wireCode(),notify,failure);}
    private void fail(Attempt state,String message,boolean notify){fail(state,message,notify,new IOException(message));}
    private void fail(Attempt state,String message,boolean notify,IOException failure) {
        if(state.removed)return;
        if(notify&&alive(state))try{JsonObject rejected=message(state,"reject");rejected.addProperty("reason",message);send(state,rejected);}catch(Exception ignored){}
        state.removed=true;attempts.remove(state.key,state);
        long until=System.nanoTime()+WAIT_NS;
        if(retired.size()>=1024)replayBlockedUntil=Math.max(replayBlockedUntil,until);else retired.put(state.key,until);
        closeTransport(state);state.result.completeExceptionally(failure);state.phase="failed";state.code=message.replaceAll("[^A-Za-z0-9]+","_").toUpperCase(Locale.ROOT);
        if(terminalProtocols.size()>=16)terminalProtocols.removeFirst();terminalProtocols.addLast(protocol(state));status(state,"failed",message);
    }
    private ProtocolState protocol(Attempt state){
        int acknowledged=(int)state.pendingCandidates.values().stream().filter(candidate->candidate.acknowledged).count();
        return new ProtocolState(state.phase,state.code,state.localCandidates,acknowledged,state.remoteCandidates.size(),state.appliedCandidates,state.inFlight,state.peakInFlight,state.candidateTransmissions,state.deliveryFailures);
    }
    private void publishProtocol(){
        List<ProtocolState> snapshot=new ArrayList<>(terminalProtocols);for(Attempt state:attempts.values())snapshot.add(protocol(state));protocolSnapshot=List.copyOf(snapshot);
    }
    private void status(Attempt state,String phase,String message){state.phase=phase;publishProtocol();try{listener.status(state.peerId,state.id,phase,message);}catch(RuntimeException ignored){}}
    private void execute(Runnable action,CompletableFuture<?> failureTarget){execute(action,failureTarget,()->{});}
    private void execute(Runnable action,CompletableFuture<?> failureTarget,Runnable rejected) {
        if(failureTarget!=null&&operations.add(failureTarget))failureTarget.whenComplete((value,failure)->operations.remove(failureTarget));
        if(closed.get()){if(failureTarget!=null)failureTarget.completeExceptionally(new IOException("Signaling broker is closed"));rejected.run();return;}
        try{worker.execute(()->{if(closed.get()){if(failureTarget!=null)failureTarget.completeExceptionally(new IOException("Signaling broker is closed"));rejected.run();return;}try{action.run();}catch(Throwable failure){if(failureTarget!=null)failureTarget.completeExceptionally(safe(failure));}});}
        catch(RejectedExecutionException failure){if(failureTarget!=null)failureTarget.completeExceptionally(new IOException("Signaling worker capacity reached"));rejected.run();}
    }
    private static JsonObject manifest(JsonObject input)throws IOException {
        if(input==null||ProfileFiles.bytes(input).length>8192)throw new IOException("Invalid compatibility manifest");
        ProfileFiles.number(input,"schema");ProfileFiles.number(input,"protocol");ProfileFiles.number(input,"dataContract");
        ProfileFiles.string(input,"minecraft",64);String hash=ProfileFiles.string(input,"definitionsSha256",64);if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid compatibility digest");return input.deepCopy();
    }
    private static void compatible(JsonObject local,JsonObject peer)throws IOException {
        manifest(local);manifest(peer);
        for(String field:List.of("schema","protocol","dataContract"))if(ProfileFiles.number(local,field)!=ProfileFiles.number(peer,field))throw new IOException("Incompatible protocol or data contract");
        for(String field:List.of("minecraft","definitionsSha256"))if(!ProfileFiles.string(local,field,64).equals(ProfileFiles.string(peer,field,64)))throw new IOException("Incompatible game definitions");
    }
    /** Public owner proofs only; live lease, external pin and host eligibility remain caller checks. */
    private static JsonObject authority(JsonObject input,String islandId)throws IOException {
        try{
            if(input==null||ProfileFiles.bytes(input).length>65536)throw new IOException("Invalid signaling authority size");
            if(input.size()==0)return new JsonObject();
            ProfileFiles.fields(input,Set.of("certificate","policy"),Set.of());
            if(!input.get("certificate").isJsonObject()||!input.get("policy").isJsonObject())
                throw new IOException("Missing signaling authority proofs");
            WorldAuthority.Certificate certificate=WorldAuthority.certificateFromJson(input.getAsJsonObject("certificate"));
            WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(input.getAsJsonObject("policy")));
            if(!WorldAuthority.verify(certificate).islandId().toString().equals(islandId))
                throw new IOException("Signaling authority belongs to another island");
            return input.deepCopy();
        }catch(RuntimeException invalid){throw new IOException("Invalid signaling authority proofs",invalid);}
    }
    private static Descriptor descriptor(JsonObject input)throws IOException {
        try{
            ProfileFiles.fields(input,Set.of("sessionId","epoch","islandId","hostCompatibility","compatDigest"),Set.of("authority"));
            Descriptor descriptor=new Descriptor(uuid(ProfileFiles.string(input,"sessionId",36)),ProfileFiles.number(input,"epoch"),ProfileFiles.string(input,"islandId",36),manifest(input.getAsJsonObject("hostCompatibility")),ProfileFiles.string(input,"compatDigest",64),input.has("authority")?input.getAsJsonObject("authority"):new JsonObject());
            if(!descriptor.compatDigest().equals(sha(ProfileFiles.bytes(descriptor.hostCompatibility()))))throw new IOException("Descriptor compatibility digest mismatch");return descriptor;
        }catch(RuntimeException invalid){throw new IOException("Invalid session descriptor",invalid);}
    }
    private static WebRtcLink.Description description(JsonObject input,String expected)throws IOException {
        try{ProfileFiles.fields(input,Set.of("type","sdp"),Set.of());WebRtcLink.Description value=new WebRtcLink.Description(ProfileFiles.string(input,"type",16),ProfileFiles.string(input,"sdp",MAX_BODY));if(!value.type().equals(expected))throw new IOException("Unexpected SDP direction");return value;}
        catch(RuntimeException invalid){throw new IOException("Invalid authenticated SDP",invalid);}
    }
    private static WebRtcLink.Candidate candidate(JsonObject input)throws IOException {
        try{ProfileFiles.fields(input,Set.of("sdpMid","sdpMLineIndex","sdp"),Set.of());return new WebRtcLink.Candidate(ProfileFiles.string(input,"sdpMid",256),Math.toIntExact(ProfileFiles.number(input,"sdpMLineIndex")),ProfileFiles.string(input,"sdp",8192));}
        catch(RuntimeException invalid){throw new IOException("Invalid authenticated ICE candidate",invalid);}
    }
    private static String requestHash(String from,String to,UUID attempt,String token,JsonObject manifest)throws IOException {
        return requestHash(from,to,attempt,token,manifest,"");
    }
    private static String requestHash(String from,String to,UUID attempt,String token,JsonObject manifest,String streamKey)throws IOException {
        JsonObject body=new JsonObject();body.addProperty("from",from);body.addProperty("to",to);body.addProperty("attempt",attempt.toString());body.addProperty("token",token);body.add("manifest",manifest);
        if(!streamKey.isEmpty())body.addProperty("streamKey",streamKey(streamKey));return sha(ProfileFiles.bytes(body));
    }
    private static String streamKey(String value)throws IOException {
        if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("Invalid stream transport key");return value;
    }
    private static String sha(byte[] value)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private static String token(String value)throws IOException {if(value==null||value.length()>16384||value.indexOf('\0')>=0)throw new IOException("Invalid invitation capability");return value;}
    private static UUID uuid(String value)throws IOException {try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IOException("Invalid attempt UUID");return result;}catch(RuntimeException invalid){throw new IOException("Invalid attempt UUID",invalid);}}
    private static void id(String value)throws IOException {if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid peer identity");}
    private static IOException safe(Throwable failure){return failure instanceof IOException io?io:new IOException("Signaling operation failed",failure);}
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;timer.shutdownNow();
        worker.getQueue().clear();
        try{worker.execute(()->{for(Attempt state:List.copyOf(attempts.values())){state.removed=true;closeTransport(state);state.result.completeExceptionally(new IOException("Signaling broker closed"));}attempts.clear();retired.clear();terminalProtocols.clear();protocolSnapshot=List.of();for(CompletableFuture<?> operation:List.copyOf(operations))operation.completeExceptionally(new IOException("Signaling broker closed"));operations.clear();});}
        catch(RejectedExecutionException ignored){}finally{worker.shutdown();}
    }
}
