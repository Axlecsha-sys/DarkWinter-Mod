// Dynamic authored snow caps: immutable per-chunk meshes, fine displacement and genuine sole compaction.
package com.winterkore.engine.surface.client;

import com.winterkore.engine.surface.SnowSurface;
import com.winterkore.engine.surface.geometry.SnowContactRelief;
import com.winterkore.engine.surface.geometry.SnowFieldEnvelope;
import com.winterkore.engine.surface.geometry.ConformingSnowMesh;
import com.winterkore.engine.surface.geometry.SnowSurfaceShading;
import com.winterkore.engine.surface.geometry.SoleProfiles;
import com.winterkore.engine.surface.geometry.SnowTerrainGeometry;
import com.winterkore.engine.surface.geometry.SnowFootContactPolicy;
import com.winterkore.engine.surface.host.PlayerSnowFootprints;
import com.winterkore.engine.surface.host.SnowHostGeometry;
import com.winterkore.engine.surface.host.SnowSurfacePayload;
import com.winterkore.engine.surface.host.SnowSurfaceChunkPayload;
import com.winterkore.engine.surface.host.SnowSurfaceRuntime;
import com.dark.winter.visuals.VisualRenderer;
import com.dark.winter.visuals.ShadowCaptureCache;
import com.winterkore.engine.graphics.lighting.WkeSnowLighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Native block caps are omitted only after a complete replacement mesh exists. Ownership lasts until unload. */
public final class SnowSurfaceClient {
    private static final int BYTES=SnowMeshBuffer.STRIDE,MAX_OWNED_CHUNKS=256;
    private static final double MESH_DELTA_STEP_MM=.5,LOCAL_RELIEF_ERROR=.006;
    private static final Identifier TEXTURE=Identifier.fromNamespaceAndPath("darkwinter","block/test_ground_snow");
    private static final RenderStateDataKey<Frame> FRAME=RenderStateDataKey.create(()->"WKE dynamic authored snow");
    private static final VertexFormat FORMAT=VertexFormat.builder(0).addAttribute("Position",GpuFormat.RGBA32_FLOAT)
            .addAttribute("UVLight",GpuFormat.RGBA32_FLOAT).addAttribute("NormalDepth",GpuFormat.RGBA32_FLOAT).build();
    private static final RenderPipeline PIPELINE=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
            .withLocation(id("pipeline/snow_contacts")).withVertexShader(id("core/snow_contacts")).withFragmentShader(id("core/snow_contacts"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SnowView",UniformType.UNIFORM_BUFFER).build())
            .withVertexBinding(0,FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT).build());
    private static final RenderPipeline SHADOW_PIPELINE=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
            .withLocation(id("pipeline/snow_shadow")).withVertexShader(id("core/snow_shadow")).withFragmentShader(id("core/snow_shadow"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SnowView",UniformType.UNIFORM_BUFFER).build())
            .withVertexBinding(0,FORMAT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,0)).build());
    public record DebugVertex(double x,double y,double z,double u,double v,double normalX,double normalY,double normalZ,
            double depthMillimeters,double blockLight,double skyLight,int sharedWallSide) {
        public DebugVertex(double x,double y,double z,double u,double v,double nx,double ny,double nz,double depth,double blockLight,double skyLight){
            this(x,y,z,u,v,nx,ny,nz,depth,blockLight,skyLight,-1);
        }
        public DebugVertex(double x,double y,double z,double u,double v,double nx,double ny,double nz,double depth){this(x,y,z,u,v,nx,ny,nz,depth,-1,-1);}
        public DebugVertex(double x,double y,double z,double u,double v,double nx,double ny,double nz){this(x,y,z,u,v,nx,ny,nz,0,-1,-1);}
    }
    public record DebugTriangle(DebugVertex a,DebugVertex b,DebugVertex c) {}
    public record DebugCell(boolean owned,int layers,int mask,int crust,List<Double> corners,
            List<DebugTriangle> triangles,List<SnowSurfacePayload.Mark> contacts) {}
    public record Metrics(int marks,int drawn,long uploads,long frames,boolean gpuAllocated,
            int ownedColumns,long generatedTriangles,long suppressedCaps,long builds,
            long snapshotNanos,long workerNanos,long discardedBuilds,boolean buildPending) {}
    private record CellData(SnowSurfacePayload.Column column,SnowHostGeometry.Cell geometry,List<SnowSurfacePayload.Mark> contacts,
            SnowContactRelief.Prepared[] pressure,double[] fineCorners,int firstVertex,int vertexCount,int blockLight,int skyLight,int skirtSides,
            boolean approximate,double approximationErrorMillimeters,boolean fineBudgetLimited,SnowMeshRange range) {
        CellData withRange(int logicalFirst,SnowMeshRange packed){
            return new CellData(column,geometry,contacts,pressure,fineCorners,logicalFirst,vertexCount,blockLight,skyLight,skirtSides,
                    approximate,approximationErrorMillimeters,fineBudgetLimited,packed);
        }
    }
    private record MarkKey(double x,float y,double z,float yaw,float width,float length,boolean vehicle,int soleProfile,int ownerY) {
        static MarkKey of(SnowSurfacePayload.Mark m){return new MarkKey(m.x(),m.y(),m.z(),m.yaw(),m.width(),m.length(),m.vehicle(),m.soleProfile(),m.ownerY());}
        boolean matches(SnowSurfacePayload.Mark mark){
            return Double.compare(x,mark.x())==0&&Float.compare(y,mark.y())==0&&Double.compare(z,mark.z())==0
                    &&Float.compare(yaw,mark.yaw())==0&&Float.compare(width,mark.width())==0&&Float.compare(length,mark.length())==0
                    &&vehicle==mark.vehicle()&&soleProfile==mark.soleProfile()&&ownerY==mark.ownerY();
        }
    }
    private record DrawGroup(long section,int firstVertex,int vertexCount,int cells,SnowMeshBuffer source) {
        DrawGroup(long section,int firstVertex,int vertexCount,int cells){this(section,firstVertex,vertexCount,cells,null);}
    }
    private record Handoff(long epoch,Mesh before,Mesh after,Set<Long> nativeSections) {
        Handoff {nativeSections=Set.copyOf(nativeSections);}
        Handoff(long epoch,Mesh before,Mesh after){this(epoch,before,after,Set.of());}
    }
    private record Mesh(long key,long revision,BlockPos origin,Map<Long,CellData> cells,
            List<SnowSurfacePayload.Mark> contacts,ByteBuffer data,int vertices,List<DrawGroup> groups,AABB bounds,ConcurrentMap<Long,DebugCell> debugCells) {
        Mesh {
            SnowMeshBuffer packed=null;Map<Long,CellData> bound=null;
            for(var entry:cells.entrySet())if(entry.getValue().range==null){
                if(packed==null)packed=new SnowMeshBuffer(data);
                if(bound==null)bound=new LinkedHashMap<>(cells);
                var cell=entry.getValue();bound.put(entry.getKey(),cell.withRange(cell.firstVertex,
                        new SnowMeshRange(packed,cell.firstVertex,cell.vertexCount)));
            }
            if(bound!=null)cells=Map.copyOf(bound);
            groups=rangeGroups(cells);
        }
    }
    /** Order opaque cells by immutable source within a section. Copy-only background
     * packing can then collapse many tiles into one bind without changing geometry. */
    private static List<DrawGroup> rangeGroups(Map<Long,CellData> cells){
        var ordered=new ArrayList<>(cells.values());
        ordered.sort(Comparator.comparingLong((CellData c)->section(c.geometry.pos())).thenComparingInt(c->c.firstVertex));
        var groups=new ArrayList<DrawGroup>();int cursor=0;
        while(cursor<ordered.size()){
            long section=section(ordered.get(cursor).geometry.pos());var bySource=new IdentityHashMap<SnowMeshBuffer,List<CellData>>();
            var sourceOrder=new ArrayList<SnowMeshBuffer>();
            while(cursor<ordered.size()&&section(ordered.get(cursor).geometry.pos())==section){
                var cell=ordered.get(cursor++);if(cell.vertexCount==0)continue;var source=cell.range.source();
                var list=bySource.get(source);if(list==null){list=new ArrayList<>();bySource.put(source,list);sourceOrder.add(source);}list.add(cell);
            }
            for(var source:sourceOrder){
                var list=bySource.get(source);list.sort(Comparator.comparingInt(c->c.range.firstVertex()));
                for(var cell:list){var range=cell.range;
                    if(!groups.isEmpty()){var last=groups.getLast();
                        if(last.section==section&&last.source==source&&last.firstVertex+last.vertexCount==range.firstVertex()){
                            groups.set(groups.size()-1,new DrawGroup(section,last.firstVertex,last.vertexCount+range.vertexCount(),last.cells+1,source));continue;
                        }
                    }
                    groups.add(new DrawGroup(section,range.firstVertex(),range.vertexCount(),1,source));
                }
            }
        }
        return List.copyOf(groups);
    }
    private record Frame(long epoch,long id,List<Mesh> all,List<Mesh> meshes) {}
    private record ShadowDraw(Mesh mesh,DrawGroup group,GpuBuffer vertices,GpuBuffer view) {}
    private record ShadowPrepared(long epoch,long frame,List<ShadowDraw> draws,GpuBufferSlice transform,Consumer<RenderPass> bindPipeline) {}
    private record CellSnapshot(SnowSurfacePayload.Column column,SnowHostGeometry.Cell geometry,double[] corners,
            List<SnowSurfacePayload.Mark> contacts,int blockLight,int skyLight,int skirtSides) {}
    private record BuiltCell(CellSnapshot input,SnowTriangleBuffer triangles,double approximationErrorMillimeters,boolean fineBudgetLimited) {}
    private record BuildSnapshot(long epoch,long key,long geometryRevision,long sourceRevision,long renderRevision,BlockPos origin,
            List<CellSnapshot> cells,List<SnowSurfacePayload.Mark> authoritativeContacts,List<SnowSurfacePayload.Mark> contacts,Map<Long,CellSnapshot> lighting,Map<Long,FieldSignature> fieldContext) {}
    /** Main owns the handle until the worker really exits, including cancellation and world switches. */
    private record ColumnField(int minX,int minZ,it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap depths){
        double delta(BlockPos pos){
            int x=pos.getX()-minX,z=pos.getZ()-minZ;if(x<0||x>=50||z<0||z>=50)return Double.NaN;
            // The host corner solver distinguishes unobserved support from measured zero.
            return depths.get(pos.asLong());
        }
    }
    private record Ownership(long epoch,Set<Long> positions) {}
    private record BlockedFrame(long revision,long geometryRevision,String reason) {}
    private record BootstrapCohort(long epoch,long weatherTick,long geometryEpoch,
            Map<Long,SnowSurfaceChunkPayload.Chunk> frames,Map<Long,Map<Long,SnowSurfacePayload.Column>> geometryColumns,
            Map<Long,Long> geometryVersions,boolean nativeReady) {}
    private static final class FrameNotReady extends RuntimeException {
        FrameNotReady(String reason){super(reason,null,false,false);}
    }
    private static final class Preparation {
        final long epoch;final List<Long> keys;final Map<Long,SnowSurfaceChunkPayload.Chunk> frames;
        final Map<Long,Long> geometry;final ColumnField field;final boolean initial;final Map<Long,FieldSignature> fieldContext;
        final List<BuildSnapshot> snapshots=new ArrayList<>();boolean cancelled;
        FinePlan fine;
        Preparation(long epoch,List<Long> keys,Map<Long,SnowSurfaceChunkPayload.Chunk> frames,
                Map<Long,Long> geometry,ColumnField field,boolean initial){
            this.epoch=epoch;this.keys=keys;this.frames=frames;this.geometry=geometry;this.field=field;this.initial=initial;
            this.fieldContext=SnowSurfaceClient.fieldContext(frames);
        }
    }
    private static final int MAX_CACHED_CONTACT_SAMPLES=32768;
    private static final ThreadLocal<WorkerSampling> WORKER_SAMPLING=new ThreadLocal<>();
    private static final ThreadLocal<ReliefCache> RELIEF_CACHE=ThreadLocal.withInitial(ReliefCache::new);
    // Separate the two workloads without increasing the total 64 MiB cache budget.
    // Full terrain sweeps must not evict the recent samples used beneath a moving foot.
    // Local helpers finish before the next pressure snapshot: keep one current sample per coordinate.
    private static final SnowFieldEnvelope.Cache FIELD_ENVELOPES=new SnowFieldEnvelope.Cache(32,32L*1024*1024,true);
    private static final SnowFieldEnvelope.Cache BACKGROUND_FIELD_ENVELOPES=new SnowFieldEnvelope.Cache(32,32L*1024*1024);
    private record EnvelopeOwner(long owner,long rootTile) {}
    private record PreparedKey(double x,double z,float yaw,float width,float length,float depth,boolean vehicle,int profile) {}

    private record DraftContactKey(PreparedKey kernel,int ownerY) {
        static DraftContactKey of(SnowSurfacePayload.Mark mark){
            return new DraftContactKey(new PreparedKey(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),
                    mark.depthMillimeters(),mark.vehicle(),mark.soleProfile()),mark.ownerY());
        }
        com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo.Bounds bounds(){
            double radius=SnowContactRelief.influenceRadiusMeters(kernel.width,kernel.length,kernel.depth);
            return new com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo.Bounds(
                    kernel.x-radius,kernel.z-radius,kernel.x+radius,kernel.z+radius);
        }
    }
    private record DraftDependency(SnowHostGeometry.Cell geometry,double[] corners,Set<DraftContactKey> contacts) {}
    /** Only narrow immutable physical dependencies survive a contact build, never a BuildSnapshot. */
    private static final class DraftErrorState {
        static final long MAX_DEPENDENCY_BYTES=512L*1024;
        final com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo memo=
                new com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo(16384);
        final com.winterkore.engine.surface.geometry.SnowHeightMemo heights=
                new com.winterkore.engine.surface.geometry.SnowHeightMemo(65536);
        List<DraftDependency> dependencies=List.of();
        long dependencyBytes;boolean leased;
        private void clearSamples(){memo.clear();heights.clear();}
        boolean bind(List<CapFace> faces){
            long estimate=128,inputReferences=0;
            boolean oversized=faces.size()>MAX_DEPENDENCY_BYTES/512;
            var intern=new HashMap<DraftContactKey,DraftContactKey>();
            var next=new ArrayList<DraftDependency>(oversized?0:faces.size());
            for(var face:faces){
                if(oversized)break;
                // Bound traversal even if an invalid input repeats the same contact thousands of times.
                inputReferences+=face.contacts.size();
                estimate+=512L+96L*face.geometry.patches().size()+8L*face.input.corners.length;
                if(inputReferences>MAX_DEPENDENCY_BYTES/32||estimate>MAX_DEPENDENCY_BYTES){oversized=true;break;}
                var contacts=new HashSet<DraftContactKey>();
                for(var mark:face.contacts){
                    var key=DraftContactKey.of(mark);var previous=intern.putIfAbsent(key,key);
                    if(previous==null)estimate+=256;
                    if(contacts.add(previous==null?key:previous))estimate+=32;
                    if(estimate>MAX_DEPENDENCY_BYTES){oversized=true;break;}
                }
                if(oversized)break;
                next.add(new DraftDependency(face.geometry,face.input.corners.clone(),Set.copyOf(contacts)));
            }
            if(oversized){clearSamples();dependencies=List.of();dependencyBytes=0;return false;}
            boolean sameSupport=dependencies.size()==next.size(),cornersChanged=false,featurePolicyChanged=false;
            if(sameSupport)for(int i=0;i<next.size();i++){
                var old=dependencies.get(i);var fresh=next.get(i);
                // Face IDs and support membership must retain their exact meaning.
                if(!old.geometry.equals(fresh.geometry)){sameSupport=false;break;}
                cornersChanged|=!Arrays.equals(old.corners,fresh.corners);
                featurePolicyChanged|=old.contacts.isEmpty()!=fresh.contacts.isEmpty();
            }
            if(!sameSupport)clearSamples();
            else{
                // Weather and material-probe policy change triangle errors, not signed pressure
                // outside changed kernel supports. Compare every face, including empty halo transitions.
                if(cornersChanged||featurePolicyChanged)memo.clear();
                var changed=new HashSet<DraftContactKey>();
                for(int i=0;i<next.size();i++){
                    var old=dependencies.get(i).contacts;var fresh=next.get(i).contacts;
                    for(var key:old)if(!fresh.contains(key))changed.add(key);
                    for(var key:fresh)if(!old.contains(key))changed.add(key);
                }
                // Large contact revisions are cheaper to discard than to test against every cached triangle.
                if(changed.size()>16)clearSamples();
                else if(!changed.isEmpty()){
                    var bounds=new ArrayList<com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo.Bounds>(changed.size());
                    for(var key:changed)bounds.add(key.bounds());
                    memo.invalidate(bounds);heights.invalidate(bounds);
                }
            }
            dependencies=List.copyOf(next);dependencyBytes=estimate;return true;
        }
        long retainedBytes(){return memo.retainedBytes()+heights.retainedBytes()+dependencyBytes+4096;}
    }
    /** At most twelve exclusive owners, including leased entries: below 56 MiB with metadata. */
    private static final class DraftErrorMemos {
        static final int MAX_OWNERS=12;
        final LinkedHashMap<Long,DraftErrorState> owners=new LinkedHashMap<>(16,.75f,true);
        long retiredHits,retiredMisses,retiredInvalidations,retiredEvictions;
        synchronized DraftErrorState acquire(long owner){
            var state=owners.get(owner);
            if(state!=null){if(state.leased)return null;state.leased=true;return state;}
            if(owners.size()>=MAX_OWNERS){
                boolean removed=false;
                for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
                    var entry=iterator.next();if(entry.getValue().leased)continue;
                    retire(entry.getValue());iterator.remove();removed=true;break;
                }
                if(!removed)return null;
            }
            state=new DraftErrorState();state.leased=true;owners.put(owner,state);return state;
        }
        synchronized void release(DraftErrorState state){state.leased=false;}
        private void retire(DraftErrorState state){
            retiredHits+=state.memo.hits();retiredMisses+=state.memo.misses();
            retiredInvalidations+=state.memo.invalidations();retiredEvictions+=state.memo.evictions();
        }
        synchronized void unload(long chunk){
            for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
                var entry=iterator.next();
                if(chunkKey(BlockPos.of(entry.getKey()))==chunk&&!entry.getValue().leased){retire(entry.getValue());iterator.remove();}
            }
        }
        synchronized void clear(){
            owners.clear();retiredHits=retiredMisses=retiredInvalidations=retiredEvictions=0;
        }
        synchronized void metrics(Map<String,Object> out){
            long hits=retiredHits,misses=retiredMisses,invalidations=retiredInvalidations,evictions=retiredEvictions,bytes=0,entries=0;
            for(var state:owners.values()){
                hits+=state.memo.hits();misses+=state.memo.misses();invalidations+=state.memo.invalidations();
                evictions+=state.memo.evictions();bytes+=state.retainedBytes();entries+=state.memo.entries();
            }
            out.put("triangleErrorMemoHits",hits);out.put("triangleErrorMemoMisses",misses);
            out.put("triangleErrorMemoInvalidations",invalidations);out.put("triangleErrorMemoEvictions",evictions);
            out.put("triangleErrorMemoRetainedBytes",bytes);out.put("triangleErrorMemoOwners",owners.size());out.put("triangleErrorMemoEntries",entries);
            long heightHits=0,heightMisses=0,heightEntries=0;
            for(var state:owners.values()){heightHits+=state.heights.hits();heightMisses+=state.heights.misses();heightEntries+=state.heights.entries();}
            out.put("activeHeightMemoHits",heightHits);out.put("activeHeightMemoMisses",heightMisses);out.put("activeHeightMemoEntries",heightEntries);
        }
    }
    private static final DraftErrorMemos DRAFT_ERROR_MEMOS=new DraftErrorMemos();

    /** A horizon entry depends on its reference origin and the complete sampled physical halo. */
    private static final class HorizonMemoState {
        static final long MAX_DEPENDENCY_BYTES=256L*1024;
        final com.winterkore.engine.surface.geometry.SnowHorizonSampleCache cache;
        List<DraftDependency> dependencies=List.of();
        long dependencyBytes;boolean leased;
        HorizonMemoState(int x,int z){cache=new com.winterkore.engine.surface.geometry.SnowHorizonSampleCache(x,z);}
        private boolean bypass(){cache.clear();dependencies=List.of();dependencyBytes=0;return false;}
        boolean bind(CellSnapshot input,Map<Long,CellSnapshot> halo){
            var sources=new ArrayList<CellSnapshot>();sources.add(input);
            var position=input.geometry.pos();
            // The reference input and the lighting map are distinct roles. Retain both
            // even if an unusual fixture supplies different snapshots for the same owner.
            var nearby=new ArrayList<CellSnapshot>();
            for(var cell:halo.values()){
                var pos=cell.geometry.pos();
                if(Math.abs((long)pos.getX()-position.getX())<=1&&Math.abs((long)pos.getZ()-position.getZ())<=1)nearby.add(cell);
            }
            nearby.sort(Comparator.comparingLong(cell->cell.geometry.pos().asLong()));sources.addAll(nearby);
            var intern=new HashMap<DraftContactKey,DraftContactKey>();
            var next=new ArrayList<DraftDependency>(sources.size());long estimate=1024;
            for(var cell:sources){
                // Bound duplicate input traversal as well as retained unique keys.
                if(cell.contacts.size()>MAX_DEPENDENCY_BYTES/32)return bypass();
                estimate+=512L+96L*cell.geometry.patches().size()+8L*cell.corners.length;
                if(estimate>MAX_DEPENDENCY_BYTES)return bypass();
                var contacts=new HashSet<DraftContactKey>();
                for(var mark:cell.contacts){
                    var key=DraftContactKey.of(mark);var previous=intern.putIfAbsent(key,key);
                    if(previous==null){
                        estimate+=256;
                        if(estimate>MAX_DEPENDENCY_BYTES)return bypass();
                    }
                    if(contacts.add(previous==null?key:previous)){
                        estimate+=32;
                        if(estimate>MAX_DEPENDENCY_BYTES)return bypass();
                    }
                }
                next.add(new DraftDependency(cell.geometry,cell.corners.clone(),Set.copyOf(contacts)));
            }
            boolean sameSupport=dependencies.size()==next.size();
            if(sameSupport)for(int i=0;i<next.size();i++){
                var old=dependencies.get(i);var fresh=next.get(i);
                if(!old.geometry.equals(fresh.geometry)||!Arrays.equals(old.corners,fresh.corners)){sameSupport=false;break;}
            }
            if(!sameSupport)cache.clear();
            else{
                var changed=new HashSet<DraftContactKey>();
                for(int i=0;i<next.size();i++){
                    var old=dependencies.get(i).contacts;var fresh=next.get(i).contacts;
                    for(var key:old)if(!fresh.contains(key))changed.add(key);
                    for(var key:fresh)if(!old.contains(key))changed.add(key);
                }
                if(changed.size()>64)cache.clear();
                else if(!changed.isEmpty()){
                    var bounds=new ArrayList<com.winterkore.engine.surface.geometry.SnowTriangleErrorMemo.Bounds>(changed.size());
                    for(var key:changed)bounds.add(key.bounds());cache.invalidate(bounds);
                }
            }
            dependencies=List.copyOf(next);dependencyBytes=estimate;return true;
        }
        long retainedBytes(){return cache.retainedBytes()+dependencyBytes+4096;}
    }
    /** Exclusive per-owner leases avoid point locks, with less than 24 MiB including metadata. */
    private static final class HorizonMemos {
        static final int MAX_OWNERS=12;
        final LinkedHashMap<Long,HorizonMemoState> owners=new LinkedHashMap<>(16,.75f,true);
        long retiredHits,retiredMisses,retiredInvalidations,ownerEvictions;
        synchronized HorizonMemoState acquire(long owner,int x,int z){
            var state=owners.get(owner);
            if(state!=null){if(state.leased)return null;state.leased=true;return state;}
            if(owners.size()>=MAX_OWNERS){
                boolean removed=false;
                for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
                    var entry=iterator.next();if(entry.getValue().leased)continue;
                    retire(entry.getValue());iterator.remove();ownerEvictions++;removed=true;break;
                }
                if(!removed)return null;
            }
            state=new HorizonMemoState(x,z);state.leased=true;owners.put(owner,state);return state;
        }
        synchronized void release(HorizonMemoState state){state.leased=false;}
        private void retire(HorizonMemoState state){
            retiredHits+=state.cache.hits();retiredMisses+=state.cache.misses();retiredInvalidations+=state.cache.invalidations();
        }
        synchronized void unload(long chunk){
            for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
                var entry=iterator.next();
                if(chunkKey(BlockPos.of(entry.getKey()))==chunk&&!entry.getValue().leased){retire(entry.getValue());iterator.remove();}
            }
        }
        synchronized void clear(){owners.clear();retiredHits=retiredMisses=retiredInvalidations=ownerEvictions=0;}
        synchronized void metrics(Map<String,Object> out){
            long hits=retiredHits,misses=retiredMisses,invalidations=retiredInvalidations,bytes=0,entries=0;
            for(var state:owners.values()){
                hits+=state.cache.hits();misses+=state.cache.misses();invalidations+=state.cache.invalidations();
                bytes+=state.retainedBytes();entries+=state.cache.entries();
            }
            out.put("aoHorizonMemoHits",hits);out.put("aoHorizonMemoMisses",misses);out.put("aoHorizonMemoInvalidations",invalidations);
            out.put("aoHorizonMemoRetainedBytes",bytes);out.put("aoHorizonMemoOwners",owners.size());
            out.put("aoHorizonMemoEntries",entries);out.put("aoHorizonMemoOwnerEvictions",ownerEvictions);
        }
    }
    private static final HorizonMemos AO_HORIZON_MEMOS=new HorizonMemos();


    private static final class PreparedCache extends LinkedHashMap<PreparedKey,SnowContactRelief.Prepared> {
        long hits,misses;
        PreparedCache(){super(256,.75f,true);}
        @Override protected boolean removeEldestEntry(Map.Entry<PreparedKey,SnowContactRelief.Prepared> entry){return size()>2048;}
    }
    private static final ThreadLocal<PreparedCache> PREPARED_CACHE=ThreadLocal.withInitial(PreparedCache::new);
    private static final ThreadLocal<com.winterkore.engine.surface.geometry.SnowHorizonWorkCache> HORIZON_WORK_CACHE=
            ThreadLocal.withInitial(()->new com.winterkore.engine.surface.geometry.SnowHorizonWorkCache(MAX_CACHED_CONTACT_SAMPLES));
    /** One live cell cache per worker, released on cancellation/completion and cleared between cells. */
    private static final class WorkerSampling {
        final IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> contacts=new IdentityHashMap<>();
        final IdentityHashMap<List<SnowSurfacePayload.Mark>,ContactField> fields=new IdentityHashMap<>();
        final IdentityHashMap<CellSnapshot,SnowTerrainGeometry.CompiledHeight> heights=new IdentityHashMap<>();
        // Authored cap roots are immutable for this snapshot and shared by overlapping tile halos.
        final IdentityHashMap<CellSnapshot,List<com.winterkore.engine.surface.geometry.ConformingSnowMesh.Root>> rootTemplates=new IdentityHashMap<>();
        final ReliefCache relief=RELIEF_CACHE.get();
        final com.winterkore.engine.surface.geometry.SnowHorizonWorkCache horizonRays=HORIZON_WORK_CACHE.get();
        final double[] horizonScratch=new double[com.winterkore.engine.surface.geometry.SnowHorizonWorkCache.VALUES];
        final long[] maxThresholdCounters=new long[2];
        final SnowContactRelief.FieldAccumulator fieldValue=new SnowContactRelief.FieldAccumulator();
        long reliefQueries,reliefHits,reliefCandidates,horizonQueries,horizonHits;
        long featureCandidates,featurePointsInside,featureHeightQueries;
        int fieldSequence;
        List<SnowSurfacePayload.Mark> lastContactList;ContactField lastContactField;
        SnowHostGeometry.Cell geometry;LightingField lighting;
        boolean localApproximation,cellTopologyBudgetLimited,persistentAo;double cellWorstError,worstApproximationError;long overBudgetLeaves;
        com.winterkore.engine.surface.geometry.SnowHorizonSampleCache horizonMemo;
        WorkerSampling(List<SnowSurfacePayload.Mark> marks){this(marks,null);}
        WorkerSampling(List<SnowSurfacePayload.Mark> marks,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> cached){
            relief.newPatch();horizonRays.clear();if(cached!=null)contacts.putAll(cached);
        }
        final IdentityHashMap<List<SnowSurfacePayload.Mark>,EnvelopeOwner> envelopeOwners=new IdentityHashMap<>();
        BuildSnapshot envelopeSnapshot;
        void bindSnapshot(BuildSnapshot snapshot){
            if(envelopeSnapshot==snapshot)return;
            envelopeSnapshot=snapshot;envelopeOwners.clear();fields.clear();heights.clear();rootTemplates.clear();lastContactList=null;lastContactField=null;
            // Field ordinals and kernels belong to this immutable snapshot. Samples
            // can be shared by its adjacent tiles and by the later packing pass.
            fieldSequence=0;relief.clear();
            for(var cell:snapshot.lighting.values())registerEnvelope(cell.geometry.pos().asLong(),cell.geometry.generic()?-1:0,cell.contacts);
            for(var cell:snapshot.cells)registerEnvelope(cell.geometry.pos().asLong(),cell.geometry.generic()?-1:0,cell.contacts);
        }
        SnowTerrainGeometry.CompiledHeight compiledHeight(CellSnapshot input){
            return heights.computeIfAbsent(input,cell->{
                var geometry=cell.geometry;var pos=geometry.pos();
                return SnowTerrainGeometry.compileHeight(pos.getX(),pos.getZ(),pos.getY()+geometry.baseOffset(),
                        geometry.layers(),geometry.mask(),geometry.crust(),cell.corners);
            });
        }
        void registerEnvelope(long owner,long rootTile,List<SnowSurfacePayload.Mark> marks){
            if(!marks.isEmpty())envelopeOwners.putIfAbsent(marks,new EnvelopeOwner(owner,rootTile));
        }
        SnowFieldEnvelope.Handle envelope(List<SnowSurfacePayload.Mark> marks){
            var context=envelopeOwners.get(marks);var snapshot=envelopeSnapshot;
            if(context==null||snapshot==null)return null;
            var keys=new ArrayList<SnowFieldEnvelope.KernelKey>(marks.size());
            for(var mark:marks)keys.add(new SnowFieldEnvelope.KernelKey(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),
                    mark.depthMillimeters(),mark.vehicle(),mark.soleProfile()));
            // Relief depends only on exact owner-filtered kernels and world XZ. A halo owner
            // must share its cache regardless of which chunk captured it. Geometry/clamping
            // stays outside this cache; changed support membership has a new input revision.
            var tile=new SnowFieldEnvelope.TileKey(snapshot.epoch,context.owner,0,context.rootTile);
            var position=BlockPos.of(context.owner);
            var cache=localApproximation?FIELD_ENVELOPES:BACKGROUND_FIELD_ENVELOPES;
            return cache.bind(tile,cache.prepareInput(tile,snapshot.renderRevision,keys,position.getX(),position.getZ()));
        }
        SnowContactRelief.Prepared prepared(SnowSurfacePayload.Mark mark){
            var known=contacts.get(mark);
            if(known!=null)return known;
            var value=prepareContact(mark);
            contacts.put(mark,value);return value;
        }
        void beginCell(SnowHostGeometry.Cell cell){geometry=cell;cellTopologyBudgetLimited=false;horizonRays.clear();}
        double relief(List<SnowSurfacePayload.Mark> marks,double x,double z){
            if(marks.isEmpty())return 0;reliefQueries++;
            ContactField field=marks==lastContactList?lastContactField:fields.get(marks);
            if(field==null){field=new ContactField(marks,this);fields.put(marks,field);}
            lastContactList=marks;lastContactField=field;
            double cached=relief.get(field.ordinal,x,z);
            if(!Double.isNaN(cached)){reliefHits++;return cached;}
            if(field.envelope!=null){
                double value=field.envelope.signedMillimeters(x,z);
                relief.put(field.ordinal,x,z,value);return value;
            }
            var nearby=field.at(x,z);var valueField=fieldValue;valueField.reset();var kernels=nearby.depression;
            for(int i=0;i<kernels.length;i++){
                if(valueField.fullyCovered()&&valueField.depressionMillimeters()>=kernels[i].maxDepressionMillimeters())break;
                // D and normalized coverage are independent maxima until a real sole
                // core covers this point. The accumulator's threshold preserves both.
                valueField.addDepression(kernels[i],x,z,maxThresholdCounters);reliefCandidates++;
            }
            if(!valueField.fullyCovered()){
                kernels=nearby.rim;
                for(int i=0;i<kernels.length;i++){
                    if(valueField.rimMillimeters()>=kernels[i].maxRimMillimeters())break;
                    valueField.addRim(kernels[i],x,z);reliefCandidates++;
                }
            }
            double value=valueField.signedMillimeters();
            relief.put(field.ordinal,x,z,value);return value;
        }
    }
    /** Reused primitive storage: exact field/coordinate keys, four-way replacement, no object/world references. */
    private static final class ReliefCache {
        private static final int WAYS=4,SETS=32768,SLOTS=WAYS*SETS;
        final long[] xs=new long[SLOTS],zs=new long[SLOTS];
        final double[] values=new double[SLOTS];
        final int[] fields=new int[SLOTS],stamps=new int[SLOTS];
        final byte[] next=new byte[SETS];
        int generation=1;long evictions;
        void newPatch(){clear();evictions=0;}
        void clear(){if(++generation==0){Arrays.fill(stamps,0);generation=1;}}
        private static int set(int field,long x,long z){
            long hash=x^Long.rotateLeft(z,27)^((long)field*0x9e3779b97f4a7c15L);
            hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;hash=(hash^(hash>>>27))*0x94d049bb133111ebL;
            return (int)(hash^(hash>>>31))&(SETS-1);
        }
        double get(int field,double x,double z){
            long xb=Double.doubleToRawLongBits(x),zb=Double.doubleToRawLongBits(z);int first=set(field,xb,zb)*WAYS;
            for(int i=0;i<WAYS;i++){int slot=first+i;
                if(stamps[slot]==generation&&fields[slot]==field&&xs[slot]==xb&&zs[slot]==zb)return values[slot];}
            return Double.NaN;
        }
        void put(int field,double x,double z,double value){
            long xb=Double.doubleToRawLongBits(x),zb=Double.doubleToRawLongBits(z);int set=set(field,xb,zb),first=set*WAYS,slot=-1;
            for(int i=0;i<WAYS;i++){int candidate=first+i;
                if(stamps[candidate]!=generation){slot=candidate;break;}
                if(fields[candidate]==field&&xs[candidate]==xb&&zs[candidate]==zb){slot=candidate;break;}}
            if(slot<0){slot=first+(next[set]&3);next[set]=(byte)((next[set]+1)&3);evictions++;}
            xs[slot]=xb;zs[slot]=zb;fields[slot]=field;values[slot]=value;stamps[slot]=generation;
        }
    }
    private record FootFeature(double x,double z,int ownerY) {}
    private record FeaturePlacement(double x,double z,float yaw,float width,float length,boolean vehicle,int soleProfile,int ownerY) {
        static FeaturePlacement of(SnowSurfacePayload.Mark mark){
            return new FeaturePlacement(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),mark.vehicle(),mark.soleProfile(),mark.ownerY());
        }
    }
    private record FeaturePoint(double x,double z) {}
    /** Actual sole contours and interior material probes; never tied to test sample locations. */
    private static final class PressureFeatures {
        static final PressureFeatures NONE=new PressureFeatures(List.of());
        final List<FootFeature> points;final IdentityHashMap<SnowHostGeometry.Cell,FeatureGrid> cells=new IdentityHashMap<>();
        PressureFeatures(List<FootFeature> points){this.points=points;}
        static PressureFeatures from(List<SnowSurfacePayload.Mark> marks){
            var points=new LinkedHashSet<FootFeature>();var placements=new HashSet<FeaturePlacement>();
            for(var mark:marks){
                // A saved pose can occur at several weather-adjusted heights. Its XZ probes are identical.
                if(mark.depthMillimeters()<=0||!placements.add(FeaturePlacement.of(mark)))continue;
                points.add(new FootFeature(mark.x(),mark.z(),mark.ownerY()));
                double angle=Math.toRadians(mark.yaw()),c=Math.cos(angle),sn=Math.sin(angle);
                if(mark.vehicle()){
                    // Cross-sections of the real oriented trench, including its centre and shoulders.
                    for(int iz=-4;iz<=4;iz++)for(int ix=-4;ix<=4;ix++){
                        double x=ix*mark.width()/8.0,z=iz*mark.length()/8.0;
                        points.add(new FootFeature(mark.x()+x*c-z*sn,mark.z()+x*sn+z*c,mark.ownerY()));
                    }
                    continue;
                }
                var outline=SoleProfiles.outline(mark.soleProfile());
                for(int ring=1;ring<=4;ring++){
                    double scale=ring*.25;double[][] polygon=new double[outline.length][2];
                    for(int i=0;i<outline.length;i++){
                        double x=outline[i][0]*mark.width()*.5*scale,z=outline[i][1]*mark.length()*.5*scale;
                        polygon[i][0]=mark.x()+x*c-z*sn;polygon[i][1]=mark.z()+x*sn+z*c;
                        points.add(new FootFeature(polygon[i][0],polygon[i][1],mark.ownerY()));
                    }
                    for(int i=0;i<polygon.length;i++){
                        var x=polygon[i];var y=polygon[(i+1)%polygon.length];
                        points.add(new FootFeature((x[0]+y[0])*.5,(x[1]+y[1])*.5,mark.ownerY()));
                        // Preserve true contour/core crossings on shared owner/skirt boundaries.
                        if(Math.abs(y[0]-x[0])>1e-15)for(int edge=(int)Math.ceil(Math.min(x[0],y[0]));edge<=Math.floor(Math.max(x[0],y[0]));edge++){
                            double t=(edge-x[0])/(y[0]-x[0]);points.add(new FootFeature(edge,x[1]+(y[1]-x[1])*t,mark.ownerY()));
                        }
                        if(Math.abs(y[1]-x[1])>1e-15)for(int edge=(int)Math.ceil(Math.min(x[1],y[1]));edge<=Math.floor(Math.max(x[1],y[1]));edge++){
                            double t=(edge-x[1])/(y[1]-x[1]);points.add(new FootFeature(x[0]+(y[0]-x[0])*t,edge,mark.ownerY()));
                        }
                    }
                }
                for(int axis=0;axis<2;axis++)for(int sign=-1;sign<=1;sign+=2)for(int step=1;step<=3;step++){
                    double x=axis==0?sign*step*.25*mark.width()*.5:0,z=axis==1?sign*step*.25*mark.length()*.5:0;
                    points.add(new FootFeature(mark.x()+x*c-z*sn,mark.z()+x*sn+z*c,mark.ownerY()));
                }
            }
            return points.isEmpty()?NONE:new PressureFeatures(List.copyOf(points));
        }
        FeatureGrid cell(SnowHostGeometry.Cell geometry){
            if(points.isEmpty())return FeatureGrid.NONE;
            var known=cells.get(geometry);if(known!=null)return known;
            var result=new FeatureGrid(geometry,points);cells.put(geometry,result);return result;
        }
    }
    private static final class FeatureGrid {
        static final int STRIPS=64,COARSE_BINS=8,STRIPS_PER_BIN=STRIPS/COARSE_BINS;
        static final FeatureGrid NONE=new FeatureGrid();
        // Exact owner-relative coordinates, sorted by X within each Z strip.
        final double[] xs,zs;
        final int[] offsets=new int[STRIPS+1];

        private FeatureGrid(){
            xs=zs=new double[0];
        }
        FeatureGrid(SnowHostGeometry.Cell geometry,List<FootFeature> points){
            @SuppressWarnings("unchecked") ArrayList<FeaturePoint>[] building=(ArrayList<FeaturePoint>[])new ArrayList<?>[STRIPS];
            var position=geometry.pos();
            int count=0;
            for(var point:points){
                if(point.ownerY()!=Integer.MIN_VALUE&&point.ownerY()!=position.getY())continue;
                double x=point.x()-position.getX(),z=point.z()-position.getZ();
                if(x< -1e-9||x>1+1e-9||z< -1e-9||z>1+1e-9||!geometry.contains(point.x(),point.z()))continue;
                int index=strip(z);
                var row=building[index];
                if(row==null)building[index]=row=new ArrayList<>();
                row.add(new FeaturePoint(x,z));
                count++;
            }
            xs=new double[count];zs=new double[count];
            int offset=0;
            for(int strip=0;strip<STRIPS;strip++){
                offsets[strip]=offset;
                var row=building[strip];
                if(row==null)continue;
                row.sort(Comparator.comparingDouble(FeaturePoint::x));
                for(var point:row){
                    xs[offset]=point.x();
                    zs[offset++]=point.z();
                }
            }
            offsets[STRIPS]=offset;
        }
        static int strip(double z){
            return Math.clamp((int)Math.floor(z*STRIPS),0,STRIPS-1);
        }
        static int coarseBin(double coordinate){
            return Math.clamp((int)Math.floor(coordinate*COARSE_BINS),0,COARSE_BINS-1);
        }
        int lowerBoundX(int first,int end,double minimum){
            while(first<end){
                int middle=(first+end)>>>1;
                if(xs[middle]<minimum)first=middle+1;
                else end=middle;
            }
            return first;
        }
    }
    private record ContactBin(SnowContactRelief.Prepared[] depression,SnowContactRelief.Prepared[] rim) {
        static ContactBin sorted(SnowContactRelief.Prepared[] source){
            var depression=source.clone();Arrays.sort(depression,Comparator.comparingDouble(SnowContactRelief.Prepared::maxDepressionMillimeters).reversed());
            var rim=source.clone();Arrays.sort(rim,Comparator.comparingDouble(SnowContactRelief.Prepared::maxRimMillimeters).reversed());
            return new ContactBin(depression,rim);
        }
    }
    /** Exact broad phase with bounded, direct-indexed quarter-metre tiles. */
    private static final class ContactField {
        private static final ContactBin NONE=new ContactBin(new SnowContactRelief.Prepared[0],new SnowContactRelief.Prepared[0]);
        final ContactBin all;final SnowFieldEnvelope.Handle envelope;
        final ContactBin[] bins;final int ordinal,minX,minZ,width,depth;
        ContactField(List<SnowSurfacePayload.Mark> marks,WorkerSampling sampling){
            ordinal=++sampling.fieldSequence;envelope=sampling.envelope(marks);
            if(envelope!=null){all=NONE;bins=null;minX=minZ=width=depth=0;return;}
            var source=new SnowContactRelief.Prepared[marks.size()];
            int x0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z0=Integer.MAX_VALUE,z1=Integer.MIN_VALUE;
            for(int i=0;i<marks.size();i++){
                var mark=marks.get(i);source[i]=sampling.prepared(mark);
                double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
                x0=Math.min(x0,(int)Math.floor((mark.x()-radius)*4));x1=Math.max(x1,(int)Math.floor((mark.x()+radius)*4));
                z0=Math.min(z0,(int)Math.floor((mark.z()-radius)*4));z1=Math.max(z1,(int)Math.floor((mark.z()+radius)*4));
            }
            all=ContactBin.sorted(source);minX=x0;minZ=z0;
            long sx=(long)x1-x0+1,sz=(long)z1-z0+1;
            if(sx<=0||sz<=0||sx>4096||sz>4096||sx*sz>4096){bins=null;width=depth=0;return;}
            width=(int)sx;depth=(int)sz;bins=new ContactBin[width*depth];
            @SuppressWarnings("unchecked") ArrayList<SnowContactRelief.Prepared>[] building=
                    (ArrayList<SnowContactRelief.Prepared>[])new ArrayList<?>[bins.length];
            for(int i=0;i<marks.size();i++){
                var mark=marks.get(i);var prepared=source[i];
                double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
                int ax=(int)Math.floor((mark.x()-radius)*4),bx=(int)Math.floor((mark.x()+radius)*4);
                int az=(int)Math.floor((mark.z()-radius)*4),bz=(int)Math.floor((mark.z()+radius)*4);
                for(int z=az;z<=bz;z++)for(int x=ax;x<=bx;x++){
                    if(!prepared.influenceIntersectsBounds(x*.25,z*.25,(x+1)*.25,(z+1)*.25))continue;
                    int index=(z-minZ)*width+x-minX;var bin=building[index];
                    if(bin==null)building[index]=bin=new ArrayList<>();bin.add(prepared);
                }
            }
            for(int i=0;i<bins.length;i++)if(building[i]!=null)bins[i]=ContactBin.sorted(building[i].toArray(SnowContactRelief.Prepared[]::new));
        }
        ContactBin at(double x,double z){
            if(bins==null)return all;
            int ax=(int)Math.floor(x*4)-minX,az=(int)Math.floor(z*4)-minZ;
            if(ax<0||ax>=width||az<0||az>=depth)return NONE;
            var bin=bins[az*width+ax];return bin==null?NONE:bin;
        }
    }
    /** Lighting support lookup has the exact source cells and a bounded three-dimensional index. */
    private static final class LightingField {
        private static final long MAX_CELLS=1_000_000;
        final Map<Long,CellSnapshot> source;final CellSnapshot[] cells;
        Map<Long,CellSnapshot> fallback;int minX,minY,minZ,width,height,depth;
        LightingField(Map<Long,CellSnapshot> source){
            this.source=source;
            if(source.isEmpty()){cells=new CellSnapshot[0];return;}
            int x0=Integer.MAX_VALUE,y0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,y1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(var value:source.values()){
                var pos=value.geometry.pos();x0=Math.min(x0,pos.getX());x1=Math.max(x1,pos.getX());
                y0=Math.min(y0,pos.getY());y1=Math.max(y1,pos.getY());z0=Math.min(z0,pos.getZ());z1=Math.max(z1,pos.getZ());
            }
            minX=x0;minY=y0;minZ=z0;long sx=(long)x1-x0+1,sy=(long)y1-y0+1,sz=(long)z1-z0+1;
            if(sx<=0||sy<=0||sz<=0||sx>MAX_CELLS||sy>MAX_CELLS||sz>MAX_CELLS||sx*sy>MAX_CELLS||sx*sy*sz>MAX_CELLS){
                cells=null;fallback=new HashMap<>(source);return;
            }
            width=(int)sx;height=(int)sy;depth=(int)sz;cells=new CellSnapshot[(int)(sx*sy*sz)];
            for(var value:source.values()){
                var pos=value.geometry.pos();cells[((pos.getY()-minY)*depth+pos.getZ()-minZ)*width+pos.getX()-minX]=value;
            }
        }
        CellSnapshot at(int x,int y,int z){
            if(cells==null)return fallback.get(BlockPos.asLong(x,y,z));
            int ax=x-minX,ay=y-minY,az=z-minZ;
            return ax<0||ax>=width||ay<0||ay>=height||az<0||az>=depth?null:cells[(ay*depth+az)*width+ax];
        }
    }
    private static final class BuildJob {
        final List<BuildSnapshot> snapshots;final Set<Long> dependencies;final boolean initial;
        final FinePlan fine;
        final Map<Long,FieldSignature> fieldContext;final List<Long> fieldOrder;
        volatile boolean cancelled,finished;
        List<Mesh> result;Throwable failure;long elapsedNanos;
        BuildJob(List<BuildSnapshot> snapshots,Set<Long> dependencies,boolean initial){
            this(snapshots,dependencies,initial,null,Map.of(),List.of());
        }
        BuildJob(List<BuildSnapshot> snapshots,Set<Long> dependencies,boolean initial,FinePlan fine,
                Map<Long,FieldSignature> fieldContext,List<Long> fieldOrder){
            this.snapshots=snapshots;this.dependencies=dependencies;this.initial=initial;this.fine=fine;
            this.fieldContext=fieldContext;this.fieldOrder=fieldOrder;
        }
    }
    private static final class GpuMesh {
        MappableRingBuffer vertices,view,shadowView;int capacity;
        GpuBuffer frameVertices;long vertexFrame=Long.MIN_VALUE,lastUseFrame;boolean shadowUsed;
        final Map<GpuBuffer,Long> uploaded=new IdentityHashMap<>();
        void close(){
            if(vertices!=null)vertices.close();if(view!=null)view.close();if(shadowView!=null)shadowView.close();
            vertices=view=shadowView=null;frameVertices=null;vertexFrame=Long.MIN_VALUE;shadowUsed=false;uploaded.clear();
        }
    }
    private static volatile SnowSurfacePayload incoming;
    private static volatile Map<Long,Mesh> meshes=Map.of();
    private static volatile Frame extractedFrame;
    private static ShadowPrepared shadowPrepared;
    private static long frameSequence;
    private static final Map<Long,LinkedHashMap<Long,SnowSurfacePayload.Column>> columns=new HashMap<>();
    private static final LinkedHashSet<Long> dirty=new LinkedHashSet<>();
    private enum DirtyCause {
        UNKNOWN,NATIVE,COLUMN,DRY_CORNER,AUTHORITY_WEATHER,AUTHORITY_POSITIVE,CANONICAL_RETIREMENT,
        PRESSURE_FOLLOWUP,PRESSURE_PATCH_FALLBACK,DRAFT_BUDGET,CARRY_FALLBACK,PARTIAL_FALLBACK,WORKER_RETRY,WORKER_VISUAL
    }
    private static final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap dirtyCauses=new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
    private static final long[] dirtyEnqueueCounts=new long[DirtyCause.values().length],fineCaptureDirtyCauses=new long[DirtyCause.values().length];
    private static void markDirty(long key,DirtyCause cause){
        if(dirty.add(key)){
            dirtyCauses.put(key,(byte)cause.ordinal());
            dirtyEnqueueCounts[cause.ordinal()]++;
        }
    }
    private static void clearDirty(long key){
        dirty.remove(key);dirtyCauses.remove(key);
    }
    private static final Map<Long,Long> geometryRevisions=new HashMap<>();
    private static ExecutorService tessellator;
    private static BuildJob activeBuild;
    private static Preparation preparing;
    private static final Map<Long,SnowSurfaceChunkPayload.Chunk> readyFrames=new HashMap<>();
    private static final Map<Long,BlockedFrame> blockedFrames=new HashMap<>();
    private static final Map<Long,FieldSignature> fieldSignatures=new HashMap<>();
    private static final Map<Long,Map<Long,FieldSignature>> lastBuiltFieldContexts=new HashMap<>();
    private static final Map<Long,Long> lastBuiltFieldGeometry=new HashMap<>(),lastFineBuildStarted=new HashMap<>();
    private static long fineContactBuildsDebounced,fineContactBuildsEquivalent,fineContactBuildsStrictFields;
    private record FieldSignature(List<SnowSurfacePayload.Column> columns,List<SnowSurfacePayload.Column> halo) {}
    private static FieldSignature fieldSignature(SnowSurfaceChunkPayload.Chunk frame){
        var existing=fieldSignatures.get(frame.key());
        if(existing!=null&&visualColumnsMatch(existing.columns,frame.columns(),true)&&visualColumnsMatch(existing.halo,frame.halo(),true))return existing;
        return new FieldSignature(frame.columns(),frame.halo());
    }
    private static Map<Long,FieldSignature> fieldContext(Map<Long,SnowSurfaceChunkPayload.Chunk> frames){
        var result=new HashMap<Long,FieldSignature>();for(var entry:frames.entrySet())result.put(entry.getKey(),fieldSignature(entry.getValue()));
        return Map.copyOf(result);
    }
    /** Path density remains in the installed column for physical use; cap shape uses measured depth. */
    private static boolean visualColumnMatches(SnowSurfacePayload.Column a,SnowSurfacePayload.Column b,boolean exact){
        if(a==b)return true;
        if(a==null||b==null||a.x()!=b.x()||a.y()!=b.y()||a.z()!=b.z()||!sameBootstrapColumn(a,b))return false;
        return exact?Float.floatToIntBits(a.deltaMillimeters())==Float.floatToIntBits(b.deltaMillimeters())
                :Math.abs(a.deltaMillimeters()-b.deltaMillimeters())<MESH_DELTA_STEP_MM;
    }
    private static boolean visualColumnsMatch(List<SnowSurfacePayload.Column> a,List<SnowSurfacePayload.Column> b,boolean exact){
        if(a==b)return true;if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!visualColumnMatches(a.get(i),b.get(i),exact))return false;
        return true;
    }
    private static boolean equivalentFields(Map<Long,FieldSignature> a,Map<Long,FieldSignature> b){
        if(a==b)return true;if(b==null||a.size()!=b.size())return false;
        for(var entry:a.entrySet()){
            var old=b.get(entry.getKey());var current=entry.getValue();if(old==null)return false;if(current==old)continue;
            if(!visualColumnsMatch(current.columns,old.columns,false)||!visualColumnsMatch(current.halo,old.halo,false))return false;
        }
        return true;
    }
    private static boolean debounceContactFine(long key,Map<Long,FieldSignature> context){
        if(!meshes.containsKey(key)||context.isEmpty()
                ||lastBuiltFieldGeometry.getOrDefault(key,Long.MIN_VALUE).longValue()!=geometryRevisions.getOrDefault(key,0L).longValue())return false;
        // Only a pressure-equivalent field waits for the route to quiet down.
        // Meaningful weather or changes to native support still get their normal turn.
        var previous=lastBuiltFieldContexts.get(key);var current=context.get(key);var built=previous==null?null:previous.get(key);
        if(current==null||built==null||!visualColumnsMatch(current.columns,built.columns,false)||!visualColumnsMatch(current.halo,built.halo,false))return false;
        fineContactBuildsEquivalent++;
        if(visualColumnsMatch(current.columns,built.columns,true)&&visualColumnsMatch(current.halo,built.halo,true))fineContactBuildsStrictFields++;
        fineContactBuildsDebounced++;return true;
    }
    private static final long CONTACT_FINE_QUIET_TICKS=10;
    private static final int MAX_LOCAL_CONTACTS=512,MAX_LOCAL_EVENTS=4096;
    private static final long LOCAL_CONTACT_TTL=200;
    private static final double CANONICAL_PRESSURE_TOLERANCE_MM=6.25;
    private static final int CANONICAL_CHECKS_PER_TICK=16;
    private record CanonicalStamp(List<SnowSurfacePayload.Mark> authority,List<SnowSurfacePayload.Mark> visible,
            SnowHostGeometry.Cell geometry,double[] corners) {}
    private record CanonicalOwner(CellData cell,SnowContactRelief.Prepared[] kernels) {}
    private static final class LocalContact {
        final long id,tick,nanos,frame;final SnowSurfacePayload.Mark mark;final boolean channel;
        Set<Long> owners=Set.of();final Set<Long> drawnOwners=new HashSet<>();
        final Map<Long,Long> eligibleAt=new HashMap<>(),drawnAt=new HashMap<>();
        long eligibleTick=-1,eligibleFrame=-1;
        boolean ttlUnconfirmed,canonicalCovered;String retirementReason="pending";
        final SnowSurfacePayload.Mark weatherAtBirth;final boolean weatherBirthKnown;
        long weatherSeenRevision,weatherMatchedRevision=-1,weatherMatchedTick=-1;
        double physicalCoverMillimeters;
        SnowSurfacePayload.Mark weatherMark;
        Map<Long,CanonicalStamp> canonicalStamps=Map.of();double[] canonicalPoints;
        long canonicalChecks;int canonicalSources;boolean canonicalCoreProbe;
        double canonicalMaximumDepth,canonicalDeficit,canonicalExpected,canonicalActual,canonicalRequired,canonicalX,canonicalZ;
        long appliedTick=-1,appliedFrame=-1,appliedNanos,meshRevision,drawnTick=-1,drawnFrame=-1,drawnNanos;
        LocalContact(long id,SnowSurfacePayload.Mark mark,boolean channel){
            this.id=id;this.mark=mark;this.channel=channel;tick=contactTick();nanos=contactFrameStartedNanos!=0?contactFrameStartedNanos:System.nanoTime();frame=frameSequence;
            var observed=weatherAuthority(mark);weatherSeenRevision=observed==null?-1:observed.revision();
            var lookup=observed==null?new WeatherLookup(null,false):weatherPlacement(observed,mark);
            weatherAtBirth=lookup.mark;weatherBirthKnown=lookup.complete;
        }
    }
    private static final LinkedHashMap<MarkKey,LocalContact> localContacts=new LinkedHashMap<>();
    private static long canonicalCursor,canonicalBudgetTick=-1;private static int canonicalChecksThisTick,weatherChecksThisTick;
    private static long canonicalRetirements,unloadedRetirements,physicalRetirements,ttlUnconfirmedContacts,
            capacityUnconfirmedContacts,canonicalChecks,canonicalCacheHits,canonicalProbeSamples,canonicalCoreProbeFailures,canonicalWorstNanos;
    private static final Map<Long,List<SnowSurfacePayload.Mark>> committedAuthoritative=new HashMap<>();
    private static final Set<Long> pendingPressureChunks=new HashSet<>();
    private record PressureOffer(SnowSurfacePayload.Mark mark,long sourceChunk,long sourceRevision) {}
    private static final class PressureLineage {
        long coveredRevision;
        final Map<MarkKey,PressureOffer> supplemental=new LinkedHashMap<>();

        void accept(MarkKey identity,PressureOffer offer,long completeRevision){
            if(offer.sourceRevision<=completeRevision){
                coveredRevision=Math.max(coveredRevision,offer.sourceRevision);
            }else supplemental.put(identity,offer);
        }
        void observeComplete(long revision){
            for(var iterator=supplemental.values().iterator();iterator.hasNext();){
                var offer=iterator.next();
                if(offer.sourceRevision<=revision){
                    coveredRevision=Math.max(coveredRevision,offer.sourceRevision);
                    iterator.remove();
                }
            }
        }
    }
    private static final Map<Long,PressureLineage> pressureLineages=new HashMap<>();
    private static final Map<MarkKey,PressureOffer> urgentPressureAuthority=new LinkedHashMap<>();
    private static final int MAX_URGENT_AUTHORITY_MARKS=64;
    private static long weatherPressureDeferrals,urgentPressureDeferrals;
    private static long contactFrameStartedNanos;
    private static final Map<MarkKey,SnowSurfacePayload.Mark> retiredLocalContacts=new HashMap<>();
    private static final Map<Long,Long> coarseUntil=new HashMap<>();
    private static final Set<Long> fineQueued=new LinkedHashSet<>();
    private record PressureFollowup(long deadline,boolean hadDraft) {}
    /** The affected chunk still owes AO outside its synchronous owner updates. */
    private static final Map<Long,PressureFollowup> localFineFollowups=new LinkedHashMap<>();
    private static final long LOCAL_FINE_FOLLOWUP_TICKS=120;
    private static final int FINE_WITNESS_LIMIT=48,FINE_CAPTURE_ROOT_LIMIT=12,FINE_BATCH_LIMIT=4;
    private static final long FINE_WITNESS_BYTE_LIMIT=16L*1024*1024;
    /** Immutable draft inputs only. No mesh-wide contact ledger or packed source is retained here. */
    private record FineBasis(long epoch,long key,long sourceRevision,BlockPos origin,List<CellSnapshot> roots,
            Map<Long,CellSnapshot> lighting,Map<Long,Long> geometry,long bytes) {}
    private record FineWitness(FineBasis basis,CellSnapshot input,java.lang.ref.WeakReference<SnowMeshRange> range) {}
    private record FinePlan(FineBasis basis,BuildSnapshot snapshot,Map<Long,SnowMeshRange> ranges) {}
    private static final LinkedHashMap<Long,FineWitness> fineWitnesses=new LinkedHashMap<>();
    private static long fineWitnessBytes,partialFineJobs,partialFineCells,partialFineCommits,partialFineFallbacks;
    private static long fineCaptureCandidates,fineCaptureDirty,fineWitnessesCaptured;
    private static long fineWitnessDropsDirty,fineWitnessDropsRange,fineWitnessDropsPreparation,fineWitnessDropsSuperseded,fineWitnessDropsBudget;
    private enum FineReject {
        EMPTY_WITNESS_SET,NO_QUEUED_WITNESS,STALE_RANGE,MESH_MISSING,EPOCH,DIRTY,ORIGIN,
        DEPENDENCY_UNLOADED,HANDOFF,GEOMETRY_REVISION,HALO_OWNER_ADDED,HALO_OWNER_REMOVED,
        HALO_COLUMN_DENSITY,HALO_COLUMN,HALO_GEOMETRY,HALO_CORNERS,HALO_CONTACT_STRENGTH,
        HALO_CONTACT_ORDER,HALO_CONTACT_FIELD,HALO_LIGHT,HALO_SKIRT
    }
    private static final long[] fineRejectCounts=new long[FineReject.values().length];
    private static long fineSelectionAttempts;
    private static boolean rejectFine(FineReject reason){
        fineRejectCounts[reason.ordinal()]++;
        return false;
    }
    private static Map<String,Long> reasonCounts(Enum<?>[] reasons,long[] counts){
        var result=new LinkedHashMap<String,Long>();
        for(var reason:reasons)if(counts[reason.ordinal()]!=0)
            result.put(reason.name().toLowerCase(java.util.Locale.ROOT),counts[reason.ordinal()]);
        return result;
    }
    private static int consecutivePriorityPreparations;
    private static long nearWeatherPreparations;
    private static double maxLocalApproximationErrorMm;
    private static long localTessellationWallNanos,localPackingWallNanos,localParallelPackJobs;
    private static long localOverBudgetLeaves,lastFinePublicationTick=-1;
    record LocalContactCandidate(SnowSurfacePayload.Mark mark,double rawX,double rawZ,boolean channel,
            SnowSurfaceRuntime.RenderedSupport support) {}
    private static final ArrayDeque<LocalContact> localEvents=new ArrayDeque<>();
    private static long localGenerated,localPublished,localDrawn,localPatches,localPatchCells,localPatchNanos,
            localWorstPatchNanos,localWorstPublishTicks,localWorstDrawFrames,localEventDrops,
            localScannedTriangles,localReusedTriangles,localRefinedTriangles,localRetainedCoreTriangles,localCoreProofChecks,
            localRetainedDominatedTriangles,localDominanceProofChecks,localCopiedBytes,localPatchScanNanos,localPatchPackNanos,
            localReliefQueries,localReliefCacheHits,localReliefCandidates,localMaxThresholdTests,localMaxThresholdRejects,
            localRelitVertices,localAoRayQueries,localAoRayHits,localReliefCacheEvictions,
            localFeatureCandidates,localFeaturePointsInside,localFeatureHeightQueries;
    private static volatile Ownership ownership=new Ownership(0,Set.of());
    private static volatile Map<Long,Handoff> handoffs=Map.of();
    private static final ConcurrentMap<Long,Long> requiredNativeSections=new ConcurrentHashMap<>();
    private static volatile Map<Long,Long> bootstrapRequiredSections=Map.of();
    private static final ConcurrentMap<Long,Long> nativeSectionAcks=new ConcurrentHashMap<>();
    private static long bootstrapCenter=Long.MIN_VALUE;
    private static BootstrapCohort bootstrapCohort;
    private static final LinkedHashMap<Long,Map<Long,Long>> rejectedBootstrapSources=new LinkedHashMap<>();
    private static boolean bootstrapNeedsFreshCohort;
    private static long bootstrapRetryGeneration,bootstrapCohortCaptures,bootstrapCohortInvalidations,lastBootstrapCohortTick=-1;
    private static long bootstrapCommittedSourceRevision=-1;
    private static String bootstrapCohortState="waiting for frames";
    private static final long BOOTSTRAP_NATIVE_STABLE_NANOS=1_000_000_000L;
    private static long bootstrapNativeMismatchSince,bootstrapNativeMismatchCapture=-1;
    private static Map<Long,Long> bootstrapNativeMismatchVersions=Map.of();
    private static final Map<Mesh,GpuMesh> gpu=new IdentityHashMap<>();
    private static final Map<SnowMeshBuffer,GpuMesh> sourceGpu=new IdentityHashMap<>();
    private static long sourceUploadedBytes,sourceRetiredBuffers,sourcePayloadCopies,sourceExactRangeReuses;
    private static final AtomicLong suppressed=new AtomicLong();
    private static final int DRAFT_TOPOLOGY_WORK=120_000,FINE_TOPOLOGY_WORK=300_000;
    // Keep counts across world reopen so an exceptional build remains visible to acceptance.
    private static final AtomicLong topologyBudgetFallbacks=new AtomicLong();
    private static final AtomicLong maxDraftTopologyWork=new AtomicLong(),maxFineTopologyWork=new AtomicLong();
    private static List<SnowSurfacePayload.Mark> marks=List.of();
    private static ClientLevel world;
    private static GpuSampler materialSampler;
    private static long epoch,revision,uploads,frames,builds,snapshotNanos,workerNanos,discardedBuilds,geometryRevision;
    private static long lastTick=-1,buildingKey=Long.MIN_VALUE;
    private static int drawn;
    private static boolean initialized,bootstrapDone;
    /** CPU-only initial commit latch; ordinary movement never re-enters the loading gate. */
    public static boolean bootstrapReady(){return Minecraft.getInstance().level==world&&world!=null&&bootstrapDone;}
    public static boolean bootstrapPending(){
        var level=Minecraft.getInstance().level;
        return level!=null&&!bootstrapReady()&&!SnowSurfaceChunkInbox.activeChunks(level.dimension().identifier().toString()).isEmpty();
    }
    public static long ownershipEpoch(){return ownership.epoch;}
    public static Set<Long> bootstrapRequiredSectionKeys(){return bootstrapRequiredSections.keySet();}
    public static long requiredNativeEpoch(long section){return requiredNativeSections.getOrDefault(section,0L);}
    public static Set<Long> pendingNativeSectionKeys(){
        var pending=new HashSet<Long>();
        for(var entry:requiredNativeSections.entrySet())if(!acknowledged(entry.getKey(),entry.getValue()))pending.add(entry.getKey());
        return Set.copyOf(pending);
    }
    /** A cap and its lower native white fringe switch ownership as one upload barrier. */
    private static boolean nativeUploadsReady(long section,long expected,Set<Long> dependencies,Map<Long,Long> uploaded){
        if(uploaded.getOrDefault(section,Long.MIN_VALUE)<expected)return false;
        for(long dependency:dependencies)if(uploaded.getOrDefault(dependency,Long.MIN_VALUE)<expected)return false;
        return true;
    }
    private static boolean acknowledged(long section,long expected){
        var transfer=handoffs.get(section);
        Set<Long> dependencies=transfer!=null&&transfer.epoch==expected?transfer.nativeSections:Set.of();
        return nativeUploadsReady(section,expected,dependencies,nativeSectionAcks);
    }
    private static long section(BlockPos pos){return SectionPos.asLong(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4);}
    /** Native sections upload independently. Once the cap's native faces are suppressed,
     * its replacement must cover them immediately. Keep both snapshots alive until the
     * full dependency ACK; removals also keep old coverage until the fringe returns. */
    private static boolean nativeDisplayReady(long section,long expected,boolean replacement,Set<Long> dependencies,Map<Long,Long> uploaded){
        return replacement?uploaded.getOrDefault(section,Long.MIN_VALUE)>=expected
                :nativeUploadsReady(section,expected,dependencies,uploaded);
    }
    private static Mesh displayedHandoff(long section,Handoff transfer){
        return nativeDisplayReady(section,transfer.epoch,hasSection(transfer.after,section),transfer.nativeSections,nativeSectionAcks)
                ?transfer.after:transfer.before;
    }
    private static Mesh displayedMesh(BlockPos owner){
        long section=section(owner);Handoff pending=handoffs.get(section);
        return pending==null?meshes.get(chunkKey(owner)):displayedHandoff(section,pending);
    }
    private static Map<Long,Mesh> displaySelections(List<Mesh> candidates){
        var result=new HashMap<Long,Mesh>();var pending=handoffs;
        for(var mesh:candidates)for(var group:mesh.groups)if(!result.containsKey(group.section)){
            var transfer=pending.get(group.section);
            result.put(group.section,transfer==null?meshes.get(mesh.key):displayedHandoff(group.section,transfer));
        }
        return result;
    }
    private static List<Mesh> sceneMeshes(){
        if(handoffs.isEmpty())return List.copyOf(meshes.values());
        var unique=new IdentityHashMap<Mesh,Boolean>();for(var mesh:meshes.values())unique.put(mesh,Boolean.TRUE);
        for(var pending:handoffs.values())if(pending.before!=null)unique.put(pending.before,Boolean.TRUE);
        return List.copyOf(unique.keySet());
    }
    /** An actual native upload may acknowledge only a task created after this owner-map publication. */
    public static void nativeSectionPublished(int x,int y,int z,long capturedEpoch){
        long section=SectionPos.asLong(x,y,z);Long required=requiredNativeSections.get(section);
        if(required!=null&&capturedEpoch>=required){
            Long previous=nativeSectionAcks.putIfAbsent(section,capturedEpoch);
            if(previous==null||capturedEpoch>previous){nativeSectionAcks.merge(section,capturedEpoch,Math::max);ShadowCaptureCache.terrainChanged();}
        }
    }
    public static boolean bootstrapNativeReady(){
        if(!bootstrapReady())return false;
        for(var entry:bootstrapRequiredSections.entrySet())if(nativeSectionAcks.getOrDefault(entry.getKey(),Long.MIN_VALUE)<entry.getValue())return false;
        return true;
    }
    /** Stable retry generation: an SSP sender can rearm one coherent server bundle without native-cap bypass. */
    public static boolean bootstrapNeedsFreshCohort(){return world!=null&&!bootstrapDone&&bootstrapNeedsFreshCohort;}
    public static long bootstrapRetryGeneration(){return bootstrapRetryGeneration;}
    public static long bootstrapCommittedSourceRevision(){return bootstrapCommittedSourceRevision;}
    /** Bounded read-only loading diagnostics; never decodes rich mesh geometry or requests a chunk. */
    public static Map<String,Object> bootstrapDiagnostics(){
        int ready=0,committed=0,acknowledged=0;var blocked=new LinkedHashMap<String,String>();var weather=new LinkedHashMap<String,Long>();
        if(bootstrapCenter!=Long.MIN_VALUE){
            int cx=(int)bootstrapCenter,cz=(int)(bootstrapCenter>>32);
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                long key=chunkKey(cx+dx,cz+dz);var frame=readyFrames.get(key);String name=(cx+dx)+","+(cz+dz);
                if(frame!=null){ready++;weather.put(name,frame.weatherTick());}
                if(meshes.containsKey(key))committed++;
                var failure=blockedFrames.get(key);
                if(failure!=null&&frame!=null&&failure.revision==frame.revision()
                        &&failure.geometryRevision==geometryRevisions.getOrDefault(key,0L))blocked.put(name,failure.reason);
            }
        }
        for(var entry:bootstrapRequiredSections.entrySet())if(nativeSectionAcks.getOrDefault(entry.getKey(),Long.MIN_VALUE)>=entry.getValue())acknowledged++;
        var out=new LinkedHashMap<String,Object>();
        out.put("nearReadyFrames",ready);out.put("nearCommittedMeshes",committed);out.put("nearTarget",9);
        out.put("nearWeatherTicks",weather);out.put("blocked",blocked);out.put("preparing",preparing==null?0:preparing.snapshots.size());
        out.put("workerPending",activeBuild!=null);out.put("nativeRequired",bootstrapRequiredSections.size());out.put("nativeAcknowledged",acknowledged);
        out.put("cpuReady",bootstrapReady());out.put("nativeReady",bootstrapNativeReady());out.put("queuedFrames",SnowSurfaceChunkInbox.pendingChunks());
        out.put("nearWeatherPreparations",nearWeatherPreparations);
        out.put("handoffSections",handoffs.size());out.put("nativePendingSections",pendingNativeSectionKeys().size());
        var cohort=bootstrapCohort;
        out.put("retainedCohortSize",cohort==null?0:cohort.frames.size());out.put("retainedCohortTick",cohort==null?-1:cohort.weatherTick);
        out.put("retainedCohortSourceRevision",cohort==null?-1:cohort.frames.values().iterator().next().revision());
        out.put("retainedCohortEpoch",cohort==null?-1:cohort.epoch);out.put("retainedCohortGeometryEpoch",cohort==null?-1:cohort.geometryEpoch);
        out.put("retainedCohortNativeReady",cohort!=null&&cohort.nativeReady);out.put("lastBootstrapCohortTick",lastBootstrapCohortTick);
        out.put("bootstrapCohortCaptures",bootstrapCohortCaptures);out.put("bootstrapCohortInvalidations",bootstrapCohortInvalidations);
        out.put("bootstrapNeedsFreshCohort",bootstrapNeedsFreshCohort());out.put("bootstrapRetryGeneration",bootstrapRetryGeneration);
        out.put("bootstrapCommittedSourceRevision",bootstrapCommittedSourceRevision);
        out.put("bootstrapCohortState",bootstrapCohortState);
        out.put("bootstrapNativeMismatchMillis",bootstrapNativeMismatchSince==0?0:(System.nanoTime()-bootstrapNativeMismatchSince)/1_000_000);
        out.put("bootstrapNativeMismatchRequiredMillis",BOOTSTRAP_NATIVE_STABLE_NANOS/1_000_000);
        out.put("bootstrapNativeGeometryDataReady",bootstrapNativeWindowReady());
        return Map.copyOf(out);
    }
    private SnowSurfaceClient(){}
    private static Identifier id(String path){return Identifier.fromNamespaceAndPath("wke",path);}
    private static long chunkKey(int x,int z){return ((long)x&0xffffffffL)|((long)z<<32);}
    private static long chunkKey(BlockPos p){return chunkKey(p.getX()>>4,p.getZ()>>4);}
    /** Safe for compile workers; the published map and each replacement mesh are immutable. */
    public static boolean ownsTop(BlockPos pos){
        return ownership.positions.contains(pos.asLong());
    }
    public static boolean suppressTop(BlockPos pos){boolean owned=ownsTop(pos);if(owned)suppressed.incrementAndGet();return owned;}
    /** Decode diagnostics only on request from the exact immutable float bytes submitted to the GPU. */
    public static DebugCell debugCell(BlockPos pos){
        Mesh mesh=displayedMesh(pos);CellData cell=mesh==null?null:mesh.cells.get(pos.asLong());
        if(cell==null)return new DebugCell(false,0,0,0,List.of(),List.of(),List.of());
        // Per-mesh ownership bounds this diagnostics-only cache; replacing/unloading the mesh retires it too.
        return mesh.debugCells.computeIfAbsent(pos.asLong(),ignored->decodeCell(mesh,cell));
    }
    public record PackedCell(ByteBuffer data,BlockPos origin,int vertexCount,long meshRevision) {}
    /** Cold diagnostics: the actual ACK-selected cell bytes, without decoding or flattening. */
    public static PackedCell packedCell(BlockPos owner){
        Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
        return cell==null?null:new PackedCell(cell.range.bytes(),mesh.origin,cell.vertexCount,mesh.revision);
    }
    private static DebugCell decodeCell(Mesh mesh,CellData cell){
        ByteBuffer data=cell.range.bytes();
        int end=cell.vertexCount;
        var triangles=new ArrayList<DebugTriangle>(cell.vertexCount/3);
        int dx=cell.geometry.pos().getX()-mesh.origin.getX(),dz=cell.geometry.pos().getZ()-mesh.origin.getZ();
        for(int vertex=0;vertex<end;vertex+=3){
            int offset=vertex*BYTES;
            triangles.add(new DebugTriangle(decodeVertex(data,offset,dx,dz),decodeVertex(data,offset+BYTES,dx,dz),decodeVertex(data,offset+BYTES*2,dx,dz)));
        }
        var geometry=cell.geometry;double[] corners=cell.fineCorners;
        return new DebugCell(true,geometry.layers(),geometry.mask(),geometry.crust(),
                List.of(corners[0],corners[1],corners[2],corners[3]),List.copyOf(triangles),cell.contacts);
    }
    private static DebugVertex decodeVertex(ByteBuffer data,int offset,int ownerChunkX,int ownerChunkZ){
        // Position.X/Z are chunk-local, Position.Y is already world metres; UVLight and NormalDepth are four floats each.
        int sharedWallSide=(int)-data.getFloat(offset+12)-1;
        return new DebugVertex(data.getFloat(offset)-ownerChunkX,data.getFloat(offset+4),data.getFloat(offset+8)-ownerChunkZ,
                data.getFloat(offset+16),data.getFloat(offset+20),data.getFloat(offset+32),data.getFloat(offset+36),
                data.getFloat(offset+40),data.getFloat(offset+44),-1,-1,sharedWallSide);
    }
    private static double delta(BlockPos p){
        Mesh mesh=displayedMesh(p);CellData cell=mesh==null?null:mesh.cells.get(p.asLong());
        return cell==null?Double.NaN:cell.column.deltaMillimeters();
    }
    private static double pendingDelta(BlockPos p){
        long key=chunkKey(p);Mesh current=meshes.get(key);
        if(key!=buildingKey&&(current==null||!current.cells.containsKey(p.asLong())))return Double.NaN;
        var chunk=columns.get(key);var c=chunk==null?null:chunk.get(p.asLong());return c==null?Double.NaN:c.deltaMillimeters();
    }
    private static final SnowContactRelief.Prepared[] NO_CONTACT_PRESSURE=new SnowContactRelief.Prepared[0];
    private static final ThreadLocal<SnowContactRelief.FieldAccumulator> COLLISION_PRESSURE=
            ThreadLocal.withInitial(SnowContactRelief.FieldAccumulator::new);
    private static double depression(BlockPos owner,double x,double z){
        Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
        if(cell==null)return 0;
        return collisionPressure(cell.pressure,x,z);
    }
    private static double depression(double x,double z){
        CellData highest=null;
        for(var mesh:sceneMeshes())for(var cell:mesh.cells.values()){
            BlockPos pos=cell.geometry.pos();
            if(pos.getX()==(int)Math.floor(x)&&pos.getZ()==(int)Math.floor(z)&&displayedMesh(pos)==mesh
                    &&(highest==null||cell.geometry.nominalHeight()>highest.geometry.nominalHeight()))highest=cell;
        }
        return highest==null?0:collisionPressure(highest.pressure,x,z);
    }
    private static double relief(SnowSurfacePayload.Mark m,double x,double z){
        return SnowContactRelief.depressionMillimeters(x,z,m.x(),m.z(),m.yaw(),m.width(),m.length(),m.depthMillimeters(),m.vehicle(),m.soleProfile());
    }
    /** A visible tile's contact geometry is immutable between publications. */
    private static double collisionPressure(SnowContactRelief.Prepared[] pressure,double x,double z){
        if(pressure.length==0)return 0;
        var field=COLLISION_PRESSURE.get();
        field.reset();
        for(var kernel:pressure)field.addDepression(kernel,x,z);
        if(!field.fullyCovered())for(var kernel:pressure)field.addRim(kernel,x,z);
        return field.signedMillimeters();
    }
    private static SnowContactRelief.Prepared[] prepareCollisionPressure(List<SnowSurfacePayload.Mark> contacts,WorkerSampling sampling){
        if(contacts.isEmpty())return NO_CONTACT_PRESSURE;
        var pressure=new SnowContactRelief.Prepared[contacts.size()];
        for(int i=0;i<pressure.length;i++)pressure[i]=sampling.prepared(contacts.get(i));
        return pressure;
    }
    private static double signedDepression(List<SnowSurfacePayload.Mark> contacts,double x,double z,WorkerSampling worker){
        if(worker!=null)return worker.relief(contacts,x,z);
        var field=new SnowContactRelief.FieldAccumulator();
        for(var mark:contacts)field.addDepression(prepareContact(mark),x,z);
        if(!field.fullyCovered())for(var mark:contacts)field.addRim(prepareContact(mark),x,z);
        return field.signedMillimeters();
    }
    public static void init(){
        if(initialized)return;initialized=true;SnowSurfaceChunkInbox.init();
        SnowSurfaceRuntime.installClientView(new SnowSurfaceRuntime.SurfaceView(){
            @Override public double deltaMillimeters(BlockPos p){return delta(p);}
            @Override public SnowHostGeometry.Cell geometry(BlockPos owner){
                Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
                return cell==null?null:cell.geometry;
            }
            @Override public double[] fineCornersMillimeters(BlockPos owner){
                Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
                return cell==null?null:cell.fineCorners;
            }
            @Override public SnowSurfacePayload.Column pressureColumn(BlockPos owner){return SnowSurfaceClient.pressureColumn(owner);}
            @Override public Object contactSnapshotKey(BlockPos owner){
                Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
                return cell==null?List.of():cell.contacts;
            }
            @Override public double depressionMillimeters(double x,double z){return depression(x,z);}
            @Override public double depressionMillimeters(BlockPos owner,double x,double z){return depression(owner,x,z);}
        });
        ClientPlayNetworking.registerGlobalReceiver(SnowSurfacePayload.TYPE,(payload,context)->incoming=payload);
        ClientPlayConnectionEvents.INIT.register((handler,client)->{SnowSurfaceChunkInbox.reset();reset();});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{SnowSurfaceChunkInbox.reset();reset();});
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->{
            SnowSurfaceChunkInbox.reset();reset();
            if(tessellator!=null){tessellator.shutdownNow();tessellator=null;}
            if(contactTessellator!=null){contactTessellator.shutdownNow();contactTessellator=null;}
        });
        ClientTickEvents.END_CLIENT_TICK.register(SnowSurfaceClient::tick);
        LevelExtractionEvents.END_EXTRACTION.register(context->{
            if(context.level()!=world||context.levelState().cameraRenderState.isPanoramicMode){extractedFrame=null;context.levelState().setData(FRAME,null);return;}
            contactFrameStartedNanos=System.nanoTime();
            try{if(!SnowLocalContacts.applyCaptured(context.levelState()))flushPressureChanges();}
            finally{contactFrameStartedNanos=0;}
            var all=sceneMeshes();var visible=new ArrayList<Mesh>();var camera=context.levelState().cameraRenderState;
            for(Mesh mesh:all)if(camera.cullFrustum.isVisible(mesh.bounds))visible.add(mesh);
            Frame frame=new Frame(epoch,++frameSequence,all,List.copyOf(visible));extractedFrame=frame;
            observeLocalEligibility(frame);
            context.levelState().setData(FRAME,frame);
        });
        LevelRenderEvents.END_MAIN.register(SnowSurfaceClient::draw);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(id("snow_contacts"),(ResourceManagerReloadListener)manager->reloadGpu());
    }
    public static Metrics metrics(){
        int cells=0;long triangles=0;for(Mesh mesh:meshes.values()){cells+=mesh.cells.size();triangles+=mesh.vertices/3;}
        int markCount=marks.size();
        if(!readyFrames.isEmpty()){var contacts=new HashSet<MarkKey>();for(var mesh:meshes.values())for(var mark:mesh.contacts)contacts.add(MarkKey.of(mark));markCount=contacts.size();}
        return new Metrics(markCount,drawn,uploads,frames,!gpu.isEmpty(),cells,triangles,suppressed.get(),builds,
                snapshotNanos,workerNanos,discardedBuilds,activeBuild!=null||preparing!=null);
    }
    /** Native world edits invalidate the finite neighbouring dynamic caps, never the whole region. */
    public static void blockChanged(int x0,int y0,int z0,int x1,int y1,int z1){
        Minecraft client=Minecraft.getInstance();
        if(!client.isSameThread()){client.execute(()->blockChanged(x0,y0,z0,x1,y1,z1));return;}
        if(world==null||columns.isEmpty())return;
        int cx0=(Math.min(x0,x1)-2)>>4,cx1=(Math.max(x0,x1)+2)>>4;
        int cz0=(Math.min(z0,z1)-2)>>4,cz1=(Math.max(z0,z1)+2)>>4;
        for(long key:columns.keySet()){
            int cx=(int)key,cz=(int)(key>>32);if(cx>=cx0&&cx<=cx1&&cz>=cz0&&cz<=cz1){
                geometryRevisions.put(key,++geometryRevision);markDirty(key,DirtyCause.NATIVE);
                if(activeBuild!=null&&activeBuild.dependencies.contains(key))activeBuild.cancelled=true;
                if(preparing!=null&&preparing.frames.containsKey(key))preparing.cancelled=true;
            }
        }
    }
    /** Only a column on a chunk edge can change the adjacent chunk's shared corner field. */
    private static void dirtyColumn(int x,int z,DirtyCause cause){
        int cx=x>>4,cz=z>>4,x0=(x&15)==0?-1:0,x1=(x&15)==15?1:0,z0=(z&15)==0?-1:0,z1=(z&15)==15?1:0;
        for(int dx=x0;dx<=x1;dx++)for(int dz=z0;dz<=z1;dz++)markDirty(chunkKey(cx+dx,cz+dz),cause);
    }
    private static void dirtyMark(SnowSurfacePayload.Mark mark){
        double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
        int x0=((int)Math.floor(mark.x()-radius))>>4,x1=((int)Math.floor(mark.x()+radius))>>4;
        int z0=((int)Math.floor(mark.z()-radius))>>4,z1=((int)Math.floor(mark.z()+radius))>>4;
        for(int cx=x0;cx<=x1;cx++)for(int cz=z0;cz<=z1;cz++){
            long key=chunkKey(cx,cz);if(columns.containsKey(key))markDirty(key,DirtyCause.AUTHORITY_WEATHER);
        }
    }
    /** Duplicate contacts at the same placement contribute only their deepest remaining impression. */
    private static Map<MarkKey,SnowSurfacePayload.Mark> indexedMarks(List<SnowSurfacePayload.Mark> source){
        var result=new HashMap<MarkKey,SnowSurfacePayload.Mark>();
        for(var mark:source){
            var key=MarkKey.of(mark);var previous=result.get(key);
            if(previous==null||mark.depthMillimeters()>previous.depthMillimeters())result.put(key,mark);
        }
        return result;
    }
    /** Remaining depth is compared with each actual mesh, so small fills accumulate across packets. */
    private static void dirtyAgedMark(MarkKey identity,SnowSurfacePayload.Mark mark,
            Map<Long,Map<MarkKey,SnowSurfacePayload.Mark>> publishedMarks,ContactDepthIndexes depthIndexes,boolean addition,boolean queued){
        double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
        int x0=((int)Math.floor(mark.x()-radius))>>4,x1=((int)Math.floor(mark.x()+radius))>>4;
        int z0=((int)Math.floor(mark.z()-radius))>>4,z1=((int)Math.floor(mark.z()+radius))>>4;
        for(int cx=x0;cx<=x1;cx++)for(int cz=z0;cz<=z1;cz++){
            long key=chunkKey(cx,cz);if(!columns.containsKey(key)||dirty.contains(key))continue;
            Mesh mesh=meshes.get(key);if(mesh==null){markDirty(key,DirtyCause.AUTHORITY_POSITIVE);continue;}
            // The urgent render pass owns this exact addition/increase until it publishes
            // a draft or leaves a full-build fallback. Other dirty causes remain queued.
            if(queued&&pendingPressureChunks.contains(key)&&!chunkHandoff(key))continue;
            if(addition){
                // The mesh-wide ledger may contain a mark that no visible tile
                // has built. Defer only when the actual compatible owners have it.
                if(!exactDisplayedPressure(mesh,mark,identity,depthIndexes))markDirty(key,DirtyCause.AUTHORITY_POSITIVE);
                continue;
            }
            var recorded=publishedMarks.get(key);
            if(recorded==null){recorded=indexedMarks(mesh.contacts);publishedMarks.put(key,recorded);}
            var previous=recorded.get(identity);
            if(previous==null||Math.abs(mark.depthMillimeters()-previous.depthMillimeters())>=MESH_DELTA_STEP_MM
                    ||(mark.depthMillimeters()<=0&&previous.depthMillimeters()>0))
                markDirty(key,previous==null||mark.depthMillimeters()>previous.depthMillimeters()
                        ?DirtyCause.AUTHORITY_POSITIVE:DirtyCause.AUTHORITY_WEATHER);
        }
    }
    /** Immutable contact lists are indexed at most once during the current proof pass. */
    private static final class ContactDepthIndexes {
        private IdentityHashMap<List<SnowSurfacePayload.Mark>,Map<MarkKey,SnowSurfacePayload.Mark>> byList;
        Map<MarkKey,SnowSurfacePayload.Mark> index(List<SnowSurfacePayload.Mark> contacts){
            if(byList==null)byList=new IdentityHashMap<>();
            return byList.computeIfAbsent(contacts,SnowSurfaceClient::indexedMarks);
        }
        boolean exact(List<SnowSurfacePayload.Mark> contacts,MarkKey identity,float depth){
            var deepest=index(contacts).get(identity);
            return deepest!=null&&Float.compare(deepest.depthMillimeters(),depth)==0;
        }
    }
    private static final int LINEAR_EXACT_CONTACTS=256;
    private static boolean exactContactDepth(List<SnowSurfacePayload.Mark> contacts,MarkKey identity,float depth,ContactDepthIndexes indexes){
        if(contacts.size()>LINEAR_EXACT_CONTACTS)return indexes.exact(contacts,identity,depth);
        boolean present=false;
        for(var mark:contacts)if(identity.matches(mark)){
            int comparison=Float.compare(mark.depthMillimeters(),depth);
            if(comparison>0)return false;
            if(comparison==0)present=true;
        }
        return present;
    }
    private static boolean exactDisplayedPressure(Mesh mesh,SnowSurfacePayload.Mark mark,MarkKey identity,ContactDepthIndexes depthIndexes){
        int y=mark.ownerY();
        if(mesh==null||y==Integer.MIN_VALUE||mark.depthMillimeters()<=0)return false;
        int cx=(int)mesh.key,cz=(int)(mesh.key>>32);
        // Every matching owner has the same Y and therefore the same native section.
        if(handoffs.containsKey(SectionPos.asLong(cx,y>>4,cz)))return false;
        double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
        int x0=Math.max(cx*16,(int)Math.ceil(mark.x()-radius)-1),x1=Math.min(cx*16+15,(int)Math.floor(mark.x()+radius));
        int z0=Math.max(cz*16,(int)Math.ceil(mark.z()-radius)-1),z1=Math.min(cz*16+15,(int)Math.floor(mark.z()+radius));
        if(x0>x1||z0>z1)return false;
        var kernel=prepareContact(mark);
        boolean present=false;
        for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
            var shown=mesh.cells.get(BlockPos.asLong(x,y,z));
            if(shown==null||!kernel.influenceIntersectsBounds(x,z,x+1,z+1)
                    ||!SnowHostGeometry.contactMatches(shown.geometry,shown.fineCorners,
                            mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),y))continue;
            if(!exactContactDepth(shown.contacts,identity,mark.depthMillimeters(),depthIndexes))return false;
            present=true;
        }
        return present;
    }
    private static void dirtyChangedMarks(List<SnowSurfacePayload.Mark> before,List<SnowSurfacePayload.Mark> after,boolean urgentReady){
        var oldMarks=indexedMarks(before);var newMarks=indexedMarks(after);
        var publishedMarks=new HashMap<Long,Map<MarkKey,SnowSurfacePayload.Mark>>();
        var depthIndexes=new ContactDepthIndexes();
        for(var entry:oldMarks.entrySet())if(!newMarks.containsKey(entry.getKey()))dirtyMark(entry.getValue());
        for(var entry:newMarks.entrySet()){
            var identity=entry.getKey();var mark=entry.getValue();var previous=oldMarks.get(identity);
            boolean queued=false;
            if(urgentReady&&mark.depthMillimeters()>0
                    &&(previous==null||mark.depthMillimeters()-previous.depthMillimeters()>=MESH_DELTA_STEP_MM)){
                var pending=urgentPressureAuthority.get(identity);
                queued=pending!=null&&pending.mark.depthMillimeters()>=mark.depthMillimeters();
            }
            dirtyAgedMark(identity,mark,publishedMarks,depthIndexes,previous==null,queued);
        }
    }
    private static boolean loaded(long key){
        return world.getChunkSource().getChunk((int)key,(int)(key>>32),ChunkStatus.FULL,false)!=null;
    }
    private static SnowSurfacePayload.Column pressureColumn(BlockPos owner){
        var chunk=columns.get(chunkKey(owner));var current=chunk==null?null:chunk.get(owner.asLong());
        if(current!=null)return current;
        Mesh mesh=displayedMesh(owner);CellData shown=mesh==null?null:mesh.cells.get(owner.asLong());
        return shown==null?null:shown.column;
    }
    private static float physicalDepth(SnowSurfacePayload.Column column){
        return column==null?0:Math.max(0,column.layers()*125f+column.deltaMillimeters());
    }
    /** Local pressure reads precisely the cap already used by client collision and display. */
    static LocalContactCandidate localContact(PlayerSnowFootprints.FootPlant plant){
        if(world==null||Minecraft.getInstance().level!=world)return null;
        var support=SnowSurfaceRuntime.captureRenderedSupport(world,plant.x(),plant.y(),plant.z());
        if(support==null)return null;
        var geometry=support.geometry();double top=support.skinY();
        var press=support.sample(plant.channel(),plant.pressure());
        if(press==null)return null;
        var owner=geometry.pos();
        double x=SnowSurface.contactWorldCoordinate(owner.getX()>>4,SnowSurface.contactChunkCoordinate(plant.x()));
        double z=SnowSurface.contactWorldCoordinate(owner.getZ()>>4,SnowSurface.contactChunkCoordinate(plant.z()));
        // Float rounding can cross an internal block edge. Keep the original support in that case.
        if(Math.floor(x)!=owner.getX()||Math.floor(z)!=owner.getZ()){x=plant.x();z=plant.z();}
        var mark=new SnowSurfacePayload.Mark(x,(float)top,z,plant.yaw(),plant.width(),plant.length(),
                1,false,press.depthMillimeters(),plant.soleProfile(),owner.getY());
        return new LocalContactCandidate(mark,plant.x(),plant.z(),plant.channel(),support);
    }
    private static long contactTick(){
        var player=Minecraft.getInstance().player;return player==null?0:player.tickCount;
    }
    /** Fixed stamps retain their horizontal placement and shape while weather changes skin Y. */
    private static SnowSurfacePayload.Mark shownFixedPlacement(SnowSurfacePayload.Mark requested){
        BlockPos owner=new BlockPos((int)Math.floor(requested.x()),requested.ownerY(),(int)Math.floor(requested.z()));
        Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(owner.asLong());
        if(cell==null)return null;
        for(var prior:cell.contacts)if(sameFixedPlacement(prior,requested))return prior;
        return null;
    }
    private static boolean sameFixedPlacement(SnowSurfacePayload.Mark a,SnowSurfacePayload.Mark b){
        return a.ownerY()==b.ownerY()&&a.x()==b.x()&&a.z()==b.z()
                &&a.yaw()==b.yaw()&&a.width()==b.width()&&a.length()==b.length()
                &&a.vehicle()==b.vehicle()&&a.soleProfile()==b.soleProfile();
    }
    /** Tick-thread contact cells bypass both the streaming queue and the full-chunk worker. */
    static void applyLocalContacts(List<LocalContactCandidate> contacts){
        if(world==null||contacts.isEmpty())return;
        if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("Local snow contacts require client tick");
        var affected=new HashSet<Long>();var lightingChanges=new ArrayList<SnowSurfacePayload.Mark>();
        contact: for(var candidate:contacts){
            var requested=candidate.mark();double x=candidate.rawX(),z=candidate.rawZ();
            // Already shown fixed contacts also include weathered authoritative marks.
            // Do not locally reopen them and wait for an authority depth which will never arrive.
            if(shownFixedPlacement(requested)!=null)continue;
            var identity=MarkKey.of(requested);var previous=localContacts.get(identity);
            if(previous==null)for(var pending:localContacts.values())
                if(sameFixedPlacement(pending.mark,requested)){previous=pending;break;}
            float depth=requested.depthMillimeters();
            // A fixed placement is immutable. Repeating it cannot inherit an older cavity,
            // deepen the template or reset its weather age while authoritative storage catches up.
            if(previous!=null)continue;
            BlockPos owner=new BlockPos((int)Math.floor(x),requested.ownerY(),(int)Math.floor(z));
            float physical=physicalDepth(pressureColumn(owner));
            if(physical<=0)continue;
            depth=Math.min(depth,Math.min(750,physical*.9f));
            var mark=depth==requested.depthMillimeters()?requested:new SnowSurfacePayload.Mark(requested.x(),requested.y(),requested.z(),requested.yaw(),
                    requested.width(),requested.length(),requested.strength(),requested.vehicle(),depth,requested.soleProfile(),requested.ownerY());
            while(localContacts.size()>=MAX_LOCAL_CONTACTS&&!localContacts.containsKey(identity)){
                reconcileLocalContacts(); // shares the existing bounded proof budget
                if(localContacts.size()<MAX_LOCAL_CONTACTS)break;
                Map.Entry<MarkKey,LocalContact> victim=null;
                for(var entry:localContacts.entrySet())if(localUnloaded(entry.getValue())){victim=entry;break;}
                if(victim==null){
                    // Keep every existing unresolved cut. The caller still submits the
                    // original plant to authoritative WKE after this presentation stage.
                    capacityUnconfirmedContacts++;localEventDrops++;deferPressure(requested,false);
                    continue contact;
                }
                contactChunks(victim.getValue().mark,affected);lightingChanges.add(victim.getValue().mark);
                retireLocal(victim.getValue(),localUnloaded(victim.getValue())?"unloaded":"canonical");localContacts.remove(victim.getKey());
            }
            retiredLocalContacts.remove(identity);weatherRetiredLocals.remove(identity);
            var local=new LocalContact(++localGenerated,mark,candidate.channel());local.owners=contactOwners(mark);
            localContacts.put(identity,local);localEvents.addLast(local);contactChunks(mark,affected);lightingChanges.add(mark);
            while(localEvents.size()>MAX_LOCAL_EVENTS){
                var oldest=localEvents.removeFirst();if(oldest.drawnTick<0)localEventDrops++;
            }
        }
        var received=takePressureChanges(affected,lightingChanges);
        if(!affected.isEmpty()){
            long now=contactTick();
            schedulePressureFollowups(affected,refreshPublishedContacts(affected,List.copyOf(lightingChanges),now),now);
        }
        commitPressureChanges(received);
        // Moving contacts remain anchored. Matching the pressure at a new centre cannot
        // prove that the previous heel, toe, shoulder or swept edge still exists on the server.
    }
    private static void contactChunks(SnowSurfacePayload.Mark mark,Set<Long> keys){
        double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters())+.75;
        int x0=((int)Math.floor(mark.x()-radius))>>4,x1=((int)Math.floor(mark.x()+radius))>>4;
        int z0=((int)Math.floor(mark.z()-radius))>>4,z1=((int)Math.floor(mark.z()+radius))>>4;
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)keys.add(chunkKey(x,z));
    }
    private static boolean localUnloaded(LocalContact local){
        if(local.owners.isEmpty())return !columns.containsKey(chunkKey((int)Math.floor(local.mark.x())>>4,(int)Math.floor(local.mark.z())>>4));
        for(long position:local.owners)if(columns.containsKey(chunkKey(BlockPos.of(position))))return false;
        return true;
    }
    /** An explicit installed physical field may remove the snow/support altogether. */
    private static boolean physicalSnowGone(LocalContact local){
        if(local.owners.isEmpty())return false;
        for(long position:local.owners){
            var owner=BlockPos.of(position);long key=chunkKey(owner);var source=columns.get(key);
            if(source==null||!readyFrames.containsKey(key))return false;
            var column=source.get(position);if(column!=null&&physicalDepth(column)>0)return false;
        }
        return true;
    }
    private record WeatherBucket(long x,long z,int ownerY) {}
    private record WeatherLookup(SnowSurfacePayload.Mark mark,boolean complete) {}
    private record WeatherAuthorityIndex(SnowSurfaceChunkPayload.Chunk frame,Map<WeatherBucket,List<SnowSurfacePayload.Mark>> byBucket) {}
    private static final Map<Long,WeatherAuthorityIndex> weatherAuthorityIndexes=new LinkedHashMap<>(8,.75f,true);
    private static final Set<MarkKey> weatherRetiredLocals=new HashSet<>();
    private static long weatherPredictionUpdates,weatherPredictionBurials,weatherPredictionUnmatched;
    private static SnowSurfaceChunkPayload.Chunk weatherAuthority(SnowSurfacePayload.Mark mark){
        return readyFrames.get(chunkKey(((int)Math.floor(mark.x()))>>4,((int)Math.floor(mark.z()))>>4));
    }
    private static boolean sameWeatherPlacement(SnowSurfacePayload.Mark shown,SnowSurfacePayload.Mark saved){
        return shown.ownerY()!=Integer.MIN_VALUE&&shown.ownerY()==saved.ownerY()&&shown.vehicle()==saved.vehicle()
                &&shown.soleProfile()==saved.soleProfile()
                &&Math.abs(shown.x()-saved.x())<=4*Math.max(Math.ulp(shown.x()),Math.ulp(saved.x()))
                &&Math.abs(shown.z()-saved.z())<=4*Math.max(Math.ulp(shown.z()),Math.ulp(saved.z()))
                &&Math.abs(shown.y()-saved.y())<=4*Math.max(Math.ulp(shown.y()),Math.ulp(saved.y()))
                &&Math.abs(shown.yaw()-saved.yaw())<=4*Math.max(Math.ulp(shown.yaw()),Math.ulp(saved.yaw()))
                &&Math.abs(shown.width()-saved.width())<=4*Math.max(Math.ulp(shown.width()),Math.ulp(saved.width()))
                &&Math.abs(shown.length()-saved.length())<=4*Math.max(Math.ulp(shown.length()),Math.ulp(saved.length()));
    }
    private static long weatherBucket(double coordinate){return (long)Math.floor(coordinate*1024);}
    private static WeatherLookup weatherPlacement(SnowSurfaceChunkPayload.Chunk frame,SnowSurfacePayload.Mark shown){
        if(shown.ownerY()==Integer.MIN_VALUE||frame.marks().size()>20_000)return new WeatherLookup(null,false);
        var index=weatherAuthorityIndexes.get(frame.key());
        if(index==null||index.frame!=frame){
            // Only a broad phase: actual identity below still tests original codec values.
            var buckets=new HashMap<WeatherBucket,List<SnowSurfacePayload.Mark>>();
            for(var mark:frame.marks()){
                var bucket=new WeatherBucket(weatherBucket(mark.x()),weatherBucket(mark.z()),mark.ownerY());
                buckets.computeIfAbsent(bucket,ignored->new ArrayList<>()).add(mark);
            }
            index=new WeatherAuthorityIndex(frame,buckets);weatherAuthorityIndexes.put(frame.key(),index);
            while(weatherAuthorityIndexes.size()>8)weatherAuthorityIndexes.remove(weatherAuthorityIndexes.keySet().iterator().next());
        }
        long x=weatherBucket(shown.x()),z=weatherBucket(shown.z());SnowSurfacePayload.Mark found=null;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            var bucket=index.byBucket.get(new WeatherBucket(x+dx,z+dz,shown.ownerY()));if(bucket==null)continue;
            for(var mark:bucket)if(sameWeatherPlacement(shown,mark)){
                if(found!=null&&!MarkKey.of(found).equals(MarkKey.of(mark)))return new WeatherLookup(null,false);
                if(found==null||mark.depthMillimeters()>found.depthMillimeters())found=mark;
            }
        }
        return new WeatherLookup(found,true);
    }
    /** Strength is effective/original in the complete server frame, not a local pressure weight. */
    private static double observedPhysicalCover(SnowSurfacePayload.Mark mark){
        if(mark==null||!(mark.strength()>0&&mark.strength()<=1)||!(mark.depthMillimeters()>0))return Double.NaN;
        double cover=(double)mark.depthMillimeters()*(1.0/mark.strength()-1);
        return Double.isFinite(cover)&&cover>=0?cover:Double.NaN;
    }
    private static SnowSurfacePayload.Mark remainingMark(LocalContact local){return local.weatherMark==null?local.mark:local.weatherMark;}
    /** Return true only for explicitly measured cover, or absence following an already
     * matched almost-filled cut and a newer complete weather/source observation. */
    private static boolean observePredictionWeather(LocalContact local){
        if(weatherChecksThisTick>=CANONICAL_CHECKS_PER_TICK)return false;
        var frame=weatherAuthority(local.mark);if(frame==null||frame.revision()<=local.weatherSeenRevision)return false;
        weatherChecksThisTick++;local.weatherSeenRevision=frame.revision();
        var lookup=weatherPlacement(frame,local.mark);
        if(!lookup.complete){weatherPredictionUnmatched++;return false;}
        var saved=lookup.mark;
        if(saved==null){
            boolean buried=local.weatherMatchedRevision>=0&&frame.revision()>local.weatherMatchedRevision&&frame.weatherTick()>local.weatherMatchedTick
                    &&local.mark.depthMillimeters()-local.physicalCoverMillimeters<=CANONICAL_PRESSURE_TOLERANCE_MM;
            if(buried)weatherPredictionBurials++;else weatherPredictionUnmatched++;
            return buried;
        }
        double cover=observedPhysicalCover(saved),original=saved.depthMillimeters()+cover;
        if(!Double.isFinite(cover)||original<local.mark.depthMillimeters()-CANONICAL_PRESSURE_TOLERANCE_MM){weatherPredictionUnmatched++;return false;}
        if(local.weatherMatchedRevision<0&&(!local.weatherBirthKnown||local.weatherAtBirth!=null)){
            double before=observedPhysicalCover(local.weatherAtBirth);
            boolean changedGeneration=Double.isFinite(before)&&(cover+1e-6<before
                    ||Math.abs(original-(local.weatherAtBirth.depthMillimeters()+before))>=MESH_DELTA_STEP_MM);
            // Advancing an old same-placement record is not acceptance of a newer plant.
            // A reset cover, changed original pressure, or still-full actual cut is evidence.
            if(!changedGeneration&&!(cover<=MESH_DELTA_STEP_MM
                    &&saved.depthMillimeters()>=local.mark.depthMillimeters()-MESH_DELTA_STEP_MM)){
                weatherPredictionUnmatched++;return false;
            }
        }
        local.weatherMatchedRevision=frame.revision();local.weatherMatchedTick=frame.weatherTick();
        local.physicalCoverMillimeters=Math.max(local.physicalCoverMillimeters,cover);
        float remaining=(float)Math.max(0,local.mark.depthMillimeters()-local.physicalCoverMillimeters);
        if(remaining<=0){weatherPredictionBurials++;return true;}
        if(remainingMark(local).depthMillimeters()-remaining>=MESH_DELTA_STEP_MM){
            var mark=local.mark;
            local.weatherMark=new SnowSurfacePayload.Mark(mark.x(),mark.y(),mark.z(),mark.yaw(),mark.width(),mark.length(),
                    mark.strength(),mark.vehicle(),remaining,mark.soleProfile(),mark.ownerY());
            local.canonicalStamps=Map.of();local.canonicalPoints=null;weatherPredictionUpdates++;deferPressure(mark,true);
        }
        return false;
    }
    /** Real profile probes, including the sole contour and collapsed shoulder. This is sampled
     * agreement with the actual displayed canonical field, never a continuous coverage proof. */
    private static double[] canonicalPoints(SnowSurfacePayload.Mark mark){
        var points=new LinkedHashSet<FeaturePoint>();double angle=Math.toRadians(mark.yaw()),c=Math.cos(angle),sn=Math.sin(angle);
        java.util.function.BiConsumer<Double,Double> add=(x,z)->points.add(new FeaturePoint(mark.x()+x*c-z*sn,mark.z()+x*sn+z*c));
        add.accept(0.0,0.0);add.accept(0.0,-mark.length()*.2);add.accept(0.0,mark.length()*.2);
        if(mark.vehicle()){
            for(int z=-6;z<=6;z++)for(int x=-4;x<=4;x++)add.accept(x*mark.width()/8.0,z*mark.length()/12.0);
        }else{
            var outline=SoleProfiles.outline(mark.soleProfile());double shoulder=SnowContactRelief.shoulderWidthMeters(mark.depthMillimeters());
            for(int i=0;i<outline.length;i++){
                var a=outline[i];var b=outline[(i+1)%outline.length];var previous=outline[(i+outline.length-1)%outline.length];
                double ax=a[0]*mark.width()*.5,az=a[1]*mark.length()*.5,bx=b[0]*mark.width()*.5,bz=b[1]*mark.length()*.5;
                add.accept(ax*.5,az*.5);add.accept(ax,az);add.accept((ax+bx)*.5,(az+bz)*.5);
                double dx=bx-ax,dz=bz-az,n=Math.hypot(dx,dz),nx=-dz/n,nz=dx/n;
                double px=ax-previous[0]*mark.width()*.5,pz=az-previous[1]*mark.length()*.5,pn=Math.hypot(px,pz);
                double vx=nx-pz/pn,vz=nz+px/pn,vn=Math.hypot(vx,vz);
                for(int step=1;step<=3;step++){
                    double offset=shoulder*step*.25;
                    add.accept((ax+bx)*.5+nx*offset,(az+bz)*.5+nz*offset);
                    if(vn>1e-12)add.accept(ax+vx/vn*offset,az+vz/vn*offset);
                }
            }
        }
        double[] result=new double[points.size()*3];int at=0;var kernel=prepareContact(mark);
        var field=new SnowContactRelief.FieldAccumulator();
        for(var point:points){
            field.reset();field.add(kernel,point.x,point.z);
            result[at++]=point.x;result[at++]=point.z;result[at++]=Math.max(0,field.signedMillimeters());
        }
        return result;
    }
    private static CanonicalOwner canonicalOwner(CellData cell,Map<MarkKey,SnowSurfacePayload.Mark> authority){
        var kernels=new ArrayList<SnowContactRelief.Prepared>();
        for(var visible:cell.contacts){
            var real=authority.get(MarkKey.of(visible));
            if(real!=null&&Math.abs(real.depthMillimeters()-visible.depthMillimeters())<MESH_DELTA_STEP_MM)kernels.add(prepareContact(visible));
        }
        kernels.sort(Comparator.comparingDouble(SnowContactRelief.Prepared::maxDepressionMillimeters).reversed());
        return new CanonicalOwner(cell,kernels.toArray(SnowContactRelief.Prepared[]::new));
    }
    private static double clampedPressure(CellData cell,double x,double z,double pressure){
        double skin=SnowHostGeometry.height(cell.geometry,x,z,cell.fineCorners);
        return !Double.isFinite(skin)?Double.NaN:(skin-SnowHostGeometry.clampCompactedHeight(cell.geometry,x,z,skin,pressure))*1000;
    }
    private static boolean canonicalCoverage(LocalContact local,Map<Long,CanonicalOwner> fields,ContactDepthIndexes depthIndexes){
        if(local.appliedTick<0||local.owners.isEmpty())return false;
        boolean unchanged=local.canonicalStamps.size()==local.owners.size();
        if(unchanged)for(long position:local.owners){
            var owner=BlockPos.of(position);Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(position);
            var previous=local.canonicalStamps.get(position);var authority=committedAuthoritative.getOrDefault(chunkKey(owner),List.of());
            if(previous==null||previous.authority!=authority||previous.visible!=(cell==null?null:cell.contacts)
                    ||previous.geometry!=(cell==null?null:cell.geometry)||previous.corners!=(cell==null?null:cell.fineCorners)){unchanged=false;break;}
        }
        if(unchanged){canonicalCacheHits++;return local.canonicalCovered;}
        var stamps=new HashMap<Long,CanonicalStamp>();
        for(long position:local.owners){
            var owner=BlockPos.of(position);Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(position);
            stamps.put(position,new CanonicalStamp(committedAuthoritative.getOrDefault(chunkKey(owner),List.of()),
                    cell==null?null:cell.contacts,cell==null?null:cell.geometry,cell==null?null:cell.fineCorners));
        }
        local.canonicalStamps=Map.copyOf(stamps);local.canonicalCovered=false;local.canonicalSources=0;local.canonicalMaximumDepth=0;
        local.canonicalDeficit=local.canonicalExpected=local.canonicalActual=local.canonicalRequired=0;local.canonicalCoreProbe=false;
        local.canonicalChecks++;canonicalChecks++;
        long started=System.nanoTime();if(local.canonicalPoints==null)local.canonicalPoints=canonicalPoints(remainingMark(local));
        boolean tested=false,present=false;var value=new SnowContactRelief.FieldAccumulator();
        var prediction=prepareContact(remainingMark(local));
        try{
            for(long position:local.owners){
                var owner=BlockPos.of(position);Mesh mesh=displayedMesh(owner);CellData cell=mesh==null?null:mesh.cells.get(position);
                if(cell==null)return false;
                var field=fields.computeIfAbsent(position,ignored->canonicalOwner(cell,depthIndexes.index(stamps.get(position).authority)));
                local.canonicalSources+=field.kernels.length;
                for(var kernel:field.kernels)local.canonicalMaximumDepth=Math.max(local.canonicalMaximumDepth,kernel.maxDepressionMillimeters());
                for(int i=0;i<local.canonicalPoints.length;i+=3){
                    double x=local.canonicalPoints[i],z=local.canonicalPoints[i+1],raw=local.canonicalPoints[i+2];
                    var ownerPosition=cell.geometry.pos();
                    if(raw<=0||x<ownerPosition.getX()-1e-9||x>ownerPosition.getX()+1+1e-9
                            ||z<ownerPosition.getZ()-1e-9||z>ownerPosition.getZ()+1+1e-9||!cell.geometry.contains(x,z))continue;
                    double original=clampedPressure(cell,x,z,raw);if(!Double.isFinite(original)||original<=1e-6)continue;
                    tested=true;value.reset();
                    for(var kernel:field.kernels){
                        if(value.fullyCovered()&&value.depressionMillimeters()>=kernel.maxDepressionMillimeters())break;
                        value.addDepression(kernel,x,z);
                    }
                    if(!value.fullyCovered())for(var kernel:field.kernels)value.addRim(kernel,x,z);
                    double actual=clampedPressure(cell,x,z,value.signedMillimeters());canonicalProbeSamples++;present|=actual>1e-6;
                    // A neighbouring bank already shapes the displayed shoulder. An isolated
                    // prediction is not the before-removal field, even when its exact canonical
                    // counterpart exists. Preserve signed bank heights as well as cavity pressure.
                    value.add(prediction,x,z);
                    double expected=clampedPressure(cell,x,z,value.signedMillimeters());
                    double deficit=Math.abs(expected-actual);boolean core=i<9;
                    // The first three probes retain the original centre/heel/toe floor independently.
                    double required=core?Math.max(original-CANONICAL_PRESSURE_TOLERANCE_MM,original*.5):expected-CANONICAL_PRESSURE_TOLERANCE_MM;
                    if(deficit>local.canonicalDeficit){local.canonicalDeficit=deficit;local.canonicalExpected=expected;local.canonicalActual=actual;
                        local.canonicalRequired=required;local.canonicalCoreProbe=core;local.canonicalX=x;local.canonicalZ=z;}
                    if(!com.winterkore.engine.surface.geometry.SnowContactHandover.retainsSample(
                            actual,expected,original,core,CANONICAL_PRESSURE_TOLERANCE_MM)){
                        local.canonicalDeficit=deficit;local.canonicalExpected=expected;local.canonicalActual=actual;local.canonicalRequired=required;
                        local.canonicalCoreProbe=core;local.canonicalX=x;local.canonicalZ=z;if(core)canonicalCoreProbeFailures++;return false;
                    }
                }
            }
            local.canonicalCovered=tested&&present;return local.canonicalCovered;
        }finally{canonicalWorstNanos=Math.max(canonicalWorstNanos,System.nanoTime()-started);}
    }
    private static Map<String,Object> canonicalBacklogSummary(){
        var samples=new ArrayList<Map<String,Object>>();
        for(var local:localContacts.values()){
            var mark=local.mark;var item=new LinkedHashMap<String,Object>();item.put("ageTicks",contactTick()-local.tick);
            item.put("x",mark.x());item.put("y",mark.y());item.put("z",mark.z());item.put("depthMillimeters",mark.depthMillimeters());item.put("profile",mark.soleProfile());
            item.put("canonicalSources",local.canonicalSources);item.put("maximumCanonicalDepthMillimeters",local.canonicalMaximumDepth);
            item.put("sampledDeficitMillimeters",local.canonicalDeficit);item.put("expectedMillimeters",local.canonicalExpected);item.put("actualCanonicalMillimeters",local.canonicalActual);
            item.put("requiredCanonicalMillimeters",local.canonicalRequired);item.put("coreProbe",local.canonicalCoreProbe);
            item.put("probeX",local.canonicalX);item.put("probeZ",local.canonicalZ);item.put("checks",local.canonicalChecks);samples.add(Map.copyOf(item));if(samples.size()==8)break;
        }
        return Map.of("active",localContacts.size(),"oldestUnconfirmed",List.copyOf(samples));
    }
    /** Retire only after actual ACK-selected canonical pressure retains the real profile region. */
    private static void reconcileLocalContacts(){
        long now=contactTick();if(now!=canonicalBudgetTick){canonicalBudgetTick=now;canonicalChecksThisTick=weatherChecksThisTick=0;}
        if(localContacts.isEmpty()||canonicalChecksThisTick>=CANONICAL_CHECKS_PER_TICK)return;
        var fields=new HashMap<Long,CanonicalOwner>();var depthIndexes=new ContactDepthIndexes();long start=canonicalCursor;
        // A failed oldest contact cannot monopolize the bounded verification budget.
        for(int pass=0;pass<2;pass++)for(var iterator=localContacts.entrySet().iterator();iterator.hasNext();){
            var local=iterator.next().getValue();if(pass==0?local.id<=start:local.id>start)continue;
            if(local.appliedTick<0||(local.drawnTick<0&&!local.eligibleAt.isEmpty()))continue;
            if(canonicalChecksThisTick>=CANONICAL_CHECKS_PER_TICK)return;
            canonicalCursor=local.id;
            if(observePredictionWeather(local)){retireLocal(local,"weather");iterator.remove();continue;}
            long before=canonicalChecks;boolean covered=canonicalCoverage(local,fields,depthIndexes);if(canonicalChecks!=before)canonicalChecksThisTick++;
            if(covered){retireLocal(local,"canonical",depthIndexes);iterator.remove();}
        }
    }
    private static void expireLocalContacts(){
        long now=contactTick();
        for(var iterator=localContacts.entrySet().iterator();iterator.hasNext();){
            var local=iterator.next().getValue();String reason=localUnloaded(local)?"unloaded":physicalSnowGone(local)?"physical":null;
            if(reason!=null){retireLocal(local,reason);iterator.remove();continue;}
            if(now-local.tick>LOCAL_CONTACT_TTL&&!local.ttlUnconfirmed){local.ttlUnconfirmed=true;ttlUnconfirmedContacts++;}
        }
        reconcileLocalContacts();pruneRetiredLocals();requestQuietRefinement();
    }
    private static void requestQuietRefinement(){
        long now=contactTick();var iterator=coarseUntil.entrySet().iterator();
        Set<Long> waiting=localFineFollowups.isEmpty()?null:new HashSet<>();
        while(iterator.hasNext()){
            var entry=iterator.next();BlockPos owner=BlockPos.of(entry.getKey());Mesh mesh=displayedMesh(owner);
            CellData cell=mesh==null?null:mesh.cells.get(entry.getKey());
            if(cell==null||!cell.approximate){
                fineQueued.remove(entry.getKey());forgetFineWitness(entry.getKey());iterator.remove();continue;
            }
            // Keep fine-only requests separate from native/authority/weather dirt.
            // They remain eligible until a published fine result clears them.
            if(now>=entry.getValue())fineQueued.add(entry.getKey());
            if(waiting!=null)waiting.add(chunkKey(owner));
        }
        if(waiting!=null)for(var followups=localFineFollowups.entrySet().iterator();followups.hasNext();){
            var followup=followups.next();long key=followup.getKey();
            if(dirty.contains(key)||pressureFollowupDue(followup.getValue(),waiting.contains(key),now)){
                if(columns.containsKey(key))markDirty(key,DirtyCause.PRESSURE_FOLLOWUP);
                followups.remove();
            }
        }
        if(!fineWitnesses.isEmpty())pruneFineWitnesses();
    }
    private static boolean forgetFineWitness(long position){
        if(fineWitnesses.isEmpty())return false;
        var old=fineWitnesses.remove(position);
        if(old==null)return false;
        fineWitnessBytes-=old.basis.bytes;
        return true;
    }
    private static void forgetFineChunk(long key,boolean includeDependents){
        if(fineWitnesses.isEmpty())return;
        for(var iterator=fineWitnesses.values().iterator();iterator.hasNext();){
            var basis=iterator.next().basis;
            if(basis.key==key||includeDependents&&basis.geometry.containsKey(key)){
                fineWitnessBytes-=basis.bytes;iterator.remove();
            }
        }
    }
    private static void pruneFineWitnesses(){
        for(var iterator=fineWitnesses.values().iterator();iterator.hasNext();){
            var witness=iterator.next();
            boolean rangeGone=witness.range.get()==null;
            if(rangeGone||dirty.contains(witness.basis.key)){
                if(rangeGone)fineWitnessDropsRange++;else fineWitnessDropsDirty++;
                fineWitnessBytes-=witness.basis.bytes;iterator.remove();
            }
        }
    }
    private static void retireLocal(LocalContact local,String reason){
        retireLocal(local,reason,null);
    }
    private static void retireLocal(LocalContact local,String reason,ContactDepthIndexes depthIndexes){
        local.retirementReason=reason;local.canonicalStamps=Map.of();local.canonicalPoints=null;
        if(reason.equals("canonical"))canonicalRetirements++;else if(reason.equals("unloaded"))unloadedRetirements++;else if(reason.equals("physical")||reason.equals("weather"))physicalRetirements++;
        var identity=MarkKey.of(local.mark);retiredLocalContacts.put(identity,local.mark);
        boolean weather=reason.equals("weather")||local.physicalCoverMillimeters>0;
        if(weather)weatherRetiredLocals.add(identity);
        // Removing a prediction can change pressure. An exact canonical handover
        // already backed by saved pressure keeps its queued local follow-up.
        if(reason.equals("canonical")&&!weather)deferCanonicalRetirement(local.mark,identity,depthIndexes==null?new ContactDepthIndexes():depthIndexes);
        else{dirtyMark(local.mark);queuePressureChanges(0,0,List.of(local.mark),List.of());}
        // A coherent older weather snapshot may finish. The dirty token already
        // points at the latest field, so burial cannot cancel every storm job.
        if(weather)return;
        // The identical publication guard would reject these observations anyway. Abort
        // obsolete work now, while recursive tessellation/packing still checks cancellation.
        if(activeBuild!=null&&!activeBuild.cancelled)for(var snapshot:activeBuild.snapshots)
            if(containsRetiredPrediction(snapshot)){activeBuild.cancelled=true;break;}
        if(preparing!=null&&!preparing.cancelled)for(var snapshot:preparing.snapshots)
            if(containsRetiredPrediction(snapshot)){preparing.cancelled=true;break;}
    }
    private static void deferCanonicalRetirement(SnowSurfacePayload.Mark mark,MarkKey identity,ContactDepthIndexes depthIndexes){
        var keys=new HashSet<Long>();contactChunks(mark,keys);
        for(long key:keys){
            if(!columns.containsKey(key)||dirty.contains(key))continue;
            // Exact already-published canonical pressure needs no new job after its follow-up finished.
            if(!depthIndexes.exact(committedAuthoritative.getOrDefault(key,List.of()),identity,mark.depthMillimeters())
                    ||!exactDisplayedPressure(meshes.get(key),mark,identity,depthIndexes))markDirty(key,DirtyCause.CANONICAL_RETIREMENT);
        }
        weatherPressureDeferrals++;
    }
    private static boolean containsRetiredPrediction(BuildSnapshot snapshot){
        if(retiredLocalContacts.isEmpty()&&localContacts.isEmpty())return false;
        var authority=indexedMarks(snapshot.authoritativeContacts);
        for(var mark:snapshot.contacts){
            var identity=MarkKey.of(mark);var real=authority.get(identity);
            if(real!=null&&Math.abs(real.depthMillimeters()-mark.depthMillimeters())<MESH_DELTA_STEP_MM)continue;
            if(retiredLocalContacts.containsKey(identity)&&!weatherRetiredLocals.contains(identity))return true;
            // A physical-column cap may lower a same-key prediction. An older worker must
            // not restore its deeper version after the short presentation draft expires.
            var current=localContacts.get(identity);
            if(current!=null&&mark.depthMillimeters()-current.mark.depthMillimeters()>=MESH_DELTA_STEP_MM)return true;
        }
        return false;
    }
    /** Tombstones live only as long as a real displayed/in-flight predicted occurrence can survive. */
    private static void pruneRetiredLocals(){
        if(retiredLocalContacts.isEmpty())return;var retained=new HashSet<MarkKey>();
        for(var mesh:sceneMeshes()){
            var authority=indexedMarks(committedAuthoritative.getOrDefault(mesh.key,List.of()));
            for(var mark:mesh.contacts){
                var identity=MarkKey.of(mark);if(!retiredLocalContacts.containsKey(identity))continue;
                var real=authority.get(identity);
                if(real==null||Math.abs(real.depthMillimeters()-mark.depthMillimeters())>=MESH_DELTA_STEP_MM)retained.add(identity);
            }
            // A carried cell can retain pressure independently of the mesh-wide index.
            // Keep its tombstone until real geometry, not just that index, has been repaired.
            for(var cell:mesh.cells.values())for(var mark:cell.contacts){
                var identity=MarkKey.of(mark);if(!retiredLocalContacts.containsKey(identity))continue;
                var real=authority.get(identity);
                if(real==null||Math.abs(real.depthMillimeters()-mark.depthMillimeters())>=MESH_DELTA_STEP_MM)retained.add(identity);
            }
        }
        if(activeBuild!=null)for(var snapshot:activeBuild.snapshots)retainSnapshotPredictions(snapshot,retained);
        if(preparing!=null)for(var snapshot:preparing.snapshots)retainSnapshotPredictions(snapshot,retained);
        retiredLocalContacts.keySet().retainAll(retained);weatherRetiredLocals.retainAll(retained);
    }
    private static void retainSnapshotPredictions(BuildSnapshot snapshot,Set<MarkKey> retained){
        var authority=indexedMarks(snapshot.authoritativeContacts);
        for(var mark:snapshot.contacts){var identity=MarkKey.of(mark);
            if(retiredLocalContacts.containsKey(identity)){
                var real=authority.get(identity);
                if(real==null||Math.abs(real.depthMillimeters()-mark.depthMillimeters())>=MESH_DELTA_STEP_MM)retained.add(identity);
            }
        }
    }
    private static Set<Long> contactOwners(SnowSurfacePayload.Mark mark){
        var result=new HashSet<Long>();var keys=new HashSet<Long>();contactChunks(mark,keys);
        var prepared=SnowContactRelief.prepare(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),mark.depthMillimeters(),mark.vehicle(),mark.soleProfile());
        double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
        for(var mesh:sceneMeshes())if(keys.contains(mesh.key))for(var cell:mesh.cells.values()){
            BlockPos owner=cell.geometry.pos();if(displayedMesh(owner)!=mesh)continue;
            // Match affecting(): the expanded SAT rectangle includes empty rounded-kernel corners.
            if(mark.x()+radius<owner.getX()||mark.x()-radius>owner.getX()+1||mark.z()+radius<owner.getZ()||mark.z()-radius>owner.getZ()+1)continue;
            if(prepared.influenceIntersectsBounds(owner.getX(),owner.getZ(),owner.getX()+1,owner.getZ()+1)
                    &&SnowHostGeometry.contactMatches(cell.geometry,cell.fineCorners,mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),mark.ownerY()))
                result.add(owner.asLong());
        }
        return Set.copyOf(result);
    }
    private static List<SnowSurfacePayload.Mark> overlayContacts(long key,List<SnowSurfacePayload.Mark> source,boolean authoritativeSource){
        var result=new LinkedHashMap<MarkKey,SnowSurfacePayload.Mark>();
        int x=((int)key)*16,z=((int)(key>>32))*16;
        Map<MarkKey,SnowSurfacePayload.Mark> authority=authoritativeSource?Map.of():indexedMarks(committedAuthoritative.getOrDefault(key,List.of()));
        for(var mark:source){
            var identity=MarkKey.of(mark);
            if(!authoritativeSource&&retiredLocalContacts.containsKey(identity)){
                var real=authority.get(identity);if(real!=null)result.put(identity,real);
            }else result.put(identity,mark);
        }
        for(var local:localContacts.values()){
            var mark=remainingMark(local);if(mark.depthMillimeters()<=0)continue;
            double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
            if(mark.x()+radius<x||mark.x()-radius>x+16||mark.z()+radius<z||mark.z()-radius>z+16)continue;
            var identity=MarkKey.of(mark);var before=result.get(identity);
            if(before==null||before.depthMillimeters()<mark.depthMillimeters())result.put(identity,mark);
        }
        return List.copyOf(result.values());
    }
    /** Weather may change thousands of saved kernels; it never tessellates that history on extraction. */
    private static void deferPressure(SnowSurfacePayload.Mark mark,boolean weather){
        var keys=new HashSet<Long>();contactChunks(mark,keys);keys.retainAll(columns.keySet());
        for(long key:keys)markDirty(key,weather?DirtyCause.AUTHORITY_WEATHER:DirtyCause.AUTHORITY_POSITIVE);
        if(weather)weatherPressureDeferrals++;else urgentPressureDeferrals++;
    }
    /** Only newly added or increased authority is urgent. Full latest frames remain queued for async publication. */
    private static void queuePressureChanges(long sourceChunk,long sourceRevision,List<SnowSurfacePayload.Mark> before,List<SnowSurfacePayload.Mark> after){
        if(meshes.isEmpty())return;
        var old=indexedMarks(before);var fresh=indexedMarks(after);
        for(var entry:old.entrySet()){
            var now=fresh.get(entry.getKey());var was=entry.getValue();
            if(now==null||was.depthMillimeters()-now.depthMillimeters()>=MESH_DELTA_STEP_MM
                    ||was.depthMillimeters()>0&&now.depthMillimeters()<=0)deferPressure(was,true);
        }
        for(var entry:fresh.entrySet()){
            var was=old.get(entry.getKey());var now=entry.getValue();
            if(now.depthMillimeters()<=0||was!=null&&now.depthMillimeters()-was.depthMillimeters()<MESH_DELTA_STEP_MM)continue;
            var identity=entry.getKey();var pending=urgentPressureAuthority.get(identity);
            if(pending==null&&urgentPressureAuthority.size()>=MAX_URGENT_AUTHORITY_MARKS){
                // Work is deferred, never erased: readyFrames retains the complete authoritative snapshot.
                deferPressure(now,false);continue;
            }
            var offered=new PressureOffer(now,sourceChunk,sourceRevision);
            if(newerPressureOffer(offered,pending)){
                pending=offered;urgentPressureAuthority.put(identity,pending);
            }
            contactChunks(pending.mark,pendingPressureChunks);
        }
        pendingPressureChunks.retainAll(columns.keySet());
    }
    /** Received pressure shares the local patch; weather/removals stay in the latest async dirty state. */
    private static Set<Long> takePressureChanges(Set<Long> affected,List<SnowSurfacePayload.Mark> changes){
        pendingPressureChunks.retainAll(columns.keySet());
        if(pendingPressureChunks.isEmpty()){urgentPressureAuthority.clear();return Set.of();}
        if(!bootstrapNativeReady())return Set.of();
        var keys=Set.copyOf(pendingPressureChunks);
        affected.addAll(keys);for(var offered:urgentPressureAuthority.values())changes.add(offered.mark);
        pendingPressureChunks.clear();
        return keys;
    }
    private static boolean newerPressureOffer(PressureOffer offer,PressureOffer previous){
        return previous==null||offer.sourceRevision>previous.sourceRevision||offer.sourceRevision==previous.sourceRevision
                &&offer.mark.depthMillimeters()>previous.mark.depthMillimeters();
    }
    /** Resolve a delayed offer against a newer complete observation of this target. */
    private static PressureOffer resolvePressureOffer(PressureOffer offer,long target,long completeRevision,
            Map<MarkKey,SnowSurfacePayload.Mark> complete,MarkKey identity){
        if(completeRevision<offer.sourceRevision||completeRevision==offer.sourceRevision&&target==offer.sourceChunk)return offer;
        var latest=complete.get(identity);
        return latest==null||latest.depthMillimeters()<=0?null:new PressureOffer(latest,target,completeRevision);
    }
    /** Newer complete target frames own weather changes. Earlier frames retain accepted foreign pressure. */
    private static List<SnowSurfacePayload.Mark> snapshotAuthority(long key,long sourceRevision,List<SnowSurfacePayload.Mark> raw){
        var lineage=pressureLineages.get(key);if(lineage==null||lineage.supplemental.isEmpty())return raw;
        Map<MarkKey,SnowSurfacePayload.Mark> merged=null;
        for(var entry:lineage.supplemental.entrySet()){
            var offered=entry.getValue();if(offered.sourceRevision<=sourceRevision)continue;
            if(merged==null)merged=indexedMarks(raw);
            merged.put(entry.getKey(),offered.mark);
        }
        return merged==null?raw:List.copyOf(merged.values());
    }
    private static boolean stalePressureSnapshot(BuildSnapshot sampled){
        var lineage=pressureLineages.get(sampled.key);if(lineage==null)return false;
        if(lineage.coveredRevision>sampled.sourceRevision)return true;
        Map<MarkKey,SnowSurfacePayload.Mark> frozen=null;
        for(var entry:lineage.supplemental.entrySet()){
            var offer=entry.getValue();if(offer.sourceRevision<=sampled.sourceRevision)continue;
            if(frozen==null)frozen=indexedMarks(sampled.authoritativeContacts);
            var mark=frozen.get(entry.getKey());
            if(mark==null||Float.compare(mark.depthMillimeters(),offer.mark.depthMillimeters())!=0)return true;
        }
        return false;
    }
    /** The actual published authority is the base for a foot, including during a delayed weather replacement. */
    private static List<SnowSurfacePayload.Mark> pressurePresentationAuthority(long key,boolean commit){
        var base=committedAuthoritative.getOrDefault(key,marks);
        if(urgentPressureAuthority.isEmpty())return base;
        var result=indexedMarks(base);
        var complete=readyFrames.get(key);
        long completeRevision=complete==null?0:complete.revision();
        Map<MarkKey,SnowSurfacePayload.Mark> completeMarks=null;
        var lineage=pressureLineages.get(key);
        int x=((int)key)*16,z=((int)(key>>32))*16;
        for(var entry:urgentPressureAuthority.entrySet()){
            var identity=entry.getKey();var offered=entry.getValue();var mark=offered.mark;
            double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
            if(mark.x()+radius<x||mark.x()-radius>x+16||mark.z()+radius<z||mark.z()-radius>z+16)continue;
            if(complete!=null&&(completeRevision>offered.sourceRevision
                    ||completeRevision==offered.sourceRevision&&key!=offered.sourceChunk)){
                if(completeMarks==null)completeMarks=indexedMarks(complete.marks());
                offered=resolvePressureOffer(offered,key,completeRevision,completeMarks,identity);
                if(offered==null)continue;
                mark=offered.mark;
            }
            var observed=lineage==null?null:lineage.supplemental.get(identity);
            if(observed!=null&&observed.sourceRevision>offered.sourceRevision)continue;
            var previous=result.get(identity);
            boolean deepens=previous==null||previous.depthMillimeters()<mark.depthMillimeters();
            if(deepens)result.put(identity,mark);
            if(commit&&(deepens||offered.sourceRevision>completeRevision)){
                if(lineage==null){lineage=new PressureLineage();pressureLineages.put(key,lineage);}
                lineage.accept(identity,offered,completeRevision);
            }
        }
        return List.copyOf(result.values());
    }
    private static void commitPressureChanges(Set<Long> keys){
        if(keys.isEmpty())return;
        for(long key:keys)if(meshes.containsKey(key))committedAuthoritative.put(key,pressurePresentationAuthority(key,true));
        urgentPressureAuthority.clear();
        reconcileLocalContacts();pruneRetiredLocals();
    }
    private static void flushPressureChanges(){
        var keys=new HashSet<Long>();var changes=new ArrayList<SnowSurfacePayload.Mark>();
        var received=takePressureChanges(keys,changes);if(received.isEmpty())return;
        long now=contactTick();
        schedulePressureFollowups(keys,refreshPublishedContacts(keys,changes,now),now);
        commitPressureChanges(received);
    }
    private static boolean pressureFollowupDue(PressureFollowup followup,boolean waiting,long now){
        return now>=followup.deadline||followup.hadDraft&&!waiting;
    }
    private static void rememberPressureFollowup(long key,boolean hadDraft,long now){
        var old=localFineFollowups.get(key);
        if(old==null)localFineFollowups.put(key,new PressureFollowup(now+LOCAL_FINE_FOLLOWUP_TICKS,hadDraft));
        else if(hadDraft&&!old.hadDraft)localFineFollowups.put(key,new PressureFollowup(old.deadline,true));
    }
    private static void schedulePressureFollowups(Set<Long> affected,Map<Long,PressurePatchKind> handled,long now){
        for(long key:affected){
            if(!columns.containsKey(key)||dirty.contains(key))continue;
            var kind=handled.get(key);
            if(kind==null)markDirty(key,DirtyCause.PRESSURE_PATCH_FALLBACK);
            else rememberPressureFollowup(key,kind==PressurePatchKind.DRAFT,now);
        }
    }
    private static List<SnowSurfacePayload.Mark> effectiveContacts(long key){
        // Full newer weather is published asynchronously. Starting a foot from its not-yet-displayed
        // authority would synchronously rebuild old trails and mismatch the visible collision surface.
        return overlayContacts(key,pressurePresentationAuthority(key,false),true);
    }
    private static SnowContactRelief.Prepared prepareContact(SnowSurfacePayload.Mark mark){
        var key=new PreparedKey(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),mark.depthMillimeters(),mark.vehicle(),mark.soleProfile());
        var cache=PREPARED_CACHE.get();var known=cache.get(key);
        if(known!=null){cache.hits++;return known;}
        var value=SnowContactRelief.prepare(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),mark.depthMillimeters(),mark.vehicle(),mark.soleProfile());
        cache.misses++;cache.put(key,value);return value;
    }
    /** One broad-phase pass per chunk; exact contact tests still use the owner geometry. */
    private static final class ContactOwnerIndex {
        private static final int SIDE=16;
        private final int minX,minZ;
        @SuppressWarnings("unchecked")
        private final ArrayList<SnowSurfacePayload.Mark>[] bins=(ArrayList<SnowSurfacePayload.Mark>[])new ArrayList<?>[SIDE*SIDE];

        ContactOwnerIndex(long key,List<SnowSurfacePayload.Mark> marks){
            minX=(int)key*SIDE;minZ=(int)(key>>32)*SIDE;
            for(var mark:marks){
                double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
                // Keep both owners at an exact shared edge, including at negative coordinates.
                int x0=(int)Math.max(0,Math.ceil(mark.x()-radius)-1-minX);
                int x1=(int)Math.min(SIDE-1,Math.floor(mark.x()+radius)-minX);
                int z0=(int)Math.max(0,Math.ceil(mark.z()-radius)-1-minZ);
                int z1=(int)Math.min(SIDE-1,Math.floor(mark.z()+radius)-minZ);
                for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
                    int i=z*SIDE+x;
                    if(bins[i]==null)bins[i]=new ArrayList<>();
                    bins[i].add(mark);
                }
            }
        }

        List<SnowSurfacePayload.Mark> at(BlockPos owner){
            var candidates=bins[(owner.getZ()-minZ)*SIDE+owner.getX()-minX];
            return candidates==null?List.of():candidates;
        }
    }
    private static boolean contactApplies(CellData cell,SnowSurfacePayload.Mark mark,
            IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared){
        var pos=cell.geometry.pos();var known=prepared.get(mark);
        boolean touches=known!=null?known.influenceIntersectsBounds(pos.getX(),pos.getZ(),pos.getX()+1,pos.getZ()+1):
                SnowContactRelief.influenceIntersectsBounds(mark.x(),mark.z(),mark.yaw(),mark.width(),mark.length(),mark.depthMillimeters(),
                        mark.vehicle(),mark.soleProfile(),pos.getX(),pos.getZ(),pos.getX()+1,pos.getZ()+1);
        return touches&&SnowHostGeometry.contactMatches(cell.geometry,cell.fineCorners,mark.x(),mark.y(),mark.z(),
                mark.width(),mark.length(),mark.ownerY());
    }
    /** A synchronous foot updates only its explicit keys against the actual presented field.
     * Chunk authority may already contain weather changes while this cell's old bytes were carried. */
    private static CellSnapshot contactInput(CellData cell,ContactOwnerIndex contacts,
            IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared,Set<MarkKey> changedKeys){
        var replacements=new LinkedHashMap<MarkKey,List<SnowSurfacePayload.Mark>>();
        for(var mark:contacts.at(cell.geometry.pos())){
            var key=MarkKey.of(mark);
            if(changedKeys.contains(key)&&contactApplies(cell,mark,prepared))
                replacements.computeIfAbsent(key,ignored->new ArrayList<>()).add(mark);
        }
        var result=new ArrayList<SnowSurfacePayload.Mark>(cell.contacts.size()+replacements.size());
        for(var prior:cell.contacts){
            var key=MarkKey.of(prior);
            if(!changedKeys.contains(key))result.add(prior);
            else{
                // No replacement means an explicit removal or a now-incompatible owner.
                var fresh=replacements.remove(key);if(fresh!=null)result.addAll(fresh);
            }
        }
        for(var added:replacements.values())result.addAll(added);
        return new CellSnapshot(cell.column,cell.geometry,cell.fineCorners,List.copyOf(result),
                cell.blockLight,cell.skyLight,cell.skirtSides);
    }
    /** Compare with the field which produced the actual cell bytes, not a mesh-wide
     * index or the previous packet. Duplicate placements contribute their deepest cut.
     * Strength is not a kernel input once depthMillimeters has been resolved. */
    private static boolean sameResolvedPressure(List<SnowSurfacePayload.Mark> actual,List<SnowSurfacePayload.Mark> desired){
        if(actual==desired)return true;
        if(actual.size()!=desired.size())return false;
        for(int i=0;i<actual.size();i++){
            var a=actual.get(i);var b=desired.get(i);
            // These are already support-filtered cell inputs. Y and strength do not enter the resolved kernel.
            if(Double.compare(a.x(),b.x())!=0||Double.compare(a.z(),b.z())!=0
                    ||Float.compare(a.yaw(),b.yaw())!=0||Float.compare(a.width(),b.width())!=0
                    ||Float.compare(a.length(),b.length())!=0||Float.compare(a.depthMillimeters(),b.depthMillimeters())!=0
                    ||a.vehicle()!=b.vehicle()||a.soleProfile()!=b.soleProfile())return false;
        }
        return true;
    }
    private static boolean samePressureField(List<SnowSurfacePayload.Mark> actual,List<SnowSurfacePayload.Mark> desired){
        if(sameResolvedPressure(actual,desired))return true;
        var before=indexedMarks(actual);var after=indexedMarks(desired);
        if(before.size()!=after.size())return false;
        for(var entry:before.entrySet()){
            var fresh=after.get(entry.getKey());if(fresh==null)return false;
            float oldDepth=entry.getValue().depthMillimeters(),newDepth=fresh.depthMillimeters();
            if(Math.abs(oldDepth-newDepth)>=MESH_DELTA_STEP_MM||(oldDepth>0)!=(newDepth>0))return false;
        }
        return true;
    }
    private enum PressurePatchKind { UNHANDLED, METADATA, DRAFT }
    private record ContactPatch(Mesh mesh,PressurePatchKind kind) {}
    private static boolean pressureOwnersPresent(Mesh mesh,List<SnowSurfacePayload.Mark> changed,
            List<SnowContactRelief.Prepared> kernels){
        var observed=columns.get(mesh.key);if(observed==null)return false;
        int cx=(int)mesh.key*16,cz=(int)(mesh.key>>32)*16;
        for(int i=0;i<changed.size();i++){
            var mark=changed.get(i);var kernel=kernels.get(i);
            if(!kernel.influenceIntersectsBounds(cx,cz,cx+16,cz+16))continue;
            int y=mark.ownerY();if(y==Integer.MIN_VALUE)return false;
            double radius=SnowContactRelief.influenceRadiusMeters(mark.width(),mark.length(),mark.depthMillimeters());
            int x0=Math.max(cx,(int)Math.ceil(mark.x()-radius)-1),x1=Math.min(cx+15,(int)Math.floor(mark.x()+radius));
            int z0=Math.max(cz,(int)Math.ceil(mark.z()-radius)-1),z1=Math.min(cz+15,(int)Math.floor(mark.z()+radius));
            for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
                long position=BlockPos.asLong(x,y,z);
                if(observed.containsKey(position)&&!mesh.cells.containsKey(position)&&kernel.influenceIntersectsBounds(x,z,x+1,z+1))
                    return false;
            }
        }
        return true;
    }
    /** A repeated foot near an edge must not rebuild a neighbour solely because its normal apron is touched.
     * Compare the actual immutable halo which the canonical shading would read; removals and new fields fail closed. */
    private static boolean unchangedNormalHalo(CellData cell,Mesh original,List<SnowSurfacePayload.Mark> desired,
            Set<MarkKey> changedKeys,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared,
            Map<Long,ContactOwnerIndex> indices){
        var pos=cell.geometry.pos();
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(int dy=-3;dy<=3;dy++){
            int x=pos.getX()+dx,y=pos.getY()+dy,z=pos.getZ()+dz;
            long key=((long)(x>>4)&0xffffffffL)|((long)(z>>4)<<32);
            var source=key==original.key?original:meshes.get(key);
            var neighbor=source==null?null:source.cells.get(BlockPos.asLong(x,y,z));
            if(neighbor==null)continue;
            var index=indices.computeIfAbsent(key,k->new ContactOwnerIndex(k,k==original.key?desired:effectiveContacts(k)));
            var updated=contactInput(neighbor,index,prepared,changedKeys);
            if(!sameResolvedPressure(neighbor.contacts,updated.contacts))return false;
        }
        return true;
    }
    /** Existing support/fine-corner snapshots are immutable; no model discovery or world scan occurs here. */
    private static ContactPatch patchContacts(Mesh original,List<SnowSurfacePayload.Mark> desired,List<SnowSurfacePayload.Mark> lightingChanges,
            Map<Long,BuildSnapshot> fineCaptures,long now){
        // The caller supplies only actual new pressure (or an explicit local correction).
        // Old weather/retirement fields stay on the async dirty queue. Comparing the whole
        // ledger here would synchronously revisit every aged track on the next footfall.
        var changed=new ArrayList<>(new LinkedHashSet<>(lightingChanges));
        if(changed.isEmpty())return new ContactPatch(original,PressurePatchKind.UNHANDLED);
        long started=System.nanoTime();var cells=new ArrayList<CellSnapshot>();var affectedPositions=new HashSet<Long>();var pressurePositions=new HashSet<Long>();
        ContactOwnerIndex ownerContacts=null;
        var normalContactIndices=new HashMap<Long,ContactOwnerIndex>();
        var preparedContacts=new IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared>();
        var changedPrepared=new ArrayList<SnowContactRelief.Prepared>(changed.size());
        for(var mark:changed)changedPrepared.add(preparedContacts.computeIfAbsent(mark,SnowSurfaceClient::prepareContact));
        var changedKeys=new HashSet<MarkKey>();for(var mark:changed)changedKeys.add(MarkKey.of(mark));
        for(var cell:original.cells.values()){
            boolean pressure=false,near=false,normalAffected=false;var pos=cell.geometry.pos();
            double apron=com.winterkore.engine.surface.geometry.SnowSurfaceShading.DEFAULT_NORMAL_STEP;
            int x=pos.getX(),z=pos.getZ();
            for(int i=0;i<changed.size();i++){
                var mark=changed.get(i);var kernel=changedPrepared.get(i);
                boolean reaches=kernel.influenceIntersectsBounds(x,z,x+1,z+1);
                if(!reaches&&!kernel.influenceIntersectsBounds(x-apron,z-apron,x+1+apron,z+1+apron))continue;
                near|=reaches;
                if(!SnowHostGeometry.contactMatches(cell.geometry,cell.fineCorners,mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),mark.ownerY()))continue;
                if(reaches)pressure=true;
                else normalAffected=true; // A shared edge normal samples slightly into its neighbour.
                if(pressure&&normalAffected)break;
            }
            // An explicit correction still repairs old bytes when the owner no longer matches.
            if(!pressure&&near)for(var prior:cell.contacts)if(changedKeys.contains(MarkKey.of(prior))){pressure=true;break;}
            if(pressure||normalAffected){
                if(ownerContacts==null)ownerContacts=new ContactOwnerIndex(original.key,desired);
                var input=contactInput(cell,ownerContacts,preparedContacts,changedKeys);
                if(samePressureField(cell.contacts,input.contacts)){
                    if(!normalAffected)continue;
                    normalContactIndices.putIfAbsent(original.key,ownerContacts);
                    if(unchangedNormalHalo(cell,original,desired,changedKeys,preparedContacts,normalContactIndices))continue;
                }
                cells.add(input);affectedPositions.add(pos.asLong());pressurePositions.add(pos.asLong());
            }
        }
        boolean stable=original==meshes.get(original.key)&&!chunkHandoff(original.key);
        boolean handled=stable&&(dirty.contains(original.key)||pressureOwnersPresent(original,changed,changedPrepared));
        if(cells.isEmpty()){
            // A halo/global entry can outlive every compatible owner. Keep the global
            // pressure index current in a metadata snapshot sharing immutable packed bytes;
            // cell metadata/geometry remain exact and real saved contacts remain in desired.
            var metadata=new Mesh(original.key,original.revision,original.origin,original.cells,desired,original.data,
                    original.vertices,original.groups,original.bounds,original.debugCells);
            return new ContactPatch(metadata,handled?PressurePatchKind.METADATA:PressurePatchKind.UNHANDLED);
        }
        // The finite one-cell lighting halo uses committed surfaces on both sides of a seam.
        var lighting=new HashMap<Long,CellSnapshot>();var contactSets=new HashMap<Long,ContactOwnerIndex>();
        contactSets.put(original.key,ownerContacts);
        for(var input:cells)lighting.put(input.geometry.pos().asLong(),input);
        for(var input:cells){
            BlockPos pos=input.geometry.pos();
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(int dy=-3;dy<=3;dy++){
                BlockPos neighbor=pos.offset(dx,dy,dz);long position=neighbor.asLong();if(lighting.containsKey(position))continue;
                long key=chunkKey(neighbor);Mesh source=key==original.key?original:meshes.get(key);
                CellData cell=source==null?null:source.cells.get(position);if(cell==null)continue;
                var contacts=contactSets.computeIfAbsent(key,k->new ContactOwnerIndex(k,effectiveContacts(k)));
                lighting.put(position,contactInput(cell,contacts,preparedContacts,changedKeys));
            }
        }
        var sourceFrame=readyFrames.get(original.key);
        var snapshot=new BuildSnapshot(epoch,original.key,geometryRevisions.getOrDefault(original.key,0L),sourceFrame==null?0:sourceFrame.revision(),++revision,
                original.origin,List.copyOf(cells),desired,desired,Map.copyOf(lighting),Map.of());
        Mesh patch;
        try{patch=buildContactPatch(snapshot,preparedContacts);}
        catch(com.winterkore.engine.surface.geometry.ConformingSnowMesh.WorkLimitExceeded failure){
            // Boundary planning could not finish in the render budget. Keep the
            // existing immutable owner and let the larger worker budget try it.
            markDirty(original.key,DirtyCause.DRAFT_BUDGET);
            return new ContactPatch(original,PressurePatchKind.UNHANDLED);
        }
        for(long position:pressurePositions){
            coarseUntil.put(position,now+CONTACT_FINE_QUIET_TICKS);fineQueued.remove(position);
            if(forgetFineWitness(position))fineWitnessDropsSuperseded++;
        }
        // The fresh patch owns its small immutable payload. Untouched ranges are shared,
        // and an identical packed tile keeps its previous GPU source.
        Mesh result=mergeCells(original,patch,affectedPositions,desired,snapshot.renderRevision);
        if(stable&&snapshot.cells.size()<=FINE_CAPTURE_ROOT_LIMIT){
            fineCaptureCandidates++;
            if(dirty.contains(original.key)){
                fineCaptureDirty++;fineCaptureDirtyCauses[dirtyCauses.get(original.key)]++;
            }
            else if(handled)fineCaptures.put(original.key,snapshot);
        }
        long elapsed=System.nanoTime()-started;
        localPatches++;localPatchCells+=cells.size();localPatchNanos+=elapsed;localWorstPatchNanos=Math.max(localWorstPatchNanos,elapsed);
        return new ContactPatch(result,handled?PressurePatchKind.DRAFT:PressurePatchKind.UNHANDLED);
    }
    private static final boolean PARALLEL_CONTACT_MESH=Runtime.getRuntime().availableProcessors()>=6;
    private static ExecutorService contactTessellator;
    private static Mesh buildLocalContactMesh(BuildSnapshot snapshot,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared){
        return buildLocalContactMesh(snapshot,prepared,PARALLEL_CONTACT_MESH);
    }
    private static Mesh buildLocalContactMesh(BuildSnapshot snapshot,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared,
            boolean parallel){
        return buildLocalContactMesh(snapshot,prepared,parallel,world!=null);
    }
    /** Explicit switch also permits non-vacuous offline cached/cold packed-byte checks. */
    private static Mesh buildLocalContactMesh(BuildSnapshot snapshot,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared,
            boolean parallel,boolean persistentAo){
        var sampling=new WorkerSampling(snapshot.contacts,prepared);sampling.localApproximation=true;sampling.persistentAo=persistentAo;
        sampling.lighting=new LightingField(snapshot.lighting);WORKER_SAMPLING.set(sampling);
        try{return buildWithSampling(snapshot,new BuildJob(List.of(snapshot),Set.of(snapshot.key),false),sampling,parallel);}
        finally{
            recordLocalBuildDiagnostics(sampling);
            WORKER_SAMPLING.remove();
        }
    }
    private static void recordLocalBuildDiagnostics(WorkerSampling sampling){
        maxLocalApproximationErrorMm=Math.max(maxLocalApproximationErrorMm,sampling.worstApproximationError*1000);
        localOverBudgetLeaves+=sampling.overBudgetLeaves;
        localMaxThresholdTests+=sampling.maxThresholdCounters[0];localMaxThresholdRejects+=sampling.maxThresholdCounters[1];
        localReliefQueries+=sampling.reliefQueries;localReliefCacheHits+=sampling.reliefHits;localReliefCandidates+=sampling.reliefCandidates;
        localAoRayQueries+=sampling.horizonQueries;localAoRayHits+=sampling.horizonHits;localReliefCacheEvictions+=sampling.relief.evictions;
        localFeatureCandidates+=sampling.featureCandidates;localFeaturePointsInside+=sampling.featurePointsInside;
        localFeatureHeightQueries+=sampling.featureHeightQueries;
    }
    private static void localError(WorkerSampling sampling,double error){
        sampling.cellWorstError=Math.max(sampling.cellWorstError,error);sampling.worstApproximationError=Math.max(sampling.worstApproximationError,error);
        if(error>LOCAL_RELIEF_ERROR+1e-12)sampling.overBudgetLeaves++;
    }
    /** Local geometry always starts at immutable authored roots. A previous contact
     * mesh is metadata/ownership input only, never the next tessellation's topology. */
    private static Mesh buildContactPatch(BuildSnapshot snapshot,IdentityHashMap<SnowSurfacePayload.Mark,SnowContactRelief.Prepared> prepared){
        long start=System.nanoTime();
        Mesh result=buildLocalContactMesh(snapshot,prepared);
        localRefinedTriangles+=result.vertices/3;localPatchScanNanos+=System.nanoTime()-start;
        return result;
    }
    private static final long CPU_MESH_POOL_BUDGET=128L*1024*1024;
    private static final int CPU_MESH_POOL_STEP=128*1024;
    private static final class CpuMeshSlot {
        final ByteBuffer storage;final long epoch;java.lang.ref.WeakReference<Mesh> owner;
        ByteBuffer publishedData;
        long chunkKey,lastUseFrame;boolean leased;
        CpuMeshSlot(int capacity){storage=ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder());epoch=SnowSurfaceClient.epoch;}
    }
    private record CpuMeshLease(CpuMeshSlot slot,ByteBuffer data) {}
    private static final List<CpuMeshSlot> cpuMeshSlots=new ArrayList<>();
    private static long cpuMeshPoolBytes,cpuMeshPoolPeakBytes,cpuMeshPoolAllocations,cpuMeshPoolReuses,
            cpuMeshPoolFallbacks,cpuMeshPoolEvictions,cpuMeshPoolBytesAvoided;
    private static Set<Mesh> liveCpuMeshes(){
        var live=Collections.newSetFromMap(new IdentityHashMap<Mesh,Boolean>());live.addAll(meshes.values());
        for(var transfer:handoffs.values()){if(transfer.before!=null)live.add(transfer.before);if(transfer.after!=null)live.add(transfer.after);}
        var extracted=extractedFrame;if(extracted!=null){live.addAll(extracted.all);live.addAll(extracted.meshes);}
        var shadow=shadowPrepared;if(shadow!=null)for(var draw:shadow.draws)live.add(draw.mesh);
        var job=activeBuild;if(job!=null){var result=job.result;if(result!=null)live.addAll(result);}
        return live;
    }
    private static boolean reusableCpuSlot(CpuMeshSlot slot,Set<Mesh> live){
        long age=frameSequence-slot.lastUseFrame;
        if(slot.leased||slot.epoch!=epoch||age<3)return false;
        // Metadata-only Mesh aliases share this exact immutable published view. Keep
        // its storage leased while ANY live owner, frame or handoff still references it.
        if(slot.publishedData!=null)for(var mesh:live)if(mesh.data==slot.publishedData)return false;
        Mesh owner=slot.owner==null?null:slot.owner.get();return owner==null||!live.contains(owner);
    }
    private static CpuMeshLease acquireCpuMeshBuffer(int bytes){
        long rounded=((long)bytes+CPU_MESH_POOL_STEP-1)/CPU_MESH_POOL_STEP*CPU_MESH_POOL_STEP;
        if(bytes<=0||rounded>CPU_MESH_POOL_BUDGET||!Minecraft.getInstance().isSameThread()){
            cpuMeshPoolFallbacks++;return new CpuMeshLease(null,ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()));
        }
        var live=liveCpuMeshes();CpuMeshSlot selected=null;
        for(var slot:cpuMeshSlots)if(slot.storage.capacity()>=rounded&&reusableCpuSlot(slot,live)
                &&(selected==null||slot.storage.capacity()<selected.storage.capacity()
                    ||slot.storage.capacity()==selected.storage.capacity()&&slot.lastUseFrame<selected.lastUseFrame))selected=slot;
        if(selected!=null){cpuMeshPoolReuses++;cpuMeshPoolBytesAvoided+=bytes;}
        else{
            while(cpuMeshPoolBytes+rounded>CPU_MESH_POOL_BUDGET){
                CpuMeshSlot oldest=null;
                for(var slot:cpuMeshSlots)if(reusableCpuSlot(slot,live)&&(oldest==null||slot.lastUseFrame<oldest.lastUseFrame))oldest=slot;
                if(oldest==null)break;
                cpuMeshSlots.remove(oldest);cpuMeshPoolBytes-=oldest.storage.capacity();cpuMeshPoolEvictions++;
            }
            if(cpuMeshPoolBytes+rounded>CPU_MESH_POOL_BUDGET){
                cpuMeshPoolFallbacks++;return new CpuMeshLease(null,ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()));
            }
            selected=new CpuMeshSlot((int)rounded);cpuMeshSlots.add(selected);cpuMeshPoolBytes+=rounded;
            cpuMeshPoolPeakBytes=Math.max(cpuMeshPoolPeakBytes,cpuMeshPoolBytes);cpuMeshPoolAllocations++;
        }
        selected.leased=true;selected.lastUseFrame=frameSequence;
        ByteBuffer data=selected.storage.duplicate().order(ByteOrder.nativeOrder());data.clear().limit(bytes);
        return new CpuMeshLease(selected,data);
    }
    private static void registerCpuMesh(CpuMeshLease lease,Mesh mesh){
        var slot=lease.slot;if(slot==null)return;
        slot.owner=new java.lang.ref.WeakReference<>(mesh);slot.publishedData=mesh.data;slot.chunkKey=mesh.key;slot.lastUseFrame=frameSequence;slot.leased=false;
    }
    private static void touchCpuMesh(Mesh mesh){
        for(var slot:cpuMeshSlots)if(slot.publishedData==mesh.data||(slot.owner!=null&&slot.owner.get()==mesh)){slot.lastUseFrame=Math.max(slot.lastUseFrame,frameSequence);return;}
    }
    private static void discardCpuMeshChunk(long key){
        for(var iterator=cpuMeshSlots.iterator();iterator.hasNext();){
            var slot=iterator.next();if(!slot.leased&&slot.chunkKey==key){cpuMeshPoolBytes-=slot.storage.capacity();iterator.remove();}
        }
    }
    private static void clearCpuMeshPool(){
        cpuMeshSlots.clear();cpuMeshPoolBytes=cpuMeshPoolPeakBytes=cpuMeshPoolAllocations=cpuMeshPoolReuses=
                cpuMeshPoolFallbacks=cpuMeshPoolEvictions=cpuMeshPoolBytesAvoided=0;
    }
    /** Publish changed ranges by reference; untouched cell bytes and GPU sources survive unchanged. */
    private static Mesh mergeCells(Mesh original,Mesh patch,Set<Long> positions,List<SnowSurfacePayload.Mark> contacts,long version){
        return mergeCells(original,patch,positions,contacts,version,false);
    }
    private static Mesh mergeCells(Mesh original,Mesh patch,Set<Long> positions,List<SnowSurfacePayload.Mark> contacts,long version,boolean detachScratch){
        var entries=new ArrayList<>(original.cells.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<Long,CellData> e)->e.getValue().geometry.pos().getY()>>4)
                .thenComparingInt(e->e.getValue().firstVertex));
        var accepted=new LinkedHashMap<Long,CellData>();int vertices=0;
        for(var entry:entries){
            boolean replace=positions.contains(entry.getKey());
            CellData cell=replace?patch.cells.get(entry.getKey()):entry.getValue();
            if(cell==null)throw new IllegalStateException("Missing replacement snow tile");
            SnowMeshRange range=cell.range;
            if(replace&&entry.getValue().vertexCount==cell.vertexCount
                    &&entry.getValue().range.bytes().mismatch(range.bytes())<0){
                // Same actual packed surface: metadata may advance without a copy,
                // a new GPU payload, or a vertex upload. No sampled equivalence claim.
                range=entry.getValue().range;sourceExactRangeReuses++;
            }else if(replace&&detachScratch){
                range=range.detached();localCopiedBytes+=(long)cell.vertexCount*BYTES;sourcePayloadCopies++;
            }
            accepted.put(entry.getKey(),cell.withRange(vertices,range));vertices+=cell.vertexCount;
        }
        return new Mesh(original.key,version,original.origin,Map.copyOf(accepted),contacts,ByteBuffer.allocate(0).asReadOnlyBuffer(),
                vertices,List.of(),new AABB(original.bounds.minX,Math.min(original.bounds.minY,patch.bounds.minY),original.bounds.minZ,
                        original.bounds.maxX,Math.max(original.bounds.maxY,patch.bounds.maxY),original.bounds.maxZ),new ConcurrentHashMap<>());
    }
    private static final int REPACK_SOURCE_THRESHOLD=8,REPACK_MAX_BYTES=8*1024*1024;
    private record RepackArea(long section,int x,int z) {}
    private static final class RangeRepack {
        final long epoch,key;final Map<Long,SnowMeshRange> captured;
        volatile boolean cancelled,finished;SnowMeshCompactor.Result result;Throwable failure;
        RangeRepack(long key,Map<Long,SnowMeshRange> captured){epoch=SnowSurfaceClient.epoch;this.key=key;this.captured=captured;}
    }
    private static RangeRepack rangeRepack;
    private static long rangeRepackTick=-1,rangeRepacks,rangeRepackBytes,rangeRepackStale,rangeRepackFailures;
    private static boolean chunkHandoff(long key){
        for(var transfer:handoffs.values())
            if(transfer.before!=null&&transfer.before.key==key||transfer.after!=null&&transfer.after.key==key)return true;
        return false;
    }
    /** One bounded copy-only task shares the existing CPU worker. A concurrent new foot
     * changes its range identity and is excluded from this older packing result. */
    private static void maintainRangeCompaction(){
        var task=rangeRepack;
        if(task!=null&&task.finished){
            rangeRepack=null;
            if(task.failure!=null){if(!(task.failure instanceof CancellationException))rangeRepackFailures++;}
            else if(!task.cancelled&&task.epoch==epoch&&task.result!=null&&!chunkHandoff(task.key)){
                Mesh current=meshes.get(task.key);
                if(current!=null){
                    var cells=new HashMap<>(current.cells);int adopted=0;
                    for(var entry:task.captured.entrySet()){
                        var cell=cells.get(entry.getKey());
                        if(cell!=null&&cell.range==entry.getValue()){
                            cells.put(entry.getKey(),cell.withRange(cell.firstVertex,task.result.ranges().get(entry.getKey())));adopted++;
                        }
                    }
                    if(adopted>0){
                        Mesh made=new Mesh(current.key,current.revision,current.origin,Map.copyOf(cells),current.contacts,
                                ByteBuffer.allocate(0).asReadOnlyBuffer(),current.vertices,List.of(),current.bounds,current.debugCells);
                        var next=new HashMap<>(meshes);next.put(task.key,made);meshes=Map.copyOf(next);
                        var allocation=gpu.remove(current);if(allocation!=null)gpu.put(made,allocation);
                        shadowPrepared=null;rangeRepacks++;rangeRepackBytes+=task.result.copiedBytes();
                    }else rangeRepackStale++;
                }
            }else rangeRepackStale++;
        }
        long now=contactTick();
        if(rangeRepack!=null||!bootstrapDone||activeBuild!=null||preparing!=null||!dirty.isEmpty()||!fineQueued.isEmpty()||tessellator==null
                ||now>=rangeRepackTick&&now-rangeRepackTick<4)return;
        rangeRepackTick=now;
        for(var mesh:meshes.values()){
            if(mesh.groups.size()<REPACK_SOURCE_THRESHOLD||chunkHandoff(mesh.key))continue;
            var source=mesh.groups.getFirst().source;boolean fragmented=false;
            for(var group:mesh.groups)if(group.source!=source){fragmented=true;break;}
            if(!fragmented)continue;
            var areas=new LinkedHashMap<RepackArea,List<Map.Entry<Long,CellData>>>();
            for(var entry:mesh.cells.entrySet()){
                var pos=entry.getValue().geometry.pos();var area=new RepackArea(section(pos),pos.getX()>>2,pos.getZ()>>2);
                areas.computeIfAbsent(area,ignored->new ArrayList<>()).add(entry);
            }
            for(var entries:areas.values()){
                var sources=Collections.newSetFromMap(new IdentityHashMap<SnowMeshBuffer,Boolean>());long bytes=0;
                for(var entry:entries){sources.add(entry.getValue().range.source());bytes+=(long)entry.getValue().vertexCount*BYTES;}
                if(sources.size()<REPACK_SOURCE_THRESHOLD||bytes==0||bytes>REPACK_MAX_BYTES)continue;
                entries.sort(Comparator.comparingInt(e->e.getValue().firstVertex));
                var captured=new LinkedHashMap<Long,SnowMeshRange>();
                for(var entry:entries)captured.put(entry.getKey(),entry.getValue().range);
                var submitted=new RangeRepack(mesh.key,Map.copyOf(captured));rangeRepack=submitted;rangeRepackTick=now;
                tessellator.execute(()->{
                    try{submitted.result=SnowMeshCompactor.compact(submitted.captured,REPACK_MAX_BYTES,()->{
                        if(submitted.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();
                    });}
                    catch(Throwable failure){submitted.failure=failure;}
                    finally{submitted.finished=true;}
                });
                return;
            }
        }
    }
    /** A completed async field never overwrites pressure geometry produced after its observation. */
    private static Mesh carryLocalCells(Mesh made,Mesh current){
        if(current==null||localContacts.isEmpty())return made;
        long now=contactTick();var positions=new HashSet<Long>();var contacts=indexedMarks(made.contacts);
        for(var entry:current.cells.entrySet()){
            var old=entry.getValue();var destination=made.cells.get(entry.getKey());
            if(destination==null||!old.geometry.equals(destination.geometry))continue;
            var known=indexedMarks(destination.contacts);boolean fresh=old.approximate&&now<coarseUntil.getOrDefault(entry.getKey(),0L);
            for(var mark:old.contacts){
                var identity=MarkKey.of(mark);if(!localContacts.containsKey(identity))continue;
                var previous=known.get(identity);
                if(previous==null||mark.depthMillimeters()-previous.depthMillimeters()>=MESH_DELTA_STEP_MM){fresh=true;break;}
            }
            if(fresh){
                positions.add(entry.getKey());
                for(var mark:old.contacts){var identity=MarkKey.of(mark);var previous=contacts.get(identity);
                    if(previous==null||previous.depthMillimeters()<mark.depthMillimeters())contacts.put(identity,mark);}
            }
        }
        if(positions.isEmpty())return made;
        var merged=mergeCells(made,current,positions,List.copyOf(contacts.values()),++revision);
        if(!dirty.contains(made.key)){
            // A carried draft keeps its quiet deadline only while every exact range
            // still has a captured refinement input. The worker also checks its halo.
            if(current.cells.keySet().equals(merged.cells.keySet())&&canRefineCarriedRanges(merged,positions))
                rememberPressureFollowup(made.key,true,now);
            else markDirty(made.key,DirtyCause.CARRY_FALLBACK);
        }
        return merged;
    }
    private static boolean canRefineCarriedRanges(Mesh mesh,Set<Long> positions){
        if(chunkHandoff(mesh.key))return false;
        for(long position:positions){
            var cell=mesh.cells.get(position);var witness=fineWitnesses.get(position);
            if(cell==null||!cell.approximate||!coarseUntil.containsKey(position)||witness==null||witness.basis.epoch!=epoch
                    ||witness.range.get()!=cell.range||!sameFineObservation(witness.input,cell))return false;
        }
        return true;
    }
    private static Map<Long,PressurePatchKind> refreshPublishedContacts(Set<Long> keys,List<SnowSurfacePayload.Mark> lightingChanges,long now){
        var replacements=new IdentityHashMap<Mesh,Mesh>();var fineCaptures=new HashMap<Long,BuildSnapshot>();
        var handled=new HashMap<Long,PressurePatchKind>();
        for(var old:sceneMeshes())if(keys.contains(old.key)){
            var patch=patchContacts(old,effectiveContacts(old.key),lightingChanges,fineCaptures,now);
            if(patch.mesh!=old)replacements.put(old,patch.mesh);
            if(patch.kind!=PressurePatchKind.UNHANDLED)handled.put(old.key,patch.kind);
        }
        if(replacements.isEmpty())return Map.of();
        var next=new HashMap<>(meshes);
        for(var entry:next.entrySet())entry.setValue(replacements.getOrDefault(entry.getValue(),entry.getValue()));
        var transfers=new HashMap<Long,Handoff>();
        for(var entry:handoffs.entrySet()){
            var value=entry.getValue();transfers.put(entry.getKey(),new Handoff(value.epoch,
                    replacements.getOrDefault(value.before,value.before),replacements.getOrDefault(value.after,value.after),value.nativeSections));
        }
        for(var entry:replacements.entrySet()){
            var allocation=gpu.remove(entry.getKey());if(allocation!=null)gpu.put(entry.getValue(),allocation);
        }
        meshes=Map.copyOf(next);handoffs=Map.copyOf(transfers);shadowPrepared=null;
        for(var capture:fineCaptures.values())rememberFineWitnesses(capture,meshes.get(capture.key));
        observeLocalPublication();ShadowCaptureCache.terrainChanged();
        return handled;
    }
    private static void rememberFineWitnesses(BuildSnapshot snapshot,Mesh published){
        if(published==null||dirty.contains(snapshot.key)||chunkHandoff(snapshot.key))return;
        long bytes=1024;
        for(var halo:snapshot.lighting.values())bytes+=384L+128L*halo.contacts.size()+32L*halo.geometry.patches().size();
        if(bytes>FINE_WITNESS_BYTE_LIMIT)return;
        var geometry=new HashMap<Long,Long>();
        for(var input:snapshot.cells){
            var pos=input.geometry.pos();
            for(int dz=-1;dz<=1;dz+=2)for(int dx=-1;dx<=1;dx+=2){
                long key=chunkKey((pos.getX()+dx)>>4,(pos.getZ()+dz)>>4);
                geometry.put(key,geometryRevisions.getOrDefault(key,0L));
            }
        }
        var basis=new FineBasis(snapshot.epoch,snapshot.key,snapshot.sourceRevision,snapshot.origin,snapshot.cells,
                snapshot.lighting,Map.copyOf(geometry),bytes);
        for(var input:snapshot.cells){
            long position=input.geometry.pos().asLong();var cell=published.cells.get(position);
            if(cell==null||!cell.approximate||!sameFineObservation(input,cell))continue;
            forgetFineWitness(position);
            // Charging shared bases per witness also bounds worst-case unshared retention.
            while(fineWitnesses.size()>=FINE_WITNESS_LIMIT||fineWitnessBytes+bytes>FINE_WITNESS_BYTE_LIMIT){
                forgetFineWitness(fineWitnesses.firstEntry().getKey());fineWitnessDropsBudget++;
            }
            fineWitnesses.put(position,new FineWitness(basis,input,new java.lang.ref.WeakReference<>(cell.range)));
            fineWitnessBytes+=bytes;fineWitnessesCaptured++;
        }
    }
    private static boolean containsLocal(CellData cell,LocalContact local){
        if(cell==null)return false;var identity=MarkKey.of(local.mark);
        for(var present:cell.contacts)if(MarkKey.of(present).equals(identity)
                &&present.depthMillimeters()+MESH_DELTA_STEP_MM>=local.mark.depthMillimeters())return true;
        return false;
    }
    private static void observeLocalPublication(){
        for(var local:localEvents)if(local.appliedTick<0&&!local.owners.isEmpty()){
            boolean complete=true;long meshRevision=0;
            for(long position:local.owners){
                BlockPos owner=BlockPos.of(position);Mesh mesh=displayedMesh(owner);
                if(mesh==null||!containsLocal(mesh.cells.get(position),local)){complete=false;break;}
                meshRevision=Math.max(meshRevision,mesh.revision);
            }
            if(complete){
                local.appliedTick=contactTick();local.appliedFrame=frameSequence;local.appliedNanos=System.nanoTime();local.meshRevision=meshRevision;localPublished++;
                localWorstPublishTicks=Math.max(localWorstPublishTicks,local.appliedTick-local.tick);
            }
        }
    }
    private static void observeLocalEligibility(Frame frame){
        var selected=displaySelections(frame.all);var visible=Collections.newSetFromMap(new IdentityHashMap<Mesh,Boolean>());
        visible.addAll(frame.meshes);
        for(var local:localEvents)for(long position:local.owners){
            if(local.eligibleAt.containsKey(position))continue;
            BlockPos owner=BlockPos.of(position);Mesh mesh=selected.get(section(owner));
            if(mesh!=null&&visible.contains(mesh)&&containsLocal(mesh.cells.get(position),local)){
                local.eligibleAt.put(position,frame.id);
                if(local.eligibleFrame<0){local.eligibleFrame=frame.id;local.eligibleTick=contactTick();}
            }
        }
    }
    /** Called only after the section's real RenderPass.draw has submitted all influenced owners. */
    private static void observeLocalDraw(Mesh mesh,Map<Long,Mesh> selected,long frame){
        for(var local:localEvents)if(local.drawnTick<0&&!local.owners.isEmpty()){
            for(long position:local.owners){
                BlockPos owner=BlockPos.of(position);
                if(selected.get(section(owner))==mesh&&containsLocal(mesh.cells.get(position),local)){
                    local.drawnOwners.add(position);local.drawnAt.putIfAbsent(position,frame);
                }
            }
            if(local.drawnOwners.containsAll(local.owners)){
                local.drawnTick=contactTick();local.drawnFrame=frame;local.drawnNanos=System.nanoTime();localDrawn++;
                localWorstDrawFrames=Math.max(localWorstDrawFrames,frame-local.frame);
            }
        }
    }
    private static final class RetiredOccurrence {
        final SnowSurfacePayload.Mark prediction;
        int global,cells,displayed,mismatched,saved,snapshots;float minDepth=Float.POSITIVE_INFINITY,maxDepth;
        final List<Map<String,Object>> owners=new ArrayList<>();
        RetiredOccurrence(SnowSurfacePayload.Mark prediction){this.prediction=prediction;}
        void depth(float depth){minDepth=Math.min(minDepth,depth);maxDepth=Math.max(maxDepth,depth);}
    }
    /** Cold scalar inspection distinguishes stale indices, real cell fields and legitimate saved marks.
     * This never declares geometry repaired and never changes any retirement/completion criterion. */
    public static Map<String,Object> retiredContactDiagnostics(){
        var states=new HashMap<MarkKey,RetiredOccurrence>();
        for(var entry:retiredLocalContacts.entrySet())states.put(entry.getKey(),new RetiredOccurrence(entry.getValue()));
        for(var mesh:sceneMeshes()){
            if(states.isEmpty())break;
            var authority=indexedMarks(committedAuthoritative.getOrDefault(mesh.key,List.of()));
            for(var mark:mesh.contacts){
                var identity=MarkKey.of(mark);var state=states.get(identity);if(state==null)continue;
                state.global++;state.depth(mark.depthMillimeters());
            }
            for(var entry:mesh.cells.entrySet()){
                var cell=entry.getValue();boolean displayed=displayedMesh(cell.geometry.pos())==mesh;
                for(var mark:cell.contacts){
                    var identity=MarkKey.of(mark);var state=states.get(identity);if(state==null)continue;
                    state.cells++;if(displayed)state.displayed++;state.depth(mark.depthMillimeters());
                    var real=authority.get(identity);boolean saved=real!=null&&Math.abs(real.depthMillimeters()-mark.depthMillimeters())<MESH_DELTA_STEP_MM;
                    if(saved)state.saved++;else state.mismatched++;
                    if(state.owners.size()<8)state.owners.add(Map.of("owner",entry.getKey(),"chunk",mesh.key,"meshRevision",mesh.revision,
                            "displayed",displayed,"cellDepthMillimeters",mark.depthMillimeters(),
                            "committedDepthMillimeters",real==null?-1f:real.depthMillimeters(),"verifiedAuthority",saved));
                }
            }
        }
        var snapshots=new ArrayList<BuildSnapshot>();
        if(activeBuild!=null)snapshots.addAll(activeBuild.snapshots);
        if(preparing!=null)snapshots.addAll(preparing.snapshots);
        for(var snapshot:snapshots){
            var authority=indexedMarks(snapshot.authoritativeContacts);
            for(var mark:snapshot.contacts){
                var identity=MarkKey.of(mark);var state=states.get(identity);if(state==null)continue;
                var real=authority.get(identity);
                if(real==null||Math.abs(real.depthMillimeters()-mark.depthMillimeters())>=MESH_DELTA_STEP_MM)state.snapshots++;
            }
        }
        int globals=0,cells=0,displayed=0,mismatched=0,saved=0,globalOnly=0,snapshotCount=0;var details=new ArrayList<Map<String,Object>>();
        for(var state:states.values()){
            globals+=state.global;cells+=state.cells;displayed+=state.displayed;mismatched+=state.mismatched;saved+=state.saved;snapshotCount+=state.snapshots;
            if(state.global>0&&state.cells==0)globalOnly++;
            if(details.size()<64){
                var mark=state.prediction;var item=new LinkedHashMap<String,Object>();
                item.put("x",mark.x());item.put("y",mark.y());item.put("z",mark.z());item.put("ownerY",mark.ownerY());
                item.put("predictedDepthMillimeters",mark.depthMillimeters());item.put("globalOccurrences",state.global);item.put("cellOccurrences",state.cells);
                item.put("displayedCellOccurrences",state.displayed);item.put("fieldMismatchOccurrences",state.mismatched);item.put("verifiedSavedOccurrences",state.saved);
                item.put("snapshotOccurrences",state.snapshots);item.put("minimumStoredDepthMillimeters",Float.isFinite(state.minDepth)?state.minDepth:-1f);
                item.put("maximumStoredDepthMillimeters",state.maxDepth);item.put("owners",List.copyOf(state.owners));details.add(Map.copyOf(item));
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("retiredGlobalOccurrences",globals);result.put("retiredCellOccurrences",cells);
        result.put("retiredDisplayedCellOccurrences",displayed);result.put("retiredCellFieldMismatchOccurrences",mismatched);
        result.put("retiredVerifiedSavedCellOccurrences",saved);result.put("retiredGlobalOnlyKeys",globalOnly);result.put("retiredSnapshotOccurrences",snapshotCount);
        result.put("retiredOccurrenceDetails",List.copyOf(details));return Map.copyOf(result);
    }
    /** Bounded scalar contact witnesses; reading diagnostics never decodes or walks triangle records. */
    public static Map<String,Object> localContactDiagnostics(){
        var out=new LinkedHashMap<String,Object>();out.put("generated",localGenerated);out.put("published",localPublished);out.put("drawn",localDrawn);
        out.put("patches",localPatches);out.put("patchCells",localPatchCells);out.put("patchNanos",localPatchNanos);out.put("worstPatchNanos",localWorstPatchNanos);
        out.put("worstPublishTicks",localWorstPublishTicks);out.put("worstDrawFrames",localWorstDrawFrames);
        out.put("scannedTriangles",localScannedTriangles);out.put("reusedTriangles",localReusedTriangles);out.put("refinedTriangles",localRefinedTriangles);
        out.put("retainedCoreTriangles",localRetainedCoreTriangles);out.put("coreProofChecks",localCoreProofChecks);
        out.put("retainedDominatedTriangles",localRetainedDominatedTriangles);out.put("dominanceProofChecks",localDominanceProofChecks);
        out.put("copiedBytes",localCopiedBytes);out.put("patchScanNanos",localPatchScanNanos);out.put("patchPackNanos",localPatchPackNanos);
        out.put("tessellationWallNanos",localTessellationWallNanos);out.put("packingWallNanos",localPackingWallNanos);
        out.put("parallelPackJobs",localParallelPackJobs);
        out.put("maxThresholdTests",localMaxThresholdTests);out.put("maxThresholdRejects",localMaxThresholdRejects);
        long[] scratch=SnowTriangleBuffer.diagnostics();
        out.put("triangleScratchAllocations",scratch[0]);out.put("triangleScratchReuses",scratch[1]);
        out.put("triangleScratchRetainedBytes",scratch[2]);out.put("triangleScratchBuffers",scratch[3]);
        out.put("reliefQueries",localReliefQueries);out.put("reliefCacheHits",localReliefCacheHits);out.put("reliefCandidates",localReliefCandidates);out.put("relitVertices",localRelitVertices);out.put("aoRayQueries",localAoRayQueries);out.put("aoRayCacheHits",localAoRayHits);out.put("reliefCacheEvictions",localReliefCacheEvictions);
        out.put("featureCandidateScans",localFeatureCandidates);out.put("featurePointsInside",localFeaturePointsInside);
        out.put("featureHeightQueries",localFeatureHeightQueries);
        out.put("pending",localGenerated-localDrawn);out.put("eventDrops",localEventDrops);
        out.put("activePredictions",localContacts.size());out.put("retiredPredictions",retiredLocalContacts.size());
        out.put("canonicalRetirements",canonicalRetirements);out.put("unloadedRetirements",unloadedRetirements);out.put("physicalRetirements",physicalRetirements);
        out.put("unconfirmedAtTtl",ttlUnconfirmedContacts);out.put("unconfirmedAtCapacity",capacityUnconfirmedContacts);
        out.put("canonicalChecks",canonicalChecks);out.put("canonicalCacheHits",canonicalCacheHits);out.put("canonicalProbeSamples",canonicalProbeSamples);
        out.put("canonicalCoreProbeFailures",canonicalCoreProbeFailures);out.put("canonicalWorstCheckNanos",canonicalWorstNanos);
        int pastTtl=0;for(var local:localContacts.values())if(contactTick()-local.tick>LOCAL_CONTACT_TTL)pastTtl++;
        out.put("activeUnconfirmedPastTtl",pastTtl);if(!localContacts.isEmpty()||capacityUnconfirmedContacts>0)out.put("canonicalBacklog",canonicalBacklogSummary());
        boolean inspectRetired=localContacts.isEmpty()&&coarseUntil.isEmpty();out.put("retiredInspectionPerformed",inspectRetired);
        if(inspectRetired)out.putAll(retiredContactDiagnostics());
        var retainedPredicted=new HashSet<MarkKey>();for(var mesh:sceneMeshes())for(var mark:mesh.contacts){var identity=MarkKey.of(mark);if(localContacts.containsKey(identity)||retiredLocalContacts.containsKey(identity))retainedPredicted.add(identity);}
        out.put("retainedPredictedMarks",retainedPredicted.size());
        int coarse=0,fine=0;for(var mesh:sceneMeshes())for(var cell:mesh.cells.values())if(displayedMesh(cell.geometry.pos())==mesh){if(cell.approximate)coarse++;else fine++;}
        out.put("maxLocalApproximationErrorMm",maxLocalApproximationErrorMm);out.put("coarseCells",coarse);out.put("fineCells",fine);
        out.put("finePendingCells",coarseUntil.size());out.put("queuedFineCells",fineQueued.size());
        out.put("partialFineJobs",partialFineJobs);out.put("partialFineCells",partialFineCells);
        out.put("partialFineCommits",partialFineCommits);out.put("partialFineFallbacks",partialFineFallbacks);
        out.put("fineWitnesses",fineWitnesses.size());out.put("fineWitnessBytes",fineWitnessBytes);
        out.put("fineCaptureCandidates",fineCaptureCandidates);out.put("fineCaptureDirty",fineCaptureDirty);
        out.put("fineWitnessesCaptured",fineWitnessesCaptured);
        out.put("dirtyEnqueueCauses",reasonCounts(DirtyCause.values(),dirtyEnqueueCounts));
        out.put("fineCaptureDirtyCauses",reasonCounts(DirtyCause.values(),fineCaptureDirtyCauses));
        out.put("fineSelectionAttempts",fineSelectionAttempts);out.put("fineRejections",reasonCounts(FineReject.values(),fineRejectCounts));
        out.put("fineWitnessDropsDirty",fineWitnessDropsDirty);out.put("fineWitnessDropsRange",fineWitnessDropsRange);
        out.put("fineWitnessDropsPreparation",fineWitnessDropsPreparation);out.put("fineWitnessDropsSuperseded",fineWitnessDropsSuperseded);
        out.put("fineWitnessDropsBudget",fineWitnessDropsBudget);
        out.put("localFineFollowups",localFineFollowups.size());
        out.put("localOverBudgetLeaves",localOverBudgetLeaves);out.put("lastFinePublicationTick",lastFinePublicationTick);
        out.put("topologyBudgetFallbacks",topologyBudgetFallbacks.get());
        out.put("maxDraftTopologyWork",maxDraftTopologyWork.get());out.put("maxFineTopologyWork",maxFineTopologyWork.get());
        // Scalar lifecycle witnesses expose why a real provisional mesh is still waiting.
        var fineChunks=new LinkedHashMap<Long,Integer>();
        for(long position:fineQueued)fineChunks.merge(chunkKey(BlockPos.of(position)),1,Integer::sum);
        var fineStates=new ArrayList<Map<String,Object>>(fineChunks.size());int dirtyFine=0,blockedFine=0;
        for(var entry:fineChunks.entrySet()){
            long key=entry.getKey();var frame=readyFrames.get(key);var blocked=blockedFrames.get(key);
            boolean waiting=blocked!=null&&frame!=null&&blocked.revision==frame.revision()
                    &&blocked.geometryRevision==geometryRevisions.getOrDefault(key,0L);
            boolean queued=dirty.contains(key),capturing=preparing!=null&&preparing.keys.contains(key),working=false;
            if(activeBuild!=null)for(var snapshot:activeBuild.snapshots)if(snapshot.key==key){working=true;break;}
            if(queued)dirtyFine++;if(waiting)blockedFine++;
            var state=new LinkedHashMap<String,Object>();state.put("chunkX",(int)key);state.put("chunkZ",(int)(key>>32));
            state.put("cells",entry.getValue());state.put("dirty",queued);state.put("preparing",capturing);state.put("worker",working);
            state.put("workerFinished",working&&activeBuild.finished);state.put("workerCancelled",working&&activeBuild.cancelled);
            state.put("blocked",waiting?blocked.reason:"");state.put("sourceReady",frame!=null);
            state.put("lastBuildStartedTick",lastFineBuildStarted.getOrDefault(key,-1L));fineStates.add(Map.copyOf(state));
        }
        out.put("dirtyFineChunks",dirtyFine);out.put("blockedFineChunks",blockedFine);out.put("fineQueueStates",List.copyOf(fineStates));
        var prepared=PREPARED_CACHE.get();out.put("preparedCacheHits",prepared.hits);out.put("preparedCacheMisses",prepared.misses);out.put("preparedCacheSize",prepared.size());
        out.put("cpuMeshPoolBytes",cpuMeshPoolBytes);out.put("cpuMeshPoolPeakBytes",cpuMeshPoolPeakBytes);out.put("cpuMeshPoolSlots",cpuMeshSlots.size());
        out.put("cpuMeshPoolAllocations",cpuMeshPoolAllocations);out.put("cpuMeshPoolReuses",cpuMeshPoolReuses);out.put("cpuMeshPoolFallbacks",cpuMeshPoolFallbacks);
        out.put("cpuMeshPoolEvictions",cpuMeshPoolEvictions);out.put("cpuMeshPoolBytesAvoided",cpuMeshPoolBytesAvoided);
        out.put("contactPatchBuffer",ContactPatchBuffer.diagnostics());
        var payloads=Collections.newSetFromMap(new IdentityHashMap<SnowMeshBuffer,Boolean>());
        long payloadBytes=0;int drawGroups=0;
        for(var mesh:sceneMeshes()){drawGroups+=mesh.groups.size();for(var cell:mesh.cells.values())payloads.add(cell.range.source());}
        for(var source:payloads)payloadBytes+=source.data.remaining();
        out.put("payloadSources",payloads.size());out.put("payloadBytes",payloadBytes);out.put("drawGroups",drawGroups);
        out.put("gpuPayloadSources",sourceGpu.size());out.put("uploadedBytes",sourceUploadedBytes);
        out.put("retiredGpuPayloads",sourceRetiredBuffers);out.put("exactRangeReuses",sourceExactRangeReuses);
        out.put("payloadCopies",sourcePayloadCopies);out.put("rangeRepacks",rangeRepacks);out.put("rangeRepackBytes",rangeRepackBytes);
        out.put("rangeRepackStale",rangeRepackStale);out.put("rangeRepackFailures",rangeRepackFailures);out.put("rangeRepackPending",rangeRepack!=null);
        out.put("weatherPressureDeferrals",weatherPressureDeferrals);out.put("urgentPressureDeferrals",urgentPressureDeferrals);
        out.put("weatherPredictionUpdates",weatherPredictionUpdates);out.put("weatherPredictionBurials",weatherPredictionBurials);
        out.put("weatherPredictionUnmatched",weatherPredictionUnmatched);out.put("capacityDeferred",capacityUnconfirmedContacts);
        DRAFT_ERROR_MEMOS.metrics(out);AO_HORIZON_MEMOS.metrics(out);
        var localEnvelope=envelopeMetrics(FIELD_ENVELOPES.metrics());
        var backgroundEnvelope=envelopeMetrics(BACKGROUND_FIELD_ENVELOPES.metrics());
        for(var entry:localEnvelope.entrySet())out.put(entry.getKey(),entry.getValue()+backgroundEnvelope.get(entry.getKey()));
        out.put("localEnvelope",localEnvelope);out.put("backgroundEnvelope",backgroundEnvelope);
        out.put("fineContactBuildsDebounced",fineContactBuildsDebounced);out.put("fineContactBuildsEquivalent",fineContactBuildsEquivalent);out.put("fineContactBuildsStrictFields",fineContactBuildsStrictFields);
        var events=new ArrayList<Map<String,Object>>(localEvents.size());
        for(var local:localEvents){
            var event=new LinkedHashMap<String,Object>();event.put("id",local.id);event.put("x",local.mark.x());event.put("y",local.mark.y());event.put("z",local.mark.z());
            // Freeze the actual shown kernel, including inherited pressure, for durability witnesses.
            event.put("yaw",local.mark.yaw());event.put("width",local.mark.width());event.put("length",local.mark.length());
            event.put("depthMillimeters",local.mark.depthMillimeters());event.put("soleProfile",local.mark.soleProfile());event.put("vehicle",local.mark.vehicle());
            event.put("retirementReason",local.retirementReason);event.put("ttlUnconfirmed",local.ttlUnconfirmed);event.put("canonicalDeficitMillimeters",local.canonicalDeficit);
            event.put("requiredCanonicalMillimeters",local.canonicalRequired);event.put("canonicalCoreProbe",local.canonicalCoreProbe);
            event.put("channel",local.channel);event.put("ownerY",local.mark.ownerY());event.put("owners",List.copyOf(local.owners));event.put("drawnOwners",local.drawnOwners.size());event.put("generatedTick",local.tick);event.put("generatedNanos",local.nanos);event.put("generatedFrame",local.frame);
            event.put("appliedTick",local.appliedTick);event.put("appliedFrame",local.appliedFrame);event.put("appliedNanos",local.appliedNanos);event.put("meshRevision",local.meshRevision);
            event.put("eligibleFrame",local.eligibleFrame);event.put("eligibleTick",local.eligibleTick);event.put("eligibleOwners",List.copyOf(local.eligibleAt.keySet()));
            var ownerEvents=new ArrayList<Map<String,Object>>();
            for(long position:local.owners)ownerEvents.add(Map.of("owner",position,"eligibleFrame",local.eligibleAt.getOrDefault(position,-1L),"drawnFrame",local.drawnAt.getOrDefault(position,-1L)));
            event.put("ownerEvents",List.copyOf(ownerEvents));
            event.put("drawnTick",local.drawnTick);event.put("drawnFrame",local.drawnFrame);event.put("drawnNanos",local.drawnNanos);events.add(Map.copyOf(event));
        }
        out.put("events",List.copyOf(events));return Map.copyOf(out);
    }
    private static Map<String,Long> envelopeMetrics(SnowFieldEnvelope.Metrics metrics){
        var result=new LinkedHashMap<String,Long>();
        result.put("envelopeQueries",(long)metrics.queries());
        result.put("envelopePointHits",(long)metrics.pointHits());
        result.put("envelopePointMisses",(long)metrics.pointMisses());
        result.put("envelopeFullHistoryQueries",(long)metrics.fullHistoryQueries());
        result.put("envelopeDeltaKernelEvaluations",(long)metrics.deltaKernelEvaluations());
        result.put("envelopeFullKernelEvaluations",(long)metrics.fullKernelEvaluations());
        result.put("envelopeUpdatedPoints",(long)metrics.updatedPoints());
        result.put("envelopeReusedVersionPoints",(long)metrics.reusedVersionPoints());
        result.put("envelopeFullCandidateVisits",(long)metrics.fullCandidateVisits());
        result.put("envelopeDeltaCandidateVisits",(long)metrics.deltaCandidateVisits());
        result.put("envelopeSpatialQueries",(long)metrics.spatialQueries());
        result.put("envelopePreparedCreated",(long)metrics.preparedEntriesCreated());
        result.put("envelopePreparedReused",(long)metrics.preparedEntriesReused());
        result.put("envelopeTiles",(long)metrics.tiles());
        result.put("envelopePoints",(long)metrics.points());
        result.put("envelopeRetainedBytes",(long)metrics.retainedBytes());
        result.put("envelopeStaleQueries",(long)metrics.staleQueries());
        result.put("envelopeSourceVersionEvictions",(long)metrics.sourceVersionEvictions());
        result.put("envelopeTileEvictions",(long)metrics.tileEvictions());
        result.put("envelopePointEvictions",(long)metrics.pointEvictions());
        return Map.copyOf(result);
    }
    private static void tick(Minecraft client){
        if(client.level!=world){reset();world=client.level;}
        if(world==null||client.player==null)return;
        var removed=new HashSet<Long>();for(long key:columns.keySet())if(!loaded(key))removed.add(key);
        if(!removed.isEmpty()){
            shadowPrepared=null;
            var next=new HashMap<>(meshes);boolean ownerChanged=false;
            for(long key:removed){
                DRAFT_ERROR_MEMOS.unload(key);AO_HORIZON_MEMOS.unload(key);forgetFineChunk(key,true);localFineFollowups.remove(key);discardCpuMeshChunk(key);columns.remove(key);readyFrames.remove(key);blockedFrames.remove(key);committedAuthoritative.remove(key);pressureLineages.remove(key);fieldSignatures.remove(key);lastBuiltFieldContexts.remove(key);lastBuiltFieldGeometry.remove(key);lastFineBuildStarted.remove(key);next.remove(key);clearDirty(key);geometryRevisions.remove(key);
                if(activeBuild!=null&&activeBuild.dependencies.contains(key))activeBuild.cancelled=true;
                if(preparing!=null&&preparing.frames.containsKey(key))preparing.cancelled=true;
                ownerChanged|=meshes.containsKey(key);for(var iterator=gpu.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(entry.getKey().key==key){entry.getValue().close();iterator.remove();}}
            }
            var transfers=new HashMap<>(handoffs);
            transfers.keySet().removeIf(section->removed.contains(chunkKey(SectionPos.x(section),SectionPos.z(section))));
            handoffs=Map.copyOf(transfers);
            requiredNativeSections.keySet().removeIf(section->removed.contains(chunkKey(SectionPos.x(section),SectionPos.z(section))));
            nativeSectionAcks.keySet().removeIf(section->removed.contains(chunkKey(SectionPos.x(section),SectionPos.z(section))));
            meshes=Map.copyOf(next);if(ownerChanged)publishOwnership();shadowPrepared=null;ShadowCaptureCache.terrainChanged();
        }
        if(!handoffs.isEmpty()){
            var previous=handoffs;var remaining=new HashMap<>(previous);
            remaining.entrySet().removeIf(entry->acknowledged(entry.getKey(),entry.getValue().epoch));handoffs=Map.copyOf(remaining);
            if(remaining.size()!=previous.size())shadowPrepared=null;
            for(var transfer:previous.values())if(transfer.before!=null&&meshes.get(transfer.before.key)!=transfer.before
                    &&remaining.values().stream().noneMatch(value->value.before==transfer.before)){
                GpuMesh allocation=gpu.remove(transfer.before);Mesh current=meshes.get(transfer.before.key);
                if(allocation!=null){if(current!=null&&!gpu.containsKey(current))gpu.put(current,allocation);else allocation.close();}
            }
        }
        publishCompleted(client);
        maintainRangeCompaction();
        String dimension=world.dimension().identifier().toString();Set<Long> active=SnowSurfaceChunkInbox.activeChunks(dimension);
        if(!active.isEmpty()){
            if(bootstrapCenter==Long.MIN_VALUE){
                int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
                for(long key:active){int x=(int)key,z=(int)(key>>32);minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);}
                bootstrapCenter=chunkKey((minX+maxX)/2,(minZ+maxZ)/2);
            }
            for(var frame:SnowSurfaceChunkInbox.takeReady(dimension,(x,z)->loaded(chunkKey(x,z)),64))installFrame(frame);
            for(var iterator=readyFrames.keySet().iterator();iterator.hasNext();){
                long key=iterator.next();
                if(!active.contains(key)&&!meshes.containsKey(key)&&(preparing==null||!preparing.frames.containsKey(key))){
                    iterator.remove();columns.remove(key);blockedFrames.remove(key);clearDirty(key);localFineFollowups.remove(key);
                }
            }
        }else if(bootstrapCenter==Long.MIN_VALUE)installLegacy();
        // Capture the finite initial bundle even while a cancelled old-world worker exits.
        captureBootstrapCohort();
        expireLocalContacts();
        SnowLocalContacts.tick(client);
        if(activeBuild!=null)return;
        if(preparing==null)preparing=selectPreparation();
        advancePreparation();
    }
    private static void installFrame(SnowSurfaceChunkPayload.Chunk frame){
        long key=frame.key();var previous=readyFrames.get(key);if(previous!=null&&previous.revision()>=frame.revision())return;
        // Complete frames arrive only for active, loaded vanilla chunks. Retained residency follows
        // actual vanilla unload; a cumulative fixed cap would silently drop frame 257 and leave holes.
        var signature=fieldSignature(frame);boolean fieldChanged=fieldSignatures.get(key)!=signature;
        var old=columns.get(key);var fresh=new LinkedHashMap<Long,SnowSurfacePayload.Column>();Mesh mesh=meshes.get(key);
        for(var column:frame.columns()){
            long position=BlockPos.asLong(column.x(),column.y(),column.z());fresh.put(position,column);
            CellData published=mesh==null?null:mesh.cells.get(position);var before=old==null?null:old.get(position);
            boolean changed=published==null?!visualColumnMatches(column,before,true):!column.geometry().equals(published.geometry)
                    ||Math.abs(column.deltaMillimeters()-published.column.deltaMillimeters())>=MESH_DELTA_STEP_MM;
            if(changed)dirtyColumn(column.x(),column.z(),DirtyCause.COLUMN);
            else if(published!=null&&fieldChanged&&column.layers()==0&&column.deltaMillimeters()<=0)
                dirtyColumn(column.x(),column.z(),DirtyCause.DRY_CORNER);
        }
        if(old!=null)for(var entry:old.entrySet())if(!fresh.containsKey(entry.getKey()))dirtyColumn(entry.getValue().x(),entry.getValue().z(),DirtyCause.COLUMN);
        columns.put(key,fresh);fieldSignatures.put(key,signature);readyFrames.put(key,frame);
        var previousMarks=previous==null?snapshotAuthority(key,0,List.of()):snapshotAuthority(key,previous.revision(),previous.marks());
        var newMarks=snapshotAuthority(key,frame.revision(),frame.marks());
        queuePressureChanges(key,frame.revision(),previousMarks,newMarks);
        dirtyChangedMarks(previousMarks,newMarks,!urgentPressureAuthority.isEmpty()&&bootstrapNativeReady());
        var lineage=pressureLineages.get(key);if(lineage!=null)lineage.observeComplete(frame.revision());
        captureBootstrapCohort();
    }
    private static void installLegacy(){
        var payload=incoming;if(payload==null||payload.tick()==lastTick||!world.dimension().identifier().toString().equals(payload.dimension()))return;
        lastTick=payload.tick();
        for(var column:payload.columns()){
            var pos=new BlockPos(column.x(),column.y(),column.z());long key=chunkKey(pos);var chunk=columns.get(key);
            if(chunk==null){if(columns.size()>=MAX_OWNED_CHUNKS)continue;chunk=new LinkedHashMap<>();columns.put(key,chunk);}
            var before=chunk.put(pos.asLong(),column);Mesh mesh=meshes.get(key);CellData cell=mesh==null?null:mesh.cells.get(pos.asLong());
            if(cell==null?!visualColumnMatches(column,before,true):!column.geometry().equals(cell.geometry)
                    ||Math.abs(column.deltaMillimeters()-cell.column.deltaMillimeters())>=MESH_DELTA_STEP_MM)dirtyColumn(column.x(),column.z(),DirtyCause.COLUMN);
        }
        if(!marks.equals(payload.marks())){dirtyChangedMarks(marks,payload.marks(),false);marks=payload.marks();}
    }
    private static Map<Long,SnowSurfaceChunkPayload.Chunk> neighborhood(long centre,boolean initial){
        var owner=readyFrames.get(centre);if(owner==null||!loaded(centre))return null;
        var frames=new LinkedHashMap<Long,SnowSurfaceChunkPayload.Chunk>();int cx=(int)centre,cz=(int)(centre>>32);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            long key=chunkKey(cx+dx,cz+dz);var frame=readyFrames.get(key);var blocked=blockedFrames.get(key);
            boolean available=frame!=null&&loaded(key)&&frame.weatherTick()==owner.weatherTick()
                    &&(!initial||frame.revision()==owner.revision())
                    &&!(blocked!=null&&blocked.revision==frame.revision()&&blocked.geometryRevision==geometryRevisions.getOrDefault(key,0L));
            if(!available){if(initial||key==centre)return null;continue;}
            frames.put(key,frame);
        }
        // Ordinary frames already carry a complete one-cell halo. Incremental server updates
        // have different weather ticks; waiting for all eight neighbours would stall them forever.
        // Only the finite initial loading gate requires the coherent nine-frame bootstrap.
        return Map.copyOf(frames);
    }
    private static ColumnField field(long centre,Map<Long,SnowSurfaceChunkPayload.Chunk> frames){
        int minX=((int)centre-1)*16-1,minZ=((int)(centre>>32)-1)*16-1;
        int entries=0;var complete=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(frames.size());
        for(var entry:frames.entrySet()){
            complete.add(entry.getKey().longValue());var frame=entry.getValue();
            entries+=frame.columns().size()+frame.halo().size();
        }
        var depths=new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap(entries);
        depths.defaultReturnValue(Float.NaN);
        // Complete chunks replace all their vertical supports, including explicit absence.
        // Other halo observations retain their existing last-writer order.
        for(var frame:frames.values())for(var column:frame.halo())
            if(!complete.contains(chunkKey(column.x()>>4,column.z()>>4)))
                depths.put(BlockPos.asLong(column.x(),column.y(),column.z()),column.deltaMillimeters());
        for(var frame:frames.values())for(var column:frame.columns())
            depths.put(BlockPos.asLong(column.x(),column.y(),column.z()),column.deltaMillimeters());
        return new ColumnField(minX,minZ,depths);
    }
    private static boolean sameBootstrapColumn(SnowSurfacePayload.Column a,SnowSurfacePayload.Column b){
        return a!=null&&a.layers()==b.layers()&&a.mask()==b.mask()&&a.crust()==b.crust()
                &&Float.floatToIntBits(a.baseOffset())==Float.floatToIntBits(b.baseOffset())&&a.patches().equals(b.patches());
    }
    private static Map<Long,Long> bootstrapGeometryVersions(Map<Long,SnowSurfaceChunkPayload.Chunk> frames){
        var versions=new HashMap<Long,Long>();for(long key:frames.keySet())versions.put(key,geometryRevisions.getOrDefault(key,0L));
        return Map.copyOf(versions);
    }
    private static boolean sameBootstrapGeometry(Map<Long,SnowSurfacePayload.Column> frozen,SnowSurfaceChunkPayload.Chunk fresh){
        if(frozen.size()!=fresh.columns().size()+fresh.halo().size())return false;
        for(var column:fresh.columns())if(!sameBootstrapColumn(frozen.get(BlockPos.asLong(column.x(),column.y(),column.z())),column))return false;
        for(var column:fresh.halo())if(!sameBootstrapColumn(frozen.get(BlockPos.asLong(column.x(),column.y(),column.z())),column))return false;
        return true;
    }
    private static void requestFreshBootstrap(String reason){
        if(!bootstrapNeedsFreshCohort){bootstrapNeedsFreshCohort=true;bootstrapRetryGeneration++;}
        bootstrapCohortState=reason;
    }
    private static void invalidateBootstrapCohort(String reason){
        var cohort=bootstrapCohort;if(cohort==null)return;clearBootstrapNativeMismatch();
        var rejected=new HashMap<Long,Long>();for(var frame:cohort.frames.values())rejected.put(frame.key(),frame.revision());
        rejectedBootstrapSources.put(cohort.weatherTick,Map.copyOf(rejected));
        while(rejectedBootstrapSources.size()>64)rejectedBootstrapSources.remove(rejectedBootstrapSources.keySet().iterator().next());
        bootstrapCohort=null;bootstrapCohortInvalidations++;
        // Each distinct rejected immutable candidate receives its own idempotent retry token.
        bootstrapNeedsFreshCohort=false;requestFreshBootstrap(reason);
        if(preparing!=null&&preparing.initial)preparing.cancelled=true;
        if(activeBuild!=null&&activeBuild.initial)activeBuild.cancelled=true;
    }
    private static void waitForBootstrapNative(BootstrapCohort cohort,String reason){
        if(cohort.nativeReady)bootstrapCohort=new BootstrapCohort(cohort.epoch,cohort.weatherTick,cohort.geometryEpoch,
                cohort.frames,cohort.geometryColumns,cohort.geometryVersions,false);
        bootstrapCohortState=reason;
    }
    private static void clearBootstrapNativeMismatch(){
        bootstrapNativeMismatchSince=0;bootstrapNativeMismatchCapture=-1;bootstrapNativeMismatchVersions=Map.of();
    }
    private static boolean bootstrapNativeWindowReady(){
        if(world==null||bootstrapCenter==Long.MIN_VALUE)return false;
        int cx=(int)bootstrapCenter,cz=(int)(bootstrapCenter>>32);
        // describe() derives edge masks/crust from up to two native blocks around
        // the owned 3x3. Its outermost owners therefore also need this finite border.
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)if(!loaded(chunkKey(cx+dx,cz+dz)))return false;
        return true;
    }
    private static void waitForBootstrapSupport(BootstrapCohort cohort,Map<Long,Long> versions,String reason){
        if(!bootstrapNativeWindowReady()){
            clearBootstrapNativeMismatch();waitForBootstrapNative(cohort,reason+"; waiting for native geometry border");return;
        }
        long now=System.nanoTime();
        if(bootstrapNativeMismatchSince==0||bootstrapNativeMismatchCapture!=bootstrapCohortCaptures||!versions.equals(bootstrapNativeMismatchVersions)){
            bootstrapNativeMismatchSince=now;bootstrapNativeMismatchCapture=bootstrapCohortCaptures;bootstrapNativeMismatchVersions=versions;
        }else if(now-bootstrapNativeMismatchSince>=BOOTSTRAP_NATIVE_STABLE_NANOS){
            invalidateBootstrapCohort("complete native support mismatch remained stable for resnapshot: "+reason);return;
        }
        waitForBootstrapNative(cohort,reason);
    }
    private static boolean allBootstrapFramesPresent(){
        int cx=(int)bootstrapCenter,cz=(int)(bootstrapCenter>>32);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            long key=chunkKey(cx+dx,cz+dz);if(!readyFrames.containsKey(key)||!loaded(key))return false;
        }
        return true;
    }
    /** A coherent bundle is retained independently of readyFrames and worker availability.
     * Geometry changes reject it; missing native support waits without request-token churn. */
    private static void captureBootstrapCohort(){
        if(bootstrapDone||bootstrapCenter==Long.MIN_VALUE||world==null)return;
        var cohort=bootstrapCohort;
        if(cohort==null){
            var frames=neighborhood(bootstrapCenter,true);
            if(frames==null){
                // A slow client can first observe nine ordinary replacements after the finite
                // initial bundle was superseded in the inbox. Request one fresh group, once.
                if(allBootstrapFramesPresent())requestFreshBootstrap("nine ready frames have no coherent unblocked initial weather snapshot");
                return;
            }
            long tick=frames.get(bootstrapCenter).weatherTick();var rejected=rejectedBootstrapSources.get(tick);
            // A paused weather clock may legitimately supply a fresh coherent retry.
            // Require every owner to advance beyond the rejected source revision.
            if(rejected!=null)for(var frame:frames.values())if(frame.revision()<=rejected.getOrDefault(frame.key(),Long.MIN_VALUE))return;
            var geometry=new HashMap<Long,Map<Long,SnowSurfacePayload.Column>>();
            for(var frame:frames.values()){
                var entries=new HashMap<Long,SnowSurfacePayload.Column>();
                for(var column:frame.columns())entries.put(BlockPos.asLong(column.x(),column.y(),column.z()),column);
                for(var column:frame.halo())entries.put(BlockPos.asLong(column.x(),column.y(),column.z()),column);
                geometry.put(frame.key(),Map.copyOf(entries));
            }
            cohort=new BootstrapCohort(epoch,tick,-1,frames,Map.copyOf(geometry),bootstrapGeometryVersions(frames),false);
            bootstrapCohort=cohort;bootstrapCohortCaptures++;lastBootstrapCohortTick=tick;clearBootstrapNativeMismatch();
        }
        if(cohort.epoch!=epoch){invalidateBootstrapCohort("initial cohort belongs to an older world epoch");return;}
        for(var entry:cohort.frames.entrySet()){
            long key=entry.getKey();if(!loaded(key)){clearBootstrapNativeMismatch();waitForBootstrapNative(cohort,"waiting for native owner chunk");return;}
            var latest=readyFrames.get(key);if(latest==null){clearBootstrapNativeMismatch();waitForBootstrapNative(cohort,"waiting for installed owner frame");return;}
            if(!sameBootstrapGeometry(cohort.geometryColumns.get(key),latest)){
                invalidateBootstrapCohort("server support geometry changed in initial cohort");return;
            }
        }
        var versions=bootstrapGeometryVersions(cohort.frames);
        if(cohort.nativeReady&&versions.equals(cohort.geometryVersions)){bootstrapNeedsFreshCohort=false;clearBootstrapNativeMismatch();return;}
        var observed=new HashMap<Long,SnowHostGeometry.Cell>();
        for(var frame:cohort.frames.values())for(var column:frame.columns()){
            var pos=new BlockPos(column.x(),column.y(),column.z());var state=world.getBlockState(pos);
            // Unsupported native cap models retain their native ownership in snapshot().
            if(SnowHostGeometry.nativeLayers(state)>0&&!com.dark.winter.client.TestSnowTerrainModels.supportsDynamic(state))continue;
            long position=pos.asLong();SnowHostGeometry.Cell actual;
            if(observed.containsKey(position))actual=observed.get(position);
            else{actual=SnowHostGeometry.describe(world,pos);observed.put(position,actual);}
            if(!column.geometry().equals(actual)){
                if(cohort.geometryEpoch>=0)invalidateBootstrapCohort("previously validated native support changed at "+pos);
                else waitForBootstrapSupport(cohort,versions,"waiting for native support at "+pos+" layers="+column.layers()+"/"+(actual==null?-1:actual.layers()));
                return;
            }
        }
        bootstrapCohort=new BootstrapCohort(cohort.epoch,cohort.weatherTick,geometryRevision,cohort.frames,cohort.geometryColumns,versions,true);
        bootstrapNeedsFreshCohort=false;clearBootstrapNativeMismatch();bootstrapCohortState="coherent initial cohort retained and native geometry validated";
    }
    private static boolean largeInstalledWeatherChange(long key){
        var shown=meshes.get(key);var installed=readyFrames.get(key);
        if(shown==null||installed==null)return false;
        for(var column:installed.columns()){
            var cell=shown.cells.get(BlockPos.asLong(column.x(),column.y(),column.z()));
            if(cell!=null&&Math.abs(column.deltaMillimeters()-cell.column.deltaMillimeters())>=32)return true;
        }
        return false;
    }
    private static int chunkDistanceSquared(long key,int centreX,int centreZ){
        int dx=(int)key-centreX,dz=(int)(key>>32)-centreZ;
        return dx*dx+dz*dz;
    }
    /** The complete foot/contact halo must be owned before the loading gate opens. */
    private static List<Long> bootstrapSpawnKeys(Set<Long> available,AABB feet){
        var area=feet.inflate(.75,0,.75);var keys=new ArrayList<Long>(4);
        int minX=(int)Math.floor(area.minX)>>4,maxX=(int)Math.floor(area.maxX)>>4;
        int minZ=(int)Math.floor(area.minZ)>>4,maxZ=(int)Math.floor(area.maxZ)>>4;
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
            long key=chunkKey(x,z);if(!available.contains(key))return List.of();
            keys.add(key);
        }
        return List.copyOf(keys);
    }
    private static List<Long> bootstrapInitialKeys(Map<Long,SnowSurfaceChunkPayload.Chunk> frames,AABB feet){
        var spawn=bootstrapSpawnKeys(frames.keySet(),feet);if(spawn.isEmpty())return List.of();
        var required=new LinkedHashSet<Long>(spawn);
        // Native terrain cannot represent saved deformations. Keep nearby old trails
        // behind the loading gate until their actual dynamic replacement is ready.
        for(var frame:frames.values())if(!frame.marks().isEmpty())required.add(frame.key());
        return List.copyOf(required);
    }
    /** Defer only the scheduling copy; pending ownership must leave its dirty/fine request queued. */
    private static void skipPendingHandoffPreparations(List<Long> ordered,java.util.function.LongPredicate pendingHandoff){
        ordered.removeIf(key->pendingHandoff.test(key));
    }
    private static Preparation selectPreparation(){
        if(bootstrapCenter!=Long.MIN_VALUE&&!bootstrapDone){
            captureBootstrapCohort();var cohort=bootstrapCohort;
            if(cohort==null||cohort.epoch!=epoch||!cohort.nativeReady)return null;
            var frames=cohort.frames;
            // Keep the coherent 3x3 sampling halo and cover both feet at chunk borders.
            // Remaining chunks retain native ownership and their dirty queue entries.
            var spawn=Minecraft.getInstance().player;if(spawn==null)return null;
            var keys=bootstrapInitialKeys(frames,spawn.getBoundingBox());
            if(keys.isEmpty())return null;
            return preparation(keys,frames,field(bootstrapCenter,frames),true);
        }
        // Quiet footprints and large nearby weather changes use the priority slots.
        // Keep one routine FIFO selection after three priority selections.
        var fineChunks=new LinkedHashSet<Long>();for(long position:fineQueued)fineChunks.add(chunkKey(BlockPos.of(position)));
        long now=contactTick();var activeContactChunks=new HashSet<Long>();
        for(var entry:coarseUntil.entrySet())if(entry.getValue()>now)activeContactChunks.add(chunkKey(BlockPos.of(entry.getKey())));
        var player=Minecraft.getInstance().player;
        int centreX=player==null?0:player.blockPosition().getX()>>4;
        int centreZ=player==null?0:player.blockPosition().getZ()>>4;
        var priority=new ArrayList<Long>();var nearWeather=new ArrayList<Long>();var routine=new ArrayList<Long>();
        for(long key:dirty){
            if(fineChunks.contains(key))priority.add(key);
            else if(player!=null&&Math.abs((int)key-centreX)<=2&&Math.abs((int)(key>>32)-centreZ)<=2&&largeInstalledWeatherChange(key))
                nearWeather.add(key);
            else routine.add(key);
        }
        for(long key:fineChunks)if(!dirty.contains(key))priority.add(key);
        // List.sort is stable, so equal distances keep their original enqueue order.
        nearWeather.sort(Comparator.comparingInt(key->chunkDistanceSquared(key,centreX,centreZ)));
        var ordered=new ArrayList<Long>(priority.size()+nearWeather.size()+routine.size());
        boolean preferPriority=consecutivePriorityPreparations<3;
        if(preferPriority){
            ordered.addAll(priority);ordered.addAll(nearWeather);ordered.addAll(routine);
        }else{
            // Even a queue containing only nearby weather and quiet owners
            // gives the full field a turn after the three priority slots.
            ordered.addAll(routine);ordered.addAll(nearWeather);
            for(long key:priority)if(dirty.contains(key))ordered.add(key);
            for(long key:priority)if(!dirty.contains(key))ordered.add(key);
        }
        // A finished replacement cannot publish over an unacknowledged ownership transfer.
        // Skip it before occupying the sole worker, allowing unrelated new chunks to progress.
        skipPendingHandoffPreparations(ordered,SnowSurfaceClient::chunkHandoff);
        for(long key:ordered){
            if(!columns.containsKey(key)){clearDirty(key);forgetFineChunk(key,false);continue;}
            if(!dirty.contains(key)&&fineChunks.contains(key)){
                FinePlan plan=selectFinePlan(key);
                if(plan!=null){
                    consecutivePriorityPreparations=Math.min(3,consecutivePriorityPreparations+1);
                    var prep=new Preparation(epoch,List.of(key),Map.of(),plan.basis.geometry,null,false);
                    prep.fine=plan;
                    return prep;
                }
                partialFineFallbacks++;markDirty(key,DirtyCause.PARTIAL_FALLBACK);forgetFineChunk(key,false);
            }
            if(bootstrapCenter==Long.MIN_VALUE){consecutivePriorityPreparations=0;return preparation(List.of(key),Map.of(),null,false);}
            var frames=neighborhood(key,false);if(frames==null)continue;
            if(activeContactChunks.contains(key)&&debounceContactFine(key,fieldContext(frames)))continue;
            boolean nearbyWeather=nearWeather.contains(key);
            if(nearbyWeather)nearWeatherPreparations++;
            consecutivePriorityPreparations=preferPriority&&(fineChunks.contains(key)||nearbyWeather)
                    ?Math.min(3,consecutivePriorityPreparations+1):0;
            return preparation(List.of(key),frames,field(key,frames),false);
        }
        return null;
    }
    private static FinePlan selectFinePlan(long key){
        fineSelectionAttempts++;
        if(fineWitnesses.isEmpty()){rejectFine(FineReject.EMPTY_WITNESS_SET);return null;}
        Mesh current=meshes.get(key);FineBasis basis=null;
        if(current==null){rejectFine(FineReject.MESH_MISSING);return null;}
        boolean foundWitness=false,foundRange=false;
        var inputs=new ArrayList<CellSnapshot>();var ranges=new LinkedHashMap<Long,SnowMeshRange>();
        for(long position:fineQueued){
            var witness=fineWitnesses.get(position);
            if(witness==null||witness.basis.key!=key||basis!=null&&witness.basis!=basis)continue;
            foundWitness=true;
            var range=witness.range.get();var cell=current.cells.get(position);
            if(range==null||cell==null||!cell.approximate||cell.range!=range)continue;
            foundRange=true;
            if(basis==null){
                if(!fineBasisCurrent(witness.basis,current))continue;
                basis=witness.basis;
            }
            inputs.add(witness.input);ranges.put(position,range);
            if(inputs.size()>=FINE_BATCH_LIMIT)break;
        }
        if(basis==null){
            if(!foundWitness)rejectFine(FineReject.NO_QUEUED_WITNESS);
            else if(!foundRange)rejectFine(FineReject.STALE_RANGE);
            return null;
        }
        var snapshot=new BuildSnapshot(basis.epoch,basis.key,basis.geometry.get(basis.key),basis.sourceRevision,++revision,
                basis.origin,List.copyOf(inputs),List.of(),List.of(),basis.lighting,Map.of());
        return new FinePlan(basis,snapshot,Map.copyOf(ranges));
    }
    private static boolean fineBasisCurrent(FineBasis basis,Mesh current){
        if(current==null)return rejectFine(FineReject.MESH_MISSING);
        if(basis.epoch!=epoch)return rejectFine(FineReject.EPOCH);
        if(dirty.contains(basis.key))return rejectFine(FineReject.DIRTY);
        if(!basis.origin.equals(current.origin))return rejectFine(FineReject.ORIGIN);
        for(var dependency:basis.geometry.entrySet()){
            long key=dependency.getKey();
            if(!loaded(key))return rejectFine(FineReject.DEPENDENCY_UNLOADED);
            if(chunkHandoff(key))return rejectFine(FineReject.HANDOFF);
            if(dependency.getValue().longValue()!=geometryRevisions.getOrDefault(key,0L).longValue())
                return rejectFine(FineReject.GEOMETRY_REVISION);
        }
        var mismatch=fineLightingMismatch(basis.roots,basis.lighting,SnowSurfaceClient::displayedCellAt);
        return mismatch==null||rejectFine(mismatch);
    }
    private static CellData displayedCellAt(long position){
        Mesh mesh=displayedMesh(BlockPos.of(position));return mesh==null?null:mesh.cells.get(position);
    }
    /** Validate presence as well as value over the same complete draft halo, including empty slots. */
    private static boolean fineLightingMatches(List<CellSnapshot> roots,Map<Long,CellSnapshot> lighting,
            java.util.function.LongFunction<CellData> displayed){
        return fineLightingMismatch(roots,lighting,displayed)==null;
    }
    private static FineReject fineLightingMismatch(List<CellSnapshot> roots,Map<Long,CellSnapshot> lighting,
            java.util.function.LongFunction<CellData> displayed){
        for(var root:roots){
            var pos=root.geometry.pos();
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(int dy=-3;dy<=3;dy++){
                long position=BlockPos.asLong(pos.getX()+dx,pos.getY()+dy,pos.getZ()+dz);
                var frozen=lighting.get(position);var shown=displayed.apply(position);
                if(!sameFineObservation(frozen,shown))return fineObservationRejection(frozen,shown);
            }
        }
        return null;
    }
    private static FineReject fineObservationRejection(CellSnapshot frozen,CellData shown){
        if(frozen==null)return FineReject.HALO_OWNER_ADDED;
        if(shown==null)return FineReject.HALO_OWNER_REMOVED;
        if(!frozen.geometry.equals(shown.geometry))return FineReject.HALO_GEOMETRY;
        if(!frozen.column.equals(shown.column))
            return visualColumnMatches(frozen.column,shown.column,true)?FineReject.HALO_COLUMN_DENSITY:FineReject.HALO_COLUMN;
        if(!Arrays.equals(frozen.corners,shown.fineCorners))return FineReject.HALO_CORNERS;
        if(!frozen.contacts.equals(shown.contacts))return fineContactRejection(frozen.contacts,shown.contacts);
        if(frozen.blockLight!=shown.blockLight||frozen.skyLight!=shown.skyLight)return FineReject.HALO_LIGHT;
        return FineReject.HALO_SKIRT;
    }
    private record FineContactKey(MarkKey placement,int depthBits) {
        static FineContactKey of(SnowSurfacePayload.Mark mark){
            return new FineContactKey(MarkKey.of(mark),Float.floatToIntBits(mark.depthMillimeters()));
        }
    }
    private static FineReject fineContactRejection(List<SnowSurfacePayload.Mark> frozen,List<SnowSurfacePayload.Mark> shown){
        if(frozen.size()!=shown.size())return FineReject.HALO_CONTACT_FIELD;
        boolean ordered=true;
        for(int i=0;i<frozen.size();i++){
            var a=frozen.get(i);var b=shown.get(i);
            if(!MarkKey.of(a).matches(b)||Float.compare(a.depthMillimeters(),b.depthMillimeters())!=0){ordered=false;break;}
        }
        if(ordered)return FineReject.HALO_CONTACT_STRENGTH;
        var counts=new HashMap<FineContactKey,Integer>();
        for(var mark:frozen)counts.merge(FineContactKey.of(mark),1,Integer::sum);
        for(var mark:shown){
            var key=FineContactKey.of(mark);var count=counts.get(key);
            if(count==null)return FineReject.HALO_CONTACT_FIELD;
            if(count==1)counts.remove(key);else counts.put(key,count-1);
        }
        return FineReject.HALO_CONTACT_ORDER;
    }
    private static boolean sameFineObservation(CellSnapshot frozen,CellData shown){
        if(frozen==null)return shown==null;
        return shown!=null&&frozen.column.equals(shown.column)&&frozen.geometry.equals(shown.geometry)
                &&Arrays.equals(frozen.corners,shown.fineCorners)&&frozen.contacts.equals(shown.contacts)
                &&frozen.blockLight==shown.blockLight&&frozen.skyLight==shown.skyLight&&frozen.skirtSides==shown.skirtSides;
    }
    private static Preparation preparation(List<Long> keys,Map<Long,SnowSurfaceChunkPayload.Chunk> frames,ColumnField field,boolean initial){
        var versions=new HashMap<Long,Long>();
        for(long key:keys){
            clearDirty(key);localFineFollowups.remove(key);
            int witnessesBefore=fineWitnesses.size();forgetFineChunk(key,false);
            fineWitnessDropsPreparation+=witnessesBefore-fineWitnesses.size();
            versions.put(key,geometryRevisions.getOrDefault(key,0L));
        }
        return new Preparation(epoch,List.copyOf(keys),frames,Map.copyOf(versions),field,initial);
    }
    /** One chunk's world observations per tick; the complete coherent bundle then enters the one worker. */
    private static void advancePreparation(){
        Preparation prep=preparing;if(prep==null)return;boolean invalid=prep.cancelled||prep.epoch!=epoch;
        for(long key:prep.keys)invalid|=!loaded(key)||prep.geometry.get(key).longValue()!=geometryRevisions.getOrDefault(key,0L).longValue();
        for(long key:prep.frames.keySet())invalid|=!loaded(key);
        if(invalid){
            if(prep.fine!=null)fallbackFine(prep.fine);
            else for(long key:prep.keys)markDirty(key,DirtyCause.WORKER_RETRY);
            preparing=null;return;
        }
        long key=prep.keys.get(prep.snapshots.size()),started=System.nanoTime();buildingKey=key;
        try{
            prep.snapshots.add(prep.fine==null?snapshot(key,prep.frames.get(key),prep.field,prep.fieldContext):prep.fine.snapshot);
        }
        catch(FrameNotReady notReady){
            var frame=prep.frames.get(key);if(frame!=null)blockedFrames.put(key,new BlockedFrame(frame.revision(),geometryRevisions.getOrDefault(key,0L),notReady.getMessage()));
            if(prep.initial)invalidateBootstrapCohort("initial snapshot rejected: "+notReady.getMessage());
            for(long failed:prep.keys)markDirty(failed,DirtyCause.WORKER_RETRY);
            preparing=null;return;
        }finally{buildingKey=Long.MIN_VALUE;snapshotNanos+=System.nanoTime()-started;}
        if(prep.snapshots.size()<prep.keys.size())return;
        Set<Long> dependencies=prep.fine!=null?prep.fine.basis.geometry.keySet()
                :prep.frames.isEmpty()?Set.copyOf(prep.keys):prep.frames.keySet();
        BuildJob job=new BuildJob(List.copyOf(prep.snapshots),dependencies,prep.initial,prep.fine,
                prep.fieldContext,List.copyOf(prep.frames.keySet()));
        if(prep.fine!=null){partialFineJobs++;partialFineCells+=prep.fine.ranges.size();}
        preparing=null;activeBuild=job;
        if(!prep.initial)for(long builtKey:prep.keys)lastFineBuildStarted.put(builtKey,contactTick());
        if(tessellator==null)tessellator=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"WKE snow tessellator");thread.setDaemon(true);return thread;});
        tessellator.execute(()->{
            long workerStarted=System.nanoTime();
            try{var result=new ArrayList<Mesh>();for(var snapshot:job.snapshots){checkCancelled(job);result.add(build(snapshot,job));}job.result=List.copyOf(result);}
            catch(CancellationException ignored){}catch(Throwable failure){job.failure=failure;}
            finally{job.elapsedNanos=System.nanoTime()-workerStarted;job.finished=true;}
        });
    }
    private static void publishOwnership(){
        var positions=new HashSet<Long>();for(var mesh:meshes.values())positions.addAll(mesh.cells.keySet());
        ownership=new Ownership(ownership.epoch+1,Set.copyOf(positions));
    }
    private static boolean hasSection(Mesh mesh,long section){
        if(mesh==null)return false;for(var group:mesh.groups)if(group.section==section)return true;return false;
    }
    private static boolean replacesLowerFringe(SnowHostGeometry.Cell cell){
        return !cell.generic()&&cell.layers()>0&&cell.baseOffset()==0&&(cell.mask()&0xf0)!=0;
    }
    private static void nativeOwnerSections(Set<Integer> sections,SnowHostGeometry.Cell cell){
        sections.add(cell.pos().getY()>>4);
        if(replacesLowerFringe(cell))sections.add((cell.pos().getY()-1)>>4);
    }
    private static void fringeDependencies(Set<Long> dependencies,Mesh mesh,long ownerSection,Set<Integer> changed){
        if(mesh==null)return;
        for(var cell:mesh.cells.values())if(section(cell.geometry.pos())==ownerSection&&replacesLowerFringe(cell.geometry)){
            var pos=cell.geometry.pos();int sy=(pos.getY()-1)>>4;
            if(changed.contains(sy))dependencies.add(SectionPos.asLong(pos.getX()>>4,sy,pos.getZ()>>4));
        }
    }
    private static Set<Long> nativeDependencies(Mesh before,Mesh after,long ownerSection,Set<Integer> changed){
        var dependencies=new HashSet<Long>();dependencies.add(ownerSection);
        fringeDependencies(dependencies,before,ownerSection,changed);fringeDependencies(dependencies,after,ownerSection,changed);
        return Set.copyOf(dependencies);
    }
    private static boolean needsVisualFollowup(BuildJob sampled,Map<Long,SnowSurfaceChunkPayload.Chunk> latestFrames){
        // The field can use a complete neighbour in place of the centre's halo.
        // Also preserve the actual order of competing halo observations.
        if(latestFrames==null||latestFrames.isEmpty()||!sampled.fieldOrder.equals(List.copyOf(latestFrames.keySet())))return true;
        for(var entry:latestFrames.entrySet()){
            var previous=sampled.fieldContext.get(entry.getKey());var fresh=entry.getValue();
            if(previous==null||!visualColumnsMatch(previous.columns,fresh.columns(),true)
                    ||!visualColumnsMatch(previous.halo,fresh.halo(),true))return true;
        }
        return false;
    }
    private static void publishCompleted(Minecraft client){
        BuildJob job=activeBuild;if(job==null||!job.finished)return;
        if(job.fine!=null){publishFineCompleted(job);return;}
        boolean invalid=job.cancelled;
        var cohort=bootstrapCohort;
        if(job.initial){
            invalid|=cohort==null||cohort.epoch!=epoch||!cohort.nativeReady;
            if(cohort!=null)for(var sampled:job.snapshots){
                var source=cohort.frames.get(sampled.key);invalid|=source==null||source.revision()!=sampled.sourceRevision;
            }
        }
        for(var snapshot:job.snapshots)invalid|=snapshot.epoch!=epoch||!columns.containsKey(snapshot.key)
                ||snapshot.geometryRevision!=geometryRevisions.getOrDefault(snapshot.key,0L).longValue()||containsRetiredPrediction(snapshot)||stalePressureSnapshot(snapshot);
        if(invalid){
            activeBuild=null;workerNanos+=job.elapsedNanos;discardedBuilds+=job.snapshots.size();
            for(var s:job.snapshots)if(columns.containsKey(s.key))markDirty(s.key,DirtyCause.WORKER_RETRY);return;
        }
        if(job.failure instanceof com.winterkore.engine.surface.geometry.ConformingSnowMesh.WorkLimitExceeded failure){
            activeBuild=null;workerNanos+=job.elapsedNanos;discardedBuilds+=job.snapshots.size();
            for(var sampled:job.snapshots){
                var latest=readyFrames.get(sampled.key);
                if(latest!=null&&latest.revision()>sampled.sourceRevision){markDirty(sampled.key,DirtyCause.WORKER_RETRY);continue;}
                // A planning failure has no replacement cap. Keep the visible mesh and
                // block this exact input; a later source or support change can retry.
                if(latest!=null){
                    blockedFrames.put(sampled.key,new BlockedFrame(sampled.sourceRevision,sampled.geometryRevision,failure.getMessage()));
                    clearDirty(sampled.key);
                }
                coarseUntil.keySet().removeIf(position->chunkKey(BlockPos.of(position))==sampled.key);
                fineQueued.removeIf(position->chunkKey(BlockPos.of(position))==sampled.key);
            }
            return;
        }
        if(job.failure!=null){activeBuild=null;throw new IllegalStateException("WKE snow tessellation failed",job.failure);}
        if(job.result==null){activeBuild=null;for(var s:job.snapshots)markDirty(s.key,DirtyCause.WORKER_RETRY);return;}
        // An in-flight terrain job may have sampled before a later footfall. Reconcile its
        // contact cells to the newest server/local pressure before it can replace the display.
        var refreshed=new ArrayList<Mesh>(job.result.size());var publicationAuthority=new HashMap<Long,List<SnowSurfacePayload.Mark>>();
        for(var made:job.result){
            BuildSnapshot sampled=null;for(var candidate:job.snapshots)if(candidate.key==made.key){sampled=candidate;break;}
            if(sampled==null)throw new IllegalStateException("Snow build has no source snapshot");
            publicationAuthority.put(made.key,sampled.authoritativeContacts);
            var latest=readyFrames.get(made.key);
            if(!job.initial&&latest!=null&&!dirty.contains(made.key)&&needsVisualFollowup(job,neighborhood(made.key,false)))
                markDirty(made.key,DirtyCause.WORKER_VISUAL);
            if(latest!=null&&latest.revision()>sampled.sourceRevision){
                if(job.initial)markDirty(made.key,DirtyCause.WORKER_VISUAL);
                // Positive pressure can reach the next extraction, while filling,
                // removals and overflow still request a full field.
                queuePressureChanges(made.key,latest.revision(),sampled.authoritativeContacts,snapshotAuthority(made.key,latest.revision(),latest.marks()));
            }
            // Contact geometry sampled after the worker began already exists in the displayed mesh.
            // Carry those exact cell bytes forward; never run historical tessellation/AO on this thread.
            refreshed.add(carryLocalCells(made,meshes.get(made.key)));
        }
        job.result=List.copyOf(refreshed);
        var next=new HashMap<>(meshes);var changedSections=new LinkedHashMap<Long,Set<Integer>>();
        for(Mesh made:job.result){
            var before=meshes.get(made.key);next.put(made.key,made);Set<Integer> sections=new HashSet<>();
            for(var cell:made.cells.entrySet())if(before==null||!before.cells.containsKey(cell.getKey()))nativeOwnerSections(sections,cell.getValue().geometry);
            if(before!=null)for(var cell:before.cells.entrySet())if(!made.cells.containsKey(cell.getKey()))nativeOwnerSections(sections,cell.getValue().geometry);
            if(!sections.isEmpty())changedSections.put(made.key,sections);
        }
        // One outstanding ownership transition per native section: an intermediate upload can never orphan its cap.
        for(var entry:changedSections.entrySet())for(int sy:entry.getValue()){
            long key=entry.getKey(),section=SectionPos.asLong((int)key,sy,(int)(key>>32));var previous=handoffs.get(section);
            if(previous!=null&&!acknowledged(section,previous.epoch))return;
        }
        activeBuild=null;workerNanos+=job.elapsedNanos;boolean ownerChanged=!changedSections.isEmpty();
        long expected=ownership.epoch+(ownerChanged?1:0);var transitions=new HashMap<>(handoffs);
        for(Mesh made:job.result){
            // Fine/contact updates replace only the pending destination; the displayed source stays coherent until ACK.
            for(var entry:new ArrayList<>(transitions.entrySet())){
                long section=entry.getKey();
                if(chunkKey(SectionPos.x(section),SectionPos.z(section))==made.key){
                    var transfer=entry.getValue();transitions.put(section,new Handoff(transfer.epoch,transfer.before,hasSection(made,section)?made:null,transfer.nativeSections));
                }
            }
            for(int sy:changedSections.getOrDefault(made.key,Set.of())){
                long section=SectionPos.asLong((int)made.key,sy,(int)(made.key>>32));Mesh before=meshes.get(made.key);
                transitions.put(section,new Handoff(expected,hasSection(before,section)?before:null,hasSection(made,section)?made:null,
                        nativeDependencies(before,made,section,changedSections.getOrDefault(made.key,Set.of()))));
                requiredNativeSections.put(section,expected);
            }
        }
        if(job.initial){
            var required=new HashMap<Long,Long>();
            // Actual native white-fringe sections participate in the same initial ownership barrier.
            for(var entry:changedSections.entrySet())for(int sy:entry.getValue()){
                long key=entry.getKey();required.put(SectionPos.asLong((int)key,sy,(int)(key>>32)),expected);
            }
            bootstrapRequiredSections=Map.copyOf(required);
        }
        // Normal updates reuse the ring. Coexisting source/destination section groups must keep separate GPU buffers.
        for(Mesh made:job.result){
            Mesh before=meshes.get(made.key);
            if(before!=null&&transitions.values().stream().noneMatch(transfer->transfer.before==before)){
                GpuMesh reusable=gpu.remove(before);if(reusable!=null)gpu.put(made,reusable);
            }
        }
        meshes=Map.copyOf(next);handoffs=Map.copyOf(transitions);if(ownerChanged)publishOwnership();
        committedAuthoritative.putAll(publicationAuthority);
        for(var sampled:job.snapshots){lastBuiltFieldContexts.put(sampled.key,sampled.fieldContext);lastBuiltFieldGeometry.put(sampled.key,sampled.geometryRevision);}
        for(var made:job.result)for(var cell:made.cells.values()){
            if(cell.approximate&&!cell.fineBudgetLimited)continue;
            long position=cell.geometry.pos().asLong();
            if(coarseUntil.remove(position)!=null){
                fineQueued.remove(position);forgetFineWitness(position);
                if(!cell.fineBudgetLimited)lastFinePublicationTick=contactTick();
            }
        }
        observeLocalPublication();reconcileLocalContacts();
        builds+=job.result.size();shadowPrepared=null;ShadowCaptureCache.terrainChanged();
        for(var entry:changedSections.entrySet())for(int sy:entry.getValue()){
            long key=entry.getKey();client.levelExtractor.setSectionDirty((int)key,sy,(int)(key>>32));
        }
        if(job.initial){
            long committedSource=Long.MAX_VALUE;for(var sampled:job.snapshots)committedSource=Math.min(committedSource,sampled.sourceRevision);
            bootstrapCommittedSourceRevision=committedSource;
            bootstrapDone=true;bootstrapCohort=null;rejectedBootstrapSources.clear();bootstrapNeedsFreshCohort=false;clearBootstrapNativeMismatch();
            bootstrapCohortState="initial CPU ownership committed; native section acknowledgements remain required";
        }
    }
    private static void fallbackFine(FinePlan plan){
        if(plan.basis.epoch!=epoch)return;
        partialFineFallbacks++;
        long key=plan.basis.key;
        if(!dirty.contains(key))for(long position:plan.ranges.keySet()){
            var newer=fineWitnesses.get(position);
            if(newer!=null&&newer.basis!=plan.basis){
                // A newer contact already captured its own inputs. Let its
                // quiet deadline expire without destroying the fresh witness.
                for(long oldPosition:plan.ranges.keySet()){
                    var old=fineWitnesses.get(oldPosition);
                    if(old!=null&&old.basis==plan.basis)forgetFineWitness(oldPosition);
                }
                return;
            }
        }
        forgetFineChunk(key,false);localFineFollowups.remove(key);
        if(columns.containsKey(key))markDirty(key,DirtyCause.PARTIAL_FALLBACK);
    }
    private static void publishFineCompleted(BuildJob job){
        activeBuild=null;workerNanos+=job.elapsedNanos;
        var plan=job.fine;long key=plan.basis.key;Mesh current=meshes.get(key);
        if(job.cancelled||!fineQueued.containsAll(plan.ranges.keySet())||!fineBasisCurrent(plan.basis,current)){
            discardedBuilds++;fallbackFine(plan);return;
        }
        if(job.failure!=null&&!(job.failure instanceof com.winterkore.engine.surface.geometry.ConformingSnowMesh.WorkLimitExceeded))
            throw new IllegalStateException("WKE quiet snow refinement failed",job.failure);
        Mesh made=job.result==null?null:mergeQuietFine(current,job.result.getFirst(),plan.ranges,++revision);
        if(made==null){discardedBuilds++;fallbackFine(plan);return;}
        var next=new HashMap<>(meshes);next.put(key,made);meshes=Map.copyOf(next);
        GpuMesh allocation=gpu.remove(current);if(allocation!=null)gpu.put(made,allocation);
        for(long position:plan.ranges.keySet()){
            coarseUntil.remove(position);fineQueued.remove(position);forgetFineWitness(position);
        }
        partialFineCommits++;builds++;lastFinePublicationTick=contactTick();
        // The worker only improved these exact ranges. It did not commit newer
        // authority, change native ownership, or advance a whole-chunk field stamp.
        observeLocalPublication();reconcileLocalContacts();shadowPrepared=null;ShadowCaptureCache.terrainChanged();
    }
    private static Mesh mergeQuietFine(Mesh current,Mesh patch,Map<Long,SnowMeshRange> expected,long version){
        if(current==null||current.key!=patch.key||!current.origin.equals(patch.origin)||!patch.cells.keySet().equals(expected.keySet()))
            return null;
        for(var entry:expected.entrySet()){
            var shown=current.cells.get(entry.getKey());var refined=patch.cells.get(entry.getKey());
            if(shown==null||!shown.approximate||shown.range!=entry.getValue()||refined.approximate||refined.fineBudgetLimited
                    ||!shown.geometry.equals(refined.geometry)||!shown.column.equals(refined.column)
                    ||!Arrays.equals(shown.fineCorners,refined.fineCorners)||!shown.contacts.equals(refined.contacts)
                    ||shown.blockLight!=refined.blockLight||shown.skyLight!=refined.skyLight||shown.skirtSides!=refined.skirtSides)
                return null;
        }
        return mergeCells(current,patch,expected.keySet(),current.contacts,version);
    }
    /** All Level, model-support and lighting observations happen on the client thread. */
    private static BuildSnapshot snapshot(long key,SnowSurfaceChunkPayload.Chunk source,ColumnField field,Map<Long,FieldSignature> fieldContext){
        int cx=(int)key,cz=(int)(key>>32);BlockPos origin=new BlockPos(cx*16,0,cz*16);
        var localMarks=new ArrayList<SnowSurfacePayload.Mark>();
        if(source!=null)localMarks.addAll(source.marks());
        else for(var mark:marks)if(mark.x()+3>=origin.getX()&&mark.x()-3<=origin.getX()+16&&mark.z()+3>=origin.getZ()&&mark.z()-3<=origin.getZ()+16)localMarks.add(mark);
        var authoritativeMarks=snapshotAuthority(key,source==null?0:source.revision(),List.copyOf(localMarks));
        localMarks=new ArrayList<>(overlayContacts(key,authoritativeMarks,true));
        Collection<SnowSurfacePayload.Column> observed=source==null?columns.get(key).values():source.columns();
        List<SnowSurfacePayload.Column> halo=source==null?List.of():source.halo();
        int lightingSize=observed.size()+halo.size();
        var cells=new ArrayList<CellSnapshot>(observed.size());
        var observedGeometry=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SnowHostGeometry.Cell>(lightingSize+observed.size());
        var lighting=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<CellSnapshot>(lightingSize);
        // Freeze the unique payload faces once; neighbour lookups may additionally
        // observe native support at a different Y than the supplied face.
        if(source!=null){
            for(var col:halo)observedGeometry.put(BlockPos.asLong(col.x(),col.y(),col.z()),col.geometry());
            for(var col:observed)observedGeometry.put(BlockPos.asLong(col.x(),col.y(),col.z()),col.geometry());
        }
        for(var col:observed){
            BlockPos pos=new BlockPos(col.x(),col.y(),col.z());long position=pos.asLong();
            var cell=source==null?describeSnapshot(pos,observedGeometry):observedGeometry.get(position);
            if(cell==null||cell.layers()!=col.layers()){
                if(source!=null)throw new FrameNotReady("Owner "+pos+" payloadLayers="+col.layers()+" current="+cell+" native="+world.getBlockState(pos));
                else continue;
            }
            var state=world.getBlockState(pos);
            // A legitimate unsupported resource model retains its native cap and never acquires dynamic ownership.
            if(SnowHostGeometry.nativeLayers(state)>0&&!com.dark.winter.client.TestSnowTerrainModels.supportsDynamic(state))continue;
            double[] corners=SnowHostGeometry.fineCornersMillimeters(world,cell,p->field==null?pendingDelta(p):field.delta(p));
            var affecting=new ArrayList<SnowSurfacePayload.Mark>();
            for(var mark:localMarks)if(SnowHostGeometry.contactMatches(cell,corners,mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),mark.ownerY()))affecting.add(mark);
            var cellContacts=List.copyOf(affecting);
            BlockPos lightPos=pos.above();
            int blockLight=world.getBrightness(LightLayer.BLOCK,lightPos)*16,skyLight=world.getBrightness(LightLayer.SKY,lightPos)*16;
            lighting.put(position,new CellSnapshot(col,cell,corners,cellContacts,blockLight,skyLight,0));
            // Dry ground is still a real lower neighbour for bank lighting, but emits no snow cap.
            if(cell.layers()==0&&Arrays.stream(corners).allMatch(height->height<=0))continue;
            int skirtSides=0;
            for(int side=0;side<4;side++){
                BlockPos neighbor=cell.pos().offset(side==1?1:side==3?-1:0,0,side==2?1:side==0?-1:0);
                var adjacent=describeSnapshot(neighbor,observedGeometry);
                if(adjacent==null||Math.abs(adjacent.nominalHeight()-cell.nominalHeight())>=.000001||!Double.isFinite(field==null?pendingDelta(neighbor):field.delta(neighbor)))skirtSides|=1<<side;
            }
            cells.add(new CellSnapshot(col,cell,corners.clone(),cellContacts,blockLight,skyLight,skirtSides));
        }
        // The existing complete one-cell transport halo supplies real geometry on chunk boundaries.
        for(var col:halo){
            long position=BlockPos.asLong(col.x(),col.y(),col.z());
            var cell=observedGeometry.get(position);
            if(cell==null||cell.layers()!=col.layers())continue;
            double[] corners=SnowHostGeometry.fineCornersMillimeters(world,cell,field::delta);
            var affecting=new ArrayList<SnowSurfacePayload.Mark>();
            for(var mark:localMarks)if(SnowHostGeometry.contactMatches(cell,corners,mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),mark.ownerY()))affecting.add(mark);
            BlockPos lightPos=cell.pos().above();
            lighting.put(position,new CellSnapshot(col,cell,corners,List.copyOf(affecting),
                    world.getBrightness(LightLayer.BLOCK,lightPos)*16,world.getBrightness(LightLayer.SKY,lightPos)*16,0));
        }
        return new BuildSnapshot(epoch,key,geometryRevisions.getOrDefault(key,0L),source==null?0:source.revision(),++revision,origin,List.copyOf(cells),authoritativeMarks,List.copyOf(localMarks),Map.copyOf(lighting),fieldContext.containsKey(key)?Map.of(key,fieldContext.get(key)):Map.of());
    }
    /** Per-snapshot cache cannot outlive this atomic client-thread world observation. */
    private static SnowHostGeometry.Cell describeSnapshot(BlockPos pos,
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SnowHostGeometry.Cell> observed){
        long key=pos.asLong();var known=observed.get(key);
        if(known!=null||observed.containsKey(key))return known;
        var cell=SnowHostGeometry.describe(world,pos);observed.put(key,cell);return cell;
    }
    private static void checkCancelled(BuildJob job){
        if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();
    }
    /** Pure CPU job: no Level, Minecraft, resource, mutable column or published-mesh access. */
    private static Mesh build(BuildSnapshot snapshot,BuildJob job){
        var sampling=new WorkerSampling(snapshot.contacts);sampling.lighting=new LightingField(snapshot.lighting);WORKER_SAMPLING.set(sampling);
        try{return buildWithSampling(snapshot,job,sampling,false);}
        finally{WORKER_SAMPLING.remove();}
    }
    private static final class CapFace {
        final CellSnapshot input;final SnowHostGeometry.Cell geometry;final List<SnowSurfacePayload.Mark> contacts;
        final FeatureGrid features;final double[][] featureHeights;int memoId;
        final SnowTerrainGeometry.CompiledHeight compiledHeight;
        final double constantBottom;
        CapFace(CellSnapshot input,SnowHostGeometry.Cell geometry,List<SnowSurfacePayload.Mark> contacts,boolean features,
                SnowTerrainGeometry.CompiledHeight compiledHeight){
            this.input=input;this.geometry=geometry;this.contacts=contacts;this.compiledHeight=compiledHeight;
            this.features=features?PressureFeatures.from(contacts).cell(geometry):FeatureGrid.NONE;
            featureHeights=this.features.xs.length==0?null:new double[FeatureGrid.STRIPS][];
            if(!features||contacts.isEmpty()||geometry.mask()!=0||geometry.crust()!=0)constantBottom=Double.NaN;
            else{
                var patch=geometry.generic()?geometry.patches().getFirst():null;
                double x=geometry.pos().getX()+(patch==null?.5:(patch.minX()+patch.maxX())*.5);
                double z=geometry.pos().getZ()+(patch==null?.5:(patch.minZ()+patch.maxZ())*.5);
                constantBottom=compiledHeight==null?SnowHostGeometry.snowBottomHeight(geometry,x,z):compiledHeight.bottom(x,z);
            }
        }
    }
    /** All roots, height observations and shared shading come from one immutable snapshot.
     * No previous mesh, packed vertex or subdivision hierarchy is an input. */
    private static final class CanonicalTile {
        final CellSnapshot input;final BuildSnapshot snapshot;final WorkerSampling sampling;final BuildJob job;
        final List<CapFace> faces=new ArrayList<>();final CapFace[] owners=new CapFace[3*3*7];
        DraftErrorState errorMemo;
        SnowTriangleBuffer numericOutput;
        final List<com.winterkore.engine.surface.geometry.ConformingSnowMesh.Root> roots=new ArrayList<>();
        final List<com.winterkore.engine.surface.geometry.ConformingSnowMesh.Root> context=new ArrayList<>();
        final com.winterkore.engine.surface.geometry.SnowSurfaceShading<CapFace> shading;
        CanonicalTile(CellSnapshot input,BuildSnapshot snapshot,WorkerSampling sampling,BuildJob job){
            this.input=input;this.snapshot=snapshot;this.sampling=sampling;this.job=job;
            add(input,true);
            var pos=input.geometry.pos();var indexed=sampling.lighting;
            // The same finite halo as before, in a stable world-axis order. Never
            // scan the entire chunk's lighting field for every individual tile.
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(int dy=-3;dy<=3;dy++){
                if((dx|dy|dz)==0)continue;
                int x=pos.getX()+dx,y=pos.getY()+dy,z=pos.getZ()+dz;
                var neighbor=indexed!=null&&indexed.source==snapshot.lighting?indexed.at(x,y,z):snapshot.lighting.get(BlockPos.asLong(x,y,z));
                if(neighbor!=null)add(neighbor,false);
            }
            shading=new com.winterkore.engine.surface.geometry.SnowSurfaceShading<>(new com.winterkore.engine.surface.geometry.SnowSurfaceShading.Source<CapFace>(){
                public CapFace at(int x,int y,int z){return ownerAt(x,y,z);}
                public int x(CapFace f){return f.geometry.pos().getX();}
                public int y(CapFace f){return f.geometry.pos().getY();}
                public int z(CapFace f){return f.geometry.pos().getZ();}
                public boolean contains(CapFace f,double x,double z){return f.geometry.contains(x,z);}
                public boolean smooth(CapFace f){return !f.geometry.generic();}
                public double skinHeight(CapFace f,double x,double z){return skin(f,x,z);}
                public double bottomHeight(CapFace f,double x,double z){return bottom(f,x,z);}
                public double height(CapFace f,double x,double z){return physical(f,x,z);}
                public double blockLight(CapFace f){return f.input.blockLight;}
                public double skyLight(CapFace f){return f.input.skyLight;}
            });
        }
        int ownerIndex(int x,int y,int z){
            var centre=input.geometry.pos();
            int dx=x-centre.getX()+1,dy=y-centre.getY()+3,dz=z-centre.getZ()+1;
            if(dx<0||dx>=3||dy<0||dy>=7||dz<0||dz>=3)return -1;
            return (dy*3+dz)*3+dx;
        }
        CapFace ownerAt(int x,int y,int z){
            int index=ownerIndex(x,y,z);
            return index<0?null:owners[index];
        }
        double skin(CapFace face,double x,double z){
            return face.compiledHeight==null?SnowHostGeometry.height(face.geometry,x,z,face.input.corners):face.compiledHeight.skin(x,z);
        }
        double bottom(CapFace face,double x,double z){
            return face.compiledHeight==null?SnowHostGeometry.snowBottomHeight(face.geometry,x,z):face.compiledHeight.bottom(x,z);
        }
        double physical(CapFace face,double x,double z){
            var memo=errorMemo==null?null:errorMemo.heights;
            // Retain only the exact contact field; weather and support clamping stay current on hits.
            double pressure=memo==null?Double.NaN:memo.get(face.memoId,x,z);
            if(Double.isNaN(pressure)){
                pressure=signedDepression(face.contacts,x,z,sampling);
                if(memo!=null)memo.put(face.memoId,x,z,pressure);
            }
            return face.compiledHeight!=null?face.compiledHeight.compacted(x,z,pressure):
                    SnowHostGeometry.clampCompactedHeight(face.geometry,x,z,skin(face,x,z),pressure);
        }
        static com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point world(P p,BlockPos pos){
            return new com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point(p.x+pos.getX(),p.y,p.z+pos.getZ());
        }
        static boolean perimeter(com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,double x0,double z0,double x1,double z1){
            return (a.x()==b.x()&&(a.x()==x0||a.x()==x1))||(a.z()==b.z()&&(a.z()==z0||a.z()==z1));
        }
        void root(int face,P a,P b,P c,double x0,double z0,double x1,double z1,boolean active){
            var pos=faces.get(face).geometry.pos();var aa=world(a,pos);var bb=world(b,pos);var cc=world(c,pos);
            double area=(bb.x()-aa.x())*(cc.z()-aa.z())-(bb.z()-aa.z())*(cc.x()-aa.x());
            if(Math.abs(area)<1e-15)return;
            int mask=(perimeter(aa,bb,x0,z0,x1,z1)?1:0)|(perimeter(bb,cc,x0,z0,x1,z1)?2:0)|(perimeter(cc,aa,x0,z0,x1,z1)?4:0);
            var root=new com.winterkore.engine.surface.geometry.ConformingSnowMesh.Root(face,aa,bb,cc,mask);
            (active?roots:context).add(root);
        }
        void add(CellSnapshot source,boolean active){
            var complete=source.geometry;var pos=complete.pos();
            int owner=ownerIndex(pos.getX(),pos.getY(),pos.getZ());
            if(!complete.generic()){
                int face=faces.size();var value=new CapFace(source,complete,source.contacts,active,sampling.compiledHeight(source));
                value.memoId=faces.size();faces.add(value);owners[owner]=value;
                for(var piece:SnowTerrainGeometry.shape(Math.max(1,complete.layers()),complete.mask()).snowOwner())if(piece.face()==0){
                    var points=piece.vertices();var a=points.getFirst();
                    for(int i=1;i<points.size()-1;i++)root(face,point(a,complete,source.corners),point(points.get(i),complete,source.corners),
                            point(points.get(i+1),complete,source.corners),pos.getX(),pos.getZ(),pos.getX()+1,pos.getZ()+1,active);
                }
            }else for(var patch:complete.patches()){
                var geometry=new SnowHostGeometry.Cell(pos,0,0,0,complete.baseOffset(),List.of(patch));
                var contacts=new ArrayList<SnowSurfacePayload.Mark>();
                for(var mark:source.contacts)if(SnowHostGeometry.contactMatches(geometry,source.corners,mark.x(),mark.y(),mark.z(),mark.width(),mark.length(),mark.ownerY()))contacts.add(mark);
                int face=faces.size();var value=new CapFace(source,geometry,List.copyOf(contacts),active,null);value.memoId=faces.size();faces.add(value);if(owners[owner]==null)owners[owner]=value;
                sampling.registerEnvelope(pos.asLong(),complete.patches().indexOf(patch)+1,value.contacts);
                P a=patchPoint(geometry,patch.minX(),patch.minZ(),source.corners),b=patchPoint(geometry,patch.maxX(),patch.minZ(),source.corners);
                P c=patchPoint(geometry,patch.maxX(),patch.maxZ(),source.corners),d=patchPoint(geometry,patch.minX(),patch.maxZ(),source.corners);
                double x0=pos.getX()+patch.minX(),x1=pos.getX()+patch.maxX(),z0=pos.getZ()+patch.minZ(),z1=pos.getZ()+patch.maxZ();
                root(face,a,b,c,x0,z0,x1,z1,active);root(face,a,c,d,x0,z0,x1,z1,active);
            }
        }
        double probe(CapFace face,double x,double z){return shading.height(face,x,z);}
        double featureError(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,double ya,double yb,double yc,double stopAbove){
            var grid=face.features;
            if(grid.xs.length==0)return 0;
            // Keep the existing floating-point operation order in the point test.
            double bzc=b.z()-c.z(),cxb=c.x()-b.x(),azc=a.z()-c.z(),axc=a.x()-c.x();
            double cza=c.z()-a.z();
            double denominator=bzc*axc+cxb*azc;
            if(Math.abs(denominator)<1e-15)return 0;

            var position=face.geometry.pos();
            double minX=Math.min(a.x(),Math.min(b.x(),c.x()))-position.getX();
            double maxX=Math.max(a.x(),Math.max(b.x(),c.x()))-position.getX();
            double minZ=Math.min(a.z(),Math.min(b.z(),c.z()))-position.getZ();
            double maxZ=Math.max(a.z(),Math.max(b.z(),c.z()))-position.getZ();
            int coarseX0=FeatureGrid.coarseBin(minX),coarseX1=FeatureGrid.coarseBin(maxX);
            int firstStrip=FeatureGrid.coarseBin(minZ)*FeatureGrid.STRIPS_PER_BIN;
            int lastStrip=(FeatureGrid.coarseBin(maxZ)+1)*FeatureGrid.STRIPS_PER_BIN-1;
            double firstX=coarseX0==0?Double.NEGATIVE_INFINITY:coarseX0/(double)FeatureGrid.COARSE_BINS;
            double pastX=coarseX1==FeatureGrid.COARSE_BINS-1?Double.POSITIVE_INFINITY:(coarseX1+1)/(double)FeatureGrid.COARSE_BINS;
            double firstZ=Double.NEGATIVE_INFINITY,pastZ=Double.POSITIVE_INFINITY;

            // On a one metre tile, a one micrometre pad covers the accepted negative
            // barycentric weight and floating-point roundoff for these determinants.
            // Thin triangles retain the original coarse window before the point test.
            if(Math.abs(denominator)>=1e-6){
                double pad=1e-6;
                firstX=Math.max(firstX,Math.nextDown(minX-pad));
                pastX=Math.min(pastX,Math.nextUp(maxX+pad));
                firstZ=Math.nextDown(minZ-pad);
                pastZ=Math.nextUp(maxZ+pad);
                firstStrip=Math.max(firstStrip,FeatureGrid.strip(firstZ));
                lastStrip=Math.min(lastStrip,FeatureGrid.strip(pastZ));
            }

            double error=0;
            long candidates=0,inside=0,heightQueries=0;
            for(int strip=firstStrip;strip<=lastStrip;strip++){
                int start=grid.offsets[strip],end=grid.offsets[strip+1];
                if(start==end)continue;
                int from=grid.lowerBoundX(start,end,firstX),to=grid.lowerBoundX(from,end,pastX);
                var heights=face.featureHeights[strip];
                for(int index=from;index<to;index++){
                    candidates++;
                    double localZ=grid.zs[index];
                    if(localZ<firstZ||localZ>=pastZ)continue;
                    double x=grid.xs[index]+position.getX(),z=localZ+position.getZ();
                    double dx=x-c.x(),dz=z-c.z();
                    double u=(bzc*dx+cxb*dz)/denominator;
                    double v=(cza*dx+axc*dz)/denominator,w=1-u-v;
                    if(u< -1e-9||v< -1e-9||w< -1e-9)continue;
                    inside++;
                    if(heights==null){
                        heights=new double[end-start];
                        Arrays.fill(heights,Double.NaN);
                        face.featureHeights[strip]=heights;
                    }
                    int localIndex=index-start;
                    if(Double.isNaN(heights[localIndex])){
                        heights[localIndex]=probe(face,x,z);
                        heightQueries++;
                    }
                    error=Math.max(error,Math.abs(heights[localIndex]-(u*ya+v*yb+w*yc)));
                    if(error>stopAbove)return finishFeatureError(error,candidates,inside,heightQueries);
                }
            }
            return finishFeatureError(error,candidates,inside,heightQueries);
        }
        double finishFeatureError(double error,long candidates,long inside,long heightQueries){
            sampling.featureCandidates+=candidates;
            sampling.featurePointsInside+=inside;
            sampling.featureHeightQueries+=heightQueries;
            return error;
        }
        double error(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,
                double ya,double yb,double yc,double stopAbove,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.MidpointSamples midpoints){
            double error=Math.abs(midpoints.height(0)-(ya+yb)*.5);
            if(error>stopAbove)return error;
            error=Math.max(error,Math.abs(midpoints.height(1)-(yb+yc)*.5));
            if(error>stopAbove)return error;
            error=Math.max(error,Math.abs(midpoints.height(2)-(yc+ya)*.5));
            if(error>stopAbove)return error;
            error=Math.max(error,Math.abs(probe(face,(a.x()+b.x()+c.x())/3,(a.z()+b.z()+c.z())/3)-(ya+yb+yc)/3));
            if(error>stopAbove)return error;
            error=Math.max(error,featureError(face,a,b,c,ya,yb,yc,stopAbove));
            if(error>stopAbove||face.contacts.isEmpty())return error;

            double reliefA=a.y()-ya,reliefB=b.y()-yb,reliefC=c.y()-yc;
            double minimumRelief=Math.min(reliefA,Math.min(reliefB,reliefC));
            double reliefVariation=Math.max(reliefA,Math.max(reliefB,reliefC))-minimumRelief;
            double materialVariation=sampling.localApproximation?LOCAL_RELIEF_ERROR:.0015;
            // A mixed pressure field can bend between three nearly equal vertices.
            // Probe it when cheap samples already detect nontrivial curvature.
            if(reliefVariation>materialVariation||error>materialVariation*.45){
                // Symmetric triangle cubature probes reach inside a curved cut where
                // the edge midpoints and centroid can agree with the cap plane.
                error=interiorProbeErrors(face,a,b,c,ya,yb,yc,.2,error,stopAbove);
                if(error>stopAbove)return error;
                // Both a packed sole edge and a raised shoulder can change abruptly
                // close to a vertex even while the broader interior probes agree.
                error=interiorProbeErrors(face,a,b,c,ya,yb,yc,.125,error,stopAbove);
                if(error>stopAbove)return error;
            }

            // At a support clamp the high part of a triangle can be confined to a
            // small corner, with every midpoint already on the bottom.
            double da=ya-supportHeight(face,a),db=yb-supportHeight(face,b),dc=yc-supportHeight(face,c);
            double bottomTolerance=1e-7;
            if(Math.min(da,Math.min(db,dc))>bottomTolerance||Math.max(da,Math.max(db,dc))<=bottomTolerance)return error;
            return interiorProbeErrors(face,a,b,c,ya,yb,yc,1.0/16,error,stopAbove);
        }
        double supportHeight(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point point){
            if(Double.isFinite(face.constantBottom))return face.constantBottom;
            return bottom(face,point.x(),point.z());
        }
        double interiorProbeErrors(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,
                double ya,double yb,double yc,double otherWeight,double error,double stopAbove){
            error=Math.max(error,weightedProbeError(face,a,b,c,ya,yb,yc,otherWeight));
            if(error>stopAbove)return error;
            error=Math.max(error,weightedProbeError(face,b,c,a,yb,yc,ya,otherWeight));
            if(error>stopAbove)return error;
            return Math.max(error,weightedProbeError(face,c,a,b,yc,ya,yb,otherWeight));
        }
        double weightedProbeError(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point tip,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point left,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point right,
                double tipHeight,double leftHeight,double rightHeight,double otherWeight){
            double x=tip.x()+((left.x()-tip.x())+(right.x()-tip.x()))*otherWeight;
            double z=tip.z()+((left.z()-tip.z())+(right.z()-tip.z()))*otherWeight;
            double interpolated=tipHeight+((leftHeight-tipHeight)+(rightHeight-tipHeight))*otherWeight;
            return Math.abs(probe(face,x,z)-interpolated);
        }
        double tolerance(int id,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,double ya,double yb,double yc,int level){
            double target=sampling.localApproximation?LOCAL_RELIEF_ERROR:.0015;
            // Reserve margin between measured probes wherever pressure affects the cap.
            if(sampling.localApproximation&&!faces.get(id).contacts.isEmpty())target*=.9;
            return target;
        }
        double measureError(int id,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,
                double ya,double yb,double yc,int level,double stopAbove,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.MidpointSamples midpoints){
            if(errorMemo==null)return error(faces.get(id),a,b,c,ya,yb,yc,stopAbove,midpoints);
            double cached=errorMemo.memo.get(id,level,a,b,c,ya,yb,yc,stopAbove);
            if(!Double.isNaN(cached))return cached;
            double measured=error(faces.get(id),a,b,c,ya,yb,yc,stopAbove,midpoints);
            errorMemo.memo.put(id,level,a,b,c,ya,yb,yc,stopAbove,measured);
            return measured;
        }
        boolean refine(int id,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point a,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point b,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Point c,double ya,double yb,double yc,int level,
                com.winterkore.engine.surface.geometry.ConformingSnowMesh.MidpointSamples midpoints){
            double target=tolerance(id,a,b,c,ya,yb,yc,level);
            return measureError(id,a,b,c,ya,yb,yc,level,target,midpoints)>target;
        }
        DebugVertex vertex(int id,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Vertex vertex,double[] shade){
            if(numericOutput!=null){
                int index=numericOutput.index(vertex);
                if(index>=0){
                    var v=numericOutput.vertices;int at=index*SnowTriangleBuffer.STRIDE;
                    return new DebugVertex(v[at],v[at+1],v[at+2],v[at+3],v[at+4],v[at+5],v[at+6],v[at+7],
                            v[at+8],v[at+9],v[at+10],(int)v[at+11]);
                }
            }
            var face=faces.get(id);var pos=face.geometry.pos();shading.sample(face,vertex.x(),vertex.z(),shade);
            double depth=Math.max(0,skin(face,vertex.x(),vertex.z())-vertex.y())*1000;
            return new DebugVertex(vertex.x()-pos.getX(),vertex.y(),vertex.z()-pos.getZ(),vertex.x()-pos.getX(),vertex.z()-pos.getZ(),
                    shade[1],shade[2],shade[3],depth,shade[4],shade[5]);
        }
        int numericVertex(int id,com.winterkore.engine.surface.geometry.ConformingSnowMesh.Vertex value,double[] shade){
            int known=numericOutput.index(value);if(known>=0)return known;
            var face=faces.get(id);var pos=face.geometry.pos();shading.sample(face,value.x(),value.z(),shade);
            double depth=Math.max(0,skin(face,value.x(),value.z())-value.y())*1000;
            int index=numericOutput.vertex(value.x()-pos.getX(),value.y(),value.z()-pos.getZ(),
                    value.x()-pos.getX(),value.z()-pos.getZ(),shade[1],shade[2],shade[3],depth,shade[4],shade[5],-1);
            numericOutput.remember(value,index);return index;
        }
        List<DebugTriangle> build(){
            // Direct debug fixtures and asynchronous fine builds keep their original pure path.
            var lease=sampling.localApproximation&&(world!=null||sampling.persistentAo)
                    ?DRAFT_ERROR_MEMOS.acquire(input.geometry.pos().asLong()):null;
            try{
                if(lease!=null&&lease.bind(faces))errorMemo=lease;
                return buildCanonical();
            }finally{
                errorMemo=null;
                if(lease!=null)DRAFT_ERROR_MEMOS.release(lease);
            }
        }
        List<DebugTriangle> buildCanonical(){
            var sampler=new com.winterkore.engine.surface.geometry.ConformingSnowMesh.Sampler(){
                public double height(int face,double x,double rawY,double z){return physical(faces.get(face),x,z);}
                public double boundaryHeight(int face,double x,double rawY,double z){return shading.height(faces.get(face),x,z);}
                public double midpointHeight(int face,double x,double rawY,double z){return shading.height(faces.get(face),x,z);}
            };
            var builder=sampling.localApproximation
                    ?new com.winterkore.engine.surface.geometry.ConformingSnowMesh.Builder(sampler,this::measureError,this::tolerance)
                        .onTerminalError(error->localError(sampling,error))
                    :new com.winterkore.engine.surface.geometry.ConformingSnowMesh.Builder(sampler,this::refine);
            builder.check(()->checkCancelled(job)).insetSeedGrid().cacheBoundarySamples()
                    .maxWork(sampling.localApproximation?DRAFT_TOPOLOGY_WORK:FINE_TOPOLOGY_WORK).approximateOnWorkLimit();
            for(var root:roots)builder.addRoot(root);for(var root:context)builder.addBoundaryContext(root);
            var shade=new double[6];
            if(numericOutput!=null)builder.emitTriangles((face,a,b,c,level)->
                    numericOutput.triangle(numericVertex(face,a,shade),numericVertex(face,b,shade),numericVertex(face,c,shade)));
            com.winterkore.engine.surface.geometry.ConformingSnowMesh.Result result;
            try{result=builder.build();}
            catch(com.winterkore.engine.surface.geometry.ConformingSnowMesh.WorkLimitExceeded failure){
                topologyBudgetFallbacks.incrementAndGet();
                throw failure;
            }
            var peak=sampling.localApproximation?maxDraftTopologyWork:maxFineTopologyWork;
            peak.accumulateAndGet(result.diagnostics().get("work"),Math::max);
            sampling.cellTopologyBudgetLimited=result.diagnostics().get("budgetLimited")!=0;
            if(sampling.cellTopologyBudgetLimited)topologyBudgetFallbacks.incrementAndGet();
            // Rich records remain available to explicit diagnostic fixtures. Gameplay keeps indexed numbers.
            List<DebugTriangle> out=numericOutput==null?new ArrayList<>():new AbstractList<>(){
                @Override public DebugTriangle get(int index){throw new UnsupportedOperationException("Write-only snow sink");}
                @Override public int size(){return numericOutput.indexCount/3;}
                @Override public boolean add(DebugTriangle triangle){numericOutput.triangle(triangle);return true;}
            };
            var vertices=new IdentityHashMap<com.winterkore.engine.surface.geometry.ConformingSnowMesh.Vertex,DebugVertex>();
            for(var triangle:result.triangles()){
                var a=vertices.computeIfAbsent(triangle.a(),v->vertex(triangle.faceId(),v,shade));
                var b=vertices.computeIfAbsent(triangle.b(),v->vertex(triangle.faceId(),v,shade));
                var c=vertices.computeIfAbsent(triangle.c(),v->vertex(triangle.faceId(),v,shade));
                out.add(new DebugTriangle(a,b,c));
            }
            for(var segment:result.boundarySegments())wall(segment,out,vertices,shade);
            if(!input.geometry.generic()&&input.geometry.layers()==0){
                double floor=input.geometry.pos().getY()+1e-9;
                if(numericOutput!=null)numericOutput.discardBelow(floor);
                else out.removeIf(t->t.a.y<=floor&&t.b.y<=floor&&t.c.y<=floor);
            }
            return numericOutput==null?out:List.of();
        }
        /** Published adjacent tiles can differ both across chunks and when a newer
         * foot tile survives a fine rebuild in the same chunk. Close compatible caps
         * to their true common bottom. Equal caps bury both internal walls. */
        boolean closesSharedSupport(CapFace face,com.winterkore.engine.surface.geometry.ConformingSnowMesh.BoundarySegment segment,int side){
            var pos=face.geometry.pos();
            var other=ownerAt(pos.getX()+(side==1?1:side==3?-1:0),pos.getY(),pos.getZ()+(side==2?1:side==0?-1:0));
            if(other==null||other.geometry.generic()||Math.abs(face.geometry.nominalHeight()-other.geometry.nominalHeight())>=1e-6)return false;
            var a=segment.a();var b=segment.b();
            return commonBottom(face,other,a.x(),a.z())&&commonBottom(face,other,b.x(),b.z());
        }
        boolean commonBottom(CapFace a,CapFace b,double x,double z){
            double first=bottom(a,x,z),second=bottom(b,x,z);
            return Double.isFinite(first)&&Double.isFinite(second)&&Math.abs(first-second)<=1e-6;
        }
        void wall(com.winterkore.engine.surface.geometry.ConformingSnowMesh.BoundarySegment segment,List<DebugTriangle> out,
                IdentityHashMap<com.winterkore.engine.surface.geometry.ConformingSnowMesh.Vertex,DebugVertex> vertices,double[] shade){
            var face=faces.get(segment.faceId());var cell=face.geometry;var pos=cell.pos();var a=segment.a();var b=segment.b();
            double minX=pos.getX(),minZ=pos.getZ(),maxX=minX+1,maxZ=minZ+1;
            if(cell.generic()){var patch=cell.patches().getFirst();minX+=patch.minX();maxX=pos.getX()+patch.maxX();minZ+=patch.minZ();maxZ=pos.getZ()+patch.maxZ();}
            int side=a.z()==b.z()?(Math.abs(a.z()-minZ)<1e-9?0:2):(Math.abs(a.x()-maxX)<1e-9?1:3);
            boolean shared=!cell.generic()&&(input.skirtSides&(1<<side))==0;
            if(!cell.generic()){
                if(shared&&!closesSharedSupport(face,segment,side))return;
            }else{
                var patch=cell.patches().getFirst();
                boolean outer=side==0?patch.minZ()==0:side==1?patch.maxX()==1:side==2?patch.maxZ()==1:patch.minX()==0;
                if(outer&&(input.skirtSides&(1<<side))==0)return;
                double nx=side==1?1:side==3?-1:0,nz=side==2?1:side==0?-1:0;
                double x=(a.x()+b.x())*.5-pos.getX()+nx*1e-5,z=(a.z()+b.z())*.5-pos.getZ()+nz*1e-5;
                for(var other:input.geometry.patches())if(other!=patch&&x>other.minX()&&x<other.maxX()&&z>other.minZ()&&z<other.maxZ()
                        &&Math.abs(other.top()-patch.top())<1e-5)return;
            }
            if(shared){
                double tangent=(side&1)==0?b.x()-a.x():b.z()-a.z();
                if(side<2?tangent<0:tangent>0){var swap=a;a=b;b=swap;}
            }
            double ay=bottom(face,a.x(),a.z()),by=bottom(face,b.x(),b.z());
            if(a.y()<=ay+1e-6&&b.y()<=by+1e-6)return;
            P bottomA=new P(a.x()-pos.getX(),ay,a.z()-pos.getZ()),bottomB=new P(b.x()-pos.getX(),by,b.z()-pos.getZ());
            P topA=new P(bottomA.x,a.y(),bottomA.z,Math.max(0,skin(face,a.x(),a.z())-a.y())*1000);
            P topB=new P(bottomB.x,b.y(),bottomB.z,Math.max(0,skin(face,b.x(),b.z())-b.y())*1000);
            DebugVertex lowA,lowB,highA,highB;
            if(shared){
                var capA=vertices.computeIfAbsent(a,v->vertex(segment.faceId(),v,shade));
                var capB=vertices.computeIfAbsent(b,v->vertex(segment.faceId(),v,shade));
                lowA=sharedSideVertex(bottomA,capA,side);highA=sharedSideVertex(topA,capA,side);
                lowB=sharedSideVertex(bottomB,capB,side);highB=sharedSideVertex(topB,capB,side);
            }else{
                double nx=side==1?1:side==3?-1:0,nz=side==2?1:side==0?-1:0;
                lowA=sideVertex(bottomA,nx,nz,0);highA=sideVertex(topA,nx,nz,1);
                lowB=sideVertex(bottomB,nx,nz,0);highB=sideVertex(topB,nx,nz,1);
            }
            out.add(new DebugTriangle(lowA,highA,highB));
            out.add(new DebugTriangle(lowA,highB,lowB));
        }
    }
    private static List<DebugTriangle> canonicalTile(CellSnapshot input,BuildSnapshot snapshot,WorkerSampling sampling,BuildJob job){
        sampling.bindSnapshot(snapshot);
        return new CanonicalTile(input,snapshot,sampling,job).build();
    }
    private static void buildTileRange(BuildSnapshot snapshot,BuildJob job,WorkerSampling sampling,
            BuiltCell[] result,int first,int end){
        for(int i=first;i<end;i++){
            checkCancelled(job);
            var input=snapshot.cells.get(i);sampling.beginCell(input.geometry);sampling.cellWorstError=0;
            var triangles=SnowTriangleBuffer.acquire();boolean accepted=false;
            try{
                sampling.bindSnapshot(snapshot);
                var tile=new CanonicalTile(input,snapshot,sampling,job);tile.numericOutput=triangles;tile.build();
                triangles.clearIdentities();
                double errorMm=sampling.cellTopologyBudgetLimited?-1:sampling.cellWorstError*1000;
                result[i]=new BuiltCell(input,triangles,errorMm,!sampling.localApproximation&&sampling.cellTopologyBudgetLimited);
                accepted=true;
            }catch(com.winterkore.engine.surface.geometry.ConformingSnowMesh.WorkLimitExceeded failure){
                if(!job.initial)throw failure;
                // Keep the native owner until a complete replacement is available.
            }finally{if(!accepted)SnowTriangleBuffer.release(triangles);}
        }
    }
    /** One helper handles independent owners from the same immutable contact snapshot. */
    private static WorkerSampling buildLocalTiles(BuildSnapshot snapshot,BuildJob job,WorkerSampling sampling,BuiltCell[] result){
        if(contactTessellator==null)contactTessellator=Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("WKE snow contact tessellator").factory());
        int middle=result.length/2;
        var prepared=new IdentityHashMap<>(sampling.contacts);
        var other=CompletableFuture.supplyAsync(()->{
            var helper=new WorkerSampling(snapshot.contacts,prepared);helper.localApproximation=true;
            helper.lighting=sampling.lighting;helper.persistentAo=sampling.persistentAo;
            WORKER_SAMPLING.set(helper);
            try{
                buildTileRange(snapshot,job,helper,result,middle,result.length);
                return helper;
            }finally{WORKER_SAMPLING.remove();}
        },contactTessellator);
        return finishContactTask(other,()->buildTileRange(snapshot,job,sampling,result,0,middle),job);
    }
    private static <T> T finishContactTask(CompletableFuture<T> other,Runnable local,BuildJob job){
        try{
            local.run();
            return other.join();
        }catch(CompletionException failure){
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            if(failure.getCause() instanceof Error cause)throw cause;
            throw failure;
        }finally{
            if(!other.isDone()){
                job.cancelled=true;
                // No auxiliary worker may keep writing after a failed contact build.
                other.handle((ignored,failure)->null).join();
            }
        }
    }
    private static Mesh buildWithSampling(BuildSnapshot snapshot,BuildJob job,WorkerSampling sampling,boolean parallel){
        var tiles=new BuiltCell[snapshot.cells.size()];
        WorkerSampling helper=null;
        try{
            long start=sampling.localApproximation?System.nanoTime():0;
            try{
                if(parallel&&tiles.length>1)helper=buildLocalTiles(snapshot,job,sampling,tiles);
                else buildTileRange(snapshot,job,sampling,tiles,0,tiles.length);
            }finally{
                if(sampling.localApproximation)localTessellationWallNanos+=System.nanoTime()-start;
            }
            start=sampling.localApproximation?System.nanoTime():0;
            try{return packTiles(snapshot,job,sampling,helper,tiles);}
            finally{
                if(sampling.localApproximation)localPackingWallNanos+=System.nanoTime()-start;
                if(helper!=null)recordLocalBuildDiagnostics(helper);
            }
        }finally{
            // finishContactTask joins its helper even on failure; no worker or published mesh owns scratch now.
            for(var tile:tiles)if(tile!=null)SnowTriangleBuffer.release(tile.triangles);
        }
    }
    private static Mesh packTiles(BuildSnapshot snapshot,BuildJob job,WorkerSampling sampling,WorkerSampling helper,BuiltCell[] tiles){
        var built=new ArrayList<BuiltCell>(tiles.length);
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(var tile:tiles){
            if(tile==null)continue;
            built.add(tile);
            var geometry=tile.triangles;
            for(int i=0;i<geometry.indexCount;i++){
                double y=geometry.vertices[geometry.indices[i]*SnowTriangleBuffer.STRIDE+1];
                low=Math.min(low,y);high=Math.max(high,y);
            }
        }
        checkCancelled(job);
        built.sort(Comparator.comparingInt((BuiltCell cell)->cell.input.geometry.pos().getY()>>4));
        var firstVertices=new int[built.size()+1];
        for(int i=0;i<built.size();i++)
            firstVertices[i+1]=Math.addExact(firstVertices[i],built.get(i).triangles.indexCount);
        int vertexCount=firstVertices[built.size()];
        ByteBuffer data=ByteBuffer.allocate(Math.multiplyExact(vertexCount,BYTES)).order(ByteOrder.nativeOrder());
        var cells=new CellData[built.size()];
        if(helper!=null&&built.size()>1){
            int middle=built.size()/2;
            // The same dedicated thread owns helper and its relief cache in both phases.
            var other=CompletableFuture.runAsync(()->{
                WORKER_SAMPLING.set(helper);
                try{packTileRange(snapshot,job,helper,built,firstVertices,data,cells,middle,built.size());}
                finally{WORKER_SAMPLING.remove();}
            },contactTessellator);
            finishContactTask(other,()->packTileRange(snapshot,job,sampling,built,firstVertices,data,cells,0,middle),job);
            localParallelPackJobs++;
        }else packTileRange(snapshot,job,sampling,built,firstVertices,data,cells,0,built.size());

        var accepted=new LinkedHashMap<Long,CellData>();
        for(var cell:cells)accepted.put(cell.geometry.pos().asLong(),cell);
        if(!Double.isFinite(low)){low=0;high=0;}
        BlockPos origin=snapshot.origin;
        // Only compact geometry/contact metadata and packed bytes survive this job; rich triangle records are temporary.
        return new Mesh(snapshot.key,snapshot.renderRevision,origin,Map.copyOf(accepted),snapshot.contacts,data.asReadOnlyBuffer(),vertexCount,List.of(),
                new AABB(origin.getX(),low-.001,origin.getZ(),origin.getX()+16,high+VISUAL_SURFACE_CLEARANCE+.001,origin.getZ()+16),new ConcurrentHashMap<>());
    }
    private static void packTileRange(BuildSnapshot snapshot,BuildJob job,WorkerSampling sampling,List<BuiltCell> built,
            int[] firstVertices,ByteBuffer data,CellData[] cells,int first,int end){
        // Independent buffer cursors and disjoint byte ranges share one immutable-on-publication source.
        var target=data.duplicate().order(ByteOrder.nativeOrder());
        target.position(Math.multiplyExact(firstVertices[first],BYTES)).limit(Math.multiplyExact(firstVertices[end],BYTES));
        int packed=firstVertices[first];
        for(int i=first;i<end;i++){
            checkCancelled(job);
            var cell=built.get(i);var input=cell.input;BlockPos pos=input.geometry.pos();sampling.beginCell(input.geometry);
            float block=input.blockLight,sky=input.skyLight;int firstVertex=packed;
            boolean cavity=needsLocalOcclusion(input,snapshot.lighting,sampling);
            var horizon=sampling.localApproximation&&sampling.persistentAo&&cavity&&!input.geometry.generic()
                    ?AO_HORIZON_MEMOS.acquire(pos.asLong(),pos.getX(),pos.getZ()):null;
            try{
                if(horizon!=null&&horizon.bind(input,snapshot.lighting))sampling.horizonMemo=horizon.cache;
                var geometry=cell.triangles;var values=geometry.vertices;
                for(int vertex=0;vertex<geometry.indexCount;vertex++){
                    int index=geometry.indices[vertex],at=index*SnowTriangleBuffer.STRIDE;
                    float visibility=geometry.visibility[index];
                    if(Float.isNaN(visibility)){
                        visibility=cavity?skyVisibility(values[at],values[at+1],values[at+2],
                                values[at+5],values[at+6],values[at+7],(int)values[at+11],input,snapshot.lighting,sampling):1;
                        geometry.visibility[index]=visibility;
                    }
                    packed=packVertex(target,values,at,pos,snapshot.origin,block,sky,packed,job,visibility);
                }
            }finally{
                sampling.horizonMemo=null;
                if(horizon!=null)AO_HORIZON_MEMOS.release(horizon);
            }
            if(packed!=firstVertices[i+1])throw new IllegalStateException("Packed snow owner size changed");
            cells[i]=new CellData(input.column,input.geometry,input.contacts,
                    prepareCollisionPressure(input.contacts,sampling),input.corners,firstVertex,packed-firstVertex,
                    input.blockLight,input.skyLight,input.skirtSides,sampling.localApproximation||cell.fineBudgetLimited,
                    cell.approximationErrorMillimeters,cell.fineBudgetLimited,null);
        }
    }
    // Shift caps and closure walls together above retained native support faces.
    // Collision and persisted physical heights remain exact; shadows use the same packed mesh.
    private static final double VISUAL_SURFACE_CLEARANCE=.0005;
    private static int packVertex(ByteBuffer data,DebugVertex v,BlockPos pos,BlockPos origin,float block,float sky,int packed,BuildJob job,float visibility){
        if((packed&1023)==0)checkCancelled(job);
        // Position.w carries sky visibility for caps or a negative shared-wall side code.
        float packedVisibility=v.sharedWallSide<0?visibility:-1-v.sharedWallSide;
        data.putFloat((float)(v.x+pos.getX()-origin.getX())).putFloat((float)(v.y+VISUAL_SURFACE_CLEARANCE)).putFloat((float)(v.z+pos.getZ()-origin.getZ())).putFloat(packedVisibility);
        data.putFloat((float)v.u).putFloat((float)v.v).putFloat(v.blockLight>=0?(float)v.blockLight:block).putFloat(v.skyLight>=0?(float)v.skyLight:sky);
        data.putFloat((float)v.normalX).putFloat((float)v.normalY).putFloat((float)v.normalZ).putFloat((float)v.depthMillimeters);
        return packed+1;
    }
    private static int packVertex(ByteBuffer data,double[] v,int at,BlockPos pos,BlockPos origin,
            float block,float sky,int packed,BuildJob job,float visibility){
        if((packed&1023)==0)checkCancelled(job);
        float packedVisibility=v[at+11]<0?visibility:-1-(int)v[at+11];
        data.putFloat((float)(v[at]+pos.getX()-origin.getX())).putFloat((float)(v[at+1]+VISUAL_SURFACE_CLEARANCE))
                .putFloat((float)(v[at+2]+pos.getZ()-origin.getZ())).putFloat(packedVisibility);
        data.putFloat((float)v[at+3]).putFloat((float)v[at+4])
                .putFloat(v[at+9]>=0?(float)v[at+9]:block).putFloat(v[at+10]>=0?(float)v[at+10]:sky);
        data.putFloat((float)v[at+5]).putFloat((float)v[at+6]).putFloat((float)v[at+7]).putFloat((float)v[at+8]);
        return packed+1;
    }
    /** Avoid vertex work for a complete plane; supports on another floor remain distinct. */
    private static boolean needsLocalOcclusion(CellSnapshot input,Map<Long,CellSnapshot> patch){
        return needsLocalOcclusion(input,patch,WORKER_SAMPLING.get());
    }
    private static boolean needsLocalOcclusion(CellSnapshot input,Map<Long,CellSnapshot> patch,WorkerSampling sampling){
        var pos=input.geometry.pos();double height=input.geometry.nominalHeight(),fine=input.corners[0];
        if(input.geometry.generic())return true;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            var selected=lightingSample(patch,pos.getX()+dx+.5,pos.getZ()+dz+.5,height,sampling);
            if(selected==null)continue;var neighbor=selected.cell;
            if(!neighbor.contacts.isEmpty()||neighbor.geometry.mask()!=0||neighbor.geometry.crust()!=0
                    ||neighbor.geometry.generic()||Math.abs(neighbor.geometry.nominalHeight()-height)>1e-7)return true;
            for(double corner:neighbor.corners)if(Math.abs(corner-fine)>1e-7)return true;
        }
        return false;
    }
    /** Carry the exact height already evaluated by support selection instead of sampling it twice. */
    private record LightingSample(CellSnapshot cell,double skinHeight) {}
    private static double lightingSkin(CellSnapshot input,double x,double z,WorkerSampling sampling){
        return sampling==null||input.geometry.generic()
                ?SnowHostGeometry.height(input.geometry,x,z,input.corners):sampling.compiledHeight(input).skin(x,z);
    }
    private static double lightingCompactedHeight(CellSnapshot input,double x,double z,double skin,WorkerSampling sampling){
        double pressure=signedDepression(input.contacts,x,z,sampling);
        if(sampling==null||input.geometry.generic()||!Double.isFinite(skin)||!Double.isFinite(pressure))
            return SnowHostGeometry.clampCompactedHeight(input.geometry,x,z,skin,pressure);
        return Math.max(sampling.compiledHeight(input).bottom(x,z),skin-pressure/1000);
    }
    private static LightingSample lightingSample(Map<Long,CellSnapshot> patch,double x,double z,double referenceHeight,WorkerSampling sampling){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z),by=(int)Math.floor(referenceHeight);
        CellSnapshot nearest=null;double nearestSkin=Double.NaN,distance=Double.POSITIVE_INFINITY;
        var indexed=sampling!=null&&sampling.lighting!=null&&sampling.lighting.source==patch?sampling.lighting:null;
        for(int dy=-3;dy<=1;dy++){
            var candidate=indexed==null?patch.get(BlockPos.asLong(bx,by+dy,bz)):indexed.at(bx,by+dy,bz);if(candidate==null)continue;
            double height=lightingSkin(candidate,x,z,sampling);
            double difference=Math.abs(height-referenceHeight);
            if(Double.isFinite(height)&&difference<distance&&difference<=1.75){nearest=candidate;nearestSkin=height;distance=difference;}
        }
        return nearest==null?null:new LightingSample(nearest,nearestSkin);
    }
    /** Bounded geometry-only sky occlusion, including neighbouring banks across block/chunk boundaries.
     * Subtract the current tangent so an unbroken plane keeps its original material brightness. */
    private static float localSkyVisibility(DebugVertex v,CellSnapshot input,Map<Long,CellSnapshot> patch,Map<DebugVertex,Float> cache){
        return localSkyVisibility(v,input,patch,cache,WORKER_SAMPLING.get());
    }
    private static float localSkyVisibility(DebugVertex v,CellSnapshot input,Map<Long,CellSnapshot> patch,Map<DebugVertex,Float> cache,WorkerSampling sampling){
        if(cache==null||v.sharedWallSide>=0||v.normalY<=.05)return 1;
        Float known=cache.get(v);if(known!=null)return known;
        float visibility=skyVisibility(v.x,v.y,v.z,v.normalX,v.normalY,v.normalZ,v.sharedWallSide,input,patch,sampling);
        cache.put(v,visibility);return visibility;
    }
    private static float skyVisibility(double x,double y,double z,double nx,double ny,double nz,int side,
            CellSnapshot input,Map<Long,CellSnapshot> patch,WorkerSampling sampling){
        if(side>=0||ny<=.05)return 1;
        // Both detail stages use the same world-space horizon field and exact tangent.
        double[] samples=horizonSamples(x,y,z,input,patch,sampling);double sum=0;
        double wx=input.geometry.pos().getX()+x,wz=input.geometry.pos().getZ()+z;
        for(int ray=0;ray<4;ray++){
            double horizon=0;
            for(int step=0;step<3;step++){
                int offset=(ray*3+step)*4;double distance=samples[offset+3];if(distance<1e-8)continue;
                double dx=samples[offset+1],dz=samples[offset+2];
                dx-=wx;dz-=wz;distance=Math.hypot(dx,dz);if(distance<1e-8)continue;
                double rise=samples[offset]-y+(nx*dx+nz*dz)/ny;
                horizon=Math.max(horizon,Math.max(0,rise)/distance);
            }
            sum+=WkeSnowLighting.horizonVisibility(horizon);
        }
        return (float)(sum*.25);
    }
    private static double[] horizonSamples(DebugVertex v,CellSnapshot input,Map<Long,CellSnapshot> patch){
        return horizonSamples(v,input,patch,WORKER_SAMPLING.get());
    }
    private static double[] horizonSamples(DebugVertex v,CellSnapshot input,Map<Long,CellSnapshot> patch,WorkerSampling sampling){
        return horizonSamples(v.x,v.y,v.z,input,patch,sampling);
    }
    private static double[] horizonSamples(double x,double y,double z,CellSnapshot input,Map<Long,CellSnapshot> patch,WorkerSampling sampling){
        var cell=input.geometry;double wx=cell.pos().getX()+x,wz=cell.pos().getZ()+z;
        double originX=wx,originY=y,originZ=wz;boolean grid=false;
        if(!cell.generic()){
            double qx=Math.rint(wx*64)/64,qz=Math.rint(wz*64)/64;
            if(cell.contains(qx,qz)){originX=qx;originZ=qz;grid=true;}
        }
        // beginCell() clears this cache and input geometry/corners/contacts are immutable during
        // packing. The world grid identifies one exact physical owner sample in both stages.
        double keyX=originX,keyY=grid?cell.nominalHeight():y,keyZ=originZ;
        var result=sampling==null?new double[48]:sampling.horizonScratch;
        if(sampling!=null){
            sampling.horizonQueries++;
            if(sampling.horizonRays.get(keyX,keyY,keyZ,result)){sampling.horizonHits++;return result;}
        }
        Arrays.fill(result,0);
        var persistent=grid&&sampling!=null?sampling.horizonMemo:null;
        if(persistent!=null&&persistent.get(originX,originZ,result)){
            sampling.horizonRays.put(keyX,keyY,keyZ,result);
            return result;
        }
        if(grid){
            double skin=lightingSkin(input,originX,originZ,sampling);
            if(Double.isFinite(skin)){
                originY=lightingCompactedHeight(input,originX,originZ,skin,sampling);
            }else{originX=wx;originY=y;originZ=wz;keyX=wx;keyY=y;keyZ=wz;persistent=null;}
        }
        for(int ray=0;ray<4;ray++)for(int step=0;step<3;step++){
            double radius=step==0?.06:step==1?.25:.75;
            double sampleX=originX+(ray==0?radius:ray==2?-radius:0),sampleZ=originZ+(ray==1?radius:ray==3?-radius:0);
            var selected=lightingSample(patch,sampleX,sampleZ,originY,sampling);if(selected==null)continue;
            var neighbor=selected.cell;var owner=neighbor.geometry;
            double localX=Math.clamp(sampleX-owner.pos().getX(),0,1),localZ=Math.clamp(sampleZ-owner.pos().getZ(),0,1);
            double dx=owner.pos().getX()+localX-originX,dz=owner.pos().getZ()+localZ-originZ,distance=Math.hypot(dx,dz);if(distance<1e-8)continue;
            double worldX=owner.pos().getX()+localX,worldZ=owner.pos().getZ()+localZ;
            double height=lightingCompactedHeight(neighbor,worldX,worldZ,selected.skinHeight,sampling);
            int offset=(ray*3+step)*4;result[offset]=height;
            result[offset+1]=worldX;result[offset+2]=worldZ;result[offset+3]=distance;
        }
        if(persistent!=null)persistent.put(originX,originZ,result);
        if(sampling!=null)sampling.horizonRays.put(keyX,keyY,keyZ,result);
        return result;
    }
    private static P patchPoint(SnowHostGeometry.Cell face,double x,double z,double[] corners){
        double delta=(corners[0]*(1-x)+corners[1]*x)*(1-z)+(corners[3]*(1-x)+corners[2]*x)*z;
        return new P(x,face.pos().getY()+face.patches().getFirst().top()+delta/1000,z);
    }
    private static DebugVertex sharedSideVertex(P p,DebugVertex cap,int side){
        // Integer world tile boundaries use the same atlas phase on both owners.
        boolean alongX=(side&1)==0;
        return new DebugVertex(p.x,p.y,p.z,alongX?p.x:0,alongX?0:p.z,
                cap.normalX,cap.normalY,cap.normalZ,p.depthMillimeters,cap.blockLight,cap.skyLight,side);
    }
    private static DebugVertex sideVertex(P p,double nx,double nz,double verticalUv){
        // UVLight is mapped into a single atlas sprite, so every side coordinate must stay inside0..1.
        return new DebugVertex(p.x,p.y,p.z,nx==0?p.x:p.z,verticalUv,nx,0,nz,p.depthMillimeters);
    }

    private record P(double x,double y,double z,double depthMillimeters){
        P(double x,double y,double z){this(x,y,z,0);}
    }
    private static P point(SnowTerrainGeometry.Vertex p,SnowHostGeometry.Cell cell,double[] corners){
        return new P(p.x(),cell.pos().getY()+cell.baseOffset()+SnowTerrainGeometry.vertexY(p,cell.mask(),cell.crust(),corners),p.z());
    }
    private static void draw(LevelTerrainRenderContext context){
        drawn=0;Frame frame=context.levelState().getData(FRAME);if(frame==null||frame.epoch!=epoch||frame.meshes.isEmpty())return;
        var selected=displaySelections(frame.all);
        var camera=context.levelState().cameraRenderState;var game=context.gameRenderer();var target=game.mainRenderTarget();
        Minecraft client=Minecraft.getInstance();
        TextureAtlas texture=(TextureAtlas)client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var sprite=texture.getSprite(TEXTURE);
        int anisotropy=client.options.textureFiltering().get()==TextureFilteringMethod.ANISOTROPIC?client.options.maxAnisotropyValue():1;
        if(materialSampler==null||materialSampler.getMaxAnisotropy()!=anisotropy){
            if(materialSampler!=null)materialSampler.close();
            materialSampler=RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,FilterMode.LINEAR,anisotropy,OptionalDouble.empty());
        }
        var transform=RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(camera.viewRotationMatrix));
        var lightSampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var pipeline=RenderSystem.getCompiledPipeline(PIPELINE);
        for(Mesh mesh:frame.meshes){
            if(mesh.vertices==0||mesh.groups.stream().noneMatch(group->selected.get(group.section)==mesh))continue;
            GpuMesh allocation=gpu.computeIfAbsent(mesh,ignored->new GpuMesh());
            var buffers=new IdentityHashMap<SnowMeshBuffer,GpuBuffer>();
            for(var group:mesh.groups)if(selected.get(group.section)==mesh)
                buffers.computeIfAbsent(group.source,source->vertexBuffer(source,sourceGpu.computeIfAbsent(source,ignored->new GpuMesh()),frame.id));
            var light=VisualRenderer.snowLighting();
            GpuBuffer uniform=writeView(allocation,false,mesh,camera.pos.x,camera.pos.y,camera.pos.z,
                    sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1(),light);
            try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"WKE authored snow surface",
                    target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
                pass.setPipeline(pipeline);RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",transform);pass.setUniform("SnowView",uniform);
                pass.setUniform("Sampler0",texture.getTextureView(),materialSampler);pass.setUniform("Sampler2",game.lightmap(),lightSampler);
                for(var group:mesh.groups)if(selected.get(group.section)==mesh){
                    pass.setVertexBuffer(0,buffers.get(group.source).slice());
                    pass.draw(group.vertexCount,1,group.firstVertex,0);drawn+=group.cells;
                }
            }
            observeLocalDraw(mesh,selected,frame.id);
            allocation.view.rotate();
        }
        frames++;if((frame.id&15)==0)retireSourceGpu();
    }
    /** Immutable payload identity, not a contact mesh revision, controls vertex uploads. */
    private static GpuBuffer vertexBuffer(SnowMeshBuffer source,GpuMesh allocation,long frame){
        int bytes=source.data.remaining();allocation.lastUseFrame=frame;
        if(allocation.vertices==null||allocation.capacity<bytes){
            allocation.close();allocation.capacity=Math.max(4096,Integer.highestOneBit(Math.max(1,bytes-1))<<1);
            allocation.vertices=new MappableRingBuffer(()->"WKE immutable snow tiles",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_MAP_WRITE,allocation.capacity);
        }
        if(allocation.vertexFrame!=frame){
            if(allocation.frameVertices!=null)allocation.vertices.rotate();
            allocation.frameVertices=allocation.vertices.currentBuffer();allocation.vertexFrame=frame;
        }
        GpuBuffer buffer=allocation.frameVertices;
        if(!allocation.uploaded.containsKey(buffer)){
            try(var mapped=buffer.map(0,bytes,false,true)){mapped.data().put(source.data.duplicate());}
            allocation.uploaded.put(buffer,1L);uploads++;sourceUploadedBytes+=bytes;
        }
        return buffer;
    }
    private static void retireSourceGpu(){
        var live=Collections.newSetFromMap(new IdentityHashMap<SnowMeshBuffer,Boolean>());
        for(var mesh:liveCpuMeshes())for(var cell:mesh.cells.values())live.add(cell.range.source());
        for(var iterator=sourceGpu.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();
            if(!live.contains(entry.getKey())&&frameSequence-entry.getValue().lastUseFrame>=3){
                entry.getValue().close();iterator.remove();sourceRetiredBuffers++;
            }
        }
    }
    private static GpuBuffer writeView(GpuMesh allocation,boolean shadow,Mesh mesh,double x,double y,double z,
            float u0,float v0,float u1,float v1,WkeSnowLighting.Sunlight light){
        MappableRingBuffer ring=shadow?allocation.shadowView:allocation.view;
        if(ring==null){
            ring=new MappableRingBuffer(()->shadow?"WKE snow shadow view":"WKE snow main view",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,64);
            if(shadow)allocation.shadowView=ring;else allocation.view=ring;
        }
        if(shadow&&allocation.shadowUsed){ring.rotate();allocation.shadowUsed=false;}
        GpuBuffer uniform=ring.currentBuffer();
        try(var mapped=uniform.map(false,true)){
            mapped.data().order(ByteOrder.nativeOrder())
                    .putFloat((float)(mesh.origin.getX()-x)).putFloat((float)-y).putFloat((float)(mesh.origin.getZ()-z)).putFloat(0)
                    .putFloat(u0).putFloat(v0).putFloat(u1).putFloat(v1)
                    .putFloat((float)light.sunX()).putFloat((float)light.sunY()).putFloat((float)light.sunZ()).putFloat((float)light.intensity())
                    .putFloat((float)WkeSnowLighting.GROUND_BOUNCE).putFloat((float)WkeSnowLighting.UPPER_SKY)
                    .putFloat((float)WkeSnowLighting.DIRECT_FRACTION).putFloat((float)WkeSnowLighting.AO_STRENGTH);
        }
        return uniform;
    }
    /** Called before the shadow pass opens: compile, light-cull, upload and map all caster resources here. */
    public static void prepareShadowCasters(Matrix4fc lightView,Matrix4fc lightClip,double cameraX,double cameraY,double cameraZ){
        shadowPrepared=null;Frame frame=extractedFrame;
        if(frame==null||frame.epoch!=epoch)return;
        var pipeline=RenderSystem.getCompiledPipeline(SHADOW_PIPELINE);
        var draws=new ArrayList<ShadowDraw>();var light=VisualRenderer.snowLighting();var selected=displaySelections(frame.all);
        for(Mesh mesh:frame.all){
            if(mesh.vertices==0||!lightIntersects(mesh.bounds,lightClip,cameraX,cameraY,cameraZ)||mesh.groups.stream().noneMatch(group->selected.get(group.section)==mesh))continue;
            GpuMesh allocation=gpu.computeIfAbsent(mesh,ignored->new GpuMesh());
            GpuBuffer view=writeView(allocation,true,mesh,cameraX,cameraY,cameraZ,0,0,1,1,light);
            for(var group:mesh.groups)if(selected.get(group.section)==mesh){
                GpuBuffer vertices=vertexBuffer(group.source,sourceGpu.computeIfAbsent(group.source,ignored->new GpuMesh()),frame.id);
                draws.add(new ShadowDraw(mesh,group,vertices,view));
            }
        }
        var transform=RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(lightView));
        shadowPrepared=new ShadowPrepared(epoch,frame.id,List.copyOf(draws),transform,pass->pass.setPipeline(pipeline));
    }
    /** Bind/draw only: no upload, mapping or transfer may occur inside the existing shadow pass. */
    public static void drawShadow(RenderPass pass,Matrix4fc lightView,Matrix4fc lightClip,double cameraX,double cameraY,double cameraZ){
        ShadowPrepared prepared=shadowPrepared;Frame frame=extractedFrame;
        if(frame==null||frame.epoch!=epoch)return;
        if(prepared==null||prepared.epoch!=epoch||prepared.frame!=frame.id)throw new IllegalStateException("Snow shadow casters were not prepared");
        for(var draw:prepared.draws){
            prepared.bindPipeline.accept(pass);RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",prepared.transform);pass.setUniform("SnowView",draw.view);
            pass.setVertexBuffer(0,draw.vertices.slice());pass.draw(draw.group.vertexCount,1,draw.group.firstVertex,0);
            gpu.get(draw.mesh).shadowUsed=true;
        }
    }
    /** Conservative zero-to-one clip test against the LIGHT volume, including off-camera casters. */
    private static boolean lightIntersects(AABB box,Matrix4fc clip,double cameraX,double cameraY,double cameraZ){
        int outside=63;
        for(int corner=0;corner<8;corner++){
            double x=((corner&1)==0?box.minX:box.maxX)-cameraX,y=((corner&2)==0?box.minY:box.maxY)-cameraY,z=((corner&4)==0?box.minZ:box.maxZ)-cameraZ;
            double cx=clip.m00()*x+clip.m10()*y+clip.m20()*z+clip.m30();
            double cy=clip.m01()*x+clip.m11()*y+clip.m21()*z+clip.m31();
            double cz=clip.m02()*x+clip.m12()*y+clip.m22()*z+clip.m32();
            double cw=clip.m03()*x+clip.m13()*y+clip.m23()*z+clip.m33();
            outside&=(cx< -cw?1:0)|(cx>cw?2:0)|(cy< -cw?4:0)|(cy>cw?8:0)|(cz<0?16:0)|(cz>cw?32:0);
            if(outside==0)return true;
        }
        return false;
    }
    private static void reloadGpu(){
        shadowPrepared=null;ShadowCaptureCache.terrainChanged();
        for(var entry:gpu.values())entry.close();gpu.clear();
        for(var entry:sourceGpu.values())entry.close();sourceGpu.clear();
        if(materialSampler!=null)materialSampler.close();materialSampler=null;
        if(activeBuild!=null)activeBuild.cancelled=true;if(preparing!=null)preparing.cancelled=true;
        for(long key:columns.keySet()){geometryRevisions.put(key,++geometryRevision);markDirty(key,DirtyCause.NATIVE);}
    }
    private static void reset(){
        reloadGpu();
        if(rangeRepack!=null)rangeRepack.cancelled=true;rangeRepack=null;rangeRepackTick=-1;
        rangeRepacks=rangeRepackBytes=rangeRepackStale=rangeRepackFailures=0;
        sourceUploadedBytes=sourceRetiredBuffers=sourcePayloadCopies=sourceExactRangeReuses=0;
        localTessellationWallNanos=localPackingWallNanos=localParallelPackJobs=0;
        clearCpuMeshPool();SnowTriangleBuffer.clearPool();fieldSignatures.clear();lastBuiltFieldContexts.clear();lastBuiltFieldGeometry.clear();lastFineBuildStarted.clear();fineContactBuildsDebounced=fineContactBuildsEquivalent=fineContactBuildsStrictFields=0;
        SnowLocalContacts.reset();DRAFT_ERROR_MEMOS.clear();AO_HORIZON_MEMOS.clear();FIELD_ENVELOPES.clear();BACKGROUND_FIELD_ENVELOPES.clear();RELIEF_CACHE.remove();HORIZON_WORK_CACHE.remove();PREPARED_CACHE.remove();coarseUntil.clear();fineQueued.clear();
        fineWitnesses.clear();localFineFollowups.clear();fineWitnessBytes=partialFineJobs=partialFineCells=partialFineCommits=partialFineFallbacks=0;consecutivePriorityPreparations=0;nearWeatherPreparations=0;maxLocalApproximationErrorMm=0;localOverBudgetLeaves=0;lastFinePublicationTick=-1;
        localContacts.clear();localEvents.clear();committedAuthoritative.clear();retiredLocalContacts.clear();
        canonicalCursor=0;canonicalBudgetTick=-1;canonicalChecksThisTick=weatherChecksThisTick=0;
        weatherAuthorityIndexes.clear();weatherRetiredLocals.clear();weatherPredictionUpdates=weatherPredictionBurials=weatherPredictionUnmatched=0;
        canonicalRetirements=unloadedRetirements=physicalRetirements=ttlUnconfirmedContacts=capacityUnconfirmedContacts=
                canonicalChecks=canonicalCacheHits=canonicalProbeSamples=canonicalCoreProbeFailures=canonicalWorstNanos=0;
        fineCaptureCandidates=fineCaptureDirty=fineWitnessesCaptured=fineSelectionAttempts=0;
        fineWitnessDropsDirty=fineWitnessDropsRange=fineWitnessDropsPreparation=fineWitnessDropsSuperseded=fineWitnessDropsBudget=0;
        Arrays.fill(dirtyEnqueueCounts,0);Arrays.fill(fineCaptureDirtyCauses,0);Arrays.fill(fineRejectCounts,0);
        pendingPressureChunks.clear();urgentPressureAuthority.clear();pressureLineages.clear();contactFrameStartedNanos=0;
        weatherPressureDeferrals=urgentPressureDeferrals=0;
        localGenerated=localPublished=localDrawn=localPatches=localPatchCells=localPatchNanos=localWorstPatchNanos=localWorstPublishTicks=localWorstDrawFrames=localEventDrops=0;
        localScannedTriangles=localReusedTriangles=localRefinedTriangles=localRetainedCoreTriangles=localCoreProofChecks=
                localRetainedDominatedTriangles=localDominanceProofChecks=localCopiedBytes=localPatchScanNanos=localPatchPackNanos=0;
        localReliefQueries=localReliefCacheHits=localReliefCandidates=localMaxThresholdTests=localMaxThresholdRejects=
                localRelitVertices=localAoRayQueries=localAoRayHits=localReliefCacheEvictions=0;
        localFeatureCandidates=localFeaturePointsInside=localFeatureHeightQueries=0;
        incoming=null;world=null;bootstrapDone=false;bootstrapCenter=Long.MIN_VALUE;preparing=null;readyFrames.clear();blockedFrames.clear();
        bootstrapCohort=null;rejectedBootstrapSources.clear();bootstrapNeedsFreshCohort=false;bootstrapRetryGeneration=0;
        bootstrapCohortCaptures=bootstrapCohortInvalidations=0;lastBootstrapCohortTick=-1;bootstrapCommittedSourceRevision=-1;
        clearBootstrapNativeMismatch();bootstrapCohortState="waiting for frames";
        bootstrapRequiredSections=Map.of();requiredNativeSections.clear();nativeSectionAcks.clear();handoffs=Map.of();ownership=new Ownership(ownership.epoch+1,Set.of());
        meshes=Map.of();extractedFrame=null;columns.clear();dirty.clear();dirtyCauses.clear();geometryRevisions.clear();marks=List.of();lastTick=-1;epoch++;drawn=0;
    }
}
