// Prepares immutable same-island archive companions for checkpoint replication.
package com.dark.winter.testing;

import com.dark.winter.online.identity.ProfileFiles;
import com.dark.winter.online.authority.WorldAuthority;
import com.dark.winter.online.authority.LeaseFence;
import com.dark.winter.online.replica.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Same-island immutable archive companion. No live-world scan, independent-copy
 * remapping, global archive publication or native activation occurs here.
 * Call preparation/verification/copying on a bounded I/O worker.
 */
public final class TestingReplicationArchive {
    public static final String PREFIX="replication/testing/v1/",MANIFEST=PREFIX+"manifest.json";
    private static final String DATA="data/darkwinter-testing/";
    private static final Gson GSON=new Gson();
    private static final long MIB=1024L*1024;
    public record Limits(int versions,int objects,int prefabs,long bytes,long fileBytes,long metadataBytes) {
        public Limits {
            if(versions<1||versions>256||objects<3||objects>8192||prefabs<0||prefabs>128||
                    bytes<1024||bytes>2L*1024*MIB||fileBytes<1||fileBytes>512*MIB||fileBytes>bytes||
                    metadataBytes<1024||metadataBytes>128*MIB)throw new IllegalArgumentException("Archive companion quota bounds");
        }
        public static Limits defaults(){return new Limits(256,4096,128,512*MIB,128*MIB,64*MIB);}
    }
    @FunctionalInterface public interface Reader {InputStream open(String relativePath)throws IOException;}
    @FunctionalInterface private interface VersionReader {TestingArchive.Version read(String id)throws IOException;}
    @FunctionalInterface private interface BlobReader {byte[] read(TestingArchive.Entry entry)throws IOException;}
    private record Item(String path,long bytes,String sha256) {
        ReplicaProtocol.Entry entry(){return new ReplicaProtocol.Entry(path,bytes,sha256);}
    }
    private static final class Dataset {
        final Map<String,TestingArchive.Version> versions=new TreeMap<>();
        final Map<String,byte[]> timelines=new TreeMap<>(),prefabs=new TreeMap<>();
        final Map<String,Long> objects=new TreeMap<>();
        final Map<String,String> foreignParents=new TreeMap<>();
        final Map<String,Integer> marks=new HashMap<>();
        byte[] legacy;String latest="";long metadata;
    }
    public static final class Prepared {
        private final Path directory;private final String rootId,sourceKey,manifestSha;
        private final List<ReplicaPublisher.SourceBlob> sources;
        private Prepared(Path directory,String rootId,String sourceKey,String sha,List<ReplicaPublisher.SourceBlob> sources) {
            this.directory=directory;this.rootId=rootId;this.sourceKey=sourceKey;manifestSha=sha;this.sources=List.copyOf(sources);
        }
        public Path directory(){return directory;}public String rootVersionId(){return rootId;}public String sourceKey(){return sourceKey;}
        public String manifestSha256(){return manifestSha;}
        /** Root native files and reserved companions share the existing bounded replica index/credits. */
        public List<ReplicaPublisher.SourceBlob> sources(){return sources;}
    }
    /** Only a completed verified restore can construct this provenance token. It grants no hosting permission. */
    public static final class Restored {
        private final Path directory;private final String rootId,oldSource,newSource,authority,contentRoot,bindingSha;
        private final Map<String,TestingArchive.Version> versions;private final Map<String,String> aliases,verifiedFiles;
        private Restored(Path directory,String rootId,String oldSource,String newSource,Map<String,TestingArchive.Version> versions,
                Map<String,String> aliases,String authority,String contentRoot,String bindingSha,Map<String,String> verifiedFiles){
            this.directory=directory;this.rootId=rootId;this.oldSource=oldSource;this.newSource=newSource;this.versions=Map.copyOf(versions);
            this.aliases=Map.copyOf(aliases);this.authority=authority;this.contentRoot=contentRoot;this.bindingSha=bindingSha;this.verifiedFiles=Map.copyOf(verifiedFiles);
        }
        public Path directory(){return directory;}public String rootVersionId(){return rootId;}
        public String oldSourceKey(){return oldSource;}public String newSourceKey(){return newSource;}
        public Map<String,TestingArchive.Version> versions(){return versions;}public boolean requiresScopedArchiveContext(){return true;}
        public Map<String,String> sourceAliases(){return aliases;}public String authorityId(){return authority;}
        public String contentRoot(){return contentRoot;}public String bindingSha256(){return bindingSha;}
        Map<String,String> verifiedFiles(){return verifiedFiles;}
    }
    /** Opaque one-prepare path-role evidence. No arbitrary source-key allowlist can be supplied. */
    public static final class SameIslandBinding {
        private final TestingArchiveSourceBinding.Proof proof;
        private SameIslandBinding(TestingArchiveSourceBinding.Proof proof){this.proof=proof;}
        public Map<String,String> sourceBindings(){return proof.roles;}
    }
    public static SameIslandBinding sameIsland(Path permanentAuthor,Path previousPublishedWorld,Path stoppedTarget,
            WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence)throws IOException{
        return new SameIslandBinding(TestingArchiveSourceBinding.capture(permanentAuthor,previousPublishedWorld,stoppedTarget,certificate,freshFence));
    }
    private record SourceScope(String rootSource,String timelineOwner,Map<String,String> roles,String authorityId,String islandId,String ownerId,
                               TestingArchiveSourceBinding.Proof proof,LeaseFence.Fence fence,WorldAuthority.Certificate certificate,Map<String,String> aliases) {
        static SourceScope strict(String source){return new SourceScope(source,source,Map.of(),"","","",null,null,null,Map.of());}
        boolean bound(){return !roles.isEmpty();}
        Set<String> keys(){return bound()?Set.copyOf(roles.values()):Set.of(rootSource);}
        boolean allows(String source){return keys().contains(source)||aliases.containsKey(source);}
        void current()throws IOException{if(proof!=null)proof.current();else if(fence!=null)fence.requireUsable();}
        void addFields(JsonObject target){
            target.addProperty("authorityId",authorityId);target.addProperty("islandId",islandId);target.addProperty("ownerId",ownerId);
            target.add("sourceBindings",GSON.toJsonTree(new TreeMap<>(roles)));target.add("sourceAliases",GSON.toJsonTree(new TreeMap<>(aliases)));
        }
    }
    private TestingReplicationArchive(){}

    /** Snapshots the complete current UUID prefab library and optional old prototype; never scans an active save. */
    public static Prepared prepare(TestingArchive.Version supplied,Path newPrivateDirectory,Limits limits)throws IOException {
        return prepareInternal(supplied,newPrivateDirectory,limits,null);
    }
    public static Prepared prepare(TestingArchive.Version supplied,Path newPrivateDirectory,Limits limits,SameIslandBinding binding)throws IOException {
        if(binding==null)throw new IOException("Missing explicit stopped same-island binding");
        return prepareInternal(supplied,newPrivateDirectory,limits,binding);
    }
    private static Prepared prepareInternal(TestingArchive.Version supplied,Path newPrivateDirectory,Limits limits,SameIslandBinding binding)throws IOException {
        TestingArchive.Version root=version(parse(versionBytes(supplied),8*MIB));
        SourceScope scope=SourceScope.strict(root.source());
        if(binding!=null){var proof=binding.proof;proof.claim(root);scope=new SourceScope(root.source(),proof.roles.get("author"),proof.roles,
            proof.certificate.authorityId(),proof.genesis.islandId().toString(),proof.genesis.ownerId(),proof,proof.fence,proof.certificate,inheritedAliases(proof));}
        final SourceScope captured=scope;captured.current();
        Dataset data=new Dataset();Path library=TestingArchive.root().getParent().resolve("prefabs");
        Map<String,byte[]> prefabFiles=directory(library,limits.prefabs()+1,32*MIB);
        for(var file:prefabFiles.entrySet()) {
            if(file.getKey().equals("latest.txt")){data.latest=new String(file.getValue(),StandardCharsets.UTF_8).strip();uuid(data.latest);continue;}
            if(!file.getKey().matches("[0-9a-f-]{36}\\.json"))throw new IOException("Unknown external prefab library file");
            String id=file.getKey().substring(0,36);uuid(id);validatePrefab(id,file.getValue());data.prefabs.put(id,file.getValue());
        }
        if(!data.latest.isEmpty()&&!data.prefabs.containsKey(data.latest))throw new IOException("Latest prefab points outside the captured library");
        Path legacy=TestingArchive.root().getParent().resolve("latest-blocks.json");
        if(Files.exists(legacy,LinkOption.NOFOLLOW_LINKS)){data.legacy=readFile(legacy,MIB);validateLegacy(data.legacy);}
        source(root.source());data.timelines.putAll(timelines(captured.timelineOwner()));
        data.metadata=externalBytes(data);if(data.metadata>limits.metadataBytes())throw new IOException("External scenario/prefab metadata quota");
        collect(root,captured,TestingArchive::read,entry->readFile(TestingArchive.verifiedBlob(entry),Math.min(limits.metadataBytes(),32*MIB)),data,limits,0);
        for(byte[] timeline:data.timelines.values())for(String id:scenarioReferences(timeline))
            collect(TestingArchive.read(id),captured,TestingArchive::read,entry->readFile(TestingArchive.verifiedBlob(entry),Math.min(limits.metadataBytes(),32*MIB)),data,limits,0);
        for(byte[] prefab:data.prefabs.values())for(String id:prefabReferences(prefab))
            collect(TestingArchive.read(id),captured,TestingArchive::read,entry->readFile(TestingArchive.verifiedBlob(entry),Math.min(limits.metadataBytes(),32*MIB)),data,limits,0);
        return seal(root,data,newPrivateDirectory,limits,captured,()->{
            captured.current();
            for(var version:data.versions.values())if(!Arrays.equals(versionBytes(TestingArchive.read(version.id())),versionBytes(version)))throw new IOException("Version metadata changed during companion capture");
            if(!same(data.timelines,timelines(captured.timelineOwner()))||!same(prefabFiles,directory(library,limits.prefabs()+1,32*MIB)))throw new IOException("External timeline/prefab library changed during capture");
            if(data.legacy!=null&&!Arrays.equals(data.legacy,readFile(legacy,MIB))||data.legacy==null&&Files.exists(legacy))throw new IOException("Legacy prefab changed during capture");
        });
    }
    @FunctionalInterface private interface Check {void run()throws IOException;}
    private static Prepared seal(TestingArchive.Version root,Dataset data,Path destination,Limits limits,SourceScope scope,Check finalCheck)throws IOException {
        Map<String,Item> descriptors=new TreeMap<>();Map<String,Path> sourceByHash=new HashMap<>();Map<String,byte[]> generated=new HashMap<>();
        Map<String,String> rootPaths=new HashMap<>();
        for(var file:root.files()) {
            checkWorldPath(file);Path blob=TestingArchive.verifiedBlob(file);safe(blob);sourceByHash.put(file.sha256(),blob);
            if(!ReplicaProtocol.derivedTransient(file.path())&&!ReplicaProtocol.authoritySidecar(file.path())) {
                descriptors.put(file.path(),new Item(file.path(),file.size(),file.sha256()));rootPaths.putIfAbsent(file.sha256(),file.path());
            }
        }
        JsonObject manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("kind","DarkWinter.TestingReplicationArchive.v1");
        manifest.addProperty("rootVersionId",root.id());manifest.addProperty("sourceKey",root.source());
        if(scope.bound()){manifest.addProperty("schema",2);scope.addFields(manifest);}
        JsonObject versions=new JsonObject(),lines=new JsonObject(),prefabs=new JsonObject(),objects=new JsonObject(),foreign=new JsonObject();
        for(var version:data.versions.values()) {
            String path=PREFIX+"versions/"+version.id()+".json";byte[] bytes=versionBytes(version);versions.add(version.id(),descriptor(path,bytes,descriptors,generated));
            for(var file:version.files()) {
                checkWorldPath(file);Path raw=TestingArchive.verifiedBlob(file);safe(raw);sourceByHash.putIfAbsent(file.sha256(),raw);
                if(!rootPaths.containsKey(file.sha256()))descriptors.putIfAbsent(PREFIX+"objects/"+file.sha256(),new Item(PREFIX+"objects/"+file.sha256(),file.size(),file.sha256()));
            }
        }
        for(var file:data.timelines.entrySet()){byte[] bytes=file.getValue();lines.add(file.getKey(),descriptor(PREFIX+"timelines/"+hash(file.getKey().getBytes(StandardCharsets.UTF_8))+".json",bytes,descriptors,generated));}
        for(var file:data.prefabs.entrySet())prefabs.add(file.getKey(),descriptor(PREFIX+"prefabs/"+file.getKey()+".json",file.getValue(),descriptors,generated));
        for(var file:data.objects.entrySet()){
            JsonObject value=new JsonObject();value.addProperty("path",rootPaths.getOrDefault(file.getKey(),PREFIX+"objects/"+file.getKey()));value.addProperty("bytes",file.getValue());objects.add(file.getKey(),value);
        }
        data.foreignParents.forEach(foreign::addProperty);manifest.add("versions",versions);manifest.add("timelines",lines);manifest.add("prefabs",prefabs);manifest.add("objects",objects);
        manifest.add("foreignParentLabels",foreign);manifest.addProperty("latestPrefab",data.latest);
        if(data.legacy==null)manifest.addProperty("legacyPrefab",false);else{manifest.addProperty("legacyPrefab",true);descriptor(PREFIX+"legacy-prefab.json",data.legacy,descriptors,generated);}
        byte[] manifestBytes=ProfileFiles.bytes(manifest);if(manifestBytes.length>4*MIB)throw new IOException("Companion manifest exceeds4MiB");
        descriptor(MANIFEST,manifestBytes,descriptors,generated);
        checkDescriptors(descriptors,limits);finalCheck.run();
        Path target=newTarget(destination),stage=createStage(target);
        boolean published=false;
        try {
            Map<String,Item> unique=new TreeMap<>();for(Item item:descriptors.values())unique.putIfAbsent(item.sha256(),item);
            for(Item item:unique.values()) {
                Path output=stage.resolve("objects").resolve(item.sha256());byte[] bytes=generated.get(item.sha256());
                if(bytes!=null)write(output,bytes);else copyVerified(sourceByHash.get(item.sha256()),output,item.bytes(),item.sha256());
            }
            finalCheck.run();write(stage.resolve("prepared.json"),manifestBytes);publish(stage,target);published=true;
            List<ReplicaPublisher.SourceBlob> sources=new ArrayList<>();
            for(Item item:descriptors.values())sources.add(new ReplicaPublisher.SourceBlob(item.entry(),target.resolve("objects").resolve(item.sha256())));
            // Preserve the public authority sidecar/derived root declarations for Publisher's existing managed exclusions.
            for(var file:root.files())if(ReplicaProtocol.authoritySidecar(file.path())||ReplicaProtocol.derivedTransient(file.path()))
                sources.add(new ReplicaPublisher.SourceBlob(new ReplicaProtocol.Entry(file.path(),file.size(),file.sha256()),target.resolve("objects").resolve(file.sha256())));
            return new Prepared(target,root.id(),root.source(),hash(manifestBytes),sources);
        }finally{if(!published)removeStage(stage,target.getParent());}
    }

    /**
     * Verify the entire typed companion and all native history payloads BEFORE creating a private
     * NEW archive staging directory. Never publishes into TestingArchive.root(), a save or a library.
     * The future world path is only a source-key binding label; it is not read, created or opened.
     */
    public static Restored restoreExternal(ReplicaProtocol.Index index,Reader reader,Path newPrivateDirectory,Path futureWorld,Limits limits)throws IOException {
        return restoreExternalInternal(index,reader,newPrivateDirectory,futureWorld,limits,null,null);
    }
    public static Restored restoreExternal(ReplicaProtocol.Index index,Reader reader,Path newPrivateDirectory,Path futureWorld,Limits limits,
            WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence)throws IOException{
        if(certificate==null||freshFence==null)throw new IOException("Missing verified authority context for the bound archive");
        freshFence.requireUsable();WorldAuthority.verify(certificate);
        if(!certificate.authorityId().equals(freshFence.authorityId()))throw new IOException("Archive restore fence belongs to another authority");
        return restoreExternalInternal(index,reader,newPrivateDirectory,futureWorld,limits,certificate,freshFence);
    }
    private static Restored restoreExternalInternal(ReplicaProtocol.Index index,Reader reader,Path newPrivateDirectory,Path futureWorld,Limits limits,
            WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence)throws IOException {
        Objects.requireNonNull(index);Objects.requireNonNull(reader);Path target=newTarget(newPrivateDirectory);
        Path future=futureWorld.toAbsolutePath().normalize();if(target.startsWith(future)||future.startsWith(target))throw new IOException("External archive must remain outside the future native world");
        byte[] manifestBytes=read(index,reader,MANIFEST,4*MIB);JsonObject manifest=parse(manifestBytes,4*MIB);
        long schema=number(manifest,"schema");
        if(schema==1)fields(manifest,"schema","kind","rootVersionId","sourceKey","versions","timelines","prefabs","objects","foreignParentLabels","latestPrefab","legacyPrefab");
        else if(schema==2){if(manifest.has("sourceAliases"))fields(manifest,"schema","kind","rootVersionId","sourceKey","versions","timelines","prefabs","objects","foreignParentLabels","latestPrefab","legacyPrefab","authorityId","islandId","ownerId","sourceBindings","sourceAliases");else fields(manifest,"schema","kind","rootVersionId","sourceKey","versions","timelines","prefabs","objects","foreignParentLabels","latestPrefab","legacyPrefab","authorityId","islandId","ownerId","sourceBindings");}
        else throw new IOException("Unknown archive companion format");
        if(!"DarkWinter.TestingReplicationArchive.v1".equals(string(manifest,"kind",96)))throw new IOException("Unknown archive companion kind");
        String rootId=string(manifest,"rootVersionId",36),oldSource=string(manifest,"sourceKey",24);uuid(rootId);source(oldSource);
        SourceScope scope=SourceScope.strict(oldSource);
        if(schema==2){
            if(certificate==null||freshFence==null)throw new IOException("Bound same-island archive requires an explicitly verified authority context");
            freshFence.requireUsable();var genesis=WorldAuthority.verify(certificate);
            if(!certificate.authorityId().equals(string(manifest,"authorityId",64))||!certificate.authorityId().equals(freshFence.authorityId())
                    ||!genesis.islandId().toString().equals(string(manifest,"islandId",36))||!genesis.ownerId().equals(string(manifest,"ownerId",64)))
                throw new IOException("Bound archive differs from the verified permanent authority");
            JsonObject encodedRoles=object(manifest,"sourceBindings");fields(encodedRoles,"author","current","target");Map<String,String> roles=new TreeMap<>();
            for(String role:List.of("author","current","target")){String key=string(encodedRoles,role,24);source(key);roles.put(role,key);}
            if(!oldSource.equals(roles.get("target")))throw new IOException("Bound snapshot source is not its declared stopped target");
            scope=new SourceScope(oldSource,roles.get("author"),Map.copyOf(roles),certificate.authorityId(),genesis.islandId().toString(),genesis.ownerId(),null,freshFence,certificate,manifest.has("sourceAliases")?decodeAliases(object(manifest,"sourceAliases"),roles):Map.of());
        }
        scope.current();
        Dataset data=new Dataset();Set<String> expected=new HashSet<>();expected.add(MANIFEST);
        long versionMetadata=0;JsonObject versionRows=object(manifest,"versions");if(versionRows.size()>limits.versions())throw new IOException("Companion version quota");
        for(var row:versionRows.entrySet()) {
            uuid(row.getKey());String path=PREFIX+"versions/"+row.getKey()+".json";
            byte[] bytes=readDescriptor(index,reader,row.getValue(),path,8*MIB);expected.add(path);
            if((versionMetadata+=bytes.length)>limits.metadataBytes())throw new IOException("Version metadata aggregate quota");
            TestingArchive.Version version=version(parse(bytes,8*MIB));if(!version.id().equals(row.getKey())||!scope.allows(version.source()))throw new IOException("Foreign or mismatched companion version");
            data.versions.put(row.getKey(),version);
        }
        TestingArchive.Version root=data.versions.get(rootId);if(root==null||!root.source().equals(oldSource))throw new IOException("Companion root version missing or has the wrong target source");
        Map<String,Item> objects=new TreeMap<>();JsonObject raw=object(manifest,"objects");if(raw.size()>limits.objects())throw new IOException("Companion object quota");
        for(var row:raw.entrySet()) {
            sha(row.getKey());JsonObject value=row.getValue().getAsJsonObject();fields(value,"path","bytes");
            String path=string(value,"path",512);long size=number(value,"bytes");
            if(!path.equals(PREFIX+"objects/"+row.getKey())&&!root.files().stream().anyMatch(e->e.path().equals(path)&&e.sha256().equals(row.getKey())&&e.size()==size))throw new IOException("Historical object must be raw CAS or exact current native file");
            Item item=new Item(path,size,row.getKey());requireEntry(index,item);objects.put(row.getKey(),item);
            if(path.startsWith(PREFIX))expected.add(path);
        }
        JsonObject lineRows=object(manifest,"timelines");if(lineRows.size()>128)throw new IOException("Timeline quota");
        for(var row:lineRows.entrySet()) {
            name(row.getKey());String path=PREFIX+"timelines/"+hash(row.getKey().getBytes(StandardCharsets.UTF_8))+".json";
            byte[] bytes=readDescriptor(index,reader,row.getValue(),path,MIB);validateTimeline(row.getKey(),bytes);data.timelines.put(row.getKey(),bytes);expected.add(path);
        }
        JsonObject prefabRows=object(manifest,"prefabs");if(prefabRows.size()>limits.prefabs())throw new IOException("Prefab quota");
        for(var row:prefabRows.entrySet()) {
            uuid(row.getKey());String path=PREFIX+"prefabs/"+row.getKey()+".json";byte[] bytes=readDescriptor(index,reader,row.getValue(),path,32*MIB);
            validatePrefab(row.getKey(),bytes);data.prefabs.put(row.getKey(),bytes);expected.add(path);
        }
        data.latest=string(manifest,"latestPrefab",36);if(!data.latest.isEmpty()&&!data.prefabs.containsKey(data.latest))throw new IOException("Unknown latest prefab");
        JsonElement legacy=manifest.get("legacyPrefab");if(!legacy.isJsonPrimitive()||!legacy.getAsJsonPrimitive().isBoolean())throw new IOException("Invalid legacy prefab flag");
        if(legacy.getAsBoolean()){data.legacy=read(index,reader,PREFIX+"legacy-prefab.json",MIB);validateLegacy(data.legacy);expected.add(PREFIX+"legacy-prefab.json");}
        JsonObject foreign=object(manifest,"foreignParentLabels");if(foreign.size()>limits.versions())throw new IOException("Foreign parent label quota");
        for(var row:foreign.entrySet()){uuid(row.getKey());String parent=row.getValue().getAsString();uuid(parent);var v=data.versions.get(row.getKey());if(v==null||!parent.equals(v.parent())||data.versions.containsKey(parent))throw new IOException("Invalid optional foreign parent label");data.foreignParents.put(row.getKey(),parent);}
        for(String path:index.files().keySet())if(path.startsWith(PREFIX)&&!expected.contains(path))throw new IOException("Undeclared/unreachable companion file");
        for(String path:expected)if(!index.files().containsKey(path))throw new IOException("Companion file missing");
        Dataset verified=new Dataset();verified.metadata=externalBytes(data);if(verified.metadata>limits.metadataBytes()-versionMetadata)throw new IOException("Companion metadata aggregate quota");verified.timelines.putAll(data.timelines);verified.prefabs.putAll(data.prefabs);verified.foreignParents.putAll(data.foreignParents);
        BlobReader metadata=entry->{
            Item item=objects.get(entry.sha256());if(item==null||item.bytes()!=entry.size())throw new IOException("Missing historical metadata object");
            return read(index,reader,item.path(),Math.min(limits.metadataBytes(),32*MIB));
        };
        VersionReader provider=id->{var v=data.versions.get(id);if(v==null)throw new IOException("Missing same-island ancestor/replay dependency");return v;};
        collect(root,scope,provider,metadata,verified,limits,0);
        for(byte[] bytes:data.timelines.values())for(String id:scenarioReferences(bytes))collect(provider.read(id),scope,provider,metadata,verified,limits,0);
        for(byte[] bytes:data.prefabs.values())for(String id:prefabReferences(bytes))collect(provider.read(id),scope,provider,metadata,verified,limits,0);
        if(!verified.versions.keySet().equals(data.versions.keySet()))throw new IOException("Unreachable/missing declared history");
        if(!verified.objects.equals(sizes(objects)))throw new IOException("Companion object catalog differs from exact historical closure");
        long total=0;for(Item item:objects.values()){if(item.bytes()>limits.fileBytes()||total>limits.bytes()-item.bytes())throw new IOException("Historical payload quota");total+=item.bytes();verify(index,reader,item);}
        String newSource=TestingArchive.worldKey(futureWorld);Map<String,TestingArchive.Version> rebound=new TreeMap<>();
        for(var v:data.versions.values())rebound.put(v.id(),new TestingArchive.Version(v.schema(),v.minecraft(),v.id(),v.name(),newSource,v.branch(),v.parent(),v.created(),v.protectedVersion(),v.bytes(),List.copyOf(v.files()),v.description(),v.category(),v.thumbnail()));
        scope.current();Path stage=createStage(target);boolean published=false;
        try {
            for(Item item:objects.values())copyReader(index,reader,item,stage.resolve("objects").resolve(item.sha256().substring(0,2)).resolve(item.sha256()));
            for(var v:rebound.values())write(stage.resolve("versions").resolve(v.id()+".json"),versionBytes(v));
            for(var line:data.timelines.entrySet())write(stage.resolve("scenario-timelines").resolve(newSource).resolve(line.getKey()+".json"),line.getValue());
            for(var prefab:data.prefabs.entrySet())write(stage.resolve("prefabs").resolve(prefab.getKey()+".json"),prefab.getValue());
            if(data.legacy!=null)write(stage.resolve("latest-blocks.json"),data.legacy);
            if(!data.latest.isEmpty())write(stage.resolve("prefabs/latest.txt"),data.latest.getBytes(StandardCharsets.UTF_8));
            JsonObject binding=new JsonObject();binding.addProperty("schema",1);binding.addProperty("rootVersionId",rootId);binding.addProperty("oldSourceKey",oldSource);binding.addProperty("newSourceKey",newSource);
            binding.addProperty("manifestSha256",hash(manifestBytes));binding.addProperty("requiresScopedArchiveContext",true);binding.addProperty("globalPublished",false);
            String activationAuthority=certificate==null?"":certificate.authorityId();binding.addProperty("activationAuthorityId",activationAuthority);binding.addProperty("contentRoot",index.contentRoot());
            Map<String,String> aliases=new TreeMap<>();for(String key:scope.keys())aliases.put(key,newSource);for(String key:scope.aliases().keySet())aliases.put(key,newSource);if(aliases.size()>64)throw new IOException("Too many inherited source aliases");
            if(scope.bound()){binding.addProperty("schema",2);scope.addFields(binding);binding.add("sourceAliases",GSON.toJsonTree(aliases));}
            byte[] bindingBytes=ProfileFiles.bytes(binding);scope.current();write(stage.resolve("binding.json"),bindingBytes);publish(stage,target);published=true;
            return new Restored(target,rootId,oldSource,newSource,rebound,aliases,activationAuthority,index.contentRoot(),hash(bindingBytes),restoredFiles(data,objects,rebound,bindingBytes));
        }finally{if(!published)removeStage(stage,target.getParent());}
    }

    private static boolean sameScopedSource(SourceScope scope,String left,String right){
        return scope.aliases().getOrDefault(left,left).equals(scope.aliases().getOrDefault(right,right));
    }
    private static Map<String,String> inheritedAliases(TestingArchiveSourceBinding.Proof proof)throws IOException{
        Map<String,String> aliases=TestingArchiveScope.sourceAliases();
        if(!aliases.isEmpty()&&!TestingArchiveScope.authorityId().orElse("").equals(proof.certificate.authorityId()))throw new IOException("Archive context belongs to another certified authority");
        return validatedAliases(aliases,proof.roles);
    }
    private static Map<String,String> decodeAliases(JsonObject json,Map<String,String> roles)throws IOException{
        Map<String,String> aliases=new TreeMap<>();for(var row:json.entrySet()){if(!row.getValue().isJsonPrimitive()||!row.getValue().getAsJsonPrimitive().isString())throw new IOException("Invalid typed source alias");aliases.put(row.getKey(),row.getValue().getAsString());}
        return validatedAliases(aliases,roles);
    }
    private static Map<String,String> validatedAliases(Map<String,String> aliases,Map<String,String> roles)throws IOException{
        if(aliases.size()>64)throw new IOException("Inherited source alias quota");String author=roles.get("author");Map<String,String> normalized=new TreeMap<>();
        for(var row:aliases.entrySet()){
            source(row.getKey());source(row.getValue());if(row.getKey().equals(author)&&!row.getValue().equals(author))throw new IOException("Canonical author source alias conflict");String next=row.getValue();Set<String> seen=new HashSet<>();seen.add(row.getKey());
            while(!next.equals(author)){if(!seen.add(next)||!aliases.containsKey(next))throw new IOException("Cyclic, dangling or foreign source alias");next=aliases.get(next);source(next);}
            normalized.put(row.getKey(),author);
        }
        Set<String> all=new HashSet<>(roles.values());all.addAll(normalized.keySet());if(all.size()>64)throw new IOException("Combined inherited source alias quota");
        return Map.copyOf(normalized);
    }

    private static Map<String,String> restoredFiles(Dataset data,Map<String,Item> objects,Map<String,TestingArchive.Version> versions,byte[] binding){
        Map<String,String> files=new TreeMap<>();for(Item item:objects.values())files.put("objects/"+item.sha256().substring(0,2)+"/"+item.sha256(),item.sha256());
        versions.forEach((id,version)->files.put("versions/"+id+".json",hash(versionBytes(version))));
        String owner=versions.values().iterator().next().source();
        data.timelines.forEach((name,bytes)->files.put("scenario-timelines/"+owner+"/"+name+".json",hash(bytes)));
        data.prefabs.forEach((id,bytes)->files.put("prefabs/"+id+".json",hash(bytes)));
        if(data.legacy!=null)files.put("latest-blocks.json",hash(data.legacy));
        if(!data.latest.isEmpty())files.put("prefabs/latest.txt",hash(data.latest.getBytes(StandardCharsets.UTF_8)));
        files.put("binding.json",hash(binding));files.put(".owned-stage",hash(new byte[]{1}));return Map.copyOf(files);
    }

    private static void collect(TestingArchive.Version value,SourceScope scope,VersionReader provider,BlobReader reader,Dataset data,Limits limits,int depth)throws IOException {
        canceled();TestingArchive.validate(value);source(value.source());uuid(value.id());if(!scope.allows(value.source()))throw new IOException("Replay dependency belongs to another island");
        Integer mark=data.marks.get(value.id());if(mark!=null){if(mark==1)throw new IOException("Cyclic ancestor/scenario archive graph");return;}
        if(depth>64||data.versions.size()>=limits.versions())throw new IOException("Archive companion ancestor/dependency depth or version quota");
        data.metadata+=versionBytes(value).length;if(data.metadata>limits.metadataBytes())throw new IOException("Version/scenario metadata aggregate quota");
        data.marks.put(value.id(),1);data.versions.put(value.id(),value);Map<String,byte[]> known=new LinkedHashMap<>();Set<String> refs=new TreeSet<>();
        for(var entry:value.files()) {
            checkWorldPath(entry);if(entry.size()>limits.fileBytes())throw new IOException("Historical file quota");
            Long before=data.objects.putIfAbsent(entry.sha256(),entry.size());if(before!=null&&before.longValue()!=entry.size())throw new IOException("One historical hash has contradictory size");
            if(data.objects.size()>limits.objects())throw new IOException("Historical object count quota");
            if(TestingScenarioHistoryIO.isMetadataPath(entry.path())) {
                if(entry.size()>limits.metadataBytes()||data.metadata>limits.metadataBytes()-entry.size())throw new IOException("Scenario metadata quota");
                byte[] bytes=reader.read(entry);if(bytes.length!=entry.size()||!hash(bytes).equals(entry.sha256()))throw new IOException("Historical metadata SHA mismatch");
                data.metadata+=bytes.length;known.put(entry.path(),bytes);refs.addAll(TestingScenarioHistoryIO.references(entry.path(),bytes));
            }
            if(scope.bound()&&(entry.path().equals("data/darkwinter-online/access.json")||entry.path().equals("data/darkwinter-online/authority.json"))){
                long cap=entry.path().endsWith("/access.json")?512*1024:65536;
                if(entry.size()>cap||data.metadata>limits.metadataBytes()-entry.size())throw new IOException("Historical public authority metadata quota");
                byte[] bytes=reader.read(entry);if(bytes.length!=entry.size()||!hash(bytes).equals(entry.sha256()))throw new IOException("Historical public authority SHA mismatch");
                data.metadata+=bytes.length;TestingArchiveSourceBinding.recorded(entry.path(),bytes,scope.certificate(),scope.fence());
            }
            if(entry.path().equals(DATA+"dependency-root.json")) {
                byte[] bytes=reader.read(entry);JsonObject marker=parse(bytes,16384);fields(marker,"schema","root","owner");
                String dependency=string(marker,"root",36);uuid(dependency);String dependencyOwner=string(marker,"owner",24);source(dependencyOwner);
                if(number(marker,"schema")!=1||!scope.allows(dependencyOwner)||!sameScopedSource(scope,provider.read(dependency).source(),dependencyOwner))throw new IOException("Foreign extraction dependency marker");
                refs.add(dependency);
            }
        }
        Map<String,String> identity=new HashMap<>();refs.forEach(id->identity.put(id,id));
        var unchanged=TestingScenarioHistoryIO.rewrite(known,identity);if(!unchanged.replacements().isEmpty()||!unchanged.removals().isEmpty())throw new IOException("Identity-preserving scenario validation unexpectedly rewrites history");
        String parent=value.parent();if(parent!=null&&!parent.isBlank()) {
            uuid(parent);
            if(!data.foreignParents.containsKey(value.id())) {
                TestingArchive.Version ancestor=provider.read(parent);
                if(scope.allows(ancestor.source()))refs.add(parent);else data.foreignParents.put(value.id(),parent);
            }
        }
        for(String id:refs){uuid(id);collect(provider.read(id),scope,provider,reader,data,limits,depth+1);}
        data.marks.put(value.id(),2);
    }
    private static long externalBytes(Dataset data){long total=data.legacy==null?0:data.legacy.length;for(byte[] b:data.prefabs.values())total+=b.length;for(byte[] b:data.timelines.values())total+=b.length;return total;}
    private static Map<String,Long> sizes(Map<String,Item> rows){Map<String,Long> result=new TreeMap<>();rows.forEach((sha,item)->result.put(sha,item.bytes()));return result;}
    private static Set<String> scenarioReferences(byte[] bytes)throws IOException {return TestingScenarioHistoryIO.references(DATA+"scenarios/timeline.json",bytes);}
    private static Set<String> prefabReferences(byte[] bytes)throws IOException {
        JsonObject payload=object(parse(bytes,32*MIB),"payload");return payload.has("scenarioDocuments")?
                TestingScenarioHistoryIO.references(DATA+"scenario-documents.json",GSON.toJson(payload.get("scenarioDocuments")).getBytes(StandardCharsets.UTF_8)):Set.of();
    }
    private static void validateTimeline(String expected,byte[] bytes)throws IOException {JsonObject value=parse(bytes,MIB);if(!expected.equals(string(value,"name",80)))throw new IOException("Timeline filename/name mismatch");scenarioReferences(bytes);}
    private static Map<String,byte[]> timelines(String owner)throws IOException {
        Map<String,byte[]> result=new TreeMap<>();for(var file:directory(TestingArchive.root().resolve("scenario-timelines").resolve(owner),128,MIB).entrySet()){
            if(!file.getKey().endsWith(".json"))throw new IOException("Unknown timeline file");String name=file.getKey().substring(0,file.getKey().length()-5);name(name);validateTimeline(name,file.getValue());result.put(name,file.getValue());}return result;
    }
    private static Map<String,byte[]> directory(Path path,int max,long perFile)throws IOException {
        safe(path);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Map.of();if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("External library is not a directory");
        List<Path> files;try(var list=Files.list(path)){files=list.limit(max+1L).sorted().toList();}if(files.size()>max)throw new IOException("External library count quota");
        Map<String,byte[]> result=new TreeMap<>();Set<String> folded=new HashSet<>();long total=0;
        for(Path file:files){String name=file.getFileName().toString();if(!folded.add(name.toUpperCase(Locale.ROOT)))throw new IOException("Windows library name collision");byte[] bytes=readFile(file,perFile);if((total+=bytes.length)>128*MIB)throw new IOException("External library metadata quota");result.put(name,bytes);}return result;
    }
    private static void validatePrefab(String id,byte[] bytes)throws IOException {
        JsonObject root=parse(bytes,32*MIB);long schema=number(root,"schema");if(schema<2||schema>6||!"26.3".equals(string(root,"minecraft",16))||!id.equals(string(root,"id",36)))throw new IOException("Unsupported/mismatched prefab");
        name(string(root,"name",96));JsonObject payload=object(root,"payload");if(!hash(payload.toString().getBytes(StandardCharsets.UTF_8)).equals(string(root,"sha256",64)))throw new IOException("Prefab body checksum mismatch");
        validateDimensions(payload);prefabReferences(bytes);
    }
    private static void validateLegacy(byte[] bytes)throws IOException {JsonObject root=parse(bytes,MIB);if(number(root,"schema")!=1)throw new IOException("Unsupported legacy prefab");validateDimensions(root);}
    private static void validateDimensions(JsonObject payload)throws IOException {
        for(String key:List.of("sizeX","sizeY","sizeZ")){long size=number(payload,key);if(size<1||size>64)throw new IOException("Prefab dimension bounds");}
        if(!payload.has("blocks")||!payload.get("blocks").isJsonArray()||payload.getAsJsonArray("blocks").size()>32768)throw new IOException("Prefab member quota");
    }
    private static void checkWorldPath(TestingArchive.Entry entry)throws IOException {
        if(entry.path().startsWith(PREFIX))throw new IOException("Recursive replica sidecar inside a native version");
        if(ReplicaProtocol.derivedTransient(entry.path())){if(entry.size()>16)throw new IOException("Oversized world lock");return;}
        if(ReplicaProtocol.authoritySidecar(entry.path())){if(entry.size()>65536)throw new IOException("Oversized public authority sidecar");return;}
        ReplicaProtocol.path(entry.path());
    }
    private static byte[] versionBytes(TestingArchive.Version version){return ProfileFiles.bytes(GSON.toJsonTree(version));}
    private static TestingArchive.Version version(JsonObject value)throws IOException {try{ProfileFiles.fields(value,Set.of("schema","minecraft","id","name","source","branch","created","protectedVersion","bytes","files"),Set.of("parent","description","category","thumbnail"));var v=GSON.fromJson(value,TestingArchive.Version.class);TestingArchive.validate(v);source(v.source());return v;}catch(RuntimeException bad){throw new IOException("Invalid immutable version metadata",bad);}}
    private static JsonObject descriptor(String path,byte[] bytes,Map<String,Item> rows,Map<String,byte[]> generated)throws IOException {
        String sha=hash(bytes);Item item=new Item(path,bytes.length,sha);if(rows.putIfAbsent(path,item)!=null)throw new IOException("Companion path collision");generated.putIfAbsent(sha,bytes);
        JsonObject row=new JsonObject();row.addProperty("path",path);row.addProperty("bytes",bytes.length);row.addProperty("sha256",sha);return row;
    }
    private static byte[] readDescriptor(ReplicaProtocol.Index index,Reader reader,JsonElement encoded,String path,long cap)throws IOException {
        if(encoded==null||!encoded.isJsonObject())throw new IOException("Missing sidecar descriptor");JsonObject row=encoded.getAsJsonObject();fields(row,"path","bytes","sha256");
        if(!path.equals(string(row,"path",512)))throw new IOException("Typed sidecar path mismatch");Item item=new Item(path,number(row,"bytes"),string(row,"sha256",64));requireEntry(index,item);return read(index,reader,path,cap);
    }
    private static void requireEntry(ReplicaProtocol.Index index,Item item)throws IOException {sha(item.sha256());var row=index.files().get(item.path());if(row==null||item.bytes()<0||row.bytes()!=item.bytes()||!row.sha256().equals(item.sha256()))throw new IOException("Historical object descriptor does not match signed replica index");}
    private static byte[] read(ReplicaProtocol.Index index,Reader reader,String path,long cap)throws IOException {
        var item=index.files().get(path);if(item==null||item.bytes()>cap)throw new IOException("Missing/oversized declared companion file");
        try(InputStream input=reader.open(path)){byte[] bytes=input.readNBytes((int)cap+1);if(bytes.length!=item.bytes()||!hash(bytes).equals(item.sha256()))throw new IOException("Companion full SHA mismatch");return bytes;}
    }
    private static void verify(ReplicaProtocol.Index index,Reader reader,Item item)throws IOException {requireEntry(index,item);try(InputStream input=reader.open(item.path())){stream(input,null,item.bytes(),item.sha256());}}
    private static void copyReader(ReplicaProtocol.Index index,Reader reader,Item item,Path output)throws IOException {
        requireEntry(index,item);Files.createDirectories(output.getParent());try(InputStream input=reader.open(item.path());OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){ProfileFiles.protect(output,false);stream(input,out,item.bytes(),item.sha256());}force(output);
    }
    private static void copyVerified(Path input,Path output,long size,String sha)throws IOException {
        if(input==null)throw new IOException("Missing immutable archive blob");safe(input);Files.createDirectories(output.getParent());
        try(InputStream in=Files.newInputStream(input,LinkOption.NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){ProfileFiles.protect(output,false);stream(in,out,size,sha);}force(output);
    }
    private static void stream(InputStream input,OutputStream output,long expected,String sha)throws IOException {
        MessageDigest digest=digest();byte[] buffer=new byte[65536];long total=0;for(int n;(n=input.read(buffer))!=-1;){canceled();if(total>expected-n)throw new IOException("Immutable payload grew");total+=n;digest.update(buffer,0,n);if(output!=null)output.write(buffer,0,n);}
        if(total!=expected||!HexFormat.of().formatHex(digest.digest()).equals(sha))throw new IOException("Immutable payload size/full SHA mismatch");
    }
    private static void checkDescriptors(Map<String,Item> rows,Limits limits)throws IOException {
        if(rows.size()>limits.objects()+limits.versions()+limits.prefabs()+130)throw new IOException("Companion transfer file quota");long total=0;
        Set<String> folded=new HashSet<>();for(Item item:rows.values()){ReplicaProtocol.path(item.path());if(!folded.add(item.path().toUpperCase(Locale.ROOT))||item.bytes()>limits.fileBytes()||total>limits.bytes()-item.bytes())throw new IOException("Companion path/total/file quota");total+=item.bytes();}
    }
    private static byte[] readFile(Path file,long cap)throws IOException {safe(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>cap)throw new IOException("Missing/linked/oversized external file");try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes((int)cap+1);if(bytes.length>cap)throw new IOException("External file grew");return bytes;}}
    private static Path newTarget(Path path)throws IOException {Path target=path.toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(target);safe(target);if(target.startsWith(TestingArchive.root().getParent()))throw new IOException("Cannot publish into the global archive/library");if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Private replica archive staging already exists");return target;}
    private static Path createStage(Path target)throws IOException {Files.createDirectories(target.getParent());Path stage=Files.createTempDirectory(target.getParent(),".dw-replication-");ProfileFiles.protect(stage,true);write(stage.resolve(".owned-stage"),new byte[]{1});return stage;}
    private static void publish(Path stage,Path target)throws IOException {canceled();if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Archive companion destination collision");try{Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){throw new IOException("Archive companion requires atomic directory publication",unsupported);}}
    private static void removeStage(Path stage,Path parent)throws IOException {
        safe(stage);if(!stage.getParent().equals(parent)||!stage.getFileName().toString().startsWith(".dw-replication-")||!Files.isRegularFile(stage.resolve(".owned-stage"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Refused foreign staging cleanup");
        List<Path> paths;try(var walk=Files.walk(stage)){paths=walk.limit(20000).sorted(Comparator.reverseOrder()).toList();}if(paths.size()>=20000)throw new IOException("Staging cleanup file quota");
        for(Path path:paths){safe(path);Files.delete(path);}
    }
    private static void safe(Path path)throws IOException {for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&!p.toRealPath().equals(p))throw new IOException("Linked/redirected companion path");}
    private static void write(Path path,byte[] bytes)throws IOException {Files.createDirectories(path.getParent());try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ProfileFiles.protect(path,false);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
    private static void force(Path path)throws IOException {try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
    private static JsonObject parse(byte[] bytes,long cap)throws IOException {
        if(bytes.length>cap)throw new IOException("Companion metadata byte quota");
        try(var reader=new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()))) {
            reader.setStrictness(Strictness.STRICT);JsonElement value=json(reader,0,new int[]{0});if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw new IOException("Expected one typed JSON object");return value.getAsJsonObject();
        }catch(RuntimeException bad){throw new IOException("Invalid typed archive companion JSON",bad);}
    }
    private static JsonElement json(JsonReader reader,int depth,int[] nodes)throws IOException {
        if(depth>64||++nodes[0]>1000000)throw new IOException("Archive JSON complexity quota");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{reader.beginObject();JsonObject value=new JsonObject();while(reader.hasNext()){String key=reader.nextName();if(value.has(key))throw new IOException("Duplicate archive JSON field");value.add(key,json(reader,depth+1,nodes));}reader.endObject();yield value;}
            case BEGIN_ARRAY->{reader.beginArray();JsonArray value=new JsonArray();while(reader.hasNext())value.add(json(reader,depth+1,nodes));reader.endArray();yield value;}
            case STRING->new JsonPrimitive(reader.nextString());case NUMBER->new JsonPrimitive(new BigDecimal(reader.nextString()));case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}default->throw new IOException("Invalid archive JSON token");
        };
    }
    private static boolean same(Map<String,byte[]> a,Map<String,byte[]> b){if(!a.keySet().equals(b.keySet()))return false;for(String key:a.keySet())if(!Arrays.equals(a.get(key),b.get(key)))return false;return true;}
    private static JsonObject object(JsonObject value,String key)throws IOException {if(!value.has(key)||!value.get(key).isJsonObject())throw new IOException("Missing typed object");return value.getAsJsonObject(key);}
    private static String string(JsonObject value,String key,int cap)throws IOException{return ProfileFiles.string(value,key,cap);}
    private static long number(JsonObject value,String key)throws IOException{return ProfileFiles.number(value,key);}
    private static void fields(JsonObject value,String...keys)throws IOException {ProfileFiles.fields(value,Set.of(keys),Set.of());}
    private static void name(String name)throws IOException {if(name.isBlank()||name.length()>96||!java.text.Normalizer.isNormalized(name,java.text.Normalizer.Form.NFC)||name.contains("..")||name.endsWith(".")||name.endsWith(" ")||name.codePoints().anyMatch(c->c<32||c==127||"\\/:*?<>|\"".indexOf(c)>=0))throw new IOException("Unsafe scenario/prefab name");}
    private static void uuid(String value)throws IOException {try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Noncanonical history ID");}catch(RuntimeException bad){throw new IOException("Invalid history ID",bad);}}
    private static void source(String value)throws IOException {if(value==null||!value.matches("[0-9a-f]{24}"))throw new IOException("Invalid source world key");}
    private static void sha(String value)throws IOException {if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("Invalid archive content SHA");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}
    private static void canceled()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Archive companion canceled");}
}
