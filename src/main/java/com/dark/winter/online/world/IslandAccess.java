// Persists island ownership, admission policy, roles, and permissions.
package com.dark.winter.online.world;

import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Persistent island authority. Stores public IDs and policy only; admission/host simulation remains server-owned. */
public final class IslandAccess implements AutoCloseable {
    public enum Role {OWNER,ASSISTANT,EDITOR,TESTER,OBSERVER}
    public enum Permission {VIEW,BUILD,BREAK,INTERACT,TERRAIN,BIOME,WATER,VEGETATION,OBJECTS,SYSTEMS,START_TEST,STOP_TEST,KEEP_TEST,MANAGE_ZONES,HISTORY,UNDO_OWN,UNDO_ANY,VERSIONS,IMPORT,EXPORT,INVITE,KICK,ASSIGN_ROLES,TRANSFER_HOST,GLOBAL_VOICE,SCENARIOS,VOICE_SPEAK,VOICE_LISTEN}
    public enum Privacy {PRIVATE,FRIENDS,INVITE_ONLY,PUBLIC}
    public record Member(String id,Role role,Map<Permission,Boolean> overrides) {
        public Member {overrides=Map.copyOf(overrides);}
    }
    public record View(String islandId,String ownerId,int limit,long epoch,Privacy privacy,Map<String,Member> members,Set<String> allowlist,Set<String> blocked,Set<String> trustedLeads) {
        public View {members=Map.copyOf(members);allowlist=Set.copyOf(allowlist);blocked=Set.copyOf(blocked);trustedLeads=Set.copyOf(trustedLeads);}
    }
    private static final int MAX_MEMBERS=1024,LIMIT=512*1024;
    private final Path world,file;private final FileChannel channel;private final FileLock lock;
    private View state;private boolean closed;
    private IslandAccess(Path world,FileChannel channel,FileLock lock,View state){this.world=world;this.file=world.resolve("data/darkwinter-online/access.json");this.channel=channel;this.lock=lock;this.state=state;}
    public static IslandAccess open(Path markedWorld,IdentityProfile initialOwner)throws IOException {return open(markedWorld,initialOwner,null);}
    /** A version captured before Online existed may resume its externally checked current authority. */
    public static IslandAccess open(Path markedWorld,IdentityProfile initialOwner,View continuation)throws IOException {
        if(continuation!=null){validate(continuation);if(!can(continuation,initialOwner.id(),Permission.TRANSFER_HOST)||!continuation.trustedLeads().contains(initialOwner.id()))throw new IOException("Untrusted continuation");}
        IdentityStore.validateProfile(initialOwner);Path world=validateWorld(markedWorld),directory=world.resolve("data/darkwinter-online"),file=directory.resolve("access.json");
        safePath(directory);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))decode(ProfileFiles.read(file,LIMIT));
        Files.createDirectories(directory);safePath(directory);
        Path lockPath=directory.resolve(".access.lock");safePath(lockPath);
        FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;
        try{
            lock=channel.tryLock();if(lock==null)throw new IOException("Island access is already open");
            View state;
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))state=decode(ProfileFiles.read(file,LIMIT));
            else{
                Member owner=new Member(initialOwner.id(),Role.OWNER,Map.of());
                state=continuation==null?new View(UUID.randomUUID().toString(),initialOwner.id(),8,0,Privacy.PRIVATE,Map.of(owner.id(),owner),Set.of(owner.id()),Set.of(),Set.of(owner.id())):continuation;
                ProfileFiles.atomic(file,encode(state),false);
            }
            return new IslandAccess(world,channel,lock,state);
        }catch(IOException|RuntimeException failure){
            if(lock!=null)lock.release();channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Invalid or locked island access",failure);
        }
    }
    private static Path validateWorld(Path requested)throws IOException {
        Path world=requested.toAbsolutePath().normalize();safePath(world);
        if(!Files.isDirectory(world,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing island directory");
        Path marker=world.resolve("data/darkwinter-testing.properties");safePath(marker);
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>65536)throw new IOException("Online access requires a marked Testing island");
        Properties properties=new Properties();try(InputStream input=Files.newInputStream(marker)){properties.load(input);}
        if(!"1".equals(properties.getProperty("schema"))&&!"2".equals(properties.getProperty("schema"))||!"testing".equals(properties.getProperty("mode")))
            throw new IOException("Online access requires a valid Testing schema 1 or 2 marker");
        return world.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    private static void safePath(Path path)throws IOException {for(Path parent=path.toAbsolutePath().normalize();parent!=null;parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException("Linked island policy path");}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Island access is closed");}
    public synchronized View snapshot(){ensureOpen();return state;}
    public synchronized String islandId(){return snapshot().islandId();}
    public synchronized String ownerId(){return snapshot().ownerId();}
    public synchronized int limit(){return snapshot().limit();}
    public synchronized long epoch(){return snapshot().epoch();}
    public synchronized Privacy privacy(){return snapshot().privacy();}
    public synchronized Map<String,Member> members(){return snapshot().members();}
    public synchronized Role roleOf(String actor){Member member=snapshot().members().get(actor);return member==null?Role.OBSERVER:member.role();}
    public synchronized boolean blocked(String actor){return snapshot().blocked().contains(actor);}
    public synchronized boolean allowed(String actor){return snapshot().allowlist().contains(actor)&&!snapshot().blocked().contains(actor);}
    public synchronized boolean trustedLead(String actor){return snapshot().trustedLeads().contains(actor)&&can(actor,Permission.TRANSFER_HOST);}
    public synchronized boolean can(String actor,Permission permission){ensureOpen();return can(state,actor,Objects.requireNonNull(permission));}
    private static boolean can(View state,String actor,Permission permission){
        if(actor==null||state.blocked().contains(actor)||!state.allowlist().contains(actor))return false;
        if(state.ownerId().equals(actor))return true;
        Member member=state.members().get(actor);if(member==null)return false;
        Boolean override=member.overrides().get(permission);return override!=null?override:defaults(member.role()).contains(permission);
    }
    private static Set<Permission> defaults(Role role){
        return switch(role){
            case OWNER,ASSISTANT->EnumSet.allOf(Permission.class);
            case EDITOR->EnumSet.of(Permission.VIEW,Permission.BUILD,Permission.BREAK,Permission.INTERACT,Permission.TERRAIN,Permission.BIOME,Permission.WATER,Permission.VEGETATION,Permission.OBJECTS,Permission.SYSTEMS,Permission.STOP_TEST,Permission.HISTORY,Permission.UNDO_OWN,Permission.EXPORT,Permission.SCENARIOS,Permission.VOICE_SPEAK,Permission.VOICE_LISTEN);
            case TESTER->EnumSet.of(Permission.VIEW,Permission.INTERACT,Permission.STOP_TEST,Permission.VOICE_SPEAK,Permission.VOICE_LISTEN);
            case OBSERVER->EnumSet.of(Permission.VIEW,Permission.VOICE_SPEAK,Permission.VOICE_LISTEN);
        };
    }
    private void require(String actor,Permission permission)throws IOException {id(actor);if(!can(actor,permission))throw new IOException("Island permission is denied");}
    private void mutableTarget(String actor,String target)throws IOException {
        id(target);if(target.equals(state.ownerId()))throw new IOException("The island owner is immutable");
        Member old=state.members().get(target);
        if(!actor.equals(state.ownerId())&&old!=null&&old.role()==Role.ASSISTANT)throw new IOException("Only the owner may manage an assistant");
    }
    private void grantCeiling(String actor,Member member)throws IOException {
        if(actor.equals(state.ownerId()))return;
        if(member.role()==Role.OWNER||member.role()==Role.ASSISTANT)throw new IOException("Only the owner may appoint an assistant");
        for(Permission permission:Permission.values()){
            boolean granted=member.overrides().getOrDefault(permission,defaults(member.role()).contains(permission));
            if(granted&&!can(actor,permission))throw new IOException("A delegate cannot grant a permission they do not own");
        }
    }
    /** Complete authority/ceiling/bounds preflight; does not admit a member or write world metadata. */
    public synchronized void validateRoleGrant(String actor,String target,Role role)throws IOException {planRole(actor,target,role);}
    public synchronized void setRole(String actor,String target,Role role)throws IOException {commit(planRole(actor,target,role));}
    private View planRole(String actor,String target,Role role)throws IOException {
        require(actor,Permission.ASSIGN_ROLES);mutableTarget(actor,target);Objects.requireNonNull(role);
        if(role==Role.OWNER)throw new IOException("Ownership cannot be assigned as a member role");
        Member old=state.members().get(target),changed=new Member(target,role,old==null?Map.of():old.overrides());grantCeiling(actor,changed);
        Map<String,Member> members=new HashMap<>(state.members());members.put(target,changed);Set<String> allowed=new HashSet<>(state.allowlist());allowed.add(target);
        View next=copy(members,allowed,state.blocked(),state.trustedLeads(),state.privacy(),state.limit(),state.epoch());validate(next);return next;
    }
    public synchronized void setPermission(String actor,String target,Permission permission,boolean allowed)throws IOException {
        permission(actor,target,permission,allowed);
    }
    public synchronized void clearPermission(String actor,String target,Permission permission)throws IOException {permission(actor,target,permission,null);}
    private void permission(String actor,String target,Permission permission,Boolean allowed)throws IOException {
        require(actor,Permission.ASSIGN_ROLES);mutableTarget(actor,target);Objects.requireNonNull(permission);
        Member previous=state.members().get(target);if(previous==null)throw new IOException("Unknown island member");
        Map<Permission,Boolean> overrides=new EnumMap<>(Permission.class);overrides.putAll(previous.overrides());if(allowed==null)overrides.remove(permission);else overrides.put(permission,allowed);
        Member changed=new Member(target,previous.role(),overrides);grantCeiling(actor,changed);
        Map<String,Member> members=new HashMap<>(state.members());members.put(target,changed);
        commit(copy(members,state.allowlist(),state.blocked(),state.trustedLeads(),state.privacy(),state.limit(),state.epoch()));
    }
    /** Admission preflight without a role grant, token use, or any file mutation. */
    public synchronized void validateAllow(String actor,String target)throws IOException {planAllow(actor,target);}
    public synchronized void allow(String actor,String target)throws IOException {commit(planAllow(actor,target));}
    private View planAllow(String actor,String target)throws IOException {
        require(actor,Permission.INVITE);id(target);Map<String,Member> members=new HashMap<>(state.members());members.putIfAbsent(target,new Member(target,Role.OBSERVER,Map.of()));
        Set<String> allowed=new HashSet<>(state.allowlist());allowed.add(target);
        View next=copy(members,allowed,state.blocked(),state.trustedLeads(),state.privacy(),state.limit(),state.epoch());validate(next);return next;
    }
    public synchronized void block(String actor,String target)throws IOException {
        require(actor,Permission.KICK);mutableTarget(actor,target);Set<String> blocked=new HashSet<>(state.blocked());blocked.add(target);
        Set<String> trusted=new HashSet<>(state.trustedLeads());trusted.remove(target);commit(copy(state.members(),state.allowlist(),blocked,trusted,state.privacy(),state.limit(),state.epoch()));
    }
    public synchronized void unblock(String actor,String target)throws IOException {
        require(actor,Permission.KICK);mutableTarget(actor,target);Set<String> blocked=new HashSet<>(state.blocked());blocked.remove(target);
        commit(copy(state.members(),state.allowlist(),blocked,state.trustedLeads(),state.privacy(),state.limit(),state.epoch()));
    }
    public synchronized void configure(String actor,Privacy privacy,int limit)throws IOException {
        require(actor,Permission.SYSTEMS);Objects.requireNonNull(privacy);
        if(!actor.equals(state.ownerId())&&roleOf(actor)!=Role.ASSISTANT)throw new IOException("Island-wide configuration requires owner or delegated assistant authority");
        commit(copy(state.members(),state.allowlist(),state.blocked(),state.trustedLeads(),privacy,limit,state.epoch()));
    }
    public synchronized void trustLead(String actor,String target,boolean trusted)throws IOException {
        if(!state.ownerId().equals(actor)||!can(actor,Permission.TRANSFER_HOST))throw new IOException("Only the owner may designate trusted successors");
        id(target);if(target.equals(state.ownerId()))throw new IOException("Owner trust is immutable");
        Set<String> leads=new HashSet<>(state.trustedLeads());
        if(trusted){if(!can(target,Permission.TRANSFER_HOST))throw new IOException("Successor lacks transfer authority");leads.add(target);}else leads.remove(target);
        commit(copy(state.members(),state.allowlist(),state.blocked(),leads,state.privacy(),state.limit(),state.epoch()));
    }
    public synchronized long advanceEpoch(String actor,long expectedEpoch)throws IOException {
        require(actor,Permission.TRANSFER_HOST);
        if(!state.ownerId().equals(actor)&&!trustedLead(actor))throw new IOException("A host successor must be explicitly trusted");
        if(expectedEpoch!=state.epoch()||state.epoch()==Long.MAX_VALUE)throw new IOException("Stale or exhausted host authority epoch");
        long next=state.epoch()+1;commit(copy(state.members(),state.allowlist(),state.blocked(),state.trustedLeads(),state.privacy(),state.limit(),next));return next;
    }
    /** Preserve current access decisions across an authorized stopped-save copy/rollback. Ownership never changes. */
    public synchronized long resume(String actor,View live)throws IOException {
        ensureOpen();Objects.requireNonNull(live);validate(live);
        if(!state.islandId().equals(live.islandId())||!state.ownerId().equals(live.ownerId()))throw new IOException("Another island authority");
        if(!can(live,actor,Permission.TRANSFER_HOST)||!live.trustedLeads().contains(actor))throw new IOException("Untrusted live coordinator");
        long previous=Math.max(state.epoch(),live.epoch());if(previous==Long.MAX_VALUE)throw new IOException("Exhausted island authority epoch");
        View next=new View(live.islandId(),live.ownerId(),live.limit(),previous+1,live.privacy(),live.members(),live.allowlist(),live.blocked(),live.trustedLeads());
        commit(next);return next.epoch();
    }
    private View copy(Map<String,Member> members,Set<String> allowed,Set<String> blocked,Set<String> trusted,Privacy privacy,int limit,long epoch){
        View candidate=new View(state.islandId(),state.ownerId(),limit,epoch,privacy,members,allowed,blocked,trusted);
        Set<String> eligible=new HashSet<>(trusted);eligible.removeIf(id->!can(candidate,id,Permission.TRANSFER_HOST));
        return new View(state.islandId(),state.ownerId(),limit,epoch,privacy,members,allowed,blocked,eligible);
    }
    private void commit(View next)throws IOException {
        ensureOpen();validate(next);safePath(file);validateWorld(world);
        JsonObject current=ProfileFiles.read(file,LIMIT);
        if(!state.equals(decode(current)))throw new IOException("Island policy changed outside this authoritative store");
        JsonObject encoded=encode(next);if(ProfileFiles.bytes(encoded).length>LIMIT)throw new IOException("Island access storage limit reached");
        ProfileFiles.atomic(file,encoded,true);state=next;
    }
    private static void validate(View state)throws IOException {
        uuid(state.islandId());id(state.ownerId());
        if(state.limit()<2||state.limit()>16||state.epoch()<0||state.privacy()==null||state.members().size()>MAX_MEMBERS||state.allowlist().size()>MAX_MEMBERS||state.blocked().size()>MAX_MEMBERS||state.trustedLeads().size()>16)
            throw new IOException("Invalid island access bounds");
        Member owner=state.members().get(state.ownerId());
        if(owner==null||owner.role()!=Role.OWNER||!owner.overrides().isEmpty()||!state.allowlist().contains(state.ownerId())||state.blocked().contains(state.ownerId())||!state.trustedLeads().contains(state.ownerId()))
            throw new IOException("Invalid immutable island owner");
        for(var entry:state.members().entrySet()){
            id(entry.getKey());Member member=entry.getValue();if(!entry.getKey().equals(member.id())||member.role()==null||!entry.getKey().equals(state.ownerId())&&member.role()==Role.OWNER)
                throw new IOException("Invalid island member role");
        }
        for(String value:state.allowlist()){id(value);if(!state.members().containsKey(value))throw new IOException("Allowlist references an unknown member");}
        for(String value:state.blocked())id(value);
        for(String value:state.trustedLeads()){id(value);if(!can(state,value,Permission.TRANSFER_HOST))throw new IOException("Invalid trusted successor");}
    }
    private static JsonObject encode(View state)throws IOException {
        validate(state);JsonObject document=new JsonObject();document.addProperty("schema",1);document.addProperty("islandId",state.islandId());document.addProperty("ownerId",state.ownerId());
        document.addProperty("limit",state.limit());document.addProperty("epoch",state.epoch());document.addProperty("privacy",state.privacy().name());
        JsonObject members=new JsonObject();for(String key:new TreeSet<>(state.members().keySet())){
            Member member=state.members().get(key);JsonObject row=new JsonObject();row.addProperty("role",member.role().name());JsonObject overrides=new JsonObject();
            for(Permission permission:Permission.values())if(member.overrides().containsKey(permission))overrides.addProperty(permission.name(),member.overrides().get(permission));row.add("overrides",overrides);members.add(key,row);
        }
        document.add("members",members);document.add("allowlist",array(state.allowlist()));document.add("blocked",array(state.blocked()));document.add("trustedLeads",array(state.trustedLeads()));
        document.addProperty("sha256",sha(document));return document;
    }
    private static JsonArray array(Set<String> values){JsonArray result=new JsonArray();for(String value:new TreeSet<>(values))result.add(value);return result;}
    private static View decode(JsonObject document)throws IOException {
        try{
            ProfileFiles.fields(document,Set.of("schema","islandId","ownerId","limit","epoch","privacy","members","allowlist","blocked","trustedLeads","sha256"),Set.of());
            JsonObject body=document.deepCopy();String expected=ProfileFiles.string(body,"sha256",64);body.remove("sha256");
            if(!expected.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),sha(body).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("Island access checksum mismatch");
            if(ProfileFiles.number(body,"schema")!=1)throw new IOException("Unsupported island access schema");
            Map<String,Member> members=new HashMap<>();JsonObject rows=body.getAsJsonObject("members");
            if(rows.size()>MAX_MEMBERS)throw new IOException("Too many island members");
            for(var entry:rows.entrySet()){
                JsonObject row=entry.getValue().getAsJsonObject();ProfileFiles.fields(row,Set.of("role","overrides"),Set.of());
                Map<Permission,Boolean> overrides=new EnumMap<>(Permission.class);
                for(var override:row.getAsJsonObject("overrides").entrySet()){
                    if(!override.getValue().isJsonPrimitive()||!override.getValue().getAsJsonPrimitive().isBoolean())throw new IOException("Invalid permission override");
                    overrides.put(Permission.valueOf(override.getKey()),override.getValue().getAsBoolean());
                }
                members.put(entry.getKey(),new Member(entry.getKey(),Role.valueOf(ProfileFiles.string(row,"role",32)),overrides));
            }
            View state=new View(ProfileFiles.string(body,"islandId",36),ProfileFiles.string(body,"ownerId",64),Math.toIntExact(ProfileFiles.number(body,"limit")),ProfileFiles.number(body,"epoch"),
                    Privacy.valueOf(ProfileFiles.string(body,"privacy",32)),members,set(body.getAsJsonArray("allowlist")),set(body.getAsJsonArray("blocked")),set(body.getAsJsonArray("trustedLeads")));
            validate(state);return state;
        }catch(RuntimeException invalid){throw new IOException("Invalid island access document",invalid);}
    }
    private static Set<String> set(JsonArray array)throws IOException {
        if(array.size()>MAX_MEMBERS)throw new IOException("Island access list limit reached");Set<String> result=new HashSet<>();
        for(JsonElement value:array){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!result.add(value.getAsString()))throw new IOException("Duplicate or invalid island access identity");}return result;
    }
    private static String sha(JsonObject body)throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ProfileFiles.bytes(body)));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private static void id(String value)throws IOException {if(value==null||!value.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid island participant ID");}
    private static void uuid(String value)throws IOException {try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Invalid island ID");}catch(RuntimeException invalid){throw new IOException("Invalid island ID",invalid);}}
    @Override public synchronized void close()throws IOException {if(closed)return;closed=true;lock.release();channel.close();}
}
