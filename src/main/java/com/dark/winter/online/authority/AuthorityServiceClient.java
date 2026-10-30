// Sends signed authority requests through an existing verified directory connection.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * No endpoint or implicit connection. Outbound is the owning runtime's existing directory::request.
 * Signing happens on a bounded worker. The caller owns identity, directory, persistent pins and game lifecycle.
 */
public final class AuthorityServiceClient implements AutoCloseable {
    @FunctionalInterface public interface Outbound{CompletableFuture<JsonObject> request(String operation,JsonObject arguments);}
    @FunctionalInterface private interface Decode<T>{T apply(SignedPayload signed)throws IOException;}
    private static final Gson JSON=new Gson();
    private final WorldAuthority.Certificate certificate;private final String nodeId;
    private final Supplier<IdentityStore> identity;private final Outbound outbound;private final Clock clock;private final LongSupplier nano;
    private final AtomicBoolean closed=new AtomicBoolean();private final Semaphore slots=new Semaphore(16);
    private final AtomicLong observedEpoch=new AtomicLong(),observedPolicy=new AtomicLong();
    private final Set<Pending<?>> pending=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),
        task->{Thread thread=new Thread(task,"DarkWinter-authority-client");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private static final class Pending<T>{
        final CompletableFuture<T> result=new CompletableFuture<>();final UUID requestId;final LeaseFence.RequestContext context;
        volatile Future<?> work;volatile CompletableFuture<?> upstream;volatile String actor;
        Pending(LeaseFence.RequestContext context){this.context=context;requestId=context==null?UUID.randomUUID():context.requestId();}
        void invalidate(){if(context!=null)context.close();if(work!=null)work.cancel(true);if(upstream!=null)upstream.cancel(true);}
    }
    public AuthorityServiceClient(WorldAuthority.Certificate certificate,Supplier<IdentityStore> singleIdentity,Outbound outbound,Clock clock,LongSupplier nano)throws IOException{
        this.certificate=Objects.requireNonNull(certificate);nodeId=WorldAuthority.verify(certificate).coordinatorId();
        identity=Objects.requireNonNull(singleIdentity);this.outbound=Objects.requireNonNull(outbound);this.clock=Objects.requireNonNull(clock);this.nano=Objects.requireNonNull(nano);
    }
    public String authorityId(){return certificate.authorityId();}public String coordinatorId(){return nodeId;}
    public long observedEpoch(){return observedEpoch.get();}public long observedPolicyRevision(){return observedPolicy.get();}
    /** These in-memory pins supplement but do not replace the caller's durable external pins. */
    public void requireMinimum(long epoch,long policyRevision)throws IOException{
        if(epoch<0||policyRevision<0)throw new IOException("Invalid authority pin");
        observedEpoch.accumulateAndGet(epoch,Math::max);observedPolicy.accumulateAndGet(policyRevision,Math::max);
    }
    public CompletableFuture<JsonObject> enroll(SignedPayload ownerPolicy){
        try{JsonObject args=args();args.add("certificate",WorldAuthority.certificateToJson(certificate));args.add("policy",JSON.toJsonTree(ownerPolicy));return plain("authority_enroll",args);}
        catch(IOException failure){return CompletableFuture.failedFuture(failure);}
    }
    public CompletableFuture<JsonObject> policy(SignedPayload ownerPolicy){JsonObject args=args();args.add("policy",JSON.toJsonTree(ownerPolicy));return plain("authority_policy",args);}
    /** Observing parsed status is not permission to simulate. Only an accepted unexpired Fence grants that capability. */
    public CompletableFuture<JsonObject> status(){return plain("authority_status",args());}
    public CompletableFuture<LeaseFence.Fence> claim(long expectedEpoch,UUID nativeSession,long checkpointSequence,String checkpointSha,long ttlMs,long minimumPolicyRevision){
        JsonObject args=args();args.addProperty("expectedEpoch",expectedEpoch);args.addProperty("sessionId",nativeSession.toString());
        checkpoint(args,checkpointSequence,checkpointSha,ttlMs);
        return fenced("authority_claim",args,expectedEpoch==Long.MAX_VALUE?Long.MAX_VALUE:expectedEpoch+1,minimumPolicyRevision,null,nativeSession);
    }
    public CompletableFuture<LeaseFence.Fence> renew(LeaseFence.Fence previous,long checkpointSequence,String checkpointSha,long ttlMs){
        try{previous.requireUsable();requireOwnFence(previous);JsonObject args=leaseArgs(previous);checkpoint(args,checkpointSequence,checkpointSha,ttlMs);
            return fenced("authority_renew",args,previous.epoch(),previous.policyRevision(),previous.holderId(),previous.sessionId());}
        catch(IOException failure){return CompletableFuture.failedFuture(failure);}
    }
    public CompletableFuture<JsonObject> release(LeaseFence.Fence previous){
        try{requireOwnFence(previous);return plain("authority_release",leaseArgs(previous));}
        catch(IOException failure){return CompletableFuture.failedFuture(failure);}
    }
    public CompletableFuture<LeaseFence.Fence> observe(String expectedHolder,UUID nativeSession,long minimumEpoch,long minimumPolicyRevision){
        return fenced("authority_status",args(),minimumEpoch,minimumPolicyRevision,expectedHolder,nativeSession);
    }
    private CompletableFuture<JsonObject> plain(String operation,JsonObject arguments){return send(operation,arguments,null,signed->ProfileFiles.parse(IdentityStore.decodeData(signed),65536));}
    private CompletableFuture<LeaseFence.Fence> fenced(String operation,JsonObject arguments,long minimumEpoch,long minimumPolicy,String expectedHolder,UUID session){
        try{
            if(minimumEpoch<0||minimumPolicy<0)throw new IOException("Invalid authority pin");
            LeaseFence.RequestContext context=LeaseFence.begin(operation,nano);
            return send(operation,arguments,context,signed->{
                String holder=expectedHolder==null?currentIdentity().profile().id():expectedHolder;
                return LeaseFence.accept(certificate,context,signed,Math.max(minimumEpoch,observedEpoch.get()),Math.max(minimumPolicy,observedPolicy.get()),holder,session);
            });
        }catch(IOException failure){return CompletableFuture.failedFuture(failure);}
    }
    private <T> CompletableFuture<T> send(String operation,JsonObject arguments,LeaseFence.RequestContext context,Decode<T> decode){
        if(closed.get()){if(context!=null)context.close();return CompletableFuture.failedFuture(new IOException("Authority client closed"));}
        if(!slots.tryAcquire()){if(context!=null)context.close();return CompletableFuture.failedFuture(new IOException("Authority request capacity"));}
        Pending<T> item=new Pending<>(context);pending.add(item);
        item.result.whenComplete((value,failure)->{
            if(item.result.isCancelled())item.invalidate();
            if(item.context!=null)item.context.close();pending.remove(item);slots.release();
        });
        JsonObject copy=arguments.deepCopy();
        try{item.work=worker.submit(()->{
            try{
                if(closed.get()||item.result.isDone())throw new IOException("Authority request canceled");
                IdentityStore store=currentIdentity();item.actor=store.profile().id();
                JsonObject request=new JsonObject();request.addProperty("requestId",item.requestId.toString());request.addProperty("issuedAt",clock.millis());
                request.addProperty("operation",operation);request.add("arguments",copy);
                SignedPayload signed=store.sign(Purpose.SERVICE_REQUEST,ProfileFiles.bytes(request));
                if(closed.get()||item.result.isDone())throw new IOException("Authority request canceled");
                JsonObject wrapper=new JsonObject();wrapper.add("request",JSON.toJsonTree(signed));
                CompletableFuture<JsonObject> source=Objects.requireNonNull(outbound.request("authority",wrapper));item.upstream=source;
                if(closed.get()||item.result.isDone()){source.cancel(true);return;}
                source.whenComplete((response,failure)->{
                    if(item.result.isDone())return;
                    try{
                        if(failure!=null)throw failure instanceof CompletionException ce?ce:new CompletionException(failure);
                        if(closed.get()||!item.actor.equals(currentIdentity().profile().id()))throw new IOException("Authority identity or client changed");
                        JsonElement value=response==null?null:response.get("authorityReceipt");
                        if(value==null||!value.isJsonObject())throw new IOException("Directory node does not provide authority receipts");
                        SignedPayload receipt=WorldAuthority.signedFromJson(value.getAsJsonObject());
                        validateReceipt(receipt,item.requestId,operation);
                        if(item.result.isDone()||closed.get())return;
                        item.result.complete(decode.apply(receipt));
                    }catch(Throwable invalid){item.result.completeExceptionally(failure(invalid));}
                });
            }catch(Throwable invalid){item.result.completeExceptionally(failure(invalid));}
        });}catch(RejectedExecutionException stopped){item.result.completeExceptionally(new IOException("Authority client queue closed",stopped));}
        return item.result;
    }
    private void validateReceipt(SignedPayload receipt,UUID request,String operation)throws IOException{
        if(!IdentityStore.verify(receipt,Purpose.SERVICE_RESPONSE)||!receipt.signer().id().equals(nodeId))throw new IOException("Authority receipt came from another coordinator");
        byte[] data=IdentityStore.decodeData(receipt);JsonObject body=ProfileFiles.parse(data,65536);
        if(!Arrays.equals(data,ProfileFiles.bytes(body)))throw new IOException("Noncanonical authority receipt");
        ProfileFiles.fields(body,Set.of("ok","schema","domain","requestId","issuedAt","operation","authorityId","epoch","policyRevision","policy",
            "checkpointSequence","checkpointSha256","quarantined","lease"),Set.of());
        if(!bool(body,"ok")||WorldAuthority.integer(body,"schema")!=1||!AuthorityLeaseStore.RECEIPT.equals(ProfileFiles.string(body,"domain",100))||
            !request.equals(WorldAuthority.uuid(ProfileFiles.string(body,"requestId",36)))||!operation.equals(ProfileFiles.string(body,"operation",32))||
            !certificate.authorityId().equals(ProfileFiles.string(body,"authorityId",64)))throw new IOException("Foreign authority receipt context");
        long now=WorldAuthority.time(clock.millis()),issued=WorldAuthority.time(WorldAuthority.integer(body,"issuedAt"));
        if(issued<now-120000||issued>now+15000)throw new IOException("Stale coordinator receipt");
        long epoch=WorldAuthority.integer(body,"epoch"),revision=WorldAuthority.integer(body,"policyRevision"),position=WorldAuthority.integer(body,"checkpointSequence");
        if(epoch<0||revision<0||position<0||epoch<observedEpoch.get()||revision<observedPolicy.get())throw new IOException("Authority epoch or owner policy rolled back");
        WorldAuthority.digest(ProfileFiles.string(body,"checkpointSha256",64));
        WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(object(body,"policy")));
        if(policy.revision()!=revision)throw new IOException("Authority policy revision mismatch");
        bool(body,"quarantined");JsonObject lease=object(body,"lease");
        if(bool(lease,"present")){
            ProfileFiles.fields(lease,Set.of("present","holderId","sessionId","leaseId","remainingMs","revoked","active"),Set.of());
            WorldAuthority.identity(ProfileFiles.string(lease,"holderId",64));WorldAuthority.uuid(ProfileFiles.string(lease,"sessionId",36));
            WorldAuthority.uuid(ProfileFiles.string(lease,"leaseId",36));long left=WorldAuthority.integer(lease,"remainingMs");
            if(left<0||left>AuthorityLeaseStore.MAX_TTL_MS)throw new IOException("Authority lease duration outside bounds");
            bool(lease,"revoked");bool(lease,"active");
        }else ProfileFiles.fields(lease,Set.of("present"),Set.of());
        observedEpoch.accumulateAndGet(epoch,Math::max);observedPolicy.accumulateAndGet(revision,Math::max);
    }
    private IdentityStore currentIdentity()throws IOException{
        try{IdentityStore store=identity.get();if(store==null)throw new IOException("No local identity for authority service");store.profile();return store;}
        catch(IllegalStateException closed){throw new IOException("Local authority identity closed",closed);}
    }
    private void requireOwnFence(LeaseFence.Fence fence)throws IOException{
        if(fence==null||!certificate.authorityId().equals(fence.authorityId())||!currentIdentity().profile().id().equals(fence.holderId()))
            throw new IOException("Foreign host fence");
    }
    private JsonObject args(){JsonObject args=new JsonObject();args.addProperty("authorityId",certificate.authorityId());return args;}
    private JsonObject leaseArgs(LeaseFence.Fence fence){
        JsonObject args=args();args.addProperty("epoch",fence.epoch());args.addProperty("leaseId",fence.leaseId().toString());args.addProperty("sessionId",fence.sessionId().toString());return args;
    }
    private static void checkpoint(JsonObject args,long sequence,String sha,long ttl){args.addProperty("checkpointSequence",sequence);args.addProperty("checkpointSha256",sha);args.addProperty("ttlMs",ttl);}
    private static JsonObject object(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonObject())throw new IOException("Missing authority response object");return entry.getAsJsonObject();}
    private static boolean bool(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isBoolean())throw new IOException("Missing authority response boolean");return entry.getAsBoolean();}
    private static IOException failure(Throwable failure){
        while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();
        return failure instanceof IOException io?io:new IOException("Authority service request failed",failure);
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(Pending<?> item:List.copyOf(pending)){item.invalidate();item.result.completeExceptionally(new IOException("Authority client closed"));}
        worker.shutdownNow();
    }
}
