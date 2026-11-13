// Compares measured timing summaries only when scene, configuration, sampling and declared comparison axes agree.
package com.dark.winter.diagnostics.performance;

import java.util.*;

public final class PerformanceComparison {
    public static final int MIN_CLIENT_SAMPLES=120, MIN_SERVER_SAMPLES=40;
    public static final List<String> REQUIRED_METADATA=List.of("sceneHash","configHash","sourceHash","minecraftVersion",
            "javaVersion","vmName","gcNames","hardware","backend","harness","simulationState","tickRate");
    private static final List<String> METRICS=List.of("frameInterval","renderWall","renderCpu",
            "serverTickWall","serverTickInterval","gpuRender");
    private PerformanceComparison() {}

    public static Map<String,Object> compare(Map<String,Object> before,Map<String,Object> after,String allowedAxis) {
        var reasons=new ArrayList<String>();
        var metricReasons=new LinkedHashMap<String,String>();
        var deltas=new LinkedHashMap<String,Object>();
        validatePolicy(before,after,reasons);
        String axis=allowedAxis==null?"":allowedAxis;
        if(!axis.isEmpty() && !axis.equals("backend") && !axis.equals("hardware"))
            reasons.add("Unknown comparison axis: only backend or hardware may be allowed");
        Map<?,?> a=map(before.get("metadata")), b=map(after.get("metadata"));
        Object beforeClock=map(before.get("counters")).get("invalidClock"), afterClock=map(after.get("counters")).get("invalidClock");
        if(beforeClock instanceof Number count && count.doubleValue()!=0) reasons.add("Before capture has an invalid monotonic clock");
        if(afterClock instanceof Number count && count.doubleValue()!=0) reasons.add("After capture has an invalid monotonic clock");
        capacityDrops(before,"Before",reasons); capacityDrops(after,"After",reasons);
        for(String invalidation:List.of("configChanged","sceneChanged","worldChanged","clientInterrupted","backendFallback")) {
            if(map(before.get("unavailable")).containsKey(invalidation)) reasons.add("Before capture invalidated: "+invalidation);
            if(map(after.get("unavailable")).containsKey(invalidation)) reasons.add("After capture invalidated: "+invalidation);
        }
        validateMetadata(a,"before",reasons); validateMetadata(b,"after",reasons);
        var ignored=new HashSet<String>(); ignored.add("sourceHash");
        if(axis.equals("backend")) ignored.addAll(List.of("backend","driver"));
        if(axis.equals("hardware")) ignored.addAll(List.of("hardware","gpu","cpu","driver","osName","osVersion","arch"));
        var allKeys=new TreeSet<String>();
        a.keySet().forEach(key->allKeys.add(String.valueOf(key))); b.keySet().forEach(key->allKeys.add(String.valueOf(key)));
        for(String key:allKeys) if(!ignored.contains(key) && !Objects.equals(a.get(key),b.get(key)))
            reasons.add("Metadata differs: "+key);
        boolean sdk=PerformanceCapture.isSdk(text(a.get("harness"))) || PerformanceCapture.isSdk(text(b.get("harness")));
        boolean normal="normal".equalsIgnoreCase(text(a.get("simulationState")))
                && "normal".equalsIgnoreCase(text(b.get("simulationState")));
        boolean tpsComparable=!sdk && normal && Boolean.TRUE.equals(before.get("tpsComparable"))
                && Boolean.TRUE.equals(after.get("tpsComparable"));
        Map<?,?> am=map(before.get("metrics")), bm=map(after.get("metrics"));
        for(String metric:METRICS) {
            Map<?,?> first=map(am.get(metric)), second=map(bm.get(metric));
            int ac=count(first), bc=count(second);
            if(ac<0 || bc<0) { reasons.add("Invalid sample count: "+metric); continue; }
            if(map(before.get("unavailable")).containsKey(metric) || map(after.get("unavailable")).containsKey(metric)
                    || "UNAVAILABLE".equals(first.get("status")) || "UNAVAILABLE".equals(second.get("status"))) {
                metricReasons.put(metric,"Metric is explicitly unavailable in at least one capture");
                if(metric.equals("frameInterval") || metric.equals("serverTickWall")) reasons.add("Primary timing metric unavailable: "+metric);
                if(metric.equals("serverTickInterval")) tpsComparable=false;
                continue;
            }
            if(invalidSamples(first) || invalidSamples(second)) {
                metricReasons.put(metric,"Invalid supplied timing samples were rejected");
                if(metric.equals("frameInterval") || metric.equals("serverTickWall")) reasons.add("Invalid primary timing samples: "+metric);
                if(metric.equals("serverTickInterval")) tpsComparable=false;
                continue;
            }
            if(ac==0 && bc==0) { metricReasons.put(metric,"No samples in either capture"); continue; }
            if(metric.equals("serverTickInterval") && !tpsComparable) {
                metricReasons.put(metric,sdk?"SDK controls wall-clock tick pacing":"Simulation was not continuously advancing normally");
                continue;
            }
            int minimum=metric.startsWith("server") ? (metric.equals("serverTickInterval")?MIN_SERVER_SAMPLES-1:MIN_SERVER_SAMPLES) : MIN_CLIENT_SAMPLES;
            if(ac<minimum || bc<minimum) {
                metricReasons.put(metric,"Too few samples: before="+ac+", after="+bc+", required="+minimum);
                if(metric.equals("frameInterval") || metric.equals("serverTickWall")) reasons.add("Too few primary samples: "+metric);
                continue;
            }
            if(!validSummary(first) || !validSummary(second)) { reasons.add("Non-finite or missing summary: "+metric); continue; }
            var values=new LinkedHashMap<String,Object>();
            for(String statistic:List.of("meanNanos","p50Nanos","p95Nanos","p99Nanos","maxNanos","slowestOnePercentMeanNanos")) {
                double av=((Number)first.get(statistic)).doubleValue(), bv=((Number)second.get(statistic)).doubleValue();
                values.put(statistic,Map.of("before",av,"after",bv,"delta",bv-av,"changePercent",(bv-av)/av*100d));
            }
            deltas.put(metric,values);
        }
        if(deltas.isEmpty()) reasons.add("No sufficiently sampled comparable timing metric");
        var result=new LinkedHashMap<String,Object>();
        result.put("verdict",reasons.isEmpty()?"COMPARABLE":"NOT_COMPARABLE");
        result.put("allowedAxis",axis); result.put("reasons",reasons); result.put("metricReasons",metricReasons);
        result.put("deltas",reasons.isEmpty()?deltas:Map.of()); result.put("tpsComparable",tpsComparable && reasons.isEmpty());
        result.put("scope","Descriptive captured differences; no statistical significance, total native memory or GPU-time inference from frame intervals.");
        return PerformanceSamples.freeze(result);
    }
    private static void validatePolicy(Map<String,Object> before,Map<String,Object> after,List<String> reasons) {
        if(integer(before.get("schema"),1,1)!=1) reasons.add("Missing or unsupported before schema");
        if(integer(after.get("schema"),1,1)!=1) reasons.add("Missing or unsupported after schema");
        long ac=integer(before.get("capacity"),1,PerformanceCapture.MAX_CAPACITY);
        long bc=integer(after.get("capacity"),1,PerformanceCapture.MAX_CAPACITY);
        long ad=integer(before.get("maxDurationNanos"),1,PerformanceCapture.MAX_DURATION_NANOS);
        long bd=integer(after.get("maxDurationNanos"),1,PerformanceCapture.MAX_DURATION_NANOS);
        if(ac<0 || bc<0) reasons.add("Missing or invalid capture capacity");
        else if(ac!=bc) reasons.add("Sampling capacity differs");
        if(ad<0 || bd<0) reasons.add("Missing or invalid maximum capture duration");
        else if(ad!=bd) reasons.add("Maximum capture duration differs");
    }
    private static long integer(Object value,long min,long max) {
        if(!(value instanceof Number number)) return -1;
        double n=number.doubleValue();
        return !Double.isFinite(n) || n<min || n>max || n!=Math.rint(n) ? -1 : (long)n;
    }
    private static boolean invalidSamples(Map<?,?> summary) {
        for(String key:List.of("rejected","invalid")) {
            Object value=summary.get(key);
            if(value instanceof Number number && number.doubleValue()!=0) return true;
        }
        return false;
    }
    private static void capacityDrops(Map<String,Object> report,String side,List<String> reasons) {
        Map<?,?> metrics=map(report.get("metrics"));
        for(String metric:METRICS) {
            Object dropped=map(metrics.get(metric)).get("dropped");
            if(dropped instanceof Number count && count.doubleValue()!=0)
                reasons.add(side+" capture exceeded sample capacity or has an invalid drop counter: "+metric);
        }
        Object queue=map(report.get("counters")).get("droppedQueue");
        if(queue instanceof Number count && count.doubleValue()!=0)
            reasons.add(side+" capture exceeded queue sample capacity or has an invalid drop counter");
    }
    private static void validateMetadata(Map<?,?> metadata,String side,List<String> reasons) {
        for(String key:REQUIRED_METADATA) {
            Object value=metadata.get(key);
            if(!(value instanceof String text) || text.isBlank() || text.length()>256)
                reasons.add("Missing or invalid "+side+" metadata: "+key);
        }
        for(String key:List.of("sceneHash","configHash","sourceHash")) {
            String value=text(metadata.get(key));
            if(!value.matches("[0-9a-fA-F]{64}")) reasons.add("Invalid "+side+" SHA-256 metadata: "+key);
        }
        try {
            double rate=Double.parseDouble(text(metadata.get("tickRate")));
            if(!Double.isFinite(rate) || rate<=0) reasons.add("Invalid "+side+" tickRate");
        } catch(NumberFormatException invalid) { reasons.add("Invalid "+side+" tickRate"); }
    }
    private static boolean validSummary(Map<?,?> summary) {
        for(String key:List.of("meanNanos","p50Nanos","p95Nanos","p99Nanos","maxNanos","slowestOnePercentMeanNanos")) {
            Object value=summary.get(key);
            if(!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue()<=0) return false;
        }
        return true;
    }
    private static int count(Map<?,?> summary) {
        if(summary.isEmpty()) return 0;
        Object value=summary.get("count");
        if(!(value instanceof Number number)) return -1;
        double count=number.doubleValue();
        if(!Double.isFinite(count) || count<0 || count>PerformanceCapture.MAX_CAPACITY || count!=Math.rint(count)) return -1;
        return (int)count;
    }
    private static Map<?,?> map(Object value) { return value instanceof Map<?,?> map?map:Map.of(); }
    private static String text(Object value) { return value instanceof String text?text:""; }
}
