// Persists island discoverability and admission settings independently of personal keys.
package com.dark.winter.online.world;

import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** World policy contains no personal keys. Discoverability and admission are independent persisted settings. */
public final class IslandPolicy {
    public enum Visibility {PRIVATE,FRIENDS,PUBLIC}
    public enum Admission {INVITED,FRIENDS,CODE,OWNER_ONLY}
    public record View(long revision,Visibility visibility,Admission admission,boolean approval){}
    private final Path file;private final IslandAccess access;private View current;
    private IslandPolicy(Path file,IslandAccess access,View current){this.file=file;this.access=access;this.current=current;}
    public static IslandPolicy open(Path world,IslandAccess access)throws IOException{
        Objects.requireNonNull(access);Path root=world.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid policy world");
        Path marker=root.resolve("data/darkwinter-testing.properties");Properties props=new Properties();
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>65536)throw new IOException("Testing marker required");
        try(InputStream in=Files.newInputStream(marker)){props.load(in);}
        if(!"testing".equals(props.getProperty("mode"))||!Set.of("1","2").contains(props.getProperty("schema")))throw new IOException("Testing marker required");
        Path dir=root.resolve("data/darkwinter-online");
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(dir))throw new IOException("Open the actual island access store first");
        Path path=dir.resolve("policy.json");View view;
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            JsonObject json=ProfileFiles.read(path,65536);
            ProfileFiles.fields(json,Set.of("schema","islandId","ownerId","revision","visibility","admission","approval"),Set.of());
            if(ProfileFiles.number(json,"schema")!=1||!ProfileFiles.string(json,"islandId",36).equals(access.islandId())
                    ||!ProfileFiles.string(json,"ownerId",64).equals(access.ownerId()))throw new IOException("Policy belongs to another island");
            long revision=ProfileFiles.number(json,"revision");if(revision<0||revision==Long.MAX_VALUE)throw new IOException("Policy revision");
            try{view=new View(revision,Visibility.valueOf(ProfileFiles.string(json,"visibility",16)),Admission.valueOf(ProfileFiles.string(json,"admission",16)),bool(json,"approval"));}
            catch(IllegalArgumentException invalid){throw new IOException("Unknown policy",invalid);}
        }else view=new View(0,Visibility.PRIVATE,Admission.INVITED,true);
        IslandPolicy policy=new IslandPolicy(path,access,view);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))policy.save(view,false);return policy;
    }
    public synchronized View snapshot(){return current;}
    public synchronized boolean canConfigure(String actor){return access.can(actor,IslandAccess.Permission.ASSIGN_ROLES);}
    public synchronized void update(String actor,String key,String value)throws IOException{
        if(!canConfigure(actor))throw new IOException("Нет права менять доступ к острову");
        Visibility visibility=current.visibility();Admission admission=current.admission();boolean approval=current.approval();
        try{
            switch(key){
                case "visibility"->visibility=Visibility.valueOf(value.toUpperCase(Locale.ROOT));
                case "access"->admission=switch(value){case "invited"->Admission.INVITED;case "friends"->Admission.FRIENDS;case "code"->Admission.CODE;case "owner_only"->Admission.OWNER_ONLY;default->throw new IllegalArgumentException("Admission");};
                case "approval"->{if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("Approval");approval=Boolean.parseBoolean(value);}
                default->throw new IllegalArgumentException("Policy key");
            }
        }catch(IllegalArgumentException invalid){throw new IOException("Неизвестная настройка доступа",invalid);}
        View next=new View(Math.addExact(current.revision(),1),visibility,admission,approval);save(next,true);current=next;
    }
    /** Called only with the current coordinator's captured access store after a stopped-save transition. */
    public synchronized void resume(String actor,View live)throws IOException{
        Objects.requireNonNull(live);
        if(!access.can(actor,IslandAccess.Permission.TRANSFER_HOST)||!access.trustedLead(actor))throw new IOException("Untrusted coordinator");
        long previous=Math.max(current.revision(),live.revision());if(previous>=Long.MAX_VALUE-1)throw new IOException("Policy revision exhausted");
        View next=new View(previous+1,Objects.requireNonNull(live.visibility()),Objects.requireNonNull(live.admission()),live.approval());
        save(next,true);current=next;
    }
    private void save(View value,boolean replace)throws IOException{
        JsonObject json=new JsonObject();json.addProperty("schema",1);json.addProperty("islandId",access.islandId());json.addProperty("ownerId",access.ownerId());
        json.addProperty("revision",value.revision());json.addProperty("visibility",value.visibility().name());json.addProperty("admission",value.admission().name());json.addProperty("approval",value.approval());
        ProfileFiles.atomic(file,json,replace);
    }
    private static boolean bool(JsonObject json,String key)throws IOException{
        if(!json.has(key)||!json.get(key).isJsonPrimitive()||!json.getAsJsonPrimitive(key).isBoolean())throw new IOException("Policy flag");return json.get(key).getAsBoolean();
    }
}
