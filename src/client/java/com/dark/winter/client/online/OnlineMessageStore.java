// Worker-owned durable ciphertext outbox, consumption receipts and optional owner-private chat history.
package com.dark.winter.client.online;

import com.dark.winter.online.identity.*;
import com.dark.winter.online.OnlineJson;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.security.MessageDigest;
import java.util.*;

/** Worker-owned durable ciphertext outbox, consumption receipts and optional owner-private chat history. */
final class OnlineMessageStore {
    record Queued(String id,String recipient,String kind,JsonObject wire,long expiresAt){}
    private static final int LIMIT=8*1024*1024,MAX_OUTBOX=1024,MAX_SEEN=4096,MAX_HISTORY=512;
    private final IdentityStore identity;private final Clock clock;private final Path file;
    private JsonObject data,committed;private boolean history;
    private final LinkedHashMap<String,JsonObject> committedVisible=new LinkedHashMap<>();
    private final LinkedHashMap<String,JsonObject> visible=new LinkedHashMap<>();
    OnlineMessageStore(IdentityStore identity,Clock clock,boolean history)throws IOException{
        this.identity=identity;this.clock=clock;this.history=history;
        file=identity.base().resolve("communications-"+identity.profile().id()+".json");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))data=ProfileFiles.read(file,LIMIT);
        else{data=new JsonObject();data.addProperty("schema",1);data.addProperty("owner",identity.profile().id());for(String key:List.of("outbox","seen","invites"))data.add(key,new JsonObject());data.add("history",new JsonArray());persist();}
        validate();committed=data.deepCopy();
        for(JsonElement element:data.getAsJsonArray("history")){JsonObject row=element.getAsJsonObject();visible.put(row.get("id").getAsString(),row.deepCopy());}
        for(Queued queued:outbox()){
            JsonObject row=data.getAsJsonObject("outbox").getAsJsonObject(queued.id());
            if(row.has("preview")){
                JsonObject preview=ProfileFiles.parse(identity.openSealed(payload(row.getAsJsonObject("preview")),IdentityStore.Purpose.CHAT),32768);
                JsonObject shown=messageView(preview,true);shown.addProperty("state","pending");shown.addProperty("status","Ожидает подтверждения адресата");visible.put(queued.id(),shown);
            }
        }
        trimVisible();for(var entry:visible.entrySet())committedVisible.put(entry.getKey(),entry.getValue().deepCopy());
    }
    private void validate()throws IOException{
        ProfileFiles.fields(data,Set.of("schema","owner","outbox","seen","history","invites"),Set.of());
        if(ProfileFiles.number(data,"schema")!=1||!ProfileFiles.string(data,"owner",64).equals(identity.profile().id())
                ||data.getAsJsonObject("outbox").size()>MAX_OUTBOX||data.getAsJsonObject("seen").size()>MAX_SEEN
                ||data.getAsJsonArray("history").size()>MAX_HISTORY||data.getAsJsonObject("invites").size()>512)throw new IOException("Invalid communications state");
        for(var entry:data.getAsJsonObject("outbox").entrySet()){
            uuid(entry.getKey());JsonObject row=entry.getValue().getAsJsonObject();
            ProfileFiles.fields(row,Set.of("recipient","kind","wire","expiresAt"),Set.of("preview","queued"));
            peer(ProfileFiles.string(row,"recipient",64));String kind=ProfileFiles.string(row,"kind",16);
            if(!Set.of("chat","invite","control").contains(kind)||ProfileFiles.number(row,"expiresAt")<0||!row.get("wire").isJsonObject())throw new IOException("Invalid outbox entry");
            IdentityStore.SignedPayload signed=payload(kind.equals("control")?row.getAsJsonObject("wire").getAsJsonObject("envelope"):row.getAsJsonObject("wire"));
            IdentityStore.Purpose purpose=kind.equals("invite")?IdentityStore.Purpose.INVITATION:IdentityStore.Purpose.CHAT;
            if(!IdentityStore.verify(signed,purpose)||!signed.signer().id().equals(identity.profile().id()))throw new IOException("Invalid local outbox signature");
        }
        for(var entry:data.getAsJsonObject("seen").entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();if(!entry.getKey().matches("dw1_[A-Za-z0-9_-]{43}:[0-9a-f-]{36}")||!ProfileFiles.string(row,"hash",64).matches("[0-9a-f]{64}")||ProfileFiles.number(row,"expiresAt")<0)throw new IOException("Invalid replay receipt");
        }
        for(JsonElement element:data.getAsJsonArray("history")){
            JsonObject row=element.getAsJsonObject();uuid(ProfileFiles.string(row,"id",36));peer(ProfileFiles.string(row,"sender",64));peer(ProfileFiles.string(row,"recipient",64));
            if(ProfileFiles.string(row,"text",2048).codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new IOException("Invalid stored message");
        }
    }
    void history(boolean enabled)throws IOException{history=enabled;if(!enabled)data.add("history",new JsonArray());persist();}
    void outgoing(String id,String recipient,String kind,JsonObject wire,JsonObject preview,long expires)throws IOException{
        uuid(id);peer(recipient);prune();JsonObject out=data.getAsJsonObject("outbox");
        if(out.has(id))throw new IOException("Message ID already exists");
        if(out.size()>=MAX_OUTBOX)throw new IOException("Outbox is full");
        JsonObject row=new JsonObject();row.addProperty("recipient",recipient);row.addProperty("kind",kind);row.add("wire",wire.deepCopy());row.addProperty("expiresAt",expires);
        if(preview!=null){
            row.add("preview",OnlineJson.GSON.toJsonTree(identity.seal(identity.profile(),IdentityStore.Purpose.CHAT,OnlineJson.bytes(preview))));
            JsonObject shown=messageView(preview,true);shown.addProperty("state","pending");shown.addProperty("status","Ожидает передачи и подтверждения");visible.put(id,shown);
        }
        out.add(id,row);persist();
    }
    List<Queued> outbox()throws IOException{
        List<Queued> result=new ArrayList<>();
        for(var entry:data.getAsJsonObject("outbox").entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();
            result.add(new Queued(entry.getKey(),ProfileFiles.string(row,"recipient",64),ProfileFiles.string(row,"kind",16),row.getAsJsonObject("wire").deepCopy(),ProfileFiles.number(row,"expiresAt")));
        }return List.copyOf(result);
    }
    boolean duplicate(String sender,String id,JsonObject wire)throws IOException{
        String key=sender+":"+id;JsonObject old=data.getAsJsonObject("seen").getAsJsonObject(key);
        if(old==null)return false;
        if(!ProfileFiles.string(old,"hash",64).equals(hash(wire)))throw new IOException("Message ID reused with another payload");
        requeueReceipt(old);persist();return true;
    }
    void accept(String id,String sender,String kind,JsonObject wire,IdentityProfile remote,JsonObject body,long expires)throws IOException{
        uuid(id);peer(sender);if(!remote.id().equals(sender))throw new IOException("Sender/profile mismatch");prune();
        if(data.getAsJsonObject("seen").size()>=MAX_SEEN)throw new IOException("Replay window is full");
        if(data.getAsJsonObject("outbox").size()>=MAX_OUTBOX)throw new IOException("Receipt outbox is full");
        if(kind.equals("invite")&&data.getAsJsonObject("invites").size()>=512)throw new IOException("Invitation inbox is full");
        JsonObject shown=kind.equals("chat")?messageView(body,false):null;
        JsonObject receipt=new JsonObject();receipt.addProperty("type","delivery_receipt");receipt.addProperty("sender",identity.profile().id());receipt.addProperty("recipient",sender);
        receipt.addProperty("messageId",id);receipt.addProperty("kind",kind);receipt.addProperty("hash",hash(wire));receipt.addProperty("createdAt",clock.millis());
        JsonObject control=new JsonObject();control.addProperty("type","delivery_receipt");
        control.add("envelope",OnlineJson.GSON.toJsonTree(identity.seal(remote,IdentityStore.Purpose.CHAT,OnlineJson.bytes(receipt))));
        String receiptId=UUID.randomUUID().toString();
        JsonObject record=new JsonObject();record.addProperty("hash",hash(wire));record.addProperty("expiresAt",Math.max(clock.millis()+24*3600000L,expires));
        record.addProperty("receiptId",receiptId);record.addProperty("recipient",sender);record.add("receipt",control);
        data.getAsJsonObject("seen").add(sender+":"+id,record);requeueReceipt(record);
        if(kind.equals("chat")){shown.addProperty("state","delivered");shown.addProperty("status","Получено и сохранено");visible.put(id,shown);}
        if(kind.equals("invite")){
            JsonObject invitation=new JsonObject();invitation.add("wire",wire.deepCopy());invitation.addProperty("state","incoming");invitation.addProperty("from",sender);
            data.getAsJsonObject("invites").add(id,invitation);
            if(data.getAsJsonObject("invites").size()>512)throw new IOException("Invitation inbox is full");
        }
        persist();
    }
    private void requeueReceipt(JsonObject record)throws IOException{
        JsonObject out=data.getAsJsonObject("outbox");String id=record.get("receiptId").getAsString();
        if(!out.has(id)){
            if(out.size()>=MAX_OUTBOX)throw new IOException("Receipt outbox is full");
            JsonObject row=new JsonObject();row.addProperty("recipient",record.get("recipient").getAsString());row.addProperty("kind","control");row.add("wire",record.getAsJsonObject("receipt").deepCopy());row.addProperty("expiresAt",record.get("expiresAt").getAsLong());
            out.add(id,row);
        }
    }
    void queued(String id,boolean transportAcknowledged)throws IOException{
        JsonObject row=data.getAsJsonObject("outbox").getAsJsonObject(id);if(row==null)return;
        if(row.get("kind").getAsString().equals("control")&&transportAcknowledged){data.getAsJsonObject("outbox").remove(id);persist();return;}
        if(!row.has("queued")){row.addProperty("queued",true);persist();}
        JsonObject message=visible.get(id);if(message!=null){message.addProperty("state","pending");message.addProperty("status","Принято службой; ожидание подтверждения адресата");}
    }
    boolean receipt(String sender,String id,String kind,String digest)throws IOException{
        JsonObject row=data.getAsJsonObject("outbox").getAsJsonObject(id);if(row==null)return false;
        if(!row.get("recipient").getAsString().equals(sender)||!row.get("kind").getAsString().equals(kind)||!hash(row.getAsJsonObject("wire")).equals(digest))throw new IOException("Receipt does not match the queued message");
        data.getAsJsonObject("outbox").remove(id);JsonObject message=visible.get(id);
        if(message!=null){message.addProperty("state","delivered");message.addProperty("status","Подтверждено адресатом");}
        persist();return true;
    }
    void failed(String id,String reason)throws IOException{
        JsonObject message=visible.get(id);if(message!=null){message.addProperty("state","failed");message.addProperty("status",reason);}
    }
    JsonArray messages(){JsonArray result=new JsonArray();for(JsonObject row:visible.values())result.add(row.deepCopy());return result;}
    /** Worker-only current signature/expiry check; this does not infer remote consumption or revocation. */
    JsonArray invitations()throws IOException{
        List<JsonObject> shown=new ArrayList<>();
        for(var entry:data.getAsJsonObject("invites").entrySet()){
            JsonObject row=new JsonObject();row.addProperty("id","");row.addProperty("messageId",entry.getKey());
            row.addProperty("direction","incoming");row.addProperty("nickname","Приглашение");
            row.addProperty("state","unavailable");row.addProperty("actionable",false);
            row.addProperty("status","Срок истёк или приглашение недействительно");
            try{
                JsonObject stored=entry.getValue().getAsJsonObject();
                IdentityStore.SignedPayload envelope=payload(stored.getAsJsonObject("wire"));
                JsonObject body=ProfileFiles.parse(identity.openSealed(envelope,IdentityStore.Purpose.INVITATION),32768);
                String token=ProfileFiles.string(body,"token",16384),invitationId=ProfileFiles.string(body,"invitationId",36);
                uuid(invitationId);row.addProperty("id",invitationId);row.addProperty("nickname",envelope.signer().nickname());
                var verified=com.dark.winter.online.friends.InvitationStore.inspect(token,clock);
                if(!verified.id().equals(invitationId)||!verified.issuer().id().equals(envelope.signer().id())
                        ||!verified.targetId().isBlank()&&!verified.targetId().equals(identity.profile().id()))
                    throw new IOException("Invitation audience or issuer changed");
                row.addProperty("createdAt",verified.createdAt());row.addProperty("expiresAt",verified.expiresAt());
                String state=ProfileFiles.string(stored,"state",32);boolean actionable=state.equals("incoming");
                row.addProperty("state",state);row.addProperty("actionable",actionable);
                row.addProperty("status",state.equals("declined")?"Приглашение отклонено":actionable?"Получено приглашение":"Приглашение неактивно");
                if(actionable)row.addProperty("code",token);
            }catch(IOException|RuntimeException invalid){/* Keep one unavailable history row; never expose errors or an actionable code. */}
            shown.add(row);
        }
        shown.sort(Comparator.<JsonObject>comparingInt(row->row.get("actionable").getAsBoolean()?0:1)
                .thenComparing(Comparator.comparingLong((JsonObject row)->row.has("createdAt")?row.get("createdAt").getAsLong():0).reversed())
                .thenComparing(row->row.get("messageId").getAsString()));
        JsonArray result=new JsonArray();for(JsonObject row:shown)result.add(row);return result;
    }
    void rejectInvitation(String invitationId)throws IOException{
        boolean found=false;
        for(var entry:data.getAsJsonObject("invites").entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();JsonObject body=ProfileFiles.parse(identity.openSealed(payload(row.getAsJsonObject("wire")),IdentityStore.Purpose.INVITATION),32768);
            if(ProfileFiles.string(body,"invitationId",36).equals(invitationId)){row.addProperty("state","declined");found=true;}
        }
        if(!found)throw new IOException("Invitation not found");persist();
    }
    private JsonObject messageView(JsonObject body,boolean outgoing)throws IOException{
        JsonObject row=new JsonObject();
        for(String key:List.of("id","sender","recipient","nickname","text","channel"))row.addProperty(key,ProfileFiles.string(body,key,key.equals("text")?2048:128));
        row.addProperty("direction",outgoing?"outgoing":"incoming");row.addProperty("createdAt",ProfileFiles.number(body,"createdAt"));return row;
    }
    private void prune(){
        long now=clock.millis();JsonObject seen=data.getAsJsonObject("seen");
        for(String key:new ArrayList<>(seen.keySet()))if(seen.getAsJsonObject(key).get("expiresAt").getAsLong()<=now)seen.remove(key);
        JsonObject out=data.getAsJsonObject("outbox");
        for(String id:new ArrayList<>(out.keySet()))if(out.getAsJsonObject(id).get("expiresAt").getAsLong()<=now){JsonObject row=visible.get(id);if(row!=null){row.addProperty("state","failed");row.addProperty("status","Срок доставки истёк.");}out.remove(id);}
    }
    private void trimVisible(){while(visible.size()>MAX_HISTORY)visible.remove(visible.keySet().iterator().next());}
    private void persist()throws IOException{
        trimVisible();JsonArray saved=new JsonArray();if(history)for(JsonObject row:visible.values())saved.add(row.deepCopy());data.add("history",saved);
        try{
            byte[] bytes=ProfileFiles.bytes(data);if(bytes.length>LIMIT)throw new IOException("Communications storage limit reached");
            ProfileFiles.atomic(file,data,true);committed=data.deepCopy();committedVisible.clear();
            for(var entry:visible.entrySet())committedVisible.put(entry.getKey(),entry.getValue().deepCopy());
        }catch(IOException|RuntimeException failure){
            if(committed!=null){data=committed.deepCopy();visible.clear();for(var entry:committedVisible.entrySet())visible.put(entry.getKey(),entry.getValue().deepCopy());}
            throw failure;
        }
    }
    static IdentityStore.SignedPayload payload(JsonObject value)throws IOException{
        try{ProfileFiles.fields(value,Set.of("schema","purpose","signer","data","signature"),Set.of());return OnlineJson.GSON.fromJson(value,IdentityStore.SignedPayload.class);}
        catch(RuntimeException failure){throw new IOException("Invalid encrypted envelope",failure);}
    }
    static String hash(JsonObject value)throws IOException{
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ProfileFiles.bytes(value)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    static void uuid(String value)throws IOException{try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Invalid message ID");}catch(RuntimeException failure){throw new IOException("Invalid message ID",failure);}}
    static void peer(String value)throws IOException{if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid identity ID");}
}
