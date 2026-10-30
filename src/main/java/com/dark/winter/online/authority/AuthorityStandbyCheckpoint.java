// Content-only standby provenance captured while an actual old Node Fence is usable.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.ProfileFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Content-only standby provenance captured while an actual old Node Fence is usable.
 * Caller first verifies complete READY/native bytes, owner ACL and independent pins.
 * This opaque value neither grants hosting nor substitutes for a new Node claim/Fence.
 */
public final class AuthorityStandbyCheckpoint {
    private final String authorityId,ownerId,candidateId,worldSha,certificateSha,checkpointSha,policySha;
    private final long epoch,sequence,minimumPolicy;
    private final Path world;
    private AuthorityStandbyCheckpoint(WorldAuthority.Certificate certificate,WorldAuthority.Genesis root,
                                       WorldAuthority.Policy policy,LeaseFence.Fence fence,String candidate,Path world,String worldSha)throws IOException {
        authorityId=certificate.authorityId();ownerId=root.ownerId();candidateId=candidate;this.world=world;this.worldSha=worldSha;
        certificateSha=WorldAuthority.hash(ProfileFiles.bytes(WorldAuthority.certificateToJson(certificate)));
        epoch=fence.epoch();sequence=fence.checkpointSequence();checkpointSha=fence.checkpointSha256();
        minimumPolicy=policy.revision();policySha=policy.sha256();
    }
    public static AuthorityStandbyCheckpoint capture(WorldAuthority.Certificate certificate,WorldAuthority.Policy verifiedOldPolicy,
                                                       LeaseFence.Fence oldFence,String actualCandidateId,Path existingStagedWorld)throws IOException {
        if(verifiedOldPolicy==null||oldFence==null||existingStagedWorld==null)throw new IOException("Missing standby provenance");
        oldFence.requireUsable();WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
        WorldAuthority.requirePinned(verifiedOldPolicy.certificate(),certificate.authorityId(),root.ownerId());
        WorldAuthority.Policy policy=WorldAuthority.verifyPolicy(certificate,verifiedOldPolicy.proof());
        if(!certificate.authorityId().equals(oldFence.authorityId())||oldFence.policyRevision()!=policy.revision()
                ||!oldFence.policySha256().equals(policy.sha256())||oldFence.epoch()<1||oldFence.checkpointSequence()<0)
            throw new IOException("Standby policy or realm differs from its actual old Fence");
        WorldAuthority.identity(actualCandidateId);WorldAuthority.digest(oldFence.checkpointSha256());
        long now=System.currentTimeMillis();WorldAuthority.authorizeHost(policy,oldFence.holderId(),now);WorldAuthority.authorizeHost(policy,actualCandidateId,now);
        String hash=AuthorityHostJournal.worldHash(existingStagedWorld);
        oldFence.requireUsable();
        return new AuthorityStandbyCheckpoint(certificate,root,policy,oldFence,actualCandidateId,existingStagedWorld.toAbsolutePath(),hash);
    }
    public String authorityId(){return authorityId;}public String ownerId(){return ownerId;}public String candidateId(){return candidateId;}
    public String worldSha256(){return worldSha;}public long epoch(){return epoch;}
    public long checkpointSequence(){return sequence;}public String checkpointSha256(){return checkpointSha;}
    public long minimumPolicyRevision(){return minimumPolicy;}
    String certificateSha256(){return certificateSha;}String policySha256(){return policySha;}
    String checkedWorld()throws IOException {
        if(!Objects.equals(worldSha,AuthorityHostJournal.worldHash(world)))throw new IOException("Staged standby path binding changed");return worldSha;
    }
    @Override public String toString(){return "AuthorityStandbyCheckpoint[content-only,epoch="+epoch+",sequence="+sequence+"]";}
}
