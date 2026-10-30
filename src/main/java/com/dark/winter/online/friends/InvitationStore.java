// Issues and tracks signed island capabilities, revocation, and replay-safe use counts.
package com.dark.winter.online.friends;

import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;

/** Local issuer's capability book. Consuming a code never replaces host-side identity/ACL/ban validation. */
public final class InvitationStore {
    public enum Role {OBSERVER,TESTER,EDITOR}
    public record InvitationOptions(String islandId,Role role,String targetId,long ttlMillis,int maxUses,boolean requireApproval){}
    public record InvitationView(String id,String islandId,Role role,String targetId,long createdAt,long expiresAt,int maxUses,int uses,boolean requireApproval,boolean revoked){}
    public record CreatedInvitation(String token,InvitationView invitation){
        @Override public String toString(){return "CreatedInvitation[id="+invitation.id()+"]";}
    }
    public record SignedInvitation(IdentityProfile issuer,String id,String islandId,Role role,String targetId,long createdAt,long expiresAt,int maxUses,boolean requireApproval){}
    public record InvitationGrant(String invitationId,String islandId,Role role,String peerId,boolean repeated){}
    private record Parsed(IdentityStore.SignedPayload envelope,JsonObject body,SignedInvitation invitation){}
    private static final Gson JSON=new Gson();
    private static final int MAX_ENTRIES=512,MAX_ATTEMPTS=4096;
    private static final long MAX_TTL=7*24*3600_000L;
    private final IdentityStore identity;private final Clock clock;
    private InvitationStore(IdentityStore identity,Clock clock){this.identity=identity;this.clock=clock;}
    public static InvitationStore open(Path base,IdentityStore identity,Clock clock)throws IOException {
        if(!base.toAbsolutePath().normalize().toRealPath().equals(identity.base()))throw new IOException("Invitation store belongs to another profile");
        InvitationStore store=new InvitationStore(identity,Objects.requireNonNull(clock));
        JsonObject state=identity.section(IdentityStore.Section.INVITATIONS);
        if(state.isEmpty()){state=new JsonObject();state.addProperty("schema",1);state.addProperty("owner",identity.profile().id());state.add("entries",new JsonObject());identity.writeSection(IdentityStore.Section.INVITATIONS,state);}
        store.validate(state);return store;
    }
    public synchronized CreatedInvitation create(InvitationOptions options)throws IOException {
        validateOptions(options);JsonObject state=read(),entries=state.getAsJsonObject("entries");
        for(String old:new ArrayList<>(entries.keySet())){InvitationView prior=view(entries.getAsJsonObject(old));if(prior.revoked()||prior.expiresAt()<=clock.millis())entries.remove(old);}
        if(entries.size()>=MAX_ENTRIES)throw new IOException("Invitation book is full; revoke/expire old entries before issuing more");
        long now=clock.millis();String id=UUID.randomUUID().toString();
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("id",id);body.addProperty("islandId",options.islandId());
        body.addProperty("role",options.role().name());body.addProperty("targetId",options.targetId());body.addProperty("secret",IdentityStore.randomToken());
        body.addProperty("createdAt",now);body.addProperty("expiresAt",Math.addExact(now,options.ttlMillis()));body.addProperty("maxUses",options.maxUses());body.addProperty("requireApproval",options.requireApproval());
        IdentityStore.SignedPayload signed=identity.sign(IdentityStore.Purpose.INVITATION,ProfileFiles.bytes(body));
        String token="dwi1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(ProfileFiles.bytes(JSON.toJsonTree(signed)));
        JsonObject entry=body.deepCopy();entry.remove("secret");entry.addProperty("hash",hash(token));entry.addProperty("uses",0);entry.addProperty("revoked",false);entry.add("attempts",new JsonObject());
        entries.add(id,entry);persist(state);return new CreatedInvitation(token,view(entry));
    }
    public synchronized List<InvitationView> list()throws IOException {
        List<InvitationView> result=new ArrayList<>();for(var value:read().getAsJsonObject("entries").entrySet())result.add(view(value.getValue().getAsJsonObject()));
        result.sort(Comparator.comparingLong(InvitationView::createdAt).reversed());return List.copyOf(result);
    }
    public synchronized void revoke(String id)throws IOException {
        uuid(id);JsonObject state=read(),entry=entry(state,id);entry.addProperty("revoked",true);persist(state);
    }
    public static SignedInvitation inspect(String token,Clock clock)throws IOException {return parse(token,clock.millis()).invitation();}
    /** Only call on the authoritative host after verifying peer proof, issuer permissions, bans and any required human approval. */
    public synchronized InvitationGrant consume(String token,String authenticatedPeerId,UUID attemptId,boolean hostApproved)throws IOException {
        id(authenticatedPeerId);Objects.requireNonNull(attemptId);
        Parsed parsed=parse(token,clock.millis());SignedInvitation invitation=parsed.invitation();
        if(!invitation.issuer().id().equals(identity.profile().id()))throw new IOException("This issuer's book cannot authorize another issuer's invitation");
        if(!invitation.targetId().isEmpty()&&!invitation.targetId().equals(authenticatedPeerId))throw new IOException("Invitation is bound to another peer");
        if(blocked(authenticatedPeerId))throw new IOException("Peer is blocked");
        JsonObject state=read(),entry=entry(state,invitation.id());
        if(entry.get("revoked").getAsBoolean()||!MessageDigest.isEqual(entry.get("hash").getAsString().getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Unknown or revoked invitation");
        if(!view(entry).islandId().equals(invitation.islandId())||view(entry).role()!=invitation.role()
                ||view(entry).expiresAt()!=invitation.expiresAt()||view(entry).maxUses()!=invitation.maxUses()
                ||!view(entry).targetId().equals(invitation.targetId())||view(entry).requireApproval()!=invitation.requireApproval())
            throw new IOException("Invitation state does not match its signed capability");
        if(invitation.requireApproval()&&!hostApproved)throw new IOException("Host approval is required");
        JsonObject attempts=entry.getAsJsonObject("attempts");String key=attemptId.toString();
        if(attempts.has(key)){
            if(!attempts.get(key).getAsString().equals(authenticatedPeerId))throw new IOException("Join attempt identity mismatch");
            return new InvitationGrant(invitation.id(),invitation.islandId(),invitation.role(),authenticatedPeerId,true);
        }
        int uses=entry.get("uses").getAsInt();
        if(invitation.maxUses()!=0&&uses>=invitation.maxUses())throw new IOException("Invitation usage limit reached");
        if(attempts.size()>=MAX_ATTEMPTS)throw new IOException("Invitation replay ledger is full");
        attempts.addProperty(key,authenticatedPeerId);entry.addProperty("uses",Math.addExact(uses,1));persist(state);
        return new InvitationGrant(invitation.id(),invitation.islandId(),invitation.role(),authenticatedPeerId,false);
    }
    private boolean blocked(String peer)throws IOException {
        JsonObject friends=identity.section(IdentityStore.Section.FRIENDS);if(friends.isEmpty())return false;
        if(ProfileFiles.number(friends,"schema")!=1)throw new IOException("Unsupported friends state");
        for(JsonElement value:friends.getAsJsonArray("blocks"))if(value.getAsString().equals(peer))return true;return false;
    }
    private static Parsed parse(String token,long now)throws IOException {
        if(token==null||!token.startsWith("dwi1_")||token.length()>16384)throw new IOException("Invalid invitation code");
        JsonObject envelope=ProfileFiles.parse(IdentityStore.decode(token.substring(5),12288),12288);
        ProfileFiles.fields(envelope,Set.of("schema","purpose","signer","data","signature"),Set.of());
        IdentityStore.SignedPayload signed;
        try{signed=JSON.fromJson(envelope,IdentityStore.SignedPayload.class);}catch(RuntimeException failure){throw new IOException("Invalid invitation envelope",failure);}
        if(!IdentityStore.verify(signed,IdentityStore.Purpose.INVITATION))throw new IOException("Invalid invitation signature");
        JsonObject body=ProfileFiles.parse(IdentityStore.decodeData(signed),4096);
        ProfileFiles.fields(body,Set.of("schema","id","islandId","role","targetId","secret","createdAt","expiresAt","maxUses","requireApproval"),Set.of());
        String id=ProfileFiles.string(body,"id",36),island=ProfileFiles.string(body,"islandId",36),target=ProfileFiles.string(body,"targetId",64);
        uuid(id);uuid(island);if(!target.isEmpty())id(target);
        long created=ProfileFiles.number(body,"createdAt"),expires=ProfileFiles.number(body,"expiresAt"),uses=ProfileFiles.number(body,"maxUses");
        if(ProfileFiles.number(body,"schema")!=1||created<0||created>now+30000||expires<=now||expires<=created||expires-created>MAX_TTL||uses<0||uses>10000
                ||IdentityStore.decode(ProfileFiles.string(body,"secret",64),32).length!=32)throw new IOException("Expired or invalid invitation");
        Role role;
        try{role=Role.valueOf(ProfileFiles.string(body,"role",32));}catch(IllegalArgumentException failure){throw new IOException("Invitation cannot grant this role",failure);}
        if(!body.get("requireApproval").isJsonPrimitive()||!body.get("requireApproval").getAsJsonPrimitive().isBoolean())throw new IOException("Invalid invitation approval policy");
        SignedInvitation view=new SignedInvitation(signed.signer(),id,island,role,target,created,expires,(int)uses,body.get("requireApproval").getAsBoolean());
        return new Parsed(signed,body,view);
    }
    private JsonObject read()throws IOException {JsonObject state=identity.section(IdentityStore.Section.INVITATIONS);validate(state);return state;}
    private void persist(JsonObject state)throws IOException {validate(state);identity.writeSection(IdentityStore.Section.INVITATIONS,state);}
    private void validate(JsonObject state)throws IOException {
        try{
            ProfileFiles.fields(state,Set.of("schema","owner","entries"),Set.of());
            if(ProfileFiles.number(state,"schema")!=1||!ProfileFiles.string(state,"owner",64).equals(identity.profile().id())
                    ||state.getAsJsonObject("entries").size()>MAX_ENTRIES)throw new IOException("Invalid invitation book");
            for(var row:state.getAsJsonObject("entries").entrySet()){
                JsonObject entry=row.getValue().getAsJsonObject();
                ProfileFiles.fields(entry,Set.of("schema","id","islandId","role","targetId","createdAt","expiresAt","maxUses","requireApproval","hash","uses","revoked","attempts"),Set.of());
                uuid(row.getKey());if(!row.getKey().equals(ProfileFiles.string(entry,"id",36)))throw new IOException("Invitation ID mismatch");
                InvitationView view=view(entry);validateOptions(new InvitationOptions(view.islandId(),view.role(),view.targetId(),view.expiresAt()-view.createdAt(),view.maxUses(),view.requireApproval()));
                if(ProfileFiles.number(entry,"schema")!=1||view.createdAt()<0||view.uses()<0
                        ||!entry.get("hash").getAsString().matches("[0-9a-f]{64}")||entry.getAsJsonObject("attempts").size()>MAX_ATTEMPTS
                        ||view.uses()!=entry.getAsJsonObject("attempts").size()||view.maxUses()>0&&view.uses()>view.maxUses())throw new IOException("Invalid invitation usage ledger");
                for(var attempt:entry.getAsJsonObject("attempts").entrySet()){uuid(attempt.getKey());id(attempt.getValue().getAsString());}
            }
        }catch(RuntimeException failure){throw new IOException("Invalid invitation state",failure);}
    }
    private static InvitationView view(JsonObject value)throws IOException {
        try{return new InvitationView(value.get("id").getAsString(),value.get("islandId").getAsString(),Role.valueOf(value.get("role").getAsString()),
                value.get("targetId").getAsString(),ProfileFiles.number(value,"createdAt"),ProfileFiles.number(value,"expiresAt"),
                Math.toIntExact(ProfileFiles.number(value,"maxUses")),Math.toIntExact(ProfileFiles.number(value,"uses")),value.get("requireApproval").getAsBoolean(),value.get("revoked").getAsBoolean());}
        catch(RuntimeException failure){throw new IOException("Invalid invitation fields",failure);}
    }
    private static JsonObject entry(JsonObject state,String id)throws IOException {JsonElement value=state.getAsJsonObject("entries").get(id);if(value==null)throw new IOException("Unknown invitation");return value.getAsJsonObject();}
    private static void validateOptions(InvitationOptions options)throws IOException {
        if(options==null||options.role()==null||options.targetId()==null||options.ttlMillis()<1000||options.ttlMillis()>MAX_TTL||options.maxUses()<0||options.maxUses()>10000)
            throw new IOException("Invalid invitation options");
        uuid(options.islandId());if(!options.targetId().isEmpty())id(options.targetId());
    }
    private static void uuid(String value)throws IOException {try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Invalid invitation identifier");}catch(RuntimeException failure){throw new IOException("Invalid invitation identifier",failure);}}
    private static void id(String value)throws IOException {if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid peer identity");}
    private static String hash(String token)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
}
