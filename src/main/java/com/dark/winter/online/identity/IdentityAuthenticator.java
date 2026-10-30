// Verifies short-lived identity challenges without establishing global presence.
package com.dark.winter.online.identity;

import com.google.gson.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Short-lived challenge ownership. No network, global presence or trust-by-nickname. */
public final class IdentityAuthenticator {
    public record Challenge(int schema,String nonce,String audience,String subject,String sessionId,long issuedAt,long expiresAt) {
        @Override public String toString(){return "Challenge[expiresAt="+expiresAt+"]";}
    }
    public record Proof(IdentityStore.SignedPayload signed) {
        @Override public String toString(){return "IdentityProof";}
    }
    public record VerifiedPeer(IdentityProfile profile,String sessionId,long verifiedAt) {}
    private final IdentityStore identity;private final Clock clock;
    private final Map<String,Challenge> outstanding=new HashMap<>();
    public IdentityAuthenticator(IdentityStore identity,Clock clock){this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);}
    public synchronized Challenge issueChallenge(String sessionId,String expectedPeerId,Duration duration)throws IOException {
        long ttl;
        try{ttl=duration.toMillis();}catch(RuntimeException failure){throw new IOException("Invalid authentication duration",failure);}
        if(ttl<1000||ttl>120000||sessionId==null||sessionId.isBlank()||sessionId.length()>128
                ||expectedPeerId==null||!expectedPeerId.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid authentication challenge");
        long now=clock.millis();outstanding.values().removeIf(value->value.expiresAt()<=now);
        if(outstanding.size()>=1024)throw new IOException("Too many pending authentication challenges");
        Challenge challenge=new Challenge(1,IdentityStore.randomToken(),identity.profile().id(),expectedPeerId,sessionId,now,Math.addExact(now,ttl));
        outstanding.put(challenge.nonce(),challenge);return challenge;
    }
    public Proof prove(Challenge challenge)throws IOException {
        validateTime(challenge,clock.millis());
        if(!identity.profile().id().equals(challenge.subject()))throw new IOException("Authentication challenge names another peer");
        return new Proof(identity.sign(IdentityStore.Purpose.AUTH,ProfileFiles.bytes(new Gson().toJsonTree(challenge))));
    }
    public synchronized VerifiedPeer verifyAndConsume(Proof proof)throws IOException {
        if(proof==null||!IdentityStore.verify(proof.signed(),IdentityStore.Purpose.AUTH))throw new IOException("Invalid identity proof");
        JsonObject object=ProfileFiles.parse(IdentityStore.decodeData(proof.signed()),4096);
        ProfileFiles.fields(object,Set.of("schema","nonce","audience","subject","sessionId","issuedAt","expiresAt"),Set.of());
        Challenge value;
        try{value=new Gson().fromJson(object,Challenge.class);}catch(RuntimeException failure){throw new IOException("Invalid proof body",failure);}
        long now=clock.millis();validateTime(value,now);
        Challenge expected=outstanding.get(value.nonce());
        if(expected==null||!expected.equals(value)||!identity.profile().id().equals(value.audience())
                ||!proof.signed().signer().id().equals(value.subject()))throw new IOException("Unknown, replayed or mismatched authentication proof");
        outstanding.remove(value.nonce());return new VerifiedPeer(proof.signed().signer(),value.sessionId(),now);
    }
    private static void validateTime(Challenge value,long now)throws IOException {
        if(value==null||value.schema()!=1||value.nonce()==null||IdentityStore.decode(value.nonce(),32).length!=32
                ||value.audience()==null||!value.audience().matches("dw1_[A-Za-z0-9_-]{43}")
                ||value.subject()==null||!value.subject().matches("dw1_[A-Za-z0-9_-]{43}")
                ||value.sessionId()==null||value.sessionId().isBlank()||value.sessionId().length()>128
                ||value.issuedAt()<0||value.issuedAt()>now+30000||value.expiresAt()<=now
                ||value.expiresAt()<=value.issuedAt()||value.expiresAt()-value.issuedAt()>120000)
            throw new IOException("Expired or invalid authentication challenge");
    }
}
