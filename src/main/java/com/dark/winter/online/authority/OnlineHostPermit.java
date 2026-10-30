// Represents verified, time-bounded permission for an attested native host.
package com.dark.winter.online.authority;

import java.io.IOException;
import java.time.Clock;
import java.util.*;

/**
 * In-memory host capability, obtained only by combining verified owner policy with
 * a fresh opaque node fence. No files, private keys, sockets, threads or native world are opened.
 * The native host must separately bind its actual local identity and pre-reserved session ID.
 */
public final class OnlineHostPermit implements AutoCloseable {
    private record State(WorldAuthority.Policy policy,LeaseFence.Fence fence){}
    public record View(String authorityId,UUID islandId,String ownerId,String nodeId,String holderId,
                       UUID nativeSessionId,UUID leaseId,long nodeLeaseEpoch,long policyRevision,
                       long checkpointSequence,String checkpointSha256,long remainingMillis,boolean closed){}
    private final WorldAuthority.Certificate certificate;
    private final WorldAuthority.Genesis root;
    private final String holder;
    private final UUID nativeSession,leaseId;
    private final long nodeLeaseEpoch;
    private final Clock clock;
    private volatile boolean closed;
    private State state;
    private OnlineHostPermit(WorldAuthority.Certificate certificate,WorldAuthority.Genesis root,String holder,
                             UUID nativeSession,LeaseFence.Fence fence,WorldAuthority.Policy policy,Clock clock) {
        this.certificate=certificate;this.root=root;this.holder=holder;this.nativeSession=nativeSession;
        leaseId=fence.leaseId();nodeLeaseEpoch=fence.epoch();this.clock=clock;state=new State(policy,fence);
    }
    public static OnlineHostPermit open(WorldAuthority.Certificate certificate,WorldAuthority.Policy verifiedPolicy,
                                        LeaseFence.Fence freshFence,String actualLocalEdID,UUID reservedNativeSession)throws IOException {
        return open(certificate,verifiedPolicy,freshFence,actualLocalEdID,reservedNativeSession,Clock.systemUTC());
    }
    /** Clock injection is for deterministic tests; the opaque fence retains its independently verified monotonic deadline. */
    public static OnlineHostPermit open(WorldAuthority.Certificate certificate,WorldAuthority.Policy verifiedPolicy,
                                        LeaseFence.Fence freshFence,String actualLocalEdID,UUID reservedNativeSession,Clock clock)throws IOException {
        if(clock==null||reservedNativeSession==null)throw new IOException("A host permit requires a clock and pre-reserved native session");
        WorldAuthority.identity(actualLocalEdID);WorldAuthority.uuid(reservedNativeSession.toString());
        WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
        State accepted=verify(certificate,root,verifiedPolicy,freshFence,actualLocalEdID,reservedNativeSession,clock);
        return new OnlineHostPermit(certificate,root,actualLocalEdID,reservedNativeSession,accepted.fence(),accepted.policy(),clock);
    }
    public String authorityId(){return certificate.authorityId();}public UUID islandId(){return root.islandId();}
    public String ownerId(){return root.ownerId();}public String nodeId(){return root.coordinatorId();}public String holderId(){return holder;}
    public UUID nativeSessionId(){return nativeSession;}public UUID leaseId(){return leaseId;}
    /** This is the coordinator CAS epoch. It is NOT a native IslandAccess/lifecycle epoch. */
    public long nodeLeaseEpoch(){return nodeLeaseEpoch;}
    public synchronized long policyRevision(){return state.policy().revision();}
    public synchronized long checkpointSequence(){return state.fence().checkpointSequence();}
    public synchronized String checkpointSha256(){return state.fence().checkpointSha256();}
    public WorldAuthority.Certificate certificate(){return certificate;}
    public synchronized WorldAuthority.Policy policy(){return state.policy();}
    public synchronized LeaseFence.Fence fence()throws IOException {requireCurrent();return state.fence();}
    public synchronized void requireUsable()throws IOException {requireCurrent();}
    public synchronized void requireUsableFor(String actualLocalEdID,UUID actualNativeSession)throws IOException {
        if(!holder.equals(actualLocalEdID)||!nativeSession.equals(actualNativeSession))throw new IOException("Host permit belongs to another actual identity/native session");
        requireCurrent();
    }
    public synchronized boolean usable(){try{requireCurrent();return true;}catch(IOException denied){return false;}}
    public synchronized long remainingMillis(){return usable()?state.fence().remainingMillis():0;}
    public synchronized View snapshot(){
        long remaining=remainingMillis();
        return new View(authorityId(),islandId(),ownerId(),nodeId(),holder,nativeSession,leaseId,nodeLeaseEpoch,
                state.policy().revision(),state.fence().checkpointSequence(),state.fence().checkpointSha256(),remaining,closed);
    }
    /**
     * An accepted renewal may update policy/checkpoint/deadline only within the exact same
     * lease/epoch/native-session/holder. Closed or once-expired capabilities cannot be resurrected.
     * Crypto occurs before the short state lock; close wins against late callbacks.
     */
    public void renew(WorldAuthority.Policy verifiedPolicy,LeaseFence.Fence freshFence)throws IOException {
        if(closed)throw new IOException("Host permit is closed");
        State candidate=verify(certificate,root,verifiedPolicy,freshFence,holder,nativeSession,clock);
        synchronized(this) {
            requireCurrent();
            if(!leaseId.equals(freshFence.leaseId())||nodeLeaseEpoch!=freshFence.epoch())
                throw new IOException("A different node lease or epoch requires a new host lifecycle");
            if(candidate.policy().revision()<state.policy().revision()||
                    candidate.policy().revision()==state.policy().revision()&&!candidate.policy().sha256().equals(state.policy().sha256()))
                throw new IOException("Owner policy rollback or same-revision fork");
            long previous=state.fence().checkpointSequence(),next=freshFence.checkpointSequence();
            if(next<previous||next==previous&&!freshFence.checkpointSha256().equals(state.fence().checkpointSha256()))
                throw new IOException("Confirmed checkpoint rollback or same-sequence fork");
            checkLive(candidate);
            // A real same-lease renewal can shorten the Node TTL. Honor its verified
            // conservative deadline even when checkpoint/policy stay unchanged.
            state=candidate;
        }
    }
    private void requireCurrent()throws IOException {
        if(closed)throw new IOException("Host permit is closed");
        try{checkLive(state);}catch(IOException expired){closed=true;throw expired;}
    }
    /** All data here is immutable and already signature-verified; keep native hot-path checks bounded to at most16 grants. */
    private void checkLive(State current)throws IOException {
        LeaseFence.Fence fence=current.fence();
        if(!certificate.authorityId().equals(fence.authorityId())||!holder.equals(fence.holderId())||
                !nativeSession.equals(fence.sessionId())||!leaseId.equals(fence.leaseId())||nodeLeaseEpoch!=fence.epoch()||
                current.policy().revision()!=fence.policyRevision()||!current.policy().sha256().equals(fence.policySha256()))
            throw new IOException("Host permit binding changed");
        fence.requireUsable();activeGrant(current.policy(),root,holder,clock);
    }
    private static State verify(WorldAuthority.Certificate certificate,WorldAuthority.Genesis root,WorldAuthority.Policy supplied,
                                LeaseFence.Fence fence,String holder,UUID session,Clock clock)throws IOException {
        if(supplied==null||fence==null)throw new IOException("Missing verified policy or opaque node fence");
        WorldAuthority.requirePinned(supplied.certificate(),certificate.authorityId(),root.ownerId());
        WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,supplied.proof());
        if(!policy.sha256().equals(supplied.sha256())||policy.revision()!=fence.policyRevision()||!policy.sha256().equals(fence.policySha256())||
                !certificate.authorityId().equals(fence.authorityId())||!holder.equals(fence.holderId())||!session.equals(fence.sessionId()))
            throw new IOException("Owner policy/fence/actual host identity/session mismatch");
        fence.requireUsable();activeGrant(policy,root,holder,clock);return new State(policy,fence);
    }
    private static void activeGrant(WorldAuthority.Policy policy,WorldAuthority.Genesis root,String holder,Clock clock)throws IOException {
        long now=WorldAuthority.time(clock.millis());
        if(holder.equals(root.ownerId()))return;
        for(WorldAuthority.Grant grant:policy.grants())if(holder.equals(grant.delegate())&&grant.notBefore()<=now&&now<grant.expiresAt())return;
        throw new IOException("Actual holder has no currently active owner-signed host grant");
    }
    @Override public synchronized void close(){closed=true;}
}
