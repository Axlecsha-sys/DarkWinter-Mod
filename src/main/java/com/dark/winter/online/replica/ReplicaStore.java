// Maintains the private content-addressed checkpoint cache without opening native worlds.
package com.dark.winter.online.replica;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.SignedPayload;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Private content-addressed standby cache. Never extracts into or opens an active/native save. All IO is caller-worker IO. */
public final class ReplicaStore implements AutoCloseable {
    private static final int META_LIMIT=12*1024*1024;
    private final Path root,objects,staging,readyDir;private final ReplicaProtocol.Limits limits;private final ReplicaProtocol.Compatibility compatibility;
    private final UUID storeId;private final FileChannel lockChannel;private final FileLock lock;
    private final Map<UUID,Receiver> receivers=new HashMap<>();private long reserved;private boolean closed;
    private ReplicaStore(Path root,ReplicaProtocol.Limits limits,UUID storeId,FileChannel channel,FileLock lock,ReplicaProtocol.Compatibility compatibility){
        this.root=root;this.limits=limits;this.compatibility=compatibility;this.storeId=storeId;lockChannel=channel;this.lock=lock;
        objects=root.resolve("objects");staging=root.resolve("staging");readyDir=root.resolve("ready");
    }
    public static ReplicaStore open(Path directory,ReplicaProtocol.Limits limits,ReplicaProtocol.Compatibility compatibility)throws IOException{
        ReplicaStorageIO.safe(directory.toAbsolutePath().normalize());
        Path root=ProfileFiles.directory(directory),marker=root.resolve("store.json"),lockPath=root.resolve(".replica.lock");
        ReplicaStorageIO.safe(lockPath);FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;
        try{
            ProfileFiles.protect(lockPath,false);lock=channel.tryLock();if(lock==null)throw new IOException("Replica cache already open");
            UUID id;
            if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
                JsonObject value=ProfileFiles.read(marker,4096);ReplicaProtocol.fields(value,"schema","kind","id");
                if(ReplicaProtocol.integer(value,"schema")!=1||!ReplicaProtocol.string(value,"kind",32).equals("DarkWinterReplicaCache"))throw new IOException("Foreign replica cache marker");
                id=ReplicaProtocol.uuid(ReplicaProtocol.string(value,"id",36));
            }else{
                try(var children=Files.list(root)){if(children.anyMatch(p->!p.equals(lockPath)))throw new IOException("Replica cache directory is not empty/owned");}
                id=UUID.randomUUID();JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("kind","DarkWinterReplicaCache");value.addProperty("id",id.toString());ProfileFiles.atomic(marker,value,false);
            }
            ReplicaStore store=new ReplicaStore(root,limits,id,channel,lock,Objects.requireNonNull(compatibility));
            for(Path path:List.of(store.objects,store.staging,store.readyDir)){ReplicaStorageIO.safe(path);Files.createDirectories(path);ProfileFiles.protect(path,true);}
            store.cleanupStaging();store.usage();return store;
        }catch(IOException|RuntimeException failure){if(lock!=null)lock.release();channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Replica cache unavailable",failure);}
    }
    public synchronized Receiver begin(ReplicaProtocol.Offer offer,WorldAuthority.Certificate certificate,LeaseFence.Fence freshFence,Ready base)throws IOException{
        ensureOpen();if(receivers.size()>=ReplicaProtocol.MAX_RECEIVERS)throw new IOException("Replica receiver capacity/backpressure");
        if(base!=null&&!base.offer.header().authorityId().equals(certificate.authorityId()))throw new IOException("Foreign replica delta base");
        ReplicaProtocol.VerifiedOffer verified=ReplicaProtocol.verify(offer,certificate,freshFence,base==null?null:base.offer.index(),base==null?-1:base.offer.header().sequence(),limits,compatibility);
        Map<String,ReplicaProtocol.Entry> needed=unique(verified.index());long missing=0;int absent=0;
        for(var entry:needed.values()){
            Path blob=object(entry.sha256());
            if(Files.exists(blob,LinkOption.NOFOLLOW_LINKS))ReplicaStorageIO.verify(blob,entry);
            else{missing=Math.addExact(missing,entry.bytes());absent++;}
        }
        long metaReserve=2L*limits.indexBytes()+131072;
        long allocation=Math.addExact(missing,metaReserve),used=usage();
        if(used>limits.cacheBytes()-reserved||allocation>limits.cacheBytes()-used-reserved)throw new IOException("Replica cache disk quota");
        if(objectCount()+reservedObjects()+absent>maxObjects())throw new IOException("Replica cache object-count quota");
        if(Files.getFileStore(root).getUsableSpace()<allocation+reserved+limits.freeReserveBytes())throw new IOException("Insufficient free disk for complete replica");
        UUID id=UUID.randomUUID();Path dir=staging.resolve(id.toString());Files.createDirectory(dir);ProfileFiles.protect(dir,true);
        JsonObject owner=new JsonObject();owner.addProperty("schema",1);owner.addProperty("storeId",storeId.toString());owner.addProperty("receiver",id.toString());
        ProfileFiles.atomic(dir.resolve("owner.json"),owner,false);
        Receiver receiver=new Receiver(id,dir,certificate,verified,base==null?"":base.offer.index().contentRoot(),needed,allocation,absent);
        receivers.put(id,receiver);reserved+=allocation;
        try{receiver.emptyObjects();return receiver;}catch(IOException failure){receiver.cancel();throw failure;}
    }
    public final class Receiver implements AutoCloseable {
        private final UUID id;private final Path directory;private final WorldAuthority.Certificate certificate;
        private final ReplicaProtocol.VerifiedOffer offer;private final String baseRoot;
        private final Map<String,ReplicaProtocol.Entry> needed;private final Map<String,Writer> writers=new LinkedHashMap<>();
        private final Map<UUID,ReplicaProtocol.Request> outstanding=new HashMap<>();
        private long reservation;private int missingObjects;private boolean ended;
        private Receiver(UUID id,Path directory,WorldAuthority.Certificate certificate,ReplicaProtocol.VerifiedOffer offer,String baseRoot,
                         Map<String,ReplicaProtocol.Entry>needed,long reservation,int missingObjects){
            this.id=id;this.directory=directory;this.certificate=certificate;this.offer=offer;this.baseRoot=baseRoot;
            this.needed=needed;this.reservation=reservation;this.missingObjects=missingObjects;
        }
        public ReplicaProtocol.Header header(){return offer.header();}
        public int outstandingChunks(){synchronized(ReplicaStore.this){return outstanding.size();}}
        public List<ReplicaProtocol.Request> requests(LeaseFence.Fence freshFence)throws IOException{
            synchronized(ReplicaStore.this){
                active(freshFence);List<ReplicaProtocol.Request> result=new ArrayList<>();
                for(var entry:needed.values()){
                    if(outstanding.size()>=ReplicaProtocol.MAX_INFLIGHT)break;
                    if(Files.isRegularFile(object(entry.sha256()),LinkOption.NOFOLLOW_LINKS))continue;
                    Writer writer=writers.get(entry.sha256());
                    if(writer==null){
                        Path path=directory.resolve(entry.sha256()+".part");FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                        ProfileFiles.protect(path,false);writer=new Writer(entry,path,channel);writers.put(entry.sha256(),writer);
                    }
                    if(writer.pending!=null)continue;
                    int bytes=(int)Math.min(ReplicaProtocol.CHUNK_BYTES,entry.bytes()-writer.offset);
                    if(bytes<=0)throw new IOException("Invalid replica writer progress");
                    ReplicaProtocol.Request request=new ReplicaProtocol.Request(offer.header().snapshotId(),UUID.randomUUID(),entry.sha256(),writer.offset,bytes);
                    writer.pending=request.requestId();outstanding.put(request.requestId(),request);result.add(request);
                }
                return List.copyOf(result);
            }
        }
        public void accept(ReplicaProtocol.Chunk chunk,LeaseFence.Fence freshFence)throws IOException{
            synchronized(ReplicaStore.this){
                active(freshFence);byte[] data=chunk.data();ReplicaProtocol.validateChunk(chunk,data);
                ReplicaProtocol.Request request=outstanding.get(chunk.requestId());
                if(request==null||!request.snapshotId().equals(chunk.snapshotId())||!request.fileSha256().equals(chunk.fileSha256())||
                    request.offset()!=chunk.offset()||request.bytes()!=data.length)throw new IOException("Unrequested or replayed replica chunk");
                Writer writer=writers.get(request.fileSha256());
                if(writer==null||writer.offset!=request.offset()||!request.requestId().equals(writer.pending))throw new IOException("Replica chunk writer mismatch");
                try{
                    ByteBuffer buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())writer.channel.write(buffer);
                    writer.digest.update(data);writer.offset+=data.length;releaseReservation(data.length);
                    outstanding.remove(request.requestId());writer.pending=null;
                    if(writer.offset==writer.entry.bytes()){
                        writer.channel.force(true);writer.channel.close();
                        if(!HexFormat.of().formatHex(writer.digest.digest()).equals(writer.entry.sha256()))throw new IOException("Replica full file SHA mismatch");
                        publishObject(writer.path,writer.entry);writers.remove(writer.entry.sha256());missingObjects--;
                    }
                }catch(IOException failure){cancel();throw failure;}
            }
        }
        /** Only complete, fully rehashed file sets receive an atomic READY marker. No native world is opened. */
        public Ready finish(LeaseFence.Fence freshFence)throws IOException{
            synchronized(ReplicaStore.this){
                active(freshFence);if(!outstanding.isEmpty()||!writers.isEmpty())throw new IOException("Replica package is incomplete");
                for(var entry:needed.values())ReplicaStorageIO.verify(object(entry.sha256()),entry);
                ReplicaStorageIO.world(offer.index(),path->Files.newInputStream(object(offer.index().files().get(path).sha256()),LinkOption.NOFOLLOW_LINKS),certificate);
                ReplicaProtocol.sameFence(offer.header(),freshFence);
                Path file=readyPath(certificate.authorityId(),offer.header().sequence(),offer.index().contentRoot());
                if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                    if(readyFiles().size()>=limits.retainedReady())throw new IOException("Replica READY retention quota; prune explicitly while protecting confirmed checkpoint");
                    JsonObject metadata=metadata(certificate,offer);
                    if(ProfileFiles.bytes(metadata).length>META_LIMIT)throw new IOException("Replica READY metadata byte quota");
                    Files.createDirectories(file.getParent());ProfileFiles.protect(file.getParent(),true);
                    ProfileFiles.atomic(file,metadata,false);
                }else inspectReady(certificate,offer.index().contentRoot(),offer.header().sequence());
                ended=true;receivers.remove(id);releaseReservation(reservation);
                ReplicaStorageIO.removeOwnedTree(directory,staging);
                return new Ready(certificate,offer,file);
            }
        }
        public void cancel()throws IOException{
            synchronized(ReplicaStore.this){
                if(ended)return;ended=true;IOException failure=null;
                for(Writer writer:writers.values())try{writer.channel.close();}catch(IOException error){failure=error;}
                writers.clear();outstanding.clear();receivers.remove(id);releaseReservation(reservation);
                try{ReplicaStorageIO.removeOwnedTree(directory,staging);}catch(IOException error){failure=error;}
                if(failure!=null)throw failure;
            }
        }
        private void releaseReservation(long bytes){reservation-=bytes;reserved-=bytes;}
        private void active(LeaseFence.Fence fence)throws IOException{
            ensureOpen();if(ended||receivers.get(id)!=this)throw new IOException("Replica receive session ended");ReplicaProtocol.sameFence(offer.header(),fence);
        }
        private void emptyObjects()throws IOException{
            for(var entry:needed.values())if(entry.bytes()==0&&!Files.exists(object(entry.sha256()),LinkOption.NOFOLLOW_LINKS)){
                if(!entry.sha256().equals(ReplicaProtocol.hash(new byte[0])))throw new IOException("Empty replica object has nonempty hash");
                Path temp=directory.resolve(entry.sha256()+".part");ReplicaStorageIO.writeNew(temp,new byte[0]);publishObject(temp,entry);missingObjects--;
            }
        }
        @Override public void close()throws IOException{cancel();}
    }
    private static final class Writer{
        final ReplicaProtocol.Entry entry;final Path path;final FileChannel channel;final MessageDigest digest=ReplicaStorageIO.digest();
        long offset;UUID pending;
        Writer(ReplicaProtocol.Entry entry,Path path,FileChannel channel){this.entry=entry;this.path=path;this.channel=channel;}
    }
    public final class Ready{
        private final WorldAuthority.Certificate certificate;private final ReplicaProtocol.VerifiedOffer offer;private final Path marker;
        private Ready(WorldAuthority.Certificate certificate,ReplicaProtocol.VerifiedOffer offer,Path marker){this.certificate=certificate;this.offer=offer;this.marker=marker;}
        public WorldAuthority.Certificate certificate(){return certificate;}public ReplicaProtocol.Index index(){return offer.index();}
        public ReplicaProtocol.Header header(){return offer.header();}
        public SignedPayload receipt(IdentityStore actualReceiver,Instant completed)throws IOException{
            synchronized(ReplicaStore.this){ensureOpen();for(var entry:unique(offer.index()).values())ReplicaStorageIO.verify(object(entry.sha256()),entry);
                return ReplicaProtocol.readyAck(actualReceiver,offer,completed);}
        }
        public InputStream open(String relative)throws IOException{
            synchronized(ReplicaStore.this){ensureOpen();ReplicaProtocol.path(relative);ReplicaProtocol.Entry entry=offer.index().files().get(relative);
                if(entry==null)throw new IOException("File is not in this replica");ReplicaStorageIO.verify(object(entry.sha256()),entry);
                return new CheckingInput(Files.newInputStream(object(entry.sha256()),LinkOption.NOFOLLOW_LINKS),entry);}
        }
        public List<ReplicaPublisher.SourceBlob> sources()throws IOException{
            synchronized(ReplicaStore.this){ensureOpen();List<ReplicaPublisher.SourceBlob> sources=new ArrayList<>();
                for(var entry:offer.index().files().values())sources.add(new ReplicaPublisher.SourceBlob(entry,object(entry.sha256())));return List.copyOf(sources);}
        }
        /** New epoch is required. Caller must ALSO verify actual local identity/ACL eligibility before opening a copied save. */
        public void requirePromotion(LeaseFence.Fence newEpochFence,String actualLocalIdentity)throws IOException{
            synchronized(ReplicaStore.this){
                ensureOpen();ReplicaProtocol.fresh(certificate,newEpochFence);
                if(newEpochFence.epoch()<=offer.header().epoch()||!newEpochFence.holderId().equals(actualLocalIdentity)||
                    newEpochFence.checkpointSequence()!=offer.header().sequence()||!newEpochFence.checkpointSha256().equals(offer.index().contentRoot()))
                    throw new IOException("Replica is not the exact Node-confirmed checkpoint for this newly elected local host");
                for(var entry:unique(offer.index()).values())ReplicaStorageIO.verify(object(entry.sha256()),entry);
                ReplicaStorageIO.world(offer.index(),path->Files.newInputStream(object(offer.index().files().get(path).sha256()),LinkOption.NOFOLLOW_LINKS),certificate);
                newEpochFence.requireUsable();
            }
        }
    }
    private static final class CheckingInput extends FilterInputStream{
        private final ReplicaProtocol.Entry entry;private final MessageDigest digest=ReplicaStorageIO.digest();private long count;private boolean ended;
        CheckingInput(InputStream input,ReplicaProtocol.Entry entry){super(input);this.entry=entry;}
        @Override public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:Byte.toUnsignedInt(one[0]);}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            int n=in.read(bytes,offset,length);
            if(n>0){count+=n;if(count>entry.bytes())throw new IOException("Replica object grew during copy");digest.update(bytes,offset,n);}
            if(n<0&&!ended){ended=true;if(count!=entry.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))throw new IOException("Replica object changed during copy");}
            return n;
        }
    }
    /** Signature/full-byte integrity inspection survives previous producer expiry; it does not authorize host activation. */
    public synchronized Ready inspectReady(WorldAuthority.Certificate certificate,String contentRoot,long sequence)throws IOException{
        ensureOpen();ReplicaProtocol.digest(contentRoot);WorldAuthority.verify(certificate);
        Path path=readyPath(certificate.authorityId(),sequence,contentRoot);JsonObject value=ProfileFiles.read(path,META_LIMIT);
        ReplicaProtocol.fields(value,"schema","kind","certificate","header","transferIndex","fullIndex","sha256");
        String expected=ReplicaProtocol.digest(ReplicaProtocol.string(value,"sha256",64));JsonObject body=value.deepCopy();body.remove("sha256");
        if(!ReplicaProtocol.hash(ProfileFiles.bytes(body)).equals(expected)||ReplicaProtocol.integer(value,"schema")!=1||
            !ReplicaProtocol.string(value,"kind",32).equals("DarkWinterReplicaReady"))throw new IOException("Replica READY metadata hash/schema");
        WorldAuthority.Certificate stored=WorldAuthority.certificateFromJson(ReplicaProtocol.object(value,"certificate"));
        WorldAuthority.requirePinned(stored,certificate.authorityId(),WorldAuthority.verify(certificate).ownerId());
        SignedPayload header=WorldAuthority.signedFromJson(ReplicaProtocol.object(value,"header"));
        ReplicaProtocol.Offer offer=new ReplicaProtocol.Offer(header,ProfileFiles.bytes(ReplicaProtocol.object(value,"transferIndex")));
        ReplicaProtocol.Index index=ReplicaProtocol.decodeIndex(ProfileFiles.bytes(ReplicaProtocol.object(value,"fullIndex")),limits);
        ReplicaProtocol.VerifiedOffer verified=ReplicaProtocol.stored(offer,index,certificate,limits,compatibility);
        if(verified.header().sequence()!=sequence||!index.contentRoot().equals(contentRoot))throw new IOException("Replica READY name/index mismatch");
        for(var entry:unique(index).values())ReplicaStorageIO.verify(object(entry.sha256()),entry);
        ReplicaStorageIO.world(index,file->Files.newInputStream(object(index.files().get(file).sha256()),LinkOption.NOFOLLOW_LINKS),certificate);
        return new Ready(certificate,verified,path);
    }
    /** Explicit cache-only pruning; caller must keep the last confirmed recoverable checkpoint. Never touches user archive versions. */
    public synchronized long prune(Set<String> keepContentRoots)throws IOException{
        ensureOpen();Set<String> keep=new HashSet<>();for(String hash:keepContentRoots)keep.add(ReplicaProtocol.digest(hash));
        Set<String> present=new HashSet<>(),used=new HashSet<>();Map<Path,Ready> verified=new LinkedHashMap<>();
        for(Receiver receiver:receivers.values()){
            keep.add(receiver.offer.index().contentRoot());present.add(receiver.offer.index().contentRoot());
            if(!receiver.baseRoot.isEmpty()){keep.add(receiver.baseRoot);present.add(receiver.baseRoot);}
            used.addAll(receiver.needed.keySet());
        }
        // Preflight ALL metadata/signatures/names/full file hashes before deleting ANY file.
        for(Path file:readyFiles()){
            JsonObject value=ProfileFiles.read(file,META_LIMIT);
            WorldAuthority.Certificate certificate=WorldAuthority.certificateFromJson(ReplicaProtocol.object(value,"certificate"));
            ReplicaProtocol.Index index=ReplicaProtocol.decodeIndex(ProfileFiles.bytes(ReplicaProtocol.object(value,"fullIndex")),limits);
            SignedPayload header=WorldAuthority.signedFromJson(ReplicaProtocol.object(value,"header"));
            ReplicaProtocol.Header h=ReplicaProtocol.header(header);
            Ready ready=inspectReady(certificate,index.contentRoot(),h.sequence());
            if(!ready.marker.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize()))throw new IOException("Replica READY name mismatch before pruning");
            verified.put(file,ready);present.add(index.contentRoot());
        }
        if(!present.containsAll(keep))throw new IOException("Protected replica checkpoint is missing; cache pruning aborted");
        for(Ready ready:verified.values())if(keep.contains(ready.index().contentRoot()))used.addAll(unique(ready.index()).keySet());
        List<Path> blobs=objectFiles();long deleted=0;
        for(var entry:verified.entrySet())if(!keep.contains(entry.getValue().index().contentRoot())){deleted+=Files.size(entry.getKey());Files.delete(entry.getKey());}
        for(Path file:blobs){String hash=file.getFileName().toString().substring(0,64);if(!used.contains(hash)){deleted+=Files.size(file);Files.delete(file);}}
        return deleted;
    }
    public synchronized long cachedBytes()throws IOException{ensureOpen();return usage();}
    private static JsonObject metadata(WorldAuthority.Certificate certificate,ReplicaProtocol.VerifiedOffer offer)throws IOException{
        JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("kind","DarkWinterReplicaReady");
        value.add("certificate",WorldAuthority.certificateToJson(certificate));value.add("header",new Gson().toJsonTree(offer.offer().header()));
        value.add("transferIndex",ReplicaProtocol.canonical(offer.offer().index(),4*1024*1024));value.add("fullIndex",ReplicaProtocol.canonical(offer.index().encoded(),4*1024*1024));
        value.addProperty("sha256",ReplicaProtocol.hash(ProfileFiles.bytes(value)));return value;
    }
    private void publishObject(Path temp,ReplicaProtocol.Entry entry)throws IOException{
        Path target=object(entry.sha256());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){ReplicaStorageIO.verify(target,entry);Files.delete(temp);}
        else{
            try{Files.createLink(target,temp);}catch(FileAlreadyExistsException concurrent){ReplicaStorageIO.verify(target,entry);}
            Files.delete(temp);
        }
    }
    private Path object(String hash)throws IOException{ReplicaProtocol.digest(hash);return objects.resolve(hash+".blob");}
    private Path readyPath(String authority,long sequence,String hash)throws IOException{
        if(!authority.matches("dwa1_[A-Za-z0-9_-]{43}")||sequence<0)throw new IOException("Invalid replica READY key");ReplicaProtocol.digest(hash);
        Path file=readyDir.resolve(authority).resolve(String.format(Locale.ROOT,"%020d-%s.json",sequence,hash));ReplicaStorageIO.safe(file);return file;
    }
    private static Map<String,ReplicaProtocol.Entry> unique(ReplicaProtocol.Index index){
        Map<String,ReplicaProtocol.Entry> entries=new TreeMap<>();for(var entry:index.files().values())entries.putIfAbsent(entry.sha256(),entry);return entries;
    }
    private int maxObjects(){return limits.files()*(limits.retainedReady()+ReplicaProtocol.MAX_RECEIVERS);}
    private int objectCount()throws IOException{return objectFiles().size();}
    private int reservedObjects(){return receivers.values().stream().mapToInt(receiver->receiver.missingObjects).sum();}
    private List<Path> objectFiles()throws IOException{
        List<Path> paths;try(var files=Files.list(objects)){paths=files.limit(maxObjects()+1L).toList();}
        if(paths.size()>maxObjects())throw new IOException("Replica cache object count exceeded");
        for(Path path:paths)if(!path.getFileName().toString().matches("[0-9a-f]{64}\\.blob")||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unknown or linked replica cache object");
        return paths;
    }
    private List<Path> readyFiles()throws IOException{
        List<Path> paths;try(var files=Files.walk(readyDir)){paths=files.filter(p->!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).limit(limits.retainedReady()+1L).toList();}
        if(paths.size()>limits.retainedReady())throw new IOException("Replica READY count quota");
        for(Path path:paths)if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.getFileName().toString().matches("[0-9]{20}-[0-9a-f]{64}\\.json"))throw new IOException("Unknown replica READY entry");
        return paths;
    }
    private long usage()throws IOException{
        long sum=0;int max=maxObjects()+limits.files()*ReplicaProtocol.MAX_RECEIVERS+256,count=0;
        try(var files=Files.walk(root)){
            Iterator<Path> iterator=files.iterator();while(iterator.hasNext()){Path path=iterator.next();if(++count>max)throw new IOException("Replica cache entry quota");
                if(Files.isSymbolicLink(path))throw new IOException("Linked replica cache entry");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))sum=Math.addExact(sum,Files.size(path));}
        }
        if(sum>limits.cacheBytes())throw new IOException("Replica cache byte quota exceeded");return sum;
    }
    private void cleanupStaging()throws IOException{
        List<Path> paths;try(var files=Files.list(staging)){paths=files.limit(ReplicaProtocol.MAX_RECEIVERS+1L).toList();}
        if(paths.size()>ReplicaProtocol.MAX_RECEIVERS)throw new IOException("Too many interrupted replica receivers");
        for(Path path:paths){
            ReplicaStorageIO.safe(path);UUID id=ReplicaProtocol.uuid(path.getFileName().toString());JsonObject marker=ProfileFiles.read(path.resolve("owner.json"),4096);
            ReplicaProtocol.fields(marker,"schema","storeId","receiver");
            if(ReplicaProtocol.integer(marker,"schema")!=1||!storeId.equals(ReplicaProtocol.uuid(ReplicaProtocol.string(marker,"storeId",36)))||
                !id.equals(ReplicaProtocol.uuid(ReplicaProtocol.string(marker,"receiver",36))))throw new IOException("Foreign interrupted replica staging");
            ReplicaStorageIO.removeOwnedTree(path,staging);
        }
    }
    private void ensureOpen()throws IOException{if(closed)throw new IOException("Replica store closed");}
    @Override public synchronized void close()throws IOException{
        if(closed)return;IOException failure=null;for(Receiver receiver:List.copyOf(receivers.values()))try{receiver.cancel();}catch(IOException error){failure=error;}
        closed=true;try{lock.release();}finally{lockChannel.close();}if(failure!=null)throw failure;
    }
}
