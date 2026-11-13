// Collects bounded opt-in timing evidence with fixed primitive storage and a single immutable completion snapshot.
package com.dark.winter.diagnostics.performance;

import java.util.*;
import java.util.function.*;

public final class PerformanceCapture {
    public static final int MAX_CAPACITY = 65536;
    public static final long MAX_DURATION_NANOS = 60_000_000_000L;
    private static final List<String> METRICS = List.of("frameInterval","renderWall","renderCpu",
            "serverTickWall","serverTickInterval","gpuRender","clientTickWall");
    private final Map<String,String> metadata;
    private final Map<String,PerformanceSamples> samples = new LinkedHashMap<>();
    private final Map<String,String> unavailable = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Supplier<Map<String,Object>> jvmReader;
    private final Map<String,Object> jvmBefore;
    private final int capacity;
    private final long maxDuration, started;
    private final long[] gpuIds, gpuIdSet;
    private final int[] queued, freeBuffers;
    private final PerformanceTimeline timeline;
    private long acceptedObservation;
    private int queueCount;
    private long rejectedAfterStop, rejectedExpired, rejectedQueue, droppedQueue, rejectedGpuIds, nonAdvancingTicks, invalidClock;
    private boolean open = true;
    private Map<String,Object> finished;

    public PerformanceCapture(Map<String,String> metadata,int capacity,long maxDurationNanos) {
        this(metadata,capacity,maxDurationNanos,System::nanoTime,JvmSnapshot::capture);
    }
    PerformanceCapture(Map<String,String> metadata,int capacity,long maxDurationNanos,
                       LongSupplier clock,Supplier<Map<String,Object>> jvmReader) {
        if (capacity<1 || capacity>MAX_CAPACITY) throw new IllegalArgumentException("Capacity must be 1..65536");
        if (maxDurationNanos<1 || maxDurationNanos>MAX_DURATION_NANOS)
            throw new IllegalArgumentException("Duration must be positive and at most 60 seconds");
        this.metadata = metadata(metadata); this.capacity=capacity; this.maxDuration=maxDurationNanos;
        this.clock=Objects.requireNonNull(clock); this.jvmReader=Objects.requireNonNull(jvmReader);
        METRICS.forEach(name->samples.put(name,new PerformanceSamples(capacity)));
        queued=new int[capacity]; freeBuffers=new int[capacity]; gpuIds=new long[capacity];
        timeline=new PerformanceTimeline(capacity,gpuIds);
        int slots=2; while(slots<capacity*2) slots<<=1;
        gpuIdSet=new long[slots]; Arrays.fill(gpuIdSet,-1);
        jvmBefore=PerformanceSamples.freeze(jvmReader.get()); started=clock.getAsLong();
        timeline.startAt(started);
    }
    public synchronized boolean isOpen() { return open && !expired(); }
    public synchronized boolean expired() { return clock.getAsLong()-started>=maxDuration; }
    public synchronized void frame(long intervalNs,long renderWallNs,long renderCpuNs) {
        if (!accept()) return;
        observed(PerformanceTimeline.FRAME,intervalNs,PerformanceTimeline.LEGACY_FRAME,-1,-1);
        int wall=observed(PerformanceTimeline.RENDER,renderWallNs,PerformanceTimeline.LEGACY_FRAME,-1,-1);
        observed(PerformanceTimeline.CPU,renderCpuNs,PerformanceTimeline.LEGACY_FRAME,-1,wall);
    }
    public synchronized void render(long wallNs,long cpuNs) {
        if (!accept()) return;
        int wall=observed(PerformanceTimeline.RENDER,wallNs,PerformanceTimeline.LEGACY_RENDER,-1,-1);
        observed(PerformanceTimeline.CPU,cpuNs,PerformanceTimeline.LEGACY_RENDER,-1,wall);
    }
    public synchronized void tick(long intervalNs,long tickWallNs,boolean advancing) {
        if (!accept()) return;
        if (!advancing) { nonAdvancingTicks++; return; }
        observed(PerformanceTimeline.SERVER_INTERVAL,intervalNs,PerformanceTimeline.LEGACY_TICK,-1,-1);
        observed(PerformanceTimeline.SERVER_WALL,tickWallNs,PerformanceTimeline.LEGACY_TICK,-1,-1);
    }
    public synchronized void frameAt(long previousSystem,long nowSystem,long renderFrameId) {
        if(!accept())return;
        span(PerformanceTimeline.FRAME,nowSystem-previousSystem,PerformanceTimeline.FRAME_AT,previousSystem,nowSystem,renderFrameId,false);
    }
    public synchronized void renderAt(long wallNs,long cpuNs,long startSystem,long endSystem,long renderFrameId) {
        if(!accept())return;
        int wall=span(PerformanceTimeline.RENDER,wallNs,PerformanceTimeline.RENDER_AT,startSystem,endSystem,renderFrameId,true);
        observed(PerformanceTimeline.CPU,cpuNs,PerformanceTimeline.RENDER_AT,renderFrameId,wall);
    }
    public synchronized void tickAt(long intervalNs,long startSystem,long endSystem,boolean advancing) {
        if(!accept())return;
        if(!advancing){nonAdvancingTicks++;return;}
        if(intervalNs!=-1){PerformanceSamples metric=samples.get("serverTickInterval");int row=metric.count();if(metric.add(intervalNs))timeline.cadence(row,acceptedObservation,startSystem,intervalNs);}
        span(PerformanceTimeline.SERVER_WALL,endSystem-startSystem,PerformanceTimeline.TICK_AT,startSystem,endSystem,-1,false);
    }
    public synchronized void clientTickAt(long startSystem,long endSystem) {
        if(!accept())return;
        span(PerformanceTimeline.CLIENT_TICK,endSystem-startSystem,PerformanceTimeline.CLIENT_TICK_AT,startSystem,endSystem,-1,false);
    }
    public synchronized void gpu(long frameId,long durationNs) {
        if (!accept()) return;
        if (frameId<0) { rejectedGpuIds++; return; }
        int slot=(int)((frameId^(frameId>>>32))*0x9E3779B9L)&(gpuIdSet.length-1);
        while(gpuIdSet[slot]!=-1 && gpuIdSet[slot]!=frameId) slot=(slot+1)&(gpuIdSet.length-1);
        if (gpuIdSet[slot]==frameId) { rejectedGpuIds++; return; }
        PerformanceSamples gpu=samples.get("gpuRender"); int index=gpu.count();
        if (gpu.add(durationNs)) { gpuIdSet[slot]=frameId; gpuIds[index]=frameId;timeline.gpu(index,acceptedObservation); }
    }
    public synchronized void queue(int queued,int freeBuffers) {
        if (!accept()) return;
        queueRecord(queued,freeBuffers,0,-1,false);
    }
    public synchronized void queueAt(int queued,int freeBuffers,long observedSystem,long renderFrameId) {
        if(!accept())return;
        queueRecord(queued,freeBuffers,observedSystem,renderFrameId,true);
    }
    private void queueRecord(int queued,int freeBuffers,long observedSystem,long renderFrameId,boolean supplied) {
        if (queued<0 || freeBuffers<0) { rejectedQueue++; return; }
        if (queueCount==capacity) { droppedQueue++; return; }
        this.queued[queueCount]=queued; this.freeBuffers[queueCount]=freeBuffers;
        timeline.queue(queueCount,acceptedObservation,observedSystem,renderFrameId,supplied);queueCount++;
    }
    public synchronized void unavailable(String metric,String reason) {
        validate(metric,64,"Metric"); validate(reason,256,"Reason");
        if (unavailable.size()>=32 && !unavailable.containsKey(metric))
            throw new IllegalArgumentException("At most 32 unavailable metrics");
        if (finished==null) unavailable.put(metric,reason);
    }
    public synchronized Map<String,Object> finish(String reason) {
        if (finished!=null) return finished;
        validate(reason,128,"Completion reason"); open=false;
        long finishedAt=clock.getAsLong(),elapsed=finishedAt-started;
        if (elapsed<0) { invalidClock++; elapsed=0; }
        var metrics=new LinkedHashMap<String,Object>();
        var series=new LinkedHashMap<String,Object>();
        for (String name:METRICS) {
            PerformanceSamples values=samples.get(name);
            var summary=new LinkedHashMap<>(values.statistics());
            if(unavailable.containsKey(name)) {
                summary.clear(); summary.put("status","UNAVAILABLE"); summary.put("reason",unavailable.get(name));
                summary.put("unit","nanoseconds"); summary.put("count",values.count());
                summary.put("rejected",values.rejected()); summary.put("dropped",values.dropped());
            } else if(values.count()==0) summary.put("reason","No positive samples observed");
            metrics.put(name,summary); series.put(name,values.series());
        }
        series.put("gpuFrameIds",PerformanceTimeline.longSnapshot(gpuIds,samples.get("gpuRender").count()));
        var queueSeries=new ArrayList<Map<String,Integer>>(queueCount);
        for(int i=0;i<queueCount;i++) queueSeries.add(Map.of("queued",queued[i],"freeBuffers",freeBuffers[i]));
        series.put("sectionCompileQueue",queueSeries);
        var counters=new LinkedHashMap<String,Object>();
        counters.put("rejectedAfterStop",rejectedAfterStop); counters.put("rejectedExpired",rejectedExpired);
        counters.put("rejectedQueue",rejectedQueue); counters.put("droppedQueue",droppedQueue);
        counters.put("rejectedGpuIds",rejectedGpuIds); counters.put("nonAdvancingTicks",nonAdvancingTicks);
        counters.put("invalidClock",invalidClock); counters.put("queueSamples",queueCount);
        var result=new LinkedHashMap<String,Object>();
        result.put("schema",1); result.put("metadata",metadata); result.put("metrics",metrics); result.put("series",series);
        result.put("timeline",timeline.snapshot(samples,queueCount,rejectedQueue,droppedQueue,finishedAt));
        result.put("jvmBefore",jvmBefore); result.put("jvmAfter",jvmReader.get());
        result.put("counters",counters); result.put("unavailable",unavailable); result.put("reason",reason);
        result.put("elapsedNanos",elapsed); result.put("maxDurationNanos",maxDuration); result.put("capacity",capacity);
        boolean sdk=isSdk(metadata.get("harness"));
        boolean tpsComparable=!sdk && nonAdvancingTicks==0 && invalidClock==0
                && !unavailable.containsKey("serverTickInterval") && !unavailable.containsKey("serverTickWall")
                && Collections.disjoint(unavailable.keySet(),Set.of("configChanged","sceneChanged","worldChanged","clientInterrupted","backendFallback"))
                && "normal".equalsIgnoreCase(metadata.get("simulationState"))
                && samples.get("serverTickInterval").count()>=2;
        result.put("tpsComparable",tpsComparable);
        Object tickMean=((Map<?,?>)metrics.get("serverTickInterval")).get("meanNanos");
        result.put("observedTickStartsPerSecond",tpsComparable && tickMean instanceof Number number
                ? 1_000_000_000d/number.doubleValue() : null);
        result.put("observedTickRateScope","1e9 divided by the mean valid tick-start interval; observed wall-clock starts, not a simulation TPS claim");
        result.put("timingScope","Frame intervals include pacing; render spans are host wall/CPU time, not GPU duration. Server spans wrap MinecraftServer.tickServer(BooleanSupplier), excluding its outer pacing wait.");
        result.put("sdkWallClockScope",sdk?"Harness controls tick scheduling; wall-clock TPS is not comparable.":"No SDK scheduling was declared.");
        finished=PerformanceSamples.freeze(result); return finished;
    }
    private int observed(int channel,long value,int source,long renderFrameId,int renderWallRow) {
        // Only optional durations use -1; signed System timestamps and offsets never use this sentinel.
        if(value==-1)return -1;
        PerformanceSamples metric=samples.get(METRICS.get(channel));int row=metric.count();
        if(!metric.add(value))return -1;
        if(channel==PerformanceTimeline.CPU)timeline.cpu(row,acceptedObservation,source,renderFrameId,renderWallRow);
        else timeline.legacy(channel,row,acceptedObservation,source);
        return row;
    }
    private int span(int channel,long duration,int source,long startSystem,long endSystem,long renderFrameId,boolean optional) {
        if(optional&&duration==-1)return -1;
        PerformanceSamples metric=samples.get(METRICS.get(channel));int row=metric.count();
        if(!metric.add(duration))return -1;
        timeline.span(channel,row,acceptedObservation,source,startSystem,endSystem,duration,renderFrameId);return row;
    }
    private boolean accept() {
        if(!open) { rejectedAfterStop++; return false; }
        acceptedObservation=clock.getAsLong();long elapsed=acceptedObservation-started;
        if(elapsed<0) { invalidClock++; return false; }
        if(elapsed>=maxDuration) { rejectedExpired++; return false; }
        return true;
    }
    static boolean isSdk(String harness) {
        String text=harness==null?"":harness.toLowerCase(Locale.ROOT);
        return text.contains("sdk") || text.contains("gametest");
    }
    private static Map<String,String> metadata(Map<String,String> input) {
        Objects.requireNonNull(input,"metadata");
        if(input.size()>48) throw new IllegalArgumentException("At most 48 metadata fields");
        var copy=new LinkedHashMap<String,String>();
        input.forEach((key,value)->{validate(key,64,"Metadata key");validate(value,256,"Metadata value");copy.put(key,value);});
        return Collections.unmodifiableMap(copy);
    }
    private static void validate(String value,int length,String label) {
        if(value==null || value.isBlank() || value.length()>length || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label+" must be bounded printable text");
    }
}
