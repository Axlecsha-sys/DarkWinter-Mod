// Stores bounded nanosecond samples and calculates explicitly named nearest-rank summaries without changing chronological evidence.
package com.dark.winter.diagnostics.performance;

import java.util.*;

public final class PerformanceSamples {
    private final long[] values;
    private int count;
    private long rejected, dropped;

    public PerformanceSamples(int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Capacity must be 1..65536");
        values = new long[capacity];
    }
    public synchronized boolean add(long nanos) {
        if (nanos <= 0) { rejected++; return false; }
        if (count == values.length) { dropped++; return false; }
        values[count++] = nanos; return true;
    }
    public synchronized int count() { return count; }
    public synchronized long rejected() { return rejected; }
    public synchronized long dropped() { return dropped; }
    public synchronized List<Long> series() {
        List<Long> copy = new ArrayList<>(count);
        for (int i=0; i<count; i++) copy.add(values[i]);
        return List.copyOf(copy);
    }
    public synchronized Map<String,Object> statistics() {
        var result = new LinkedHashMap<String,Object>();
        result.put("count",count); result.put("rejected",rejected); result.put("dropped",dropped);
        result.put("unit","nanoseconds"); result.put("quantileMethod","nearest-rank");
        if (count == 0) { result.put("status","NO_SAMPLES"); return freeze(result); }
        long[] sorted = Arrays.copyOf(values,count); Arrays.sort(sorted);
        double sum = 0;
        for (long value : sorted) sum += value;
        int slowCount = Math.max(1,(int)Math.ceil(count * .01));
        double slowSum = 0;
        for (int i=count-slowCount;i<count;i++) slowSum += sorted[i];
        double slowMean = slowSum / slowCount;
        result.put("status","AVAILABLE"); result.put("meanNanos",sum/count);
        result.put("minNanos",sorted[0]); result.put("maxNanos",sorted[count-1]);
        result.put("p50Nanos",rank(sorted,.5)); result.put("p95Nanos",rank(sorted,.95)); result.put("p99Nanos",rank(sorted,.99));
        result.put("slowestOnePercentCount",slowCount);
        result.put("slowestOnePercentDefinition","Mean of ceil(N*0.01) largest durations; reciprocal is 1e9/meanNanos, FPS only for frame intervals");
        result.put("slowestOnePercentMeanNanos",slowMean);
        result.put("reciprocalSlowestOnePercentMeanPerSecond",1_000_000_000d/slowMean);
        return freeze(result);
    }
    private static long rank(long[] sorted,double q) { return sorted[Math.max(0,(int)Math.ceil(sorted.length*q)-1)]; }

    @SuppressWarnings("unchecked")
    static <T> T freeze(T value) {
        if (value instanceof Map<?,?> map) {
            var copy = new LinkedHashMap<Object,Object>();
            map.forEach((key,item)->copy.put(key,freeze(item)));
            return (T)Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (Object item:list) copy.add(freeze(item));
            return (T)Collections.unmodifiableList(copy);
        }
        return value;
    }
}
