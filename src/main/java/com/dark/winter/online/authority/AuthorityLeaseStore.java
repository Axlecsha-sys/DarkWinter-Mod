// Persists signed coordinator leases, authority policies, and replay-protection state.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Candidate coordinator backend: no HTTP listener, MC state or automatic host migration.
 * One externally pinned node identity owns this protected store. Runtime must actually stop
 * simulation before its locally timed lease expires. A malicious coordinator is outside this trust model.
 */
public final class AuthorityLeaseStore implements AutoCloseable {
    public static final long DEFAULT_TTL_MS=30000,MAX_TTL_MS=60000,RESTART_QUARANTINE_MS=MAX_TTL_MS+2000;
    static final String RECEIPT="DarkWinter.WorldAuthority.lease-receipt.v1";
    private static final String STATE="DarkWinter.WorldAuthority.coordinator-state.v1",STATE_SIGNATURE=STATE+".signature";
    private static final int MAX_WORLDS=64,MAX_SEEN=128,FILE_LIMIT=4*1024*1024;
    private static final Gson JSON=new Gson();
    private record Seen(String digest,long until){}
    private record Lease(String holderId,UUID sessionId,UUID id,long issuedAt,long ttlMs,boolean revoked){}
    private record World(WorldAuthority.Certificate certificate,WorldAuthority.Policy policy,long epoch,
                         Lease lease,long checkpointSequence,String checkpointSha,Map<String,Seen> seen){
        World{seen=Map.copyOf(seen);}
    }
    private record Running(UUID leaseId,long startedNano,long ttlMs){}
    private final IdentityStore node;private final Clock clock;private final LongSupplier nano;
    private final Path file;private final FileChannel channel;private final FileLock lock;private final long openedNano;
    private Map<String,World> worlds=Map.of();private final Map<String,Running> running=new HashMap<>();
    private long sequence;private boolean quarantine,closed;
    private AuthorityLeaseStore(Path file,IdentityStore node,Clock clock,LongSupplier nano,FileChannel channel,FileLock lock){
        this.file=file;this.node=node;this.clock=clock;this.nano=nano;this.channel=channel;this.lock=lock;openedNano=nano.getAsLong();
    }
    public static AuthorityLeaseStore open(Path directory,IdentityStore sharedNodeIdentity,Clock clock,LongSupplier nano)throws IOException{
        Objects.requireNonNull(sharedNodeIdentity);Objects.requireNonNull(clock);Objects.requireNonNull(nano);
        Path base=ProfileFiles.directory(directory),file=base.resolve("authority-leases.json"),lockPath=base.resolve(".authority-leases.lock");
        if(Files.isSymbolicLink(lockPath))throw new IOException("Linked coordinator lock");
        FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;
        try{
            ProfileFiles.protect(lockPath,false);lock=channel.tryLock();if(lock==null)throw new IOException("Coordinator store already open");
            AuthorityLeaseStore store=new AuthorityLeaseStore(file,sharedNodeIdentity,clock,nano,channel,lock);
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){ProfileFiles.protect(file,false);store.load();}
            return store;
        }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Coordinator store cannot be opened",failure);}
    }
    /** The complete SERVICE_REQUEST is verified here; a caller-supplied actor string never authenticates a claim. */
    public synchronized SignedPayload handle(SignedPayload signed)throws IOException{
        if(closed)throw new IOException("Coordinator store closed");
        if(!IdentityStore.verify(signed,Purpose.SERVICE_REQUEST))throw new IOException("Invalid signed authority service request");
        JsonObject request=ProfileFiles.parse(IdentityStore.decodeData(signed),65536);
        ProfileFiles.fields(request,Set.of("requestId","issuedAt","operation","arguments"),Set.of());
        UUID requestId=WorldAuthority.uuid(ProfileFiles.string(request,"requestId",36));
        long issued=WorldAuthority.time(WorldAuthority.integer(request,"issuedAt")),now=WorldAuthority.time(clock.millis());
        if(issued<now-60000||issued>now+15000)throw new IOException("Stale authority request");
        String operation=ProfileFiles.string(request,"operation",32),actor=signed.signer().id();
        if(!Set.of("authority_enroll","authority_policy","authority_claim","authority_renew","authority_release","authority_status").contains(operation))
            throw new IOException("Unknown authority operation");
        JsonObject args=object(request,"arguments");String authority=authorityId(ProfileFiles.string(args,"authorityId",64));
        World current=worlds.get(authority);String seenKey=actor+":"+requestId,digest=WorldAuthority.hash(IdentityStore.decodeData(signed));
        if(current!=null&&current.seen().containsKey(seenKey)){
            if(!current.seen().get(seenKey).digest().equals(digest))throw new IOException("Authority request identifier reused with another body");
            // Idempotent re-delivery never renews the lease. The response is a fresh observation of current state.
            return response(current,authority,requestId,operation,now);
        }
        if(operation.equals("authority_status")){
            fields(args,"authorityId");if(current==null)throw new IOException("Unknown world authority");
            return response(current,authority,requestId,operation,now);
        }
        World next;Running nextRunning=null;boolean replaceRunning=false;
        switch(operation){
            case "authority_enroll"->{
                fields(args,"authorityId","certificate","policy");
                if(current!=null)throw new IOException("Authority already enrolled; use owner policy update");
                if(worlds.size()>=MAX_WORLDS)throw new IOException("Coordinator authority capacity");
                WorldAuthority.Certificate certificate=WorldAuthority.certificateFromJson(object(args,"certificate"));
                WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
                if(!certificate.authorityId().equals(authority)||!root.ownerId().equals(actor)||!root.coordinatorId().equals(node.profile().id()))
                    throw new IOException("Only the certified owner may enroll at the pinned coordinator");
                for(World other:worlds.values())if(WorldAuthority.verify(other.certificate()).islandId().equals(root.islandId()))
                    throw new IOException("World UUID is already pinned to another authority root");
                WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(object(args,"policy")));
                if(policy.issuedAt()>now+15000)throw new IOException("Future owner policy");
                next=new World(certificate,policy,0,null,0,root.genesisSha256(),Map.of());
            }
            case "authority_policy"->{
                fields(args,"authorityId","policy");require(current);
                WorldAuthority.Genesis root=WorldAuthority.verify(current.certificate());
                if(!root.ownerId().equals(actor))throw new IOException("Only the certified owner may replace host grants");
                WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(current.certificate(),WorldAuthority.signedFromJson(object(args,"policy")));
                if(policy.issuedAt()>now+15000||policy.revision()<current.policy().revision()||
                    policy.revision()==current.policy().revision()&&!policy.sha256().equals(current.policy().sha256()))
                    throw new IOException("Stale or conflicting owner policy");
                Lease lease=current.lease();
                if(lease!=null&&(lease.revoked()||!authorized(policy,lease.holderId(),now)))
                    lease=new Lease(lease.holderId(),lease.sessionId(),lease.id(),lease.issuedAt(),lease.ttlMs(),true);
                // Revocation invalidates the old holder but does not shorten the blocking deadline.
                next=new World(current.certificate(),policy,current.epoch(),lease,current.checkpointSequence(),current.checkpointSha(),current.seen());
            }
            case "authority_claim"->{
                fields(args,"authorityId","expectedEpoch","sessionId","checkpointSequence","checkpointSha256","ttlMs");require(current);
                if(quarantined())throw new IOException("Coordinator restart quarantine");
                long expected=nonnegative(args,"expectedEpoch");
                if(expected!=current.epoch()||expected==Long.MAX_VALUE)throw new IOException("Stale or exhausted authority epoch");
                if(remaining(authority,current)>0)throw new IOException("Previous host lease has not been released or expired");
                checkpoint(current,args,false);
                WorldAuthority.authorizeHost(current.policy(),actor,now);
                long ttl=ttl(current.policy(),actor,now,args);
                UUID session=WorldAuthority.uuid(ProfileFiles.string(args,"sessionId",36)),lease=UUID.randomUUID();
                next=new World(current.certificate(),current.policy(),expected+1,new Lease(actor,session,lease,now,ttl,false),
                    current.checkpointSequence(),current.checkpointSha(),current.seen());
                nextRunning=new Running(lease,nano.getAsLong(),ttl);replaceRunning=true;
            }
            case "authority_renew"->{
                fields(args,"authorityId","epoch","leaseId","sessionId","checkpointSequence","checkpointSha256","ttlMs");require(current);
                if(quarantined())throw new IOException("Coordinator restart quarantine");
                exactLease(current,args,actor);
                if(current.lease().revoked()||remaining(authority,current)<=0)throw new IOException("Host lease expired or revoked");
                WorldAuthority.authorizeHost(current.policy(),actor,now);checkpoint(current,args,true);
                long ttl=ttl(current.policy(),actor,now,args),position=nonnegative(args,"checkpointSequence");
                String sha=WorldAuthority.digest(ProfileFiles.string(args,"checkpointSha256",64));
                Lease old=current.lease(),lease=new Lease(actor,old.sessionId(),old.id(),now,ttl,false);
                next=new World(current.certificate(),current.policy(),current.epoch(),lease,position,sha,current.seen());
                nextRunning=new Running(lease.id(),nano.getAsLong(),ttl);replaceRunning=true;
            }
            case "authority_release"->{
                fields(args,"authorityId","epoch","leaseId","sessionId");require(current);exactLease(current,args,actor);
                next=new World(current.certificate(),current.policy(),current.epoch(),null,current.checkpointSequence(),current.checkpointSha(),current.seen());
                replaceRunning=true;
            }
            default->throw new IOException("Unknown mutating authority operation");
        }
        Map<String,Seen> seen=new HashMap<>(next.seen());seen.entrySet().removeIf(entry->entry.getValue().until()<now);
        if(seen.size()>=MAX_SEEN)throw new IOException("Authority replay receipt capacity");
        seen.put(seenKey,new Seen(digest,Math.min(253402300799000L,issued+75000)));
        next=new World(next.certificate(),next.policy(),next.epoch(),next.lease(),next.checkpointSequence(),next.checkpointSha(),seen);
        Map<String,World> proposed=new HashMap<>(worlds);proposed.put(authority,next);
        persist(proposed); // File content is forced and atomically published BEFORE acknowledging the CAS.
        if(replaceRunning){if(nextRunning==null)running.remove(authority);else running.put(authority,nextRunning);}
        return response(next,authority,requestId,operation,now);
    }
    private SignedPayload response(World world,String authority,UUID requestId,String operation,long now)throws IOException{
        JsonObject result=new JsonObject();result.addProperty("ok",true);result.addProperty("schema",1);result.addProperty("domain",RECEIPT);
        result.addProperty("requestId",requestId.toString());result.addProperty("issuedAt",now);result.addProperty("operation",operation);
        result.addProperty("authorityId",authority);result.addProperty("epoch",world.epoch());result.addProperty("policyRevision",world.policy().revision());
        result.add("policy",JSON.toJsonTree(world.policy().proof()));result.addProperty("checkpointSequence",world.checkpointSequence());
        result.addProperty("checkpointSha256",world.checkpointSha());boolean guarded=quarantined();result.addProperty("quarantined",guarded);
        Lease lease=world.lease();
        if(lease==null)result.add("lease",noLease());
        else{
            JsonObject value=new JsonObject();value.addProperty("present",true);value.addProperty("holderId",lease.holderId());value.addProperty("sessionId",lease.sessionId().toString());
            value.addProperty("leaseId",lease.id().toString());long left=guarded?0:remaining(authority,world);
            value.addProperty("remainingMs",left);value.addProperty("revoked",lease.revoked()||!authorized(world.policy(),lease.holderId(),now));
            value.addProperty("active",left>0&&!lease.revoked()&&authorized(world.policy(),lease.holderId(),now));
            result.add("lease",value);
        }
        return node.sign(Purpose.SERVICE_RESPONSE,ProfileFiles.bytes(result));
    }
    private static boolean authorized(WorldAuthority.Policy policy,String actor,long now){
        try{WorldAuthority.authorizeHost(policy,actor,now);return true;}catch(IOException denied){return false;}
    }
    private boolean quarantined()throws IOException{
        if(!quarantine)return false;long elapsed=nano.getAsLong()-openedNano;
        if(elapsed<0)throw new IOException("Coordinator monotonic clock reversed");
        return elapsed<RESTART_QUARANTINE_MS*1000000L;
    }
    private long remaining(String authority,World world)throws IOException{
        Lease lease=world.lease();Running live=running.get(authority);
        if(lease==null||live==null||!live.leaseId().equals(lease.id()))return 0;
        long elapsed=nano.getAsLong()-live.startedNano();
        if(elapsed<0)throw new IOException("Coordinator monotonic clock reversed");
        return Math.max(0,live.ttlMs()-((elapsed+999999)/1000000));
    }
    private static long ttl(WorldAuthority.Policy policy,String actor,long now,JsonObject args)throws IOException{
        long ttl=WorldAuthority.integer(args,"ttlMs");
        if(ttl<5000||ttl>MAX_TTL_MS)throw new IOException("Lease TTL outside5000..60000ms");
        if(!WorldAuthority.verify(policy.certificate()).ownerId().equals(actor))
            for(WorldAuthority.Grant grant:policy.grants())if(grant.delegate().equals(actor))ttl=Math.min(ttl,grant.expiresAt()-now);
        if(ttl<5000)throw new IOException("Host grant expires too soon for a lease");return ttl;
    }
    private static void checkpoint(World world,JsonObject args,boolean advance)throws IOException{
        long position=nonnegative(args,"checkpointSequence");String sha=WorldAuthority.digest(ProfileFiles.string(args,"checkpointSha256",64));
        if(position<world.checkpointSequence()||(!advance&&position!=world.checkpointSequence())||
            position==world.checkpointSequence()&&!sha.equals(world.checkpointSha()))
            throw new IOException("Stale or conflicting replicated checkpoint");
    }
    private static void exactLease(World world,JsonObject args,String actor)throws IOException{
        Lease lease=world.lease();
        if(lease==null||nonnegative(args,"epoch")!=world.epoch()||!lease.holderId().equals(actor)||
            !lease.id().equals(WorldAuthority.uuid(ProfileFiles.string(args,"leaseId",36)))||
            !lease.sessionId().equals(WorldAuthority.uuid(ProfileFiles.string(args,"sessionId",36))))
            throw new IOException("Foreign or stale host lease");
    }
    private void persist(Map<String,World> proposed)throws IOException{
        if(sequence==Long.MAX_VALUE)throw new IOException("Coordinator storage generation exhausted");
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("domain",STATE);
        body.addProperty("nodeId",node.profile().id());body.addProperty("generation",sequence+1);JsonObject rows=new JsonObject();
        for(String id:new TreeSet<>(proposed.keySet()))rows.add(id,encode(proposed.get(id)));body.add("worlds",rows);
        JsonObject binding=new JsonObject();binding.addProperty("schema",1);binding.addProperty("domain",STATE_SIGNATURE);
        binding.addProperty("nodeId",node.profile().id());binding.addProperty("generation",sequence+1);binding.addProperty("sha256",WorldAuthority.hash(ProfileFiles.bytes(body)));
        JsonObject document=body.deepCopy();document.add("signature",JSON.toJsonTree(node.sign(Purpose.SERVICE_RESPONSE,ProfileFiles.bytes(binding))));
        if(ProfileFiles.bytes(document).length>FILE_LIMIT)throw new IOException("Coordinator storage byte limit");
        ProfileFiles.atomic(file,document,Files.exists(file,LinkOption.NOFOLLOW_LINKS));
        worlds=Map.copyOf(proposed);sequence++;
    }
    private JsonObject encode(World world)throws IOException{
        JsonObject row=new JsonObject();row.add("certificate",WorldAuthority.certificateToJson(world.certificate()));
        row.add("policy",JSON.toJsonTree(world.policy().proof()));row.addProperty("epoch",world.epoch());
        row.addProperty("checkpointSequence",world.checkpointSequence());row.addProperty("checkpointSha256",world.checkpointSha());
        JsonObject lease=world.lease()==null?noLease():JSON.toJsonTree(world.lease()).getAsJsonObject();
        if(world.lease()!=null)lease.addProperty("present",true);row.add("lease",lease);JsonObject receipts=new JsonObject();
        for(String key:new TreeSet<>(world.seen().keySet()))receipts.add(key,JSON.toJsonTree(world.seen().get(key)));
        row.add("seen",receipts);return row;
    }
    private void load()throws IOException{
        JsonObject document=ProfileFiles.read(file,FILE_LIMIT);
        ProfileFiles.fields(document,Set.of("schema","domain","nodeId","generation","worlds","signature"),Set.of());
        if(WorldAuthority.integer(document,"schema")!=1||!STATE.equals(ProfileFiles.string(document,"domain",100))||
            !node.profile().id().equals(ProfileFiles.string(document,"nodeId",64)))throw new IOException("Foreign coordinator storage");
        long generation=nonnegative(document,"generation");
        SignedPayload signature=WorldAuthority.signedFromJson(object(document,"signature"));
        if(!IdentityStore.verify(signature,Purpose.SERVICE_RESPONSE)||!signature.signer().id().equals(node.profile().id()))throw new IOException("Coordinator storage signature invalid");
        JsonObject binding=ProfileFiles.parse(IdentityStore.decodeData(signature),4096),body=document.deepCopy();body.remove("signature");
        ProfileFiles.fields(binding,Set.of("schema","domain","nodeId","generation","sha256"),Set.of());
        if(WorldAuthority.integer(binding,"schema")!=1||!STATE_SIGNATURE.equals(ProfileFiles.string(binding,"domain",100))||
            !node.profile().id().equals(ProfileFiles.string(binding,"nodeId",64))||nonnegative(binding,"generation")!=generation||
            !WorldAuthority.hash(ProfileFiles.bytes(body)).equals(ProfileFiles.string(binding,"sha256",64)))
            throw new IOException("Coordinator storage digest/generation mismatch");
        JsonObject rows=object(document,"worlds");if(rows.size()>MAX_WORLDS)throw new IOException("Coordinator authority count exceeds cap");
        Map<String,World> parsed=new HashMap<>();Set<UUID> labels=new HashSet<>();
        for(var entry:rows.entrySet()){
            String id=authorityId(entry.getKey());if(!entry.getValue().isJsonObject())throw new IOException("Invalid stored world");
            JsonObject row=entry.getValue().getAsJsonObject();
            ProfileFiles.fields(row,Set.of("certificate","policy","epoch","checkpointSequence","checkpointSha256","lease","seen"),Set.of());
            var certificate=WorldAuthority.certificateFromJson(object(row,"certificate"));var root=WorldAuthority.verify(certificate);
            if(!id.equals(certificate.authorityId())||!root.coordinatorId().equals(node.profile().id())||!labels.add(root.islandId()))throw new IOException("Foreign or duplicated stored authority");
            var policy=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(object(row,"policy")));
            long epoch=nonnegative(row,"epoch"),position=nonnegative(row,"checkpointSequence");String sha=WorldAuthority.digest(ProfileFiles.string(row,"checkpointSha256",64));
            Lease lease=null;
            {
            JsonObject value=object(row,"lease");
            if(bool(value,"present")){
                fields(value,"present","holderId","sessionId","id","issuedAt","ttlMs","revoked");
                long ttl=WorldAuthority.integer(value,"ttlMs");if(ttl<5000||ttl>MAX_TTL_MS||epoch==0)throw new IOException("Invalid stored lease lifetime/epoch");
                if(!value.get("revoked").isJsonPrimitive()||!value.getAsJsonPrimitive("revoked").isBoolean())throw new IOException("Invalid stored lease revocation");
                lease=new Lease(WorldAuthority.identity(ProfileFiles.string(value,"holderId",64)),WorldAuthority.uuid(ProfileFiles.string(value,"sessionId",36)),
                    WorldAuthority.uuid(ProfileFiles.string(value,"id",36)),WorldAuthority.time(WorldAuthority.integer(value,"issuedAt")),ttl,value.get("revoked").getAsBoolean());
                if(!lease.revoked())WorldAuthority.authorizeHost(policy,lease.holderId(),lease.issuedAt());
                quarantine=true;
            }else fields(value,"present");
            }
            JsonObject receiptRows=object(row,"seen");if(receiptRows.size()>MAX_SEEN)throw new IOException("Stored receipt cap");
            Map<String,Seen> seen=new HashMap<>();
            for(var receipt:receiptRows.entrySet()){
                String key=receipt.getKey();int split=key.indexOf(':');if(split<0)throw new IOException("Invalid stored receipt key");
                WorldAuthority.identity(key.substring(0,split));WorldAuthority.uuid(key.substring(split+1));
                if(!receipt.getValue().isJsonObject())throw new IOException("Invalid stored receipt");
                JsonObject value=receipt.getValue().getAsJsonObject();fields(value,"digest","until");
                seen.put(key,new Seen(WorldAuthority.digest(ProfileFiles.string(value,"digest",64)),WorldAuthority.time(WorldAuthority.integer(value,"until"))));
            }
            parsed.put(id,new World(certificate,policy,epoch,lease,position,sha,seen));
        }
        worlds=Map.copyOf(parsed);sequence=generation;
    }
    private static JsonObject noLease(){JsonObject value=new JsonObject();value.addProperty("present",false);return value;}
    private static boolean bool(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isBoolean())throw new IOException("Missing authority boolean");return entry.getAsBoolean();}
    private static void require(World world)throws IOException{if(world==null)throw new IOException("Unknown world authority");}
    private static String authorityId(String value)throws IOException{if(value==null||!value.matches("dwa1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid authority root");return value;}
    private static long nonnegative(JsonObject object,String key)throws IOException{long value=WorldAuthority.integer(object,key);if(value<0)throw new IOException("Negative authority counter");return value;}
    private static JsonObject object(JsonObject value,String key)throws IOException{JsonElement entry=value.get(key);if(entry==null||!entry.isJsonObject())throw new IOException("Missing authority object: "+key);return entry.getAsJsonObject();}
    private static void fields(JsonObject object,String...keys)throws IOException{ProfileFiles.fields(object,Set.of(keys),Set.of());}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;running.clear();lock.release();channel.close();}
}
