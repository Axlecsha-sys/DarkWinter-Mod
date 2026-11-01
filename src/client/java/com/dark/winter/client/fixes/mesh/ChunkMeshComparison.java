package com.dark.winter.client.fixes.mesh;

import com.dark.winter.client.diagnostics.performance.PerformanceClient;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit diagnostic replay of the SAME real RenderSectionRegion and compiler, not a synthetic FPS claim.
 * Replays run sequentially on the worker that owns the snapshot; results are released immediately.
 * Neither snapshots nor block entities escape to a background executor.
 */
public final class ChunkMeshComparison {
    private static final Logger LOG = LoggerFactory.getLogger("DarkWinter/ChunkMeshCompare");
    private static final ThreadLocal<Boolean> REPLAY = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Map<String, Long>> LAST = new ThreadLocal<>();
    private static volatile Campaign campaign;
    private ChunkMeshComparison() { }

    public static boolean replaying() { return REPLAY.get(); }
    public static boolean running() { Campaign c = campaign; return c != null && !c.done; }
    public static String status() {
        Campaign c = campaign;
        return c == null ? "comparison=idle" : "comparison=" + c.id + ", samples=" + c.samples.size()
                + "/" + c.requested + ", done=" + c.done + ", report=" + c.report;
    }

    public static synchronized String request(int sections) {
        Minecraft client = Minecraft.getInstance();
        if (sections < 1 || sections > 4) throw new IllegalArgumentException("sections must be 1..4");
        if (client.level == null) throw new IllegalStateException("Load the test world first");
        if (running() || PerformanceClient.isCapturing()) throw new IllegalStateException("Another diagnostic capture is active");
        if (!ChunkMeshRuntime.ensureNative()) throw new IllegalStateException("Compatible native mesh DLL is unavailable");
        ChunkMeshRuntime.setModeForComparison(ChunkMeshRuntime.Mode.VANILLA);
        campaign = new Campaign(sections);
        LOG.info("Mesh comparison {} armed for {} real sections. Diagnostic rebuilds are NOT FPS samples.", campaign.id, sections);
        return status();
    }

    public static void tick(Minecraft client) {
        Campaign c = campaign;
        if (c == null || c.done) return;
        long now = System.nanoTime();
        if (!c.busy.get() && (client.level == null || now - c.start > 90_000_000_000L)) {
            c.errors.add(client.level == null ? "World closed before comparison completed" : "Timed out waiting for eligible section snapshots");
            finish(c);
            return;
        }
        if (!c.busy.get() && client.level != null && client.gui.screen() == null && now >= c.nextRebuild) {
            c.nextRebuild = now + 5_000_000_000L;
            client.levelExtractor.allChanged();
        }
    }

    /** Called after the primary compilation has fully flushed, and its scope has been released. */
    public static void onCompiled(SectionCompiler compiler, SectionPos pos, RenderSectionRegion region,
                                  VertexSorting sorting, SectionCompiler.Results primary,
                                  int eligibleQuads, Map<String, Long> measured) {
        if (replaying()) { LAST.set(measured); return; }
        Campaign c = campaign;
        if (c == null || c.done || eligibleQuads < 128 || !c.busy.compareAndSet(false, true)) return;
        try {
            if (c.done) return;
            int sampleIndex = c.samples.size();
            Map<String, Object> sample = compare(compiler, pos, region, sorting, primary, eligibleQuads, sampleIndex);
            c.samples.add(sample);
            LOG.info("Mesh comparison {}: sample {} / {}, section {}, byteParity={}",
                    c.id, c.samples.size(), c.requested, pos, sample.get("byteParity"));
            if (!Boolean.TRUE.equals(sample.get("byteParity"))) {
                c.errors.add("Mesh mismatch in section " + pos);
                finish(c);
            } else if (!Boolean.TRUE.equals(sample.get("nativeExercised"))) {
                c.errors.add("No native batch executed in section " + pos);
                finish(c);
            } else if (c.samples.size() >= c.requested) finish(c);
        } catch (Throwable failure) {
            c.errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            LOG.error("Mesh comparison failed, vanilla remains selected", failure);
            finish(c);
            if (failure instanceof Error e && !(e instanceof LinkageError) && !(e instanceof AssertionError)) throw e;
        } finally {
            c.busy.set(false);
        }
    }

    private static Map<String, Object> compare(SectionCompiler compiler, SectionPos pos,
            RenderSectionRegion region, VertexSorting sorting, SectionCompiler.Results primary,
            int eligible, int sampleIndex) throws Exception {
        Snapshot reference = Snapshot.read(primary);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("section", List.of(pos.x(), pos.y(), pos.z()));
        result.put("eligiblePackedQuads", eligible);
        result.put("packedLayers", List.of("SOLID", "CUTOUT"));
        result.put("reference", reference.summary());
        Map<ChunkMeshRuntime.Mode, List<Map<String, Long>>> runs = new EnumMap<>(ChunkMeshRuntime.Mode.class);
        for (ChunkMeshRuntime.Mode mode : ChunkMeshRuntime.Mode.values()) runs.put(mode, new ArrayList<>());
        List<String> mismatches = new ArrayList<>();
        ChunkMeshRuntime.Mode[] modes = ChunkMeshRuntime.Mode.values();
        REPLAY.set(true);
        ChunkMeshRuntime.Mode previous = ChunkMeshRuntime.override(ChunkMeshRuntime.Mode.VANILLA);
        try (SectionBufferBuilderPack buffers = new SectionBufferBuilderPack()) {
            // First establish that the original renderer is deterministic for this very snapshot.
            run(compiler, pos, region, sorting, buffers, ChunkMeshRuntime.Mode.VANILLA, reference, null);
            // Two complete warm-up rotations, excluded from timings.
            for (int warm = 0; warm < 2; warm++) {
                for (int offset = 0; offset < 3; offset++) {
                    run(compiler, pos, region, sorting, buffers, modes[(warm + offset + sampleIndex) % 3], reference, null);
                }
            }
            // Rotate order so native is not systematically last. Measure whole SectionCompiler.compile.
            for (int round = 0; round < 5; round++) {
                for (int offset = 0; offset < 3; offset++) {
                    ChunkMeshRuntime.Mode mode = modes[(round + offset + sampleIndex) % 3];
                    run(compiler, pos, region, sorting, buffers, mode, reference, runs.get(mode));
                }
            }
        } catch (MeshMismatch mismatch) {
            mismatches.add(mismatch.getMessage());
            // A VANILLA mismatch can mean an unstable custom model, not a native error.
            if (mismatch.mode != ChunkMeshRuntime.Mode.VANILLA) {
                ChunkMeshRuntime.disableNative("Replay byte mismatch: " + mismatch.getMessage());
            }
        } finally {
            ChunkMeshRuntime.override(previous);
            REPLAY.remove(); LAST.remove();
        }
        Map<String, Object> modeResults = new LinkedHashMap<>();
        boolean nativeUsed = false;
        for (ChunkMeshRuntime.Mode mode : modes) {
            List<Map<String, Long>> values = runs.get(mode);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runs", values);
            out.put("measuredRuns", values.size());
            out.put("detailedTiming", ChunkMeshRuntime.detailedTiming());
            out.put("componentTiming", ChunkMeshRuntime.detailedTiming() ? "measured" : "UNAVAILABLE (-1)");
            out.put("medianWallNanos", median(values, "wallNanos"));
            out.put("medianCompileNanos", median(values, "compileNanos"));
            out.put("medianPreparationNanos", median(values, "packetPreparationNanos"));
            out.put("medianReserveNanos", median(values, "reserveNanos"));
            out.put("medianWriteNanos", median(values, "writeTotalNanos"));
            out.put("medianCopyNanos", median(values, "nativeInputCopyNanos"));
            out.put("medianNativeCallNanos", median(values, "nativeCallNanos"));
            out.put("nativeBatches", values.stream().mapToLong(v -> v.getOrDefault("nativeBatches", 0L)).sum());
            out.put("fallbackBatches", values.stream().mapToLong(v -> v.getOrDefault("fallbackBatches", 0L)).sum());
            modeResults.put(mode.name(), out);
            if (mode == ChunkMeshRuntime.Mode.CPP_PACKED) nativeUsed = values.size() == 5 && values.stream().allMatch(v ->
                    v.getOrDefault("nativeBatches", 0L) > 0 && v.getOrDefault("fallbackBatches", 0L) == 0);
        }
        result.put("modes", modeResults);
        result.put("byteParity", mismatches.isEmpty() && runs.values().stream().allMatch(v -> v.size() == 5));
        result.put("nativeExercised", nativeUsed);
        result.put("mismatches", mismatches);
        return result;
    }

    private static void run(SectionCompiler compiler, SectionPos pos, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers, ChunkMeshRuntime.Mode mode,
            Snapshot reference, List<Map<String, Long>> measurements) throws Exception {
        ChunkMeshRuntime.override(mode);
        LAST.remove();
        long start = System.nanoTime();
        SectionCompiler.Results output = compiler.compile(pos, region, sorting, buffers);
        long wall = System.nanoTime() - start;
        try {
            String mismatch = reference.mismatch(output);
            if (mismatch != null) throw new MeshMismatch(mode, mismatch);
            if (measurements != null) {
                Map<String, Long> measured = LAST.get();
                if (measured == null) throw new IllegalStateException("Section scope hook did not execute");
                Map<String, Long> row = new LinkedHashMap<>(measured);
                row.put("wallNanos", wall);
                measurements.add(row);
            }
        } finally {
            output.release();
            buffers.clearAll();
        }
    }

    private static long median(List<Map<String, Long>> rows, String key) {
        if (rows.isEmpty()) return -1;
        long[] values = rows.stream().mapToLong(v -> v.getOrDefault(key, 0L)).sorted().toArray();
        return values[values.length / 2];
    }

    private static final class MeshMismatch extends Exception {
        final ChunkMeshRuntime.Mode mode;
        MeshMismatch(ChunkMeshRuntime.Mode mode, String detail) { super(mode + ": " + detail); this.mode = mode; }
    }

    private record Layer(byte[] vertices, byte[] indices, MeshData.DrawState state) { }
    private record Snapshot(Map<ChunkSectionLayer, Layer> layers, List<Object> blockEntities) {
        static Snapshot read(SectionCompiler.Results result) {
            Map<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
            result.renderedLayers.forEach((key, mesh) -> layers.put(key,
                    new Layer(bytes(mesh.vertexBuffer()), bytes(mesh.indexBuffer()), mesh.drawState())));
            return new Snapshot(layers, new ArrayList<>(result.blockEntities));
        }
        String mismatch(SectionCompiler.Results result) {
            if (!result.renderedLayers.keySet().equals(layers.keySet())) return "render layer set changed";
            if (!blockEntities.equals(result.blockEntities)) return "block entity list changed";
            for (var entry : layers.entrySet()) {
                Layer expected = entry.getValue();
                MeshData actual = result.renderedLayers.get(entry.getKey());
                if (!expected.state.equals(actual.drawState())) return entry.getKey() + " draw state changed";
                int vertexMismatch = mismatchBytes(expected.vertices, actual.vertexBuffer());
                if (vertexMismatch != -1) return entry.getKey() + " vertex byte " + vertexMismatch;
                int indexMismatch = mismatchBytes(expected.indices, actual.indexBuffer());
                if (indexMismatch != -1) return entry.getKey() + " index byte " + indexMismatch;
            }
            return null;
        }
        Map<String, Object> summary() throws Exception {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (var entry : layers.entrySet()) {
                Layer l = entry.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("vertexCount", l.state.vertexCount()); m.put("indexCount", l.state.indexCount());
                m.put("vertexBytes", l.vertices.length); m.put("vertexSha256", sha(l.vertices));
                m.put("indexBytes", l.indices == null ? 0 : l.indices.length);
                m.put("indexSha256", l.indices == null ? "none" : sha(l.indices));
                summary.put(entry.getKey().name(), m);
            }
            return summary;
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        if (buffer == null) return null;
        ByteBuffer b = buffer.duplicate();
        if (b.remaining() > 16 * 1024 * 1024) throw new IllegalArgumentException("Diagnostic mesh exceeds 16 MiB/layer");
        byte[] data = new byte[b.remaining()]; b.get(data); return data;
    }
    private static int mismatchBytes(byte[] expected, ByteBuffer actual) {
        if (expected == null || actual == null) return expected == null && actual == null ? -1 : 0;
        return ByteBuffer.wrap(expected).mismatch(actual.duplicate());
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static synchronized void finish(Campaign c) {
        if (c.done) return;
        c.done = true;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1); report.put("experiment", "real-section-three-backend-replay");
        report.put("detailedTiming", ChunkMeshRuntime.detailedTiming());
        report.put("componentTiming", ChunkMeshRuntime.detailedTiming() ? "measured" : "UNAVAILABLE (-1); whole compile wall retained");
        report.put("id", c.id); report.put("created", c.created);
        report.put("requestedSamples", c.requested); report.put("completedSamples", c.samples.size());
        report.put("scope", "Sequential replay of an identical real RenderSectionRegion. Whole CPU compile including packet preparation, reserve, copies and FFM. Excludes GPU upload, verification hashing, initial scratch pack allocation and JVM-wide FPS.");
        report.put("warmupRotations", 2); report.put("measuredRunsPerModePerSection", 5);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name")); report.put("arch", System.getProperty("os.arch"));
        report.put("samples", List.copyOf(c.samples)); report.put("errors", List.copyOf(c.errors));
        report.put("complete", c.samples.size() == c.requested && c.errors.isEmpty());
        report.put("status", ChunkMeshRuntime.status());
        try {
            Path folder = FabricLoader.getInstance().getGameDir().resolve("performance-reports");
            Files.createDirectories(folder);
            Path path = folder.resolve("chunk-mesh-" + c.id + ".json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            c.report = path.toString();
            LOG.info("Mesh comparison saved: {} (samples={}, errors={})", path, c.samples.size(), c.errors.size());
        } catch (Exception failure) { LOG.error("Could not save mesh comparison", failure); }
    }

    private static final class Campaign {
        final String id = UUID.randomUUID().toString();
        final String created = Instant.now().toString();
        final int requested;
        final long start = System.nanoTime();
        final AtomicBoolean busy = new AtomicBoolean();
        final List<Map<String, Object>> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile long nextRebuild = start + 1_000_000_000L;
        volatile boolean done;
        volatile String report = "pending";
        Campaign(int requested) { this.requested = requested; }
    }
}
