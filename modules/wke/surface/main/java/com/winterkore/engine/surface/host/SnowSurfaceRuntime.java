// Bounded server adapter for WKE weather history, authored snow surfaces and normal world contacts.
package com.winterkore.engine.surface.host;

import com.dark.winter.testing.TestingServer;
import com.dark.winter.weather.WeatherServer;
import com.dark.winter.worldgen.TestSpruceBlocks;
import com.winterkore.engine.surface.SnowSurface;
import com.winterkore.engine.surface.SnowTravelPolicy;
import com.winterkore.engine.surface.SnowStreamLookAhead;
import com.winterkore.engine.surface.SnowSurfaceStore;
import com.winterkore.engine.surface.SnowWeatherTimeline;
import com.winterkore.engine.surface.SnowWeatherTimelineStore;
import com.winterkore.engine.surface.geometry.SnowContactRelief;
import com.winterkore.engine.surface.geometry.SnowFootContactPolicy;
import com.winterkore.engine.surface.geometry.SoleProfiles;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft provides geometry and climate. WKE owns continuous depth, weather replay and contact policy. */
public final class SnowSurfaceRuntime {
    public static final int MAX_RESIDENT_CHUNKS=256,COLUMNS_PER_TICK=64,MAX_WORLD_WRITES_PER_TICK=0,MAX_SURFACES_PER_COLUMN=8;
    private static final Map<MinecraftServer,State> STATES=new HashMap<>();
    private static final Logger STREAM_LOG=LoggerFactory.getLogger("WinterKore/SnowStream");
    public static final int PREFETCH_RADIUS=3;
    private static final long HYDRATION_BUDGET_NANOS=2_000_000,STREAM_BUDGET_NANOS=1_500_000;
    private static final long SAVE_RETRY_NANOS=1_000_000_000L;
    private static final int MAX_STREAM_FRAMES_PER_TICK=2,FRAME_RECHECK_TICKS=20;
    private static final double FRAME_HEIGHT_QUANTUM_MM=.5,CONTACT_DEPTH_QUANTUM_MM=.25;
    private static final int RENDER_TRACE_BATCH=8,RENDER_TRACES_PER_TICK=256,RENDER_BODY_HISTORY_TICKS=12,COLLISION_PREPARED_CACHE=512,BOOTSTRAP_COHORT_HISTORY=32;
    private static final float[] OPEN={1,1,1,1,1,1,1,1};
    private static volatile SurfaceView clientView=pos->0;
    private static boolean initialized;
    public interface SurfaceView {
        double deltaMillimeters(BlockPos pos);
        default Object contactSnapshotKey(BlockPos owner){return List.of();}
        /** Immutable descriptor of the actual published cap, or null before ownership. */
        default SnowHostGeometry.Cell geometry(BlockPos owner){return null;}
        default double depressionMillimeters(double x,double z){return 0;}
        default double depressionMillimeters(BlockPos owner,double x,double z){return depressionMillimeters(x,z);}
        /** Immutable published corners of the actual owned client mesh, or null before ownership. */
        default double[] fineCornersMillimeters(BlockPos owner){return null;}
        /** The exact physical column used by local pressure, which may be newer than the displayed cap. */
        default SnowSurfacePayload.Column pressureColumn(BlockPos owner){return null;}
    }
    /** Client owns its immutable network mirror; no client classes or threads enter server state. */
    public static void installClientView(SurfaceView view){clientView=view==null?pos->0:view;}
    public record Exposure(float vertical,float[] directional) {
        public Exposure {directional=directional.clone();}
        @Override public float[] directional(){return directional.clone();}
    }
    private record ExposureCache(long tick,int y,int layers,Exposure exposure) {}
    private static final Exposure SHELTERED_EXPOSURE=new Exposure(0,new float[8]);
    private static final int WEATHER_BATCHES_PER_TICK=32,WEATHER_COLUMNS_PER_BATCH=16,WEATHER_CONTACTS_PER_BATCH=32;
    private static final long WEATHER_NANOS_PER_TICK=2_000_000;
    /** Per-surface cursors coalesce to the latest weather; they never enqueue one job per weather tick. */
    private static final class WeatherWork implements SnowSurface.ContactExposure {
        final State state;final long key;final SnowSurface surface;
        int column;boolean active,contactsDone,visiblyChanged;long nextCycle;
        WeatherWork(State state,long key,SnowSurface surface){this.state=state;this.key=key;this.surface=surface;}
        private Exposure at(SnowSurface.Contact contact){
            Map<Long,ExposureCache> cached=state.exposure.get(key);
            if(cached==null)return SHELTERED_EXPOSURE;
            ExposureCache observed=null;
            if(contact.ownerY()!=Integer.MIN_VALUE){
                int x=chunkX(key)*16+(int)Math.floor(contact.x()),z=chunkZ(key)*16+(int)Math.floor(contact.z());
                observed=cached.get(BlockPos.asLong(x,contact.ownerY(),z));
            }else for(var item:cached.values())if(Math.abs(item.y()-contact.y())<1.25){observed=item;break;}
            return observed==null?SHELTERED_EXPOSURE:observed.exposure();
        }
        public float vertical(SnowSurface.Contact contact){return at(contact).vertical();}
        public float[] directional(SnowSurface.Contact contact){return at(contact).directional;}
    }
    public record WeatherMaintenanceMetrics(long batches,long columns,long contacts,long completedSweeps,long elapsedNanos,
            long lastTickNanos,int lastTickBatches,int lastTickColumns,int lastTickContacts,int pendingSurfaces,int dirtyChunkViews) {}
    private record SurfaceSaveRetry(AccessDeniedException denial,long afterNanos) {}
    private static final class State {
        final ServerLevel level;
        final SnowWeatherTimeline timeline;
        final LinkedHashMap<Long,SnowSurface> chunks=new LinkedHashMap<>(32,.75f,true);
        final Map<Long,Map<Integer,SnowSurface>> extraLayers=new HashMap<>();
        final Set<Long> pendingUnloads=new java.util.LinkedHashSet<>();
        final Map<Long,SurfaceSaveRetry> surfaceSaveRetries=new LinkedHashMap<>();
        AccessDeniedException historySaveDenial;
        long historyRetryAfter;
        final Map<Long,Map<Long,ExposureCache>> exposure=new HashMap<>();
        final java.util.ArrayDeque<WeatherWork> weatherWork=new java.util.ArrayDeque<>();
        final java.util.IdentityHashMap<SnowSurface,WeatherWork> weatherTasks=new java.util.IdentityHashMap<>();
        final java.util.LinkedHashSet<Long> weatherDirtyChunks=new java.util.LinkedHashSet<>();
        long weatherBatches,weatherColumns,weatherContacts,weatherCompleted,weatherNanos,weatherLastNanos;
        int weatherLastBatches,weatherLastColumns,weatherLastContacts;
        final Map<Long,List<SnowSurface.Contact>> contactViews=new HashMap<>();
        final Map<Long,CollisionIndex> collisionIndices=new HashMap<>();
        final LinkedHashMap<CollisionKernel,SnowContactRelief.Prepared> collisionPrepared=new LinkedHashMap<>(64,.75f,true);
        final ArrayList<CollisionEntry> collisionMatches=new ArrayList<>(64);
        final SnowContactRelief.FieldAccumulator collisionField=new SnowContactRelief.FieldAccumulator();
        long collisionQueries,collisionIndexBuilds,collisionIndexedContacts,collisionCacheHits,collisionCacheMisses,collisionCacheEvictions;
        long collisionCandidates,collisionOwnerMatches,collisionDepressionEvaluations,collisionRimEvaluations;
        final Map<Long,Long> contactRevisions=new HashMap<>();
        long contactRevision;
        final Map<Long,Map<Long,Long>> windAt=new HashMap<>();
        final Map<UUID,PlayerSnowFootprints.Walker> walkers=new HashMap<>();
        final Map<UUID,RenderedTraceState> renderedTraces=new HashMap<>();
        final Map<Long,Hydration> hydration=new HashMap<>();
        final LinkedHashMap<Long,Boolean> supportEdits=new LinkedHashMap<>();
        boolean processingEdits;
        final Map<UUID,StreamPlayer> streamPlayers=new HashMap<>();
        List<Work> work=List.of();
        boolean refreshWork=true;
        long cursor,processed,contacts,streamRevision,sentFrames,streamTicks,persistedTimelineTick=Long.MIN_VALUE;
        State(ServerLevel level) {
            this.level=level;
            try{timeline=SnowWeatherTimelineStore.load(historyPath(level),level.getGameTime());}
            catch(IOException ex){throw new IllegalStateException("Cannot load WKE snow weather history; metadata was not reset",ex);}
        }
    }
    private static final class Hydration {
        final BitSet visited=new BitSet(256);
        @SuppressWarnings("unchecked")
        final List<SnowHostGeometry.Cell>[] geometry=(List<SnowHostGeometry.Cell>[])new List<?>[256];
        final long[] checked=new long[256];
    }
    private static final class Work {
        final long key;final BitSet desired=new BitSet(256),critical=new BitSet(256);
        double priority=Double.POSITIVE_INFINITY;
        Work(long key){this.key=key;}
    }
    /**
     * Immutable WKE publication capability. Only the installed client surface can create one;
     * no entity, Level, mutable mesh, requested pressure depth or network payload crosses threads.
     */
    public static final class RenderedSupport {
        private final String dimension;
        private final SnowHostGeometry.Cell geometry;
        private final double[] corners;
        private final double x,soleY,z;
        private final SnowFootContactPolicy.Support pressure;
        private RenderedSupport(String dimension,SnowHostGeometry.Cell geometry,double[] corners,
                SnowSurfacePayload.Column source,double x,double soleY,double z,double skinY){
            this.dimension=dimension;this.geometry=geometry;this.corners=corners.clone();
            this.x=x;this.soleY=soleY;this.z=z;
            // Preserve the client's float column arithmetic before widening.
            this.pressure=new SnowFootContactPolicy.Support(Math.max(0,source.layers()*125f+source.deltaMillimeters()),
                    source.layers()*125.0,skinY);
        }
        public SnowHostGeometry.Cell geometry(){return geometry;}
        public double skinY(){return pressure.skinY();}
        public double physicalColumnDepthMillimeters(){return pressure.physicalColumnDepthMm();}
        public SnowFootContactPolicy.Press sample(boolean scuff,float load){
            return SnowFootContactPolicy.sampleFixed(pressure.physicalColumnDepthMm(),pressure.authoredBaseMm(),skinY(),soleY,scuff,load);
        }
        private boolean matches(ServerLevel level,SnowHostGeometry.Cell live,double x,double soleY,double z){
            return dimension.equals(level.dimension().identifier().toString())&&geometry.equals(live)
                    &&Double.compare(this.x,x)==0&&Double.compare(this.soleY,soleY)==0&&Double.compare(this.z,z)==0;
        }
    }
    /** Called only during the client contact capture, before either publication or server time advances. */
    public static RenderedSupport captureRenderedSupport(Level level,double x,double soleY,double z){
        if(level==null||!level.isClientSide()||!Double.isFinite(x+soleY+z))return null;
        var geometry=findGeometry(level,x,soleY,z);if(geometry==null)return null;
        var view=clientView;var published=view.geometry(geometry.pos());
        if(published==null||!published.equals(geometry))return null;
        double[] corners=view.fineCornersMillimeters(geometry.pos());
        var source=view.pressureColumn(geometry.pos());
        if(corners==null||corners.length!=4||source==null||source.x()!=geometry.pos().getX()
                ||source.y()!=geometry.pos().getY()||source.z()!=geometry.pos().getZ())return null;
        for(double corner:corners)if(!Double.isFinite(corner)||Math.abs(corner)>1500)return null;
        double top=SnowHostGeometry.sample(geometry,x,z,corners).height();
        if(!Double.isFinite(top)||Math.abs(top-soleY)>1.25)return null;
        return new RenderedSupport(level.dimension().identifier().toString(),geometry,corners,source,x,soleY,z,top);
    }
    /** A displayed sole carries its original measured support; pressure is still calculated by WKE. */
    public record RenderedFootTrace(double x,double soleY,double z,float yaw,float width,float length,boolean channel,RenderedSupport support) {
        public RenderedFootTrace(double x,double soleY,double z,float yaw,float width,float length,boolean channel){
            this(x,soleY,z,yaw,width,length,channel,null);
        }
    }
    /** Bounded immutable evidence only; a rejection never acknowledges/removes a client prediction. */
    public record RenderedTraceRejection(long sequence,long serverTick,String reason,Map<String,Object> trace,
            Map<String,Object> player,List<Map<String,Object>> recentBody,Map<String,Object> support) {}
    public record RenderedFootTraceMetrics(long sequence,long received,long stamped,long rejected,long budgetRejected,
            long serverPressureSweepTicksSuppressed,long serverStanceStamps,long serverLoadOnlyStamps,long serverFallbackPressureStamps,
            List<RenderedTraceRejection> lastRejections) {
        public RenderedFootTraceMetrics(long sequence,long received,long stamped,long rejected,long budgetRejected,
                List<RenderedTraceRejection> lastRejections){
            this(sequence,received,stamped,rejected,budgetRejected,0,0,0,0,lastRejections);
        }
    }
    private record RenderedStampFailure(String reason,SnowHostGeometry.Cell geometry,double skinY,double physicalDepth,double baseDepth) {}
    private record BodySample(long tick,double x,double y,double z,double scale,boolean grounded) {}
    private static final class RenderedTraceState {
        ServerPlayer owner;long sequence=-1,budgetTick=Long.MIN_VALUE,received,stamped,rejected,budgetRejected;int used;
        long lastRenderedPressureTick=Long.MIN_VALUE;
        long serverPressureSweepTicksSuppressed,serverStanceStamps,serverLoadOnlyStamps,serverFallbackPressureStamps;
        boolean renderedPressureActive(ServerPlayer player,long tick){
            return owner==player&&com.winterkore.engine.surface.geometry.SnowFootContactPolicy.renderedPressureActive(lastRenderedPressureTick,tick);
        }
        void resetBody(){body.clear();lastRenderedPressureTick=Long.MIN_VALUE;}
        final ArrayList<BodySample> body=new ArrayList<>(RENDER_BODY_HISTORY_TICKS+1);
        final ArrayList<RenderedTraceRejection> rejections=new ArrayList<>(8);
        void observe(ServerPlayer player,long tick){
            double scale=player.getScale(),x=player.getX(),y=player.getY(),z=player.getZ();
            if(owner!=player){owner=player;resetBody();}
            if(!Double.isFinite(x+y+z+scale)||scale<=0||scale>4){resetBody();return;}
            BodySample previous=body.isEmpty()?null:body.getLast();
            if(previous!=null){
                long elapsed=tick-previous.tick();double dt=Math.max(1,elapsed);
                if(elapsed<0||elapsed>RENDER_BODY_HISTORY_TICKS||Math.hypot(x-previous.x(),z-previous.z())>1.6*dt
                        ||Math.abs(y-previous.y())>2.5*dt)resetBody();
            }
            boolean enabled=player.isAlive()&&!player.isSpectator()&&!player.isPassenger()&&!player.isSleeping()
                    &&!player.isFallFlying()&&!player.isVisuallySwimming()&&!player.isInWater()&&!player.isInLava()
                    &&!player.onClimbable()&&!player.getAbilities().flying&&!player.noPhysics;
            var sample=new BodySample(tick,x,y,z,scale,enabled&&SnowLocomotion.grounded(player));
            if(!sample.grounded())lastRenderedPressureTick=Long.MIN_VALUE;
            if(!body.isEmpty()&&body.getLast().tick()==tick)body.set(body.size()-1,sample);else body.add(sample);
            while(!body.isEmpty()&&tick-body.getFirst().tick()>RENDER_BODY_HISTORY_TICKS)body.removeFirst();
        }
    }
    private record CollisionKernel(double x,double z,float yaw,float width,float length,float depth,boolean vehicle,int profile) {}
    private record CollisionEntry(SnowSurface.Contact contact,CollisionKernel kernel) {}
    private static final class CollisionBucket {
        CollisionEntry[] entries=new CollisionEntry[8];int size;
        void add(CollisionEntry entry){if(size==entries.length)entries=java.util.Arrays.copyOf(entries,size*2);entries[size++]=entry;}
    }
    /** One sidecar for one immutable collision view; every point reuses its prepared keys and bins. */
    private static final class CollisionIndex {
        final List<SnowSurface.Contact> source;final int minX,minZ,width,height;final CollisionBucket[] bins;
        CollisionIndex(List<SnowSurface.Contact> source,int chunkX,int chunkZ){
            this.source=source;var entries=new CollisionEntry[source.size()];
            int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(int i=0;i<source.size();i++){
                var c=source.get(i);
                // Preserve the established collision expression: int + float rounds before widening.
                double x=chunkX*16+c.x(),z=chunkZ*16+c.z();
                var kernel=new CollisionKernel(x,z,c.yaw(),c.width(),c.length(),c.depressionMillimeters(),c.vehicle(),c.soleProfile());
                entries[i]=new CollisionEntry(c,kernel);
                double radius=SnowContactRelief.influenceRadiusMeters(c.width(),c.length(),SnowSurface.MAX_CONTACT_DEPTH_MM);
                x0=Math.min(x0,(int)Math.floor(x-radius));z0=Math.min(z0,(int)Math.floor(z-radius));
                x1=Math.max(x1,(int)Math.floor(x+radius));z1=Math.max(z1,(int)Math.floor(z+radius));
            }
            minX=x0;minZ=z0;width=x1-x0+1;height=z1-z0+1;bins=new CollisionBucket[width*height];
            for(var entry:entries){
                var k=entry.kernel();double radius=SnowContactRelief.influenceRadiusMeters(k.width(),k.length(),SnowSurface.MAX_CONTACT_DEPTH_MM);
                int ax=(int)Math.floor(k.x()-radius)-minX,bx=(int)Math.floor(k.x()+radius)-minX;
                int az=(int)Math.floor(k.z()-radius)-minZ,bz=(int)Math.floor(k.z()+radius)-minZ;
                for(int z=az;z<=bz;z++)for(int x=ax;x<=bx;x++){
                    int index=x+z*width;CollisionBucket bucket=bins[index];if(bucket==null)bins[index]=bucket=new CollisionBucket();bucket.add(entry);
                }
            }
        }
        CollisionBucket near(double x,double z){
            if(!Double.isFinite(x)||!Double.isFinite(z))return null;
            int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
            if(bx<minX||bz<minZ||(long)bx-minX>=width||(long)bz-minZ>=height)return null;
            return bins[(bx-minX)+(bz-minZ)*width];
        }
    }
    public record CollisionPressureMetrics(long queries,long snapshotIndexBuilds,long indexedContacts,long preparedCacheHits,
            long preparedCacheMisses,long preparedCacheEvictions,long candidateVisits,long ownerMatches,
            long depressionEvaluations,long rimEvaluations,int preparedCacheSize,int snapshotIndexCount) {}
    public record CollisionPressureParity(double indexedMillimeters,double referenceMillimeters) {}
    private record SentFrame(long signature,long contactRevision,long checkedTick) {}
    private record StreamCandidate(long key,int tier,double priority,long checkedTick) {}
    private static final class PendingFrame {
        final SnowSurfaceChunkPayload.Chunk frame;final List<SnowSurfaceChunkPayload.Chunk> pages;
        final long signature,contactRevision;int nextPage;
        PendingFrame(SnowSurfaceChunkPayload.Chunk frame,long signature,long contactRevision){
            this.frame=frame;this.signature=signature;this.contactRevision=contactRevision;pages=SnowSurfaceChunkPayload.pages(frame);
        }
    }
    private static final class StreamPlayer {
        int x=Integer.MIN_VALUE,z=Integer.MIN_VALUE,focusX=Integer.MIN_VALUE,focusZ=Integer.MIN_VALUE;boolean bootstrapped;
        final SnowStreamLookAhead ahead=new SnowStreamLookAhead();
        final Map<Long,SentFrame> sent=new HashMap<>();
        List<PendingFrame> initialFrames=List.of();int initialNext;PendingFrame pending;
        int consecutiveContactFrames;
        /** Persist fairness across ticks; an expensive first slot may consume the complete time budget. */
        boolean firstFrameTurn(boolean contactsReady,boolean firstReady){
            boolean first=firstReady&&(!contactsReady||consecutiveContactFrames>=3);
            consecutiveContactFrames=contactsReady&&!first?Math.min(3,consecutiveContactFrames+1):0;
            return first;
        }
        long bootstrapRetryToken,bootstrapRetriesAccepted,bootstrapRetriesRejected,bootstrapRetryCooldownRejected,bootstrapRetryIdempotent;
        long lastBootstrapRetryTick=Long.MIN_VALUE,bootstrapSourceRevision,bootstrapAcksAccepted,bootstrapAcksRejected,bootstrapAcksIdempotent;
        long bootstrapAcceptedSourceRevision,bootstrapAcceptedOldAcks;
        final ArrayList<Long> bootstrapSentCohorts=new ArrayList<>(BOOTSTRAP_COHORT_HISTORY);
        boolean bootstrapInitialSent,bootstrapRequiresAck,bootstrapAwaitingAck,bootstrapAcknowledged;
    }
    private record ContactIdentity(float x,float y,float z,float yaw,float width,float length,boolean vehicle,int profile,int ownerY) {
        static ContactIdentity of(SnowSurface.Contact c){return new ContactIdentity(c.x(),c.y(),c.z(),c.yaw(),c.width(),c.length(),c.vehicle(),c.soleProfile(),c.ownerY());}
    }
    public record Metrics(int residentChunks,long processedColumns,long contacts) {}
    private SnowSurfaceRuntime(){}
    public static void init(){
        if(initialized)return;initialized=true;
        PayloadTypeRegistry.clientboundPlay().register(SnowSurfacePayload.TYPE,SnowSurfacePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnowSurfaceChunkPayload.TYPE,SnowSurfaceChunkPayload.CODEC);
        ServerChunkEvents.CHUNK_LOAD.register((level,chunk,generated)->{
            State s=STATES.get(level.getServer());if(s!=null&&s.level==level)s.refreshWork=true;
        });
        ServerTickEvents.END_SERVER_TICK.register(SnowSurfaceRuntime::tick);
        ServerChunkEvents.CHUNK_UNLOAD.register((level,chunk)->{
            State s=STATES.get(level.getServer());
            if(s!=null&&s.level==level){
                long key=key(chunk.getPos().x(),chunk.getPos().z());
                if(!evict(s,key))s.pendingUnloads.add(key);
            }
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((server,flush,force)->saveAll(server,flush||force));
        ServerLifecycleEvents.SERVER_STOPPING.register(SnowSurfaceRuntime::saveAll);
        ServerLifecycleEvents.SERVER_STOPPED.register(STATES::remove);
    }
    private static long key(int x,int z){return ((long)x&0xffffffffL)|((long)z<<32);}
    private static int chunkX(long key){return (int)key;}
    private static int chunkZ(long key){return (int)(key>>32);}
    private static int cell(int x,int z){return (x&15)+16*(z&15);}
    private static boolean allowed(ServerLevel level){return Level.OVERWORLD.equals(level.dimension())&&TestingServer.surfaceSimulationAllowed(level.getServer());}
    private static void owner(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Snow surface requires world thread");}
    private static State state(ServerLevel level){owner(level);return STATES.computeIfAbsent(level.getServer(),ignored->new State(level));}
    public static Path chunkPath(ServerLevel level,int x,int z){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/wke-surface/overworld").resolve(x+"_"+z+".bin");}
    public static Path layerPath(ServerLevel level,int x,int z,int ownerY){
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/wke-surface/overworld/layers").resolve(x+"_"+z+"_"+ownerY+".bin");
    }
    public static Path historyPath(ServerLevel level){return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/wke-surface/overworld/weather");}
    private static SnowSurface resident(State s,int x,int z){
        if(s.level.getChunkSource().getChunkNow(x,z)==null)return null;
        long k=key(x,z);SnowSurface surface=s.chunks.get(k);
        if(surface!=null){
            if(!s.pendingUnloads.isEmpty())s.pendingUnloads.remove(k);
            return surface;
        }
        // Eviction may flush queued construction edits, which can themselves hydrate this very chunk.
        // Resolve capacity first, then recheck ownership before loading; never overwrite that new cursor with stale disk data.
        while(s.chunks.size()>=MAX_RESIDENT_CHUNKS){
            if(historyRetryDeferred(s))return null;
            long oldest=s.chunks.entrySet().iterator().next().getKey();
            if(surfaceRetryDeferred(s,oldest))return null;
            boolean evicted=evict(s,oldest);
            surface=s.chunks.get(k);
            if(surface!=null){
                if(!s.pendingUnloads.isEmpty())s.pendingUnloads.remove(k);
                return surface;
            }
            if(!evicted&&s.chunks.size()>=MAX_RESIDENT_CHUNKS)return null;
        }
        try{surface=SnowSurfaceStore.load(chunkPath(s.level,x,z),x,z,s.level.getGameTime());}
        catch(IOException ex){throw new IllegalStateException("Cannot load snow surface; metadata was not reset",ex);}
        s.chunks.put(k,surface);s.contactViews.put(k,surface.contacts());return surface;
    }
    private static boolean evict(State s,long key){
        owner(s.level);
        applySupportEdits(s,s.timeline.snapshot(),4096);
        SnowSurface surface=s.chunks.get(key);
        if(surface!=null&&!saveSurfaces(s,key,surface,s.extraLayers.get(key),false))return false;
        s.chunks.remove(key);s.extraLayers.remove(key);s.pendingUnloads.remove(key);
        if(!s.surfaceSaveRetries.isEmpty())s.surfaceSaveRetries.remove(key);
        forgetCaches(s,key);
        return true;
    }
    private static boolean hasDirtySurfaces(SnowSurface primary,Map<Integer,SnowSurface> extra){
        if(primary.dirty())return true;
        if(extra!=null)for(var layer:extra.values())if(layer.dirty())return true;
        return false;
    }
    /** The legacy primary keeps its old path; extra levels allocate files only after observation. */
    private static SnowSurface surface(State s,BlockPos pos,boolean create){
        long k=key(pos.getX()>>4,pos.getZ()>>4);SnowSurface primary=create?resident(s,pos.getX()>>4,pos.getZ()>>4):s.chunks.get(k);
        if(primary==null)return null;int column=cell(pos.getX(),pos.getZ());
        if(primary.observed(column)&&primary.sample(column).snowY()==pos.getY())return primary;
        Map<Integer,SnowSurface> layers=s.extraLayers.get(k);
        SnowSurface found=layers==null?null:layers.get(pos.getY());
        if(found!=null&&found.observed(column)&&found.sample(column).snowY()==pos.getY())return found;
        if(!create)return null;
        if(!primary.observed(column))return primary;
        if(found!=null)return found;
        if(layers==null){layers=new HashMap<>();s.extraLayers.put(k,layers);}
        if(layers.size()>=48)return null;
        try{found=SnowSurfaceStore.load(layerPath(s.level,pos.getX()>>4,pos.getZ()>>4,pos.getY()),pos.getX()>>4,pos.getZ()>>4,s.level.getGameTime());}
        catch(IOException ex){throw new IllegalStateException("Cannot load layered snow surface; metadata was not reset",ex);}
        layers.put(pos.getY(),found);return found;
    }
    private static List<SnowSurface> surfaces(State s,long key){
        SnowSurface primary=s.chunks.get(key);if(primary==null)return List.of();
        Map<Integer,SnowSurface> extra=s.extraLayers.get(key);if(extra==null||extra.isEmpty())return List.of(primary);
        var result=new ArrayList<SnowSurface>(extra.size()+1);result.add(primary);
        extra.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add(e.getValue()));return result;
    }
    private static void pruneEmptyLayers(State s,long key){
        Map<Integer,SnowSurface> extra=s.extraLayers.get(key);if(extra==null)return;
        for(var iterator=extra.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();SnowSurface layer=entry.getValue();boolean occupied=false;
            for(int column=0;column<256;column++)if(layer.observed(column)){occupied=true;break;}
            if(occupied||!layer.contacts().isEmpty())continue;
            // Save the cleared owner before releasing its sparse layer; a later load must never resurrect old mass.
            if(layer.dirty()){
                if(surfaceRetryDeferred(s,key)||!saveHistory(s,false))continue;
                if(!saveSurfaceFile(s,key,layerPath(s.level,chunkX(key),chunkZ(key),entry.getKey()),layer,false))continue;
            }
            iterator.remove();
        }
        if(extra.isEmpty())s.extraLayers.remove(key);
    }
    private static void refreshContacts(State s,long key){refreshContacts(s,key,true);}
    private static void refreshContacts(State s,long key,boolean urgent){
        var marks=new ArrayList<SnowSurface.Contact>();
        for(var surface:surfaces(s,key)){
            var contacts=surface.contacts();for(int i=contacts.size()-1;i>=0;i--)marks.add(contacts.get(i));
        }
        var fresh=List.copyOf(marks);var previous=s.contactViews.put(key,fresh);
        // Aging/fill updates the real field, but cannot occupy the new-pressure streaming queue.
        // The existing bounded background signature checks still transmit its changed presentation.
        if(urgent&&contactPresentationHash(previous)!=contactPresentationHash(fresh)){
            var old=new HashMap<ContactIdentity,SnowSurface.Contact>();var current=new HashMap<ContactIdentity,SnowSurface.Contact>();
            if(previous!=null)for(var c:previous)old.put(ContactIdentity.of(c),c);
            for(var c:fresh)current.put(ContactIdentity.of(c),c);
            var changed=new java.util.HashSet<Long>();
            for(var entry:old.entrySet())if(contactVisualChanged(entry.getValue(),current.get(entry.getKey())))contactInfluence(s,key,entry.getValue(),changed);
            for(var entry:current.entrySet())if(contactVisualChanged(entry.getValue(),old.get(entry.getKey())))contactInfluence(s,key,entry.getValue(),changed);
            long revision=++s.contactRevision;for(long affected:changed)s.contactRevisions.put(affected,revision);
        }
    }
    private static long mix(long hash,long value){return (hash^value)*0x100000001b3L;}
    private static long contactPresentationHash(List<SnowSurface.Contact> contacts){
        if(contacts==null)return 0;long hash=0xcbf29ce484222325L;
        for(var c:contacts){hash=mix(hash,ContactIdentity.of(c).hashCode());hash=mix(hash,Math.round(c.depressionMillimeters()/CONTACT_DEPTH_QUANTUM_MM));hash=mix(hash,Math.round(c.strength()*512));}
        return hash;
    }
    private static boolean contactVisualChanged(SnowSurface.Contact a,SnowSurface.Contact b){
        return b==null||Math.round(a.depressionMillimeters()/CONTACT_DEPTH_QUANTUM_MM)!=Math.round(b.depressionMillimeters()/CONTACT_DEPTH_QUANTUM_MM)
                ||Math.round(a.strength()*512)!=Math.round(b.strength()*512);
    }
    private static void contactInfluence(State s,long chunkKey,SnowSurface.Contact c,Set<Long> changed){
        double x=chunkX(chunkKey)*16.0+c.x(),z=chunkZ(chunkKey)*16.0+c.z();
        double radius=SnowContactRelief.influenceRadiusMeters(c.width(),c.length(),c.depressionMillimeters());
        for(int cz=(int)Math.floor((z-radius)/16);cz<=(int)Math.floor((z+radius)/16);cz++)
            for(int cx=(int)Math.floor((x-radius)/16);cx<=(int)Math.floor((x+radius)/16);cx++){
                long affected=key(cx,cz);if(s.chunks.containsKey(affected))changed.add(affected);
            }
    }
    private static void forgetCaches(State s,long key){
        s.weatherWork.removeIf(work->work.key==key);s.weatherTasks.values().removeIf(work->work.key==key);s.weatherDirtyChunks.remove(key);
        s.exposure.remove(key);s.contactViews.remove(key);s.collisionIndices.remove(key);s.contactRevisions.remove(key);s.windAt.remove(key);s.hydration.remove(key);s.refreshWork=true;
    }
    private static boolean historyRetryDeferred(State s){
        return s.historySaveDenial!=null&&System.nanoTime()-s.historyRetryAfter<0;
    }
    private static boolean saveHistory(State s,boolean required){
        long tick=s.timeline.tick();
        if(s.persistedTimelineTick==tick)return true;
        if(!required&&historyRetryDeferred(s))return false;
        try{
            SnowWeatherTimelineStore.save(historyPath(s.level),s.timeline);
            s.persistedTimelineTick=tick;
            s.historySaveDenial=null;
            return true;
        }catch(AccessDeniedException denied){
            boolean firstDenial=s.historySaveDenial==null;
            s.historySaveDenial=denied;s.historyRetryAfter=System.nanoTime()+SAVE_RETRY_NANOS;
            if(required)throw new IllegalStateException("Cannot preserve WKE snow weather history",denied);
            if(firstDenial)STREAM_LOG.warn("WKE snow writes deferred after access to the weather manifest was denied",denied);
            return false;
        }catch(IOException ex){throw new IllegalStateException("Cannot preserve WKE snow weather history",ex);}
    }
    private static boolean surfaceRetryDeferred(State s,long key){
        if(s.surfaceSaveRetries.isEmpty())return false;
        var retry=s.surfaceSaveRetries.get(key);
        return retry!=null&&System.nanoTime()-retry.afterNanos()<0;
    }
    private static void retryWeatherPersistence(State s){
        if(s.historySaveDenial!=null&&!saveHistory(s,false))return;
        // Every retry key still owns a resident chunk. Start at most one chunk
        // write per tick and let deferred keys yield to the next ready key.
        long now=System.nanoTime();
        for(var entry:s.surfaceSaveRetries.entrySet()){
            if(now-entry.getValue().afterNanos()<0)continue;
            long key=entry.getKey();
            var surface=s.chunks.get(key);
            if(surface==null)s.surfaceSaveRetries.remove(key);
            else saveSurfaces(s,key,surface,s.extraLayers.get(key),false);
            return;
        }
        // Failed unloads keep their surfaces until they can be saved, or until
        // the host loads the same chunk again.
        for(long key:s.pendingUnloads){
            if(surfaceRetryDeferred(s,key))continue;
            if(s.level.getChunkSource().getChunkNow(chunkX(key),chunkZ(key))!=null)s.pendingUnloads.remove(key);
            else evict(s,key);
            return;
        }
    }
    private static boolean saveSurfaces(State s,long key,SnowSurface surface,Map<Integer,SnowSurface> extra,boolean required){
        if(hasDirtySurfaces(surface,extra)){
            if((!required&&surfaceRetryDeferred(s,key))||!saveHistory(s,required))return false;
            int x=chunkX(key),z=chunkZ(key);
            if(surface.dirty()&&!saveSurfaceFile(s,key,chunkPath(s.level,x,z),surface,required))return false;
            if(extra!=null)for(var layer:extra.entrySet()){
                if(layer.getValue().dirty()&&!saveSurfaceFile(s,key,layerPath(s.level,x,z,layer.getKey()),layer.getValue(),required))
                    return false;
            }
        }
        if(!s.surfaceSaveRetries.isEmpty()&&s.surfaceSaveRetries.remove(key)!=null)pruneEmptyLayers(s,key);
        return true;
    }
    /** The caller has persisted the current timeline before writing any surface cursor. */
    private static boolean saveSurfaceFile(State s,long key,Path path,SnowSurface surface,boolean required){
        try{
            SnowSurfaceStore.save(path,chunkX(key),chunkZ(key),surface);
            return true;
        }catch(AccessDeniedException denied){
            var previous=s.surfaceSaveRetries.put(key,new SurfaceSaveRetry(denied,System.nanoTime()+SAVE_RETRY_NANOS));
            if(required)throw new IllegalStateException("Cannot save WKE snow surface "+path,denied);
            if(previous==null)STREAM_LOG.warn("WKE snow surface write deferred after access was denied: {}",path,denied);
            return false;
        }catch(IOException ex){throw new IllegalStateException("Cannot save WKE snow surface "+path,ex);}
    }
    public static void saveAll(MinecraftServer server){saveAll(server,true);}
    private static void saveAll(MinecraftServer server,boolean required){
        State s=STATES.get(server);if(s==null)return;owner(s.level);
        if(!saveHistory(s,required))return;
        applySupportEdits(s,s.timeline.snapshot(),4096);
        SnowResidencyCycle.forEachCurrent(s.chunks,MAX_RESIDENT_CHUNKS,
                (chunkKey,primary)->saveSurfaces(s,chunkKey,primary,s.extraLayers.get(chunkKey),required));
    }
    /** Actual player/world edits start new supports at the current weather cursor, unlike first terrain discovery. */
    public static void blockChanged(Level level,BlockPos pos,BlockState before,BlockState after){
        if(before==after||!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return;
        State s=STATES.get(server.getServer());if(s==null||s.level!=server)return;
        boolean supportChanged=before.getBlock()!=after.getBlock()||!com.dark.winter.placement.AnchorRuntime.rawShape(server,pos,before,false).toAabbs()
                .equals(com.dark.winter.placement.AnchorRuntime.rawShape(server,pos,after,false).toAabbs());
        queueSupportEdit(s,pos,supportChanged&&newSupportTopAt(server,pos,List.of(pos)));
        queueSupportEdit(s,pos.above(),supportChanged&&newSupportTopAt(server,pos.above(),List.of(pos)));
    }
    public static void anchorChanged(ServerLevel level,BlockPos owner,List<BlockPos> members,Vec3 offset){
        State s=STATES.get(level.getServer());if(s==null||s.level!=level||!level.getServer().isSameThread())return;
        for(BlockPos member:members){
            var bounds=new net.minecraft.world.phys.AABB(member).move(offset).inflate(.001);
            for(int y=(int)Math.floor(bounds.minY);y<=(int)Math.floor(bounds.maxY)+1;y++)
                for(int z=(int)Math.floor(bounds.minZ);z<=(int)Math.floor(bounds.maxZ);z++)
                    for(int x=(int)Math.floor(bounds.minX);x<=(int)Math.floor(bounds.maxX);x++)
                        {BlockPos physical=new BlockPos(x,y,z);queueSupportEdit(s,physical,newSupportTopAt(level,physical,members));}
            queueSupportEdit(s,member,newSupportTopAt(level,member,members));
            queueSupportEdit(s,member.above(),newSupportTopAt(level,member.above(),members));
        }
    }
    private static boolean newSupportTopAt(ServerLevel level,BlockPos physical,List<BlockPos> changedOwners){
        if(!level.hasChunkAt(physical)||level.isOutsideBuildHeight(physical))return false;
        var cap=SnowHostGeometry.describe(level,physical);if(cap==null||!cap.generic())return false;
        for(BlockPos logical:changedOwners){
            if(!level.hasChunkAt(logical))continue;
            BlockState block=level.getBlockState(logical);
            if(block.getBlock() instanceof com.dark.winter.placement.item.PlacedItemBlock)continue;
            for(var box:block.getShape(level,logical,CollisionContext.empty()).toAabbs()){
                double top=logical.getY()+box.maxY-physical.getY();
                for(var patch:cap.patches())if(Math.abs(top-patch.top())<1e-5
                        &&logical.getX()+box.maxX>physical.getX()+patch.minX()&&logical.getX()+box.minX<physical.getX()+patch.maxX()
                        &&logical.getZ()+box.maxZ>physical.getZ()+patch.minZ()&&logical.getZ()+box.minZ<physical.getZ()+patch.maxZ())return true;
            }
        }
        return false;
    }
    private static void queueSupportEdit(State s,BlockPos pos,boolean rebase){
        if(s.level.isOutsideBuildHeight(pos)||!s.level.hasChunkAt(pos))return;
        if(s.supportEdits.size()>=4096&&!s.supportEdits.containsKey(pos.asLong()))applySupportEdits(s,s.timeline.snapshot(),128);
        s.supportEdits.put(pos.asLong(),rebase);
        Hydration h=s.hydration.get(key(pos.getX()>>4,pos.getZ()>>4));
        if(h!=null){int column=cell(pos.getX(),pos.getZ());h.visited.clear(column);h.geometry[column]=null;}
        s.refreshWork=true;
    }
    private static void applySupportEdits(State s,SnowWeatherTimeline.Snapshot snapshot,int limit){
        if(s.processingEdits||s.supportEdits.isEmpty())return;s.processingEdits=true;
        try{
            for(int i=0;i<limit&&!s.supportEdits.isEmpty();i++){
                var iterator=s.supportEdits.entrySet().iterator();var edit=iterator.next();
                BlockPos pos=BlockPos.of(edit.getKey());boolean rebase=edit.getValue();iterator.remove();
                if(!s.level.hasChunkAt(pos))continue;
                var geometry=SnowHostGeometry.describe(s.level,pos);
                if(geometry==null){
                    SnowSurface removed=surface(s,pos,false);
                    if(removed!=null){long k=key(pos.getX()>>4,pos.getZ()>>4);removed.forget(cell(pos.getX(),pos.getZ()));pruneEmptyLayers(s,k);refreshContacts(s,k);}
                    Map<Long,ExposureCache> cached=s.exposure.get(key(pos.getX()>>4,pos.getZ()>>4));if(cached!=null)cached.remove(pos.asLong());
                    continue;
                }
                SnowSurface layer=surface(s,pos,true);
                if(layer==null){
                    long key=key(pos.getX()>>4,pos.getZ()>>4);
                    if((s.historySaveDenial!=null||!s.surfaceSaveRetries.isEmpty())
                            &&s.chunks.size()>=MAX_RESIDENT_CHUNKS&&!s.chunks.containsKey(key)){
                        s.supportEdits.putIfAbsent(pos.asLong(),rebase);
                        break;
                    }
                    continue;
                }
                int column=cell(pos.getX(),pos.getZ());
                layer.observe(column,pos.getY(),geometry.layers(),s.level.getGameTime(),support(s,geometry));
                if(rebase){layer.resetAccumulationToBase(column);layer.rebaseWeatherTo(column,snapshot);}
                refreshContacts(s,key(pos.getX()>>4,pos.getZ()>>4));
            }
        }finally{s.processingEdits=false;}
    }
    public static Metrics metrics(MinecraftServer server){State s=STATES.get(server);return s==null?new Metrics(0,0,0):new Metrics(s.chunks.size(),s.processed,s.contacts);}
    private static SnowSurface.Weather weather(MinecraftServer server){
        var snow=WeatherServer.surfaceWeather(server);var air=WeatherServer.climate(server);
        return snow==null||air==null?null:new SnowSurface.Weather(snow.snowfall(),air.airTemperatureC(),air.windMetersPerSecond(),air.sourceBearingDegrees());
    }
    private static void tick(MinecraftServer server){
        ServerLevel level=server.overworld();if(!allowed(level))return;
        SnowSurface.Weather weather=weather(server);if(weather==null)return;
        State s=state(level);long now=level.getGameTime(),streamTick=++s.streamTicks;s.timeline.advance(now,weather);
        retryWeatherPersistence(s);
        var snapshot=s.timeline.snapshot();
        var players=server.getPlayerList().getPlayers().stream().filter(p->p.level()==level).limit(64).toList();
        // Weather advances for the active world, including territories not currently visited.
        if(players.isEmpty())return;
        for(ServerPlayer p:players){
            StreamPlayer view=s.streamPlayers.computeIfAbsent(p.getUUID(),ignored->new StreamPlayer());
            if(server.isSingleplayer()&&server.isSingleplayerOwner(p.nameAndId()))
                s.renderedTraces.computeIfAbsent(p.getUUID(),ignored->new RenderedTraceState()).observe(p,now);
            view.ahead.update(p.getX(),p.getZ());
            int cx=p.blockPosition().getX()>>4,cz=p.blockPosition().getZ()>>4;
            int focusX=view.ahead.projectedChunkX(),focusZ=view.ahead.projectedChunkZ();
            if(focusX!=view.focusX||focusZ!=view.focusZ){view.focusX=focusX;view.focusZ=focusZ;s.refreshWork=true;}
            if(cx!=view.x||cz!=view.z){
                view.x=cx;view.z=cz;s.refreshWork=true;
                if(!view.bootstrapped){
                    view.initialFrames=List.of();view.initialNext=0;view.pending=null;view.bootstrapped=false;
                    view.bootstrapSourceRevision=0;view.bootstrapInitialSent=false;view.bootstrapAwaitingAck=false;
                }
                announce(s,p,cx,cz);
            }
        }
        if(s.refreshWork||streamTick%10==0){s.work=work(s,players);s.refreshWork=false;}
        applySupportEdits(s,snapshot,32);
        hydrate(s,snapshot,weather,streamTick);
        for(ServerPlayer p:players)if(!p.isSpectator())walk(s,p);
        maintainWeather(s,snapshot,streamTick);
        if(streamTick%20==0){
            s.walkers.keySet().removeIf(id->players.stream().noneMatch(p->p.getUUID().equals(id)));
            s.streamPlayers.keySet().removeIf(id->players.stream().noneMatch(p->p.getUUID().equals(id)));
        }
        // Contacts and first-time fields need not wait for the one-second weather maintenance cadence.
        // send() has its own strict frame/time budget and suppresses unchanged presentations.
        for(ServerPlayer p:players)send(s,p,snapshot);
    }
    /** Historical integration remains lossless; only how much work runs on a tick is bounded. */
    private static void maintainWeather(State s,SnowWeatherTimeline.Snapshot snapshot,long tick){
        long started=System.nanoTime(),deadline=started+WEATHER_NANOS_PER_TICK;
        if(tick%20==0||s.weatherWork.isEmpty()){
            var resident=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SnowSurface,Boolean>());
            SnowResidencyCycle.forEachCurrent(s.chunks,MAX_RESIDENT_CHUNKS,(chunkKey,primary)->{
                for(SnowSurface surface:surfaces(s,chunkKey)){
                    resident.add(surface);
                    if(!s.weatherTasks.containsKey(surface)){
                        WeatherWork work=new WeatherWork(s,chunkKey,surface);
                        s.weatherTasks.put(surface,work);s.weatherWork.addLast(work);
                    }
                }
            });
            s.weatherTasks.keySet().removeIf(surface->!resident.contains(surface));
            s.weatherWork.removeIf(work->!resident.contains(work.surface));
        }
        int attempts=Math.min(WEATHER_BATCHES_PER_TICK,s.weatherWork.size()),batches=0,columns=0,contacts=0;
        for(int attempt=0;attempt<attempts&&(attempt==0||System.nanoTime()<deadline);attempt++){
            WeatherWork work=s.weatherWork.removeFirst();
            if(s.weatherTasks.get(work.surface)!=work)continue;
            if(s.level.getChunkSource().getChunkNow(chunkX(work.key),chunkZ(work.key))==null){
                s.weatherTasks.remove(work.surface);continue;
            }
            s.weatherWork.addLast(work);
            if(!work.active){
                if(tick<work.nextCycle)continue;
                work.active=true;work.column=0;work.contactsDone=false;work.visiblyChanged=false;
            }
            batches++;
            Map<Long,ExposureCache> cached=s.exposure.get(work.key);
            int stop=Math.min(256,work.column+WEATHER_COLUMNS_PER_BATCH);
            for(;work.column<stop;work.column++){
                columns++;
                if(!work.surface.observed(work.column)||cached==null)continue;
                int x=chunkX(work.key)*16+(work.column&15),z=chunkZ(work.key)*16+(work.column>>>4);
                int y=work.surface.sample(work.column).snowY();
                ExposureCache observed=cached.get(BlockPos.asLong(x,y,z));
                if(observed!=null){
                    Exposure e=observed.exposure();
                    work.surface.advance(work.column,snapshot,e.vertical(),e.directional);
                }
            }
            if(!work.contactsDone){
                SnowSurface.ContactAging aged=work.surface.ageContactsBudget(snapshot,work,WEATHER_CONTACTS_PER_BATCH);
                contacts+=aged.visited();work.contactsDone=aged.completed();
                work.visiblyChanged|=aged.visiblyChanged()>0||aged.removed()>0;
            }
            if(work.column==256&&work.contactsDone){
                work.active=false;work.nextCycle=tick+20;s.weatherCompleted++;
                if(work.visiblyChanged)s.weatherDirtyChunks.add(work.key);
            }
        }
        // One immutable collision/network view per completed sweep, never per aged footprint.
        for(int count=0;count<2&&!s.weatherDirtyChunks.isEmpty()&&(count==0||System.nanoTime()<deadline);count++){
            var iterator=s.weatherDirtyChunks.iterator();long key=iterator.next();iterator.remove();
            if(s.chunks.containsKey(key))refreshContacts(s,key,false);
        }
        long elapsed=System.nanoTime()-started;
        s.weatherBatches+=batches;s.weatherColumns+=columns;s.weatherContacts+=contacts;s.weatherNanos+=elapsed;
        s.weatherLastNanos=elapsed;s.weatherLastBatches=batches;s.weatherLastColumns=columns;s.weatherLastContacts=contacts;
    }
    public static WeatherMaintenanceMetrics weatherMaintenanceMetrics(MinecraftServer server){
        State s=STATES.get(server);
        return s==null?new WeatherMaintenanceMetrics(0,0,0,0,0,0,0,0,0,0,0)
                :new WeatherMaintenanceMetrics(s.weatherBatches,s.weatherColumns,s.weatherContacts,s.weatherCompleted,s.weatherNanos,
                    s.weatherLastNanos,s.weatherLastBatches,s.weatherLastColumns,s.weatherLastContacts,s.weatherTasks.size(),s.weatherDirtyChunks.size());
    }
    /** Only already-loaded chunks enter the queue; the outer one-cell border supplies exact shared corners. */
    private static List<Work> work(State s,List<ServerPlayer> players){
        var all=new HashMap<Long,Work>();
        for(var p:players){
            int cx=p.blockPosition().getX()>>4,cz=p.blockPosition().getZ()>>4;
            StreamPlayer view=s.streamPlayers.get(p.getUUID());
            if(view==null)continue;
            for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++){
                int x=cx+dx,z=cz+dz;
                if(s.level.getChunkSource().getChunkNow(x,z)==null)continue;
                Work w=all.computeIfAbsent(key(x,z),Work::new);
                w.priority=Math.min(w.priority,view.ahead.priority(x,z));
                for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                    int rx=dx*16+lx,rz=dz*16+lz,column=lx+16*lz;
                    if(rx>=-48-1&&rx<=64&&rz>=-48-1&&rz<=64)w.desired.set(column);
                    if(SnowStreamLookAhead.criticalColumn(x,z,lx,lz,cx,cz)
                            ||SnowStreamLookAhead.criticalColumn(x,z,lx,lz,view.focusX,view.focusZ))w.critical.set(column);
                }
            }
        }
        var ordered=new ArrayList<>(all.values());ordered.sort(Comparator.comparingDouble(w->w.priority));
        if(ordered.size()>MAX_RESIDENT_CHUNKS)ordered.subList(MAX_RESIDENT_CHUNKS,ordered.size()).clear();
        return List.copyOf(ordered);
    }
    private static int next(Hydration h,BitSet wanted,boolean fresh,long now,long age){
        for(int column=wanted.nextSetBit(0);column>=0;column=wanted.nextSetBit(column+1))
            if(fresh?!h.visited.get(column):h.visited.get(column)&&now-h.checked[column]>=age)return column;
        return -1;
    }
    /** Discovery outranks maintenance. Both a fixed column count and wall-time budget bound world observations. */
    private static void hydrate(State s,SnowWeatherTimeline.Snapshot snapshot,SnowSurface.Weather weather,long now){
        long started=System.nanoTime();int count=0;
        for(int stage=0;stage<4;stage++)for(Work w:s.work){
            if(count>=COLUMNS_PER_TICK||count>0&&System.nanoTime()-started>=HYDRATION_BUDGET_NANOS)return;
            // Finish discovery throughout the bounded prefetched region before stale-field maintenance.
            boolean critical=stage==0||stage==2,fresh=stage<2;
            BitSet wanted=critical?w.critical:w.desired;if(wanted.isEmpty())continue;
            Hydration h=s.hydration.get(w.key);
            if(h!=null&&next(h,wanted,fresh,now,critical?100:600)<0||h==null&&!fresh)continue;
            if(s.level.getChunkSource().getChunkNow(chunkX(w.key),chunkZ(w.key))==null)continue;
            SnowSurface surface=resident(s,chunkX(w.key),chunkZ(w.key));if(surface==null)continue;
            h=s.hydration.computeIfAbsent(w.key,ignored->new Hydration());
            for(int column;(column=next(h,wanted,fresh,now,critical?100:600))>=0;){
                int x=chunkX(w.key)*16+(column&15),z=chunkZ(w.key)*16+(column>>>4);
                List<SnowHostGeometry.Cell> geometries=updateColumn(s,surface,column,x,z,snapshot);
                h.geometry[column]=geometries;h.visited.set(column);h.checked[column]=now;
                for(var g:geometries){
                    SnowSurface layer=surface(s,g.pos(),false);if(layer!=null)redistribute(s,layer,column,g,snapshot,weather);
                }
                s.processed++;count++;
                if(count>=COLUMNS_PER_TICK||System.nanoTime()-started>=HYDRATION_BUDGET_NANOS)return;
            }
        }
    }
    private static boolean visited(State s,int x,int z){
        long k=key(x>>4,z>>4);Hydration h=s.hydration.get(k);
        return h!=null&&h.visited.get(cell(x,z))&&s.level.getChunkSource().getChunkNow(x>>4,z>>4)!=null;
    }
    private static boolean fieldReady(State s,int cx,int cz){
        Hydration centre=s.hydration.get(key(cx,cz));
        if(centre==null||centre.visited.nextClearBit(0)<256||s.level.getChunkSource().getChunkNow(cx,cz)==null)return false;
        // The fully visited centre is already known; only its finite border still needs observation.
        for(int z=cz*16-1;z<=cz*16+16;z++)for(int x=cx*16-1;x<=cx*16+16;x++)
            if(((x>>4)!=cx||(z>>4)!=cz)&&!visited(s,x,z))return false;
        return true;
    }
    private static boolean nearReady(State s,int cx,int cz){
        // One complete3x3 and its border, not a wait for every distant render-distance chunk.
        for(int z=(cz-1)*16-1;z<=(cz+2)*16;z++)for(int x=(cx-1)*16-1;x<=(cx+2)*16;x++)if(!visited(s,x,z))return false;
        return true;
    }
    public static boolean chunkReady(ServerLevel level,int x,int z){
        owner(level);State s=STATES.get(level.getServer());return s!=null&&s.level==level&&fieldReady(s,x,z);
    }
    private static List<SnowHostGeometry.Cell> locate(State s,int x,int z){
        int top=s.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+1;
        var result=new ArrayList<SnowHostGeometry.Cell>(2);
        for(int y=top;y>=Math.max(s.level.getMinY(),top-40)&&result.size()<MAX_SURFACES_PER_COLUMN;y--){
            BlockPos pos=new BlockPos(x,y,z);BlockState block=s.level.getBlockState(pos);
            if(block.isAir()&&s.level.getBlockState(pos.below()).isAir()
                    &&com.dark.winter.placement.AnchorRuntime.logicalOwners(s.level,pos).isEmpty()
                    &&com.dark.winter.placement.AnchorRuntime.logicalOwners(s.level,pos.below()).isEmpty())continue;
            var geometry=SnowHostGeometry.describe(s.level,pos);if(geometry!=null)result.add(geometry);
        }
        return List.copyOf(result);
    }
    private static List<SnowHostGeometry.Cell> updateColumn(State s,SnowSurface primary,int column,int x,int z,SnowWeatherTimeline.Snapshot snapshot){
        var geometries=locate(s,x,z);var accepted=new ArrayList<SnowHostGeometry.Cell>(geometries.size());
        long k=key(x>>4,z>>4);boolean removed=false;
        for(var old:surfaces(s,k))if(old.observed(column)){
            int oldY=old.sample(column).snowY();boolean present=false;
            for(var geometry:geometries)if(geometry.pos().getY()==oldY){present=true;break;}
            if(!present){old.forget(column);removed=true;Map<Long,ExposureCache> cached=s.exposure.get(k);
                if(cached!=null)cached.remove(new BlockPos(x,oldY,z).asLong());}
        }
        if(removed){pruneEmptyLayers(s,k);refreshContacts(s,k);}
        for(var geometry:geometries){
            SnowSurface layer=surface(s,geometry.pos(),true);if(layer==null)continue;
            layer.observe(column,geometry.pos().getY(),geometry.layers(),s.level.getGameTime(),support(s,geometry));
            Exposure e=exposure(s,geometry);layer.advance(column,snapshot,e.vertical(),e.directional);accepted.add(geometry);
        }
        return List.copyOf(accepted);
    }
    private static Exposure exposure(State s,SnowHostGeometry.Cell geometry){
        long k=key(geometry.pos().getX()>>4,geometry.pos().getZ()>>4);
        Map<Long,ExposureCache> columns=s.exposure.computeIfAbsent(k,ignored->new HashMap<>());
        ExposureCache old=columns.get(geometry.pos().asLong());long now=s.level.getGameTime();
        if(old!=null&&now-old.tick()<100&&old.layers()==geometry.layers())return old.exposure();
        Exposure fresh=exposure(s.level,geometry);columns.put(geometry.pos().asLong(),new ExposureCache(now,geometry.pos().getY(),geometry.layers(),fresh));return fresh;
    }
    private static SnowSurface.Support support(State s,SnowHostGeometry.Cell geometry){
        BlockPos p=geometry.pos();BlockState block=s.level.getBlockState(p);
        if(block.isAir()||block.getBlock() instanceof com.dark.winter.placement.item.PlacedItemBlock
                ||SnowHostGeometry.nativeLayers(block)>0||SnowHostGeometry.groundPlant(block))block=s.level.getBlockState(p.below());
        String id=BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
        SnowSurface.Material material=SnowHostGeometry.soil(block)||SnowHostGeometry.snowySlab(block)?SnowSurface.Material.SOIL
                :id.contains("metal")||id.contains("iron")||id.contains("copper")?SnowSurface.Material.METAL
                :id.contains("concrete")||id.contains("asphalt")?SnowSurface.Material.CONCRETE
                :block.is(BlockTags.PLANKS)||block.is(BlockTags.LOGS)||id.contains("wood")||id.contains("floor_segment")?SnowSurface.Material.WOOD
                :id.contains("stone")||id.contains("rock")||id.contains("boulder")?SnowSurface.Material.ROCK
                :block.getBlock() instanceof com.dark.winter.block.FurnitureBlock?SnowSurface.Material.FURNITURE:SnowSurface.Material.ROCK;
        double variation=(Math.sin((p.getX()+31)*.031)+Math.cos((p.getZ()-47)*.027)+2)*.25;int higher=0;
        for(Direction d:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}){
            BlockPos n=p.relative(d);if(!s.level.hasChunkAt(n))continue;
            int height=s.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,n.getX(),n.getZ());
            if(height>geometry.nominalHeight()+.35&&height<geometry.nominalHeight()+3)higher++;
        }
        Exposure shelter=exposure(s,geometry);float least=1;for(float value:shelter.directional)least=Math.min(least,value);
        boolean trap=material==SnowSurface.Material.SOIL&&(higher>=2||higher>=1&&least<.55||shelter.vertical()>.1&&shelter.vertical()<.85&&least<.35);
        return new SnowSurface.Support(material,(float)Math.clamp(variation,0,1),trap);
    }
    /** Bounded shape rays: opaque roofs stop snow; foliage and sparse furniture transmit a fraction. */
    public static Exposure exposure(ServerLevel level,SnowHostGeometry.Cell geometry){
        owner(level);double x=geometry.pos().getX()+.5,z=geometry.pos().getZ()+.5,y=geometry.nominalHeight();
        float vertical=0;boolean thinCavity=false;double[] offsets={-.25,0,.25};
        int samples=geometry.generic()?Math.min(geometry.patches().size(),4):offsets.length;
        for(int i=0;i<samples;i++){
            double px=x,pz=z,py=y;
            if(geometry.generic()){
                var patch=geometry.patches().get(i);px=geometry.pos().getX()+(patch.minX()+patch.maxX())*.5;
                pz=geometry.pos().getZ()+(patch.minZ()+patch.maxZ())*.5;py=geometry.pos().getY()+patch.top();
                if(i==0){x=px;z=pz;y=py;}
            }else{px+=offsets[i];pz-=offsets[i];py=SnowHostGeometry.sample(geometry,px,pz,0).height();}
            if(geometry.generic()&&ray(level,px,py+.004,pz,0,1,0,.2)==0)thinCavity=true;
            int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,(int)Math.floor(px),(int)Math.floor(pz));
            int distance=(int)Math.max(1,Math.ceil(top-py+1));
            vertical+=ray(level,px,py+.004,pz,0,1,0,distance)/samples;
        }
        float[] directional=new float[8];
        for(int direction=0;direction<8;direction++){
            double angle=Math.toRadians(direction*45);
            directional[direction]=thinCavity?0:ray(level,x,y+.008,z,Math.sin(angle),.32,-Math.cos(angle),16);
        }
        return new Exposure(Math.clamp(vertical,0,1),directional);
    }
    private static float ray(ServerLevel level,double x,double y,double z,double dx,double dy,double dz,double maxDistance){
        float transmission=1;
        int vx=(int)Math.floor(x),vy=(int)Math.floor(y),vz=(int)Math.floor(z);
        int sx=(int)Math.signum(dx),sy=(int)Math.signum(dy),sz=(int)Math.signum(dz);
        double tx=dx==0?Double.POSITIVE_INFINITY:((dx>0?vx+1:vx)-x)/dx;
        double ty=dy==0?Double.POSITIVE_INFINITY:((dy>0?vy+1:vy)-y)/dy;
        double tz=dz==0?Double.POSITIVE_INFINITY:((dz>0?vz+1:vz)-z)/dz;
        double stepX=dx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx);
        double stepY=dy==0?Double.POSITIVE_INFINITY:Math.abs(1/dy);
        double stepZ=dz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        double entry=0;
        // Exact voxel traversal avoids missing a thin slab/roof between arbitrary sample points.
        for(int visited=0,limit=(int)Math.min(4096,Math.max(96,Math.ceil(maxDistance*4+4)));visited<limit&&entry<=maxDistance;visited++){
            BlockPos pos=new BlockPos(vx,vy,vz);
            if(level.isOutsideBuildHeight(pos))break;
            if(!level.hasChunkAt(pos))return 0;
            var shifted=com.dark.winter.placement.AnchorRuntime.logicalOwners(level,pos);
            for(int ownerIndex=-1;ownerIndex<shifted.size();ownerIndex++){
                BlockPos logical=ownerIndex<0?pos:shifted.get(ownerIndex);
                if(ownerIndex>=0&&logical.equals(pos))continue;
                if(!level.hasChunkAt(logical))return 0;
                BlockState block=level.getBlockState(logical);
                if(block.isAir()||block.getBlock() instanceof com.dark.winter.placement.item.PlacedItemBlock)continue;
                boolean foliage=block.is(TestSpruceBlocks.BOUGH)||block.is(BlockTags.LEAVES);
                String path=BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
                boolean permeable=foliage||path.contains("shrub")||path.contains("fern")||path.contains("grass_tuft");
                boolean hit=false;
                for(var local:block.getShape(level,logical,CollisionContext.empty()).toAabbs()){
                    var box=local.move(logical.getX()-vx,logical.getY()-vy,logical.getZ()-vz);
                    if(rayBox(x-vx,y-vy,z-vz,dx,dy,dz,maxDistance,box)){hit=true;break;}
                }
                if(hit){
                    if(!permeable)return 0;
                    transmission*=foliage?.55f:.65f;if(transmission<.015)return 0;
                }
            }
            double next=Math.min(tx,Math.min(ty,tz));if(!Double.isFinite(next))break;
            if(tx==next){vx+=sx;tx+=stepX;}
            if(ty==next){vy+=sy;ty+=stepY;}
            if(tz==next){vz+=sz;tz+=stepZ;}
            entry=next;
        }
        return transmission;
    }
    private static boolean rayBox(double x,double y,double z,double dx,double dy,double dz,double max,net.minecraft.world.phys.AABB box){
        double enter=0,exit=max;
        if(dx==0){if(x<box.minX||x>box.maxX)return false;}
        else {double a=(box.minX-x)/dx,b=(box.maxX-x)/dx;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return false;}
        if(dy==0){if(y<box.minY||y>box.maxY)return false;}
        else {double a=(box.minY-y)/dy,b=(box.maxY-y)/dy;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return false;}
        if(dz==0){if(z<box.minZ||z>box.maxZ)return false;}
        else {double a=(box.minZ-z)/dz,b=(box.maxZ-z)/dz;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return false;}
        return exit>=0&&enter<=max;
    }
    private static void redistribute(State s,SnowSurface from,int source,SnowHostGeometry.Cell geometry,SnowWeatherTimeline.Snapshot snapshot,SnowSurface.Weather weather){
        long sourceChunk=key(geometry.pos().getX()>>4,geometry.pos().getZ()>>4);
        Map<Long,Long> clocks=s.windAt.computeIfAbsent(sourceChunk,ignored->new HashMap<>());
        Long previous=clocks.put(geometry.pos().asLong(),s.level.getGameTime());
        if(previous==null||weather.windMetersPerSecond()<=3)return;
        double seconds=Math.clamp((s.level.getGameTime()-previous)/20.0,0,10);
        int sector=SnowWeatherTimeline.direction(weather.windFromDegrees());double angle=Math.toRadians(sector*45);
        int dx=-(int)Math.round(Math.sin(angle)),dz=(int)Math.round(Math.cos(angle));
        BlockPos to=geometry.pos().offset(dx,0,dz);
        if(s.level.getChunkSource().getChunkNow(to.getX()>>4,to.getZ()>>4)==null)return;
        SnowHostGeometry.Cell receiver=SnowHostGeometry.find(s.level,to.getX()+.5,geometry.nominalHeight(),to.getZ()+.5);if(receiver==null)return;
        SnowSurface target=surface(s,receiver.pos(),false);int cell=cell(to.getX(),to.getZ());
        if(target==null||!target.observed(cell)||target.sample(cell).snowY()!=receiver.pos().getY()||target==from&&source==cell)return;
        if(Math.abs(receiver.nominalHeight()-geometry.nominalHeight())>1.125)return;
        Exposure a=exposure(s,geometry),b=exposure(s,receiver);
        target.advance(cell,snapshot,b.vertical(),b.directional);
        float amount=(float)(Math.max(0,weather.windMetersPerSecond()-3)*.005*seconds*a.directional[sector]*(.25+.75*(1-b.vertical())));
        from.transferAdditionalTo(source,target,cell,amount);
    }
    public static SnowSurface.Sample observe(ServerLevel level,BlockPos pos){
        owner(level);if(!allowed(level)||!level.hasChunkAt(pos))return null;
        SnowSurface.Weather weather=weather(level.getServer());if(weather==null)return null;
        State s=state(level);s.timeline.advance(level.getGameTime(),weather);
        var g=SnowHostGeometry.describe(level,pos);if(g==null)return null;
        SnowSurface surface=surface(s,pos,true);if(surface==null)return null;
        surface.observe(cell(pos.getX(),pos.getZ()),g.pos().getY(),g.layers(),level.getGameTime(),support(s,g));
        Exposure e=exposure(s,g);surface.advance(cell(pos.getX(),pos.getZ()),s.timeline.snapshot(),e.vertical(),e.directional);
        Hydration h=s.hydration.computeIfAbsent(key(pos.getX()>>4,pos.getZ()>>4),ignored->new Hydration());
        int column=cell(pos.getX(),pos.getZ());List<SnowHostGeometry.Cell> known=h.geometry[column];var owners=new ArrayList<SnowHostGeometry.Cell>(known==null?List.of():known);
        owners.removeIf(c->c.pos().equals(g.pos()));if(owners.size()<MAX_SURFACES_PER_COLUMN)owners.add(g);h.geometry[column]=List.copyOf(owners);
        h.checked[column]=s.streamTicks;
        return surface.sample(column);
    }
    public static List<SnowSurface.Contact> contacts(ServerLevel level,int x,int z){
        owner(level);State s=STATES.get(level.getServer());return s==null?List.of():s.contactViews.getOrDefault(key(x,z),List.of());
    }
    public static SnowSurface.Sample sample(ServerLevel level,BlockPos pos){
        owner(level);State s=STATES.get(level.getServer());if(s==null||s.level!=level||!level.hasChunkAt(pos))return null;
        SnowSurface surface=surface(s,pos,false);int column=cell(pos.getX(),pos.getZ());
        return surface==null||!surface.observed(column)||surface.sample(column).snowY()!=pos.getY()?null:surface.sample(column);
    }
    public static double fineDeltaMillimeters(Level level,BlockPos pos){
        double value=knownFineDeltaMillimeters(level,pos);return Double.isFinite(value)?value:0;
    }
    public static double knownFineDeltaMillimeters(Level level,BlockPos pos){
        if(level.isClientSide())return clientView.deltaMillimeters(pos);
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return Double.NaN;
        State s=STATES.get(server.getServer());if(s==null||s.level!=level)return Double.NaN;
        SnowSurface surface=surface(s,pos,false);int column=cell(pos.getX(),pos.getZ());
        return surface==null||!surface.observed(column)||surface.sample(column).snowY()!=pos.getY()?Double.NaN:surface.fineDeltaMillimeters(column);
    }
    /** Keep an owned client's shape frozen with its visible cap while a replacement mesh is pending. */
    public static SnowHostGeometry.Cell geometry(Level level,BlockPos owner){
        if(level.isClientSide()){
            SnowHostGeometry.Cell published=clientView.geometry(owner);
            if(published!=null)return published;
        }
        return SnowHostGeometry.describe(level,owner);
    }
    /** Published client geometry retains priority; fresh soil fallback needs only its shoulder mask. */
    public static SnowHostGeometry.Cell geometryForSoilCollision(Level level,BlockPos owner){
        if(level.isClientSide()){
            SnowHostGeometry.Cell published=clientView.geometry(owner);
            if(published!=null)return published;
        }
        return SnowHostGeometry.describeForSoilCollision(level,owner);
    }
    /** A removed owner can still have a visible cap until the worker commits its replacement. */
    public static SnowHostGeometry.Cell findGeometry(Level level,double x,double estimatedY,double z){
        if(!Double.isFinite(x)||!Double.isFinite(estimatedY)||!Double.isFinite(z))return null;
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z),y=(int)Math.floor(estimatedY+.1);
        SnowHostGeometry.Cell nearest=null;double distance=Double.POSITIVE_INFINITY;
        // A published client owner remains authoritative until its replacement mesh is committed.
        for(int pass=0;pass<(level.isClientSide()?2:1);pass++){
            for(int dy=2;dy>=-3;dy--){
                BlockPos owner=new BlockPos(bx,y+dy,bz);if(!level.hasChunkAt(owner))continue;
                SnowHostGeometry.Cell geometry=level.isClientSide()&&pass==0?clientView.geometry(owner):SnowHostGeometry.describe(level,owner);
                if(geometry==null||!geometry.contains(x,z))continue;
                double[] corners=fineCornersMillimeters(level,geometry);
                double top=SnowHostGeometry.sample(geometry,x,z,corners).height(),bottom=SnowHostGeometry.snowBottomHeight(geometry,x,z);
                if(!Double.isFinite(top+bottom))continue;
                double relieved=SnowHostGeometry.clampCompactedHeight(geometry,x,z,top,depressionMillimeters(level,geometry,corners,x,z));
                double sink=SnowTravelPolicy.sinkMillimeters(Math.max(0,(top-bottom)*1000),geometry.layers()*125.0)/1000;
                double physical=Math.max(bottom,Math.min(relieved,top-sink));
                double d=Math.abs(physical-estimatedY);
                if(d<distance&&d<=1.75){nearest=geometry;distance=d;}
            }
            if(nearest!=null)return nearest;
        }
        return null;
    }
    /** Client collision uses the actual published mesh revision; the server observes its authoritative field. */
    public static double[] fineCornersMillimeters(Level level,SnowHostGeometry.Cell geometry){
        if(level.isClientSide()){
            double[] published=clientView.fineCornersMillimeters(geometry.pos());
            if(published!=null){
                if(published.length!=4)throw new IllegalStateException("Published snow corner count");
                for(double value:published)if(!Double.isFinite(value))throw new IllegalStateException("Published snow corner height");
                return published;
            }
        }
        return SnowHostGeometry.fineCornersMillimeters(level,geometry,p->knownFineDeltaMillimeters(level,p));
    }
    /** Immutable contact revisions for the same bounded neighbourhood sampled by the physical relief. */
    public static Object contactSnapshotKey(Level level,BlockPos owner){
        if(level.isClientSide())return clientView.contactSnapshotKey(owner);
        if(!(level instanceof ServerLevel server))return List.of();
        int x=owner.getX()>>4,z=owner.getZ()>>4;
        return List.of(contacts(server,x-1,z-1),contacts(server,x,z-1),contacts(server,x+1,z-1),
                contacts(server,x-1,z),contacts(server,x,z),contacts(server,x+1,z),
                contacts(server,x-1,z+1),contacts(server,x,z+1),contacts(server,x+1,z+1));
    }
    /** Compatibility query uses the one observed walking surface at this X/Z, never a global 2D mask. */
    public static double depressionMillimeters(Level level,double x,double z){
        if(level.isClientSide())return clientView.depressionMillimeters(x,z);
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return 0;
        State s=STATES.get(server.getServer());if(s==null)return 0;
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z),column=cell(bx,bz);
        SnowSurface surface=s.chunks.get(key(bx>>4,bz>>4));
        if(surface==null||!surface.observed(column))return 0;
        return depressionMillimeters(level,new BlockPos(bx,surface.sample(column).snowY(),bz),x,z);
    }
    /** A contact belongs to the queried cap's height, including a footprint crossing its horizontal edge. */
    public static double depressionMillimeters(Level level,BlockPos owner,double x,double z){
        if(level.isClientSide())return clientView.depressionMillimeters(owner,x,z);
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return 0;
        SnowHostGeometry.Cell geometry=SnowHostGeometry.describe(level,owner);
        return geometry==null?0:depressionMillimeters(level,geometry,x,z);
    }
    public static double depressionMillimeters(Level level,SnowHostGeometry.Cell geometry,double x,double z){
        return depressionMillimeters(level,geometry,fineCornersMillimeters(level,geometry),x,z);
    }
    public static double depressionMillimeters(Level level,SnowHostGeometry.Cell geometry,double[] corners,double x,double z){
        BlockPos owner=geometry.pos();
        if(level.isClientSide())return clientView.depressionMillimeters(owner,x,z);
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return 0;
        State s=STATES.get(server.getServer());if(s==null)return 0;
        s.collisionQueries++;s.collisionMatches.clear();var field=s.collisionField;field.reset();
        int cx=owner.getX()>>4,cz=owner.getZ()>>4;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            CollisionBucket candidates=collisionCandidates(s,cx+dx,cz+dz,x,z);if(candidates==null)continue;
            for(int i=0;i<candidates.size;i++){
                s.collisionCandidates++;var entry=candidates.entries[i];var c=entry.contact();var k=entry.kernel();
                if(!SnowHostGeometry.contactMatches(geometry,corners,k.x(),c.y(),k.z(),c.width(),c.length(),c.ownerY()))continue;
                s.collisionOwnerMatches++;
                if(!field.fullyCovered())s.collisionMatches.add(entry);
                // A shallower core can still increase normalized coverage until some core reaches C=1.
                if(k.depth()<=0||field.fullyCovered()&&k.depth()<=field.depressionMillimeters())continue;
                s.collisionDepressionEvaluations++;
                field.addDepression(collisionPrepared(s,k),x,z);
            }
        }
        if(!field.fullyCovered())for(var entry:s.collisionMatches){
            var prepared=collisionPrepared(s,entry.kernel());
            if(prepared.maxRimMillimeters()<=field.rimMillimeters())continue;
            s.collisionRimEvaluations++;field.addRim(prepared,x,z);
        }
        double signed=field.signedMillimeters();s.collisionMatches.clear();return signed;
    }
    private static CollisionBucket collisionCandidates(State s,int cx,int cz,double x,double z){
        long key=key(cx,cz);List<SnowSurface.Contact> view=s.contactViews.get(key);
        if(view==null||view.isEmpty()){s.collisionIndices.remove(key);return null;}
        CollisionIndex index=s.collisionIndices.get(key);
        if(index==null||index.source!=view){
            index=new CollisionIndex(view,cx,cz);s.collisionIndices.put(key,index);
            s.collisionIndexBuilds++;s.collisionIndexedContacts+=view.size();
        }
        return index.near(x,z);
    }
    private static SnowContactRelief.Prepared collisionPrepared(State s,CollisionKernel key){
        var prepared=s.collisionPrepared.get(key);
        if(prepared!=null){s.collisionCacheHits++;return prepared;}
        s.collisionCacheMisses++;
        prepared=SnowContactRelief.prepare(key.x(),key.z(),key.yaw(),key.width(),key.length(),key.depth(),key.vehicle(),key.profile());
        s.collisionPrepared.put(key,prepared);
        if(s.collisionPrepared.size()>COLLISION_PREPARED_CACHE){
            s.collisionPrepared.remove(s.collisionPrepared.keySet().iterator().next());s.collisionCacheEvictions++;
        }
        return prepared;
    }
    /** Independent stateless full scan of the continuous field; it does not reuse the accumulator. */
    private static double referenceCollisionPressure(State s,SnowHostGeometry.Cell geometry,double[] corners,double x,double z){
        BlockPos owner=geometry.pos();
        double depression=0,rim=0,coverage=0;
        int cx=owner.getX()>>4,cz=owner.getZ()>>4;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(var c:s.contactViews.getOrDefault(key(cx+dx,cz+dz),List.of())){
            double wx=(cx+dx)*16+c.x(),wz=(cz+dz)*16+c.z(),depth=c.depressionMillimeters();
            if(!SnowHostGeometry.contactMatches(geometry,corners,wx,c.y(),wz,c.width(),c.length(),c.ownerY()))continue;
            double value=SnowContactRelief.depressionMillimeters(x,z,wx,wz,c.yaw(),c.width(),c.length(),depth,c.vehicle(),c.soleProfile());
            depression=Math.max(depression,value);
            if(depth>0)coverage=Math.max(coverage,Math.clamp(value/depth,0,1));
            rim=Math.max(rim,SnowContactRelief.rimMillimeters(x,z,wx,wz,c.yaw(),c.width(),c.length(),depth,c.vehicle(),c.soleProfile()));
        }
        return coverage>=1?depression:depression==0?-rim:depression-rim*(1-coverage);
    }
    public static CollisionPressureMetrics collisionPressureMetrics(ServerLevel level){
        owner(level);State s=STATES.get(level.getServer());
        return s==null?new CollisionPressureMetrics(0,0,0,0,0,0,0,0,0,0,0,0):
                new CollisionPressureMetrics(s.collisionQueries,s.collisionIndexBuilds,s.collisionIndexedContacts,s.collisionCacheHits,
                        s.collisionCacheMisses,s.collisionCacheEvictions,s.collisionCandidates,s.collisionOwnerMatches,
                        s.collisionDepressionEvaluations,s.collisionRimEvaluations,s.collisionPrepared.size(),s.collisionIndices.size());
    }
    /** Evaluate both real server paths against the same owner, corners and immutable contact views. */
    public static CollisionPressureParity collisionPressureParity(ServerLevel level,BlockPos owner,double x,double z){
        owner(level);var geometry=SnowHostGeometry.describe(level,owner);
        State s=STATES.get(level.getServer());
        if(geometry==null||s==null)return new CollisionPressureParity(Double.NaN,Double.NaN);
        double[] corners=fineCornersMillimeters(level,geometry);
        return new CollisionPressureParity(depressionMillimeters(level,geometry,corners,x,z),referenceCollisionPressure(s,geometry,corners,x,z));
    }
    public static double surfaceHeight(Level level,double x,double estimatedY,double z){
        var g=findGeometry(level,x,estimatedY,z);if(g==null)return Double.NaN;
        return surfaceHeight(level,g,x,z);
    }
    private static double surfaceHeight(Level level,SnowHostGeometry.Cell g,double x,double z){
        var corners=fineCornersMillimeters(level,g);
        return SnowHostGeometry.compactedHeight(g,x,z,corners,depressionMillimeters(level,g,corners,x,z));
    }
    public static Vec3 travelInput(Player player,Vec3 input){
        if(!Level.OVERWORLD.equals(player.level().dimension())||!player.isAlive()||player.isSpectator()||player.isCreative()
                ||player.getAbilities().flying||player.isFallFlying()||player.isPassenger()||!player.onGround()
                ||player.isInLiquid()||player.onClimbable()||player.noPhysics)return input;
        double depth=SnowLocomotion.depthMillimeters(player);
        if(!Double.isFinite(depth)||depth<=0)return input;
        double scale=SnowTravelPolicy.movementMultiplier(depth);
        return new Vec3(input.x*scale,input.y,input.z*scale);
    }
    private static boolean finiteRenderedTrace(RenderedFootTrace trace,SoleProfiles.Profile sole,double scale){
        if(trace==null||!Double.isFinite(trace.x())||!Double.isFinite(trace.soleY())||!Double.isFinite(trace.z())
                ||!Float.isFinite(trace.yaw())||!Float.isFinite(trace.width())||!Float.isFinite(trace.length())
                ||Math.abs(trace.x())>30_000_000||Math.abs(trace.z())>30_000_000||trace.soleY()< -2048||trace.soleY()>2048
                ||Math.abs(trace.yaw())>360||trace.width()<.04f||trace.length()<.08f)return false;
        // Fixed traces use the native sole size and one constant deep-snow segment.
        // Match the producer's float sole length before adding its segment span.
        float maxWidth=Math.nextUp((float)sole.worldWidth(scale));
        float maxLength=Math.nextUp((float)((float)sole.worldLength(scale)+(trace.channel()
                ?com.winterkore.engine.surface.geometry.FixedSnowTrack.SEGMENT_METERS:0)));
        return trace.width()<=maxWidth&&trace.width()<=2&&trace.length()<=maxLength&&trace.length()<=4;
    }
    private static boolean renderedNearBody(RenderedFootTrace trace,BodySample a,BodySample b,SoleProfiles.Profile sole){
        if(!a.grounded()||!b.grounded())return false;
        double dx=b.x()-a.x(),dz=b.z()-a.z(),square=dx*dx+dz*dz;
        double t=square==0?0:Math.clamp(((trace.x()-a.x())*dx+(trace.z()-a.z())*dz)/square,0,1);
        double scale=Math.max(a.scale(),b.scale()),unit=SoleProfiles.AVATAR_SCALE*scale/16;
        // Full leg length, hip/pelvis/sole offsets and crouch bound every articulated centre.
        // A scuff is the midpoint of a <=.75 m real-foot segment. The extra quarter metre
        // covers the local body's one-packet interpolation lead without accepting a remote stamp.
        double reach=(SoleProfiles.LEG_LENGTH+SoleProfiles.LEG_PIVOT_X+Math.abs(sole.outboard()*SoleProfiles.LEG_SCALE_X)
                +Math.abs(sole.centreZ()*SoleProfiles.LEG_SCALE_Z)+.48+.78)*unit+.25+(trace.channel()?.375:0);
        double x=trace.x()-(a.x()+dx*t),z=trace.z()-(a.z()+dz*t);
        return x*x+z*z<=reach*reach&&trace.soleY()>=Math.min(a.y(),b.y())-scale
                &&trace.soleY()<=Math.max(a.y(),b.y())+2*scale;
    }
    private static boolean renderedNearMotion(RenderedTraceState state,RenderedFootTrace trace,SoleProfiles.Profile sole){
        BodySample previous=null;
        for(var sample:state.body){
            if(renderedNearBody(trace,sample,sample,sole)||previous!=null&&renderedNearBody(trace,previous,sample,sole))return true;
            previous=sample;
        }
        return false;
    }
    /** JSON-safe values even when bounds validation rejects a nonfinite trace. */
    private static Object renderedDiagnosticNumber(double value){return Double.isFinite(value)?value:Double.toString(value);}
    private static void recordRenderedRejection(RenderedTraceState receiver,long sequence,long tick,ServerPlayer player,
            RenderedFootTrace trace,int profile,String reason,RenderedStampFailure failure){
        Map<String,Object> input=trace==null?Map.of("nullTrace",true):Map.of(
                "x",renderedDiagnosticNumber(trace.x()),"soleY",renderedDiagnosticNumber(trace.soleY()),
                "z",renderedDiagnosticNumber(trace.z()),"yaw",renderedDiagnosticNumber(trace.yaw()),
                "width",renderedDiagnosticNumber(trace.width()),"length",renderedDiagnosticNumber(trace.length()),
                "channel",trace.channel(),"serverSoleProfile",profile);
        if(trace!=null&&trace.support()!=null){
            var captured=trace.support();var annotated=new LinkedHashMap<String,Object>(input);
            var p=captured.geometry.pos();
            annotated.put("capturedSupport",Map.of("owner",List.of(p.getX(),p.getY(),p.getZ()),
                    "skinY",captured.skinY(),"physicalDepthMillimeters",captured.pressure.physicalColumnDepthMm(),
                    "baseDepthMillimeters",captured.pressure.authoredBaseMm(),
                    "fineCornersMillimeters",List.of(captured.corners[0],captured.corners[1],captured.corners[2],captured.corners[3])));
            input=Map.copyOf(annotated);
        }
        var body=new ArrayList<Map<String,Object>>(receiver.body.size());
        for(var sample:receiver.body)body.add(Map.of("tick",sample.tick(),"x",sample.x(),"y",sample.y(),"z",sample.z(),
                "scale",sample.scale(),"grounded",sample.grounded()));
        var position=Map.<String,Object>of("entityId",player.getId(),"x",renderedDiagnosticNumber(player.getX()),
                "y",renderedDiagnosticNumber(player.getY()),"z",renderedDiagnosticNumber(player.getZ()),
                "scale",renderedDiagnosticNumber(player.getScale()),"onGround",player.onGround(),
                "bodyHistoryTicks",RENDER_BODY_HISTORY_TICKS);
        Map<String,Object> support=Map.of();
        if(failure!=null){
            var measured=new LinkedHashMap<String,Object>();var geometry=failure.geometry();
            if(geometry!=null){var p=geometry.pos();measured.put("owner",List.of(p.getX(),p.getY(),p.getZ()));measured.put("layers",geometry.layers());}
            measured.put("skinY",renderedDiagnosticNumber(failure.skinY()));
            measured.put("physicalDepthMillimeters",renderedDiagnosticNumber(failure.physicalDepth()));
            measured.put("baseDepthMillimeters",renderedDiagnosticNumber(failure.baseDepth()));
            if(trace!=null)measured.put("immersionMillimeters",renderedDiagnosticNumber((failure.skinY()-trace.soleY())*1000));
            support=Map.copyOf(measured);
        }
        if(receiver.rejections.size()==8)receiver.rejections.removeFirst();
        receiver.rejections.add(new RenderedTraceRejection(sequence,tick,reason,input,position,List.copyOf(body),support));
    }
    /**
     * Integrated-owner render extraction runs at a different phase from the 20 Hz gait.
     * Replay its original sole and measured published support, retaining current loaded material bounds.
     * Callers enqueue this immutable, bounded batch onto the server thread; no network payload is accepted.
     */
    public static void acceptRenderedFootTraces(ServerPlayer player,List<RenderedFootTrace> traces,long sequence){
        if(player==null||traces==null||traces.isEmpty()||traces.size()>RENDER_TRACE_BATCH||sequence<0)return;
        ServerLevel level=player.level();MinecraftServer server=level.getServer();
        if(!server.isSameThread()||!server.isSingleplayer()||!server.isSingleplayerOwner(player.nameAndId())
                ||server.getPlayerList().getPlayer(player.getUUID())!=player||!allowed(level))return;
        State s=state(level);var receiver=s.renderedTraces.computeIfAbsent(player.getUUID(),ignored->new RenderedTraceState());
        if(sequence<=receiver.sequence)return;receiver.sequence=sequence;receiver.received+=traces.size();
        long tick=level.getGameTime();receiver.observe(player,tick);
        if(receiver.budgetTick!=tick){receiver.budgetTick=tick;receiver.used=0;}
        if(receiver.used+traces.size()>RENDER_TRACES_PER_TICK){
            receiver.budgetRejected+=traces.size();
            for(var trace:traces)recordRenderedRejection(receiver,sequence,tick,player,trace,-1,"per-tick-trace-budget",null);
            return;
        }
        receiver.used+=traces.size();double scale=player.getScale();
        if(!Double.isFinite(scale)||scale<=0||scale>4){
            receiver.rejected+=traces.size();
            for(var trace:traces)recordRenderedRejection(receiver,sequence,tick,player,trace,-1,"invalid-player-scale",null);
            return;
        }
        int profile=PlayerSnowFootprints.soleProfile(player);var sole=SoleProfiles.profile(profile);
        var changed=new java.util.HashSet<Long>();
        for(var trace:traces){
            String rejection=!finiteRenderedTrace(trace,sole,scale)?"invalid-trace-shape":
                    !renderedNearMotion(receiver,trace,sole)?"outside-grounded-body-history":
                    level.getChunkSource().getChunkNow((int)Math.floor(trace.x())>>4,(int)Math.floor(trace.z())>>4)==null?"source-chunk-unloaded":null;
            if(rejection!=null){
                receiver.rejected++;recordRenderedRejection(receiver,sequence,tick,player,trace,profile,rejection,null);continue;
            }
            long before=s.contacts;
            var failure=stamp(s,trace.x(),trace.soleY(),trace.z(),trace.yaw(),trace.width(),trace.length(),false,profile,
                    trace.channel(),trace.channel()?.82f:1,false,changed,trace.support());
            if(s.contacts>before){
                receiver.stamped++;
                if(!receiver.body.isEmpty()&&receiver.body.getLast().grounded())receiver.lastRenderedPressureTick=tick;
            }else{
                receiver.rejected++;
                recordRenderedRejection(receiver,sequence,tick,player,trace,profile,failure==null?"no-authored-contact":failure.reason(),failure);
            }
        }
        for(long chunk:changed)refreshContacts(s,chunk);
    }
    /** Server-thread diagnostics distinguish a real accepted source from a rate/bounds rejection. */
    public static RenderedFootTraceMetrics renderedFootTraceMetrics(ServerPlayer player){
        if(player==null||!player.level().getServer().isSameThread())return new RenderedFootTraceMetrics(-1,0,0,0,0,List.of());
        State state=STATES.get(player.level().getServer());RenderedTraceState receiver=state==null?null:state.renderedTraces.get(player.getUUID());
        return receiver==null?new RenderedFootTraceMetrics(-1,0,0,0,0,List.of()):
                new RenderedFootTraceMetrics(receiver.sequence,receiver.received,receiver.stamped,receiver.rejected,receiver.budgetRejected,
                        receiver.serverPressureSweepTicksSuppressed,receiver.serverStanceStamps,receiver.serverLoadOnlyStamps,
                        receiver.serverFallbackPressureStamps,List.copyOf(receiver.rejections));
    }
    public record RenderedBootstrapRetryMetrics(long token,long accepted,long rejected,long cooldownRejected,
            long idempotent,long lastAcceptedStreamTick,long cohortFirstSourceRevision,boolean initialPagesSent,
            boolean requiresAck,boolean awaitingAck,boolean acknowledged,long acksAccepted,long acksRejected,long acksIdempotent,
            long acceptedSourceRevision,long acceptedOldAcks,int sentCohortHistorySize) {}
    /** A stale native support invalidates a frozen cohort; only the actual local owner may rearm it. */
    public static void requestRenderedBootstrapRetry(ServerPlayer player,long token){
        if(player==null)return;ServerLevel level=player.level();MinecraftServer server=level.getServer();
        if(!server.isSameThread()||!server.isSingleplayer()||!server.isSingleplayerOwner(player.nameAndId())
                ||server.getPlayerList().getPlayer(player.getUUID())!=player||!allowed(level))return;
        State s=state(level);if(s.level!=level)return;
        var view=s.streamPlayers.computeIfAbsent(player.getUUID(),ignored->new StreamPlayer());
        if(token<=0||token<view.bootstrapRetryToken){view.bootstrapRetriesRejected++;return;}
        if(token==view.bootstrapRetryToken){view.bootstrapRetryIdempotent++;return;}
        // Do not consume an early new token: the same client request may succeed after the cooldown.
        if(view.lastBootstrapRetryTick!=Long.MIN_VALUE&&s.streamTicks-view.lastBootstrapRetryTick<20){
            view.bootstrapRetriesRejected++;view.bootstrapRetryCooldownRejected++;return;
        }
        view.bootstrapRetryToken=token;view.lastBootstrapRetryTick=s.streamTicks;view.bootstrapRetriesAccepted++;
        view.pending=null;view.initialFrames=List.of();view.initialNext=0;view.bootstrapped=false;
        view.bootstrapSourceRevision=0;view.bootstrapInitialSent=false;view.bootstrapRequiresAck=false;
        view.bootstrapAwaitingAck=false;view.bootstrapAcknowledged=false;view.bootstrapAcceptedSourceRevision=0;
        // Stream revisions and all authoritative pressure/history remain monotonic and untouched.
    }
    /** A committed, fully sent SSP cohort releases initialization; this is not a latest-pressure ACK. */
    public static boolean ackRenderedBootstrap(ServerPlayer player,long firstSourceRevision){
        if(player==null)return false;ServerLevel level=player.level();MinecraftServer server=level.getServer();
        if(!server.isSameThread()||!server.isSingleplayer()||!server.isSingleplayerOwner(player.nameAndId())
                ||server.getPlayerList().getPlayer(player.getUUID())!=player||!allowed(level))return false;
        State s=STATES.get(server);if(s==null||s.level!=level)return false;
        StreamPlayer view=s.streamPlayers.get(player.getUUID());if(view==null)return false;
        if(firstSourceRevision<=0||!view.bootstrapSentCohorts.contains(firstSourceRevision)){view.bootstrapAcksRejected++;return false;}
        if(view.bootstrapAcknowledged){view.bootstrapAcksIdempotent++;return true;}
        view.bootstrapAcknowledged=true;view.bootstrapAwaitingAck=false;view.bootstrapAcksAccepted++;
        view.bootstrapAcceptedSourceRevision=firstSourceRevision;
        if(firstSourceRevision!=view.bootstrapSourceRevision)view.bootstrapAcceptedOldAcks++;
        return true;
    }
    public static RenderedBootstrapRetryMetrics renderedBootstrapRetryMetrics(ServerPlayer player){
        if(player==null||!player.level().getServer().isSameThread())return new RenderedBootstrapRetryMetrics(0,0,0,0,0,-1,0,false,false,false,false,0,0,0,0,0,0);
        State s=STATES.get(player.level().getServer());StreamPlayer view=s==null?null:s.streamPlayers.get(player.getUUID());
        return view==null?new RenderedBootstrapRetryMetrics(0,0,0,0,0,-1,0,false,false,false,false,0,0,0,0,0,0):
                new RenderedBootstrapRetryMetrics(view.bootstrapRetryToken,view.bootstrapRetriesAccepted,view.bootstrapRetriesRejected,
                        view.bootstrapRetryCooldownRejected,view.bootstrapRetryIdempotent,
                        view.lastBootstrapRetryTick==Long.MIN_VALUE?-1:view.lastBootstrapRetryTick,
                        view.bootstrapSourceRevision,view.bootstrapInitialSent,view.bootstrapRequiresAck,view.bootstrapAwaitingAck,
                        view.bootstrapAcknowledged,view.bootstrapAcksAccepted,view.bootstrapAcksRejected,view.bootstrapAcksIdempotent,
                        view.bootstrapAcceptedSourceRevision,view.bootstrapAcceptedOldAcks,view.bootstrapSentCohorts.size());
    }
    private static void walk(State s,ServerPlayer player){
        var walker=s.walkers.computeIfAbsent(player.getUUID(),ignored->new PlayerSnowFootprints.Walker(player));
        var server=s.level.getServer();
        var rendered=server.isSingleplayer()&&server.isSingleplayerOwner(player.nameAndId())?s.renderedTraces.get(player.getUUID()):null;
        boolean loadOnly=rendered!=null&&rendered.renderedPressureActive(player,s.level.getGameTime());
        if(loadOnly)rendered.serverPressureSweepTicksSuppressed++;
        for(var plant:walker.update(player,loadOnly)){
            long before=s.contacts;
            stamp(s,plant.x(),plant.y(),plant.z(),plant.yaw(),plant.width(),plant.length(),
                    false,plant.soleProfile(),plant.channel(),plant.pressure(),plant.loading());
            if(rendered!=null&&s.contacts>before){
                if(plant.loading()){
                    rendered.serverStanceStamps++;
                    if(loadOnly)rendered.serverLoadOnlyStamps++;
                }else rendered.serverFallbackPressureStamps++;
            }
        }
    }
    /** Read live ledgers, not the last published views: earlier contacts in this batch already exist. */
    private static double inheritedPressure(State s,SnowHostGeometry.Cell geometry,double[] corners,double x,double z){
        double pressure=0;int cx=geometry.pos().getX()>>4,cz=geometry.pos().getZ()>>4;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            double originX=(cx+dx)*16.0,originZ=(cz+dz)*16.0;
            for(var surface:surfaces(s,key(cx+dx,cz+dz))){
                pressure=Math.max(pressure,surface.depressionMillimeters(x-originX,z-originZ,
                        contact->SnowHostGeometry.contactMatches(geometry,corners,originX+contact.x(),contact.y(),originZ+contact.z(),
                                contact.width(),contact.length(),contact.ownerY())));
            }
        }
        return pressure;
    }
    private static void stamp(State s,double x,double y,double z,float yaw,float width,float length,boolean vehicle){
        stamp(s,x,y,z,yaw,width,length,vehicle,0);
    }
    private static void stamp(State s,double x,double y,double z,float yaw,float width,float length,boolean vehicle,int soleProfile){
        stamp(s,x,y,z,yaw,width,length,vehicle,soleProfile,false,1,true);
    }
    private static void stamp(State s,double x,double y,double z,float yaw,float width,float length,boolean vehicle,int soleProfile,boolean scuff,float pressure,boolean loading){
        stamp(s,x,y,z,yaw,width,length,vehicle,soleProfile,scuff,pressure,loading,null,null);
    }
    private static RenderedStampFailure stampFailure(Set<Long> renderedChanges,String reason,SnowHostGeometry.Cell geometry,
            double skinY,SnowSurface surface,int column){
        if(renderedChanges==null)return null;
        return new RenderedStampFailure(reason,geometry,skinY,surface==null?Double.NaN:surface.physicalDepthMillimeters(column),
                surface==null?Double.NaN:surface.sample(column).baseMillimeters());
    }
    private static RenderedStampFailure stamp(State s,double x,double y,double z,float yaw,float width,float length,boolean vehicle,int soleProfile,boolean scuff,float pressure,boolean loading,Set<Long> changed,RenderedSupport captured){
        var g=findGeometry(s.level,x,y,z);if(g==null)return stampFailure(changed,"no-loaded-support",null,Double.NaN,null,-1);
        if(captured!=null&&!captured.matches(s.level,g,x,y,z))
            return stampFailure(changed,"captured-support-owner-changed",g,Double.NaN,null,-1);
        SnowSurface surface=surface(s,g.pos(),true);if(surface==null)return stampFailure(changed,"surface-unavailable",g,Double.NaN,null,-1);
        int column=cell(g.pos().getX(),g.pos().getZ());surface.observe(column,g.pos().getY(),g.layers(),s.level.getGameTime(),support(s,g));
        Exposure e=exposure(s,g);var snapshot=s.timeline.snapshot();surface.advance(column,snapshot,e.vertical(),e.directional);
        if(surface.sample(column).depthMillimeters()<=.01)return stampFailure(changed,"no-snow-depth",g,Double.NaN,surface,column);
        double[] corners=fineCornersMillimeters(s.level,g);
        double top=vehicle?surfaceHeight(s.level,x,y,z):SnowHostGeometry.sample(g,x,z,corners).height();
        if(!Double.isFinite(top)||Math.abs(top-y)>1.25)return stampFailure(changed,!Double.isFinite(top)?"nonfinite-skin":"sole-support-distance",g,top,surface,column);
        float physical=surface.physicalDepthMillimeters(column),depth,compaction;
        if(vehicle){
            depth=(float)Math.min(750,Math.max(60,SnowTravelPolicy.sinkMillimeters(physical,surface.sample(column).baseMillimeters())));
            compaction=.8f;
        }else{
            double policyTop=captured==null?top:captured.skinY();
            var press=captured==null?SnowFootContactPolicy.sampleFixed(
                    physical,surface.sample(column).baseMillimeters(),top,y,scuff,pressure):
                    SnowFootContactPolicy.sampleCapturedFixed(captured.pressure,physical,true,y,scuff,pressure);
            if(press==null){
                String reason=physical<1?"physical-depth-below1mm":y>policyTop+.02?"sole-above-skin":
                        scuff&&(captured==null?physical:captured.pressure.physicalColumnDepthMm())<200?"fixed-channel-shallow-snow":"pressure-policy-rejected";
                return stampFailure(changed,reason,g,top,surface,column);
            }
            depth=press.depthMillimeters();
            // Every fixed placement carries its load once; exact storage repeats cannot compact again.
            compaction=press.compaction();
        }
        float storedX=SnowSurface.contactChunkCoordinate(x),storedZ=SnowSurface.contactChunkCoordinate(z);
        // Footprints retain their selected profile. Vehicles keep the existing cumulative pressure model.
        if(vehicle)depth=SnowSurface.contactDepthWithCavity(depth,inheritedPressure(s,g,corners,x,z),physical);
        if(depth<=0)return stampFailure(changed,"no-combined-pressure",g,top,surface,column);
        double contactTop=captured==null?top:captured.skinY();
        var placement=new SnowSurface.Contact(storedX,(float)contactTop,storedZ,
                yaw,width,length,0,vehicle,depth,compaction,soleProfile,g.pos().getY());
        boolean contactChanged=true;
        if(vehicle)surface.pressContact(column,placement,snapshot);
        else{
            int before=surface.contactCount();
            surface.pressFixedContact(column,placement,snapshot);
            contactChanged=surface.contactCount()!=before;
        }
        // A duplicate remains an accepted rendered trace but creates no new presentation work.
        s.contacts++;
        if(contactChanged){
            long chunk=key(g.pos().getX()>>4,g.pos().getZ()>>4);
            if(changed==null)refreshContacts(s,chunk);else changed.add(chunk);
        }
        return null;
    }
    public static int vehicleTrack(ServerLevel level,Vec3 from,Vec3 to,float width){
        owner(level);if(from==null||to==null||!allowed(level)||!Double.isFinite(from.x+from.y+from.z)||!Double.isFinite(to.x+to.y+to.z)||!Float.isFinite(width)||width<.04||width>2)return 0;
        double distance=from.distanceTo(to);if(distance>16)return 0;
        State s=state(level);int count=Math.max(1,(int)Math.ceil(distance/.3));float yaw=(float)Math.toDegrees(Math.atan2(to.z-from.z,to.x-from.x))-90;long before=s.contacts;
        for(int i=0;i<count;i++){double t=(i+.5)/count;stamp(s,from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t,from.z+(to.z-from.z)*t,yaw,width,.5f,true);}
        return (int)(s.contacts-before);
    }
    private static List<SnowSurfacePayload.Column> presentationColumns(State s,int x,int z,SnowWeatherTimeline.Snapshot snapshot){
        long k=key(x>>4,z>>4);Hydration h=s.hydration.get(k);int column=cell(x,z);
        List<SnowHostGeometry.Cell> owners=h==null?null:h.geometry[column];if(owners==null||owners.isEmpty())return List.of();
        var result=new ArrayList<SnowSurfacePayload.Column>(owners.size());
        for(var g:owners){
            SnowSurface surface=surface(s,g.pos(),false);if(surface==null)continue;
            Map<Long,ExposureCache> observed=s.exposure.get(k);ExposureCache cached=observed==null?null:observed.get(g.pos().asLong());
            Exposure e=cached==null?exposure(s,g):cached.exposure();
            surface.advance(column,snapshot,e.vertical(),e.directional);
            result.add(new SnowSurfacePayload.Column(g,surface.fineDeltaMillimeters(column),surface.sample(column).pathCompactionFraction()));
        }
        return result;
    }
    private static SnowSurfaceChunkPayload.Chunk frame(State s,int cx,int cz,SnowWeatherTimeline.Snapshot snapshot){
        return frame(s,cx,cz,snapshot,++s.streamRevision);
    }
    private static SnowSurfaceChunkPayload.Chunk frame(State s,int cx,int cz,SnowWeatherTimeline.Snapshot snapshot,long sourceRevision){
        var columns=new ArrayList<SnowSurfacePayload.Column>(256);var halo=new ArrayList<SnowSurfacePayload.Column>(68);
        for(int z=cz*16-1;z<=cz*16+16;z++)for(int x=cx*16-1;x<=cx*16+16;x++){
            var values=presentationColumns(s,x,z,snapshot);
            if((x>>4)==cx&&(z>>4)==cz)columns.addAll(values);else halo.addAll(values);
        }
        var marks=new ArrayList<SnowSurfacePayload.Mark>();
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)for(var c:s.contactViews.getOrDefault(key(cx+dx,cz+dz),List.of())){
            double x=SnowSurface.contactWorldCoordinate(cx+dx,c.x()),z=SnowSurface.contactWorldCoordinate(cz+dz,c.z());
            double radius=SnowContactRelief.influenceRadiusMeters(c.width(),c.length(),c.depressionMillimeters());
            if(x+radius<cx*16||x-radius>cx*16+16||z+radius<cz*16||z-radius>cz*16+16)continue;
            marks.add(new SnowSurfacePayload.Mark(x,c.y(),z,c.yaw(),c.width(),c.length(),c.strength(),c.vehicle(),c.depressionMillimeters(),c.soleProfile(),c.ownerY()));
        }
        return new SnowSurfaceChunkPayload.Chunk(cx,cz,sourceRevision,snapshot.tick(),columns,halo,marks);
    }
    private static void announce(State s,ServerPlayer player,int cx,int cz){
        if(!ServerPlayNetworking.canSend(player,SnowSurfaceChunkPayload.TYPE))return;
        var active=new ArrayList<Long>(49);
        for(int dz=-PREFETCH_RADIUS;dz<=PREFETCH_RADIUS;dz++)for(int dx=-PREFETCH_RADIUS;dx<=PREFETCH_RADIUS;dx++)
            active.add(key(cx+dx,cz+dz));
        ServerPlayNetworking.send(player,new SnowSurfaceChunkPayload(s.level.dimension().identifier().toString(),
                s.level.getGameTime(),active,List.of()));
    }
    /** Fingerprints suppress unchanged frames; transmitted values keep their original precision. */
    private static long columnSignature(long hash,SnowSurfacePayload.Column c){
        hash=mix(hash,BlockPos.asLong(c.x(),c.y(),c.z()));hash=mix(hash,c.layers());hash=mix(hash,c.mask());hash=mix(hash,c.crust());
        hash=mix(hash,Float.floatToIntBits(c.baseOffset()));hash=mix(hash,c.patches().hashCode());
        hash=mix(hash,Math.round(c.deltaMillimeters()/FRAME_HEIGHT_QUANTUM_MM));
        return mix(hash,Math.round(c.pathCompactionFraction()*1000));
    }
    private static long frameSignature(SnowSurfaceChunkPayload.Chunk frame){
        long hash=0xcbf29ce484222325L;
        for(var column:frame.columns())hash=columnSignature(hash,column);
        hash=mix(hash,frame.columns().size());
        for(var column:frame.halo())hash=columnSignature(hash,column);
        hash=mix(hash,frame.halo().size());
        for(var mark:frame.marks()){
            hash=mix(hash,Double.doubleToLongBits(mark.x()));hash=mix(hash,Float.floatToIntBits(mark.y()));hash=mix(hash,Double.doubleToLongBits(mark.z()));
            hash=mix(hash,Float.floatToIntBits(mark.yaw()));hash=mix(hash,Float.floatToIntBits(mark.width()));hash=mix(hash,Float.floatToIntBits(mark.length()));
            hash=mix(hash,mark.vehicle()?1:0);hash=mix(hash,mark.soleProfile());hash=mix(hash,mark.ownerY());
            hash=mix(hash,Math.round(mark.depthMillimeters()/CONTACT_DEPTH_QUANTUM_MM));hash=mix(hash,Math.round(mark.strength()*512));
        }
        return mix(hash,frame.marks().size());
    }
    private static PendingFrame freeze(State s,SnowSurfaceChunkPayload.Chunk frame){
        return new PendingFrame(frame,frameSignature(frame),s.contactRevisions.getOrDefault(frame.key(),0L));
    }
    /** One bounded packet, with no rebuild or revision change while a logical field is in flight. */
    private static boolean transmit(State s,ServerPlayer player,StreamPlayer view,List<Long> active,PendingFrame pending){
        ServerPlayNetworking.send(player,new SnowSurfaceChunkPayload(s.level.dimension().identifier().toString(),
                s.level.getGameTime(),active,List.of(pending.pages.get(pending.nextPage++))));
        if(pending.nextPage<pending.pages.size())return false;
        // Later pressure writes must stay urgent even if they arrived while these pages were sent.
        view.sent.put(pending.frame.key(),new SentFrame(pending.signature,pending.contactRevision,s.streamTicks));
        s.sentFrames++;return true;
    }
    private static void rememberSentBootstrap(StreamPlayer view){
        long revision=view.bootstrapSourceRevision;
        if(revision<=0||view.bootstrapSentCohorts.contains(revision))return;
        if(view.bootstrapSentCohorts.size()>=BOOTSTRAP_COHORT_HISTORY)view.bootstrapSentCohorts.removeFirst();
        view.bootstrapSentCohorts.add(revision);
    }
    private static void send(State s,ServerPlayer player,SnowWeatherTimeline.Snapshot snapshot){
        if(!ServerPlayNetworking.canSend(player,SnowSurfaceChunkPayload.TYPE))return;
        int cx=player.blockPosition().getX()>>4,cz=player.blockPosition().getZ()>>4;
        StreamPlayer view=s.streamPlayers.get(player.getUUID());if(view==null)return;
        var active=new ArrayList<Long>(49);
        for(int dz=-PREFETCH_RADIUS;dz<=PREFETCH_RADIUS;dz++)for(int dx=-PREFETCH_RADIUS;dx<=PREFETCH_RADIUS;dx++)active.add(key(cx+dx,cz+dz));
        view.sent.keySet().retainAll(active);
        long started=System.nanoTime();int processed=0;
        if(!view.bootstrapped){
            if(view.initialFrames.isEmpty()){
                if(!nearReady(s,cx,cz))return;
                // The opaque initial gate receives one coherent, finite nine-frame weather snapshot.
                // Later walking never rearms this group or waits for unrelated neighbours.
                long cohortRevision=++s.streamRevision;
                var initial=new ArrayList<PendingFrame>(9);
                initial.add(freeze(s,frame(s,cx,cz,snapshot,cohortRevision)));
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dz!=0)
                    initial.add(freeze(s,frame(s,cx+dx,cz+dz,snapshot,cohortRevision)));
                view.initialFrames=List.copyOf(initial);view.initialNext=0;
                // One identity for the atomic nine-chunk cohort, even when the weather clock is paused.
                view.bootstrapSourceRevision=cohortRevision;
                view.bootstrapInitialSent=false;
                view.bootstrapRequiresAck=player.level().getServer().isSingleplayer()&&player.level().getServer().isSingleplayerOwner(player.nameAndId());
                if(!view.bootstrapRequiresAck)view.bootstrapAcknowledged=true;
                view.bootstrapAwaitingAck=view.bootstrapRequiresAck&&!view.bootstrapAcknowledged;
            }
            while(view.initialNext<view.initialFrames.size()&&processed<MAX_STREAM_FRAMES_PER_TICK){
                if(processed>0&&System.nanoTime()-started>=STREAM_BUDGET_NANOS)break;
                var pending=view.initialFrames.get(view.initialNext);
                if(transmit(s,player,view,active,pending))view.initialNext++;
                processed++;
            }
            if(view.initialNext==view.initialFrames.size()){
                rememberSentBootstrap(view);
                view.bootstrapped=true;view.initialFrames=List.of();view.initialNext=0;view.bootstrapInitialSent=true;
                STREAM_LOG.info("Nearby snow field ready: centre={},{} frames=9 weatherTick={} streamTick={}",cx,cz,snapshot.tick(),s.streamTicks);
            }
            return;
        }
        // The SSP client must commit the finite coherent cohort before later frames can replace it in its inbox.
        // Remote players retain the ordinary streaming policy; their protocol has no local commit bridge.
        if(view.bootstrapRequiresAck&&view.bootstrapAwaitingAck)return;
        // Finish an immutable logical snapshot before acknowledging it or selecting a newer one.
        // Paging bounds network work, rather than dropping old geometry to fit a packet.
        if(view.pending!=null&&!active.contains(view.pending.frame.key()))view.pending=null;
        while(view.pending!=null&&processed<MAX_STREAM_FRAMES_PER_TICK){
            if(processed>0&&System.nanoTime()-started>=STREAM_BUDGET_NANOS)break;
            if(transmit(s,player,view,active,view.pending))view.pending=null;
            processed++;
        }
        if(view.pending!=null||processed>=MAX_STREAM_FRAMES_PER_TICK)return;
        var urgent=new ArrayList<StreamCandidate>();var firstFrames=new ArrayList<StreamCandidate>();var background=new ArrayList<StreamCandidate>();
        for(long k:active){
            int x=chunkX(k),z=chunkZ(k);
            if(s.level.getChunkSource().getChunkNow(x,z)==null||!fieldReady(s,x,z))continue;
            SentFrame previous=view.sent.get(k);long checked=previous==null?Long.MIN_VALUE:previous.checkedTick();
            long contact=s.contactRevisions.getOrDefault(k,0L);
            boolean near=Math.max(Math.abs(x-cx),Math.abs(z-cz))<=1;
            if(previous==null){
                // Every new ready chunk has a bounded first-publication turn, including new contact owners.
                firstFrames.add(new StreamCandidate(k,contact>0?0:near?1:3,view.ahead.priority(x,z),checked));continue;
            }
            if(contact>previous.contactRevision()){
                urgent.add(new StreamCandidate(k,0,view.ahead.priority(x,z),checked));continue;
            }
            if(s.streamTicks-checked<FRAME_RECHECK_TICKS)continue;
            background.add(new StreamCandidate(k,near?2:4,view.ahead.priority(x,z),checked));
        }
        // Oldest pending contacts go first: a newly dirty hotspot cannot starve a neighbouring trail.
        urgent.sort(Comparator.comparingLong(StreamCandidate::checkedTick).thenComparingDouble(StreamCandidate::priority));
        firstFrames.sort(Comparator.comparingInt(StreamCandidate::tier).thenComparingDouble(StreamCandidate::priority));
        background.sort(Comparator.comparingInt(StreamCandidate::tier).thenComparingLong(StreamCandidate::checkedTick).thenComparingDouble(StreamCandidate::priority));
        int contactIndex=0,firstIndex=0,backgroundIndex=0;
        // Three contact starts at most before a waiting first frame takes the next available slot.
        // Count starts across ticks, not only second slots: one expensive frame can use the 1.5 ms budget.
        // Existing pending pages above still finish their immutable logical frame before selection resumes.
        while(processed<MAX_STREAM_FRAMES_PER_TICK&&(contactIndex<urgent.size()||firstIndex<firstFrames.size()||backgroundIndex<background.size())){
            if(processed>0&&System.nanoTime()-started>=STREAM_BUDGET_NANOS)break;
            boolean contactsReady=contactIndex<urgent.size(),firstReady=firstIndex<firstFrames.size();
            StreamCandidate candidate=view.firstFrameTurn(contactsReady,firstReady)?firstFrames.get(firstIndex++)
                    :contactsReady?urgent.get(contactIndex++):background.get(backgroundIndex++);
            long k=candidate.key();var made=frame(s,chunkX(k),chunkZ(k),snapshot);long signature=frameSignature(made);
            SentFrame previous=view.sent.get(k);
            if(previous==null||previous.signature()!=signature){
                view.pending=new PendingFrame(made,signature,s.contactRevisions.getOrDefault(k,0L));
                do{
                    if(transmit(s,player,view,active,view.pending))view.pending=null;
                    processed++;
                }while(view.pending!=null&&processed<MAX_STREAM_FRAMES_PER_TICK&&System.nanoTime()-started<STREAM_BUDGET_NANOS);
                if(view.pending!=null)break;
            }else{
                view.sent.put(k,new SentFrame(signature,s.contactRevisions.getOrDefault(k,0L),s.streamTicks));processed++;
            }
        }
    }
}
