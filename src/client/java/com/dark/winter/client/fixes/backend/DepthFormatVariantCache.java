// Caches owned native depth-format variants while leaving the original D32 pipeline under vanilla ownership.
package com.dark.winter.client.fixes.backend;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

public final class DepthFormatVariantCache implements AutoCloseable {
    private final long defaultPipeline;
    private final ToLongFunction<GpuFormat> create;
    private final LongConsumer destroy;
    private final EnumMap<GpuFormat, Long> variants = new EnumMap<>(GpuFormat.class);
    private boolean closed;

    public DepthFormatVariantCache(long defaultPipeline, ToLongFunction<GpuFormat> create, LongConsumer destroy) {
        if (defaultPipeline == 0L) throw new IllegalArgumentException("The default D32 pipeline must be valid");
        this.defaultPipeline = defaultPipeline;
        this.create = Objects.requireNonNull(create);
        this.destroy = Objects.requireNonNull(destroy);
    }

    public synchronized long get(GpuFormat format) {
        if (closed) throw new IllegalStateException("Depth-format pipeline variants have been destroyed");
        if (format == null || !format.hasDepthAspect()) throw new IllegalArgumentException("Not a depth attachment format: " + format);
        if (format == GpuFormat.D32_FLOAT) return defaultPipeline;
        Long cached = variants.get(format);
        if (cached != null) return cached;
        long pipeline = create.applyAsLong(format);
        if (pipeline == 0L) throw new IllegalStateException("Failed to create a depth-format pipeline for " + format);
        variants.put(format, pipeline);
        return pipeline;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException firstFailure = null;
        try {
            for (long pipeline : variants.values()) {
                try {
                    destroy.accept(pipeline);
                } catch (RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
        } finally {
            variants.clear();
        }
        if (firstFailure != null) throw firstFailure;
    }
}
