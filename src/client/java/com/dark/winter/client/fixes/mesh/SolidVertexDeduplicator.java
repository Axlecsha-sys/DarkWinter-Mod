// Optionally reuses exact final solid vertex records while preserving native triangle order and buffer ownership.
package com.dark.winter.client.fixes.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/** Compile-worker transformation only; all world queries, AO, lighting and native packing finish before it runs. */
public final class SolidVertexDeduplicator {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("darkwinter.render.solidVertexDedup", "true"));
    private static final boolean FILTER_DEGENERATES = Boolean.parseBoolean(System.getProperty("darkwinter.render.solidDegenerateFilter", "true"));
    private static volatile boolean vulkanReady;
    private static final LongAdder inspected = new LongAdder(), optimized = new LongAdder();
    private static final LongAdder before = new LongAdder(), unique = new LongAdder(), padded = new LongAdder(), indexBytes = new LongAdder();
    private static final LongAdder noBenefit = new LongAdder(), unsupported = new LongAdder(), precommitFallback = new LongAdder();

    /** Registers a render-thread startup check; no worker thread ever queries RenderSystem or the device. */
    public static void initialize() {
        if (!ENABLED) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                vulkanReady = "Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> vulkanReady = false);
    }

    /** Call only after all native/C++ writes finish, and before CompiledSectionMesh snapshots the draw state. */
    public static SectionCompiler.Results apply(SectionCompiler.Results results, SectionBufferBuilderPack builders) {
        if (!ENABLED || !vulkanReady || ChunkMeshComparison.running() || ChunkMeshComparison.replaying()) return results;
        MeshData old = results.renderedLayers.get(ChunkSectionLayer.SOLID);
        if (old == null) return results;
        var state = old.drawState();
        int count = state.vertexCount();
        if (old.getClass() != MeshData.class || state.format() != DefaultVertexFormat.BLOCK || state.format().getVertexSize() != SolidVertexDedupPlan.STRIDE
                || state.primitiveTopology() != PrimitiveTopology.QUADS || old.indexBuffer() != null
                || count < 512 || count > SolidVertexDedupPlan.MAX_VERTICES || (count & 3) != 0
                || state.indexCount() != count / 4 * 6) {
            unsupported.increment();
            return results;
        }

        inspected.increment();
        SolidVertexDedupPlan plan;
        ByteBuffer vertices;
        IndexedSolidMesh replacement;
        ByteBufferBuilder.Result indices = null;
        try {
            ByteBuffer originalView = old.vertexBuffer();
            if (originalView.isReadOnly() || originalView.remaining() != count * SolidVertexDedupPlan.STRIDE) {
                unsupported.increment();
                return results;
            }
            plan = SolidVertexDedupPlan.build(originalView, count, FILTER_DEGENERATES);
            if (!plan.worthwhile() || plan.indexCount == 0) {
                noBenefit.increment();
                return results;
            }
            // Native translucent sorting uses this same pack-owned builder/result lifetime pattern.
            ByteBufferBuilder builder = builders.buffer(ChunkSectionLayer.SOLID);
            builder.reserve(plan.indexBytes());
            indices = builder.build();
            if (indices == null || indices.size() != plan.indexBytes())
                throw new IllegalStateException("Solid index builder did not yield the exact reserved range");
            plan.writeIndices(indices.byteBuffer().order(ByteOrder.nativeOrder()));

            // reserve may realloc the builder. Never use a previously acquired view after it.
            vertices = old.vertexBuffer();
            if (vertices.isReadOnly() || vertices.remaining() != count * SolidVertexDedupPlan.STRIDE)
                throw new IllegalStateException("Original vertex range changed before commit");
            IndexType indexType = IndexType.least(plan.paddedVertices);
            if (indexType.bytes != plan.indexTypeBytes) throw new IllegalStateException("Native index width differs from planned width");
            var draw = new MeshData.DrawState(state.format(), plan.paddedVertices, plan.indexCount,
                    state.primitiveTopology(), indexType);
            replacement = new IndexedSolidMesh(old.vertexBufferSlice(), draw, indices, plan.vertexBytes());
        } catch (RuntimeException failure) {
            if (indices != null) indices.close();
            precommitFallback.increment();
            return results; // No original vertex bytes or published draw state have changed.
        }

        // Deliberately outside the fallback catch: a partially mutated VBO must never be published as the old mesh.
        plan.compactPrefix(vertices);
        results.renderedLayers.put(ChunkSectionLayer.SOLID, replacement);
        // Do not close the replaced wrapper: ownership of its sole vertex Result transfers to replacement.
        optimized.increment();before.add(count);unique.add(plan.uniqueVertices);padded.add(plan.paddedVertices);indexBytes.add(plan.indexBytes());
        return results;
    }

    /** Reflection/diagnostic consumers call this outside measured spans, never per vertex. */
    public static Map<String, Long> statistics() {
        return Map.of("inspected", inspected.sum(), "optimized", optimized.sum(), "verticesBefore", before.sum(),
                "uniqueVertices", unique.sum(), "paddedVertices", padded.sum(), "indexBytes", indexBytes.sum(),
                "noBenefit", noBenefit.sum(), "unsupported", unsupported.sum(), "precommitFallback", precommitFallback.sum());
    }

    /** Owns two native Results from the same existing worker pack; no independent native allocation owner. */
    private static final class IndexedSolidMesh extends MeshData {
        private final ByteBufferBuilder.Result indices;
        private final int activeVertexBytes;
        IndexedSolidMesh(ByteBufferBuilder.Result vertices, DrawState draw, ByteBufferBuilder.Result indices, int activeVertexBytes) {
            super(vertices, draw);
            this.indices = indices;
            this.activeVertexBytes = activeVertexBytes;
        }
        @Override public ByteBuffer vertexBuffer() {
            ByteBuffer view = super.vertexBuffer();
            view.limit(activeVertexBytes);
            return view;
        }
        @Override public ByteBuffer indexBuffer() { return indices.byteBuffer(); }
        @Override public void close() {
            try { super.close(); }
            finally { indices.close(); }
        }
    }

    private SolidVertexDeduplicator() { }
}
