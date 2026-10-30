// Flexible woody outer twigs and their needles, attached to the ordinary chunk-rendered skeleton.
package com.dark.winter.client;

import com.dark.winter.client.weather.WeatherClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Flexible woody outer twigs and their needles, attached to the ordinary chunk-rendered skeleton. */
public final class SpruceNeedleRenderer {
    public static final int RADIUS = 24;
    public static final int MAX_SPRIGS = 384;
    public static final int MAX_BLOCK_CHECKS_PER_TICK = 4096;
    private static final double SWAY_PERIOD_SECONDS = 2.0 * Math.PI / 0.017;
    private static final int INSTANCE_BYTES = 64;
    private static final int SEGMENTS = 4;
    private static final int NEEDLE_VERTICES = 2 * (SEGMENTS - 1) * 6;
    private static final int WOOD_VERTICES = 4 * SEGMENTS * 6;
    private static final Direction[] FACES = Direction.values();
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("darkwinter", "textures/block/spruce_branch.png");
    private static final Identifier BARK_TEXTURE = Identifier.withDefaultNamespace("textures/block/spruce_log.png");
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "DarkWinter spruce wind");
    private static final VertexFormat INSTANCES = VertexFormat.builder(1)
            .addAttribute("AnchorPhase", GpuFormat.RGBA32_FLOAT)
            .addAttribute("DirectionLength", GpuFormat.RGBA32_FLOAT)
            .addAttribute("NeedleColor", GpuFormat.RGBA32_FLOAT)
            .addAttribute("LightSize", GpuFormat.RGBA32_FLOAT).build();
    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("darkwinter", "pipeline/spruce_needles"))
            .withVertexShader(Identifier.fromNamespaceAndPath("darkwinter", "core/spruce_needles"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("darkwinter", "core/spruce_needles"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SpruceWind", UniformType.UNIFORM_BUFFER).build())
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX).withVertexBinding(1, INSTANCES)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT).build());
    private static final SpruceNeedleRenderer INSTANCE = new SpruceNeedleRenderer();

    private ClientLevel world;
    private long epoch, revision, uploads, frames, rootInvalidations;
    private Batch batch = new Batch(0, 0, BlockPos.ZERO, List.of());
    private BlockPos scanCenter;
    private List<SectionPos> sections = List.of();
    private final ArrayList<Sprig> pending = new ArrayList<>();
    // Client-tick scratch only: shape() consumes all six states immediately; neither escapes into a batch.
    private final BlockState[] neighborStates = new BlockState[FACES.length];
    private final BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();
    private int sectionIndex, blockIndex, waitTicks, checked, drawn;
    private float lastAmplitude, lastWindX, lastWindZ;
    private GpuBuffer vertices;
    private MappableRingBuffer instances, wind;
    private final Map<GpuBuffer, Long> uploadedRevisions = new HashMap<>();

    private SpruceNeedleRenderer() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        LevelExtractionEvents.END_EXTRACTION.register(INSTANCE::extract);
        // Earlier terrain events run inside vanilla's still-open RenderPass in 26.3.
        // END_MAIN runs after those passes close, and before DarkWinter's world postprocessing.
        LevelRenderEvents.END_MAIN.register(INSTANCE::draw);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> INSTANCE.reset());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("darkwinter", "spruce_needles"),
                (ResourceManagerReloadListener) manager -> INSTANCE.reset());
    }

    public record Metrics(int sprigs, int blockChecks, int drawnSprigs, long uploads, long renderedFrames,
                          float windAmplitude, boolean gpuAllocated, long rootInvalidations) {}

    public static Metrics metrics() {
        return new Metrics(INSTANCE.batch.sprigs.size(), INSTANCE.checked, INSTANCE.drawn, INSTANCE.uploads,
                INSTANCE.frames, INSTANCE.lastAmplitude, INSTANCE.vertices != null, INSTANCE.rootInvalidations);
    }

    public static int instanceCount() { return INSTANCE.batch.sprigs.size(); }
    public static int lastDrawCount() { return INSTANCE.drawn; }
    public static float lastWindX() { return INSTANCE.lastWindX; }
    public static float lastWindZ() { return INSTANCE.lastWindZ; }
    public static float lastWindAmplitude() { return INSTANCE.lastAmplitude; }

    /** Displacement is exactly zero in calm air, and bounded even in the strongest storm. */
    public static float windAmplitude(float metersPerSecond) {
        return Math.clamp(metersPerSecond * 0.006F, 0.0F, 0.22F);
    }

    private void tick(Minecraft client) {
        checked = 0;
        if (client.level != world) {
            reset();
            world = client.level;
        }
        if (world == null || client.player == null || !Level.OVERWORLD.equals(world.dimension()) || client.isPaused()) return;
        Vec3 camera = client.gameRenderer.mainCamera().position();

        // Two forks share a leaf/root signature. Recheck all six neighbors once per leaf, so breaking
        // its supporting log or changing the chosen parent cannot leave a detached moving twig.
        Map<BlockPos, Boolean> validity = new HashMap<>();
        List<Sprig> alive = batch.sprigs.stream().filter(sprig -> validity.computeIfAbsent(sprig.pos,
                ignored -> stillPresent(sprig, camera))).toList();
        if (alive.size() != batch.sprigs.size()) publish(batch.origin, alive);
        BlockPos center = BlockPos.containing(camera);
        if (scanCenter == null || scanCenter.distSqr(center) >= 64) beginScan(center);
        if (sections.isEmpty()) {
            if (--waitTicks > 0) return;
            beginScan(center);
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (sectionIndex < sections.size() && checked <= MAX_BLOCK_CHECKS_PER_TICK - 8 && pending.size() < MAX_SPRIGS) {
            SectionPos sectionPos = sections.get(sectionIndex);
            var chunk = world.getChunkSource().getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.FULL, false);
            if (chunk == null || sectionPos.y() < world.getMinSectionY() || sectionPos.y() > world.getMaxSectionY()) {
                nextSection();
                continue;
            }
            var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionPos.y()));
            if (blockIndex == 0 && !section.maybeHas(state -> state.is(Blocks.SPRUCE_LEAVES))) {
                nextSection();
                continue;
            }
            BlockPos origin = sectionPos.origin();
            while (blockIndex < 4096 && checked <= MAX_BLOCK_CHECKS_PER_TICK - 8 && pending.size() < MAX_SPRIGS) {
                int index = blockIndex++;
                int x = index & 15, y = index >>> 8, z = (index >>> 4) & 15;
                checked++;
                BlockState state = section.getBlockState(x, y, z);
                if (!state.is(Blocks.SPRUCE_LEAVES)) continue;
                cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                if (cursor.distSqr(scanCenter) > RADIUS * RADIUS) continue;
                addSprig(client, cursor, state);
            }
            if (blockIndex == 4096) nextSection();
        }
        if (sectionIndex == sections.size() || pending.size() == MAX_SPRIGS) {
            publish(scanCenter, List.copyOf(pending));
            pending.clear();
            sections = List.of();
            waitTicks = 20;
        }
    }

    private boolean stillPresent(Sprig sprig, Vec3 camera) {
        if (sprig.pos.distToCenterSqr(camera.x, camera.y, camera.z) > (RADIUS + 4) * (RADIUS + 4)) return false;
        BlockPos neighbor = neighborCursor.setWithOffset(sprig.pos, sprig.face);
        if (!loaded(sprig.pos) || !loaded(neighbor)) return false;
        checked++;
        BlockState state = world.getBlockState(sprig.pos);
        if (!state.is(Blocks.SPRUCE_LEAVES)) return false;
        BlockState[] neighbors = neighborStates;
        for (Direction face : FACES) {
            BlockPos adjacent = neighborCursor.setWithOffset(sprig.pos, face);
            if (!loaded(adjacent)) return false;
            checked++;
            neighbors[face.ordinal()] = world.getBlockState(adjacent);
        }
        if (SpruceBranches.shape(sprig.pos, state, neighbors).key() != sprig.shapeKey) {
            rootInvalidations++;
            return false;
        }
        return neighbors[sprig.face.ordinal()].isAir();
    }

    private boolean loaded(BlockPos pos) {
        return world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }

    private void addSprig(Minecraft client, BlockPos pos, BlockState state) {
        long hash = hash(pos.asLong());
        BlockState[] neighbors = neighborStates;
        for (Direction face : FACES) {
            BlockPos neighbor = neighborCursor.setWithOffset(pos, face);
            if (!loaded(neighbor)) return;
            checked++;
            neighbors[face.ordinal()] = world.getBlockState(neighbor);
        }
        int first = (int) ((hash >>> 1) % FACES.length);
        for (int i = 0; i < FACES.length; i++) {
            Direction face = FACES[(first + i) % FACES.length];
            if (!neighbors[face.ordinal()].isAir()) continue;
            BlockPos outside = neighborCursor.setWithOffset(pos, face);
            var source = client.getBlockColors().getTintSource(Blocks.SPRUCE_LEAVES.defaultBlockState(), 0);
            int color = source == null ? 0xFF619961 : source.colorInWorld(Blocks.SPRUCE_LEAVES.defaultBlockState(), world, pos);
            int blockLight = world.getBrightness(LightLayer.BLOCK, outside) * 16;
            int skyLight = world.getBrightness(LightLayer.SKY, outside) * 16;
            var shape = SpruceBranches.shape(pos, state, neighbors);
            for (var branch : shape.windBranches()) {
                if (pending.size() == MAX_SPRIGS) break;
                // A shared phase makes forks belonging to one bough sway coherently.
                pending.add(new Sprig(pos.immutable(), face, color, blockLight, skyLight, hash, shape.key(), branch));
            }
            return;
        }
    }

    private void beginScan(BlockPos center) {
        scanCenter = center;
        ArrayList<SectionPos> next = new ArrayList<>();
        for (int x = (center.getX() - RADIUS) >> 4; x <= (center.getX() + RADIUS) >> 4; x++) {
            for (int z = (center.getZ() - RADIUS) >> 4; z <= (center.getZ() + RADIUS) >> 4; z++) {
                for (int y = (center.getY() - RADIUS) >> 4; y <= (center.getY() + RADIUS) >> 4; y++) {
                    next.add(SectionPos.of(x, y, z));
                }
            }
        }
        next.sort(Comparator.comparingDouble(section -> section.origin().offset(8, 8, 8).distSqr(center)));
        sections = next;
        sectionIndex = blockIndex = 0;
        pending.clear();
    }

    private void nextSection() { sectionIndex++; blockIndex = 0; }

    private void publish(BlockPos origin, List<Sprig> sprigs) {
        if (!batch.origin.equals(origin) || !batch.sprigs.equals(sprigs)) {
            batch = new Batch(epoch, ++revision, origin, sprigs);
        }
    }

    private void extract(LevelExtractionContext context) {
        var state = context.levelState();
        if (!Level.OVERWORLD.equals(context.level().dimension()) || !state.cameraRenderState.initialized
                || state.cameraRenderState.isPanoramicMode || !WeatherClient.active()) {
            state.setData(FRAME, null);
            return;
        }
        var climate = WeatherClient.climate(context.deltaTracker().getGameTimeDeltaPartialTick(false));
        if (climate == null) return;
        // All three shader frequencies complete whole cycles at this period (100, 231 and 137).
        // Wrap in double precision before upload, preserving continuity and a stable paused game clock.
        float seconds = (float) (((state.gameTime + (double) state.worldPartialTicks) / 20.0) % SWAY_PERIOD_SECONDS);
        state.setData(FRAME, new Frame(batch, climate.windX(), climate.windZ(), windAmplitude(climate.windMetersPerSecond()), seconds));
    }

    private void draw(LevelTerrainRenderContext context) {
        drawn = 0;
        Frame frame = context.levelState().getData(FRAME);
        if (frame == null || frame.batch.epoch != epoch || frame.batch.sprigs.isEmpty()) return;
        ensureBuffers();
        var camera = context.levelState().cameraRenderState;
        GpuBuffer instanceBuffer = instances.currentBuffer();
        if (uploadedRevisions.getOrDefault(instanceBuffer, -1L) != frame.batch.revision) {
            try (var mapped = instanceBuffer.map(0, (long) INSTANCE_BYTES * frame.batch.sprigs.size(), false, true)) {
                ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
                for (Sprig sprig : frame.batch.sprigs) writeInstance(data, sprig, frame.batch.origin);
            }
            uploadedRevisions.put(instanceBuffer, frame.batch.revision);
            uploads++;
        }
        GpuBuffer uniformBuffer = wind.currentBuffer();
        try (var mapped = uniformBuffer.map(false, true)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(frame.windX).putFloat(frame.windZ).putFloat(frame.amplitude).putFloat(frame.seconds);
            data.putFloat((float) (frame.batch.origin.getX() - camera.pos.x))
                    .putFloat((float) (frame.batch.origin.getY() - camera.pos.y))
                    .putFloat((float) (frame.batch.origin.getZ() - camera.pos.z)).putFloat(RADIUS);
        }
        var game = context.gameRenderer();
        var target = game.mainRenderTarget();
        // Texture loading, mapped-buffer writes and dynamic-UBO allocation must precede the pass.
        var texture = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
        var bark = Minecraft.getInstance().getTextureManager().getTexture(BARK_TEXTURE);
        var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(camera.viewRotationMatrix));
        var lightmap = game.lightmap();
        var lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var pipeline = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "DarkWinter spruce needles", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("SpruceWind", uniformBuffer);
            pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
            pass.setUniform("Sampler2", lightmap, lightmapSampler);
            pass.setVertexBuffer(0, vertices.slice());
            pass.setVertexBuffer(1, instanceBuffer.slice());
            pass.draw(NEEDLE_VERTICES, frame.batch.sprigs.size(), 0, 0);
            pass.setUniform("Sampler0", bark.getTextureView(), bark.getSampler());
            pass.draw(WOOD_VERTICES, frame.batch.sprigs.size(), NEEDLE_VERTICES, 0);
        }
        instances.rotate();
        wind.rotate();
        drawn = frame.batch.sprigs.size();
        lastAmplitude = frame.amplitude;
        lastWindX = frame.windX;
        lastWindZ = frame.windZ;
        frames++;
    }

    private void ensureBuffers() {
        if (vertices != null) return;
        ByteBuffer data = ByteBuffer.allocateDirect((NEEDLE_VERTICES + WOOD_VERTICES) * 20).order(ByteOrder.nativeOrder());
        int[] corners = {0, 1, 2, 0, 2, 3};
        float[] sides = {-1, 1, 1, -1};
        float[] roots = {0, 0, 1, 1};
        for (int plane = 0; plane < 2; plane++) {
            for (int segment = 1; segment < SEGMENTS; segment++) {
                for (int corner : corners) {
                    float along = (segment + roots[corner]) / SEGMENTS;
                    // The first quarter stays a visible wooden stalk. Needles begin on this stalk,
                    // then share exactly the same world-space bending as its tapered wooden core.
                    data.putFloat(sides[corner]).putFloat(along).putFloat(plane);
                    data.putFloat((sides[corner] + 1) * 0.5F)
                            .putFloat(SpruceBranches.ROOT_V + (along - 0.25F) / 0.75F
                                    * (SpruceBranches.TIP_V - SpruceBranches.ROOT_V));
                }
            }
        }
        float[] squareX = {-1, 1, 1, -1};
        float[] squareZ = {-1, -1, 1, 1};
        for (int face = 0; face < 4; face++) {
            for (int segment = 0; segment < SEGMENTS; segment++) {
                for (int corner : corners) {
                    int cross = (face + (corner == 1 || corner == 2 ? 1 : 0)) & 3;
                    float along = (segment + roots[corner]) / SEGMENTS;
                    data.putFloat(squareX[cross]).putFloat(along).putFloat(3 + squareZ[cross]);
                    data.putFloat(corner == 1 || corner == 2 ? 0.25F : 0).putFloat(along);
                }
            }
        }
        data.flip();
        vertices = RenderSystem.getDevice().createBuffer(() -> "DarkWinter spruce card", GpuBuffer.USAGE_VERTEX, data);
        instances = new MappableRingBuffer(() -> "DarkWinter spruce instances", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                MAX_SPRIGS * INSTANCE_BYTES);
        wind = new MappableRingBuffer(() -> "DarkWinter spruce wind", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 32);
    }

    private static void writeInstance(ByteBuffer data, Sprig sprig, BlockPos origin) {
        var branch = sprig.branch;
        float phase = ((sprig.seed >>> 8) & 65535) / 65535.0F * 6.2831853F;
        float dx = branch.endX() - branch.x(), dy = branch.endY() - branch.y(), dz = branch.endZ() - branch.z();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        // The root is an actual point on the static skeleton, shared by both wood and needle vertices.
        data.putFloat(sprig.pos.getX() - origin.getX() + branch.x())
                .putFloat(sprig.pos.getY() - origin.getY() + branch.y())
                .putFloat(sprig.pos.getZ() - origin.getZ() + branch.z()).putFloat(phase);
        data.putFloat(dx / length).putFloat(dy / length).putFloat(dz / length).putFloat(length);
        data.putFloat(((sprig.color >>> 16) & 255) / 255.0F).putFloat(((sprig.color >>> 8) & 255) / 255.0F)
                .putFloat((sprig.color & 255) / 255.0F).putFloat(1);
        data.putFloat(sprig.blockLight).putFloat(sprig.skyLight).putFloat(branch.width()).putFloat(0.026F);
    }

    private void reset() {
        if (vertices != null) vertices.close();
        if (instances != null) instances.close();
        if (wind != null) wind.close();
        vertices = null;
        instances = wind = null;
        uploadedRevisions.clear();
        world = null;
        epoch++;
        batch = new Batch(epoch, ++revision, BlockPos.ZERO, List.of());
        scanCenter = null;
        sections = List.of();
        pending.clear();
        sectionIndex = blockIndex = waitTicks = drawn = 0;
        lastAmplitude = lastWindX = lastWindZ = 0;
    }

    private static long hash(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Sprig(BlockPos pos, Direction face, int color, int blockLight, int skyLight, long seed, int shapeKey,
                         SpruceBranches.Branch branch) {}
    private record Batch(long epoch, long revision, BlockPos origin, List<Sprig> sprigs) {}
    private record Frame(Batch batch, float windX, float windZ, float amplitude, float seconds) {}
}
