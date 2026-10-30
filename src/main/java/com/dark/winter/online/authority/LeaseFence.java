// Fresh pinned-node observations turn into short local monotonic fences, never replay-reset wall-clock timers.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Fresh pinned-node observations turn into short local monotonic fences, never replay-reset wall-clock timers. */
public final class LeaseFence {
    public static final long SAFETY_MS=2000;
    private LeaseFence(){}
    public static final class RequestContext implements AutoCloseable {
        private final UUID id=UUID.randomUUID();private final String operation;
        private final LongSupplier nano;private final long started;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private RequestContext(String operation,LongSupplier nano){this.operation=operation;this.nano=nano;started=nano.getAsLong();}
        public UUID requestId(){return id;}
        /** Cancellation permanently invalidates late responses. Retries must retain the original context/start time. */
        @Override public void close(){consumed.set(true);}
    }
    public static RequestContext begin(String operation,LongSupplier nano)throws IOException{
        if(!Set.of("authority_claim","authority_renew","authority_status").contains(operation))throw new IOException("Operation cannot produce an active lease fence");
        return new RequestContext(operation,Objects.requireNonNull(nano));
    }
    public static final class Fence {
        private final String authorityId,holderId,checkpointSha,policySha;
        private final UUID leaseId,sessionId;private final long epoch,policyRevision,checkpointSequence,allowanceNanos;
        private final LongSupplier nano;private final long started;
        private Fence(String authorityId,String holderId,UUID leaseId,UUID sessionId,long epoch,long policyRevision,long checkpointSequence,
                      String checkpointSha,String policySha,RequestContext request,long remainingMs){
            this.authorityId=authorityId;this.holderId=holderId;this.leaseId=leaseId;this.sessionId=sessionId;
            this.epoch=epoch;this.policyRevision=policyRevision;this.checkpointSequence=checkpointSequence;this.checkpointSha=checkpointSha;this.policySha=policySha;
            nano=request.nano;started=request.started;allowanceNanos=(remainingMs-SAFETY_MS)*1000000L;
        }
        public String authorityId(){return authorityId;}public String holderId(){return holderId;}
        public UUID leaseId(){return leaseId;}public UUID sessionId(){return sessionId;}public long epoch(){return epoch;}
        public long policyRevision(){return policyRevision;}public String policySha256(){return policySha;}public long checkpointSequence(){return checkpointSequence;}
        public String checkpointSha256(){return checkpointSha;}
        public long remainingMillis(){
            long elapsed=nano.getAsLong()-started;
            return elapsed<0?0:Math.max(0,(allowanceNanos-elapsed)/1000000L);
        }
        public boolean usable(){return remainingMillis()>0;}
        public void requireUsable()throws IOException{if(!usable())throw new IOException("Local host authority lease expired");}
    }
    public static Fence accept(WorldAuthority.Certificate certificate,RequestContext context,SignedPayload response,
                               long minimumEpoch,long minimumPolicyRevision,String expectedHolder,UUID expectedSession)throws IOException{
        if(context==null||context.consumed.get())throw new IOException("Authority observation was canceled or already consumed");
        WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
        if(!IdentityStore.verify(response,Purpose.SERVICE_RESPONSE)||!response.signer().id().equals(root.coordinatorId()))
            throw new IOException("Authority lease was not signed by the pinned coordinator");
        byte[] bytes=IdentityStore.decodeData(response);JsonObject body=ProfileFiles.parse(bytes,65536);
        if(!Arrays.equals(bytes,ProfileFiles.bytes(body)))throw new IOException("Noncanonical authority receipt");
        ProfileFiles.fields(body,Set.of("ok","schema","domain","requestId","issuedAt","operation","authorityId","epoch","policyRevision","policy",
            "checkpointSequence","checkpointSha256","quarantined","lease"),Set.of());
        if(!bool(body,"ok")||WorldAuthority.integer(body,"schema")!=1||
            !AuthorityLeaseStore.RECEIPT.equals(ProfileFiles.string(body,"domain",100))||
            !context.id.equals(WorldAuthority.uuid(ProfileFiles.string(body,"requestId",36)))||
            !context.operation.equals(ProfileFiles.string(body,"operation",32))||
            !certificate.authorityId().equals(ProfileFiles.string(body,"authorityId",64)))
            throw new IOException("Authority response belongs to another request, domain or world");
        long issued=WorldAuthority.time(WorldAuthority.integer(body,"issuedAt")),epoch=WorldAuthority.integer(body,"epoch"),
            revision=WorldAuthority.integer(body,"policyRevision"),position=WorldAuthority.integer(body,"checkpointSequence");
        if(minimumEpoch<0||minimumPolicyRevision<0||epoch<Math.max(1,minimumEpoch)||revision<minimumPolicyRevision||position<0)
            throw new IOException("Authority epoch/policy is older than the external pin");
        WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(object(body,"policy")));
        if(policy.revision()!=revision)throw new IOException("Owner policy revision mismatch");
        String sha=WorldAuthority.digest(ProfileFiles.string(body,"checkpointSha256",64));
        JsonObject lease=object(body,"lease");
        if(bool(body,"quarantined")||!bool(lease,"present"))throw new IOException("No active host lease at the coordinator");
        ProfileFiles.fields(lease,Set.of("present","holderId","sessionId","leaseId","remainingMs","revoked","active"),Set.of());
        String holder=WorldAuthority.identity(ProfileFiles.string(lease,"holderId",64));
        UUID session=WorldAuthority.uuid(ProfileFiles.string(lease,"sessionId",36)),id=WorldAuthority.uuid(ProfileFiles.string(lease,"leaseId",36));
        long remaining=WorldAuthority.integer(lease,"remainingMs");
        if(!bool(lease,"active")||bool(lease,"revoked")||remaining<=SAFETY_MS||remaining>AuthorityLeaseStore.MAX_TTL_MS||
            !holder.equals(expectedHolder)||!session.equals(expectedSession))throw new IOException("No live lease for the expected host/session");
        WorldAuthority.authorizeHost(policy,holder,issued);
        if(!root.ownerId().equals(holder))for(WorldAuthority.Grant grant:policy.grants())if(grant.delegate().equals(holder)&&remaining>grant.expiresAt()-issued)
            throw new IOException("Lease outlives its owner-signed delegation");
        Fence fence=new Fence(certificate.authorityId(),holder,id,session,epoch,revision,position,sha,policy.sha256(),context,remaining);
        fence.requireUsable();
        if(!context.consumed.compareAndSet(false,true))throw new IOException("Authority observation was canceled or already consumed");
        return fence;
    }
    private static JsonObject object(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonObject())throw new IOException("Missing receipt object");return entry.getAsJsonObject();}
    private static boolean bool(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isBoolean())throw new IOException("Missing receipt boolean");return entry.getAsBoolean();}
}
