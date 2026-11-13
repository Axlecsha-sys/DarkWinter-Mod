// Keeps bounded primitive correlation columns and publishes immutable lazy-boxed snapshots after capture closes.
package com.dark.winter.diagnostics.performance;

import java.util.*;

final class PerformanceTimeline {
    static final int FRAME=0,RENDER=1,CPU=2,SERVER_WALL=3,SERVER_INTERVAL=4,GPU=5,CLIENT_TICK=6,QUEUE=7;
    static final int LEGACY_FRAME=0,LEGACY_RENDER=1,LEGACY_TICK=2,GPU_READBACK=3,LEGACY_QUEUE=4,
            FRAME_AT=5,RENDER_AT=6,TICK_AT=7,CLIENT_TICK_AT=8,QUEUE_AT=9;
    private static final int OBSERVED_ONLY=0,EXACT_HOST_SPAN=1,DERIVED_CADENCE=2,INVALID_ANCHOR=3,CPU_DURATION_ONLY=4,HAS_ID=128;
    private static final String[] NAMES={"frameInterval","renderWall","renderCpu","serverTickWall","serverTickInterval","gpuRender","clientTickWall","sectionCompileQueue"};
    private static final Map<String,String> SOURCES=Map.ofEntries(
            Map.entry("0","legacy frame API; capture-clock observation only"),Map.entry("1","legacy render API; capture-clock observation only"),
            Map.entry("2","legacy tick API; capture-clock observation only"),Map.entry("3","GPU readback callback; capture-clock observation only"),
            Map.entry("4","legacy queue API; capture-clock observation only"),Map.entry("5","frameAt supplied previous/current System timestamps"),
            Map.entry("6","renderAt supplied host span and separate CPU duration"),Map.entry("7","tickAt supplied host span; cadence start derived from supplied interval"),
            Map.entry("8","clientTickAt supplied complete host span"),Map.entry("9","queueAt supplied System observation timestamp"));
    private static final Map<String,String> VALIDITY=Map.of("0","OBSERVED_UNANCHORED","1","EXACT_HOST_SPAN","2","DERIVED_TICK_START_CADENCE","3","INVALID_SUPPLIED_ANCHOR","4","CPU_DURATION_NOT_HOST_ELAPSED");
    private final int capacity;
    private final long[][] observed=new long[8][],starts=new long[8][],ends=new long[8][],ids=new long[8][];
    private final byte[][] flags=new byte[8][];
    private final int[] counts=new int[8],cpuRenderRows;
    private final long[] invalidAnchors=new long[8],unlinkedIds=new long[8];
    private long epoch;
    PerformanceTimeline(int capacity,long[] gpuIds){
        this.capacity=capacity;
        for(int i=0;i<8;i++){observed[i]=new long[capacity];flags[i]=new byte[capacity];}
        for(int i:new int[]{FRAME,RENDER,SERVER_WALL,SERVER_INTERVAL,CLIENT_TICK}){starts[i]=new long[capacity];ends[i]=new long[capacity];}
        for(int i:new int[]{FRAME,RENDER,CPU,QUEUE})ids[i]=new long[capacity];
        ids[GPU]=gpuIds;cpuRenderRows=new int[capacity];
    }
    void startAt(long epoch){this.epoch=epoch;}
    void span(int channel,int ordinal,long observation,int source,long start,long end,long duration,long renderId){
        boolean anchored=end-start>0&&end-start==duration&&observation-end>=0;
        store(channel,ordinal,observation,source,anchored?EXACT_HOST_SPAN:INVALID_ANCHOR,renderId);
        starts[channel][ordinal]=start-epoch;ends[channel][ordinal]=end-epoch;
    }
    void cadence(int ordinal,long observation,long tickStart,long interval){
        boolean anchored=interval>0&&observation-tickStart>=0;
        store(SERVER_INTERVAL,ordinal,observation,TICK_AT,anchored?DERIVED_CADENCE:INVALID_ANCHOR,-1);
        // This is explicitly derived tick-start cadence, not an independently observed previous tick endpoint.
        starts[SERVER_INTERVAL][ordinal]=(tickStart-interval)-epoch;ends[SERVER_INTERVAL][ordinal]=tickStart-epoch;
    }
    void legacy(int channel,int ordinal,long observation,int source){store(channel,ordinal,observation,source,OBSERVED_ONLY,-1);}
    void cpu(int ordinal,long observation,int source,long renderId,int renderWallRow){
        store(CPU,ordinal,observation,source,CPU_DURATION_ONLY,renderId);cpuRenderRows[ordinal]=renderWallRow;
    }
    void gpu(int ordinal,long observation){store(GPU,ordinal,observation,GPU_READBACK,OBSERVED_ONLY,ids[GPU][ordinal]);}
    void queue(int ordinal,long acceptedObservation,long suppliedObservation,long renderId,boolean supplied){
        int validity=supplied&&acceptedObservation-suppliedObservation<0?INVALID_ANCHOR:OBSERVED_ONLY;
        store(QUEUE,ordinal,supplied?suppliedObservation:acceptedObservation,supplied?QUEUE_AT:LEGACY_QUEUE,validity,renderId);
    }
    private void store(int channel,int ordinal,long observation,int source,int validity,long renderId){
        if(ordinal!=counts[channel]||ordinal>=capacity)throw new IllegalStateException("Timeline/sample ordinal mismatch");
        observed[channel][ordinal]=observation-epoch;
        boolean hasId=ids[channel]!=null&&renderId>=0;
        flags[channel][ordinal]=(byte)(source|(validity<<4)|(hasId?HAS_ID:0));
        if(ids[channel]!=null)ids[channel][ordinal]=renderId;
        if(validity==INVALID_ANCHOR)invalidAnchors[channel]++;
        if(ids[channel]!=null&&!hasId)unlinkedIds[channel]++;
        counts[channel]++;
    }
    Map<String,Object> snapshot(Map<String,PerformanceSamples> metrics,int queueCount,long rejectedQueue,long droppedQueue,long finishedAt){
        var channels=new LinkedHashMap<String,Object>();
        for(int channel=0;channel<8;channel++){
            int count=counts[channel];PerformanceSamples metric=channel==QUEUE?null:metrics.get(NAMES[channel]);
            if(count!=(metric==null?queueCount:metric.count()))throw new IllegalStateException("Finished timeline/sample alignment mismatch");
            byte[] flagCopy=Arrays.copyOf(flags[channel],count);
            var out=new LinkedHashMap<String,Object>();out.put("count",count);
            out.put("rejected",metric==null?rejectedQueue:metric.rejected());out.put("dropped",metric==null?droppedQueue:metric.dropped());
            out.put("invalidSuppliedAnchors",invalidAnchors[channel]);out.put("unlinkedRenderFrameIds",unlinkedIds[channel]);
            out.put("sourceCodes",new CodeColumn(flagCopy,false));out.put("validityCodes",new CodeColumn(flagCopy,true));
            out.put("observedOffsetsNanos",new LongColumn(Arrays.copyOf(observed[channel],count),null,false));
            if(starts[channel]!=null){
                out.put("startOffsetsNanos",new LongColumn(Arrays.copyOf(starts[channel],count),flagCopy,false));
                out.put("endOffsetsNanos",new LongColumn(Arrays.copyOf(ends[channel],count),flagCopy,false));
            }
            if(ids[channel]!=null)out.put("renderFrameIds",new LongColumn(Arrays.copyOf(ids[channel],count),flagCopy,true));
            if(channel==CPU)out.put("renderWallOrdinals",new IndexColumn(Arrays.copyOf(cpuRenderRows,count)));
            out.put("scope",scope(channel));channels.put(NAMES[channel],out);
        }
        var result=new LinkedHashMap<String,Object>();result.put("schema",1);result.put("clock","System.nanoTime");result.put("epochSystemNanos",epoch);
        result.put("finishedSystemNanos",finishedAt);result.put("finishedOffsetNanos",finishedAt-epoch);result.put("finishClockValid",finishedAt-epoch>=0);
        result.put("offsetUnit","nanoseconds");result.put("offsetSemantics","Signed subtraction modulo 2^64, valid for elapsed spans shorter than 2^63 ns. Negative offsets may precede capture; zero and -1 are valid timestamps/offsets, never unavailable sentinels. Endpoint absence is null plus explicit source/validity.");
        result.put("sourceCodes",SOURCES);result.put("validityCodes",VALIDITY);result.put("channels",channels);
        result.put("alignment","Each channel ordinal equals its accepted legacy series ordinal. Rejected/dropped metric samples have no timeline row; the exact metric counters are mirrored. Omitted optional duration -1 is not an attempt. API gate rejections remain in capture counters. No chronology is sorted.");
        result.put("boundaryPolicy","Only completed endpoint API calls accepted before finish are included. In-flight spans are not known to this endpoint-only API; completions arriving after finish are excluded. The immutable result and its counters are frozen at finish and do not claim to count future late calls.");
        result.put("inFlightSpanCount","NOT_TRACKED");
        result.put("renderIdSemantics","Nonnegative IDs are caller-provided links only. Null means no eligible render ID, including caller sentinel -1; it does not invalidate a System timestamp or a host span.");
        result.put("primitivePayloadBytes",188L*capacity+160L);result.put("payloadScope","Preallocated timeline column arrays plus 160 bytes of fixed primitive counter arrays, excluding object headers, existing metric/queue/GPU-ID storage and immutable completion prefix copies. No per-sample objects are allocated.");
        return result;
    }
    private static String scope(int channel){return switch(channel){
        case FRAME->"Supplied presented-frame host endpoints for frameAt; legacy frame durations are observation-only.";
        case RENDER->"GameRenderer host wall span from supplied endpoints, independent of CPU duration and GPU execution.";
        case CPU->"Thread CPU duration only; no host start/end. Optional renderWall ordinal and renderFrame ID identify the associated host observation, not CPU elapsed endpoints.";
        case SERVER_WALL->"Complete supplied server tick host span; may start before capture. Excludes outer pacing only when caller scopes tickServer that way.";
        case SERVER_INTERVAL->"For tickAt, reported tick-start interval derives previousStart=currentStart-interval and currentStart=supplied wall start; not an independently observed prior endpoint. Legacy cadence is unanchored.";
        case GPU->"GPU device duration is retained in legacy gpuRender. Host observedOffset marks readback delivery only; no GPU start/end is reconstructed from readback or duration.";
        case CLIENT_TICK->"Complete supplied Minecraft client tick host span, including work outside GameRenderer.render; no per-render ID is implied.";
        default->"Queue/free-buffer values align with legacy sectionCompileQueue; queueAt uses its supplied observation point, legacy queue uses capture-clock observation only.";
    };}
    static boolean immutableColumn(Object value){return value instanceof LongColumn||value instanceof CodeColumn||value instanceof IndexColumn;}
    static List<Long> longSnapshot(long[] values,int count){return new LongColumn(Arrays.copyOf(values,count),null,false);}
    private static final class LongColumn extends AbstractList<Long> implements RandomAccess {
        private final long[] values;private final byte[] flags;private final boolean ids;
        private LongColumn(long[] values,byte[] flags,boolean ids){this.values=values;this.flags=flags;this.ids=ids;}
        @Override public int size(){return values.length;}
        @Override public Long get(int index){Objects.checkIndex(index,values.length);if(flags!=null){int bits=flags[index]&255;if(ids?(bits&HAS_ID)==0:(bits&15)<FRAME_AT||(bits&15)>CLIENT_TICK_AT)return null;}return values[index];}
    }
    private static final class CodeColumn extends AbstractList<Integer> implements RandomAccess {
        private final byte[] flags;private final boolean validity;
        private CodeColumn(byte[] flags,boolean validity){this.flags=flags;this.validity=validity;}
        @Override public int size(){return flags.length;}
        @Override public Integer get(int index){Objects.checkIndex(index,flags.length);return validity?((flags[index]&255)>>>4)&7:flags[index]&15;}
    }
    private static final class IndexColumn extends AbstractList<Integer> implements RandomAccess {
        private final int[] indices;
        private IndexColumn(int[] indices){this.indices=indices;}
        @Override public int size(){return indices.length;}
        @Override public Integer get(int index){Objects.checkIndex(index,indices.length);return indices[index]<0?null:indices[index];}
    }
}
