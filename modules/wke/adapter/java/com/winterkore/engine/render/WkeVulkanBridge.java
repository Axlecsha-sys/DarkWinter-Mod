// Minecraft host adapter: native-owned pipelines share the host device, layouts and GPU-safe retirement.
package com.winterkore.engine.render;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.vulkan.VK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;

public final class WkeVulkanBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(WkeVulkanBridge.class);
    private record Context(VkDevice device, long handle, Set<Long> pipelines) { }
    // Identity, not raw handles: a later Vulkan device may reuse a retired device address.
    private static final Set<VkDevice> CLOSING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ConcurrentHashMap<Long, Context> CONTEXTS = new ConcurrentHashMap<>();
    private static final long[] RETIRED = new long[WkeVulkanNative.STATS_WORDS];
    private static final AtomicLong HOST_DRAWS = new AtomicLong();
    private static final AtomicLong NATIVE_DISPATCHES = new AtomicLong();
    private static volatile String reason = "";
    private WkeVulkanBridge() { }

    public static boolean isWkePipelineName(String name) {
        return name != null && (name.startsWith("wke/") || name.startsWith("wke:") || name.startsWith("winterkore:"));
    }
    public static boolean available() { return WkeVulkanNative.available(); }
    public static String statusReason() {
        return reason.isEmpty() ? (available() ? "" : WkeVulkanNative.failureReason()) : reason;
    }
    private static synchronized Context context(VulkanDevice host) {
        long device = host.vkDevice().address();
        Context found = CONTEXTS.get(device);
        if (CLOSING.contains(host.vkDevice())) throw new IllegalStateException("WKE host Vulkan device is closing");
        if (found != null) {
            if (found.device() != host.vkDevice()) throw new IllegalStateException("WKE host device identity changed while its context is live");
            return found;
        }
        long gdpa = VK.getFunctionProvider().getFunctionAddress("vkGetDeviceProcAddr");
        long handle = WkeVulkanNative.attach(device, gdpa);
        Context created = new Context(host.vkDevice(), handle, ConcurrentHashMap.newKeySet());
        CONTEXTS.put(device, created);
        return created;
    }
    /** Called synchronously while every pointer in the LWJGL MemoryStack create-info is alive. */
    public static synchronized boolean tryCreateGraphics(VulkanDevice host, String name, long cache,
            VkGraphicsPipelineCreateInfo.Buffer infos, LongBuffer output) {
        if ((!isWkePipelineName(name) && !WkeRendererRuntime.allowNativePipelineCreation())
                || infos.remaining() != 1 || output.remaining() < 1 || !available()) return false;
        // Do not fall through to a host vkCreate call on a device already entering destruction.
        if (CLOSING.contains(host.vkDevice())) throw new IllegalStateException("WKE host Vulkan device is closing");
        try {
            Context ctx = context(host);
            long pipeline = WkeVulkanNative.createGraphics(ctx.handle(), cache, infos.address());
            ctx.pipelines().add(pipeline);
            output.put(output.position(), pipeline);
            return true;
        } catch (RuntimeException failure) {
            reason = failure.getMessage();
            return false;
        }
    }
    public static boolean owns(VulkanDevice host, long pipeline) {
        Context ctx = CONTEXTS.get(host.vkDevice().address());
        return ctx != null && ctx.device() == host.vkDevice() && ctx.pipelines().contains(pipeline);
    }
    public static synchronized boolean tryDestroyGraphics(VkDevice host, long pipeline) {
        Context ctx = CONTEXTS.get(host.address());
        if (ctx == null || ctx.device() != host || !ctx.pipelines().contains(pipeline)) return false;
        WkeVulkanNative.destroyGraphics(ctx.handle(), pipeline);
        ctx.pipelines().remove(pipeline);
        return true;
    }
    public static int recordTerrain(VulkanDevice host, long pipeline, long commandBuffer,
            MemorySegment packets, int count) {
        Context ctx = CONTEXTS.get(host.vkDevice().address());
        if (ctx == null || ctx.device() != host.vkDevice() || !ctx.pipelines().contains(pipeline)) return -2;
        int code = WkeVulkanNative.recordTerrain(ctx.handle(), commandBuffer, packets, count);
        if (code != 0) reason = "Native terrain rejected before commands: " + code;
        else {
            NATIVE_DISPATCHES.incrementAndGet();
            WkeRendererRuntime.onNativeDispatchAccepted();
        }
        return code;
    }
    public static void hostDraw() {
        HOST_DRAWS.incrementAndGet();
        WkeRendererRuntime.onHostDispatchFallback();
    }
    public static long recordedHostDraws() { return HOST_DRAWS.get(); }
    public static long recordedNativeDispatches() { return NATIVE_DISPATCHES.get(); }
    public static int ownedPipelineCount() {
        int count = 0;
        for (Context ctx : CONTEXTS.values()) count += ctx.pipelines().size();
        return count;
    }
    public static synchronized long[] snapshotStats() {
        long[] totals = RETIRED.clone();
        totals[0] = WkeVulkanNative.ABI_VERSION;
        for (Context ctx : CONTEXTS.values()) {
            long[] values = WkeVulkanNative.copyStats(ctx.handle());
            for (int i = 1; i < totals.length; i++) {
                if (i == 14) { if (values[i] != 0) totals[i] = values[i]; }
                else totals[i] += values[i];
            }
        }
        return totals;
    }
    /** VulkanDevice.close already drained graphics work in VulkanCommandEncoder.destroy before this hook. */
    public static synchronized void beforeDeviceDestroy(VkDevice device) {
        CLOSING.add(device);
        Context ctx = CONTEXTS.get(device.address());
        if (ctx == null || ctx.device() != device) return;
        CONTEXTS.remove(device.address(), ctx);
        // Normally every pipeline was already retired by the host's destruction queue.
        for (long pipeline : new ArrayList<>(ctx.pipelines())) {
            WkeVulkanNative.destroyGraphics(ctx.handle(), pipeline);
            ctx.pipelines().remove(pipeline);
        }
        long[] values = WkeVulkanNative.copyStats(ctx.handle());
        for (int i = 2; i < RETIRED.length; i++) {
            if (i == 14) { if (values[i] != 0) RETIRED[i] = values[i]; }
            else RETIRED[i] += values[i];
        }
        WkeVulkanNative.detach(ctx.handle());
        LOGGER.info("WKE_VULKAN_DEVICE_DETACHED nativeLivePipelines={} created={} destroyed={} context={}",
            values[1], values[2], values[3], ctx.handle());
    }
}
