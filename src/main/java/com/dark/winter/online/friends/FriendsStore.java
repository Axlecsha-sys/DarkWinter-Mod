// Persists signed contact requests, consent state, and delivery outboxes.
package com.dark.winter.online.friends;

import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Local signed contact state. An outbox is not delivery and local intent is never remote acceptance. */
public final class FriendsStore {
    public enum State {OUTGOING_PENDING,INCOMING_PENDING,ACCEPTED_PENDING,CONFIRMED,DECLINED,CANCELLED,REMOVED,BLOCKED,EXPIRED}
    public enum Type {REQUEST,ACCEPT,ACK,DECLINE,CANCEL,REMOVE}
    public record Body(int schema,Type type,String id,String from,String to,long createdAt,long expiresAt,String requestId,String acceptanceId){}
    public record SignedFriendMessage(IdentityStore.SignedPayload envelope){
        @Override public String toString(){return "SignedFriendMessage";}
    }
    public record PeerView(IdentityProfile profile,State state,boolean blocked,String requestId,long updatedAt){}
    public record ReceiveResult(boolean changed,State state,List<SignedFriendMessage> replies,boolean duplicate){}
    private static final Gson JSON=new Gson();
    private static final int MAX_PEERS=1024,MAX_OUTBOX=1024,MAX_SEEN=4096;
    private static final long MAX_TTL=7*24*3600_000L;
    private final IdentityStore identity;private final Clock clock;
    private FriendsStore(IdentityStore identity,Clock clock){this.identity=identity;this.clock=clock;}
    public static FriendsStore open(Path base,IdentityStore identity,Clock clock)throws IOException {
        if(!base.toAbsolutePath().normalize().toRealPath().equals(identity.base()))throw new IOException("Friends store belongs to another profile");
        FriendsStore store=new FriendsStore(identity,Objects.requireNonNull(clock));JsonObject state=identity.section(IdentityStore.Section.FRIENDS);
        if(state.isEmpty()){state=empty(identity.profile().id());identity.writeSection(IdentityStore.Section.FRIENDS,state);}
        validateExport(identity.profile(),state);return store;
    }
    public synchronized List<PeerView> snapshot()throws IOException {
        JsonObject data=read();List<PeerView> result=new ArrayList<>();
        for(var entry:data.getAsJsonObject("peers").entrySet()){
            JsonObject peer=entry.getValue().getAsJsonObject();State state=State.valueOf(peer.get("state").getAsString());
            if(Set.of(State.OUTGOING_PENDING,State.INCOMING_PENDING,State.ACCEPTED_PENDING).contains(state)){
                Body request=body(message(peer.getAsJsonObject("evidence").getAsJsonObject("request")),false,0);
                if(request.expiresAt()<=clock.millis()||state==State.ACCEPTED_PENDING&&body(message(peer.getAsJsonObject("evidence").getAsJsonObject("accept")),false,0).expiresAt()<=clock.millis())state=State.EXPIRED;
            }
            result.add(new PeerView(profile(peer),state,blocked(data,entry.getKey()),peer.get("requestId").getAsString(),peer.get("updatedAt").getAsLong()));
        }
        result.sort(Comparator.comparing(view->view.profile().nickname()));return List.copyOf(result);
    }
    public synchronized Optional<PeerView> get(String id)throws IOException {return snapshot().stream().filter(peer->peer.profile().id().equals(id)).findFirst();}
    public synchronized boolean isConfirmed(String id)throws IOException {return get(id).map(peer->peer.state()==State.CONFIRMED&&!peer.blocked()).orElse(false);}
    public synchronized boolean isBlocked(String id)throws IOException {return blocked(read(),validId(id));}
    public synchronized SignedFriendMessage createRequest(IdentityStore.SignedPayload publicProfileProof)throws IOException {
        return createRequest(IdentityStore.validateProfileProof(publicProfileProof));
    }
    public synchronized SignedFriendMessage createRequest(IdentityProfile target)throws IOException {
        IdentityStore.validateProfile(target);String owner=identity.profile().id();
        if(owner.equals(target.id()))throw new IOException("Cannot request friendship with this identity");
        JsonObject data=read();prune(data);if(blocked(data,target.id()))throw new IOException("Peer is blocked");
        JsonObject old=peer(data,target.id());
        if(old!=null&&Set.of(State.CONFIRMED,State.OUTGOING_PENDING,State.INCOMING_PENDING,State.ACCEPTED_PENDING).contains(state(old)))
            throw new IOException("A friendship or pending request already exists");
        String request=UUID.randomUUID().toString();SignedFriendMessage outgoing=create(Type.REQUEST,target.id(),request,"",request);
        JsonObject next=contact(target,State.OUTGOING_PENDING,request);next.getAsJsonObject("evidence").add("request",json(outgoing));
        putPeer(data,target.id(),next);enqueue(data,outgoing);persist(data);return outgoing;
    }
    public synchronized SignedFriendMessage accept(String requestId)throws IOException {
        JsonObject data=read();prune(data);JsonObject peer=findRequest(data,requestId);
        if(state(peer)!=State.INCOMING_PENDING||blocked(data,profile(peer).id()))throw new IOException("No acceptable incoming request");
        SignedFriendMessage reply=acceptInto(data,peer);persist(data);return reply;
    }
    private SignedFriendMessage acceptInto(JsonObject data,JsonObject peer)throws IOException {
        Body request=body(message(peer.getAsJsonObject("evidence").getAsJsonObject("request")),true,clock.millis());
        String id=UUID.randomUUID().toString();SignedFriendMessage reply=create(Type.ACCEPT,request.from(),request.id(),id,id);
        peer.addProperty("state",State.ACCEPTED_PENDING.name());peer.addProperty("acceptanceId",id);peer.addProperty("updatedAt",clock.millis());
        peer.getAsJsonObject("evidence").add("accept",json(reply));enqueue(data,reply);return reply;
    }
    public synchronized SignedFriendMessage decline(String requestId)throws IOException {return finishRequest(requestId,Type.DECLINE,State.DECLINED,Set.of(State.INCOMING_PENDING));}
    public synchronized SignedFriendMessage cancel(String requestId)throws IOException {return finishRequest(requestId,Type.CANCEL,State.CANCELLED,Set.of(State.OUTGOING_PENDING,State.ACCEPTED_PENDING));}
    private SignedFriendMessage finishRequest(String requestId,Type type,State next,Set<State> allowed)throws IOException {
        JsonObject data=read();prune(data);JsonObject peer=findRequest(data,requestId);if(!allowed.contains(state(peer)))throw new IOException("Request is not pending");
        SignedFriendMessage reply=create(type,profile(peer).id(),requestId,peer.get("acceptanceId").getAsString(),UUID.randomUUID().toString());
        peer.addProperty("state",next.name());peer.addProperty("updatedAt",clock.millis());enqueue(data,reply);persist(data);return reply;
    }
    public synchronized SignedFriendMessage remove(String peerId)throws IOException {
        JsonObject data=read();JsonObject peer=requirePeer(data,peerId);if(state(peer)!=State.CONFIRMED)throw new IOException("Peer is not confirmed");
        SignedFriendMessage reply=create(Type.REMOVE,peerId,peer.get("requestId").getAsString(),peer.get("acceptanceId").getAsString(),UUID.randomUUID().toString());
        peer.addProperty("state",State.REMOVED.name());peer.addProperty("updatedAt",clock.millis());enqueue(data,reply);persist(data);return reply;
    }
    public synchronized void block(String peerId)throws IOException {
        validId(peerId);if(peerId.equals(identity.profile().id()))throw new IOException("Cannot block this identity");
        JsonObject data=read();if(!blocked(data,peerId))data.getAsJsonArray("blocks").add(peerId);
        if(data.getAsJsonArray("blocks").size()>MAX_PEERS)throw new IOException("Block list limit reached");
        JsonObject peer=peer(data,peerId);if(peer!=null){peer.addProperty("state",State.BLOCKED.name());peer.addProperty("updatedAt",clock.millis());}
        JsonObject out=data.getAsJsonObject("outbox");
        for(String key:new ArrayList<>(out.keySet()))if(body(message(out.getAsJsonObject(key)),false,0).to().equals(peerId))out.remove(key);
        persist(data);
    }
    public synchronized void unblock(String peerId)throws IOException {
        validId(peerId);JsonObject data=read();JsonArray blocks=new JsonArray();
        for(JsonElement id:data.getAsJsonArray("blocks"))if(!id.getAsString().equals(peerId))blocks.add(id);
        data.add("blocks",blocks);JsonObject peer=peer(data,peerId);
        if(peer!=null&&state(peer)==State.BLOCKED){peer.addProperty("state",State.REMOVED.name());peer.addProperty("updatedAt",clock.millis());}
        persist(data);
    }
    public synchronized ReceiveResult receive(SignedFriendMessage incoming)throws IOException {
        Body received=body(incoming,true,clock.millis());IdentityProfile remote=incoming.envelope().signer();
        String owner=identity.profile().id();
        if(!received.to().equals(owner)||received.from().equals(owner))throw new IOException("Friend message recipient mismatch");
        JsonObject data=read();prune(data);if(blocked(data,remote.id()))throw new IOException("Peer is blocked");
        String digest=hash(json(incoming));JsonObject seen=data.getAsJsonObject("seen");
        if(seen.has(received.id())){
            if(!seen.getAsJsonObject(received.id()).get("hash").getAsString().equals(digest))throw new IOException("Message identity was reused with different signed content");
            JsonObject known=peer(data,remote.id());return new ReceiveResult(false,known==null?State.REMOVED:state(known),List.of(),true);
        }
        JsonObject current=peer(data,remote.id());List<SignedFriendMessage> replies=new ArrayList<>();boolean changed=false;
        if(current!=null){
            IdentityProfile previous=profile(current);updateProfile(current,remote);
            if(remote.revision()>=previous.revision())current.getAsJsonObject("evidence").add("profile",json(incoming));
        }
        switch(received.type()){
            case REQUEST->{
                if(!received.requestId().equals(received.id())||!received.acceptanceId().isEmpty())throw new IOException("Invalid request linkage");
                if(identity.profile().privacy().requests()==IdentityProfile.ContactPolicy.NOBODY
                        ||identity.profile().privacy().requests()==IdentityProfile.ContactPolicy.FRIENDS&&(current==null||state(current)!=State.CONFIRMED))
                    throw new IOException("Incoming friend requests are disabled");
                if(current==null||!Set.of(State.INCOMING_PENDING,State.ACCEPTED_PENDING).contains(state(current))){
                    boolean alreadyConsented=current!=null&&state(current)==State.CONFIRMED;
                    boolean mutual=current!=null&&state(current)==State.OUTGOING_PENDING;
                    if(!mutual||received.id().compareTo(current.get("requestId").getAsString())<0){
                        current=contact(remote,State.INCOMING_PENDING,received.id());current.getAsJsonObject("evidence").add("request",json(incoming));current.getAsJsonObject("evidence").add("profile",json(incoming));
                        putPeer(data,remote.id(),current);changed=true;
                        if(mutual||alreadyConsented)replies.add(acceptInto(data,current));
                    }
                }
            }
            case ACCEPT->{
                if(current==null||state(current)!=State.OUTGOING_PENDING||!current.get("requestId").getAsString().equals(received.requestId())
                        ||!received.acceptanceId().equals(received.id()))throw new IOException("Acceptance does not match a local pending request");
                current.getAsJsonObject("evidence").add("accept",json(incoming));current.addProperty("acceptanceId",received.id());
                SignedFriendMessage ack=create(Type.ACK,remote.id(),received.requestId(),received.id(),UUID.randomUUID().toString());
                current.getAsJsonObject("evidence").add("ack",json(ack));current.addProperty("state",State.CONFIRMED.name());enqueue(data,ack);replies.add(ack);changed=true;
            }
            case ACK->{
                if(current==null||state(current)!=State.ACCEPTED_PENDING||!current.get("requestId").getAsString().equals(received.requestId())
                        ||!current.get("acceptanceId").getAsString().equals(received.acceptanceId()))throw new IOException("Confirmation does not match local acceptance");
                current.getAsJsonObject("evidence").add("ack",json(incoming));current.addProperty("state",State.CONFIRMED.name());changed=true;
            }
            case DECLINE,CANCEL,REMOVE->{
                if(current!=null&&current.get("requestId").getAsString().equals(received.requestId())){
                    current.addProperty("state",received.type()==Type.DECLINE?State.DECLINED.name():State.REMOVED.name());changed=true;
                }
            }
        }
        if(current!=null)current.addProperty("updatedAt",clock.millis());
        JsonObject receipt=new JsonObject();receipt.addProperty("hash",digest);receipt.addProperty("expiresAt",received.expiresAt());seen.add(received.id(),receipt);
        if(seen.size()>MAX_SEEN)throw new IOException("Friend replay window is full");
        persist(data);return new ReceiveResult(changed,current==null?State.REMOVED:state(current),List.copyOf(replies),false);
    }
    public synchronized List<SignedFriendMessage> outbox()throws IOException {
        List<SignedFriendMessage> result=new ArrayList<>();JsonObject data=read();
        for(var entry:data.getAsJsonObject("outbox").entrySet()){
            SignedFriendMessage message=message(entry.getValue().getAsJsonObject());Body body=body(message,false,0);
            if(body.expiresAt()>clock.millis()&&!blocked(data,body.to()))result.add(message);
        }return List.copyOf(result);
    }
    public synchronized void markDelivered(String messageId)throws IOException {uuid(messageId);JsonObject data=read();data.getAsJsonObject("outbox").remove(messageId);persist(data);}
    public synchronized JsonObject exportState()throws IOException {return read();}
    public synchronized void importState(JsonObject state)throws IOException {validateExport(identity.profile(),state);identity.writeSection(IdentityStore.Section.FRIENDS,state);}
    public static void validateExport(IdentityProfile owner,JsonObject data)throws IOException {
        try{
            IdentityStore.validateProfile(owner);ProfileFiles.fields(data,Set.of("schema","owner","peers","blocks","outbox","seen"),Set.of());
            if(ProfileFiles.number(data,"schema")!=1||!ProfileFiles.string(data,"owner",64).equals(owner.id()))throw new IOException("Friends state belongs to another identity");
            JsonObject peers=data.getAsJsonObject("peers"),out=data.getAsJsonObject("outbox"),seen=data.getAsJsonObject("seen");
            if(peers.size()>MAX_PEERS||out.size()>MAX_OUTBOX||seen.size()>MAX_SEEN||data.getAsJsonArray("blocks").size()>MAX_PEERS)throw new IOException("Friends state limit reached");
            Set<String> blocked=new HashSet<>();for(JsonElement row:data.getAsJsonArray("blocks"))if(!blocked.add(validId(row.getAsString())))throw new IOException("Duplicate blocked identity");
            for(var entry:peers.entrySet()){
                JsonObject peer=entry.getValue().getAsJsonObject();ProfileFiles.fields(peer,Set.of("profile","state","requestId","acceptanceId","updatedAt","evidence"),Set.of());
                IdentityProfile remote=profile(peer);
                if(!remote.id().equals(validId(entry.getKey()))||remote.id().equals(owner.id())||peer.get("updatedAt").getAsLong()<0)throw new IOException("Invalid peer identity");
                State state=state(peer);uuid(peer.get("requestId").getAsString());
                JsonObject evidence=peer.getAsJsonObject("evidence");ProfileFiles.fields(evidence,Set.of("request"),Set.of("accept","ack","profile"));
                Body request=body(message(evidence.getAsJsonObject("request")),false,0);
                if(request.type()!=Type.REQUEST||!request.requestId().equals(request.id())||!request.acceptanceId().isEmpty()||!request.id().equals(peer.get("requestId").getAsString())||!pair(request,owner.id(),remote.id()))throw new IOException("Invalid signed request evidence");
                Body accept=null,ack=null;
                if(evidence.has("accept")){
                    accept=body(message(evidence.getAsJsonObject("accept")),false,0);
                    if(accept.type()!=Type.ACCEPT||!accept.acceptanceId().equals(accept.id())||!accept.from().equals(request.to())||!accept.to().equals(request.from())
                            ||!accept.requestId().equals(request.id())||!accept.id().equals(peer.get("acceptanceId").getAsString()))throw new IOException("Invalid signed acceptance evidence");
                }
                if(evidence.has("ack")){
                    ack=body(message(evidence.getAsJsonObject("ack")),false,0);
                    if(accept==null||ack.type()!=Type.ACK||!ack.from().equals(request.from())||!ack.to().equals(request.to())
                            ||!ack.requestId().equals(request.id())||!ack.acceptanceId().equals(accept.id()))throw new IOException("Invalid signed confirmation evidence");
                }
                if(state==State.CONFIRMED&&(accept==null||ack==null))throw new IOException("Confirmed friendship lacks remote signed consent");
                if(evidence.has("profile")){
                    SignedFriendMessage proof=message(evidence.getAsJsonObject("profile"));Body statement=body(proof,false,0);
                    if(!statement.from().equals(remote.id())||!statement.to().equals(owner.id())||!proof.envelope().signer().equals(remote))
                        throw new IOException("Peer profile is not bound to its own signed statement");
                }else if(state==State.CONFIRMED)throw new IOException("Confirmed peer lacks a signed public profile");

                if(state==State.OUTGOING_PENDING&&!request.from().equals(owner.id())||state==State.INCOMING_PENDING&&!request.to().equals(owner.id())
                        ||state==State.ACCEPTED_PENDING&&(accept==null||!accept.from().equals(owner.id())))throw new IOException("Invalid pending friend direction");
                if(state==State.BLOCKED&&!blocked.contains(remote.id()))throw new IOException("Invalid blocked peer state");
            }
            for(var entry:out.entrySet()){
                SignedFriendMessage envelope=message(entry.getValue().getAsJsonObject());Body body=body(envelope,false,0);
                if(!entry.getKey().equals(body.id())||!body.from().equals(owner.id())||body.to().equals(owner.id()))throw new IOException("Invalid local outbox signature");
            }
            for(var entry:seen.entrySet()){
                uuid(entry.getKey());JsonObject receipt=entry.getValue().getAsJsonObject();ProfileFiles.fields(receipt,Set.of("hash","expiresAt"),Set.of());
                if(!receipt.get("hash").getAsString().matches("[0-9a-f]{64}")||receipt.get("expiresAt").getAsLong()<0)throw new IOException("Invalid replay receipt");
            }
        }catch(RuntimeException failure){throw new IOException("Invalid friends state",failure);}
    }
    private JsonObject read()throws IOException {JsonObject data=identity.section(IdentityStore.Section.FRIENDS);validateExport(identity.profile(),data);return data;}
    private void persist(JsonObject data)throws IOException {validateExport(identity.profile(),data);identity.writeSection(IdentityStore.Section.FRIENDS,data);}
    private SignedFriendMessage create(Type type,String peer,String request,String accept,String id)throws IOException {
        long now=clock.millis();Body body=new Body(1,type,id,identity.profile().id(),validId(peer),now,Math.addExact(now,MAX_TTL),request,accept);
        return new SignedFriendMessage(identity.sign(IdentityStore.Purpose.FRIEND,ProfileFiles.bytes(JSON.toJsonTree(body))));
    }
    public static Body inspect(SignedFriendMessage message)throws IOException {return body(message,false,0);}
    private static Body body(SignedFriendMessage value,boolean current,long now)throws IOException {
        if(value==null||!IdentityStore.verify(value.envelope(),IdentityStore.Purpose.FRIEND))throw new IOException("Invalid signed friend message");
        JsonObject json=ProfileFiles.parse(IdentityStore.decodeData(value.envelope()),16384);
        ProfileFiles.fields(json,Set.of("schema","type","id","from","to","createdAt","expiresAt","requestId","acceptanceId"),Set.of());
        try{
            ProfileFiles.number(json,"schema");ProfileFiles.number(json,"createdAt");ProfileFiles.number(json,"expiresAt");
            Body body=JSON.fromJson(json,Body.class);uuid(body.id());uuid(body.requestId());if(!body.acceptanceId().isEmpty())uuid(body.acceptanceId());
            if(body.schema()!=1||body.type()==null||!validId(body.from()).equals(value.envelope().signer().id())||body.from().equals(validId(body.to()))
                    ||body.createdAt()<0||body.expiresAt()<=body.createdAt()||body.expiresAt()-body.createdAt()>MAX_TTL
                    ||current&&(body.expiresAt()<=now||body.createdAt()>now+30000))throw new IOException("Expired or mismatched friend message");
            return body;
        }catch(RuntimeException failure){throw new IOException("Invalid friend message body",failure);}
    }
    private void prune(JsonObject data)throws IOException {
        long now=clock.millis();JsonObject seen=data.getAsJsonObject("seen"),out=data.getAsJsonObject("outbox");
        for(String id:new ArrayList<>(seen.keySet()))if(seen.getAsJsonObject(id).get("expiresAt").getAsLong()<=now)seen.remove(id);
        for(String id:new ArrayList<>(out.keySet()))if(body(message(out.getAsJsonObject(id)),false,0).expiresAt()<=now)out.remove(id);
        for(var entry:data.getAsJsonObject("peers").entrySet()){
            JsonObject peer=entry.getValue().getAsJsonObject();State current=state(peer);
            if(Set.of(State.OUTGOING_PENDING,State.INCOMING_PENDING,State.ACCEPTED_PENDING).contains(current)){
                JsonObject evidence=peer.getAsJsonObject("evidence");
                if(body(message(evidence.getAsJsonObject("request")),false,0).expiresAt()<=now
                        ||current==State.ACCEPTED_PENDING&&body(message(evidence.getAsJsonObject("accept")),false,0).expiresAt()<=now)
                    peer.addProperty("state",State.EXPIRED.name());
            }
        }
    }
    private static JsonObject empty(String owner){JsonObject data=new JsonObject();data.addProperty("schema",1);data.addProperty("owner",owner);data.add("peers",new JsonObject());data.add("blocks",new JsonArray());data.add("outbox",new JsonObject());data.add("seen",new JsonObject());return data;}
    private JsonObject contact(IdentityProfile target,State state,String request){JsonObject peer=new JsonObject();peer.add("profile",JSON.toJsonTree(target));peer.addProperty("state",state.name());peer.addProperty("requestId",request);peer.addProperty("acceptanceId","");peer.addProperty("updatedAt",clock.millis());peer.add("evidence",new JsonObject());return peer;}
    private static IdentityProfile profile(JsonObject peer)throws IOException {return IdentityStore.validateProfile(JSON.fromJson(peer.get("profile"),IdentityProfile.class));}
    private static void updateProfile(JsonObject peer,IdentityProfile next)throws IOException {IdentityProfile old=profile(peer);if(next.revision()==old.revision()&&!next.equals(old))throw new IOException("Conflicting peer profile revision");if(next.revision()>old.revision())peer.add("profile",JSON.toJsonTree(next));}
    private static State state(JsonObject peer){return State.valueOf(peer.get("state").getAsString());}
    private static JsonObject peer(JsonObject data,String id){JsonElement value=data.getAsJsonObject("peers").get(id);return value==null?null:value.getAsJsonObject();}
    private static JsonObject requirePeer(JsonObject data,String id)throws IOException {JsonObject result=peer(data,validId(id));if(result==null)throw new IOException("Unknown peer");return result;}
    private static JsonObject findRequest(JsonObject data,String id)throws IOException {uuid(id);for(var value:data.getAsJsonObject("peers").entrySet())if(value.getValue().getAsJsonObject().get("requestId").getAsString().equals(id))return value.getValue().getAsJsonObject();throw new IOException("Unknown friend request");}
    private static boolean blocked(JsonObject data,String id){for(JsonElement value:data.getAsJsonArray("blocks"))if(value.getAsString().equals(id))return true;return false;}
    private static void putPeer(JsonObject data,String id,JsonObject peer)throws IOException {if(!data.getAsJsonObject("peers").has(id)&&data.getAsJsonObject("peers").size()>=MAX_PEERS)throw new IOException("Friend list limit reached");data.getAsJsonObject("peers").add(id,peer);}
    private static void enqueue(JsonObject data,SignedFriendMessage envelope)throws IOException {Body body=body(envelope,false,0);JsonObject out=data.getAsJsonObject("outbox");if(!out.has(body.id())&&out.size()>=MAX_OUTBOX)throw new IOException("Local outbox is full");out.add(body.id(),json(envelope));}
    private static JsonObject json(SignedFriendMessage value){return JSON.toJsonTree(value).getAsJsonObject();}
    private static SignedFriendMessage message(JsonObject value)throws IOException {try{ProfileFiles.fields(value,Set.of("envelope"),Set.of());return JSON.fromJson(value,SignedFriendMessage.class);}catch(RuntimeException failure){throw new IOException("Invalid friend envelope",failure);}}
    private static boolean pair(Body body,String one,String two){return body.from().equals(one)&&body.to().equals(two)||body.from().equals(two)&&body.to().equals(one);}
    private static String validId(String id)throws IOException {if(id==null||!id.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid DarkWinter ID");return id;}
    private static void uuid(String id)throws IOException {try{if(!UUID.fromString(id).toString().equals(id))throw new IOException("Invalid message ID");}catch(RuntimeException failure){throw new IOException("Invalid message ID",failure);}}
    private static String hash(JsonObject json)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ProfileFiles.bytes(json)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
}
