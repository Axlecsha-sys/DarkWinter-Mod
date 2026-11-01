// Optional experimental renderer-side face culling; always retains vanilla and Fabric renderers.
package com.dark.winter.client.fixes.voxel;

import com.dark.winter.client.mixin.client.SectionCopySnapshotAccess;
import com.dark.winter.client.mixin.client.SectionRegionSnapshotAccess;
import net.fabricmc.loader.api.FabricLoader;
import com.dark.winter.client.fixes.nativebuffers.NativeScratchBuffers;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.dark.winter.client.diagnostics.performance.PerformanceClient;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A mask may skip ONLY the exact vanilla test Block.shouldRenderFace that returns false
 * whenever the adjacent state has a complete, full-block occlusion face. It never emits
 * geometry, skips model parts, changes transparency, modifies fluids or loads chunks.
 */
public final class VoxelFaceRuntime {
    public enum Mode { JAVA, CPP, AUTO }

    private static final Logger LOG = LoggerFactory.getLogger("DarkWinter/VoxelFFM");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("darkwinter/voxel-experiment.properties");
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Set<Block> SAFE_BLOCKS = new HashSet<>();
    private static final AtomicLong SECTIONS = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong PREP_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong SLOW_FALLBACKS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final AtomicLong SCRATCH_EPOCH = new AtomicLong();

    private static volatile Mode mode = Mode.JAVA;
    private static volatile VoxelNativeBridge nativeBridge;
    private static volatile String nativeStatus = "not initialized";
    private static volatile boolean autoApproved;
    private static volatile boolean permanentlyDisabled;
    private static volatile boolean initialized;
    private static volatile long pendingRebuildAt;
    private static volatile boolean cacheAllowed;

    private VoxelFaceRuntime() { }

    public static void initialize() {
        if (initialized) return;
        initSafeBlocks();
        readConfig();
        VoxelFaceCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long due = pendingRebuildAt;
            if (due == 0) return;
            if (!PerformanceClient.isCapturing() || client.level == null) {
                pendingRebuildAt = 0;
                return;
            }
            if (client.gui.screen() == null && System.nanoTime() - due >= 0) {
                pendingRebuildAt = 0;
                client.levelExtractor.allChanged();
                LOG.info("Voxel experiment: native section rebuild requested for DV-032 capture, mode={}", mode);
            }
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
                Identifier.fromNamespaceAndPath("darkwinter", "voxel_scratch"),
                (ResourceManagerReloadListener) manager -> releaseScratchCaches());
        initialized = true;
        if (mode != Mode.JAVA) activateNative();
        LOG.info("Voxel experiment: requested={}, effective={}, native={}",
                mode, effective(), nativeStatus);
    }

    private static void initSafeBlocks() {
        // Exact vanilla blocks only, with default states verified to have six full occlusion faces.
        for (Block block : new Block[] {
                Blocks.STONE, Blocks.DIRT, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
                Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.BEDROCK
        }) {
            BlockState state = block.defaultBlockState();
            boolean full = true;
            for (Direction direction : Direction.values()) {
                if (state.getFaceOcclusionShape(direction) != Shapes.block()) {
                    full = false;
                    break;
                }
            }
            if (full) SAFE_BLOCKS.add(block);
            else LOG.warn("Voxel experiment excluded non-cubic state {}", block);
        }
        if (SAFE_BLOCKS.isEmpty()) LOG.warn("Voxel native experiment disabled: no verified full blocks");
    }

    private static boolean safe(BlockState state) {
        return state != null && SAFE_BLOCKS.contains(state.getBlock());
    }

    private static void readConfig() {
        if (!Files.isRegularFile(CONFIG)) return;
        try (var input = Files.newInputStream(CONFIG)) {
            Properties settings = new Properties();
            settings.load(input);
            mode = Mode.valueOf(settings.getProperty("mode", "JAVA").trim().toUpperCase(Locale.ROOT));
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("Cannot read voxel experimental mode; using JAVA", failure);
            mode = Mode.JAVA;
        }
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(CONFIG.getParent());
            Properties settings = new Properties();
            settings.setProperty("mode", mode.name());
            Path temp = Files.createTempFile(CONFIG.getParent(), "voxel-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temp)) {
                    settings.store(output, "Experimental standard-block culling only");
                }
                try {
                    Files.move(temp, CONFIG, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, CONFIG, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException failure) {
            LOG.warn("Cannot save voxel experimental mode", failure);
        }
    }

    public static Mode requested() { return mode; }

    public static void captureRebuild(int seconds) {
        if (seconds < 5 || seconds > 60) throw new IllegalArgumentException("seconds must be 5..60");
        if (PerformanceClient.isCapturing()) throw new IllegalStateException("Another performance capture is already running");
        PerformanceClient.start("voxel-" + mode.name().toLowerCase(Locale.ROOT) + "-rebuild",
                "unverified", "voxel-experiment", 65536, seconds * 1000L);
        PerformanceClient.notifyWhenComplete();
        pendingRebuildAt = System.nanoTime() + 1_000_000_000L;
    }

    public static String effective() {
        if (mode == Mode.JAVA || permanentlyDisabled || nativeBridge == null) return "JAVA";
        if (mode == Mode.AUTO && !autoApproved) return "JAVA";
        return "CPP";
    }

    public static synchronized String setMode(Mode value) {
        if (value == null) throw new IllegalArgumentException("mode is missing");
        mode = value;
        if (value != Mode.JAVA) activateNative();
        else releaseScratchCaches();
        saveConfig();
        return status();
    }

    private static Path developmentLibrary() {
        String explicitlyConfigured = System.getProperty("darkwinter.voxel.native.library", "");
        if (!explicitlyConfigured.isBlank()) return Path.of(explicitlyConfigured);
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return null;
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path root = gameDir.getParent();
        if (root == null) return null;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String filename = os.contains("win") ? "dw_voxel_faces.dll"
                : os.contains("mac") ? "libdw_voxel_faces.dylib" : "libdw_voxel_faces.so";
        return root.resolve("build/voxel-faces/bin").resolve(filename);
    }

    private static synchronized void activateNative() {
        if (permanentlyDisabled) return;
        if (nativeBridge == null) {
            Path path = developmentLibrary();
            try {
                nativeBridge = VoxelNativeBridge.load(path);
                nativeStatus = "verified " + path.getFileName();
            } catch (Throwable failure) {
                if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
                nativeStatus = "fallback: " + failure.getClass().getSimpleName();
                LOG.warn("Voxel native unavailable; full vanilla fallback: {}", failure.toString());
                return;
            }
        }
        if (mode == Mode.AUTO) {
            try {
                autoApproved = nativeBridge.measurablyFasterThanJava();
                if (!autoApproved) releaseScratchCaches();
            } catch (Throwable failure) {
                if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
                disableNative(failure);
            }
        } else {
            autoApproved = false;
        }
    }

    private static synchronized void disableNative(Throwable failure) {
        permanentlyDisabled = true;
        // Keep the loaded library alive: another worker may still be returning from a downcall.
        releaseScratchCaches();
        nativeStatus = "disabled after exception: " + failure.getClass().getSimpleName();
        LOG.error("Voxel native backend disabled; reverting to the original renderers", failure);
    }

    /** Loaded DLLs have application lifetime; only their separately owned worker scratch is retired. */
    public static void releaseScratchCaches() {
        SCRATCH_EPOCH.incrementAndGet();
        NativeScratchBuffers.retireAll(NativeScratchBuffers.Kind.VOXEL);
    }

    static boolean scratchCachingAllowed() { return cacheAllowed && mode != Mode.JAVA && !permanentlyDisabled; }

    public static java.util.Map<String, Long> memorySnapshot() {
        java.util.Map<String, Long> result = new java.util.LinkedHashMap<>(
                NativeScratchBuffers.memorySnapshot(NativeScratchBuffers.Kind.VOXEL));
        result.put("libraryLoaded", nativeBridge == null ? 0L : 1L);
        result.put("cacheAllowed", cacheAllowed ? 1L : 0L);
        result.put("cacheEpoch", SCRATCH_EPOCH.get());
        return java.util.Collections.unmodifiableMap(result);
    }

    private static final Scope NOOP = () -> { };
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static Scope begin(SectionPos position, RenderSectionRegion region) {
        if (!cacheAllowed || !"CPP".equals(effective()) || SAFE_BLOCKS.isEmpty()) return NOOP;
        long epoch = SCRATCH_EPOCH.get();
        try {
            SectionRegionSnapshotAccess snapshot = (SectionRegionSnapshotAccess) region;
            if (position.x() != snapshot.darkwinter$minSectionX() + 1 ||
                position.y() != snapshot.darkwinter$minSectionY() + 1 ||
                position.z() != snapshot.darkwinter$minSectionZ() + 1) return NOOP;
            SectionCopySnapshotAccess center = (SectionCopySnapshotAccess) snapshot.darkwinter$sections()[13];
            if (center.darkwinter$debug() || center.darkwinter$section() == null) return NOOP;
            // If the immutable palette has no eligible full cube, avoid 5,832 block lookups.
            var centerPalette = center.darkwinter$section();
            if (!centerPalette.maybeHas(VoxelFaceRuntime::safe)) return NOOP;

            int originX = position.minBlockX(), originY = position.minBlockY(), originZ = position.minBlockZ();
            byte[] halo = new byte[VoxelNativeBridge.HALO_SIZE];
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            long started = System.nanoTime();
            int eligibleCount = 0;
            for (int y = 0; y < 18; y++) {
                for (int z = 0; z < 18; z++) {
                    for (int x = 0; x < 18; x++) {
                        boolean innerX = x > 0 && x < 17;
                        boolean innerY = y > 0 && y < 17;
                        boolean innerZ = z > 0 && z < 17;
                        int innerAxes = (innerX ? 1 : 0) + (innerY ? 1 : 0) + (innerZ ? 1 : 0);
                        if (innerAxes < 2) continue; // Diagonal halo edges are never sampled by the kernel.
                        BlockState state;
                        if (innerAxes == 3) {
                            // Central immutable palette: avoid section lookup and mutable BlockPos altogether.
                            state = centerPalette.get(x - 1, y - 1, z - 1);
                        } else {
                            cursor.set(originX + x - 1, originY + y - 1, originZ + z - 1);
                            state = region.getBlockState(cursor);
                        }
                        if (safe(state)) {
                            halo[(y * 18 + z) * 18 + x] = 1;
                            if (innerAxes == 3) eligibleCount++;
                        }
                    }
                }
            }
            if (eligibleCount < 64) {
                SLOW_FALLBACKS.incrementAndGet();
                return NOOP;
            }
            VoxelNativeBridge bridge = nativeBridge;
            if (!cacheAllowed || bridge == null || epoch != SCRATCH_EPOCH.get()) return NOOP;
            byte[] masks = new byte[VoxelNativeBridge.MASK_SIZE];
            long nativeBegin = System.nanoTime();
            int faces = bridge.compute(halo, masks);
            if (!cacheAllowed || epoch != SCRATCH_EPOCH.get()) {
                bridge.releaseScratchCaches();
                return NOOP;
            }
            NATIVE_NANOS.addAndGet(System.nanoTime() - nativeBegin);

            long sectionNumber = SECTIONS.incrementAndGet();
            if (sectionNumber <= 8 || (sectionNumber & 127) == 0) {
                byte[] reference = new byte[VoxelNativeBridge.MASK_SIZE];
                int javaFaces = VoxelNativeBridge.computeJava(halo, reference);
                if (javaFaces != faces || !Arrays.equals(reference, masks)) {
                    MISMATCHES.incrementAndGet();
                    disableNative(new IllegalStateException("Real section Java/C++ masks diverged"));
                    return NOOP;
                }
            }
            PREP_NANOS.addAndGet(System.nanoTime() - started);
            Context previous = CURRENT.get();
            Context active = new Context(region, originX, originY, originZ, masks);
            CURRENT.set(active);
            return () -> {
                SKIPPED.addAndGet(active.skipped);
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            };
        } catch (Throwable failure) {
            if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
            disableNative(failure);
            return NOOP;
        }
    }

    private static final class Context {
        final RenderSectionRegion region;
        final int x, y, z;
        final byte[] masks;
        long skipped;
        Context(RenderSectionRegion region, int x, int y, int z, byte[] masks) {
            this.region = region; this.x = x; this.y = y; this.z = z; this.masks = masks;
        }
    }

    /** Both vanilla ModelBlockRenderer and Indigo use the same Block.shouldRenderFace contract. */
    public static boolean maySkip(BlockAndTintGetter level, BlockState state, BlockPos position, Direction face) {
        Context current = CURRENT.get();
        return current != null && maySkip(current, level, state,
                position.getX(), position.getY(), position.getZ(), face);
    }

    /** Called by vanilla with already computed neighbor position; no BlockPos allocations. */
    public static boolean maySkipNeighbor(BlockAndTintGetter level, BlockState state,
                                          BlockPos neighbor, Direction face) {
        Context current = CURRENT.get();
        return current != null && maySkip(current, level, state,
                neighbor.getX() - face.getStepX(),
                neighbor.getY() - face.getStepY(),
                neighbor.getZ() - face.getStepZ(), face);
    }

    private static boolean maySkip(Context current, BlockAndTintGetter level, BlockState state,
                                   int px, int py, int pz, Direction direction) {
        if (level != current.region || !safe(state)) return false;
        int x = px - current.x, y = py - current.y, z = pz - current.z;
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) return false;
        int bit = switch (direction) {
            case WEST -> 1;
            case EAST -> 2;
            case DOWN -> 4;
            case UP -> 8;
            case NORTH -> 16;
            case SOUTH -> 32;
        };
        boolean skip = (current.masks[(y * 16 + z) * 16 + x] & bit) == 0;
        if (skip) current.skipped++;
        return skip;
    }

    public static String status() {
        long sections = SECTIONS.get();
        long prepUs = PREP_NANOS.get() / 1000L;
        long nativeUs = NATIVE_NANOS.get() / 1000L;
        return "requested=" + mode + ", effective=" + effective()
                + ", DLL=" + nativeStatus + ", sections=" + sections
                + ", bypassed cull checks=" + SKIPPED.get()
                + ", average full prep=" + (sections == 0 ? 0 : prepUs / sections) + "us"
                + ", average FFM+copies=" + (sections == 0 ? 0 : nativeUs / sections) + "us"
                + ", underfilled sections=" + SLOW_FALLBACKS.get()
                + ", mismatches=" + MISMATCHES.get();
    }
}
