// Exports bounded native section meshes once per scene for retained C++ camera rendering.
package com.dark.winter.biomeeditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

final class EditorSceneExporter implements AutoCloseable {
    private static final long MAX_MESH_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ASSET_BYTES = 768L * 1024 * 1024;
    private static final int MAX_PARTS = 16384;
    private static final AtomicLong SERIAL = new AtomicLong();
    private final EditorFiles files;
    private final String nonce = UUID.randomUUID().toString().replace("-", "");
    private final ThreadPoolExecutor cpu = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon(true).name("BiomeEditor-native-mesh").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private Job current;
    private Progress terminalProgress;
    // Accessed only on the single CPU executor; immutable atlas assets survive mesh generations.
    private final java.util.Map<Long, JsonObject> atlasAssets = new java.util.HashMap<>();

    record Progress(boolean running, boolean publicationComplete, int completedSections, int totalSections,
            String message, JsonObject manifest, Throwable failure, long resourceEpoch) {
        boolean complete() { return publicationComplete; }
    }

    EditorSceneExporter(EditorFiles files) { this.files = files; }
    long resourceEpoch() { return EditorSceneAtlas.resourceEpoch(); }

    /** The caller has already prepared an owned world and an immutable native 16x16 lightmap file. */
    void start(Minecraft c, BiomeEditorProfile profile, long revision, long sceneRevision,
            JsonObject environment, JsonObject lightmapAsset) {
        owner(c);
        cancel();
        terminalProgress = null;
        if (c.level == null) throw new IllegalStateException("Native scene export requires a client level");
        if (revision < 1 || sceneRevision < 1) throw new IllegalArgumentException("Positive scene revisions required");
        EditorSceneAtlas.Snapshot atlas = EditorSceneAtlas.snapshot();
        var manager = c.getModelManager();
        var compiler = new SectionCompiler(c.options.ambientOcclusion().get(), c.options.cutoutLeaves().get(),
                manager.getBlockStateModelSet(), manager.getFluidStateModelSet(), c.getBlockColors());
        current = new Job(c.level, profile, revision, sceneRevision, environment.deepCopy(), lightmapAsset.deepCopy(),
                atlas, compiler, SERIAL.incrementAndGet());
    }

    /** At most two owner-side snapshots, with a two millisecond budget between snapshots. */
    Progress poll(Minecraft c) {
        owner(c);
        reapCompleted();
        Job job = current;
        if (job == null) return terminalProgress != null ? terminalProgress
                : new Progress(false, false, 0, 0, "idle", null, null, resourceEpoch());
        if (job.failure.get() != null || job.done.get() || job.cancelled.get()) return progress(job);
        if (c.level != job.level || resourceEpoch() != job.atlas.epoch()) {
            job.failOwner(new CancellationException("Native scene or resources changed during export"));
            return progress(job);
        }
        try {
            if (job.sections == null) {
                List<SectionPos> sections = plan(c, job);
                if (sections == null) return progress(job);
                job.sections = sections;
                job.message = "Собирается настоящая геометрия: 0 / " + sections.size();
            }
            if (!job.initialized) {
                if (cpu.getQueue().remainingCapacity() == 0) return progress(job);
                job.initialized = true;
                enqueue(job, job::initialize);
            }
            long until = System.nanoTime() + 2_000_000L;
            int snapshots = 0;
            while (job.nextSection < job.sections.size() && snapshots < 2
                    && cpu.getQueue().remainingCapacity() != 0 && !job.cancelled.get() && job.failure.get() == null) {
                SectionPos section = job.sections.get(job.nextSection);
                RenderSectionRegion region = job.regions.createRegion(job.level, section.asLong());
                enqueue(job, () -> job.compile(section, region));
                job.nextSection++;
                snapshots++;
                if (System.nanoTime() >= until) break;
            }
            if (job.nextSection == job.sections.size() && !job.finalizerQueued
                    && cpu.getQueue().remainingCapacity() != 0) {
                job.finalizerQueued = true;
                enqueue(job, job::publish);
            }
        } catch (Throwable failure) {
            job.failOwner(failure);
        }
        return progress(job);
    }

    private List<SectionPos> plan(Minecraft c, Job job) {
        BiomeEditorProfile p = job.profile;
        for (int z = p.chunkZ() - 1; z <= p.chunkZ() + p.chunks(); z++) {
            for (int x = p.chunkX() - 1; x <= p.chunkX() + p.chunks(); x++) {
                var chunk = c.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null || chunk instanceof EmptyLevelChunk
                        || !c.level.getLightEngine().lightOnInColumn(SectionPos.asLong(x, 0, z))) {
                    job.message = "Ожидаются настоящие клиентские чанки и соседние данные";
                    return null;
                }
            }
        }
        List<SectionPos> sections = new ArrayList<>();
        boolean fullDepth = p.operations().stream().anyMatch(op -> op.enabled()
                && (op.kind().equals("cave") || op.kind().equals("tunnel")));
        for (int dz = 0; dz < p.chunks(); dz++) for (int dx = 0; dx < p.chunks(); dx++) {
            int cx = p.chunkX() + dx, cz = p.chunkZ() + dz;
            LevelChunk chunk = c.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)
                    || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)) {
                job.message = "Ожидаются нативные карты высот клиента";
                return null;
            }
            Heightmap.Types ground = chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                    ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.MOTION_BLOCKING;
            int low = c.level.getMaxY(), high = c.level.getMinY() - 1;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                low = Math.min(low, chunk.getHeight(ground, x, z));
                high = Math.max(high, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            }
            if (high < c.level.getMinY()) continue;
            // A deep cut needs its actual native section, including one section below the cut.
            // Cave profiles retain every section to the native world floor for later local inspection.
            int bottom = fullDepth ? c.level.getMinY() : Math.min(low - 16, job.cutY - 16);
            int first = SectionPos.blockToSectionCoord(Math.max(c.level.getMinY(), bottom));
            int last = SectionPos.blockToSectionCoord(Math.min(high, job.cutY));
            for (int y = first; y <= last; y++) {
                if (!chunk.getSection(chunk.getSectionIndexFromSectionY(y)).hasOnlyAir()) sections.add(SectionPos.of(cx, y, cz));
            }
        }
        if ((long) sections.size() * 3 > MAX_PARTS) throw new IllegalStateException("Native scene exceeds the part limit");
        return List.copyOf(sections);
    }

    private void enqueue(Job job, Checked task) {
        cpu.execute(() -> {
            try {
                job.check();
                task.run();
            } catch (Throwable error) {
                job.failure.compareAndSet(null, error);
                job.abort();
            } finally {
                if (job.cancelled.get()) job.abort();
            }
        });
    }

    private Progress progress(Job job) {
        Throwable error = job.failure.get();
        return new Progress(!job.done.get() && !job.cancelled.get() && error == null, job.published,
                job.completed, job.sections == null ? 0 : job.sections.size(), job.message,
                job.manifest == null ? null : job.manifest.deepCopy(), error, job.atlas.epoch());
    }

    void cancel() {
        Job job = current;
        if (job == null || job.done.get()) return;
        job.cancelled.set(true);
        try { cpu.execute(job::abort); }
        catch (RejectedExecutionException full) {
            // Every queued task checks cancellation and closes this job on the same CPU owner.
            if (cpu.isShutdown()) throw full;
        }
    }

    /** Client-owner handoff after every CPU task has released its native data and snapshot references. */
    private void reapCompleted() {
        Job job = current;
        if (job != null && job.done.get() && cpu.getActiveCount() == 0 && cpu.getQueue().isEmpty()) {
            terminalProgress = progress(job);
            current = null;
        }
    }
    boolean idle() {
        reapCompleted();
        return (current == null || current.done.get()) && cpu.getActiveCount() == 0 && cpu.getQueue().isEmpty();
    }
    @Override public void close() { cancel(); cpu.shutdown(); }
    private static void owner(Minecraft c) {
        if (!c.isSameThread()) throw new IllegalStateException("Native scene snapshots require the client owner");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private final class Job {
        final ClientLevel level;
        final BiomeEditorProfile profile;
        final long revision, sceneRevision;
        final int cutY;
        final JsonObject environment, lightmap;
        final EditorSceneAtlas.Snapshot atlas;
        final SectionCompiler compiler;
        final RenderRegionCache regions = new RenderRegionCache();
        final AtomicBoolean cancelled = new AtomicBoolean(), done = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final String prefix;
        final Path vertexPath, indexPath, vertexTemp, indexTemp;
        final JsonArray parts = new JsonArray();
        final java.util.HashSet<net.minecraft.core.BlockPos> omitted = new java.util.HashSet<>();
        final ByteBuffer output = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        final float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        volatile List<SectionPos> sections;
        volatile int completed;
        volatile String message = "Ожидаются клиентские данные сцены";
        volatile boolean published;
        volatile JsonObject manifest;
        boolean initialized, finalizerQueued, closedChannels;
        int nextSection, vertices, indices;
        FileChannel vertexOut, indexOut;
        SectionBufferBuilderPack builders;
        JsonObject atlasAsset;

        Job(ClientLevel level, BiomeEditorProfile profile, long revision, long sceneRevision, JsonObject environment,
                JsonObject lightmap, EditorSceneAtlas.Snapshot atlas, SectionCompiler compiler, long serial) {
            this.level = level; this.profile = profile; this.revision = revision; this.sceneRevision = sceneRevision;
            this.environment = environment; this.lightmap = lightmap; this.atlas = atlas; this.compiler = compiler;
            JsonObject camera = profile.json().getAsJsonObject("camera");
            cutY = camera.has("cutY") ? camera.get("cutY").getAsInt() : 320;
            prefix = "scene-" + sceneRevision + "-r" + revision + "-e" + atlas.epoch() + "-x" + serial + "-" + nonce;
            vertexPath = files.root.resolve(prefix + "-vertices.bin"); indexPath = files.root.resolve(prefix + "-indices.bin");
            vertexTemp = files.root.resolve(prefix + "-vertices.writing"); indexTemp = files.root.resolve(prefix + "-indices.writing");
        }

        void initialize() throws Exception {
            validateLightmap();
            atlasAsset = atlasAssets.get(atlas.epoch());
            if (atlasAsset == null) {
                byte[] pixels = atlas.stitch();
                String name = "atlas-e" + atlas.epoch() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".rgba";
                Path path = files.root.resolve(name);
                Files.write(path, pixels, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                atlasAsset = asset(path, pixels.length); atlasAsset.addProperty("width", atlas.width());
                atlasAsset.addProperty("height", atlas.height()); atlasAsset.addProperty("format", "RGBA8");
                atlasAssets.put(atlas.epoch(), atlasAsset);
            }
            check();
            vertexOut = FileChannel.open(vertexTemp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            indexOut = FileChannel.open(indexTemp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            builders = new SectionBufferBuilderPack();
        }

        void validateLightmap() throws Exception {
            if (!lightmap.has("file") || !lightmap.get("file").getAsString().matches("[A-Za-z0-9_.-]+")
                    || lightmap.get("bytes").getAsLong() != 1024 || lightmap.get("width").getAsInt() != 16
                    || lightmap.get("height").getAsInt() != 16 || !lightmap.get("format").getAsString().equals("RGBA8")) {
                throw new IOException("A native immutable 16x16 RGBA lightmap asset is required");
            }
            Path path = files.root.resolve(lightmap.get("file").getAsString());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || Files.size(path) != 1024 || !sha(path).equals(lightmap.get("sha256").getAsString())) {
                throw new IOException("Native lightmap asset failed its byte/hash validation");
            }
        }

        void compile(SectionPos section, RenderSectionRegion region) throws Exception {
            check();
            SectionCompiler.Results result = null;
            try {
                result = compiler.compile(section, region, VertexSorting.DISTANCE_TO_ORIGIN, builders);
                for (var blockEntity : result.blockEntities) omitted.add(blockEntity.getBlockPos().immutable());
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    MeshData mesh = result.renderedLayers.get(layer);
                    if (mesh != null) append(section, layer, mesh);
                }
                completed++;
                message = "Собирается настоящая геометрия: " + completed + " / " + sections.size();
            } finally {
                if (result != null) result.release();
                BlockModelLighter.clearCache();
                builders.clearAll();
            }
        }

        void append(SectionPos section, ChunkSectionLayer layer, MeshData mesh) throws Exception {
            var draw = mesh.drawState();
            if (draw.format() != DefaultVertexFormat.BLOCK || draw.format().getVertexSize() != 28
                    || draw.primitiveTopology() != PrimitiveTopology.QUADS || draw.vertexCount() < 0
                    || draw.indexCount() < 0 || draw.indexCount() % 3 != 0) throw new IOException("Unsupported native terrain mesh format");
            int count = draw.vertexCount(), indexCount = draw.indexCount(), firstVertex = vertices, firstIndex = indices;
            if (((long) vertices + count) * 32 + ((long) indices + indexCount) * 4 > MAX_MESH_BYTES
                    || (long) vertices + count > Integer.MAX_VALUE || (long) indices + indexCount > Integer.MAX_VALUE) {
                throw new IOException("Native scene geometry exceeds 512 MiB");
            }
            ByteBuffer nativeVertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            if (nativeVertices.remaining() != Math.multiplyExact(count, 28)) throw new IOException("Native vertex byte count mismatch");
            int base = nativeVertices.position();
            float dx = section.minBlockX() - profile.chunkX() * 16;
            float dy = section.minBlockY(), dz = section.minBlockZ() - profile.chunkZ() * 16;
            float[] partBounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            output.clear();
            for (int v = 0; v < count; v++) {
                if (output.remaining() < 32) flush(vertexOut);
                int offset = base + v * 28;
                float x = nativeVertices.getFloat(offset) + dx, y = nativeVertices.getFloat(offset + 4) + dy,
                        z = nativeVertices.getFloat(offset + 8) + dz, u = nativeVertices.getFloat(offset + 16),
                        texV = nativeVertices.getFloat(offset + 20);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                        || !Float.isFinite(u) || !Float.isFinite(texV)) throw new IOException("Non-finite native vertex");
                output.putFloat(x).putFloat(y).putFloat(z);
                for (int channel = 12; channel < 16; channel++) output.put(nativeVertices.get(offset + channel));
                output.putFloat(u).putFloat(texV);
                int light = nativeVertices.getInt(offset + 24);
                if ((light & 65535) > 240 || (light >>> 16) > 240) throw new IOException("Native packed light exceeds 0..240");
                output.putInt(light).putInt(0);
                bound(partBounds, x, y, z); bound(bounds, x, y, z);
            }
            flush(vertexOut);
            ByteBuffer nativeIndices = mesh.indexBuffer();
            output.clear();
            if (nativeIndices != null) {
                nativeIndices = nativeIndices.duplicate().order(ByteOrder.nativeOrder());
                int bytes = draw.indexType().bytes, indexBase = nativeIndices.position();
                int requiredBytes = Math.multiplyExact(indexCount, bytes);
                int alignedBytes = Math.addExact(requiredBytes, 3) & ~3;
                if (nativeIndices.remaining() != requiredBytes && nativeIndices.remaining() != alignedBytes)
                    throw new IOException("Native index byte count mismatch");
                // Our exact SOLID filter pads a SHORT IBO to four bytes; padding is never an index.
                for (int i = 0; i < indexCount; i++) {
                    long index = draw.indexType() == IndexType.SHORT
                            ? Short.toUnsignedInt(nativeIndices.getShort(indexBase + i * bytes))
                            : Integer.toUnsignedLong(nativeIndices.getInt(indexBase + i * bytes));
                    if (index >= count) throw new IOException("Native mesh index is outside its vertex buffer");
                    putIndex(firstVertex + (int) index);
                }
            } else {
                if (count % 4 != 0 || (long) count / 4 * 6 != indexCount) throw new IOException("Missing explicit native mesh indices");
                for (int q = 0; q < count; q += 4) {
                    putIndex(firstVertex + q); putIndex(firstVertex + q + 1); putIndex(firstVertex + q + 2);
                    putIndex(firstVertex + q + 2); putIndex(firstVertex + q + 3); putIndex(firstVertex + q);
                }
            }
            flush(indexOut);
            vertices += count; indices += indexCount;
            if (indexCount != 0) {
                if (parts.size() >= MAX_PARTS) throw new IOException("Native scene exceeds 16384 draw parts");
                JsonObject part = new JsonObject(); part.addProperty("firstIndex", firstIndex);
                part.addProperty("indexCount", indexCount); part.addProperty("layer", layer.ordinal());
                part.add("bounds", array(partBounds)); parts.add(part);
            }
            check();
        }

        void putIndex(int value) throws IOException { if (output.remaining() < 4) flush(indexOut); output.putInt(value); }
        void flush(FileChannel target) throws IOException { output.flip(); while (output.hasRemaining()) target.write(output); output.clear(); }

        void publish() throws Exception {
            check();
            closeChannels();
            Files.move(vertexTemp, vertexPath, StandardCopyOption.ATOMIC_MOVE);
            Files.move(indexTemp, indexPath, StandardCopyOption.ATOMIC_MOVE);
            long total = Files.size(vertexPath) + Files.size(indexPath) + atlasAsset.get("bytes").getAsLong() + 1024;
            if (total > MAX_ASSET_BYTES) throw new IOException("Native scene exceeds the total asset limit");
            JsonObject scene = new JsonObject();
            scene.addProperty("schema", 1); scene.addProperty("kind", "native-terrain-mesh");
            scene.addProperty("sceneRevision", sceneRevision); scene.addProperty("revision", revision);
            scene.addProperty("resourceEpoch", atlas.epoch());
            JsonArray origin = new JsonArray(); origin.add(profile.chunkX() * 16); origin.add(0); origin.add(profile.chunkZ() * 16);
            scene.add("origin", origin);
            if (vertices == 0) { bounds[0]=0; bounds[1]=level.getMinY(); bounds[2]=0; bounds[3]=profile.chunks()*16; bounds[4]=level.getMinY(); bounds[5]=profile.chunks()*16; }
            scene.add("bounds", array(bounds));
            scene.addProperty("cutY", cutY);
            scene.addProperty("vertexCount", vertices); scene.addProperty("indexCount", indices);
            scene.add("vertices", asset(vertexPath, (long) vertices * 32)); scene.add("indices", asset(indexPath, (long) indices * 4));
            scene.add("atlas", atlasAsset); scene.add("lightmap", lightmap);
            scene.add("environment", environment); scene.add("parts", parts); scene.addProperty("omittedBlockEntities", omitted.size());
            if (EditorFiles.JSON.toJson(scene).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) throw new IOException("Native scene manifest exceeds 1 MiB");
            check();
            EditorFiles.atomic(files.root.resolve("scene.json"), scene);
            manifest = scene; published = true; done.set(true); message = "Настоящая 3D-сцена готова";
        }

        void closeChannels() throws IOException {
            if (closedChannels) return;
            closedChannels = true;
            IOException failure = null;
            if (vertexOut != null) try { vertexOut.close(); } catch (IOException e) { failure=e; }
            if (indexOut != null) try { indexOut.close(); } catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
            if (builders != null) { builders.close(); builders=null; }
            if (failure != null) throw failure;
        }
        void abort() {
            try { closeChannels(); } catch (Throwable error) { failure.compareAndSet(null, error); }
            if (!published) {
                for (Path path : List.of(vertexTemp, indexTemp, vertexPath, indexPath)) {
                    try { Files.deleteIfExists(path); } catch (IOException error) { failure.compareAndSet(null, error); }
                }
            }
            done.set(true);
        }
        void failOwner(Throwable error) {
            failure.compareAndSet(null, error); cancelled.set(true);
            try { cpu.execute(this::abort); }
            catch (RejectedExecutionException full) { if (cpu.isShutdown()) failure.compareAndSet(null, full); }
        }
        void check() {
            if (cancelled.get()) throw new CancellationException("Native scene export cancelled");
            if (failure.get() != null) throw new CancellationException("Native scene export failed");
            if (EditorSceneAtlas.resourceEpoch() != atlas.epoch()) throw new CancellationException("Native resources changed");
        }
    }

    private static void bound(float[] bounds, float x, float y, float z) {
        bounds[0]=Math.min(bounds[0],x); bounds[1]=Math.min(bounds[1],y); bounds[2]=Math.min(bounds[2],z);
        bounds[3]=Math.max(bounds[3],x); bounds[4]=Math.max(bounds[4],y); bounds[5]=Math.max(bounds[5],z);
    }
    private static JsonArray array(float[] values) { JsonArray array=new JsonArray(); for(float value:values) array.add(value); return array; }
    private static JsonObject asset(Path file, long bytes) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file)!=bytes) throw new IOException("Incomplete native asset");
        JsonObject asset=new JsonObject(); asset.addProperty("file",file.getFileName().toString()); asset.addProperty("bytes",bytes);
        asset.addProperty("sha256",sha(file)); return asset;
    }
    private static String sha(Path path) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536];
        try(var input=Files.newInputStream(path)){int length;while((length=input.read(buffer))!=-1)sha.update(buffer,0,length);}
        return HexFormat.of().formatHex(sha.digest());
    }
}
