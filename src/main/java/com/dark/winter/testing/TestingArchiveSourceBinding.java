// Read-only exact path-role evidence for one stopped same-island transition.
package com.dark.winter.testing;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only exact path-role evidence for one stopped same-island transition. No aliases supplied as arbitrary strings. */
final class TestingArchiveSourceBinding {
    private static final Set<String> ACL=Set.of("schema","islandId","ownerId","limit","epoch","privacy","members","allowlist","blocked","trustedLeads","sha256");
    private record Stamp(Path file,byte[] bytes){}
    static final class Proof {
        final WorldAuthority.Certificate certificate;final LeaseFence.Fence fence;final Map<String,String> roles;
        final List<Stamp> stamps;final WorldAuthority.Genesis genesis;final AtomicBoolean used=new AtomicBoolean();
        Proof(WorldAuthority.Certificate certificate,LeaseFence.Fence fence,Map<String,String> roles,List<Stamp> stamps,WorldAuthority.Genesis genesis){
            this.certificate=certificate;this.fence=fence;this.roles=Map.copyOf(roles);this.stamps=List.copyOf(stamps);this.genesis=genesis;
        }
        void current()throws IOException{
            fence.requireUsable();
            for(Stamp stamp:stamps){byte[] actual=optional(stamp.file(),stamp.bytes()==null?65536:Math.max(65536,stamp.bytes().length));
                if(!Arrays.equals(stamp.bytes(),actual))throw new IOException("Same-island source metadata changed during archive preparation");}
        }
        void claim(TestingArchive.Version root)throws IOException{
            current();if(!root.source().equals(roles.get("target")))throw new IOException("Snapshot does not belong to the exact stopped target role");
            if(!used.compareAndSet(false,true))throw new IOException("Stopped source binding has already been used");
        }
    }
    private TestingArchiveSourceBinding(){}
    static Proof capture(Path author,Path current,Path target,WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
        if(author==null||current==null||target==null||certificate==null||fence==null)throw new IOException("Missing stopped same-island path or verified authority fence");
        fence.requireUsable();WorldAuthority.Genesis root=WorldAuthority.verify(certificate);
        if(!certificate.authorityId().equals(fence.authorityId()))throw new IOException("Same-island source fence belongs to another authority");
        Map<String,Path> paths=new LinkedHashMap<>();paths.put("author",TestingArchive.checkedSave(author,true));paths.put("current",TestingArchive.checkedSave(current,true));paths.put("target",TestingArchive.checkedSave(target,true));
        Map<String,String> roles=new TreeMap<>();List<Stamp> stamps=new ArrayList<>();Set<Path> visited=new HashSet<>();
        for(var role:paths.entrySet()){
            Path world=role.getValue();roles.put(role.getKey(),TestingArchive.worldKey(world));if(!visited.add(world))continue;
            safe(world);Path marker=world.resolve("data/darkwinter-testing.properties");
            byte[] markerBytes=required(marker,65536);Properties properties=new Properties();
            try(InputStream input=new ByteArrayInputStream(markerBytes)){properties.load(input);}catch(IllegalArgumentException bad){throw new IOException("Invalid source Testing marker",bad);}
            if(!Set.of("1","2").contains(properties.getProperty("schema",""))||!"testing".equals(properties.getProperty("mode")))throw new IOException("Invalid marked source role");
            stamps.add(new Stamp(marker,markerBytes));
            Path access=world.resolve("data/darkwinter-online/access.json");byte[] accessBytes=required(access,512*1024);
            validateAccess(accessBytes,certificate);
            stamps.add(new Stamp(access,accessBytes));
            Path authority=world.resolve("data/darkwinter-online/authority.json");byte[] authorityBytes=optional(authority,65536);stamps.add(new Stamp(authority,authorityBytes));
            if(authorityBytes!=null)validateAuthority(authorityBytes,certificate,fence);
        }
        Proof proof=new Proof(certificate,fence,roles,stamps,root);proof.current();return proof;
    }
    /** Present historical public metadata must match too; pre-Online snapshots may omit both files. */
    static void recorded(String path,byte[] bytes,WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
        if(path.equals("data/darkwinter-online/access.json"))validateAccess(bytes,certificate);
        else if(path.equals("data/darkwinter-online/authority.json"))validateAuthority(bytes,certificate,fence);
        else throw new IOException("Unexpected recorded source metadata");
    }
    private static void validateAccess(byte[] bytes,WorldAuthority.Certificate certificate)throws IOException{
        JsonObject acl=ProfileFiles.parse(bytes,512*1024);ProfileFiles.fields(acl,ACL,Set.of());JsonObject body=acl.deepCopy();
        String digest=ProfileFiles.string(body,"sha256",64);body.remove("sha256");
        if(ProfileFiles.number(acl,"schema")!=1||!digest.equals(TestingArchive.hashBytes(ProfileFiles.bytes(body))))throw new IOException("Invalid same-island source ACL checksum");
        WorldAuthority.requireAccess(certificate,ProfileFiles.string(acl,"islandId",36),ProfileFiles.string(acl,"ownerId",64));
    }
    private static void validateAuthority(byte[] bytes,WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
        JsonObject doc=ProfileFiles.parse(bytes,65536);ProfileFiles.fields(doc,Set.of("schema","certificate","policy"),Set.of());
        if(ProfileFiles.number(doc,"schema")!=1||!doc.get("certificate").isJsonObject()||!doc.get("policy").isJsonObject())throw new IOException("Invalid source public authority");
        var existing=WorldAuthority.certificateFromJson(doc.getAsJsonObject("certificate"));var root=WorldAuthority.verify(certificate);
        if(!existing.authorityId().equals(certificate.authorityId()))throw new IOException("Foreign authority in a stopped source role");
        WorldAuthority.requireAccess(existing,root.islandId().toString(),root.ownerId());
        var policy=WorldAuthority.verifyPolicy(existing,WorldAuthority.signedFromJson(doc.getAsJsonObject("policy")));
        if(policy.revision()>fence.policyRevision()||policy.revision()==fence.policyRevision()&&!policy.sha256().equals(fence.policySha256()))
            throw new IOException("Source role policy conflicts with the current verified fence");
    }
    private static byte[] required(Path file,int limit)throws IOException {byte[] value=optional(file,limit);if(value==null)throw new IOException("Missing stopped same-island source metadata");return value;}
    private static byte[] optional(Path file,int limit)throws IOException{
        safe(file);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw new IOException("Invalid stopped source metadata path/size");
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Stopped source metadata grew");return bytes;}
    }
    private static void safe(Path path)throws IOException{
        for(Path part=path.toAbsolutePath().normalize();part!=null;part=part.getParent())if(Files.isSymbolicLink(part)||Files.exists(part,LinkOption.NOFOLLOW_LINKS)&&!part.toRealPath().equals(part))throw new IOException("Linked stopped source metadata");
    }
}
