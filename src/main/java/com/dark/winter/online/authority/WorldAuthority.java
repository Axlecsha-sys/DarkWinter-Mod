// Defines and verifies immutable world-owner certificates and host-delegation policies.
package com.dark.winter.online.authority;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Immutable owner root and owner-only host delegations. A world UUID/unsigned ACL
 * alone is never a trust anchor. Callers must pin authorityId outside the untrusted world copy.
 * This class performs no network, filesystem, native game or automatic enrollment operations.
 */
public final class WorldAuthority {
    private static final String GENESIS="DarkWinter.WorldAuthority.genesis.v1";
    private static final String POLICY="DarkWinter.WorldAuthority.host-policy.v1";
    private static final int LIMIT=16384;
    private static final long MAX_TIME=253402300799000L,MAX_GRANT=366L*24*60*60*1000;
    private static final Gson JSON=new Gson();
    private WorldAuthority(){}
    public record Certificate(String authorityId,SignedPayload genesis) {
        @Override public String toString(){return "WorldAuthority["+authorityId+"]";}
    }
    public record Genesis(UUID islandId,String ownerId,String coordinatorId,String genesisSha256,
                          String nonce,long createdAt){}
    public record Grant(UUID id,String delegate,long notBefore,long expiresAt){}
    /** Verified instances can be obtained only through signature verification in this class. */
    public static final class Policy {
        private final Certificate certificate;private final long revision,issuedAt;
        private final List<Grant> grants;private final SignedPayload proof;private final String sha256;
        private Policy(Certificate certificate,long revision,long issuedAt,List<Grant>grants,SignedPayload proof){
            this.certificate=certificate;this.revision=revision;this.issuedAt=issuedAt;
            this.grants=List.copyOf(grants);this.proof=proof;sha256=hash(ProfileFiles.bytes(JSON.toJsonTree(proof)));
        }
        public Certificate certificate(){return certificate;} public long revision(){return revision;}
        public long issuedAt(){return issuedAt;} public List<Grant> grants(){return grants;}
        public SignedPayload proof(){return proof;} public String sha256(){return sha256;}
    }
    public static Certificate issue(IdentityStore owner,UUID island,String genesisSha,String coordinatorId,Instant created)throws IOException{
        Objects.requireNonNull(owner);Objects.requireNonNull(island);Objects.requireNonNull(created);
        identity(owner.profile().id());identity(coordinatorId);digest(genesisSha);time(created.toEpochMilli());
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("domain",GENESIS);
        body.addProperty("islandId",island.toString());body.addProperty("ownerId",owner.profile().id());
        body.addProperty("coordinatorId",coordinatorId);body.addProperty("genesisSha256",genesisSha);
        body.addProperty("nonce",IdentityStore.randomToken());body.addProperty("createdAt",created.toEpochMilli());
        SignedPayload signed=owner.sign(IdentityStore.Purpose.AUTH,ProfileFiles.bytes(body));
        Certificate result=new Certificate(authorityId(signed),signed);verify(result);return result;
    }
    public static Genesis verify(Certificate certificate)throws IOException{
        if(certificate==null||certificate.authorityId()==null||!certificate.authorityId().matches("dwa1_[A-Za-z0-9_-]{43}"))
            throw new IOException("Invalid world authority identifier");
        JsonObject body=signedBody(certificate.genesis(),GENESIS);
        ProfileFiles.fields(body,Set.of("schema","domain","islandId","ownerId","coordinatorId","genesisSha256","nonce","createdAt"),Set.of());
        UUID island=uuid(ProfileFiles.string(body,"islandId",36));
        String owner=identity(ProfileFiles.string(body,"ownerId",64)),node=identity(ProfileFiles.string(body,"coordinatorId",64));
        String sha=digest(ProfileFiles.string(body,"genesisSha256",64)),nonce=ProfileFiles.string(body,"nonce",64);
        if(IdentityStore.decode(nonce,32).length!=32)throw new IOException("Invalid world authority nonce");
        long created=time(integer(body,"createdAt"));
        if(!owner.equals(certificate.genesis().signer().id())||!certificate.authorityId().equals(authorityId(certificate.genesis())))
            throw new IOException("World authority owner/key or root fingerprint mismatch");
        return new Genesis(island,owner,node,sha,nonce,created);
    }
    /** Exact comparison with an externally pinned root; a replacement certificate is a different island authority. */
    public static Genesis requirePinned(Certificate certificate,String authorityId,String ownerId)throws IOException{
        Genesis root=verify(certificate);
        if(!certificate.authorityId().equals(authorityId)||!root.ownerId().equals(ownerId))
            throw new IOException("World authority differs from the pinned owner root");
        return root;
    }
    /** The existing ACL may retain its world UUID, but may never replace this certificate's owner. */
    public static void requireAccess(Certificate certificate,String islandId,String accessOwner)throws IOException{
        Genesis root=verify(certificate);
        if(!root.islandId().equals(uuid(islandId))||!root.ownerId().equals(accessOwner))
            throw new IOException("Unsigned island access does not match the certified owner/world");
    }
    public static SignedPayload policy(IdentityStore owner,Certificate certificate,long revision,List<Grant>grants,Instant issued)throws IOException{
        Genesis root=verify(certificate);
        if(!owner.profile().id().equals(root.ownerId()))throw new IOException("Only the certified owner may delegate hosting");
        if(revision<0)throw new IOException("Invalid host policy revision");
        long issuedAt=time(issued.toEpochMilli());if(issuedAt<root.createdAt())throw new IOException("Policy predates authority");
        List<Grant> sorted=new ArrayList<>(Objects.requireNonNull(grants));validateGrants(root,sorted);
        sorted.sort(Comparator.comparing(Grant::delegate).thenComparing(g->g.id().toString()));
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("domain",POLICY);
        body.addProperty("authorityId",certificate.authorityId());body.addProperty("islandId",root.islandId().toString());
        body.addProperty("ownerId",root.ownerId());body.addProperty("revision",revision);body.addProperty("issuedAt",issuedAt);
        JsonArray rows=new JsonArray();
        for(Grant grant:sorted){JsonObject row=new JsonObject();row.addProperty("id",grant.id().toString());row.addProperty("delegate",grant.delegate());
            row.addProperty("notBefore",grant.notBefore());row.addProperty("expiresAt",grant.expiresAt());rows.add(row);}
        body.add("grants",rows);
        SignedPayload signed=owner.sign(IdentityStore.Purpose.AUTH,ProfileFiles.bytes(body));verifyPolicy(certificate,signed);return signed;
    }
    public static Policy verifyPolicy(Certificate certificate,SignedPayload signed)throws IOException{
        Genesis root=verify(certificate);JsonObject body=signedBody(signed,POLICY);
        ProfileFiles.fields(body,Set.of("schema","domain","authorityId","islandId","ownerId","revision","issuedAt","grants"),Set.of());
        if(!signed.signer().id().equals(root.ownerId())||!certificate.authorityId().equals(ProfileFiles.string(body,"authorityId",64))
            ||!root.islandId().equals(uuid(ProfileFiles.string(body,"islandId",36)))||!root.ownerId().equals(ProfileFiles.string(body,"ownerId",64)))
            throw new IOException("Foreign owner, root or world in host policy");
        long revision=integer(body,"revision"),issued=time(integer(body,"issuedAt"));
        if(revision<0||issued<root.createdAt())throw new IOException("Invalid host policy revision/time");
        if(!body.get("grants").isJsonArray())throw new IOException("Host policy grants must be an array");
        JsonArray rows=body.getAsJsonArray("grants");if(rows.size()>16)throw new IOException("Too many trusted host grants");
        List<Grant> grants=new ArrayList<>();
        for(JsonElement element:rows){
            if(!element.isJsonObject())throw new IOException("Invalid host grant");JsonObject row=element.getAsJsonObject();
            ProfileFiles.fields(row,Set.of("id","delegate","notBefore","expiresAt"),Set.of());
            grants.add(new Grant(uuid(ProfileFiles.string(row,"id",36)),identity(ProfileFiles.string(row,"delegate",64)),
                time(integer(row,"notBefore")),time(integer(row,"expiresAt"))));
        }
        validateGrants(root,grants);
        List<Grant> sorted=new ArrayList<>(grants);sorted.sort(Comparator.comparing(Grant::delegate).thenComparing(g->g.id().toString()));
        if(!sorted.equals(grants))throw new IOException("Noncanonical host grant order");
        return new Policy(certificate,revision,issued,grants,signed);
    }
    /** Policy rollback/revocation tracking belongs to the durable coordinator, never a local unsigned ACL. */
    public static void authorizeHost(Policy policy,String authenticatedPeer,long now)throws IOException{
        Objects.requireNonNull(policy);identity(authenticatedPeer);time(now);
        Genesis root=verify(policy.certificate());
        if(root.ownerId().equals(authenticatedPeer))return;
        if(policy.grants().stream().noneMatch(g->g.delegate().equals(authenticatedPeer)&&g.notBefore()<=now&&now<g.expiresAt()))
            throw new IOException("No active owner-signed host delegation");
    }
    public static JsonObject certificateToJson(Certificate certificate)throws IOException{
        verify(certificate);JsonObject result=new JsonObject();result.addProperty("schema",1);
        result.addProperty("authorityId",certificate.authorityId());result.add("genesis",JSON.toJsonTree(certificate.genesis()));return result;
    }
    public static Certificate certificateFromJson(JsonObject object)throws IOException{
        bounded(object);ProfileFiles.fields(object,Set.of("schema","authorityId","genesis"),Set.of());
        if(integer(object,"schema")!=1)throw new IOException("Unsupported world authority wrapper");
        Certificate certificate=new Certificate(ProfileFiles.string(object,"authorityId",64),signed(object.get("genesis")));
        verify(certificate);return certificate;
    }
    public static SignedPayload signedFromJson(JsonObject object)throws IOException{return signed(object);}
    private static SignedPayload signed(JsonElement element)throws IOException{
        if(element==null||!element.isJsonObject())throw new IOException("Missing authority signature");JsonObject object=element.getAsJsonObject();bounded(object);
        ProfileFiles.fields(object,Set.of("schema","purpose","signer","data","signature"),Set.of());
        if(!object.get("signer").isJsonObject())throw new IOException("Missing authority signer");
        JsonObject signer=object.getAsJsonObject("signer");
        ProfileFiles.fields(signer,Set.of("schema","id","publicKey","encryptionPublicKey","nickname","model","privacy","revision","createdAt"),Set.of());
        if(!signer.get("privacy").isJsonObject())throw new IOException("Invalid signer privacy");
        ProfileFiles.fields(signer.getAsJsonObject("privacy"),Set.of("presence","requests","messages","invitations"),Set.of());
        try{return JSON.fromJson(object,SignedPayload.class);}catch(RuntimeException invalid){throw new IOException("Invalid authority signature envelope",invalid);}
    }
    private static JsonObject signedBody(SignedPayload signed,String domain)throws IOException{
        if(!IdentityStore.verify(signed,IdentityStore.Purpose.AUTH))throw new IOException("Owner authority signature is invalid");
        byte[] data=IdentityStore.decodeData(signed);JsonObject body=ProfileFiles.parse(data,LIMIT);
        if(!Arrays.equals(data,ProfileFiles.bytes(body)))throw new IOException("Authority body is not canonical");
        if(integer(body,"schema")!=1||!domain.equals(ProfileFiles.string(body,"domain",80)))throw new IOException("Wrong authority signature domain");
        return body;
    }
    private static String authorityId(SignedPayload signed)throws IOException{
        if(signed==null||signed.signer()==null)throw new IOException("Missing world authority signer");
        byte[] body=IdentityStore.decodeData(signed),key=IdentityStore.decode(signed.signer().publicKey(),128);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(DataOutputStream output=new DataOutputStream(buffer)){
            byte[] domain=GENESIS.getBytes(StandardCharsets.US_ASCII);output.writeInt(domain.length);output.write(domain);
            output.writeInt(key.length);output.write(key);output.writeInt(body.length);output.write(body);
        }
        return "dwa1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(sha(buffer.toByteArray()));
    }
    private static void validateGrants(Genesis root,List<Grant>grants)throws IOException{
        if(grants.size()>16)throw new IOException("Too many trusted hosts");Set<String>ids=new HashSet<>(),delegates=new HashSet<>();
        for(Grant grant:grants){
            if(grant==null||grant.id()==null)throw new IOException("Missing trusted host grant");
            identity(grant.delegate());time(grant.notBefore());time(grant.expiresAt());
            if(grant.delegate().equals(root.ownerId())||!ids.add(grant.id().toString())||!delegates.add(grant.delegate())||
                grant.notBefore()<root.createdAt()||grant.expiresAt()<=grant.notBefore()||grant.expiresAt()-grant.notBefore()>MAX_GRANT)
                throw new IOException("Invalid or duplicate trusted host grant");
        }
    }
    private static void bounded(JsonObject object)throws IOException{if(object==null||ProfileFiles.bytes(object).length>65536)throw new IOException("Authority envelope exceeds64KiB");}
    static String identity(String value)throws IOException{if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid authority identity");return value;}
    static String digest(String value)throws IOException{if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("Invalid authority digest");return value;}
    static UUID uuid(String value)throws IOException{try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IOException("Noncanonical authority UUID");return result;}catch(RuntimeException invalid){throw new IOException("Invalid authority UUID",invalid);}}
    static long integer(JsonObject body,String field)throws IOException{long value=ProfileFiles.number(body,field);if(!body.get(field).getAsString().equals(Long.toString(value)))throw new IOException("Noncanonical authority integer");return value;}
    static long time(long value)throws IOException{if(value<0||value>MAX_TIME)throw new IOException("Authority timestamp outside bounds");return value;}
    static String hash(byte[] value){return HexFormat.of().formatHex(sha(value));}
    private static byte[] sha(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
