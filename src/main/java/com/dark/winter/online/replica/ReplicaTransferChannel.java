// Explicit immutable checkpoint transfer on one authenticated Mux replica lane.
package com.dark.winter.online.replica;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.dark.winter.online.transport.WebRtcByteStream;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Explicit immutable checkpoint transfer on one authenticated Mux replica lane. No native world is opened.
 * Caller supplies current Node-confirmed policy/fences, transport identity binding and fresh native ACL admission.
 * READY means Store.finish plus a verified receiver signature, never merely send completion.
 * Completed verified CAS objects survive cancellation; an incomplete .part restarts on reconnect.
 */
public final class ReplicaTransferChannel {
    private static final int MAGIC=0x44575443,VERSION=1,HEADER=12,FRAGMENT=8192;
    private static final int CHALLENGE=1,AUTH=2,OFFER=3,REQUEST=4,CHUNK=5,READY=6;
    private static final int SIGNED_LIMIT=16384,INDEX_LIMIT=4*1024*1024,MAX_FRAME=INDEX_LIMIT+SIGNED_LIMIT+8;
    private static final String HELLO_DOMAIN="DarkWinter.ReplicaTransfer.hello.v1",AUTH_DOMAIN="DarkWinter.ReplicaTransfer.auth.v1";
    private static final Gson JSON=new Gson();
    private static final Semaphore RECEIVER_IO=new Semaphore(2),PUBLISHER_IO=new Semaphore(2);
    private ReplicaTransferChannel(){}
    @FunctionalInterface public interface FenceSource {LeaseFence.Fence current()throws IOException;}
    @FunctionalInterface public interface PolicySource {WorldAuthority.Policy current()throws IOException;}
    public record Options(Duration timeout,ReplicaProtocol.Limits limits,ReplicaProtocol.Compatibility compatibility) {
        public Options {
            Objects.requireNonNull(timeout);Objects.requireNonNull(limits);Objects.requireNonNull(compatibility);
            if(timeout.compareTo(Duration.ofSeconds(1))<0||timeout.compareTo(Duration.ofSeconds(60))>0||limits.indexBytes()>INDEX_LIMIT)
                throw new IllegalArgumentException("Replica transfer limits");
        }
    }
    public record Receipt(ReplicaProtocol.Header header,ReplicaProtocol.Index index,SignedPayload receiverAck){}
    public record Stats(boolean authenticated,boolean closed,String code,long wireSent,long wireReceived,long chunkBytes,
                        int peakOutstanding,int readyCount,int queuedPublications,int waitingReady){}
    public static Publisher publisher(WebRtcByteStream lane,IdentityStore sharedIdentity,SignedPayload receiverProfile,
                                      WorldAuthority.Certificate certificate,PolicySource policy,FenceSource currentHost,Options options)throws IOException {
        try{Publisher result=new Publisher(lane,sharedIdentity,receiverProfile,certificate,policy,currentHost,options);result.start();return result;}
        catch(IOException|RuntimeException|Error failed){lane.close();throw failed;}
    }
    public static Subscriber subscriber(WebRtcByteStream lane,IdentityStore sharedIdentity,SignedPayload hostProfile,
                                       WorldAuthority.Certificate certificate,PolicySource policy,FenceSource independentlyObservedHost,
                                       ReplicaStore ownStore,ReplicaStore.Ready base,Options options)throws IOException {
        if(!RECEIVER_IO.tryAcquire()){lane.close();throw new IOException("Replica subscriber IO capacity");}
        try {Subscriber result=new Subscriber(lane,sharedIdentity,hostProfile,certificate,policy,independentlyObservedHost,ownStore,base,options);result.start();return result;}
        catch(IOException|RuntimeException|Error failed){RECEIVER_IO.release();lane.close();throw failed;}
    }
    private record Frame(int type,byte[] bytes){}
    public abstract static class Endpoint implements AutoCloseable {
        final WebRtcByteStream lane;final IdentityStore identity;final IdentityProfile peer;final String localId;
        final WorldAuthority.Certificate certificate;final WorldAuthority.Genesis genesis;final PolicySource policies;final FenceSource fences;final Options options;
        final CompletableFuture<Void> opened=new CompletableFuture<>(),ended=new CompletableFuture<>();
        final Set<CompletableFuture<?>> waits=ConcurrentHashMap.newKeySet();final AtomicBoolean stopped=new AtomicBoolean();
        final Thread worker;final Raw raw=new Raw();
        volatile String code="";volatile boolean authenticated;volatile long sent,received,chunkBytes,transferDeadline;volatile int peakOutstanding,readyCount;
        ReplicaProtocol.VerifiedOffer transferring;
        Endpoint(WebRtcByteStream lane,IdentityStore shared,SignedPayload peerProfile,WorldAuthority.Certificate certificate,PolicySource policies,FenceSource fences,Options options)throws IOException {
            this.lane=Objects.requireNonNull(lane);identity=Objects.requireNonNull(shared);localId=identity.profile().id();
            peer=IdentityStore.validateProfileProof(peerProfile);this.certificate=Objects.requireNonNull(certificate);genesis=WorldAuthority.verify(certificate);
            this.policies=Objects.requireNonNull(policies);this.fences=Objects.requireNonNull(fences);this.options=Objects.requireNonNull(options);
            worker=Thread.ofVirtual().name("DarkWinter-replica-transfer").unstarted(this::run);
        }
        final void start(){lane.closed().whenComplete((v,f)->close());try{worker.start();}catch(RuntimeException|Error failure){stop("WORKER_START");throw failure;}}
        public final CompletableFuture<Void> opened(){return opened.copy();}
        public final CompletableFuture<Void> closed(){return ended.copy();}
        public abstract Stats stats();
        abstract boolean publisherSide();
        abstract void exchange()throws Exception;
        abstract void failPending(IOException failure);
        void cleanup()throws IOException{}
        final void run(){
            try{await(lane.opened());exchange();}
            catch(Throwable failure){stop(failure instanceof TransferFailure typed?typed.code:failure instanceof InterruptedException?"CANCELED":"TRANSFER_FAILED");}
            finally {
                try{cleanup();}catch(IOException ignored){code="CACHE_CLEANUP_FAILED";}
                ended.complete(null);
            }
        }
        final LeaseFence.Fence authority()throws IOException {
            if(stopped.get()||Thread.currentThread().isInterrupted())throw error("CANCELED");
            if(transferDeadline!=0&&System.nanoTime()-transferDeadline>=0)throw error("TRANSFER_TIMEOUT");
            if(!identity.profile().id().equals(localId))throw error("LOCAL_PROFILE_CHANGED");
            LeaseFence.Fence fence=Objects.requireNonNull(fences.current(),"No current Node fence");ReplicaProtocol.fresh(certificate,fence);
            String holder=publisherSide()?localId:peer.id(),receiver=publisherSide()?peer.id():localId;
            if(!holder.equals(fence.holderId()))throw error("HOST_IDENTITY_CHANGED");
            WorldAuthority.Policy policy=Objects.requireNonNull(policies.current(),"No Node-confirmed policy");
            WorldAuthority.requirePinned(policy.certificate(),certificate.authorityId(),genesis.ownerId());
            if(policy.revision()!=fence.policyRevision()||!policy.sha256().equals(fence.policySha256()))throw error("POLICY_NOT_NODE_CONFIRMED");
            WorldAuthority.authorizeHost(policy,holder,System.currentTimeMillis());WorldAuthority.authorizeHost(policy,receiver,System.currentTimeMillis());
            return fence;
        }
        final LeaseFence.Fence current(ReplicaProtocol.VerifiedOffer offer)throws IOException {
            LeaseFence.Fence fence=authority();ReplicaProtocol.sameFence(offer.header(),fence);
            if(offer.header().sequence()!=fence.checkpointSequence()||!offer.index().contentRoot().equals(fence.checkpointSha256()))
                throw error("NODE_CHECKPOINT_CHANGED");return fence;
        }
        final <T>T await(CompletableFuture<T> value)throws Exception {
            if(stopped.get()){value.cancel(true);throw error("CANCELED");}
            waits.add(value);try {if(stopped.get()){value.cancel(true);throw error("CANCELED");}long timeout=options.timeout().toNanos();if(transferDeadline!=0)timeout=Math.min(timeout,Math.max(1,transferDeadline-System.nanoTime()));return value.get(timeout,TimeUnit.NANOSECONDS);}
            finally{waits.remove(value);}
        }
        final void send(int type,byte[] data)throws Exception {
            if(data.length>limit(type))throw error("FRAME_LIMIT");
            byte[] header=ByteBuffer.allocate(HEADER).putInt(MAGIC).putShort((short)VERSION).putShort((short)type).putInt(data.length).array();
            write(header);write(data);
        }
        private void write(byte[] bytes)throws Exception {
            for(int at=0;at<bytes.length;){if(transferring!=null)current(transferring);else authority();int count=Math.min(FRAGMENT,bytes.length-at);
                await(lane.send(Arrays.copyOfRange(bytes,at,at+count)));sent+=count;at+=count;}
        }
        final Frame read()throws Exception {
            ByteBuffer header=ByteBuffer.wrap(raw.exact(HEADER));int magic=header.getInt(),version=Short.toUnsignedInt(header.getShort()),type=Short.toUnsignedInt(header.getShort()),size=header.getInt();
            if(magic!=MAGIC||version!=VERSION||size<0||size>limit(type))throw error("INVALID_FRAME");
            if(transferring!=null)current(transferring);else if(authenticated)authority();
            return new Frame(type,raw.exact(size));
        }
        private final class Raw {
            byte[] segment=new byte[0];int at;
            byte[] exact(int length)throws Exception {
                byte[] result=new byte[length];int offset=0;
                while(offset<length){
                    if(at==segment.length){segment=await(lane.read());at=0;if(segment==null||segment.length<1||segment.length>65536)throw error("STREAM_EOF_OR_SEGMENT");received+=segment.length;}
                    int count=Math.min(length-offset,segment.length-at);System.arraycopy(segment,at,result,offset,count);offset+=count;at+=count;
                }return result;
            }
        }
        final void verifySigned(SignedPayload signed,String expected)throws IOException {
            if(!IdentityStore.verify(signed,IdentityStore.Purpose.SIGNAL)||!signed.signer().id().equals(expected)
                    ||!signed.signer().publicKey().equals(peer.publicKey()))throw error("PEER_SIGNATURE");
        }
        final JsonObject signedBody(SignedPayload signed,String expected)throws IOException {
            verifySigned(signed,expected);byte[] bytes=IdentityStore.decodeData(signed);JsonObject value=ProfileFiles.parse(bytes,SIGNED_LIMIT);
            if(!Arrays.equals(bytes,ProfileFiles.bytes(value)))throw error("NONCANONICAL_AUTH");return value;
        }
        final void stop(String reason){
            if(!stopped.compareAndSet(false,true))return;code=reason;IOException failure=error(reason);
            opened.completeExceptionally(failure);failPending(failure);for(CompletableFuture<?> value:List.copyOf(waits))value.cancel(true);
            lane.close();worker.interrupt();
        }
        @Override public final void close(){stop("APPLICATION_CLOSED");}
    }
    private static final class Publication {
        final ReplicaPublisher.Prepared source;final long sequence,baseSequence;final ReplicaProtocol.Index base;
        final CompletableFuture<Receipt> result=new CompletableFuture<>();
        Publication(ReplicaPublisher.Prepared source,long sequence,ReplicaProtocol.Index base,long baseSequence){this.source=source;this.sequence=sequence;this.base=base;this.baseSequence=baseSequence;}
    }
    public static final class Publisher extends Endpoint {
        private final ArrayBlockingQueue<Publication> queue=new ArrayBlockingQueue<>(1);private Publication active;
        Publisher(WebRtcByteStream lane,IdentityStore identity,SignedPayload receiver,WorldAuthority.Certificate certificate,PolicySource policy,FenceSource fence,Options options)throws IOException{
            super(lane,identity,receiver,certificate,policy,fence,options);authority();
        }
        @Override boolean publisherSide(){return true;}
        /** Explicit checkpoint only; the caller keeps the prepared immutable sources alive until the receipt settles. */
        public synchronized CompletableFuture<Receipt> publish(ReplicaPublisher.Prepared source,long sequence,ReplicaProtocol.Index base,long baseSequence){
            if(stopped.get())return CompletableFuture.failedFuture(error("CLOSED"));
            if(active!=null)return CompletableFuture.failedFuture(error("PUBLICATION_CAPACITY"));
            if(source==null||sequence<0||base==null&&baseSequence!=-1||base!=null&&(baseSequence<0||baseSequence>=sequence))
                return CompletableFuture.failedFuture(error("PUBLICATION_ARGUMENTS"));
            Publication publication=new Publication(source,sequence,base,baseSequence);active=publication;
            publication.result.whenComplete((v,f)->{if(publication.result.isCancelled())close();});
            queue.add(publication);return publication.result;
        }
        public CompletableFuture<Receipt> publish(ReplicaPublisher.Prepared source,long sequence){return publish(source,sequence,null,-1);}
        @Override public synchronized Stats stats(){return new Stats(authenticated,stopped.get(),code,sent,received,chunkBytes,peakOutstanding,readyCount,active==null?0:1,0);}
        @Override synchronized void failPending(IOException failure){Publication previous=active;active=null;queue.clear();if(previous!=null)previous.result.completeExceptionally(failure);}
        @Override void exchange()throws Exception {
            LeaseFence.Fence fence=authority();String nonce=IdentityStore.randomToken();JsonObject challenge=context(fence,localId,peer.id());
            challenge.addProperty("domain",HELLO_DOMAIN);challenge.addProperty("nonce",nonce);
            SignedPayload hello=identity.sign(IdentityStore.Purpose.SIGNAL,ProfileFiles.bytes(challenge));send(CHALLENGE,ProfileFiles.bytes(JSON.toJsonTree(hello)));
            Frame response=read();if(response.type()!=AUTH)throw error("AUTH_REQUIRED");
            JsonObject auth=signedBody(signed(response.bytes()),peer.id());
            ProfileFiles.fields(auth,Set.of("schema","domain","authorityId","hostId","receiverId","epoch","leaseId","sessionId","challengeSha256"),Set.of());
            if(!AUTH_DOMAIN.equals(ProfileFiles.string(auth,"domain",80))||!hash(ProfileFiles.bytes(JSON.toJsonTree(hello))).equals(ProfileFiles.string(auth,"challengeSha256",64)))
                throw error("AUTH_TRANSCRIPT");
            checkContext(auth,authority(),localId,peer.id());authenticated=true;opened.complete(null);
            while(!stopped.get()){
                Publication publication=queue.take();if(publication.result.isDone())throw error("CANCELED_PUBLICATION");
                if(!PUBLISHER_IO.tryAcquire())throw error("PUBLISHER_IO_CAPACITY");
                try{transferDeadline=System.nanoTime()+options.timeout().toNanos()*10;transfer(publication);}finally{transferring=null;transferDeadline=0;PUBLISHER_IO.release();}
            }
        }
        private void transfer(Publication publication)throws Exception {
            LeaseFence.Fence fence=authority();
            if(publication.sequence!=fence.checkpointSequence()||!publication.source.index().contentRoot().equals(fence.checkpointSha256()))throw error("UNCONFIRMED_PUBLICATION");
            ReplicaProtocol.Offer offered=publication.source.offer(identity,certificate,fence,publication.sequence,publication.base,publication.baseSequence,Instant.now());
            ReplicaProtocol.VerifiedOffer verified=ReplicaProtocol.verify(offered,certificate,fence,publication.base,publication.baseSequence,options.limits(),options.compatibility());
            transferring=verified;send(OFFER,offerBytes(offered));Set<UUID> usedRequests=new HashSet<>();long maximumRequests=(verified.index().totalBytes()+ReplicaProtocol.CHUNK_BYTES-1)/ReplicaProtocol.CHUNK_BYTES+verified.index().files().size();
            while(!stopped.get()){
                Frame frame=read();
                if(frame.type()==REQUEST){
                    List<ReplicaProtocol.Request> requests=requests(frame.bytes());peakOutstanding=Math.max(peakOutstanding,requests.size());
                    for(var request:requests){
                        if(!usedRequests.add(request.requestId())||usedRequests.size()>maximumRequests)throw error("REPLAY_OR_REQUEST_BUDGET");
                        ReplicaProtocol.Chunk chunk=publication.source.read(verified,request,current(verified));current(verified);send(CHUNK,ReplicaProtocol.encodeChunk(chunk));chunkBytes+=chunk.data().length;
                    }
                }else if(frame.type()==READY){
                    SignedPayload ack=signed(frame.bytes());current(verified);ReplicaProtocol.verifyAck(verified,ack,peer.id());current(verified);
                    Receipt receipt=new Receipt(verified.header(),verified.index(),ack);
                    synchronized(this){if(active!=publication||stopped.get()||publication.result.isDone())throw error("CANCELED_PUBLICATION");active=null;
                        if(!publication.result.complete(receipt))throw error("CANCELED_PUBLICATION");readyCount++;}return;
                }else throw error("REQUEST_OR_READY_REQUIRED");
            }
            throw error("CLOSED");
        }
    }
    public static final class Subscriber extends Endpoint {
        private final ReplicaStore store;private ReplicaStore.Ready base;private ReplicaStore.Receiver receiver;
        private final Object results=new Object();private final ArrayDeque<ReplicaStore.Ready> ready=new ArrayDeque<>();
        private final ArrayDeque<CompletableFuture<ReplicaStore.Ready>> waiting=new ArrayDeque<>();
        Subscriber(WebRtcByteStream lane,IdentityStore identity,SignedPayload host,WorldAuthority.Certificate certificate,PolicySource policy,FenceSource fence,ReplicaStore store,ReplicaStore.Ready base,Options options)throws IOException{
            super(lane,identity,host,certificate,policy,fence,options);this.store=Objects.requireNonNull(store);this.base=base;authority();
        }
        @Override boolean publisherSide(){return false;}
        public CompletableFuture<ReplicaStore.Ready> nextReady(){
            synchronized(results){
                if(stopped.get())return CompletableFuture.failedFuture(error("CLOSED"));
                if(!ready.isEmpty()){var result=ready.removeFirst();results.notifyAll();return CompletableFuture.completedFuture(result);}
                while(!waiting.isEmpty()&&waiting.peekFirst().isDone())waiting.removeFirst();
                if(waiting.size()>=4)return CompletableFuture.failedFuture(error("READY_WAITER_CAPACITY"));
                CompletableFuture<ReplicaStore.Ready> value=new CompletableFuture<>();waiting.addLast(value);
                value.whenComplete((v,f)->{if(value.isCancelled())synchronized(results){waiting.remove(value);results.notifyAll();}});
                return value;
            }
        }
        @Override public Stats stats(){synchronized(results){return new Stats(authenticated,stopped.get(),code,sent,received,chunkBytes,peakOutstanding,readyCount,0,waiting.size());}}
        @Override void failPending(IOException failure){synchronized(results){for(var value:waiting)value.completeExceptionally(failure);waiting.clear();ready.clear();results.notifyAll();}}
        @Override void exchange()throws Exception {
            Frame frame=read();if(frame.type()!=CHALLENGE)throw error("HOST_CHALLENGE_REQUIRED");SignedPayload hello=signed(frame.bytes());
            JsonObject challenge=signedBody(hello,peer.id());
            ProfileFiles.fields(challenge,Set.of("schema","domain","authorityId","hostId","receiverId","epoch","leaseId","sessionId","nonce"),Set.of());
            if(!HELLO_DOMAIN.equals(ProfileFiles.string(challenge,"domain",80))||IdentityStore.decode(ProfileFiles.string(challenge,"nonce",64),32).length!=32)throw error("INVALID_CHALLENGE");
            LeaseFence.Fence fence=authority();checkContext(challenge,fence,peer.id(),localId);
            JsonObject auth=context(fence,peer.id(),localId);auth.addProperty("domain",AUTH_DOMAIN);auth.addProperty("challengeSha256",hash(ProfileFiles.bytes(JSON.toJsonTree(hello))));
            SignedPayload response=identity.sign(IdentityStore.Purpose.SIGNAL,ProfileFiles.bytes(auth));send(AUTH,ProfileFiles.bytes(JSON.toJsonTree(response)));
            authority();authenticated=true;opened.complete(null);
            while(!stopped.get()){
                synchronized(results){while(!stopped.get()&&!ready.isEmpty())results.wait();}if(stopped.get())throw error("CLOSED");
                Frame offerFrame=read();if(offerFrame.type()!=OFFER)throw error("OFFER_REQUIRED");
                ReplicaProtocol.Offer offer=offer(offerFrame.bytes());fence=authority();
                ReplicaProtocol.VerifiedOffer verified=ReplicaProtocol.verify(offer,certificate,fence,base==null?null:base.index(),base==null?-1:base.header().sequence(),options.limits(),options.compatibility());
                transferring=verified;transferDeadline=System.nanoTime()+options.timeout().toNanos()*10;
                current(verified);receiver=store.begin(offer,certificate,fence,base);
                while(!stopped.get()){
                    List<ReplicaProtocol.Request> requests=receiver.requests(current(verified));
                    peakOutstanding=Math.max(peakOutstanding,receiver.outstandingChunks());
                    if(requests.isEmpty()){
                        if(receiver.outstandingChunks()!=0)throw error("LOST_CHUNK_RESPONSE");
                        ReplicaStore.Ready complete=receiver.finish(current(verified));receiver=null;current(verified);
                        SignedPayload ack=complete.receipt(identity,Instant.now());current(verified);send(READY,ProfileFiles.bytes(JSON.toJsonTree(ack)));current(verified);
                        base=complete;readyCount++;transferring=null;transferDeadline=0;deliver(complete);break;
                    }
                    send(REQUEST,requestBytes(requests));
                    for(int i=0;i<requests.size();i++){
                        Frame chunk=read();if(chunk.type()!=CHUNK)throw error("CHUNK_REQUIRED");ReplicaProtocol.Chunk data=ReplicaProtocol.decodeChunk(chunk.bytes());
                        receiver.accept(data,current(verified));chunkBytes+=data.data().length;
                    }
                }
            }
        }
        private void deliver(ReplicaStore.Ready value)throws IOException {
            synchronized(results){if(stopped.get())throw error("CLOSED");while(!waiting.isEmpty())if(waiting.removeFirst().complete(value))return;
                if(!ready.isEmpty())throw error("READY_QUEUE_CAPACITY");ready.addLast(value);results.notifyAll();}
        }
        @Override void cleanup()throws IOException{try{if(receiver!=null){receiver.cancel();receiver=null;}}finally{RECEIVER_IO.release();}}
    }
    private static JsonObject context(LeaseFence.Fence fence,String host,String receiver){
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("authorityId",fence.authorityId());value.addProperty("hostId",host);value.addProperty("receiverId",receiver);
        value.addProperty("epoch",fence.epoch());value.addProperty("leaseId",fence.leaseId().toString());value.addProperty("sessionId",fence.sessionId().toString());return value;
    }
    private static void checkContext(JsonObject value,LeaseFence.Fence fence,String host,String receiver)throws IOException {
        if(ProfileFiles.number(value,"schema")!=1||!ProfileFiles.string(value,"authorityId",64).equals(fence.authorityId())
                ||!ProfileFiles.string(value,"hostId",64).equals(host)||!ProfileFiles.string(value,"receiverId",64).equals(receiver)
                ||ProfileFiles.number(value,"epoch")!=fence.epoch()||!ProfileFiles.string(value,"leaseId",36).equals(fence.leaseId().toString())
                ||!ProfileFiles.string(value,"sessionId",36).equals(fence.sessionId().toString()))throw error("AUTHORITY_CONTEXT_CHANGED");
    }
    private static int limit(int type)throws IOException{return switch(type){case CHALLENGE,AUTH,READY->SIGNED_LIMIT;case OFFER->MAX_FRAME;case REQUEST->4096;case CHUNK->ReplicaProtocol.CHUNK_BYTES+114;default->throw error("UNKNOWN_FRAME");};}
    private static SignedPayload signed(byte[] bytes)throws IOException{return WorldAuthority.signedFromJson(ProfileFiles.parse(bytes,SIGNED_LIMIT));}
    private static byte[] offerBytes(ReplicaProtocol.Offer offer)throws IOException {
        byte[] header=ProfileFiles.bytes(JSON.toJsonTree(offer.header())),index=offer.index();
        if(header.length>SIGNED_LIMIT||index.length>INDEX_LIMIT)throw error("OFFER_LIMIT");
        return ByteBuffer.allocate(8+header.length+index.length).putInt(header.length).put(header).putInt(index.length).put(index).array();
    }
    private static ReplicaProtocol.Offer offer(byte[] bytes)throws IOException {
        try{ByteBuffer input=ByteBuffer.wrap(bytes);int head=input.getInt();if(head<1||head>SIGNED_LIMIT||head>input.remaining()-4)throw error("HEADER_SIZE");
            byte[] header=new byte[head];input.get(header);int length=input.getInt();if(length<1||length>INDEX_LIMIT||length!=input.remaining())throw error("INDEX_SIZE");
            byte[] index=new byte[length];input.get(index);return new ReplicaProtocol.Offer(signed(header),index);
        }catch(BufferUnderflowException|IllegalArgumentException bad){throw error("INVALID_OFFER");}
    }
    private static byte[] requestBytes(List<ReplicaProtocol.Request> requests)throws IOException {
        if(requests.isEmpty()||requests.size()>ReplicaProtocol.MAX_INFLIGHT)throw error("REQUEST_COUNT");
        JsonObject value=new JsonObject();value.add("requests",JSON.toJsonTree(requests));byte[] bytes=ProfileFiles.bytes(value);if(bytes.length>4096)throw error("REQUEST_LIMIT");return bytes;
    }
    private static List<ReplicaProtocol.Request> requests(byte[] bytes)throws IOException {
        JsonObject value=ProfileFiles.parse(bytes,4096);ProfileFiles.fields(value,Set.of("requests"),Set.of());
        if(!value.get("requests").isJsonArray())throw error("REQUEST_TYPE");JsonArray rows=value.getAsJsonArray("requests");
        if(rows.isEmpty()||rows.size()>ReplicaProtocol.MAX_INFLIGHT)throw error("REQUEST_COUNT");List<ReplicaProtocol.Request> result=new ArrayList<>();
        for(JsonElement element:rows){
            if(!element.isJsonObject())throw error("REQUEST_TYPE");JsonObject row=element.getAsJsonObject();
            ProfileFiles.fields(row,Set.of("snapshotId","requestId","fileSha256","offset","bytes"),Set.of());
            result.add(new ReplicaProtocol.Request(ReplicaProtocol.uuid(ProfileFiles.string(row,"snapshotId",36)),ReplicaProtocol.uuid(ProfileFiles.string(row,"requestId",36)),
                    ReplicaProtocol.digest(ProfileFiles.string(row,"fileSha256",64)),ProfileFiles.number(row,"offset"),Math.toIntExact(ProfileFiles.number(row,"bytes"))));
        }return List.copyOf(result);
    }
    private static String hash(byte[] value){return ReplicaProtocol.hash(value);}
    private static final class TransferFailure extends IOException {final String code;TransferFailure(String code){super("Replica transfer: "+code);this.code=code;}}
    private static IOException error(String code){return new TransferFailure(code);}
}
