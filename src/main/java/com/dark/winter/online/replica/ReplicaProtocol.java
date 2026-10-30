// Defines bounded metadata for verified checkpoint replica transfers.
package com.dark.winter.online.replica;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Pure bounded checkpoint metadata. READY means complete package integrity, never native world/admission acceptance. */
public final class ReplicaProtocol {
    public static final int CHUNK_BYTES=65536,MAX_INFLIGHT=4,MAX_RECEIVERS=2,MAX_PLAYERS=16;
    /** Durable identity history; independent of the simultaneous native player limit. */
    public static final int MAX_ACTOR_ALIASES=1024;
    private static final String HEADER="DarkWinter.Replica.offer.v1",ACK="DarkWinter.Replica.ready.v1";
    private static final Gson JSON=new Gson();
    public record Limits(long snapshotBytes,long fileBytes,int files,int indexBytes,long cacheBytes,long freeReserveBytes,int retainedReady){
        public Limits{
            if(snapshotBytes<1024||snapshotBytes>2L*1024*1024*1024||fileBytes<1||fileBytes>512L*1024*1024||fileBytes>snapshotBytes||
                files<3||files>8192||indexBytes<1024||indexBytes>4*1024*1024||cacheBytes<snapshotBytes||cacheBytes>4L*1024*1024*1024||
                freeReserveBytes<0||freeReserveBytes>1024L*1024*1024||retainedReady<1||retainedReady>8)throw new IllegalArgumentException("Replica quota bounds");
        }
        public static Limits defaults(){return new Limits(512L*1024*1024,128L*1024*1024,4096,4*1024*1024,1024L*1024*1024,64L*1024*1024,4);}
    }
    public record Compatibility(int protocol,int dataContract,String minecraft,String definitionsSha256){
        public Compatibility{
            if(protocol<1||protocol>65535||dataContract<1||dataContract>65535||minecraft==null||minecraft.isBlank()||minecraft.length()>64||
                minecraft.chars().anyMatch(Character::isISOControl)||definitionsSha256==null||!definitionsSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid replica compatibility tuple");
        }
        public static Compatibility fromManifest(JsonObject manifest)throws IOException{
            long protocol=integer(manifest,"protocol"),data=integer(manifest,"dataContract");
            if(protocol<1||protocol>65535||data<1||data>65535)throw new IOException("Replica compatibility integer bounds");
            try{return new Compatibility((int)protocol,(int)data,string(manifest,"minecraft",64),digest(string(manifest,"definitionsSha256",64)));}
            catch(IllegalArgumentException invalid){throw new IOException("Invalid replica compatibility",invalid);}
        }
    }
    public record Entry(String path,long bytes,String sha256){}
    public static final class Index{
        private final SortedMap<String,Entry> files;private final Map<String,UUID> aliases;private final byte[] bytes;private final String root;private final long size;
        private Index(SortedMap<String,Entry> files,Map<String,UUID> aliases,byte[] bytes,long size){this.files=Collections.unmodifiableSortedMap(new TreeMap<>(files));this.aliases=Map.copyOf(aliases);this.bytes=bytes;root=hash(bytes);this.size=size;}
        public SortedMap<String,Entry> files(){return files;}public Map<String,UUID> actorAliases(){return aliases;}public String contentRoot(){return root;}public long totalBytes(){return size;}
        public byte[] encoded(){return bytes.clone();}
    }
    public record Offer(SignedPayload header,byte[] index){
        public Offer{Objects.requireNonNull(index);if(index.length>4*1024*1024)throw new IllegalArgumentException("Replica offer index quota");index=index.clone();}
        @Override public byte[] index(){return index.clone();}
        @Override public String toString(){return "ReplicaOffer[indexBytes="+index.length+"]";}
    }
    public record Header(UUID snapshotId,String authorityId,UUID islandId,long epoch,UUID leaseId,UUID sessionId,
                         long sequence,long baseSequence,String baseRoot,String contentRoot,String indexSha,int fileCount,long bytes,long createdAt,String type,Compatibility compatibility){}
    public static final class VerifiedOffer{
        private final Offer offer;private final Header header;private final Index index;
        private VerifiedOffer(Offer offer,Header header,Index index){this.offer=offer;this.header=header;this.index=index;}
        public Offer offer(){return offer;}public Header header(){return header;}public Index index(){return index;}
    }
    public record Request(UUID snapshotId,UUID requestId,String fileSha256,long offset,int bytes){}
    public record Chunk(UUID snapshotId,UUID requestId,String fileSha256,long offset,byte[] data){
        public Chunk{Objects.requireNonNull(data);if(data.length<1||data.length>CHUNK_BYTES)throw new IllegalArgumentException("Replica chunk quota");data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        @Override public String toString(){return "ReplicaChunk[bytes="+data.length+"]";}
    }
    public static Index index(Collection<Entry> entries,Limits limits)throws IOException{return index(entries,Map.of(),limits);}
    public static Index index(Collection<Entry> entries,Map<String,UUID> aliases,Limits limits)throws IOException{
        Objects.requireNonNull(entries);Objects.requireNonNull(limits);validateAliases(aliases);
        if(entries.size()>limits.files())throw new IOException("Replica file count quota");
        SortedMap<String,Entry> files=new TreeMap<>();Set<String> folded=new HashSet<>();Map<String,Long> sizes=new HashMap<>();long total=0;
        for(Entry entry:entries){
            if(entry==null)throw new IOException("Missing replica entry");String path=path(entry.path()),sha=digest(entry.sha256());
            if(entry.bytes()<0||entry.bytes()>limits.fileBytes()||total>limits.snapshotBytes()-entry.bytes())throw new IOException("Replica file/total byte quota");
            total+=entry.bytes();if(files.putIfAbsent(path,entry)!=null||!folded.add(path.toUpperCase(Locale.ROOT)))throw new IOException("Duplicate or Windows-aliased replica path");
            Long size=sizes.putIfAbsent(sha,entry.bytes());if(size!=null&&size!=entry.bytes())throw new IOException("One content hash has contradictory sizes");
        }
        for(String path:files.keySet())for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))
            if(files.containsKey(path.substring(0,slash)))throw new IOException("Replica file/directory collision");
        if(!files.containsKey("level.dat")||!files.containsKey("data/darkwinter-testing.properties")||!files.containsKey("data/darkwinter-online/access.json"))
            throw new IOException("Replica requires native level, Testing marker and public island access");
        JsonObject document=fullJson(files,aliases);byte[] encoded=ProfileFiles.bytes(document);
        if(encoded.length>limits.indexBytes())throw new IOException("Replica index byte quota");return new Index(files,aliases,encoded,total);
    }
    public static Index decodeIndex(byte[] bytes,Limits limits)throws IOException{
        JsonObject value=canonical(bytes,limits.indexBytes());fields(value,"schema","type","files","actorAliases");
        if(integer(value,"schema")!=1||!string(value,"type",8).equals("FULL"))throw new IOException("Unsupported full replica index");
        return index(readEntries(object(value,"files"),limits),aliases(object(value,"actorAliases")),limits);
    }
    public static Offer offer(IdentityStore actualHost,WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence,
                              Index result,long sequence,Index base,long baseSequence,Instant created,Compatibility compatibility)throws IOException{
        fresh(certificate,freshFence);if(!actualHost.profile().id().equals(freshFence.holderId()))throw new IOException("Replica producer is not the actual lease holder");
        if(!result.actorAliases().containsKey(freshFence.holderId()))throw new IOException("Replica alias table omits actual producing host");
        if(sequence<0||sequence<freshFence.checkpointSequence())throw new IOException("Replica checkpoint sequence predates confirmed fence");
        if(sequence==freshFence.checkpointSequence()&&!result.contentRoot().equals(freshFence.checkpointSha256()))throw new IOException("Replica disagrees with confirmed checkpoint");
        byte[] index;String type,baseRoot;long parent;
        if(base==null){index=result.encoded();type="FULL";baseRoot="";parent=-1;}
        else{
            if(baseSequence<0||sequence<=baseSequence)throw new IOException("Replica delta sequence must advance");
            JsonObject delta=new JsonObject();delta.addProperty("schema",1);delta.addProperty("type","DELTA");
            delta.addProperty("baseSequence",baseSequence);delta.addProperty("baseRoot",base.contentRoot());delta.addProperty("contentRoot",result.contentRoot());
            SortedMap<String,Entry> changed=new TreeMap<>();JsonArray removed=new JsonArray();
            for(Entry entry:result.files().values())if(!entry.equals(base.files().get(entry.path())))changed.put(entry.path(),entry);
            for(String path:base.files().keySet())if(!result.files().containsKey(path))removed.add(path);
            delta.add("changes",entriesJson(changed));delta.add("removed",removed);delta.add("actorAliases",aliasesJson(result.actorAliases()));index=ProfileFiles.bytes(delta);type="DELTA";baseRoot=base.contentRoot();parent=baseSequence;
        }
        if(index.length>Limits.defaults().indexBytes())throw new IOException("Replica transfer index byte quota");
        Header header=new Header(UUID.randomUUID(),certificate.authorityId(),WorldAuthority.verify(certificate).islandId(),freshFence.epoch(),freshFence.leaseId(),
            freshFence.sessionId(),sequence,parent,baseRoot,result.contentRoot(),hash(index),result.files().size(),result.totalBytes(),created.toEpochMilli(),type,Objects.requireNonNull(compatibility));
        SignedPayload signed=actualHost.sign(Purpose.SIGNAL,ProfileFiles.bytes(headerJson(header)));
        return new Offer(signed,index);
    }
    public static VerifiedOffer verify(Offer offer,WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence,Index base,long baseSequence,Limits limits,Compatibility expectedCompatibility)throws IOException{
        fresh(certificate,freshFence);Header header=header(offer.header());
        if(!header.compatibility().equals(expectedCompatibility))throw new IOException("Replica protocol/Minecraft/definitions are incompatible");
        if(!IdentityStore.verify(offer.header(),Purpose.SIGNAL)||!offer.header().signer().id().equals(freshFence.holderId())||
            !header.authorityId().equals(certificate.authorityId())||!header.islandId().equals(WorldAuthority.verify(certificate).islandId())||
            header.epoch()!=freshFence.epoch()||!header.leaseId().equals(freshFence.leaseId())||!header.sessionId().equals(freshFence.sessionId()))
            throw new IOException("Replica offer does not belong to current certified host/epoch/session");
        byte[] payload=offer.index();if(payload.length>limits.indexBytes()||!hash(payload).equals(header.indexSha()))throw new IOException("Replica index hash/size mismatch");
        Index full;
        if(header.type().equals("FULL")){if(header.baseSequence()!=-1||!header.baseRoot().isEmpty())throw new IOException("Full replica has a false delta base");full=decodeIndex(payload,limits);}
        else{
            if(base==null||baseSequence!=header.baseSequence()||!base.contentRoot().equals(header.baseRoot())||header.sequence()<=baseSequence)throw new IOException("Replica delta base is missing or stale");
            JsonObject delta=canonical(payload,limits.indexBytes());fields(delta,"schema","type","baseSequence","baseRoot","contentRoot","changes","removed","actorAliases");
            if(integer(delta,"schema")!=1||!string(delta,"type",8).equals("DELTA")||integer(delta,"baseSequence")!=baseSequence||
                !string(delta,"baseRoot",64).equals(base.contentRoot())||!string(delta,"contentRoot",64).equals(header.contentRoot()))throw new IOException("Replica delta base/result mismatch");
            SortedMap<String,Entry> merged=new TreeMap<>(base.files());Set<String> removed=new HashSet<>();
            JsonElement tombstones=delta.get("removed");if(!tombstones.isJsonArray()||tombstones.getAsJsonArray().size()>limits.files())throw new IOException("Replica tombstone quota");
            for(JsonElement row:tombstones.getAsJsonArray()){
                if(!row.isJsonPrimitive()||!row.getAsJsonPrimitive().isString())throw new IOException("Invalid replica tombstone");
                String path=path(row.getAsString());if(!removed.add(path)||merged.remove(path)==null)throw new IOException("Duplicate or unknown replica tombstone");
            }
            for(Entry change:readEntries(object(delta,"changes"),limits)){if(removed.contains(change.path()))throw new IOException("Delta both removes and changes one path");merged.put(change.path(),change);}
            full=index(merged.values(),aliases(object(delta,"actorAliases")),limits);
        }
        if(!full.actorAliases().containsKey(freshFence.holderId())||!full.contentRoot().equals(header.contentRoot())||full.files().size()!=header.fileCount()||full.totalBytes()!=header.bytes()||
            header.sequence()<freshFence.checkpointSequence()||header.sequence()==freshFence.checkpointSequence()&&!full.contentRoot().equals(freshFence.checkpointSha256()))
            throw new IOException("Replica full result or confirmed checkpoint mismatch");
        return new VerifiedOffer(offer,header,full);
    }
    /** Integrity-only load of a previously completed package. It never authorizes promotion or simulation. */
    static VerifiedOffer stored(Offer offer,Index full,WorldAuthority.Certificate certificate,Limits limits,Compatibility expectedCompatibility)throws IOException{
        WorldAuthority.Genesis root=WorldAuthority.verify(certificate);Header h=header(offer.header());
        if(!h.compatibility().equals(expectedCompatibility))throw new IOException("Stored replica compatibility differs");
        if(!h.authorityId().equals(certificate.authorityId())||!h.islandId().equals(root.islandId())||
            offer.index().length>limits.indexBytes()||!hash(offer.index()).equals(h.indexSha())||
            !full.contentRoot().equals(h.contentRoot())||full.totalBytes()!=h.bytes()||full.files().size()!=h.fileCount()||
            !full.actorAliases().containsKey(offer.header().signer().id()))throw new IOException("Stored replica signature/index/result mismatch");
        JsonObject original=canonical(offer.index(),limits.indexBytes());
        if(h.type().equals("FULL")){
            if(h.baseSequence()!=-1||!h.baseRoot().isEmpty()||!decodeIndex(offer.index(),limits).contentRoot().equals(full.contentRoot()))
                throw new IOException("Invalid stored full replica index");
        }else{
            fields(original,"schema","type","baseSequence","baseRoot","contentRoot","changes","removed","actorAliases");
            if(integer(original,"schema")!=1||!string(original,"type",8).equals("DELTA")||integer(original,"baseSequence")!=h.baseSequence()||
                !string(original,"baseRoot",64).equals(h.baseRoot())||!string(original,"contentRoot",64).equals(h.contentRoot())||h.sequence()<=h.baseSequence())
                throw new IOException("Invalid stored replica delta binding");
            readEntries(object(original,"changes"),limits);aliases(object(original,"actorAliases"));
            JsonElement removed=original.get("removed");if(!removed.isJsonArray()||removed.getAsJsonArray().size()>limits.files())throw new IOException("Stored delta tombstone quota");
            Set<String> names=new HashSet<>();for(JsonElement entry:removed.getAsJsonArray()){
                if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString()||!names.add(path(entry.getAsString())))throw new IOException("Invalid stored delta tombstone");}
        }
        return new VerifiedOffer(offer,h,full);
    }
    public static void fresh(WorldAuthority.Certificate certificate,LeaseFence.Fence fence)throws IOException{
        WorldAuthority.verify(certificate);if(fence==null||!certificate.authorityId().equals(fence.authorityId()))throw new IOException("Replica lacks certified host fence");fence.requireUsable();
    }
    public static void sameFence(Header header,LeaseFence.Fence fence)throws IOException{
        if(fence==null||!header.authorityId().equals(fence.authorityId())||header.epoch()!=fence.epoch()||
            !header.leaseId().equals(fence.leaseId())||!header.sessionId().equals(fence.sessionId()))throw new IOException("Replica host context changed");
        fence.requireUsable();
    }
    static SignedPayload readyAck(IdentityStore actualReceiver,VerifiedOffer offer,Instant completed)throws IOException{
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("domain",ACK);body.addProperty("snapshotId",offer.header().snapshotId().toString());
        body.addProperty("authorityId",offer.header().authorityId());body.addProperty("epoch",offer.header().epoch());
        body.addProperty("leaseId",offer.header().leaseId().toString());body.addProperty("sessionId",offer.header().sessionId().toString());
        body.addProperty("sequence",offer.header().sequence());body.addProperty("contentRoot",offer.index().contentRoot());
        body.addProperty("fileCount",offer.index().files().size());body.addProperty("bytes",offer.index().totalBytes());body.addProperty("completedAt",completed.toEpochMilli());
        return actualReceiver.sign(Purpose.SIGNAL,ProfileFiles.bytes(body));
    }
    /** Caller must additionally require this receiver to be an actually admitted, permitted standby. */
    public static void verifyAck(VerifiedOffer offer,SignedPayload ack,String expectedReceiver)throws IOException{
        if(!IdentityStore.verify(ack,Purpose.SIGNAL)||!ack.signer().id().equals(expectedReceiver))throw new IOException("Replica READY was not signed by expected standby");
        JsonObject body=canonical(IdentityStore.decodeData(ack),16384);
        fields(body,"schema","domain","snapshotId","authorityId","epoch","leaseId","sessionId","sequence","contentRoot","fileCount","bytes","completedAt");
        Header h=offer.header();
        if(integer(body,"schema")!=1||!string(body,"domain",80).equals(ACK)||!uuid(string(body,"snapshotId",36)).equals(h.snapshotId())||
            !string(body,"authorityId",64).equals(h.authorityId())||integer(body,"epoch")!=h.epoch()||
            !uuid(string(body,"leaseId",36)).equals(h.leaseId())||!uuid(string(body,"sessionId",36)).equals(h.sessionId())||
            integer(body,"sequence")!=h.sequence()||!string(body,"contentRoot",64).equals(h.contentRoot())||
            integer(body,"fileCount")!=h.fileCount()||integer(body,"bytes")!=h.bytes()||integer(body,"completedAt")<0)
            throw new IOException("Replica READY belongs to another package");
    }
    public static byte[] encodeChunk(Chunk chunk)throws IOException{
        byte[] data=chunk.data();validateChunk(chunk,data);ByteBuffer output=ByteBuffer.allocate(114+data.length).order(ByteOrder.BIG_ENDIAN);
        output.putInt(0x44575231).putShort((short)1);putUuid(output,chunk.snapshotId());putUuid(output,chunk.requestId());
        output.put(HexFormat.of().parseHex(chunk.fileSha256())).putLong(chunk.offset()).putInt(data.length).put(sha(data)).put(data);return output.array();
    }
    public static Chunk decodeChunk(byte[] wire)throws IOException{
        if(wire==null||wire.length<115||wire.length>CHUNK_BYTES+114)throw new IOException("Replica chunk wire size");
        ByteBuffer input=ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if(input.getInt()!=0x44575231||input.getShort()!=1)throw new IOException("Unsupported replica chunk protocol");
        UUID snapshot=getUuid(input),request=getUuid(input);byte[] hash=new byte[32];input.get(hash);long offset=input.getLong();int count=input.getInt();byte[] expected=new byte[32];input.get(expected);
        if(count<1||count>CHUNK_BYTES||input.remaining()!=count)throw new IOException("Replica chunk length mismatch");byte[] data=new byte[count];input.get(data);
        if(!MessageDigest.isEqual(expected,sha(data)))throw new IOException("Replica chunk hash mismatch");
        Chunk chunk=new Chunk(snapshot,request,HexFormat.of().formatHex(hash),offset,data);validateChunk(chunk,data);return chunk;
    }
    static void validateChunk(Chunk chunk,byte[] data)throws IOException{
        if(chunk.snapshotId()==null||chunk.requestId()==null||chunk.offset()<0||data.length<1||data.length>CHUNK_BYTES)throw new IOException("Replica chunk bounds");digest(chunk.fileSha256());
    }
    static Header header(SignedPayload signed)throws IOException{
        if(!IdentityStore.verify(signed,Purpose.SIGNAL))throw new IOException("Invalid signed replica header");
        JsonObject value=canonical(IdentityStore.decodeData(signed),16384);
        fields(value,"schema","domain","snapshotId","authorityId","islandId","epoch","leaseId","sessionId","sequence","baseSequence","baseRoot","contentRoot","indexSha256","fileCount","bytes","createdAt","type","compatibility");
        long sequence=integer(value,"sequence"),epoch=integer(value,"epoch"),size=integer(value,"bytes"),created=integer(value,"createdAt"),count=integer(value,"fileCount");
        String type=string(value,"type",8),authority=string(value,"authorityId",64),base=string(value,"baseRoot",64);
        if(integer(value,"schema")!=1||!string(value,"domain",80).equals(HEADER)||!Set.of("FULL","DELTA").contains(type)||!authority.matches("dwa1_[A-Za-z0-9_-]{43}")||
            sequence<0||epoch<1||size<0||created<0||count<3||count>8192||(!base.isEmpty()&&!base.matches("[0-9a-f]{64}")))throw new IOException("Invalid replica header fields");
        return new Header(uuid(string(value,"snapshotId",36)),authority,uuid(string(value,"islandId",36)),epoch,uuid(string(value,"leaseId",36)),
            uuid(string(value,"sessionId",36)),sequence,integer(value,"baseSequence"),base,digest(string(value,"contentRoot",64)),
            digest(string(value,"indexSha256",64)),(int)count,size,created,type,readCompatibility(object(value,"compatibility")));
    }
    private static Compatibility readCompatibility(JsonObject value)throws IOException{fields(value,"protocol","dataContract","minecraft","definitionsSha256");return Compatibility.fromManifest(value);}
    static JsonObject headerJson(Header h){
        JsonObject value=JSON.toJsonTree(h).getAsJsonObject();value.remove("indexSha");value.addProperty("indexSha256",h.indexSha());value.addProperty("schema",1);value.addProperty("domain",HEADER);return value;
    }
    static JsonObject fullJson(Map<String,Entry> entries,Map<String,UUID> aliases){JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("type","FULL");value.add("files",entriesJson(entries));value.add("actorAliases",aliasesJson(aliases));return value;}
    private static void validateAliases(Map<String,UUID> aliases)throws IOException{
        if(aliases==null||aliases.size()>MAX_ACTOR_ALIASES)throw new IOException("Replica historical native alias table exceeds1024 identities");
        Set<UUID> ids=new HashSet<>();
        for(var entry:aliases.entrySet())if(entry.getKey()==null||!entry.getKey().matches("dw1_[A-Za-z0-9_-]{43}")||
            entry.getValue()==null||!ids.add(entry.getValue()))throw new IOException("Replica actor/native UUID aliases must be a bijection");
    }
    private static JsonObject aliasesJson(Map<String,UUID> aliases){JsonObject value=new JsonObject();for(String id:new TreeSet<>(aliases.keySet()))value.addProperty(id,aliases.get(id).toString());return value;}
    private static Map<String,UUID> aliases(JsonObject value)throws IOException{
        Map<String,UUID> aliases=new TreeMap<>();if(value.size()>MAX_ACTOR_ALIASES)throw new IOException("Replica historical native alias table exceeds1024 identities");
        for(var row:value.entrySet()){if(!row.getValue().isJsonPrimitive()||!row.getValue().getAsJsonPrimitive().isString())throw new IOException("Invalid replica actor alias");aliases.put(row.getKey(),uuid(row.getValue().getAsString()));}
        validateAliases(aliases);return aliases;
    }
    private static JsonObject entriesJson(Map<String,Entry> entries){
        JsonObject files=new JsonObject();for(String path:new TreeSet<>(entries.keySet())){Entry entry=entries.get(path);JsonObject row=new JsonObject();row.addProperty("bytes",entry.bytes());row.addProperty("sha256",entry.sha256());files.add(path,row);}return files;
    }
    private static List<Entry> readEntries(JsonObject object,Limits limits)throws IOException{
        if(object.size()>limits.files())throw new IOException("Replica file count quota");List<Entry> entries=new ArrayList<>();
        for(var row:object.entrySet()){if(!row.getValue().isJsonObject())throw new IOException("Invalid replica file descriptor");JsonObject value=row.getValue().getAsJsonObject();fields(value,"bytes","sha256");
            entries.add(new Entry(path(row.getKey()),integer(value,"bytes"),digest(string(value,"sha256",64))));}return entries;
    }
    public static boolean authoritySidecar(String path){return "data/darkwinter-online/authority.json".equals(path);}
    public static boolean derivedTransient(String path){return Set.of("session.lock","data/darkwinter-online/.access.lock","data/darkwinter-online/.authority.lock").contains(path);}
    /** Unsupported source paths reject the whole package; nothing resembling profiles/mods is silently skipped. */
    public static String path(String path)throws IOException{
        if(path==null||path.isEmpty()||path.length()>512||path.getBytes(StandardCharsets.UTF_8).length>1024||!Normalizer.isNormalized(path,Normalizer.Form.NFC)||
            path.startsWith("/")||path.endsWith("/")||path.contains("\\")||path.contains(":"))throw new IOException("Unsafe replica path");
        String[] parts=path.split("/",-1);if(parts.length>16)throw new IOException("Replica path depth");
        // Real TestingNativeJournal commit markers are136characters; all other components retain128.
        int componentLimit=path.matches("data/darkwinter-testing/native-tail/commit-[0-9]{20}-[0-9a-f-]{36}-[0-9a-f]{64}\\.commit")?136:128;
        for(String part:parts){
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.length()>componentLimit||part.endsWith(".")||part.endsWith(" ")||
                part.codePoints().anyMatch(c->c<32||c==127||"*?<>|\"".indexOf(c)>=0)||
                part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")||
                Set.of("profiles","node-identity","identity").contains(part.toLowerCase(Locale.ROOT))||
                Set.of("profile.json","directory-state.json","profile-backup.json","credentials.json","friends.json","invitations.json").contains(part.toLowerCase(Locale.ROOT)))
                throw new IOException("Unsafe, private or reserved replica path");
        }
        boolean allowed=replicationArchivePath(path)||Set.of("level.dat","level.dat_old","icon.png").contains(path)||
            path.matches("players/(data/[0-9a-f-]{36}\\.dat(?:_old)?|(?:stats|advancements)/[0-9a-f-]{36}\\.json)")||
            path.matches("data/(?:minecraft/[a-z0-9_./-]+\\.dat|fabric/registry_entries\\.dat)")||
            Set.of("data/darkwinter-testing.properties","data/darkwinter-calendar.properties","data/darkwinter-weather.properties","data/darkwinter-climate.properties").contains(path)||
            path.matches("dimensions/(?:minecraft|darkwinter)/[a-z0-9_./-]+/(?:(?:region|entities|poi)/(?:r\\.-?[0-9]+\\.-?[0-9]+\\.mca|c\\.-?[0-9]+\\.-?[0-9]+\\.mcc)|data/minecraft/[a-z0-9_./-]+\\.dat)")||
            path.matches("data/darkwinter-testing/[\\p{L}\\p{N} _./-]+\\.(?:json|jsonl|properties|json\\.gz)")||
            path.matches("data/darkwinter-testing/native-tail/commit-[0-9]{20}-[0-9a-f-]{36}-[0-9a-f]{64}\\.commit")||
            Set.of("data/darkwinter-online/access.json","data/darkwinter-online/policy.json","data/darkwinter-online/voice.json",
                "data/darkwinter-online/authority.json","data/darkwinter-online/host-policy.json").contains(path);
        if(!allowed||derivedTransient(path)||authoritySidecar(path))throw new IOException("Unsupported non-world/private replica file");return path;
    }
    /** Reserved typed sidecar files are transport artifacts, never native world paths. */
    public static boolean replicationArchivePath(String path) {
        return path!=null&&(path.equals("replication/testing/v1/manifest.json")||
            path.matches("replication/testing/v1/(?:versions|prefabs)/[0-9a-f-]{36}\\.json")||
            path.matches("replication/testing/v1/timelines/[0-9a-f]{64}\\.json")||
            path.matches("replication/testing/v1/objects/[0-9a-f]{64}")||
            path.equals("replication/testing/v1/legacy-prefab.json"));
    }
    static String hash(byte[] bytes){return HexFormat.of().formatHex(sha(bytes));}
    static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static String digest(String value)throws IOException{if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("Invalid replica SHA-256");return value;}
    static UUID uuid(String value)throws IOException{try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IOException("Noncanonical replica UUID");return id;}catch(RuntimeException invalid){throw new IOException("Invalid replica UUID",invalid);}}
    static long integer(JsonObject value,String key)throws IOException{long number=ProfileFiles.number(value,key);if(!value.get(key).getAsString().equals(Long.toString(number)))throw new IOException("Noncanonical replica integer");return number;}
    static String string(JsonObject value,String key,int max)throws IOException{return ProfileFiles.string(value,key,max);}
    static JsonObject object(JsonObject value,String key)throws IOException{JsonElement child=value.get(key);if(child==null||!child.isJsonObject())throw new IOException("Missing replica object");return child.getAsJsonObject();}
    static JsonObject canonical(byte[] bytes,int limit)throws IOException{JsonObject object=ProfileFiles.parse(bytes,limit);if(!Arrays.equals(bytes,ProfileFiles.bytes(object)))throw new IOException("Noncanonical replica document");return object;}
    static void fields(JsonObject value,String...keys)throws IOException{ProfileFiles.fields(value,Set.of(keys),Set.of());}
    private static void putUuid(ByteBuffer buffer,UUID value){buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());}
    private static UUID getUuid(ByteBuffer buffer){return new UUID(buffer.getLong(),buffer.getLong());}
}
