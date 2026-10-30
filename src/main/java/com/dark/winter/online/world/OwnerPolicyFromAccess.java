// Pure owner-side planning only: no node requests, persistence, key opening or implicit signature publication.
package com.dark.winter.online.world;

import com.dark.winter.online.authority.WorldAuthority;
import com.dark.winter.online.identity.ProfileFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Pure owner-side planning only: no node requests, persistence, key opening or implicit signature publication. */
public final class OwnerPolicyFromAccess {
    public static final long GRANT_MILLIS=Duration.ofDays(30).toMillis();
    private static final long MAX_TIME=253402300799000L;
    private OwnerPolicyFromAccess(){}
    public record Plan(long revision,List<WorldAuthority.Grant> grants,boolean changed) {
        public Plan{grants=List.copyOf(grants);}
    }
    /**
     * A changed plan must be signed with the permanent owner's existing IdentityStore and confirmed at the node.
     * For unchanged plans reuse the exact current proof; re-signing a new issue time at the same revision is invalid.
     */
    public static Plan plan(WorldAuthority.Certificate certificate,WorldAuthority.Policy current,IslandAccess.View access,String actualOwner,Instant instant)throws IOException {
        if(access==null||instant==null)throw new IOException("Missing owner policy input");
        WorldAuthority.Genesis root=WorldAuthority.verify(certificate);WorldAuthority.requireAccess(certificate,access.islandId(),access.ownerId());
        if(!root.ownerId().equals(actualOwner))throw new IOException("Only the permanent owner may plan host delegation");
        long now;try{now=instant.toEpochMilli();}catch(ArithmeticException overflow){throw new IOException("Owner policy timestamp overflow",overflow);}
        if(now<root.createdAt()||now>MAX_TIME)throw new IOException("Owner policy time predates root or exceeds bounds");
        validate(access);
        WorldAuthority.Policy previous=null;
        if(current!=null){
            WorldAuthority.requirePinned(current.certificate(),certificate.authorityId(),root.ownerId());
            if(!Arrays.equals(ProfileFiles.bytes(WorldAuthority.certificateToJson(certificate)),ProfileFiles.bytes(WorldAuthority.certificateToJson(current.certificate()))))
                throw new IOException("Current policy certificate bytes changed");
            previous=WorldAuthority.verifyPolicy(certificate,current.proof());
            if(now<previous.issuedAt())throw new IOException("Owner policy clock rollback");
        }
        SortedSet<String> eligible=new TreeSet<>();
        for(String id:access.trustedLeads())if(!id.equals(root.ownerId())&&canTransfer(access,id))eligible.add(id);
        if(eligible.size()>15)throw new IOException("At most fifteen delegated hosts are allowed");
        Map<String,WorldAuthority.Grant> existing=new HashMap<>();
        if(previous!=null)for(var grant:previous.grants())existing.put(grant.delegate(),grant);
        long candidateRevision=previous==null?0:previous.revision()==Long.MAX_VALUE?Long.MAX_VALUE:previous.revision()+1;
        List<WorldAuthority.Grant> grants=new ArrayList<>();
        for(String delegate:eligible){
            var old=existing.get(delegate);WorldAuthority.Grant next;
            if(old!=null&&old.expiresAt()>now){
                // Preserve an already signed nonexpired interval exactly, including a future start.
                // Planning a new opening must neither silently prolong nor accelerate its grant.
                next=old;
            }else{
                String seed="DarkWinter.OwnerPolicyFromAccess.v1\0"+certificate.authorityId()+"\0"+delegate+"\0"+candidateRevision+"\0"+now;
                UUID id=UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
                next=new WorldAuthority.Grant(id,delegate,now,add(now,GRANT_MILLIS));
            }
            if(next.expiresAt()>MAX_TIME)throw new IOException("Host delegation exceeds timestamp bounds");
            grants.add(next);
        }
        grants.sort(Comparator.comparing(WorldAuthority.Grant::delegate).thenComparing(g->g.id().toString()));
        boolean changed=previous==null||!grants.equals(previous.grants());
        if(changed&&previous!=null&&previous.revision()==Long.MAX_VALUE)throw new IOException("Owner policy revision exhausted");
        return new Plan(changed?candidateRevision:previous.revision(),grants,changed);
    }
    private static long add(long a,long b)throws IOException{try{return Math.addExact(a,b);}catch(ArithmeticException overflow){throw new IOException("Host delegation time overflow",overflow);}}
    private static boolean canTransfer(IslandAccess.View access,String id) {
        if(access.blocked().contains(id)||!access.allowlist().contains(id))return false;
        var member=access.members().get(id);if(member==null)return false;
        Boolean override=member.overrides().get(IslandAccess.Permission.TRANSFER_HOST);
        return override!=null?override:member.role()==IslandAccess.Role.ASSISTANT;
    }
    private static void validate(IslandAccess.View access)throws IOException {
        if(access.limit()<2||access.limit()>16||access.epoch()<0||access.privacy()==null||access.members().size()>1024
                ||access.allowlist().size()>1024||access.blocked().size()>1024||access.trustedLeads().size()>16)throw new IOException("Owner ACL bounds");
        var owner=access.members().get(access.ownerId());
        if(owner==null||owner.role()!=IslandAccess.Role.OWNER||!owner.overrides().isEmpty()||!access.allowlist().contains(access.ownerId())
                ||access.blocked().contains(access.ownerId())||!access.trustedLeads().contains(access.ownerId()))throw new IOException("Permanent ACL owner changed");
        for(var entry:access.members().entrySet()){
            id(entry.getKey());var member=entry.getValue();
            if(!entry.getKey().equals(member.id())||member.role()==null||member.role()==IslandAccess.Role.OWNER&&!entry.getKey().equals(access.ownerId()))
                throw new IOException("ACL member identity or role changed");
        }
        for(String value:access.allowlist())id(value);for(String value:access.blocked())id(value);for(String value:access.trustedLeads())id(value);
    }
    private static void id(String value)throws IOException{if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid ACL public identity");}
}
