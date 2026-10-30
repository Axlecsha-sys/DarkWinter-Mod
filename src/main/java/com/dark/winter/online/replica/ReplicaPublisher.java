// Reads only explicit immutable archive blobs with declared hashes, never a live or ordinary save directory.
package com.dark.winter.online.replica;

import com.dark.winter.online.authority.*;
import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Reads only explicit immutable archive blobs with declared hashes, never a live or ordinary save directory. */
public final class ReplicaPublisher {
    private static final Semaphore READS=new Semaphore(4);
    public record SourceBlob(ReplicaProtocol.Entry entry,Path immutableBlob){
        @Override public String toString(){return "ReplicaSourceBlob[localPath=redacted]";}
    }
    private record Source(SourceBlob blob,ReplicaStorageIO.Stamp stamp){}
    public static final class Prepared{
        private final ReplicaProtocol.Index index;private final ReplicaProtocol.Compatibility compatibility;private final Map<String,Source> byHash;private final List<String> excluded;private final WorldAuthority.Certificate sidecar;private final WorldAuthority.Policy sidecarPolicy;
        private Prepared(ReplicaProtocol.Index index,Map<String,Source> byHash,List<String> excluded,WorldAuthority.Certificate sidecar,WorldAuthority.Policy sidecarPolicy,ReplicaProtocol.Compatibility compatibility){this.index=index;this.compatibility=compatibility;this.byHash=Map.copyOf(byHash);this.excluded=List.copyOf(excluded);this.sidecar=sidecar;this.sidecarPolicy=sidecarPolicy;}
        public ReplicaProtocol.Index index(){return index;}public List<String> excludedManagedFiles(){return excluded;}
        public ReplicaProtocol.Offer offer(IdentityStore holder,WorldAuthority.Certificate certificate,LeaseFence.Fence fence,long sequence,
                                           ReplicaProtocol.Index base,long baseSequence,Instant created)throws IOException{
            if(sidecar!=null&&(!sidecar.authorityId().equals(certificate.authorityId())||sidecarPolicy.revision()>fence.policyRevision()))throw new IOException("Snapshot public authority sidecar differs from pinned certificate/confirmed policy");
            return ReplicaProtocol.offer(holder,certificate,fence,index,sequence,base,baseSequence,created,compatibility);
        }
        /** One shared prepared snapshot serves up to16 participants; at most4 bounded reads run concurrently. */
        public ReplicaProtocol.Chunk read(ReplicaProtocol.VerifiedOffer offer,ReplicaProtocol.Request request,LeaseFence.Fence freshFence)throws IOException{
            ReplicaProtocol.sameFence(offer.header(),freshFence);
            if(!offer.index().contentRoot().equals(index.contentRoot())||!offer.header().snapshotId().equals(request.snapshotId()))
                throw new IOException("Replica request belongs to another package");
            Source source=byHash.get(ReplicaProtocol.digest(request.fileSha256()));
            if(source==null||request.requestId()==null||request.offset()<0||request.offset()%ReplicaProtocol.CHUNK_BYTES!=0||
                request.bytes()<1||request.bytes()>ReplicaProtocol.CHUNK_BYTES||request.offset()>=source.blob().entry().bytes()||
                request.bytes()!=Math.min(ReplicaProtocol.CHUNK_BYTES,source.blob().entry().bytes()-request.offset()))
                throw new IOException("Replica request is outside declared file/range");
            if(!READS.tryAcquire())throw new IOException("Replica publisher backpressure");
            try{
                Path file=source.blob().immutableBlob();if(!source.stamp().equals(ReplicaStorageIO.stamp(file)))throw new IOException("Replica source blob changed");
                ByteBuffer bytes=ByteBuffer.allocate(request.bytes());
                try(FileChannel input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                    input.position(request.offset());while(bytes.hasRemaining()){int n=input.read(bytes);if(n<0)throw new IOException("Truncated immutable replica source");}
                }
                if(!source.stamp().equals(ReplicaStorageIO.stamp(file)))throw new IOException("Replica source changed during chunk read");
                return new ReplicaProtocol.Chunk(request.snapshotId(),request.requestId(),request.fileSha256(),request.offset(),bytes.array());
            }finally{READS.release();}
        }
    }
    private ReplicaPublisher(){}
    public static Prepared prepare(Collection<SourceBlob> declaredArchiveFiles,Map<String,UUID> actualNativeAliases,ReplicaProtocol.Limits limits,ReplicaProtocol.Compatibility compatibility)throws IOException{
        if(declaredArchiveFiles==null||declaredArchiveFiles.size()>limits.files()+16)throw new IOException("Replica source catalog count quota");
        List<ReplicaProtocol.Entry> entries=new ArrayList<>();List<SourceBlob> included=new ArrayList<>();List<String> excluded=new ArrayList<>();WorldAuthority.Certificate sidecar=null;WorldAuthority.Policy sidecarPolicy=null;Set<String> declared=new HashSet<>();
        for(SourceBlob blob:declaredArchiveFiles){
            if(blob==null||blob.entry()==null||blob.entry().path()==null||blob.immutableBlob()==null)throw new IOException("Incomplete replica source blob declaration");
            if(!declared.add(blob.entry().path()))throw new IOException("Duplicate replica source catalog path");
            if(ReplicaProtocol.authoritySidecar(blob.entry().path())){
                if(sidecar!=null||blob.entry().bytes()>65536)throw new IOException("Duplicated/oversized public authority sidecar");
                Path file=blob.immutableBlob().toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(file.getParent());ReplicaStorageIO.verify(file,blob.entry());
                JsonObject doc=ProfileFiles.read(file,65536);ProfileFiles.fields(doc,Set.of("schema","certificate","policy"),Set.of());
                if(ReplicaProtocol.integer(doc,"schema")!=1)throw new IOException("Unsupported public authority sidecar");
                sidecar=WorldAuthority.certificateFromJson(ReplicaProtocol.object(doc,"certificate"));
                sidecarPolicy=WorldAuthority.verifyPolicy(sidecar,WorldAuthority.signedFromJson(ReplicaProtocol.object(doc,"policy")));
                excluded.add(blob.entry().path());continue;
            }
            if(ReplicaProtocol.derivedTransient(blob.entry().path())){
                if(blob.entry().bytes()<0||blob.entry().bytes()>16)throw new IOException("Oversized alleged transient world lock");
                excluded.add(blob.entry().path());continue;
            }
            entries.add(blob.entry());included.add(blob);
        }
        ReplicaProtocol.Index index=ReplicaProtocol.index(entries,actualNativeAliases,limits);Map<String,Source> sources=new HashMap<>();Map<String,Path> paths=new HashMap<>();
        for(SourceBlob blob:included){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Replica source preparation canceled");
            Path file=blob.immutableBlob().toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(file.getParent());
            ReplicaStorageIO.verify(file,blob.entry());Source source=new Source(new SourceBlob(blob.entry(),file),ReplicaStorageIO.stamp(file));
            sources.putIfAbsent(blob.entry().sha256(),source);paths.put(blob.entry().path(),file);
        }
        ReplicaStorageIO.world(index,path->Files.newInputStream(paths.get(path),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),null);
        return new Prepared(index,sources,excluded,sidecar,sidecarPolicy,Objects.requireNonNull(compatibility));
    }
}
