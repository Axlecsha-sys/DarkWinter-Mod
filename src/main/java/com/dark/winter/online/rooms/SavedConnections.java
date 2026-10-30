// Persists the connection list with reconnect credentials encrypted to the local identity.
package com.dark.winter.online.rooms;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Owner-only, atomically saved connection list. Every reconnect credential is encrypted to this actual local identity. */
public final class SavedConnections {
    public static final int MAX_CONNECTIONS=64;
    private static final Gson JSON=new Gson();
    public record Entry(ConnectionInfo.Descriptor descriptor,String shareInfo,boolean passwordRequired,boolean hosting){
        public String id(){return descriptor.id().toString();}
        @Override public String toString(){return "SavedConnection[protectedRouteOnly]";}
    }
    private final IdentityStore owner;
    private final Path file;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private SavedConnections(IdentityStore owner){this.owner=owner;file=owner.base().resolve("connections-"+owner.profile().id()+".json");}
    public static SavedConnections open(IdentityStore owner)throws IOException{
        SavedConnections store=new SavedConnections(owner);
        if(!Files.exists(store.file,LinkOption.NOFOLLOW_LINKS))return store;
        ProfileFiles.protect(store.file,false);JsonObject document=ProfileFiles.read(store.file,4*1024*1024);
        ProfileFiles.fields(document,Set.of("schema","owner","records"),Set.of());
        if(ProfileFiles.number(document,"schema")!=1||!ProfileFiles.string(document,"owner",64).equals(owner.profile().id())
                ||!document.get("records").isJsonArray()||document.getAsJsonArray("records").size()>MAX_CONNECTIONS)throw new IOException("Invalid protected connection list");
        for(JsonElement value:document.getAsJsonArray("records")){
            if(!value.isJsonObject())throw new IOException("Invalid protected connection entry");
            IdentityStore.SignedPayload sealed=JSON.fromJson(value,IdentityStore.SignedPayload.class);
            if(sealed==null||sealed.signer()==null||!owner.profile().id().equals(sealed.signer().id()))throw new IOException("Connection credential owner changed");
            byte[] bytes=owner.openSealed(sealed,IdentityStore.Purpose.INVITATION);
            try{
                JsonObject record=ProfileFiles.parse(bytes,32768);
                ProfileFiles.fields(record,Set.of("credential","shareInfo","passwordRequired","hosting"),Set.of());
                if(!record.get("credential").isJsonObject())
                    throw new IOException("Invalid protected connection credential");
                var descriptor=ConnectionInfo.inspectCredential(record.getAsJsonObject("credential"));
                String info=ProfileFiles.string(record,"shareInfo",ConnectionInfo.MAX_LENGTH);boolean required=booleanField(record,"passwordRequired"),hosting=booleanField(record,"hosting");
                if(required!=ConnectionInfo.passwordRequired(info)||hosting&&!descriptor.ownerId().equals(owner.profile().id()))throw new IOException("Invalid protected connection ownership");
                if(!required&&!ConnectionInfo.open(info,null).descriptor().localCredential().equals(descriptor.localCredential()))
                    throw new IOException("Protected connection data mismatch");
                Entry entry=new Entry(descriptor,info,required,hosting);
                if(store.entries.putIfAbsent(entry.id(),entry)!=null)throw new IOException("Duplicate protected connection");
            }finally{Arrays.fill(bytes,(byte)0);}
        }
        return store;
    }
    public List<Entry> list(){return List.copyOf(entries.values());}
    public Entry get(String id)throws IOException{Entry entry=entries.get(id);if(entry==null)throw new IOException("Unknown saved connection");return entry;}
    public void remember(Entry entry)throws IOException{
        if(entry==null||entry.hosting()&&!entry.descriptor().ownerId().equals(owner.profile().id()))throw new IOException("Connection owner mismatch");
        if(!entries.containsKey(entry.id())&&entries.size()>=MAX_CONNECTIONS)throw new IOException("Saved connection capacity");
        Entry previous=entries.put(entry.id(),entry);
        try{save();}catch(IOException failure){if(previous==null)entries.remove(entry.id());else entries.put(entry.id(),previous);throw failure;}
    }
    public void forget(String id)throws IOException{
        Entry previous=entries.remove(id);if(previous==null)throw new IOException("Unknown saved connection");
        try{save();}catch(IOException failure){entries.put(id,previous);throw failure;}
    }
    private void save()throws IOException{
        JsonObject document=new JsonObject();document.addProperty("schema",1);document.addProperty("owner",owner.profile().id());JsonArray records=new JsonArray();
        for(Entry entry:entries.values()){
            JsonObject record=new JsonObject();record.add("credential",entry.descriptor().localCredential());record.addProperty("shareInfo",entry.shareInfo());
            record.addProperty("passwordRequired",entry.passwordRequired());record.addProperty("hosting",entry.hosting());
            byte[] bytes=ProfileFiles.bytes(record);
            try{records.add(JSON.toJsonTree(owner.seal(owner.profile(),IdentityStore.Purpose.INVITATION,bytes)));}
            finally{Arrays.fill(bytes,(byte)0);}
        }
        document.add("records",records);ProfileFiles.atomic(file,document,true);
    }
    private static boolean booleanField(JsonObject value,String name)throws IOException{
        JsonElement field=value.get(name);
        if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isBoolean())throw new IOException("Invalid connection flag");
        return field.getAsBoolean();
    }
    @Override public String toString(){return "SavedConnections[ownerEncrypted]";}
}
