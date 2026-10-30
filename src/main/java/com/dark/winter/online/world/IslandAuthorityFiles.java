// Reads and writes public world-owner certificate and policy documents.
package com.dark.winter.online.world;

import com.dark.winter.online.authority.WorldAuthority;
import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Public owner-root documents only. The caller must pin the returned authority outside the save.
 * This wrapper never enrolls a node, changes an ACL owner, stores a lease or snapshots a live world.
 */
public final class IslandAuthorityFiles {
    public record Document(WorldAuthority.Certificate certificate, SignedPayload policy) {
        public Document { Objects.requireNonNull(certificate); Objects.requireNonNull(policy); }
    }
    private static final int LIMIT=65536,ACL_LIMIT=512*1024;
    private static final Set<String> ACL_FIELDS=Set.of("schema","islandId","ownerId","limit","epoch","privacy","members","allowlist","blocked","trustedLeads","sha256");
    private IslandAuthorityFiles(){}

    /** Read-only: absence never creates a directory, lock, key or certificate. This is not an external-pin check. */
    public static Optional<Document> load(Path markedWorld,IslandAccess.View access)throws IOException {
        Context context=context(markedWorld,access);
        return read(context);
    }
    /**
     * verifiedGenesisContentSHA must come from a caller-verified immutable snapshot/content root.
     * It is not recomputed from a running world here. A delegate's keys can never create the owner root.
     */
    public static Document create(Path markedWorld,IslandAccess.View access,IdentityStore owner,
                                  String verifiedGenesisContentSHA,String pinnedNodeId,Instant created)throws IOException {
        Objects.requireNonNull(owner);Objects.requireNonNull(created);
        Context initial=context(markedWorld,access);
        if(!owner.profile().id().equals(access.ownerId()))throw new IOException("Only the permanent island owner may create its authority");
        if(verifiedGenesisContentSHA==null||!verifiedGenesisContentSHA.matches("[0-9a-f]{64}")
                ||pinnedNodeId==null||!pinnedNodeId.matches("dw1_[A-Za-z0-9_-]{43}"))
            throw new IOException("Invalid verified genesis root or pinned node");
        Optional<Document> before=read(initial);
        if(before.isPresent())return identicalGenesis(before.get(),verifiedGenesisContentSHA,pinnedNodeId);
        try(Locked ignored=lock(initial)){
            Context current=context(markedWorld,access);Optional<Document> existing=read(current);
            if(existing.isPresent())return identicalGenesis(existing.get(),verifiedGenesisContentSHA,pinnedNodeId);
            var certificate=WorldAuthority.issue(owner,UUID.fromString(access.islandId()),verifiedGenesisContentSHA,pinnedNodeId,created);
            var policy=WorldAuthority.policy(owner,certificate,0,List.of(),created);
            Document result=new Document(certificate,policy);validate(result,access);
            commit(current,access,null,result);return result;
        }
    }
    /** Same signed policy is idempotent; a new signed policy must advance revision and cannot roll time backwards. */
    public static Document updatePolicy(Path markedWorld,IslandAccess.View access,SignedPayload policy)throws IOException {
        Context initial=context(markedWorld,access);Document before=required(read(initial));
        var proposed=WorldAuthority.verifyPolicy(before.certificate(),policy);
        // Reject an invalid/foreign envelope before even creating the short-lived lock file.
        try(Locked ignored=lock(initial)){
            Context current=context(markedWorld,access);Document previous=required(read(current));
            if(!previous.certificate().authorityId().equals(before.certificate().authorityId()))
                throw new IOException("Island authority changed while preparing policy update");
            var old=WorldAuthority.verifyPolicy(previous.certificate(),previous.policy());
            var next=WorldAuthority.verifyPolicy(previous.certificate(),policy);
            if(Arrays.equals(ProfileFiles.bytes(new com.google.gson.Gson().toJsonTree(previous.policy())),
                             ProfileFiles.bytes(new com.google.gson.Gson().toJsonTree(policy))))return previous;
            if(next.revision()<=old.revision()||next.issuedAt()<old.issuedAt())
                throw new IOException("Owner policy revision or time did not advance safely");
            Document result=new Document(previous.certificate(),policy);validate(result,access);
            commit(current,access,current.authorityBytes(),result);return result;
        }
    }
    /**
     * Restore the same externally pinned public root after an ordinary stopped-save rollback.
     * Only an opaque, currently usable node Fence can authorize this; no new root is issued.
     * Caller also owns external pins, stopped-save lifecycle and same-island binding.
     */
    public static Document installVerified(Path markedWorld,IslandAccess.View access,Document document,
                                           com.dark.winter.online.authority.LeaseFence.Fence fence)throws IOException {
        Objects.requireNonNull(document);Objects.requireNonNull(fence);fence.requireUsable();
        validate(document,access);
        var policy=WorldAuthority.verifyPolicy(document.certificate(),document.policy());
        if(!document.certificate().authorityId().equals(fence.authorityId())||policy.revision()!=fence.policyRevision()
                ||!policy.sha256().equals(fence.policySha256()))throw new IOException("Public authority differs from verified node fence");
        Context initial=context(markedWorld,access);
        try(Locked ignored=lock(initial)){
            Context current=context(markedWorld,access);Optional<Document> old=read(current);
            if(old.isPresent()){
                if(!old.get().certificate().authorityId().equals(document.certificate().authorityId()))
                    throw new IOException("Stopped world contains another owner authority");
                var previous=WorldAuthority.verifyPolicy(old.get().certificate(),old.get().policy());
                if(previous.revision()>policy.revision()||previous.revision()==policy.revision()&&!previous.sha256().equals(policy.sha256()))
                    throw new IOException("Stopped world owner policy conflicts with the verified node");
                if(previous.sha256().equals(policy.sha256())){fence.requireUsable();return old.get();}
            }
            fence.requireUsable();commit(current,access,current.authorityBytes(),document);return document;
        }
    }
    private static Document identicalGenesis(Document document,String sha,String node)throws IOException {
        var genesis=WorldAuthority.verify(document.certificate());
        if(!genesis.genesisSha256().equals(sha)||!genesis.coordinatorId().equals(node))
            throw new IOException("Existing owner authority has a different immutable genesis or pinned node");
        return document;
    }
    private static Document required(Optional<Document> value)throws IOException {
        if(value.isEmpty())throw new IOException("Create the permanent owner authority first");return value.get();
    }
    private record Context(Path world,Path directory,Path file,byte[] markerBytes,byte[] accessBytes,byte[] authorityBytes){}
    private static Context context(Path requested,IslandAccess.View access)throws IOException {
        Objects.requireNonNull(requested);Objects.requireNonNull(access);
        Path world=requested.toAbsolutePath().normalize();safe(world);
        if(!Files.isDirectory(world,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing island directory");
        Path marker=world.resolve("data/darkwinter-testing.properties");
        byte[] markerBytes=bytes(marker,65536);Properties properties=new Properties();
        try(InputStream input=new ByteArrayInputStream(markerBytes)){properties.load(input);}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid Testing marker",invalid);}
        String markerSchema=properties.getProperty("schema");
        if(!("1".equals(markerSchema)||"2".equals(markerSchema))||!"testing".equals(properties.getProperty("mode")))
            throw new IOException("A valid marked Testing island is required");
        Path directory=world.resolve("data/darkwinter-online"),file=directory.resolve("authority.json");
        safe(directory);safe(file);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Open the island access store first");
        byte[] accessBytes=bytes(directory.resolve("access.json"),ACL_LIMIT);
        JsonObject acl=ProfileFiles.parse(accessBytes,ACL_LIMIT);ProfileFiles.fields(acl,ACL_FIELDS,Set.of());
        JsonObject body=acl.deepCopy();String expected=ProfileFiles.string(body,"sha256",64);body.remove("sha256");
        if(!expected.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),sha(ProfileFiles.bytes(body)).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("Island access checksum mismatch");
        if(integer(acl,"schema")!=1||!ProfileFiles.string(acl,"islandId",36).equals(access.islandId())
                ||!ProfileFiles.string(acl,"ownerId",64).equals(access.ownerId()))
            throw new IOException("Permanent island access differs from the supplied owner/world");
        try{
            if(!UUID.fromString(access.islandId()).toString().equals(access.islandId())||!access.ownerId().matches("dw1_[A-Za-z0-9_-]{43}"))
                throw new IOException("Invalid permanent island owner/world");
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid permanent island UUID",invalid);}
        byte[] authorityBytes=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?bytes(file,LIMIT):null;
        return new Context(world,directory,file,markerBytes,accessBytes,authorityBytes);
    }
    private static Optional<Document> read(Context context)throws IOException {
        if(context.authorityBytes()==null)return Optional.empty();
        JsonObject object=ProfileFiles.parse(context.authorityBytes(),LIMIT);
        ProfileFiles.fields(object,Set.of("schema","certificate","policy"),Set.of());
        if(integer(object,"schema")!=1||!object.get("certificate").isJsonObject()||!object.get("policy").isJsonObject())
            throw new IOException("Unsupported or invalid island authority document");
        Document document=new Document(WorldAuthority.certificateFromJson(object.getAsJsonObject("certificate")),
                WorldAuthority.signedFromJson(object.getAsJsonObject("policy")));
        JsonObject acl=ProfileFiles.parse(context.accessBytes(),ACL_LIMIT);
        WorldAuthority.requireAccess(document.certificate(),ProfileFiles.string(acl,"islandId",36),ProfileFiles.string(acl,"ownerId",64));
        WorldAuthority.verifyPolicy(document.certificate(),document.policy());return Optional.of(document);
    }
    private static void validate(Document document,IslandAccess.View access)throws IOException {
        WorldAuthority.requireAccess(document.certificate(),access.islandId(),access.ownerId());
        WorldAuthority.verifyPolicy(document.certificate(),document.policy());
    }
    private static void commit(Context expected,IslandAccess.View access,byte[] previous,Document document)throws IOException {
        JsonObject json=new JsonObject();json.addProperty("schema",1);json.add("certificate",WorldAuthority.certificateToJson(document.certificate()));
        json.add("policy",new com.google.gson.Gson().toJsonTree(document.policy()));
        byte[] encoded=ProfileFiles.bytes(json);if(encoded.length>LIMIT)throw new IOException("Island authority document exceeds64KiB");
        // Full reread compares bytes, not timestamps. Cooperative writers also hold the same OS lock.
        Context actual=context(expected.world(),access);
        if(!Arrays.equals(expected.markerBytes(),actual.markerBytes())||!Arrays.equals(expected.accessBytes(),actual.accessBytes())
                ||!Arrays.equals(previous,actual.authorityBytes()))throw new IOException("Island metadata changed before authority publication");
        ProfileFiles.atomic(expected.file(),json,previous!=null);
    }
    private record Locked(FileChannel channel,FileLock lock)implements AutoCloseable{
        @Override public void close()throws IOException{try{lock.release();}finally{channel.close();}}
    }
    private static Locked lock(Context context)throws IOException {
        Path file=context.directory().resolve(".authority.lock");safe(file);
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=0))
            throw new IOException("Invalid authority lock");
        FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Island authority is being updated");return new Locked(channel,lock);}
        catch(IOException|OverlappingFileLockException failure){channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Island authority is being updated",failure);}
    }
    private static byte[] bytes(Path file,int limit)throws IOException {
        safe(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("Missing or oversized island metadata");
        byte[] result=Files.readAllBytes(file);if(result.length>limit)throw new IOException("Island metadata grew beyond its limit");return result;
    }
    private static void safe(Path path)throws IOException {
        for(Path part=path.toAbsolutePath().normalize();part!=null;part=part.getParent()){
            if(Files.isSymbolicLink(part))throw new IOException("Linked island authority path");
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)&&!part.toRealPath().equals(part.toAbsolutePath().normalize()))
                throw new IOException("Redirected island authority path");
        }
        if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){
            try{Object count=Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS);if(count instanceof Number value&&value.longValue()!=1)throw new IOException("Hard-linked island authority file");}
            catch(UnsupportedOperationException ignored){/* No portable link-count view; atomic replacement never edits an existing inode. */}
        }
    }
    private static long integer(JsonObject object,String key)throws IOException {
        long value=ProfileFiles.number(object,key);if(!Long.toString(value).equals(object.get(key).getAsString()))throw new IOException("Noncanonical island authority integer");return value;
    }
    private static String sha(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
}
