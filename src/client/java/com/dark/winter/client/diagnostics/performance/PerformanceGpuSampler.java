// Samples bounded asynchronous GPU timestamp pairs for an explicitly enabled performance capture.
package com.dark.winter.client.diagnostics.performance;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Render-thread-owned timestamps around submitted graphics commands. The measured scope excludes
 * presentation, the DXGI bridge, compositor work and CPU frame time. Query results are never waited on.
 */
public final class PerformanceGpuSampler implements AutoCloseable {
    public static final int RING_PAIRS = 8;
    public static final int SAMPLE_EVERY_FRAMES = 8;
    public static final int MAX_POLL_PAIRS = 2;

    private static final byte FREE = 0;
    private static final byte OPEN = 1;
    private static final byte PENDING = 2;

    private final long[] frameIds = new long[RING_PAIRS];
    private final byte[] states = new byte[RING_PAIRS];
    private GpuDevice device;
    private GpuQueryPool pool;
    private CommandEncoder activeEncoder;
    private double timestampPeriod;
    private String backend = "UNKNOWN";
    private String status = "UNAVAILABLE";
    private String reason = "NOT_INITIALIZED";
    private String lastRejectedReason = "";
    private int writeCursor;
    private int pollCursor;
    private int openSlot = -1;
    private boolean closed;
    private long eligibleFrames;
    private long samplingSkips;
    private long busyDrops;
    private long submittedPairs;
    private long completedPairs;
    private long deliveredSamples;
    private long unavailablePolls;
    private long queriedPairs;
    private long invalidSamples;
    private long negativeDeltas;
    private long zeroDeltas;
    private long conversionFailures;
    private long apiFailures;
    private long consumerFailures;
    private long poolCreations;
    private long closeCalls;
    private long poolCloseAttempts;
    private long poolCloseSuccesses;
    private long poolCloseFailures;
    private long abandonedPairs;
    private int pendingAtClose;
    private boolean openAtClose;

    @FunctionalInterface
    public interface CompletedSample {
        void accept(long frameId, long gpuNanos);
    }

    public PerformanceGpuSampler() {
        RenderSystem.assertOnRenderThread();
        try {
            device = RenderSystem.getDevice();
            DeviceInfo info = device.getDeviceInfo();
            backend = info.backendName();
            timestampPeriod = info.timestampPeriod();
            if (!Double.isFinite(timestampPeriod) || timestampPeriod <= 0.0) {
                reason = "INVALID_TIMESTAMP_PERIOD";
                return;
            }
            pool = device.createTimestampQueryPool(RING_PAIRS * 2);
            poolCreations++;
            status = "AVAILABLE";
            reason = "";
        } catch (RuntimeException failure) {
            apiFailures++;
            reason = failureReason("QUERY_POOL_UNAVAILABLE", failure);
        }
    }

    /**
     * Starts the first eligible frame and every eighth eligible frame thereafter. Call end after
     * this scope even when begin returns false; an unsampled scope makes end a no-op.
     */
    public boolean begin(long frameId) {
        RenderSystem.assertOnRenderThread();
        if (!available()) {
            return false;
        }
        if (openSlot != -1) {
            fail("UNMATCHED_BEGIN", null);
            return false;
        }
        long ordinal = eligibleFrames++;
        if ((ordinal & (SAMPLE_EVERY_FRAMES - 1L)) != 0L) {
            samplingSkips++;
            return false;
        }
        int slot = -1;
        for (int offset = 0; offset < RING_PAIRS; offset++) {
            int candidate = (writeCursor + offset) % RING_PAIRS;
            if (states[candidate] == FREE) {
                slot = candidate;
                break;
            }
        }
        if (slot == -1) {
            busyDrops++;
            return false;
        }
        try {
            // Retain the encoder, matching the native timer query's begin/end command stream.
            activeEncoder = device.createCommandEncoder();
            activeEncoder.writeTimestamp(pool, slot * 2);
            frameIds[slot] = frameId;
            states[slot] = OPEN;
            openSlot = slot;
            writeCursor = (slot + 1) % RING_PAIRS;
            return true;
        } catch (RuntimeException failure) {
            fail("BEGIN_TIMESTAMP_FAILED", failure);
            return false;
        }
    }

    public void end() {
        RenderSystem.assertOnRenderThread();
        if (!available() || openSlot == -1) {
            return;
        }
        try {
            activeEncoder.writeTimestamp(pool, openSlot * 2 + 1);
            states[openSlot] = PENDING;
            submittedPairs++;
            openSlot = -1;
            activeEncoder = null;
        } catch (RuntimeException failure) {
            fail("END_TIMESTAMP_FAILED", failure);
        }
    }

    /**
     * Checks at most two pending pairs without a wait flag. A slot is reused only after both
     * timestamps are available, because Vulkan resets that query when its next write is recorded.
     */
    public void poll(CompletedSample completed) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(completed, "completed");
        if (!available()) {
            return;
        }
        int checked = 0;
        for (int visited = 0; visited < RING_PAIRS && checked < MAX_POLL_PAIRS; visited++) {
            int slot = pollCursor;
            pollCursor = (pollCursor + 1) % RING_PAIRS;
            if (states[slot] != PENDING) {
                continue;
            }
            checked++;
            queriedPairs++;
            OptionalLong[] values;
            try {
                // In 26.3 both backends check availability; Vulkan uses 64_BIT | WITH_AVAILABILITY.
                values = pool.getValues(slot * 2, 2);
            } catch (RuntimeException failure) {
                fail("QUERY_RESULT_FAILED", failure);
                return;
            }
            if (values.length != 2 || values[0] == null || values[1] == null) {
                fail("INVALID_QUERY_RESULT", null);
                return;
            }
            if (values[0].isEmpty() || values[1].isEmpty()) {
                unavailablePolls++;
                continue;
            }
            states[slot] = FREE;
            completedPairs++;
            long delta = values[1].getAsLong() - values[0].getAsLong();
            if (delta <= 0L) {
                invalidSamples++;
                if (delta < 0L) {
                    negativeDeltas++;
                    lastRejectedReason = "NEGATIVE_DELTA_OR_UNEXPOSED_TIMESTAMP_WRAP";
                } else {
                    zeroDeltas++;
                    lastRejectedReason = "ZERO_DELTA";
                }
                continue;
            }
            double nanos = delta * timestampPeriod;
            // The generic 26.3 API does not expose timestampValidBits; do not guess a wrap mask.
            if (!Double.isFinite(nanos) || nanos < 1.0 || nanos >= 0x1.0p63) {
                invalidSamples++;
                conversionFailures++;
                lastRejectedReason = "UNREPRESENTABLE_NANOSECONDS";
                continue;
            }
            long gpuNanos = Math.round(nanos);
            try {
                completed.accept(frameIds[slot], gpuNanos);
                deliveredSamples++;
            } catch (RuntimeException failure) {
                consumerFailures++;
                fail("SAMPLE_CONSUMER_FAILED", failure);
                return;
            }
            if (!available()) {
                return;
            }
        }
    }

    /** Copies the bounded render-thread state for later export; this is not a per-frame operation. */
    public Map<String, Object> snapshot() {
        RenderSystem.assertOnRenderThread();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("reason", reason);
        result.put("backend", backend);
        result.put("scope", "GRAPHICS_QUEUE_RENDER_COMMANDS_EXCLUDING_PRESENT_DXGI_COMPOSITOR");
        result.put("timestampPeriodNanos", Double.isFinite(timestampPeriod) && timestampPeriod > 0.0 ? timestampPeriod : null);
        result.put("timestampValidBits", "NOT_EXPOSED_BY_26.3_API");
        result.put("ringPairs", RING_PAIRS);
        result.put("sampleEveryEligibleFrames", SAMPLE_EVERY_FRAMES);
        result.put("maxPendingPairChecksPerPoll", MAX_POLL_PAIRS);
        result.put("eligibleFrames", eligibleFrames);
        result.put("samplingSkips", samplingSkips);
        result.put("busyDrops", busyDrops);
        result.put("submittedPairs", submittedPairs);
        result.put("completedPairs", completedPairs);
        result.put("deliveredSamples", deliveredSamples);
        result.put("pendingPairs", pendingCount());
        result.put("openPair", openSlot != -1);
        result.put("queriedPairs", queriedPairs);
        result.put("unavailablePolls", unavailablePolls);
        result.put("invalidSamples", invalidSamples);
        result.put("negativeDeltas", negativeDeltas);
        result.put("zeroDeltas", zeroDeltas);
        result.put("conversionFailures", conversionFailures);
        result.put("lastRejectedReason", lastRejectedReason);
        result.put("apiFailures", apiFailures);
        result.put("consumerFailures", consumerFailures);
        result.put("poolCreations", poolCreations);
        result.put("closeCalls", closeCalls);
        result.put("poolCloseAttempts", poolCloseAttempts);
        result.put("poolCloseSuccesses", poolCloseSuccesses);
        result.put("poolCloseFailures", poolCloseFailures);
        result.put("pendingAtClose", pendingAtClose);
        result.put("openAtClose", openAtClose);
        result.put("abandonedPairs", abandonedPairs);
        result.put("closed", closed);
        return result;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        closeCalls++;
        if (closed) {
            return;
        }
        closed = true;
        pendingAtClose = pendingCount();
        openAtClose = openSlot != -1;
        abandonedPairs += pendingAtClose + (openAtClose ? 1 : 0);
        activeEncoder = null;
        openSlot = -1;
        Arrays.fill(states, FREE);
        GpuQueryPool toClose = pool;
        pool = null;
        status = "CLOSED";
        if (toClose != null) {
            poolCloseAttempts++;
            try {
                // Vulkan defers destruction through its native queue; no result wait is needed.
                toClose.close();
                poolCloseSuccesses++;
            } catch (RuntimeException failure) {
                poolCloseFailures++;
                reason = failureReason("QUERY_POOL_CLOSE_FAILED", failure);
            }
        }
    }

    private boolean available() {
        return !closed && pool != null && status.equals("AVAILABLE");
    }

    private int pendingCount() {
        int count = 0;
        for (byte state : states) {
            if (state == PENDING) {
                count++;
            }
        }
        return count;
    }

    private void fail(String code, RuntimeException failure) {
        if (failure != null && !code.equals("SAMPLE_CONSUMER_FAILED")) {
            apiFailures++;
        }
        status = "FAILED";
        reason = failureReason(code, failure);
        activeEncoder = null;
    }

    private static String failureReason(String code, RuntimeException failure) {
        return failure == null ? code : code + ": " + failure.getClass().getSimpleName();
    }
}
