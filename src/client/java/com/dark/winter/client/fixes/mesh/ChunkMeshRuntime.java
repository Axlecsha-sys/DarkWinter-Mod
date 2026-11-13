// Owns optional section-packing scopes and retires worker caches without interrupting native calls.
package com.dark.winter.client.fixes.mesh;

import com.dark.winter.client.fixes.nativebuffers.NativeScratchBuffers;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import com.dark.winter.client.fixes.voxel.VoxelFaceRuntime;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/** Optional final-stage SOLID/CUTOUT quad packing. Does not read blocks, calculate light or cull faces. */
public final class ChunkMeshRuntime {
    public enum Mode { VANILLA, JAVA_PACKED, CPP_PACKED }
    private static final Logger LOG = LoggerFactory.getLogger("DarkWinter/ChunkMesh");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("darkwinter/mesh-experiment.properties");
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Frame>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Mode> OVERRIDE = new ThreadLocal<>();
    private static final java.util.Set<Frame> FRAMES = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<Frame> RETIRING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong CACHE_EPOCH = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean cachesMayExist;
    private static volatile boolean cacheAllowed;
    private static final Map<Mode, Totals> TOTALS = new EnumMap<>(Mode.class);
    private static volatile Mode requested = Mode.VANILLA;
    private static volatile BlockMeshNative nativeBackend;
    private static volatile String nativeStatus = "not loaded";
    private static volatile boolean nativeDisabled;
    private static boolean initialized;
    static { for (Mode mode : Mode.values()) TOTALS.put(mode, new Totals()); }

    private ChunkMeshRuntime() { }

    public static void initialize() {
        if (initialized) return;
        boolean explicitMode = Files.isRegularFile(CONFIG);
        boolean bundledDefault = !explicitMode
                && VoxelFaceRuntime.requested() == VoxelFaceRuntime.Mode.JAVA
                && ChunkMeshRuntime.class.getModule().isNativeAccessEnabled()
                && com.winterkore.engine.WkeNativeLibraries.bundledAvailable("wke_voxel.dll");
        if (bundledDefault) requested = Mode.CPP_PACKED;
        if (explicitMode) {
            try (var input = Files.newInputStream(CONFIG)) {
                Properties p = new Properties(); p.load(input);
                requested = Mode.valueOf(p.getProperty("mode", "VANILLA"));
            } catch (IOException | IllegalArgumentException e) {
                LOG.warn("Cannot read mesh mode, retaining vanilla", e);
                requested = Mode.VANILLA;
            }
        }
        if (requested != Mode.VANILLA && VoxelFaceRuntime.requested() != VoxelFaceRuntime.Mode.JAVA)
            VoxelFaceRuntime.setMode(VoxelFaceRuntime.Mode.JAVA);
        if (requested == Mode.CPP_PACKED && !ensureNative() && bundledDefault) requested = Mode.VANILLA;
        ChunkMeshCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChunkMeshComparison.tick(client);
            if (requested == Mode.VANILLA && !ChunkMeshComparison.running() && cachesMayExist) releaseScratchCaches();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            cacheAllowed = true;
            releaseScratchCaches();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            cacheAllowed = false;
            releaseScratchCaches();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            cacheAllowed = false;
            releaseScratchCaches();
        });
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("darkwinter", "mesh_scratch"),
                (ResourceManagerReloadListener) manager -> releaseScratchCaches());
        initialized = true;
        LOG.info("WKE section packing: requested={}, effective={}, bundledDefault={}, native={}",
                requested, effective(), bundledDefault, nativeStatus);
    }

    public static Mode requested() { return requested; }
    public static boolean detailedTiming() { return BlockMeshPacking.DETAILED_TIMING; }
    public static Mode effective() {
        if (!ChunkMeshRuntime.class.getModule().isNativeAccessEnabled()
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) return Mode.VANILLA;
        if (requested == Mode.CPP_PACKED && (nativeBackend == null || nativeDisabled)) return Mode.VANILLA;
        return requested;
    }

    public static synchronized String setMode(Mode mode) {
        if (ChunkMeshComparison.running()) throw new IllegalStateException("A mesh comparison is still running");
        setModeForComparison(mode);
        return status();
    }

    static synchronized void setModeForComparison(Mode mode) {
        // The old 18^3 mask pass must not silently run together with this independent experiment.
        VoxelFaceRuntime.setMode(VoxelFaceRuntime.Mode.JAVA);
        if (mode == Mode.CPP_PACKED && !ensureNative()) {
            throw new IllegalStateException("Native packing unavailable: " + nativeStatus);
        }
        requested = mode;
        releaseScratchCaches();
        try {
            Files.createDirectories(CONFIG.getParent());
            Path tmp = Files.createTempFile(CONFIG.getParent(), "mesh-", ".tmp");
            try {
                Files.writeString(tmp, "# Optional real section vertex packing.\nmode=" + mode.name() + "\n");
                try { Files.move(tmp, CONFIG, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, CONFIG, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) { LOG.warn("Could not persist mesh mode", e); }
    }

    static synchronized boolean ensureNative() {
        if (nativeDisabled) return false;
        if (nativeBackend != null) return true;
        BlockMeshNative candidate = null;
        try {
            Path path = nativeLibrary();
            candidate = new BlockMeshNative(path);
            int[] templates = new int[20];
            int[] records = new int[12];
            for (int v = 0; v < 4; v++) {
                templates[v * 5] = Float.floatToRawIntBits(v * 0.25f);
                templates[v * 5 + 1] = Float.floatToRawIntBits(v - 1.5f);
                templates[v * 5 + 2] = Float.floatToRawIntBits(-0.0f);
                templates[v * 5 + 3] = Float.floatToRawIntBits(v * 0.125f);
                templates[v * 5 + 4] = Float.floatToRawIntBits(1.0f - v * 0.25f);
                records[4 + v] = 0x87123456 + v;
                records[8 + v] = 0x00f000a0 + v;
            }
            records[1] = Float.floatToRawIntBits(15.0f);
            records[2] = Float.floatToRawIntBits(0.75f);
            records[3] = Float.floatToRawIntBits(-0.0f);
            try (Arena arena = Arena.ofConfined(); BlockMeshNative.Scratch scratch = new BlockMeshNative.Scratch()) {
                MemorySegment expected = arena.allocate(112, 16), actual = arena.allocate(112, 16);
                BlockMeshPacking.packJava(templates, 1, records, 1, expected);
                candidate.pack(templates, 1, records, 1, actual, scratch);
                if (expected.mismatch(actual) != -1) throw new IllegalStateException("Native packing startup parity failure");
                int[] encoded = new int[32];
                for (int i = 0; i < encoded.length; i++) encoded[i] = 0x832a194f ^ (i * 0x1020304);
                BlockMeshPacking.packEncodedJava(encoded, 1, expected);
                candidate.packEncoded(encoded, 1, actual, scratch);
                if (expected.mismatch(actual) != -1) throw new IllegalStateException("Indigo packing startup parity failure");
            }
            nativeBackend = candidate;
            nativeStatus = "ABI1 verified: " + path.getFileName();
            LOG.info("WKE host activated native mesh library after ABI and byte-parity checks: {}", path);
            return true;
        } catch (Throwable failure) {
            if (candidate != null) candidate.close();
            if (failure instanceof Error e && !(e instanceof LinkageError)) throw e;
            nativeStatus = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            LOG.warn("Native mesh unavailable; vanilla retained: {}", nativeStatus);
            return false;
        } finally {
            BlockMeshPacking.releaseThreadScratch();
        }
    }

    private static Path nativeLibrary() throws IOException {
        try {
            return com.winterkore.engine.WkeNativeLibraries.resolve("darkwinter.voxel.native.library", "wke_voxel.dll");
        } catch (IOException unavailable) {
            if (com.winterkore.engine.WkeNativeLibraries.explicitlyConfigured("darkwinter.voxel.native.library")
                    || com.winterkore.engine.WkeNativeLibraries.bundledAvailable("wke_voxel.dll")
                    || !FabricLoader.getInstance().isDevelopmentEnvironment()) throw unavailable;
            // Legacy explicit experiments remain usable when no packaged WKE artifact is present.
            Path parent = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().getParent();
            if (parent == null) throw unavailable;
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String name = os.contains("win") ? "dw_voxel_faces.dll" : os.contains("mac")
                    ? "libdw_voxel_faces.dylib" : "libdw_voxel_faces.so";
            return parent.resolve("build/voxel-faces/bin/" + name);
        }
    }

    static void disableNative(String reason) {
        nativeDisabled = true;
        nativeStatus = reason;
        // Do not unload the library: another worker may be inside a downcall.
        releaseScratchCaches();
        LOG.error("Native mesh disabled for this client: {}", reason);
    }

    static Mode override(Mode mode) {
        Mode previous = OVERRIDE.get();
        if (mode == null) OVERRIDE.remove(); else OVERRIDE.set(mode);
        return previous;
    }

    /** Keep the disabled path free of section scopes, timers and counters. */
    public static boolean needsScope() {
        return CURRENT.get() != null || OVERRIDE.get() != null || ChunkMeshComparison.running()
                || effective() != Mode.VANILLA;
    }

    /** Idle caches close now; active frames keep their arrays until their scope exits. */
    public static void releaseScratchCaches() {
        CACHE_EPOCH.incrementAndGet();
        cachesMayExist = false;
        Frame[] frames;
        synchronized (FRAMES) {
            java.util.Set<Frame> all = new java.util.HashSet<>(FRAMES);
            all.addAll(RETIRING);
            frames = all.toArray(Frame[]::new);
            FRAMES.clear();
        }
        for (Frame frame : frames) frame.retireCaches();
        NativeScratchBuffers.retireAll(NativeScratchBuffers.Kind.MESH);
        BlockMeshPacking.releaseScratchCaches();
    }

    /** Exact scratch payload sizes at quiescence; object/map overhead and the loaded DLL are excluded. */
    public static Map<String, Long> memorySnapshot() {
        Map<String, Long> result = new LinkedHashMap<>(NativeScratchBuffers.memorySnapshot(NativeScratchBuffers.Kind.MESH));
        Frame[] frames;
        synchronized (FRAMES) {
            java.util.Set<Frame> all = new java.util.HashSet<>(FRAMES);
            all.addAll(RETIRING);
            frames = all.toArray(Frame[]::new);
        }
        long batches = 0, arrays = 0, active = 0;
        for (Frame frame : frames) {
            synchronized (frame) {
                if (frame.active) active++;
                for (Batch b : frame.layerBatches) if (b != null) {
                    batches++;
                    arrays += ((long) b.templates.length + b.records.length + b.encodedVertices.length) * Integer.BYTES;
                }
            }
        }
        result.put("cacheAllowed", cacheAllowed ? 1L : 0L);
        result.put("cacheEpoch", CACHE_EPOCH.get());
        result.put("cachedFrames", (long) frames.length);
        result.put("activeFrames", active);
        result.put("cachedBatches", batches);
        result.put("cachedArrayBytes", arrays);
        result.put("javaWordCacheBytes", BlockMeshPacking.memorySnapshot().get("cachedBytes"));
        return java.util.Collections.unmodifiableMap(result);
    }

    public static Scope begin() {
        Mode mode = OVERRIDE.get();
        if (mode == null) mode = effective();
        ArrayDeque<Frame> pool = POOL.get();
        Frame frame = pool.pollFirst();
        if (frame == null) frame = new Frame();
        synchronized (frame) {
            frame.reset(mode, CURRENT.get());
            frame.epoch = CACHE_EPOCH.get();
            frame.retired = false;
            frame.active = true;
        }
        synchronized (FRAMES) { FRAMES.add(frame); }
        CURRENT.set(frame);
        return new Scope(frame);
    }

    public static final class Scope implements AutoCloseable {
        private final Frame frame;
        private final long start = System.nanoTime();
        private boolean finished;
        private Scope(Frame frame) { this.frame = frame; }
        public int eligibleQuads() { return frame.eligible; }
        public Mode mode() { return frame.mode; }
        public Map<String, Long> measurements() { return frame.measurements(); }
        public void finish() {
            frame.ensureFlushed();
            frame.compileNanos = System.nanoTime() - start;
            finished = true;
            if (!ChunkMeshComparison.replaying()) TOTALS.get(frame.mode).add(frame);
        }
        @Override public void close() {
            if (CURRENT.get() != frame) throw new IllegalStateException("Mesh scope stack mismatch");
            if (frame.previous == null) CURRENT.remove(); else CURRENT.set(frame.previous);
            frame.previous = null;
            // On an exception, discard deferred work without affecting another compiler's buffers.
            synchronized (frame) {
                frame.release();
                frame.active = false;
                if (!cacheAllowed || frame.retired || frame.epoch != CACHE_EPOCH.get()
                        || (requested == Mode.VANILLA && !ChunkMeshComparison.running() && !ChunkMeshComparison.replaying())) {
                    frame.dropCaches();
                    BlockMeshPacking.releaseThreadScratch();
                }
            }
            RETIRING.remove(frame);
            if (POOL.get().size() < 2) POOL.get().addFirst(frame);
        }
    }

    public static boolean supportsLayer(ChunkSectionLayer layer) {
        // Both use the identical BLOCK layout and require no transparency index sorting.
        // Alpha testing remains a shader/material responsibility; raw vertex bytes do not change.
        return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT;
    }

    /** True means deferred into the same buffer, false means caller MUST use the original method. */
    public static boolean enqueue(BufferBuilder builder, float x, float y, float z,
                                  BakedQuad quad, QuadInstance instance) {
        Frame f = CURRENT.get();
        if (f == null) return false;
        boolean solid = supportsLayer(quad.materialInfo().layer());
        if (solid) f.eligible++;
        if (f.mode == Mode.VANILLA) return false;
        if (!solid || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !(builder instanceof BlockMeshBufferAccess access) || !access.darkwinter$canAppendBlockMesh()
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            flush(builder);
            return false;
        }
        if (!f.select(builder)) return false;
        Batch b = f.batch;
        if (b.count == BlockMeshPacking.MAX_QUADS || (b.count != 0 && b.encoded)) f.flush();
        b.encoded = false;
        long before = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
        int id = b.ids.getInt(quad);
        if (id < 0) {
            if (b.templateCount == BlockMeshPacking.MAX_TEMPLATES) {
                f.flush(); b.clearTemplates();
            }
            id = b.templateCount;
            int base = id * BlockMeshPacking.TEMPLATE_WORDS;
            for (int v = 0; v < 4; v++) {
                var pos = quad.position(v);
                long uv = quad.packedUV(v);
                float u = UVPair.unpackU(uv), vv = UVPair.unpackV(uv);
                if (!Float.isFinite(pos.x()) || !Float.isFinite(pos.y()) || !Float.isFinite(pos.z())
                        || !Float.isFinite(u) || !Float.isFinite(vv)) {
                    if (BlockMeshPacking.DETAILED_TIMING) f.preparationNanos += System.nanoTime() - before;
                    f.flush();
                    return false;
                }
                int p = base + v * 5;
                b.templates[p] = Float.floatToRawIntBits(pos.x());
                b.templates[p + 1] = Float.floatToRawIntBits(pos.y());
                b.templates[p + 2] = Float.floatToRawIntBits(pos.z());
                b.templates[p + 3] = Float.floatToRawIntBits(u);
                b.templates[p + 4] = Float.floatToRawIntBits(vv);
            }
            b.ids.put(quad, id); b.templateCount++;
        }
        int r = b.count * BlockMeshPacking.RECORD_WORDS;
        b.records[r] = id;
        b.records[r + 1] = Float.floatToRawIntBits(x);
        b.records[r + 2] = Float.floatToRawIntBits(y);
        b.records[r + 3] = Float.floatToRawIntBits(z);
        int emission = quad.materialInfo().lightEmission();
        for (int v = 0; v < 4; v++) {
            b.records[r + 4 + v] = instance.getColor(v);
            b.records[r + 8 + v] = instance.getLightCoordsWithEmission(v, emission);
        }
        b.count++;
        access.darkwinter$setPendingBlockMesh(true);
        f.packedQuads++;
        if (BlockMeshPacking.DETAILED_TIMING) f.preparationNanos += System.nanoTime() - before;
        return true;
    }

    /** Copies the final 32 encoded vertex words before Indigo reuses its mutable emitter. */
    public static boolean enqueueEncoded(BufferBuilder builder, int[] vertices, int start, boolean solid) {
        Frame f = CURRENT.get();
        if (f == null) return false;
        if (solid) f.eligible++;
        if (f.mode == Mode.VANILLA) return false;
        if (!solid || vertices == null || start < 0 || start > vertices.length - 32
                || !(builder instanceof BlockMeshBufferAccess access) || !access.darkwinter$canAppendBlockMesh()
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            flush(builder);
            return false;
        }
        if (!f.select(builder)) return false;
        Batch b = f.batch;
        if ((b.count != 0 && !b.encoded) || b.count == BlockMeshPacking.MAX_QUADS) f.flush();
        b.encoded = true;
        long before = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
        System.arraycopy(vertices, start, b.encodedVertices, b.count * 32, 32);
        b.count++;
        access.darkwinter$setPendingBlockMesh(true);
        f.packedQuads++;
        if (BlockMeshPacking.DETAILED_TIMING) f.preparationNanos += System.nanoTime() - before;
        return true;
    }

    public static void flush(BufferBuilder builder) {
        Frame f = CURRENT.get();
        if (f != null) f.flush(builder);
    }

    /** Explicit barrier before fluids: preserve order even when they share an eligible buffer. */
    public static void flushAll() {
        Frame f = CURRENT.get();
        if (f != null) {
            for (BufferBuilder builder : f.layerBuilders) if (builder != null) f.flush(builder);
        }
    }

    private static final class Batch {
        final Reference2IntOpenHashMap<BakedQuad> ids = new Reference2IntOpenHashMap<>();
        final int[] templates = new int[BlockMeshPacking.MAX_TEMPLATES * BlockMeshPacking.TEMPLATE_WORDS];
        final int[] records = new int[BlockMeshPacking.MAX_QUADS * BlockMeshPacking.RECORD_WORDS];
        final int[] encodedVertices = new int[BlockMeshPacking.MAX_QUADS * 32];
        boolean encoded;
        BlockMeshNative.Scratch nativeScratch;
        int templateCount, count;
        Batch() { ids.defaultReturnValue(-1); }
        void clearTemplates() { ids.clear(); templateCount = 0; }
        void clear() { count = 0; clearTemplates(); }
    }

    private static final class Frame {
        Frame previous;
        long epoch;
        volatile boolean retired;
        boolean active;
        Mode mode;
        BufferBuilder builder;
        Batch batch;
        // Each eligible layer owns a separate buffer, so interleaving layers must not
        // force tiny packets. Order within EACH buffer remains identical to vanilla.
        final BufferBuilder[] layerBuilders = new BufferBuilder[2];
        final Batch[] layerBatches = new Batch[2];
        int eligible, packedQuads, nativeBatches, javaBatches, fallbackBatches;
        long compileNanos, preparationNanos, reserveNanos, writeNanos, copyNanos, kernelNanos;
        void reset(Mode mode, Frame previous) {
            this.mode = mode; this.previous = previous;
            release();
            eligible = packedQuads = nativeBatches = javaBatches = fallbackBatches = 0;
            compileNanos = 0;
            preparationNanos = reserveNanos = writeNanos = copyNanos = kernelNanos =
                    BlockMeshPacking.DETAILED_TIMING ? 0 : BlockMeshPacking.UNAVAILABLE_NANOS;
        }
        boolean select(BufferBuilder target) {
            if (builder == target) return true;
            for (int i = 0; i < layerBuilders.length; i++) {
                if (layerBuilders[i] == target) {
                    builder = target; batch = layerBatches[i]; return true;
                }
            }
            for (int i = 0; i < layerBuilders.length; i++) {
                if (layerBuilders[i] == null) {
                    layerBuilders[i] = target;
                    if (layerBatches[i] == null) {
                        layerBatches[i] = new Batch();
                        cachesMayExist = true;
                    }
                    builder = target; batch = layerBatches[i]; return true;
                }
            }
            // Unexpected extra producers retain the original method; do not grow unbounded pools.
            return false;
        }
        void ensureFlushed() {
            for (Batch b : layerBatches) {
                if (b != null && b.count != 0) throw new IllegalStateException("Unflushed mesh layer batch");
            }
        }
        void release() {
            for (int i = 0; i < layerBuilders.length; i++) {
                if (layerBuilders[i] instanceof BlockMeshBufferAccess access) access.darkwinter$setPendingBlockMesh(false);
                if (layerBatches[i] != null) layerBatches[i].clear();
                layerBuilders[i] = null;
            }
            builder = null; batch = null;
        }
        synchronized void retireCaches() {
            retired = true;
            if (active) RETIRING.add(this);
            else dropCaches();
        }
        void dropCaches() {
            for (int i = 0; i < layerBatches.length; i++) {
                Batch batch = layerBatches[i];
                if (batch != null && batch.nativeScratch != null) batch.nativeScratch.close();
                layerBatches[i] = null;
            }
        }
        void flush(BufferBuilder target) {
            for (int i = 0; i < layerBuilders.length; i++) {
                if (layerBuilders[i] == target) {
                    BufferBuilder previousBuilder = builder;
                    Batch previousBatch = batch;
                    builder = target; batch = layerBatches[i];
                    try { flush(); }
                    finally { builder = previousBuilder; batch = previousBatch; }
                    return;
                }
            }
        }
        void flush() {
            if (batch == null || batch.count == 0) return;
            Batch b = batch;
            int n = b.count;
            BlockMeshBufferAccess access = (BlockMeshBufferAccess) builder;
            long start = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            long address = access.darkwinter$reserveBlockQuads(n);
            MemorySegment output = MemorySegment.ofAddress(address).reinterpret((long) n * BlockMeshPacking.QUAD_BYTES);
            if (BlockMeshPacking.DETAILED_TIMING) reserveNanos += System.nanoTime() - start;
            start = BlockMeshPacking.DETAILED_TIMING ? System.nanoTime() : 0L;
            boolean wrote = false;
            if (mode == Mode.CPP_PACKED && cacheAllowed && !retired && epoch == CACHE_EPOCH.get()
                    && !nativeDisabled && nativeBackend != null && n >= 32) {
                try {
                    if (b.nativeScratch == null || b.nativeScratch.isRetired()) b.nativeScratch = new BlockMeshNative.Scratch();
                    if (b.encoded) nativeBackend.packEncoded(b.encodedVertices, n, output, b.nativeScratch);
                    else nativeBackend.pack(b.templates, b.templateCount, b.records, n, output, b.nativeScratch);
                    if (BlockMeshPacking.DETAILED_TIMING) {
                        copyNanos += b.nativeScratch.copyNanos;
                        kernelNanos += b.nativeScratch.callNanos;
                    }
                    nativeBatches++; wrote = true;
                } catch (Throwable failure) {
                    if (failure instanceof Error e && !(e instanceof LinkageError)) throw e;
                    fallbackBatches++;
                    if (!(failure instanceof BlockMeshNative.ScratchRetiredException)) disableNative(failure.toString());
                }
            }
            if (!wrote) {
                // Also repairs every reserved byte after a recoverable native error, without appending twice.
                if (b.encoded) BlockMeshPacking.packEncodedJava(b.encodedVertices, n, output);
                else BlockMeshPacking.packJava(b.templates, b.templateCount, b.records, n, output);
                javaBatches++;
            }
            if (BlockMeshPacking.DETAILED_TIMING) writeNanos += System.nanoTime() - start;
            b.count = 0;
            access.darkwinter$setPendingBlockMesh(false);
        }
        Map<String, Long> measurements() {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("detailedTiming", BlockMeshPacking.DETAILED_TIMING ? 1L : 0L);
            m.put("compileNanos", compileNanos); m.put("packetPreparationNanos", preparationNanos);
            m.put("reserveNanos", reserveNanos); m.put("writeTotalNanos", writeNanos);
            m.put("nativeInputCopyNanos", copyNanos); m.put("nativeCallNanos", kernelNanos);
            m.put("eligibleQuads", (long) eligible); m.put("packedQuads", (long) packedQuads);
            m.put("nativeBatches", (long) nativeBatches); m.put("javaBatches", (long) javaBatches);
            m.put("fallbackBatches", (long) fallbackBatches);
            return m;
        }
    }

    private static final class Totals {
        final LongAdder sections = new LongAdder(), nanos = new LongAdder(), quads = new LongAdder();
        final LongAdder prep = new LongAdder(), writes = new LongAdder(), nativeCalls = new LongAdder();
        void add(Frame f) {
            sections.increment(); nanos.add(f.compileNanos); quads.add(f.packedQuads);
            if (BlockMeshPacking.DETAILED_TIMING) { prep.add(f.preparationNanos); writes.add(f.writeNanos); }
            nativeCalls.add(f.nativeBatches);
        }
    }

    public static String status() {
        Totals t = TOTALS.get(effective());
        long count = t.sections.sum();
        return "requested=" + requested + ", effective=" + effective() + ", detailedTiming=" + detailedTiming() + ", native=" + nativeStatus
                + ", sections=" + count + ", packedQuads=" + t.quads.sum()
                + ", nativeBatches=" + t.nativeCalls.sum()
                + ", meanCompileUs=" + (count == 0 ? 0 : t.nanos.sum() / count / 1000)
                + "; " + ChunkMeshComparison.status();
    }
}
