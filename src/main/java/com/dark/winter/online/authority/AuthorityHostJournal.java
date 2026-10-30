// Per-authority durable host/confirmed-checkpoint journal in the explicitly selected private identity base.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;

/**
 * Per-authority durable host/confirmed-checkpoint journal in the explicitly selected private identity base.
 * No identity keys, world contents, archive, endpoint, timer, background worker or game host are opened.
 * A pending claim never grants a lease. Plain status must already be freshly verified by the pinned-node client.
 */
public final class AuthorityHostJournal implements AutoCloseable {
    private static final int LIMIT=16384;
    private final Path base,file,lockFile;
    private final WorldAuthority.Certificate certificate;
    private final WorldAuthority.Genesis genesis;
    private final String certificateSha;
    private final FileChannel channel;
    private final FileLock lock;
    private final Path lockWitness;
    private Entry observed;
    private byte[] observedBytes;
    private Comparison compared;
    private LeaseFence.Fence liveConfirmed;
    private String comparedCandidate;
    private boolean closed;
    public record Confirmed(String worldSha256,String versionId,UUID sessionId,long epoch,
                            long checkpointSequence,String checkpointSha256,String holderId,UUID leaseId) {}
    public record Pending(String worldSha256,String versionId,UUID nextSessionId,long expectedNodeEpoch,
                          long checkpointSequence,String checkpointSha256) {}
    /** At least one of confirmed/pending is present. Neither record contains a raw filesystem path. */
    public record PendingCheckpoint(String worldSha256,String versionId,UUID sessionId,long epoch,String holderId,UUID leaseId,
                                    long previousSequence,String previousSha256,long nextSequence,String nextSha256) {}
    public record Entry(long revision,Confirmed confirmed,Pending pending,PendingCheckpoint pendingCheckpoint) {}
    private record Comparison(String currentWorld,long epoch,long sequence,String sha,boolean recoveredPending) {}
    private record Node(long epoch,long sequence,String sha,boolean present,boolean active,long remaining,UUID session,String holder,UUID lease,WorldAuthority.Policy policy,long issuedAt) {}

    private AuthorityHostJournal(Path base,Path file,Path lockFile,WorldAuthority.Certificate certificate,
                                 WorldAuthority.Genesis genesis,String certificateSha,FileChannel channel,FileLock lock,Path lockWitness) {
        this.base=base;this.file=file;this.lockFile=lockFile;this.certificate=certificate;this.genesis=genesis;
        this.certificateSha=certificateSha;this.channel=channel;this.lock=lock;this.lockWitness=lockWitness;
    }
    /** Holds a separate OS lock for this exact realm until close; never borrows the identity key-store lock. */
    public static AuthorityHostJournal open(Path explicitIdentityBase,WorldAuthority.Certificate certificate)throws IOException {
        Objects.requireNonNull(explicitIdentityBase);WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
        Path requested=exact(explicitIdentityBase);safe(requested);ProfileFiles.requireOutsideWorld(requested);
        Path identity=ProfileFiles.directory(requested);safe(identity);
        Path base=identity.resolve("authority-host-journals");safe(base);ProfileFiles.directory(base);safe(base);
        String realm=certificate.authorityId();
        Path file=base.resolve(realm+".json"),lockFile=base.resolve("."+realm+".lock");safe(file);safe(lockFile);
        if(Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(lockFile,LinkOption.NOFOLLOW_LINKS)||Files.size(lockFile)!=0))
            throw new IOException("Invalid authority host journal lock");
        Path witness=base.resolve(".host-journal-"+UUID.randomUUID()+".live");safe(witness);
        FileChannel channel=null;FileLock guard=null;
        try {
            if(!Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS))Files.createFile(lockFile);
            ProfileFiles.protect(lockFile,false);
            // Windows BasicFileAttributes.fileKey() is legitimately null. A private hard-link witness
            // lets Files.isSameFile compare actual volume/file identity without unsupported reflection.
            Files.createLink(witness,lockFile);
            channel=FileChannel.open(lockFile,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try{guard=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("Authority host journal is already open");}
            if(guard==null)throw new IOException("Authority host journal is already open");
            if(!Files.isSameFile(lockFile,witness))throw new IOException("Authority host journal lock was replaced during open");
            AuthorityHostJournal result=new AuthorityHostJournal(base,file,lockFile,certificate,root,
                    WorldAuthority.hash(ProfileFiles.bytes(WorldAuthority.certificateToJson(certificate))),channel,guard,witness);
            result.observedBytes=readBytes(file);
            if(result.observedBytes!=null)result.observed=result.decode(result.observedBytes);
            return result;
        }catch(IOException|RuntimeException failure){
            if(guard!=null)try{guard.release();}catch(IOException ignored){}
            if(channel!=null)try{channel.close();}catch(IOException ignored){}
            try{Files.deleteIfExists(witness);}catch(IOException ignored){}
            throw safeFailure("Authority host journal could not be opened",failure);
        }
    }
    public synchronized Optional<Entry> snapshot()throws IOException {check();return Optional.ofNullable(observed);}
    /**
     * This is a consistency check only; successful return is not hosting permission and does not write.
     * Root must supply the body of a fresh, pinned SERVICE_RESPONSE, never a raw directory/endpoint JSON.
     */
    public synchronized void compareNodeStatus(JsonObject verifiedStatus,Path currentWorld)throws IOException {
        check();compared=null;comparedCandidate=null;
        String world=worldHash(currentWorld);Node node=node(verifiedStatus);
        if(observed==null) {
            if(node.epoch()!=0||node.sequence()!=0||!node.sha().equals(genesis.genesisSha256())||node.present())
                throw new IOException("No local journal for an already advanced or leased authority");
            compared=new Comparison(world,0,0,node.sha(),false);return;
        }
        Confirmed settled=observed.confirmed();Pending pending=observed.pending();PendingCheckpoint checkpoint=observed.pendingCheckpoint();
        if(checkpoint!=null&&node.epoch()==checkpoint.epoch()&&node.sequence()==checkpoint.nextSequence()&&node.sha().equals(checkpoint.nextSha256())) {
            if(!world.equals(checkpoint.worldSha256())||!node.present()||!checkpoint.sessionId().equals(node.session())
                    ||!checkpoint.holderId().equals(node.holder())||!checkpoint.leaseId().equals(node.lease()))
                throw new IOException("Accepted pending checkpoint requires its exact target world and retained Node session");
            compared=new Comparison(world,node.epoch(),node.sequence(),node.sha(),!node.active()&&node.remaining()==0);return;
        }
        String bound=checkpoint!=null&&node.sequence()==checkpoint.nextSequence()&&node.sha().equals(checkpoint.nextSha256())
                ?checkpoint.worldSha256():settled!=null?settled.worldSha256():pending.worldSha256();
        if(!bound.equals(world))throw new IOException("Selected world does not match the durable external host binding");
        if(settled!=null&&node.epoch()==settled.epoch()&&node.sequence()==settled.checkpointSequence()&&node.sha().equals(settled.checkpointSha256())) {
            if(node.present()&&(!settled.sessionId().equals(node.session())||!settled.holderId().equals(node.holder())||!settled.leaseId().equals(node.lease())))throw new IOException("Node session differs from the confirmed host journal");
            compared=new Comparison(world,node.epoch(),node.sequence(),node.sha(),false);return;
        }
        if(pending!=null&&node.sequence()==pending.checkpointSequence()&&node.sha().equals(pending.checkpointSha256())) {
            // A committed claim MUST advance the Node epoch. The exact unchanged epoch and
            // checkpoint therefore prove this request never committed, even when the old expired
            // lease record is retained. Its blocking time must be zero before any fresh retry.
            if(node.epoch()==pending.expectedNodeEpoch()&&!node.active()&&node.remaining()==0) {
                compared=new Comparison(world,node.epoch(),node.sequence(),node.sha(),false);return;
            }
            // The claim may have committed before cancellation. Its *exact* retained session must now be expired.
            // An absent lease loses its SID; it cannot prove which claimant advanced this epoch.
            if(pending.expectedNodeEpoch()<Long.MAX_VALUE&&node.epoch()==pending.expectedNodeEpoch()+1
                    &&node.present()&&!node.active()&&node.remaining()==0&&pending.nextSessionId().equals(node.session())) {
                compared=new Comparison(world,node.epoch(),node.sequence(),node.sha(),true);return;
            }
        }
        throw new IOException("Node epoch/checkpoint is ahead, stale or unknown to the external host journal");
    }
    /**
     * First candidate content baseline only. Requires a pre-created opaque standby proof, a fresh verified
     * inactive Node status and an empty journal. It publishes no entry, permit or host confirmation.
     */
    public synchronized void compareCandidateStatus(JsonObject verifiedCurrentNodeStatus,AuthorityStandbyCheckpoint baseline)throws IOException {
        check();
        if(observed!=null)throw new IOException("Candidate baseline cannot replace an existing host journal");
        compared=null;comparedCandidate=null;
        if(baseline==null||!certificate.authorityId().equals(baseline.authorityId())||!genesis.ownerId().equals(baseline.ownerId())
                ||!certificateSha.equals(baseline.certificateSha256()))throw new IOException("Candidate baseline belongs to another immutable owner root");
        String world=baseline.checkedWorld();Node node=node(verifiedCurrentNodeStatus);
        if(node.active()||node.remaining()!=0||node.epoch()<baseline.epoch()
                ||node.sequence()!=baseline.checkpointSequence()||!node.sha().equals(baseline.checkpointSha256())
                ||node.policy().revision()<baseline.minimumPolicyRevision()
                ||node.policy().revision()==baseline.minimumPolicyRevision()&&!node.policy().sha256().equals(baseline.policySha256()))
            throw new IOException("Candidate Node status is active, stale or differs from verified standby content");
        WorldAuthority.authorizeHost(node.policy(),baseline.candidateId(),node.issuedAt());
        compared=new Comparison(world,node.epoch(),node.sequence(),node.sha(),false);comparedCandidate=baseline.candidateId();
    }
    /** Persist BEFORE the actual claim request. This pending record is never a lease or a confirmed checkpoint. */
    public synchronized void prepared(Path targetWorld,String immutableVersionId,UUID nextSessionId,long expectedNodeEpoch,
                                      long checkpointSequence,String checkpointSha256)throws IOException {
        check();String world=worldHash(targetWorld),version=version(immutableVersionId);
        uuid(nextSessionId);floor(expectedNodeEpoch);floor(checkpointSequence);WorldAuthority.digest(checkpointSha256);
        if(expectedNodeEpoch==Long.MAX_VALUE)throw new IOException("Authority host epoch exhausted");
        Comparison state=compared;
        if(state==null||state.epoch()!=expectedNodeEpoch||state.sequence()!=checkpointSequence||!state.sha().equals(checkpointSha256))
            throw new IOException("Prepare requires the matching fresh verified Node status first");
        Confirmed settled=observed==null?null:observed.confirmed();
        PendingCheckpoint checkpoint=observed==null?null:observed.pendingCheckpoint();
        if(settled!=null&&settled.sessionId().equals(nextSessionId)||checkpoint!=null&&checkpoint.sessionId().equals(nextSessionId)
                ||observed!=null&&observed.pending()!=null&&observed.pending().nextSessionId().equals(nextSessionId))
            throw new IOException("A new authority claim requires a fresh native session UUID");
        if(checkpoint!=null) {
            if(!state.recoveredPending()||!world.equals(checkpoint.worldSha256())||!version.equals(checkpoint.versionId())
                    ||checkpointSequence!=checkpoint.nextSequence()||!checkpointSha256.equals(checkpoint.nextSha256()))
                throw new IOException("Pending checkpoint requires its exact verified expired target before a new claim");
            // Content recovery only: retain both old settled authority and pending checkpoint until a NEW Fence arrives.
            commit(settled,new Pending(world,version,nextSessionId,expectedNodeEpoch,checkpointSequence,checkpointSha256),checkpoint);
            compared=null;return;
        }
        String bound=settled==null?state.currentWorld():settled.worldSha256();
        if(!world.equals(bound)) {
            if(settled==null||liveConfirmed==null||!matches(settled,liveConfirmed))
                throw new IOException("A new world binding requires a same-instance confirmed live fence");
            liveConfirmed.requireUsable();
            if(!state.currentWorld().equals(settled.worldSha256()))
                throw new IOException("Controlled world transition differs from the verified old binding");
        }
        Pending pending=new Pending(world,version,nextSessionId,expectedNodeEpoch,checkpointSequence,checkpointSha256);
        commit(settled,pending);compared=null;
    }
    /**
     * Persist the exact immutable checkpoint and controlled target BEFORE the renewal CAS.
     * An interrupted response cannot make the previous world silently override a newer accepted Node checkpoint.
     */
    public synchronized void checkpointPrepared(Path targetWorld,String immutableVersionId,LeaseFence.Fence oldFence,
                                                long nextSequence,String newSha256)throws IOException {
        check();if(oldFence==null)throw new IOException("Missing actual checkpoint baseline fence");
        oldFence.requireUsable();WorldAuthority.digest(newSha256);floor(nextSequence);
        String world=worldHash(targetWorld),version=version(immutableVersionId);
        Confirmed settled=observed==null?null:observed.confirmed();
        if(settled==null||observed.pending()!=null||observed.pendingCheckpoint()!=null||!certificate.authorityId().equals(oldFence.authorityId())
                ||!matches(settled,oldFence)||liveConfirmed==null||!matches(settled,liveConfirmed))
            throw new IOException("Checkpoint preparation requires the same-instance settled actual fence and no unresolved request");
        liveConfirmed.requireUsable();
        if(nextSequence<=settled.checkpointSequence())throw new IOException("Prepared checkpoint sequence must strictly advance");
        PendingCheckpoint pending=new PendingCheckpoint(world,version,oldFence.sessionId(),oldFence.epoch(),oldFence.holderId(),oldFence.leaseId(),
                oldFence.checkpointSequence(),oldFence.checkpointSha256(),nextSequence,newSha256);
        oldFence.requireUsable();commit(settled,null,pending);compared=null;
    }
    /** Actual opaque, usable Node fence only. Claims match pending exactly; renewals retain the settled native session. */
    public synchronized void confirmed(Path targetWorld,String immutableVersionId,LeaseFence.Fence actualFence)throws IOException {
        check();if(actualFence==null)throw new IOException("Missing actual verified host fence");
        actualFence.requireUsable();
        if(!certificate.authorityId().equals(actualFence.authorityId()))throw new IOException("Foreign authority fence");
        if(comparedCandidate!=null&&!comparedCandidate.equals(actualFence.holderId()))throw new IOException("Actual claim Fence belongs to another standby candidate");
        String world=worldHash(targetWorld),version=version(immutableVersionId);
        Confirmed old=observed==null?null:observed.confirmed();Pending pending=observed==null?null:observed.pending();
        PendingCheckpoint checkpoint=observed==null?null:observed.pendingCheckpoint();
        if(checkpoint!=null&&pending==null) {
            if(!world.equals(checkpoint.worldSha256())||!version.equals(checkpoint.versionId())||!actualFence.sessionId().equals(checkpoint.sessionId())
                    ||actualFence.epoch()!=checkpoint.epoch()||!actualFence.leaseId().equals(checkpoint.leaseId())||!actualFence.holderId().equals(checkpoint.holderId())
                    ||actualFence.checkpointSequence()!=checkpoint.nextSequence()||!actualFence.checkpointSha256().equals(checkpoint.nextSha256()))
                throw new IOException("Accepted renewal does not match the exact durable checkpoint target");
        }else if(pending!=null) {
            if(!world.equals(pending.worldSha256())||!version.equals(pending.versionId())
                    ||actualFence.epoch()!=pending.expectedNodeEpoch()+1||!actualFence.sessionId().equals(pending.nextSessionId())
                    ||actualFence.checkpointSequence()!=pending.checkpointSequence()||!actualFence.checkpointSha256().equals(pending.checkpointSha256()))
                throw new IOException("Accepted claim does not match the exact durable prepared request");
        }else {
            if(old==null||!world.equals(old.worldSha256())||!old.sessionId().equals(actualFence.sessionId())
                    ||old.epoch()!=actualFence.epoch()||!old.leaseId().equals(actualFence.leaseId())||!old.holderId().equals(actualFence.holderId()))
                throw new IOException("Renewal does not match the settled native host session");
            if(actualFence.checkpointSequence()!=old.checkpointSequence()
                    ||!actualFence.checkpointSha256().equals(old.checkpointSha256())||!version.equals(old.versionId()))
                throw new IOException("Renewal checkpoint/version is stale or conflicting");
        }
        Confirmed next=new Confirmed(world,version,actualFence.sessionId(),actualFence.epoch(),actualFence.checkpointSequence(),
                actualFence.checkpointSha256(),actualFence.holderId(),actualFence.leaseId());
        actualFence.requireUsable();
        if(!next.equals(old)||pending!=null||checkpoint!=null)commit(next,null);
        liveConfirmed=actualFence;compared=null;comparedCandidate=null;
    }
    private static boolean matches(Confirmed entry,LeaseFence.Fence fence) {
        return entry.epoch()==fence.epoch()&&entry.sessionId().equals(fence.sessionId())&&entry.leaseId().equals(fence.leaseId())
                &&entry.holderId().equals(fence.holderId())&&entry.checkpointSequence()==fence.checkpointSequence()
                &&entry.checkpointSha256().equals(fence.checkpointSha256());
    }
    private Node node(JsonObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Missing verified Node status");
        byte[] encoded=ProfileFiles.bytes(supplied);
        JsonObject value=ProfileFiles.parse(encoded,65536);
        ProfileFiles.fields(value,Set.of("ok","schema","domain","requestId","issuedAt","operation","authorityId","epoch","policyRevision","policy",
                "checkpointSequence","checkpointSha256","quarantined","lease"),Set.of());
        if(!bool(value,"ok")||WorldAuthority.integer(value,"schema")!=1
                ||!AuthorityLeaseStore.RECEIPT.equals(ProfileFiles.string(value,"domain",100))
                ||!ProfileFiles.string(value,"operation",32).equals("authority_status")
                ||!ProfileFiles.string(value,"authorityId",64).equals(certificate.authorityId()))
            throw new IOException("Wrong verified status authority/domain/operation");
        WorldAuthority.uuid(ProfileFiles.string(value,"requestId",36));long issuedAt=WorldAuthority.time(WorldAuthority.integer(value,"issuedAt"));
        long epoch=WorldAuthority.integer(value,"epoch"),sequence=WorldAuthority.integer(value,"checkpointSequence"),policy=WorldAuthority.integer(value,"policyRevision");
        floor(epoch);floor(sequence);floor(policy);
        if(!value.get("policy").isJsonObject())throw new IOException("Missing signed Node owner policy");
        WorldAuthority.Policy verified=WorldAuthority.verifyPolicy(certificate,WorldAuthority.signedFromJson(value.getAsJsonObject("policy")));
        if(verified.revision()!=policy||bool(value,"quarantined"))throw new IOException("Coordinator policy mismatch or restart quarantine");
        String sha=WorldAuthority.digest(ProfileFiles.string(value,"checkpointSha256",64));
        if(!value.get("lease").isJsonObject())throw new IOException("Missing Node lease status");
        JsonObject lease=value.getAsJsonObject("lease");boolean present=bool(lease,"present");
        if(!present) {
            ProfileFiles.fields(lease,Set.of("present"),Set.of());
            return new Node(epoch,sequence,sha,false,false,0,null,null,null,verified,issuedAt);
        }
        ProfileFiles.fields(lease,Set.of("present","holderId","sessionId","leaseId","remainingMs","revoked","active"),Set.of());
        String holder=WorldAuthority.identity(ProfileFiles.string(lease,"holderId",64));
        UUID session=WorldAuthority.uuid(ProfileFiles.string(lease,"sessionId",36)),leaseId=WorldAuthority.uuid(ProfileFiles.string(lease,"leaseId",36));
        long remaining=WorldAuthority.integer(lease,"remainingMs");boolean active=bool(lease,"active"),revoked=bool(lease,"revoked");
        if(remaining<0||remaining>AuthorityLeaseStore.MAX_TTL_MS||active&&(remaining==0||revoked)||!active&&!revoked&&remaining>0)
            throw new IOException("Inconsistent Node lease status");
        return new Node(epoch,sequence,sha,true,active,remaining,session,holder,leaseId,verified,issuedAt);
    }
    private void check()throws IOException {
        if(closed||!lock.isValid()||!channel.isOpen())throw new IOException("Authority host journal is closed");
        safe(base);safe(file);safe(lockFile);safe(lockWitness);ProfileFiles.requireOutsideWorld(base);
        if(!Files.isRegularFile(lockFile,LinkOption.NOFOLLOW_LINKS)||Files.size(lockFile)!=0||!Files.isRegularFile(lockWitness,LinkOption.NOFOLLOW_LINKS)||!Files.isSameFile(lockFile,lockWitness))
            throw new IOException("Authority host journal lifetime lock was replaced");
        byte[] current=readBytes(file);
        if(!Arrays.equals(observedBytes,current))throw new IOException("Authority host journal was replaced, deleted or changed outside its exclusive owner");
    }
    private Entry decode(byte[] bytes)throws IOException {
        JsonObject value=ProfileFiles.parse(bytes,LIMIT);
        if(!Arrays.equals(bytes,ProfileFiles.bytes(value)))throw new IOException("Noncanonical authority host journal");
        ProfileFiles.fields(value,Set.of("schema","authorityId","islandId","ownerId","nodeId","certificateSha256","revision","confirmed","pending","pendingCheckpoint","sha256"),Set.of());
        JsonObject body=value.deepCopy();String expected=WorldAuthority.digest(ProfileFiles.string(body,"sha256",64));body.remove("sha256");
        if(!expected.equals(WorldAuthority.hash(ProfileFiles.bytes(body)))||WorldAuthority.integer(value,"schema")!=1)
            throw new IOException("Authority host journal checksum/schema mismatch");
        if(!certificate.authorityId().equals(ProfileFiles.string(value,"authorityId",64))
                ||!genesis.islandId().equals(WorldAuthority.uuid(ProfileFiles.string(value,"islandId",36)))
                ||!genesis.ownerId().equals(ProfileFiles.string(value,"ownerId",64))||!genesis.coordinatorId().equals(ProfileFiles.string(value,"nodeId",64))
                ||!certificateSha.equals(ProfileFiles.string(value,"certificateSha256",64)))
            throw new IOException("Authority host journal belongs to a different immutable owner root");
        long revision=WorldAuthority.integer(value,"revision");if(revision<1)throw new IOException("Invalid authority host journal revision");
        Confirmed confirmed=null;Pending pending=null;
        if(object(value,"confirmed").size()!=0) {
            JsonObject row=object(value,"confirmed");
            ProfileFiles.fields(row,Set.of("worldSha256","versionId","sessionId","epoch","checkpointSequence","checkpointSha256","holderId","leaseId"),Set.of());
            confirmed=new Confirmed(WorldAuthority.digest(ProfileFiles.string(row,"worldSha256",64)),version(ProfileFiles.string(row,"versionId",36)),
                    WorldAuthority.uuid(ProfileFiles.string(row,"sessionId",36)),WorldAuthority.integer(row,"epoch"),WorldAuthority.integer(row,"checkpointSequence"),
                    WorldAuthority.digest(ProfileFiles.string(row,"checkpointSha256",64)),WorldAuthority.identity(ProfileFiles.string(row,"holderId",64)),
                    WorldAuthority.uuid(ProfileFiles.string(row,"leaseId",36)));
            if(confirmed.epoch()<1)throw new IOException("Invalid confirmed host epoch");floor(confirmed.checkpointSequence());
        }
        if(object(value,"pending").size()!=0) {
            JsonObject row=object(value,"pending");
            ProfileFiles.fields(row,Set.of("worldSha256","versionId","nextSessionId","expectedNodeEpoch","checkpointSequence","checkpointSha256"),Set.of());
            pending=new Pending(WorldAuthority.digest(ProfileFiles.string(row,"worldSha256",64)),version(ProfileFiles.string(row,"versionId",36)),
                    WorldAuthority.uuid(ProfileFiles.string(row,"nextSessionId",36)),WorldAuthority.integer(row,"expectedNodeEpoch"),
                    WorldAuthority.integer(row,"checkpointSequence"),WorldAuthority.digest(ProfileFiles.string(row,"checkpointSha256",64)));
            floor(pending.expectedNodeEpoch());floor(pending.checkpointSequence());
            if(pending.expectedNodeEpoch()==Long.MAX_VALUE)throw new IOException("Exhausted prepared host epoch");
        }
        PendingCheckpoint checkpoint=null;
        if(object(value,"pendingCheckpoint").size()!=0) {
            JsonObject row=object(value,"pendingCheckpoint");
            ProfileFiles.fields(row,Set.of("worldSha256","versionId","sessionId","epoch","holderId","leaseId","previousSequence","previousSha256","nextSequence","nextSha256"),Set.of());
            checkpoint=new PendingCheckpoint(WorldAuthority.digest(ProfileFiles.string(row,"worldSha256",64)),version(ProfileFiles.string(row,"versionId",36)),
                    WorldAuthority.uuid(ProfileFiles.string(row,"sessionId",36)),WorldAuthority.integer(row,"epoch"),WorldAuthority.identity(ProfileFiles.string(row,"holderId",64)),
                    WorldAuthority.uuid(ProfileFiles.string(row,"leaseId",36)),WorldAuthority.integer(row,"previousSequence"),
                    WorldAuthority.digest(ProfileFiles.string(row,"previousSha256",64)),WorldAuthority.integer(row,"nextSequence"),
                    WorldAuthority.digest(ProfileFiles.string(row,"nextSha256",64)));
            if(confirmed==null||checkpoint.epoch()!=confirmed.epoch()||!checkpoint.sessionId().equals(confirmed.sessionId())
                    ||!checkpoint.leaseId().equals(confirmed.leaseId())||!checkpoint.holderId().equals(confirmed.holderId())
                    ||checkpoint.previousSequence()!=confirmed.checkpointSequence()||!checkpoint.previousSha256().equals(confirmed.checkpointSha256())
                    ||checkpoint.nextSequence()<=checkpoint.previousSequence())throw new IOException("Prepared checkpoint conflicts with its confirmed native session");
        }
        if(confirmed==null&&pending==null)throw new IOException("Empty published host journal");
        if(checkpoint!=null&&pending!=null&&(!pending.worldSha256().equals(checkpoint.worldSha256())||!pending.versionId().equals(checkpoint.versionId())
                ||pending.expectedNodeEpoch()<checkpoint.epoch()||pending.checkpointSequence()!=checkpoint.nextSequence()||!pending.checkpointSha256().equals(checkpoint.nextSha256())))
            throw new IOException("Recovered checkpoint claim conflicts with its exact prepared content");
        if(confirmed!=null&&pending!=null&&checkpoint==null&&(pending.expectedNodeEpoch()<confirmed.epoch()||pending.checkpointSequence()!=confirmed.checkpointSequence()
                ||!pending.checkpointSha256().equals(confirmed.checkpointSha256())))
            throw new IOException("Prepared state conflicts with the settled host checkpoint");
        return new Entry(revision,confirmed,pending,checkpoint);
    }
    private void commit(Confirmed confirmed,Pending pending)throws IOException {commit(confirmed,pending,null);}
    private void commit(Confirmed confirmed,Pending pending,PendingCheckpoint checkpoint)throws IOException {
        check();long revision=observed==null?1:observed.revision()+1;
        if(observed!=null&&observed.revision()==Long.MAX_VALUE)throw new IOException("Authority host journal revision exhausted");
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("authorityId",certificate.authorityId());
        value.addProperty("islandId",genesis.islandId().toString());value.addProperty("ownerId",genesis.ownerId());value.addProperty("nodeId",genesis.coordinatorId());
        value.addProperty("certificateSha256",certificateSha);value.addProperty("revision",revision);
        // Empty objects are explicit absence tags. Shared canonical Gson intentionally omits null-valued members.
        value.add("confirmed",confirmed==null?new JsonObject():json(confirmed));value.add("pending",pending==null?new JsonObject():json(pending));
        value.add("pendingCheckpoint",checkpoint==null?new JsonObject():json(checkpoint));
        value.addProperty("sha256",WorldAuthority.hash(ProfileFiles.bytes(value)));byte[] bytes=ProfileFiles.bytes(value);
        Entry next=decode(bytes);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Authority host journal commit canceled");
        // Full bytes, not timestamps/size alone, are compared immediately before atomic forced publication.
        if(!Arrays.equals(observedBytes,readBytes(file)))throw new IOException("Authority host journal changed during update");
        try{ProfileFiles.atomic(file,value,observedBytes!=null);}catch(IOException failure){throw safeFailure("Authority host journal publication failed",failure);}
        observed=next;observedBytes=bytes;
    }
    private static JsonObject json(Confirmed value) {
        JsonObject row=new JsonObject();row.addProperty("worldSha256",value.worldSha256());row.addProperty("versionId",value.versionId());
        row.addProperty("sessionId",value.sessionId().toString());row.addProperty("epoch",value.epoch());row.addProperty("checkpointSequence",value.checkpointSequence());
        row.addProperty("checkpointSha256",value.checkpointSha256());row.addProperty("holderId",value.holderId());row.addProperty("leaseId",value.leaseId().toString());return row;
    }
    private static JsonObject json(Pending value) {
        JsonObject row=new JsonObject();row.addProperty("worldSha256",value.worldSha256());row.addProperty("versionId",value.versionId());
        row.addProperty("nextSessionId",value.nextSessionId().toString());row.addProperty("expectedNodeEpoch",value.expectedNodeEpoch());
        row.addProperty("checkpointSequence",value.checkpointSequence());row.addProperty("checkpointSha256",value.checkpointSha256());return row;
    }
    private static JsonObject json(PendingCheckpoint value) {
        JsonObject row=new JsonObject();row.addProperty("worldSha256",value.worldSha256());row.addProperty("versionId",value.versionId());
        row.addProperty("sessionId",value.sessionId().toString());row.addProperty("epoch",value.epoch());row.addProperty("holderId",value.holderId());
        row.addProperty("leaseId",value.leaseId().toString());row.addProperty("previousSequence",value.previousSequence());row.addProperty("previousSha256",value.previousSha256());
        row.addProperty("nextSequence",value.nextSequence());row.addProperty("nextSha256",value.nextSha256());return row;
    }
    private static Path exact(Path requested)throws IOException {
        if(requested==null)throw new IOException("Explicit local world/profile path required");
        Path path=requested.toAbsolutePath();
        if(!path.equals(path.normalize()))throw new IOException("Noncanonical world/profile path");
        return path;
    }
    static String worldHash(Path requested)throws IOException {
        Path path=exact(requested);safe(path);
        try {
            if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Explicit world directory is missing");
            return WorldAuthority.hash(path.toRealPath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException failure){throw safeFailure("World binding could not be resolved",failure);}
    }
    private static void safe(Path path)throws IOException {
        try {
            Path absolute=exact(path);
            if(absolute.toString().startsWith("\\\\"))throw new IOException("Remote profile/world paths are not supported");
            for(Path current=absolute;current!=null;current=current.getParent()) {
                Path leaf=current.getFileName();
                if(leaf!=null) {
                    String name=leaf.toString();
                    if(name.endsWith(".")||name.endsWith(" ")||!Normalizer.isNormalized(name,Normalizer.Form.NFC)
                            ||name.codePoints().anyMatch(c->c<32||c==127||":*?<>|\"".indexOf(c)>=0)
                            ||name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?"))
                        throw new IOException("Windows alias/reserved profile/world path");
                }
                if(Files.isSymbolicLink(current)||Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&!current.toRealPath().equals(current))
                    throw new IOException("Linked or redirected profile/world path");
            }
        }catch(IOException failure){throw safeFailure("Unsafe authority host journal path",failure);}
    }
    private static byte[] readBytes(Path path)throws IOException {
        try {
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>LIMIT)throw new IOException("Invalid authority host journal file");
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Authority host journal grew beyond its bound");return bytes;
            }
        }catch(IOException failure){throw safeFailure("Authority host journal could not be read",failure);}
    }
    private static String version(String value)throws IOException {WorldAuthority.uuid(value);return value;}
    private static void uuid(UUID value)throws IOException {if(value==null)throw new IOException("Missing native session UUID");WorldAuthority.uuid(value.toString());}
    private static void floor(long value)throws IOException {if(value<0)throw new IOException("Negative authority/checkpoint counter");}
    private static JsonObject object(JsonObject value,String key)throws IOException {JsonElement entry=value.get(key);if(entry==null||!entry.isJsonObject())throw new IOException("Missing authority host journal object");return entry.getAsJsonObject();}
    private static boolean bool(JsonObject value,String key)throws IOException {JsonElement entry=value.get(key);if(entry==null||!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isBoolean())throw new IOException("Invalid Node status boolean");return entry.getAsBoolean();}
    private static IOException safeFailure(String label,Exception failure){return new IOException(label+" ("+failure.getClass().getSimpleName()+")");}
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;compared=null;comparedCandidate=null;liveConfirmed=null;
        try{lock.release();}finally{try{channel.close();}finally{try{Files.deleteIfExists(lockWitness);}catch(IOException ignored){/* Protected zero-byte crash witness is not authority. */}}}
    }
}
