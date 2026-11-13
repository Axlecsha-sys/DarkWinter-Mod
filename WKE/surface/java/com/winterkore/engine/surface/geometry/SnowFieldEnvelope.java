package com.winterkore.engine.surface.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded shared samples of an exact contact field. Inputs contain already owner-filtered kernels.
 * A worker can warm the same logical tile used by rendering. Kernel evaluation never holds the
 * cache lock; old handles retain their own immutable input and cannot overwrite a newer source.
 */
public final class SnowFieldEnvelope {
    private SnowFieldEnvelope(){}
    public record TileKey(long worldEpoch,long owner,long geometryRevision,long rootTile) {}
    /** Intrinsic geometry, independent of Prepared identity, ledger age and filtered owner metadata. */
    public record KernelKey(double x,double z,float yaw,float width,float length,float depth,boolean vehicle,int profile) {
        public SnowContactRelief.Prepared prepare(){
            return SnowContactRelief.prepare(x,z,yaw,width,length,depth,vehicle,profile);
        }
    }
    /** The prepared value must be made from exactly the accompanying key. */
    public record Entry(KernelKey key,SnowContactRelief.Prepared prepared) {
        public Entry {Objects.requireNonNull(key);Objects.requireNonNull(prepared);}
    }
    /** Construct once per immutable source/context. Versions increase within a TileKey. */
    public static final class Input {
        private final long version;private final Map<KernelKey,Entry> byKey;
        private final Entry[] depressionOrder,rimOrder;
        private final OwnerGrid grid;
        private final GridOrigin origin;
        public Input(long version,List<Entry> entries){
            this(version,entries,null,List.of());
        }
        public Input(long version,List<Entry> entries,int ownerX,int ownerZ){
            this(version,entries,new GridOrigin(ownerX,ownerZ),List.of());
        }
        private Input(long version,List<Entry> entries,GridOrigin origin,List<OwnerGrid> previousGrids){
            this.version=version;this.origin=origin;var map=new LinkedHashMap<KernelKey,Entry>();
            for(var entry:entries)map.putIfAbsent(entry.key(),entry);
            byKey=Map.copyOf(map);depressionOrder=map.values().toArray(Entry[]::new);rimOrder=depressionOrder.clone();
            Arrays.sort(depressionOrder,Comparator.comparingDouble(
                    (Entry e)->e.prepared().maxDepressionMillimeters()).reversed());
            Arrays.sort(rimOrder,Comparator.comparingDouble(
                    (Entry e)->e.prepared().maxRimMillimeters()).reversed());
            grid=OwnerGrid.create(origin,depressionOrder,rimOrder,previousGrids);
        }
        private Input(long version,Input sameField){
            this.version=version;origin=sameField.origin;byKey=sameField.byKey;
            depressionOrder=sameField.depressionOrder;rimOrder=sameField.rimOrder;grid=sameField.grid;
        }
        public long version(){return version;}
        public int size(){return byKey.size();}
    }
    private record GridOrigin(int x,int z) {}
    private record OwnerMasks(long depression,long influence) {
        private static final OwnerMasks NONE=new OwnerMasks(0,0);
    }
    /** Fixed owner bins only narrow the candidate list. Point coordinates and kernel values remain exact. */
    private static final class OwnerGrid {
        private static final int SIDE=8,BINS=SIDE*SIDE;
        private static final double STEP=1.0/SIDE;
        private static final int WORLD_LIMIT=30_000_003;
        private static final int[] EMPTY=new int[0];
        final GridOrigin origin;
        final Map<KernelKey,OwnerMasks> masks;
        final int[][] depression,rim,influenceByDepth;
        final long estimatedBytes;

        private OwnerGrid(GridOrigin origin,Map<KernelKey,OwnerMasks> masks,Entry[] depressionOrder,Entry[] rimOrder){
            this.origin=origin;this.masks=Map.copyOf(masks);
            depression=ordinals(depressionOrder,true);rim=ordinals(rimOrder,false);
            influenceByDepth=ordinals(depressionOrder,false);
            long bytes=2048+8L*BINS+80L*masks.size();
            for(int bin=0;bin<BINS;bin++){
                if(depression[bin].length!=0)bytes+=32L+4L*depression[bin].length;
                if(rim[bin].length!=0)bytes+=32L+4L*rim[bin].length;
                if(influenceByDepth[bin].length!=0)bytes+=32L+4L*influenceByDepth[bin].length;
            }
            estimatedBytes=bytes;
        }
        static OwnerGrid create(GridOrigin origin,Entry[] depressionOrder,Entry[] rimOrder,List<OwnerGrid> previous){
            if(origin==null||Math.abs((long)origin.x())>WORLD_LIMIT||Math.abs((long)origin.z())>WORLD_LIMIT)return null;
            var masks=new HashMap<KernelKey,OwnerMasks>();
            for(var entry:depressionOrder){
                var key=entry.key();
                if(!physical(key))return null;
                OwnerMasks mask=null;
                for(var old:previous){
                    if(!origin.equals(old.origin))continue;
                    mask=old.masks.get(key);
                    if(mask!=null)break;
                }
                masks.put(key,mask==null?touchedBins(origin,entry.prepared()):mask);
            }
            return new OwnerGrid(origin,masks,depressionOrder,rimOrder);
        }
        private static boolean physical(KernelKey key){
            return Double.isFinite(key.x())&&Double.isFinite(key.z())
                    &&Math.abs(key.x())<=WORLD_LIMIT&&Math.abs(key.z())<=WORLD_LIMIT
                    &&Float.isFinite(key.yaw())&&key.width()>=.04f&&key.width()<=2
                    &&key.length()>=.08f&&key.length()<=4&&key.depth()>=0&&key.depth()<=750
                    &&SoleProfiles.valid(key.profile());
        }
        private static OwnerMasks touchedBins(GridOrigin origin,SnowContactRelief.Prepared kernel){
            long depression=0,influence=0;
            for(int z=0;z<SIDE;z++)for(int x=0;x<SIDE;x++){
                double x0=origin.x()+x*STEP,z0=origin.z()+z*STEP;
                // Closed bins and the conservative rotated supports include shared edges.
                if(!kernel.influenceIntersectsBounds(x0,z0,x0+STEP,z0+STEP))continue;
                long bit=1L<<(z*SIDE+x);
                influence|=bit;
                if(kernel.depressionIntersectsBounds(x0,z0,x0+STEP,z0+STEP))depression|=bit;
            }
            return new OwnerMasks(depression,influence);
        }
        private long mask(Entry entry,boolean depressionOnly){
            var coverage=masks.get(entry.key());
            return depressionOnly?coverage.depression:coverage.influence;
        }
        private int[][] ordinals(Entry[] order,boolean depressionOnly){
            int[] counts=new int[BINS];
            for(var entry:order)for(long bits=mask(entry,depressionOnly);bits!=0;bits&=bits-1)
                counts[Long.numberOfTrailingZeros(bits)]++;
            int[][] rows=new int[BINS][];
            for(int bin=0;bin<BINS;bin++)rows[bin]=counts[bin]==0?EMPTY:new int[counts[bin]];
            Arrays.fill(counts,0);
            for(int i=0;i<order.length;i++)for(long bits=mask(order[i],depressionOnly);bits!=0;bits&=bits-1){
                int bin=Long.numberOfTrailingZeros(bits);
                rows[bin][counts[bin]++]=i;
            }
            return rows;
        }
        int bin(double x,double z){
            double dx=x-origin.x(),dz=z-origin.z();
            if(dx<0||dx>1||dz<0||dz>1)return -1;
            int bx=Math.min(SIDE-1,(int)(dx*SIDE)),bz=Math.min(SIDE-1,(int)(dz*SIDE));
            return bz*SIDE+bx;
        }
    }
    /** Kernel calls and visited sorted candidates are measured separately. */
    public record Metrics(long queries,long pointHits,long pointMisses,long fullHistoryQueries,
            long deltaKernelEvaluations,long fullKernelEvaluations,long depressionRebuilds,
            long coverageRebuilds,long rimRebuilds,long updatedPoints,long pointEvictions,
            long tileEvictions,long sourceVersionEvictions,long staleQueries,long retainedBytes,int tiles,int points,
            long preparedEntriesCreated,long preparedEntriesReused,
            long fullCandidateVisits,long deltaCandidateVisits,long spatialQueries,long reusedVersionPoints) {}
    public static final class Handle {
        private final Cache cache;private final TileKey key;private final Input input;private final long versionId;
        private Handle(Cache cache,TileKey key,Input input,long versionId){
            this.cache=cache;this.key=key;this.input=input;this.versionId=versionId;
        }
        public double signedMillimeters(double x,double z){return cache.sample(this,x,z);}
        /** Relief is independent of Y; actual owner geometry is part of TileKey and input filtering. */
        public double signedMillimeters(double x,double ignoredY,double z){return signedMillimeters(x,z);}
    }
    /**
     * Cache-owned retention has a conservative byte estimate, including source keys, Prepared
     * edge arrays, version maps/deltas and primitive point storage. This is not a JVM heap meter.
     * At most three source versions per tile are retained. Caller-owned live Inputs/Handles may
     * outlive eviction; they remain exact through an uncached path and are not cache-owned memory.
     */
    public static final class Cache {
        private static final int VERSIONS=3,MAX_POINT_SLOTS=262144;
        private static final long TILE_BYTES=512,VERSION_BYTES=512,VERSION_ENTRY_BYTES=512,PREPARED_ENTRY_BYTES=2048;
        // A query calls only immutable kernels, so its primitive scratch cannot be reentered.
        private static final ThreadLocal<Evaluation> EVALUATION=ThreadLocal.withInitial(Evaluation::new);
        private final Object lock=new Object();private final int maxTiles;private final long maxBytes;
        private final boolean newestSamplesOnly;
        private final LinkedHashMap<TileKey,Tile> tiles=new LinkedHashMap<>(16,.75f,true);
        private final long fixedBytes;
        private final AtomicLong ids=new AtomicLong();private final PointTable table;
        private long queries,pointHits,pointMisses,fullHistoryQueries,deltaKernelEvaluations,fullKernelEvaluations,
                depressionRebuilds,coverageRebuilds,rimRebuilds,updatedPoints,pointEvictions,tileEvictions,
                sourceVersionEvictions,staleQueries,retainedBytes,preparedEntriesCreated,preparedEntriesReused,
                fullCandidateVisits,deltaCandidateVisits,spatialQueries,reusedVersionPoints;
        private int points;private long generation;
        private TileKey recentTileKey;private Tile recentTile;
        public Cache(int maxTiles,long maxBytes){this(maxTiles,maxBytes,false);}
        /**
         * Opt-in for sequential interactive sources: only the newest logical field may publish
         * point samples, and it promotes matching older coordinates before consuming empty ways.
         * Frozen older handles still read their exact inputs; the default retains late worker warming.
         */
        public Cache(int maxTiles,long maxBytes,boolean newestSamplesOnly){
            if(maxTiles<1||maxBytes<0)throw new IllegalArgumentException("Positive tile and nonnegative byte limits required");
            this.maxTiles=maxTiles;this.maxBytes=maxBytes;this.newestSamplesOnly=newestSamplesOnly;
            long available=Math.min(MAX_POINT_SLOTS,maxBytes/256);
            int capacity=available<PointTable.WAYS?0:Integer.highestOneBit((int)available);
            table=new PointTable(capacity);
            fixedBytes=PointTable.bytes(capacity);retainedBytes=fixedBytes;
        }
        /**
         * Reuse retained prepared entries before preparing new geometry. The resulting source is
         * immutable and can be shared by rendering and the worker. Call once per source/context.
         */
        public Input prepareInput(TileKey key,long version,List<KernelKey> kernels){
            return prepareInput(key,version,kernels,null);
        }
        /** Optional actual owner bounds. Samples outside the closed owner box use the full source. */
        public Input prepareInput(TileKey key,long version,List<KernelKey> kernels,int ownerX,int ownerZ){
            return prepareInput(key,version,kernels,new GridOrigin(ownerX,ownerZ));
        }
        private Input prepareInput(TileKey key,long version,List<KernelKey> kernels,GridOrigin origin){
            Objects.requireNonNull(key);Objects.requireNonNull(kernels);
            Version[] previous;
            synchronized(lock){
                var tile=touchTile(key);previous=tile==null?new Version[0]:tile.versions.toArray(Version[]::new);
            }
            var keys=new LinkedHashSet<>(kernels);
            for(var source:previous)if(source.input.version==version){
                if(!source.input.byKey.keySet().equals(keys))
                    throw new IllegalArgumentException("Equal input versions require equal kernel sets");
                return source.input;
            }
            for(var source:previous)if(Objects.equals(origin,source.input.origin)&&source.input.byKey.keySet().equals(keys)){
                synchronized(lock){preparedEntriesReused+=keys.size();}
                return new Input(version,source.input);
            }
            var entries=new ArrayList<Entry>(keys.size());int created=0,reused=0;
            for(var kernel:keys){
                Entry found=null;
                for(var source:previous){found=source.input.byKey.get(kernel);if(found!=null)break;}
                if(found==null){found=new Entry(kernel,kernel.prepare());created++;}else reused++;
                entries.add(found);
            }
            var previousGrids=new ArrayList<OwnerGrid>(previous.length);
            if(origin!=null)for(var source:previous)if(source.input.grid!=null)previousGrids.add(source.input.grid);
            var input=new Input(version,entries,origin,previousGrids);
            synchronized(lock){preparedEntriesCreated+=created;preparedEntriesReused+=reused;}
            return input;
        }
        public Handle bind(TileKey key,Input input){
            Objects.requireNonNull(key);Objects.requireNonNull(input);
            if(table.capacity()==0)return new Handle(this,key,input,0);
            for(;;){
                Version[] baseline;Version known;long observedGeneration;
                synchronized(lock){
                    var tile=touchTile(key);known=find(tile,input.version);observedGeneration=generation;
                    if(known==null&&tooOld(tile,input))return new Handle(this,key,input,0);
                    baseline=known!=null||tile==null?null:tile.versions.toArray(Version[]::new);
                }
                if(known!=null){
                    if(known.input!=input&&!known.input.byKey.keySet().equals(input.byKey.keySet()))
                        throw new IllegalArgumentException("Equal input versions require equal kernel sets");
                    return new Handle(this,key,input,known.id);
                }
                // Allocation and key comparisons are outside the sampling lock. Retry if another
                // source bound first, so equal keys receive the same IDs across retained versions.
                var proposed=new Version(ids.incrementAndGet(),input,baseline,ids);
                var retained=new ArrayList<Version>(VERSIONS+1);retained.add(proposed);
                if(baseline!=null)for(var previous:baseline)retained.add(previous);
                retained.sort(Comparator.comparingLong((Version v)->v.input.version).reversed());
                while(retained.size()>VERSIONS)retained.removeLast();
                long bytes=estimatedTileBytes(retained);
                while(bytes>maxBytes-fixedBytes&&!retained.isEmpty()){
                    retained.removeLast();bytes=estimatedTileBytes(retained);
                }
                if(!retained.contains(proposed))return new Handle(this,key,input,0);
                for(var source:retained)if(source.input.version<input.version)
                    proposed.rememberTransition(source.id,Transition.between(proposed,source));
                synchronized(lock){
                    if(generation!=observedGeneration)return new Handle(this,key,input,0);
                    var tile=touchTile(key);
                    if(!sameVersions(tile,baseline))continue;
                    if(tile==null)while(tiles.size()>=maxTiles)evictOldest(null);
                    long extra=bytes-(tile==null?0:tile.retainedBytes);
                    if(!room(extra,tile))return new Handle(this,key,input,0);
                    proposed.active=true;proposed.pointOwner.activeVersions++;
                    if(tile==null){tile=new Tile(proposed.id);tiles.put(key,tile);recentTileKey=key;recentTile=tile;}
                    else for(var previous:tile.versions)if(!retained.contains(previous)){
                        removeVersionOnly(previous);
                        for(var version:retained)version.forgetTransition(previous.id);
                    }
                    tile.versions.clear();tile.versions.addAll(retained);tile.retainedBytes=bytes;
                    retainedBytes+=extra;
                    return new Handle(this,key,input,proposed.id);
                }
            }
        }
        private static long estimatedTileBytes(List<Version> versions){
            var distinct=new IdentityHashMap<Entry,Boolean>();long bytes=TILE_BYTES;
            for(int i=0;i<versions.size();i++){
                var version=versions.get(i);bytes+=VERSION_BYTES;
                boolean newKernels=true,newGrid=version.input.grid!=null,newEntries=true;
                for(int j=0;j<i;j++){
                    var previous=versions.get(j);
                    newKernels&=previous.byKey!=version.byKey;
                    newGrid&=previous.input.grid!=version.input.grid;
                    newEntries&=previous.input.byKey!=version.input.byKey;
                }
                if(newKernels)bytes+=VERSION_ENTRY_BYTES*version.input.size();
                if(newGrid)bytes+=version.input.grid.estimatedBytes;
                if(newEntries)for(var entry:version.input.depressionOrder)distinct.put(entry,Boolean.TRUE);
            }
            return bytes+PREPARED_ENTRY_BYTES*distinct.size();
        }
        private static boolean tooOld(Tile tile,Input input){
            return tile!=null&&tile.versions.size()==VERSIONS&&input.version<tile.versions.getLast().input.version;
        }
        private static boolean sameVersions(Tile tile,Version[] baseline){
            if(tile==null)return baseline==null;
            if(baseline==null||tile.versions.size()!=baseline.length)return false;
            for(int i=0;i<baseline.length;i++)if(tile.versions.get(i)!=baseline[i])return false;
            return true;
        }
        public void clear(){
            synchronized(lock){
                tileEvictions+=tiles.size();
                for(var tile:tiles.values())for(var version:tile.versions)removeVersionOnly(version);
                generation++;tiles.clear();recentTileKey=null;recentTile=null;points=0;retainedBytes=fixedBytes;table.clear();
            }
        }
        public Metrics metrics(){
            synchronized(lock){return new Metrics(queries,pointHits,pointMisses,fullHistoryQueries,
                    deltaKernelEvaluations,fullKernelEvaluations,depressionRebuilds,coverageRebuilds,rimRebuilds,
                    updatedPoints,pointEvictions,tileEvictions,sourceVersionEvictions,staleQueries,
                    retainedBytes,tiles.size(),points,preparedEntriesCreated,preparedEntriesReused,
                    fullCandidateVisits,deltaCandidateVisits,spatialQueries,reusedVersionPoints);}
        }
        // Repeated samples of the same tile already leave it most recently used.
        // All access, insertion, eviction and reset sites maintain this under lock.
        private Tile touchTile(TileKey key){
            if(key==recentTileKey||key.equals(recentTileKey))return recentTile;
            var tile=tiles.get(key);recentTileKey=key;recentTile=tile;return tile;
        }
        private static Version find(Tile tile,long version){
            if(tile!=null)for(var candidate:tile.versions)if(candidate.input.version==version)return candidate;
            return null;
        }
        private boolean room(long extra,Tile protectedTile){
            while(extra>maxBytes-retainedBytes)if(!evictOldest(protectedTile))return false;
            return true;
        }
        private boolean evictOldest(Tile protectedTile){
            var iterator=tiles.entrySet().iterator();
            while(iterator.hasNext()){
                var tile=iterator.next().getValue();if(tile==protectedTile)continue;
                iterator.remove();retainedBytes-=tile.retainedBytes;
                if(tile==recentTile){recentTileKey=null;recentTile=null;}
                for(var version:tile.versions)removeVersionOnly(version);
                tileEvictions++;return true;
            }
            return false;
        }
        private void removeVersionOnly(Version version){
            version.active=false;sourceVersionEvictions++;
            var owner=version.pointOwner;
            if(--owner.activeVersions==0){
                points-=owner.points;pointEvictions+=owner.points;owner.points=0;
            }
        }
        /** Tile versions are sorted by logical input revision, not allocation ID. Caller holds lock. */
        private static boolean newestOwner(Tile tile,Version target){
            return tile!=null&&!tile.versions.isEmpty()&&tile.versions.getFirst().pointOwner==target.pointOwner;
        }
        /** The caller holds the lock and has already established that this point is absent. */
        private int insertPoint(Version target,int first,long hash,long xb,long zb,Version base){
            var owner=target.pointOwner;
            int slot=table.insert(first,hash,owner,xb,zb,base==null?null:base.pointOwner,newestSamplesOnly);
            var old=table.replacedOwner;
            if(old!=null&&old.activeVersions>0){old.points--;points--;pointEvictions++;}
            owner.points++;points++;
            return slot;
        }
        private double sample(Handle handle,double x,double z){
            if(!Double.isFinite(x)||!Double.isFinite(z))return 0;
            long xb=Double.doubleToRawLongBits(x),zb=Double.doubleToRawLongBits(z);
            Version target=null,base=null;Transition change=null;Evaluation result;
            int first=0;long hash=0,stamp=0;
            synchronized(lock){
                queries++;var tile=touchTile(handle.key);
                Version newerBase=null,olderBase=null;
                if(tile!=null)for(int i=0;i<tile.versions.size();i++){
                    var candidate=tile.versions.get(i);
                    if(target==null){
                        if(candidate.id==handle.versionId)target=candidate;
                    }else if(candidate.pointOwner!=target.pointOwner){
                        // The tile lists logical sources in descending revision order.
                        if(newerBase==null)newerBase=candidate;
                        else if(candidate.pointOwner!=newerBase.pointOwner){olderBase=candidate;break;}
                    }
                }
                int slot=-1;
                if(target==null)staleQueries++;
                else{
                    hash=PointTable.hash(tile.pointSalt,xb,zb);first=table.first(hash);
                    var newer=newerBase==null?null:newerBase.pointOwner;
                    var older=olderBase==null?null:olderBase.pointOwner;
                    slot=table.find(first,hash,xb,zb,target.pointOwner,newer,older);
                    if(slot>=0){
                        var owner=table.owners[slot];
                        if(owner==target.pointOwner){pointHits++;return table.signed(slot);}
                        base=owner==newer?newerBase:olderBase;
                        change=target.transitionFrom(base.id);
                    }
                    stamp=table.stamp(first);
                }
                pointMisses++;
                if(change!=null){
                    var grid=target.input.grid;
                    int bin=grid==null?-1:grid.bin(x,z);
                    if(change.unchangedAt(bin)){
                        // An occupied set may promote in place. Insertion changes the
                        // tag and key only, so a source in that slot still has its values.
                        int destination=slot;
                        if(!newestSamplesOnly||newestOwner(tile,target)){
                            destination=insertPoint(target,first,hash,xb,zb,base);
                            table.copyValues(slot,destination);
                        }
                        reusedVersionPoints++;
                        if(bin>=0)spatialQueries++;
                        if(change.added.length!=0)updatedPoints++;
                        return table.signed(destination);
                    }
                }
                result=EVALUATION.get();result.reset();
                if(base!=null)table.copy(slot,result);
            }
            boolean learnedTransition=base!=null&&change==null;
            if(target==null){
                result.full(handle.input,x,z);
            }else{
                // An older source may have bound after this target.
                if(learnedTransition)change=Transition.between(target,base);
                result.update(target,base,change,x,z);
            }
            double value=result.signed();
            synchronized(lock){
                fullHistoryQueries+=result.fullQueries;deltaKernelEvaluations+=result.deltaEvaluations;
                fullKernelEvaluations+=result.fullEvaluations;depressionRebuilds+=result.dRebuilds;
                coverageRebuilds+=result.cRebuilds;rimRebuilds+=result.rRebuilds;updatedPoints+=result.updated;
                fullCandidateVisits+=result.fullCandidates;deltaCandidateVisits+=result.deltaCandidates;
                spatialQueries+=result.spatial;
                if(target!=null&&target.active&&(!newestSamplesOnly||newestOwner(touchTile(handle.key),target))){
                    if(learnedTransition&&base.active)target.rememberTransition(base.id,change);
                    // Only another insertion into this set can invalidate the
                    // observed miss. World reset also deactivates this target.
                    if(table.unchanged(first,stamp)||table.find(first,hash,xb,zb,target.pointOwner,null,null)<0)
                        table.write(insertPoint(target,first,hash,xb,zb,base),result);
                }
            }
            return value;
        }
    }
    /** Cache-lock-owned tag. Stale rows retain only this small object. */
    private static final class PointOwner {
        int activeVersions,points;
    }
    private static final class Tile {
        final ArrayList<Version> versions=new ArrayList<>(3);
        final long pointSalt;
        long retainedBytes;
        Tile(long id){pointSalt=id*0x9e3779b97f4a7c15L;}
    }
    private record Kernel(long id,Entry entry,OwnerMasks masks) {}
    private static final class Version {
        final long id;
        final Input input;
        final Map<KernelKey,Kernel> byKey;
        final long[] kernelIds;
        final Kernel[] depressionOrder,rimOrder;
        // At most two older versions of this tile can be active. Slots are
        // initialized before publication and subsequently accessed under Cache.lock.
        final long[] transitionSources=new long[Cache.VERSIONS-1];
        final Transition[] transitions=new Transition[transitionSources.length];
        final PointOwner pointOwner;
        boolean active;
        Version(long id,Input input,Version[] previous,AtomicLong ids){
            this.id=id;this.input=input;
            Version sameField=null;
            if(previous!=null)for(var version:previous){
                if(input.byKey==version.input.byKey||input.byKey.keySet().equals(version.input.byKey.keySet())){
                    sameField=version;break;
                }
            }
            pointOwner=sameField==null?new PointOwner():sameField.pointOwner;
            if(sameField!=null&&input.byKey==sameField.input.byKey&&input.grid==sameField.input.grid){
                byKey=sameField.byKey;kernelIds=sameField.kernelIds;
                depressionOrder=sameField.depressionOrder;rimOrder=sameField.rimOrder;
                return;
            }
            var keys=new HashMap<KernelKey,Kernel>();
            kernelIds=new long[input.size()];
            depressionOrder=new Kernel[input.depressionOrder.length];rimOrder=new Kernel[input.rimOrder.length];
            for(int i=0;i<depressionOrder.length;i++){
                var entry=input.depressionOrder[i];
                Kernel existing=null;
                if(previous!=null)for(var version:previous){existing=version.byKey.get(entry.key());if(existing!=null)break;}
                var masks=input.grid==null?OwnerMasks.NONE:input.grid.masks.get(entry.key());
                var kernel=new Kernel(existing==null?ids.incrementAndGet():existing.id,entry,masks);
                keys.put(entry.key(),kernel);kernelIds[i]=kernel.id;depressionOrder[i]=kernel;
            }
            Arrays.sort(kernelIds);byKey=Map.copyOf(keys);
            for(int i=0;i<rimOrder.length;i++)rimOrder[i]=byKey.get(input.rimOrder[i].key());
        }
        boolean hasKernel(Kernel kernel){
            var own=byKey.get(kernel.entry.key());
            return own!=null&&own.id==kernel.id;
        }
        boolean retainsWinner(long winner){
            return winner==0||Arrays.binarySearch(kernelIds,winner)>=0;
        }
        Transition transitionFrom(long source){
            for(int i=0;i<transitionSources.length;i++)if(transitionSources[i]==source)return transitions[i];
            return null;
        }
        void rememberTransition(long source,Transition transition){
            int empty=-1;
            for(int i=0;i<transitionSources.length;i++){
                if(transitionSources[i]==source)return;
                if(transitionSources[i]==0)empty=i;
            }
            if(empty>=0){
                transitionSources[empty]=source;
                transitions[empty]=transition;
            }
        }
        void forgetTransition(long source){
            for(int i=0;i<transitionSources.length;i++)if(transitionSources[i]==source){
                transitionSources[i]=0;
                transitions[i]=null;
                return;
            }
        }
    }
    private static final class Transition {
        final Kernel[] added;
        final boolean sameKernels,removesKernels;
        final long changedBins;
        private Transition(Kernel[] added,boolean sameKernels,boolean removesKernels,long changedBins){
            this.added=added;this.sameKernels=sameKernels;this.removesKernels=removesKernels;this.changedBins=changedBins;
        }
        static Transition between(Version target,Version source){
            var additions=new ArrayList<Kernel>();
            for(var kernel:target.depressionOrder)if(!source.hasKernel(kernel))additions.add(kernel);
            var added=additions.toArray(Kernel[]::new);
            boolean removesKernels=source.kernelIds.length>target.kernelIds.length-added.length;
            boolean sameKernels=added.length==0&&!removesKernels;
            long changedBins=-1L;
            var targetGrid=target.input.grid;var sourceGrid=source.input.grid;
            if(!sameKernels&&targetGrid!=null&&sourceGrid!=null&&targetGrid.origin.equals(sourceGrid.origin)){
                changedBins=0;
                // The masks cover closed bins. A changed kernel cannot contribute
                // outside its mask, including on shared bin and owner edges.
                for(var kernel:added)changedBins|=kernel.masks.influence;
                if(removesKernels)for(var kernel:source.depressionOrder)if(!target.hasKernel(kernel))changedBins|=kernel.masks.influence;
            }
            return new Transition(added,sameKernels,removesKernels,changedBins);
        }
        boolean unchangedAt(int bin){return sameKernels||bin>=0&&(changedBins&(1L<<bin))==0;}
    }
    private static final class Evaluation {
        static final int D=1,C=2,DEPRESSION=D|C;
        private static final Kernel[] NONE=new Kernel[0];
        double d,c,r;long dWinner,cWinner,rWinner;
        final double[] contributions=new double[2];
        long fullQueries,deltaEvaluations,fullEvaluations,dRebuilds,cRebuilds,rRebuilds,updated;
        long fullCandidates,deltaCandidates,spatial;
        void reset(){
            d=c=r=0;dWinner=cWinner=rWinner=0;
            fullQueries=deltaEvaluations=fullEvaluations=dRebuilds=cRebuilds=rRebuilds=updated=0;
            fullCandidates=deltaCandidates=spatial=0;
        }
        double signed(){return signedField(d,c,r);}
        private int spatialBin(Input input,double x,double z){
            int bin=input.grid==null?-1:input.grid.bin(x,z);
            if(bin>=0)spatial++;
            return bin;
        }
        private static int count(int[] ordinals,int fullCount){return ordinals==null?fullCount:ordinals.length;}
        private static int ordinal(int[] ordinals,int i){return ordinals==null?i:ordinals[i];}
        void full(Input input,double x,double z){
            if(input.size()==1){single(input,0,x,z);return;}
            fresh(input,null,x,z);
        }
        /** A cold point needs both maxima. Share each outline scan across cavity and bank,
         * preserving depth order and exact independent D/C/R winners for later revisions. */
        private void fresh(Input input,Version version,double x,double z){
            fullQueries=dRebuilds=cRebuilds=1;
            int bin=spatialBin(input,x,z);
            int[] ordinals=bin<0?null:input.grid.influenceByDepth[bin];
            for(int i=0;i<count(ordinals,input.depressionOrder.length);i++){
                int ordinal=ordinal(ordinals,i);
                var entry=input.depressionOrder[ordinal];var prepared=entry.prepared();
                double depth=prepared.maxDepressionMillimeters();fullCandidates++;
                if(c>=1&&d>=depth)break;
                if(!(depth>0))continue;
                double threshold=d;
                if(c<1){
                    double bound=c*depth;
                    double previous=bound>0?Double.longBitsToDouble(Double.doubleToRawLongBits(bound)-1):0;
                    threshold=Math.min(threshold,previous);
                }
                prepared.sampleContributions(x,z,threshold,c>=1?Double.POSITIVE_INFINITY:r,contributions);
                fullEvaluations++;
                long id=version==null?0:version.depressionOrder[ordinal].id;
                double value=contributions[0],coverage=Math.clamp(value/depth,0,1);
                if(value>d){d=value;dWinner=id;}
                if(coverage>c){c=coverage;cWinner=id;}
                if(contributions[1]>r){r=contributions[1];rWinner=id;}
            }
            if(c>=1){r=Double.NaN;rWinner=0;}else rRebuilds=1;
        }
        void update(Version target,Version base,Transition change,double x,double z){
            if(base==null){
                if(target.input.size()==1)single(target.input,target.depressionOrder[0].id,x,z);
                else fresh(target.input,target,x,z);
                return;
            }
            int bin=spatialBin(target.input,x,z);
            long bit=bin<0?0:1L<<bin;
            Kernel[] added=base==null?NONE:change.added;
            boolean removes=base!=null&&change.removesKernels;
            int dirty=base==null?DEPRESSION:0;
            if(removes){
                if(!target.retainsWinner(dWinner))dirty|=D;
                if(!target.retainsWinner(cWinner))dirty|=C;
            }
            int clean=DEPRESSION&~dirty;
            if(clean!=0)for(var kernel:added){
                if(bit!=0&&(kernel.masks.depression&bit)==0)continue;
                deltaCandidates++;depression(kernel.entry,kernel.id,clean,x,z,true);
            }
            if(dirty!=0){
                fullQueries=1;
                if((dirty&D)!=0){d=0;dWinner=0;dRebuilds++;}
                if((dirty&C)!=0){c=0;cWinner=0;cRebuilds++;}
                int[] depression=bin<0?null:target.input.grid.depression[bin];
                for(int i=0;i<count(depression,target.depressionOrder.length);i++){
                    if((dirty&D)==0&&c>=1)break;
                    var kernel=target.depressionOrder[ordinal(depression,i)];fullCandidates++;
                    if(c>=1&&d>=kernel.entry.prepared().maxDepressionMillimeters())break;
                    depression(kernel.entry,kernel.id,dirty,x,z,false);
                }
            }
            boolean covered=c>=1;
            boolean rebuildRim=!covered&&(base==null||Double.isNaN(r)||removes&&!target.retainsWinner(rWinner));
            if(base!=null&&(added.length!=0||dirty!=0||rebuildRim))updated++;
            if(covered){
                // Coverage hides every bank. Keep this marker in the cached point;
                // a later source that exposes it rebuilds from its own surviving kernels.
                r=Double.NaN;rWinner=0;
            }else if(rebuildRim){
                fullQueries=1;rRebuilds++;r=0;rWinner=0;
                int[] rim=bin<0?null:target.input.grid.rim[bin];
                for(int i=0;i<count(rim,target.rimOrder.length);i++){
                    var kernel=target.rimOrder[ordinal(rim,i)];fullCandidates++;
                    if(r>=kernel.entry.prepared().maxRimMillimeters())break;
                    rim(kernel.entry,kernel.id,x,z,false);
                }
            }else for(var kernel:added){
                if(bit!=0&&(kernel.masks.influence&bit)==0)continue;
                deltaCandidates++;rim(kernel.entry,kernel.id,x,z,true);
            }
        }
        /** One sole has no competing winners: cavity and bank can share an exact edge scan.
         * Multi-contact fields keep their sorted, separately pruned D/C/R traversals. */
        private void single(Input input,long id,double x,double z){
            fullQueries=dRebuilds=cRebuilds=1;
            int bin=spatialBin(input,x,z);
            if(bin>=0&&input.grid.rim[bin].length==0){rRebuilds=1;return;}
            var prepared=input.depressionOrder[0].prepared();fullCandidates++;
            double depth=prepared.maxDepressionMillimeters();
            if(!(depth>0)){rRebuilds=1;return;}
            prepared.sampleContributions(x,z,0,0,contributions);fullEvaluations++;
            double value=contributions[0],coverage=Math.clamp(value/depth,0,1);
            if(value>d){d=value;dWinner=id;}
            if(coverage>c){c=coverage;cWinner=id;}
            if(c>=1){r=Double.NaN;rWinner=0;}
            else{if(contributions[1]>r){r=contributions[1];rWinner=id;}rRebuilds=1;}
        }
        private void depression(Entry entry,long id,int components,double x,double z,boolean delta){
            var prepared=entry.prepared();double depth=prepared.maxDepressionMillimeters();if(!(depth>0))return;
            boolean doD=(components&D)!=0,doC=(components&C)!=0&&c<1;
            if(!doD&&!doC)return;
            double threshold=doD?d:Double.POSITIVE_INFINITY;
            if(doC){
                double bound=c*depth;
                // Coverage is in [0,1) and depth is finite positive. The bit predecessor
                // gives max(0,nextDown(bound)) exactly, including subnormal products.
                double previous=bound>0?Double.longBitsToDouble(Double.doubleToRawLongBits(bound)-1):0;
                threshold=Math.min(threshold,previous);
            }
            if(threshold>=depth)return;
            double value=prepared.depressionMillimetersForMax(x,z,threshold);
            if(delta)deltaEvaluations++;else fullEvaluations++;
            if(doD&&value>d){d=value;dWinner=id;}
            if(doC){double coverage=Math.clamp(value/depth,0,1);if(coverage>c){c=coverage;cWinner=id;}}
        }
        private void rim(Entry entry,long id,double x,double z,boolean delta){
            var prepared=entry.prepared();if(prepared.maxRimMillimeters()<=r)return;
            double value=prepared.rimMillimeters(x,z);
            if(delta)deltaEvaluations++;else fullEvaluations++;
            if(value>r){r=value;rWinner=id;}
        }
    }
    private static double signedField(double d,double c,double r){return c>=1?d:d==0?-r:d-r*(1-c);}
    /** Exact-bit cache. Owner tags and primitive winner IDs never retain old Inputs or kernels. */
    private static final class PointTable {
        static final int WAYS=8;
        final PointOwner[] owners;
        final long[] fingerprints,x,z,dWinner,cWinner,rWinner,changes;
        final double[] d,c,r;final byte[] next;
        PointOwner replacedOwner;
        PointTable(int capacity){
            owners=new PointOwner[capacity];fingerprints=new long[capacity];x=new long[capacity];z=new long[capacity];
            dWinner=new long[capacity];cWinner=new long[capacity];rWinner=new long[capacity];
            d=new double[capacity];c=new double[capacity];r=new double[capacity];
            changes=new long[capacity/WAYS];next=new byte[capacity/WAYS];
        }
        int capacity(){return owners.length;}
        static long bytes(int capacity){return capacity==0?0:512L+128L*capacity;}
        void clear(){Arrays.fill(owners,null);Arrays.fill(next,(byte)0);Arrays.fill(changes,0);replacedOwner=null;}
        static long hash(long tileSalt,long xb,long zb){
            long hash=xb^Long.rotateLeft(zb,27)^tileSalt;
            hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;hash=(hash^(hash>>>27))*0x94d049bb133111ebL;
            return hash^(hash>>>31);
        }
        int first(long hash){return ((int)hash&(next.length-1))*WAYS;}
        long stamp(int first){return changes[first/WAYS];}
        boolean unchanged(int first,long stamp){return stamp!=Long.MAX_VALUE&&changes[first/WAYS]==stamp;}
        int find(int first,long hash,long xb,long zb,PointOwner primary,PointOwner newer,PointOwner older){
            int recent=-1,previous=-1;
            for(int i=first;i<first+WAYS;i++){
                if(fingerprints[i]!=hash)continue;
                var owner=owners[i];
                if(owner==null||x[i]!=xb||z[i]!=zb)continue;
                if(owner==primary)return i;
                if(owner==newer)recent=i;else if(owner==older)previous=i;
            }
            return recent>=0?recent:previous;
        }
        int insert(int first,long hash,PointOwner owner,long xb,long zb,PointOwner base,boolean preferBase){
            int slot=-1,samePoint=-1,set=first/WAYS;
            for(int i=first;i<first+WAYS;i++){
                var current=owners[i];
                if(current==null||current.activeVersions==0){
                    if(slot<0)slot=i;
                    if(!preferBase)break;
                }else if(current==base&&fingerprints[i]==hash&&x[i]==xb&&z[i]==zb)samePoint=i;
            }
            if(samePoint>=0&&(preferBase||slot<0))slot=samePoint;
            if(slot<0){slot=first+(next[set]&(WAYS-1));next[set]=(byte)((next[set]+1)&(WAYS-1));}
            replacedOwner=owners[slot];owners[slot]=owner;fingerprints[slot]=hash;x[slot]=xb;z[slot]=zb;
            if(changes[set]!=Long.MAX_VALUE)changes[set]++;
            return slot;
        }
        void copy(int slot,Evaluation value){
            value.d=d[slot];value.c=c[slot];value.r=r[slot];
            value.dWinner=dWinner[slot];value.cWinner=cWinner[slot];value.rWinner=rWinner[slot];
        }
        void copyValues(int source,int destination){
            if(source==destination)return;
            d[destination]=d[source];c[destination]=c[source];r[destination]=r[source];
            dWinner[destination]=dWinner[source];cWinner[destination]=cWinner[source];rWinner[destination]=rWinner[source];
        }
        void write(int slot,Evaluation value){
            d[slot]=value.d;c[slot]=value.c;r[slot]=value.r;
            dWinner[slot]=value.dWinner;cWinner[slot]=value.cWinner;rWinner[slot]=value.rWinner;
        }
        double signed(int slot){return signedField(d[slot],c[slot],r[slot]);}
    }
}
