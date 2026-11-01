// Reads process-wide JVM counters at capture boundaries; buffer pools are estimates and never stand in for total native or GPU memory.
package com.dark.winter.diagnostics.performance;

import java.lang.management.*;
import java.util.*;

public final class JvmSnapshot {
    private JvmSnapshot() {}
    public static Map<String,Object> capture() {
        var result = new LinkedHashMap<String,Object>();
        result.put("scope","whole JVM process; integrated client and server share these counters");
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            result.put("heap",usage(memory.getHeapMemoryUsage()));
            result.put("nonHeap",usage(memory.getNonHeapMemoryUsage()));
        } catch (RuntimeException unavailable) { result.put("memoryUnavailable",unavailable.getClass().getSimpleName()); }
        var collectors = new ArrayList<Map<String,Object>>();
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                var item = new LinkedHashMap<String,Object>();
                item.put("name",bean.getName()); item.put("valid",bean.isValid());
                item.put("collectionCount",available(bean.getCollectionCount()));
                item.put("approximateCollectionElapsedMillis",available(bean.getCollectionTime()));
                collectors.add(item);
            }
        } catch (RuntimeException unavailable) { result.put("gcUnavailable",unavailable.getClass().getSimpleName()); }
        result.put("collectors",collectors);
        result.put("gcTimeScope","approximate accumulated collection elapsed time, not stop-the-world pause time");
        var buffers = new ArrayList<Map<String,Object>>();
        try {
            for (BufferPoolMXBean bean : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                var item = new LinkedHashMap<String,Object>();
                item.put("name",bean.getName()); item.put("estimatedCount",available(bean.getCount()));
                item.put("estimatedCapacityBytes",available(bean.getTotalCapacity()));
                item.put("estimatedMemoryUsedBytes",available(bean.getMemoryUsed())); buffers.add(item);
            }
        } catch (RuntimeException unavailable) { result.put("bufferPoolsUnavailable",unavailable.getClass().getSimpleName()); }
        result.put("bufferPools",buffers);
        result.put("nativeMemoryBytes",null); result.put("gpuMemoryBytes",null);
        result.put("nativeMemoryUnavailable","MXBean buffer pools do not measure all JNI, driver, allocator or GPU allocations");
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                result.put("processCpuNanos",available(os.getProcessCpuTime()));
                result.put("committedVirtualMemoryBytes",available(os.getCommittedVirtualMemorySize()));
                result.put("committedVirtualMemoryScope","virtual address commitment; not resident memory");
            }
        } catch (RuntimeException unavailable) { result.put("processCountersUnavailable",unavailable.getClass().getSimpleName()); }
        return PerformanceSamples.freeze(result);
    }
    private static Long available(long value) { return value<0?null:value; }
    private static Map<String,Object> usage(MemoryUsage usage) {
        var result = new LinkedHashMap<String,Object>();
        result.put("usedBytes",available(usage.getUsed())); result.put("committedBytes",available(usage.getCommitted()));
        result.put("maxBytes",available(usage.getMax())); return result;
    }
}
