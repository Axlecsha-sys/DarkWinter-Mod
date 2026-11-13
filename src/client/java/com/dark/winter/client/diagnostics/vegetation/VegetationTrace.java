// Opt-in bounded evidence for real vegetation frames. No image capture, world scan or production policy changes.
package com.dark.winter.client.diagnostics.vegetation;

import com.dark.winter.client.diagnostics.performance.PerformanceGpuSampler;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import org.joml.Matrix4fc;

/**
 * Explicit SDK/debug capture only. Primitive RAM rings overwrite their oldest observations.
 * Geometry observations describe actual model selection/emission, not visibility or GPU execution.
 * GPU samples use the existing asynchronous timer sampler; no query is waited on.
 */
public final class VegetationTrace {
    public static final int CAPACITY=8192, EVENT_CAPACITY=32768, POPULATION_CAPACITY=64, MAX_SPRIGS=384;
    private static final int LONGS=28, VALUES=16, EVENT_LONGS=10, POP_LONGS=7;
    private static final List<String> LONG_COLUMNS=List.of("frameId","timeNanos","renderEndNanos","frameNanos",
            "renderWallNanos","gpuRenderNanos","worldTime","temporalResets","temporalCaptures","resourceEpoch",
            "configRevision","batchEpoch","batchRevision","populationHash","lightHash","materialRevision",
            "mainMaterialBindings","shadowMaterialBindings","mainNativeMaterialDispatches","shadowNativeMaterialDispatches",
            "geometryObservations","sectionObservations","flags","width","height","compileQueue","freeCompileBuffers","windUniformHash");
    private static final List<String> VALUE_COLUMNS=List.of("cameraX","cameraY","cameraZ","cameraYaw","cameraPitch","partialTick",
            "windSeconds","windAmplitude","windX","windZ","jitterX","jitterY","phase","drawnSprigs","blockChecks","rootInvalidations");
    private static final List<String> EVENT_COLUMNS=List.of("timeNanos","type","sectionNode","position","generationOrState",
            "geometryIdentity","quadCountOrLayer","blockLight","skyLight","threadId");
    private static final List<String> POP_COLUMNS=List.of("timeNanos","epoch","revision","origin","count","populationHash","lightHash");
    public static final int MODEL_KEY=1, MODEL_EMIT=2, SECTION_COMPILE=3, SECTION_RESET=4,
            SECTION_PUBLISH=5, SECTION_REJECT=6, SECTION_RELEASE=7;
    // flags: render completed, world drawn, temporal prepared/enabled/history/jittered, wind drawn, screen/overlay.
    private static final int COMPLETE=1,WORLD=2,TEMPORAL=4,AA=8,HISTORY=16,JITTER=32,WIND=64,INTERRUPTED=128;
    private static volatile boolean enabled;
    private static long[] frames,events,populations,populationIds,populationPositions,populationLights;
    private static double[] values;
    private static float[] matrices;
    private static String[] resetReasons,modelIds;
    private static final float[] MATRIX_SCRATCH=new float[16];
    private static final Object EVENT_LOCK=new Object();
    private static int frameSlot=-1,eventCursor,populationCursor,populationSlot=-1;
    private static long frameCount,eventCount,populationCount,geometryCount,sectionCount,previousStart;
    private static long populationEpoch=Long.MIN_VALUE,populationRevision=Long.MIN_VALUE,populationHash,lightHash;
    private static PerformanceGpuSampler gpu;
    private static Map<String,Object> closedGpu=Map.of("status","NOT_REQUESTED");
    private static final PerformanceGpuSampler.CompletedSample GPU_SAMPLE=VegetationTrace::gpuSample;
    private VegetationTrace() {}

    public static boolean enabled() { return enabled; }

    /** Render-thread only. Changing this gate never changes geometry, quality, weather or simulation. */
    public static void setEnabledForTest(boolean value) {
        RenderSystem.assertOnRenderThread();
        if(value==enabled)return;
        if(value) {
            if(frames==null)allocate();
            reset();
            gpu=new PerformanceGpuSampler();
            enabled=true;
        } else {
            enabled=false;
            if(gpu!=null) { gpu.poll(GPU_SAMPLE);closedGpu=Map.copyOf(gpu.snapshot());gpu.close();gpu=null; }
            frameSlot=-1;
        }
    }
    public static void reset() {
        RenderSystem.assertOnRenderThread();
        if(frames==null)allocate();
        boolean resume=enabled;enabled=false;
        // Outstanding timer results belong to the previous frame-id epoch.
        if(gpu!=null) { gpu.close();gpu=null; }
        synchronized(EVENT_LOCK) {
            frameCount=eventCount=populationCount=geometryCount=sectionCount=previousStart=0;
            eventCursor=populationCursor=0;frameSlot=populationSlot=-1;
            populationEpoch=populationRevision=Long.MIN_VALUE;populationHash=lightHash=0;
            Arrays.fill(frames,0);Arrays.fill(values,0);Arrays.fill(matrices,0);
            Arrays.fill(resetReasons,null);Arrays.fill(modelIds,null);
        }
        closedGpu=Map.of("status","NOT_REQUESTED");
        if(resume) { gpu=new PerformanceGpuSampler();enabled=true; }
    }
    private static void allocate() {
        frames=new long[CAPACITY*LONGS];values=new double[CAPACITY*VALUES];matrices=new float[CAPACITY*16];
        resetReasons=new String[CAPACITY];events=new long[EVENT_CAPACITY*EVENT_LONGS];modelIds=new String[EVENT_CAPACITY];
        populations=new long[POPULATION_CAPACITY*POP_LONGS];
        populationIds=new long[POPULATION_CAPACITY*MAX_SPRIGS];
        populationPositions=new long[POPULATION_CAPACITY*MAX_SPRIGS];
        populationLights=new long[POPULATION_CAPACITY*MAX_SPRIGS];
    }

    public static void renderBegin() {
        if(!enabled)return;
        int row=(int)(frameCount%CAPACITY);frameSlot=row;
        int b=row*LONGS,v=row*VALUES;
        Arrays.fill(frames,b,b+LONGS,0);Arrays.fill(values,v,v+VALUES,0);
        Arrays.fill(matrices,row*16,row*16+16,0);resetReasons[row]=null;
        long now=System.nanoTime(),id=frameCount++;
        frames[b]=id;frames[b+1]=now;frames[b+3]=previousStart==0?-1:now-previousStart;
        frames[b+5]=-1;previousStart=now;
        if(gpu!=null) { gpu.poll(GPU_SAMPLE);gpu.begin(id); }
    }
    public static void worldBegin(GameRenderer renderer) {
        if(!enabled||frameSlot<0)return;
        var client=Minecraft.getInstance();
        var state=renderer.gameRenderState().levelRenderState;
        var camera=state.cameraRenderState;
        int b=frameSlot*LONGS,v=frameSlot*VALUES;
        frames[b+22]|=WORLD;frames[b+6]=state.gameTime;
        values[v]=camera.pos.x;values[v+1]=camera.pos.y;values[v+2]=camera.pos.z;
        values[v+3]=camera.yRot;values[v+4]=camera.xRot;values[v+5]=state.worldPartialTicks;
        frames[b+23]=renderer.mainRenderTarget().width;frames[b+24]=renderer.mainRenderTarget().height;
        if(client.gui.screen()!=null||client.gui.overlay()!=null||client.isPaused())frames[b+22]|=INTERRUPTED;
        var dispatcher=client.levelRenderer.sectionRenderDispatcher();
        if(dispatcher!=null) { frames[b+25]=dispatcher.getCompileQueueSize();frames[b+26]=dispatcher.getFreeBufferCount(); }
    }
    public static void renderEnd() {
        if(!enabled||frameSlot<0)return;
        if(gpu!=null)gpu.end();
        int b=frameSlot*LONGS;
        com.winterkore.engine.material.WkeFoliageTextures.copyFrameCounters(frames,b+15);
        long now=System.nanoTime();frames[b+2]=now;frames[b+4]=now-frames[b+1];frames[b+22]|=COMPLETE;
        synchronized(EVENT_LOCK) { frames[b+20]=geometryCount;frames[b+21]=sectionCount; }
        frameSlot=-1;
    }
    private static void gpuSample(long id,long nanos) {
        if(frames==null||id<0||frameCount-id>CAPACITY)return;
        int b=(int)(id%CAPACITY)*LONGS;
        if(frames[b]==id)frames[b+5]=nanos;
    }

    /** Receives the actual prepared TAA matrix/uniform values, rather than reconstructing an approximation. */
    public static void temporal(Matrix4fc actualViewProjection,boolean aaEnabled,boolean historyUsed,boolean jittered,
            int phase,float jitterX,float jitterY,long resets,long captures,int resourceEpoch,long configRevision,String reason) {
        if(!enabled||frameSlot<0)return;
        int b=frameSlot*LONGS,v=frameSlot*VALUES;
        frames[b+22]|=TEMPORAL|(aaEnabled?AA:0)|(historyUsed?HISTORY:0)|(jittered?JITTER:0);
        frames[b+7]=resets;frames[b+8]=captures;frames[b+9]=resourceEpoch;frames[b+10]=configRevision;
        values[v+10]=jitterX;values[v+11]=jitterY;values[v+12]=phase;resetReasons[frameSlot]=reason;
        actualViewProjection.get(MATRIX_SCRATCH);
        System.arraycopy(MATRIX_SCRATCH,0,matrices,frameSlot*16,16);
    }
    public static void material(long revision,long mainBindings,long shadowBindings,long mainNative,long shadowNative) {
        if(!enabled||frameSlot<0)return;
        int b=frameSlot*LONGS;
        frames[b+15]=revision;frames[b+16]=mainBindings;frames[b+17]=shadowBindings;
        frames[b+18]=mainNative;frames[b+19]=shadowNative;
    }
    public static void wind(float seconds,float amplitude,float x,float z,int drawn,int checks,long invalidations,long uniformHash) {
        if(!enabled||frameSlot<0)return;
        int b=frameSlot*LONGS,v=frameSlot*VALUES;
        frames[b+22]|=WIND;frames[b+11]=populationEpoch;frames[b+12]=populationRevision;
        frames[b+13]=populationHash;frames[b+14]=lightHash;frames[b+27]=uniformHash;
        values[v+6]=seconds;values[v+7]=amplitude;values[v+8]=x;values[v+9]=z;
        values[v+13]=drawn;values[v+14]=checks;values[v+15]=invalidations;
    }
    public static boolean needsPopulation(long epoch,long revision) {
        return enabled&&(populationEpoch!=epoch||populationRevision!=revision);
    }
    public static void populationBegin(long epoch,long revision,BlockPos origin,int count) {
        if(!enabled)return;
        populationSlot=populationCursor;populationCursor=(populationCursor+1)%POPULATION_CAPACITY;populationCount++;
        int b=populationSlot*POP_LONGS;
        populations[b]=System.nanoTime();populations[b+1]=epoch;populations[b+2]=revision;
        populations[b+3]=origin.asLong();populations[b+4]=Math.min(count,MAX_SPRIGS);
        populationEpoch=epoch;populationRevision=revision;populationHash=lightHash=0;
    }
    public static void populationSprig(int index,long position,int face,int shape,int branch,int color,int blockLight,int skyLight) {
        if(!enabled||populationSlot<0||index<0||index>=MAX_SPRIGS)return;
        int b=populationSlot*MAX_SPRIGS+index;
        long id=mix(position^((long)face<<57)^((long)shape<<24)^branch);
        long light=((long)color<<32)^((long)blockLight<<16)^skyLight;
        populationIds[b]=id;populationPositions[b]=position;populationLights[b]=light;
        populationHash+=id;lightHash+=mix(id^light);
        populations[populationSlot*POP_LONGS+5]=populationHash;populations[populationSlot*POP_LONGS+6]=lightHash;
    }
    public static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }

    /** Called only by the real authored model paths, including geometry-cache lookup and actual emission calls. */
    public static void model(int type,String id,BlockAndTintGetter level,BlockPos pos,int state,Object mesh,int quads) {
        if(!enabled)return;
        int block=level.getBrightness(LightLayer.BLOCK,pos),sky=level.getBrightness(LightLayer.SKY,pos);
        event(type,sectionNode(pos),pos.asLong(),state,System.identityHashCode(mesh),quads,block,sky,id);
    }
    public static boolean isVegetation(String id) {
        return id.equals("snowy_shrub")||id.equals("dry_grass_tuft")||id.equals("forest_fern")
                ||id.equals("forest_branch_pile")||id.equals("fallen_spruce_log")||id.equals("spruce_stump");
    }
    public static void section(int type,long node,long generation,Object mesh,int layer) {
        if(enabled)event(type,node,0,generation,mesh==null?0:System.identityHashCode(mesh),layer,0,0,null);
    }
    private static long sectionNode(BlockPos p) {
        return net.minecraft.core.SectionPos.asLong(p.getX()>>4,p.getY()>>4,p.getZ()>>4);
    }
    private static void event(int type,long node,long pos,long generation,int geometry,int quads,int block,int sky,String id) {
        if(!enabled)return;
        synchronized(EVENT_LOCK) {
            if(!enabled)return;
            int row=eventCursor,b=row*EVENT_LONGS;eventCursor=(row+1)%EVENT_CAPACITY;eventCount++;
            if(type<=MODEL_EMIT)geometryCount++;else sectionCount++;
            events[b]=System.nanoTime();events[b+1]=type;events[b+2]=node;events[b+3]=pos;
            events[b+4]=generation;events[b+5]=geometry;events[b+6]=quads;events[b+7]=block;events[b+8]=sky;
            events[b+9]=Thread.currentThread().threadId();modelIds[row]=id;
        }
    }

    /** Copies after a measurement window; never called by the per-frame observer. No references to worlds/models are retained. */
    public static Map<String,Object> snapshot() {
        RenderSystem.assertOnRenderThread();
        if(gpu!=null)gpu.poll(GPU_SAMPLE);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("capacity",CAPACITY);result.put("totalFrames",frameCount);
        result.put("overwrittenFrames",Math.max(0,frameCount-CAPACITY));
        result.put("longColumns",LONG_COLUMNS);result.put("valueColumns",VALUE_COLUMNS);
        List<long[]> frameRows=new ArrayList<>();List<double[]> valueRows=new ArrayList<>();List<float[]> matrixRows=new ArrayList<>();
        List<String> reasons=new ArrayList<>();
        for(long n=Math.max(0,frameCount-CAPACITY);n<frameCount;n++) {
            int r=(int)(n%CAPACITY);
            frameRows.add(Arrays.copyOfRange(frames,r*LONGS,(r+1)*LONGS));
            valueRows.add(Arrays.copyOfRange(values,r*VALUES,(r+1)*VALUES));
            matrixRows.add(Arrays.copyOfRange(matrices,r*16,(r+1)*16));reasons.add(resetReasons[r]==null?"":resetReasons[r]);
        }
        result.put("frames",List.copyOf(frameRows));result.put("values",List.copyOf(valueRows));
        result.put("actualViewProjection",List.copyOf(matrixRows));result.put("resetReasons",List.copyOf(reasons));
        synchronized(EVENT_LOCK) {
            List<long[]> rows=new ArrayList<>();List<String> ids=new ArrayList<>();
            for(long n=Math.max(0,eventCount-EVENT_CAPACITY);n<eventCount;n++) {
                int r=(int)(n%EVENT_CAPACITY);rows.add(Arrays.copyOfRange(events,r*EVENT_LONGS,(r+1)*EVENT_LONGS));
                ids.add(modelIds[r]==null?"":modelIds[r]);
            }
            result.put("eventColumns",EVENT_COLUMNS);result.put("events",List.copyOf(rows));result.put("modelIds",List.copyOf(ids));
            result.put("totalEvents",eventCount);result.put("overwrittenEvents",Math.max(0,eventCount-EVENT_CAPACITY));
        }
        List<Map<String,Object>> batches=new ArrayList<>();
        for(long n=Math.max(0,populationCount-POPULATION_CAPACITY);n<populationCount;n++) {
            int r=(int)(n%POPULATION_CAPACITY),count=(int)populations[r*POP_LONGS+4],start=r*MAX_SPRIGS;
            batches.add(Map.of("values",Arrays.copyOfRange(populations,r*POP_LONGS,(r+1)*POP_LONGS),
                    "ids",Arrays.copyOfRange(populationIds,start,start+count),"positions",Arrays.copyOfRange(populationPositions,start,start+count),
                    "lights",Arrays.copyOfRange(populationLights,start,start+count)));
        }
        result.put("populationColumns",POP_COLUMNS);result.put("populations",List.copyOf(batches));
        result.put("totalPopulations",populationCount);result.put("overwrittenPopulations",Math.max(0,populationCount-POPULATION_CAPACITY));
        result.put("gpuQuery",gpu==null?closedGpu:gpu.snapshot());
        result.put("scope","GameRenderer.render wall/cadence and nonblocking sampled GPU commands; not presentation. Matrix is actual prepared TAA VP. Model events are compilation/cache observations, not GPU-visible quads. Population IDs describe the actual uploaded vanilla spruce detail batch, not authored BOUGH geometry.");
        return Collections.unmodifiableMap(result);
    }
}
