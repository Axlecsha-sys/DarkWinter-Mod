// Runs explicitly requested local performance captures and exports immutable reports after sampling stops.
package com.dark.winter.client.diagnostics.performance;

import com.dark.winter.client.TestingAccess;
import com.dark.winter.diagnostics.performance.PerformanceCapture;
import com.dark.winter.diagnostics.performance.PerformanceRuntime;
import com.dark.winter.visuals.SceneFrame;
import com.dark.winter.visuals.VisualConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

public final class PerformanceClient {
    private static final Gson JSON = new Gson();
    private static final PerformanceGpuSampler.CompletedSample GPU_SAMPLE = (frame, nanos) -> {
        PerformanceCapture capture = PerformanceRuntime.active();
        if (capture != null) capture.gpu(frame, nanos);
    };
    private static PerformanceGpuSampler gpu;
    private static ThreadMXBean threadCpu;
    private static PerformanceCapture rendering;
    private static ClientLevel world;
    private static String initialConfig;
    private static long previousFrame, renderStarted, cpuStarted, frameId, cpuCaptureStarted;
    private static boolean perRenderCpu;
    private static int effectiveFrameLimit;
    private static volatile Map<String,Object> lastReport;
    private static boolean notifyCompletion;
    private static boolean contextInvalidated;
    private static int width, height, renderDistance, simulationDistance, fov, frameLimit;
    private static boolean vsync, oit;
    private static double cameraX, cameraY, cameraZ;
    private static float cameraYaw, cameraPitch;
    private PerformanceClient() {}

    public static void initialize() {
        PerformanceCommands.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (PerformanceRuntime.active() != null) stop("client_stopping");
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PerformanceCapture capture = PerformanceRuntime.active();
            if (capture == null) return;
            if (client.level != world) {
                capture.unavailable("worldChanged", "The client world changed during capture");
                complete("world_changed");
            } else if (capture.expired()) {
                complete("duration");
            }
        });
    }

    public static void start(String scenario, String sceneHash, String harness, int capacity, long durationMillis) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) throw new IllegalStateException("Load a world before capturing");
        if (durationMillis < 1 || durationMillis > 60_000) throw new IllegalArgumentException("Duration must be 1..60000 ms");
        if (isCapturing()) throw new IllegalStateException("A performance capture is already active");
        var metadata = metadata(client, scenario, sceneHash, harness);
        initialConfig = metadata.get("configHash");
        PerformanceCapture capture = PerformanceRuntime.start(metadata, capacity, durationMillis * 1_000_000L);
        world = client.level;
        previousFrame = 0; frameId = 0; rendering = null; lastReport = null; notifyCompletion = false;
        try {
            threadCpu = ManagementFactory.getThreadMXBean();
        if (!threadCpu.isCurrentThreadCpuTimeSupported() || !threadCpu.isThreadCpuTimeEnabled()) {
            threadCpu = null;
            capture.unavailable("renderCpu", "Thread CPU time is unavailable or disabled; no JVM flags were changed");
        }
            cpuCaptureStarted = threadCpu == null ? -1 : threadCpu.getCurrentThreadCpuTime();
            perRenderCpu = threadCpu != null;
            effectiveFrameLimit = client.getFramerateLimitTracker().getFramerateLimit();
            gpu = new PerformanceGpuSampler();
            contextInvalidated = false;
            width = client.getWindow().getWidth(); height = client.getWindow().getHeight();
            renderDistance = client.options.renderDistance().get(); simulationDistance = client.options.simulationDistance().get();
            fov = client.options.fov().get(); frameLimit = client.options.framerateLimit().get();
            vsync = client.options.enableVsync().get(); oit = client.options.improvedTransparency().get();
            var camera = client.gameRenderer.mainCamera();
            cameraX = camera.position().x; cameraY = camera.position().y; cameraZ = camera.position().z;
            cameraYaw = camera.yRot(); cameraPitch = camera.xRot();
        } catch (RuntimeException | Error failure) {
            PerformanceRuntime.stop("initialization_failed");
            world = null; threadCpu = null;
            if (gpu != null) { gpu.close(); gpu = null; }
            throw failure;
        }
    }

    public static boolean isCapturing() { return PerformanceRuntime.active() != null; }
    public static Map<String,Object> lastReport() { return lastReport; }

    public static void renderBegin() {
        PerformanceCapture capture = PerformanceRuntime.active();
        Minecraft client = Minecraft.getInstance();
        if (capture == null || !capture.isOpen() || !eligible(client, capture)) { rendering = null; return; }
        rendering = capture;
        renderStarted = System.nanoTime();
        cpuStarted = threadCpu == null || !perRenderCpu ? -1 : threadCpu.getCurrentThreadCpuTime();
        if (gpu != null) { gpu.poll(GPU_SAMPLE); gpu.begin(frameId++); }
    }

    public static void renderEnd() {
        PerformanceCapture capture = rendering;
        rendering = null;
        if (capture == null) return;
        if (gpu != null) gpu.end();
        long wallNanos = System.nanoTime() - renderStarted;
        long cpuNanos = cpuStarted < 0 || threadCpu == null ? -1 : threadCpu.getCurrentThreadCpuTime() - cpuStarted;
        if (cpuStarted >= 0 && (cpuNanos <= 0 || cpuNanos > wallNanos + 1_000_000L)) {
            perRenderCpu = false;
            capture.unavailable("renderCpu", "Thread CPU timer resolution cannot resolve individual render scopes; use capture-wide CPU counters");
            cpuNanos = -1;
        }
        capture.render(wallNanos, cpuNanos);
    }

    public static void presentedFrame() {
        PerformanceCapture capture = PerformanceRuntime.active();
        if (capture == null || !capture.isOpen()) return;
        Minecraft client = Minecraft.getInstance();
        if (!eligible(client, capture)) { previousFrame = 0; return; }
        long now = System.nanoTime();
        if (previousFrame != 0) capture.frame(now - previousFrame, -1, -1);
        previousFrame = now;
        if (client.levelRenderer.sectionRenderDispatcher() != null) {
            var dispatcher = client.levelRenderer.sectionRenderDispatcher();
            capture.queue(dispatcher.getCompileQueueSize(), dispatcher.getFreeBufferCount());
        }
    }

    private static boolean eligible(Minecraft client, PerformanceCapture capture) {
        if (client.level != world) {
            capture.unavailable("worldChanged", "The client world changed during capture");
            return false;
        }
        if (client.gui.screen() != null || client.gui.overlay() != null || client.getWindow().isIconified()) {
            if (previousFrame != 0) capture.unavailable("clientInterrupted", "A screen, overlay or minimized window interrupted capture");
            return false;
        }
        if (!contextInvalidated) {
            var camera = client.gameRenderer.mainCamera();
            boolean changed = effectiveFrameLimit != client.getFramerateLimitTracker().getFramerateLimit()
                    || width != client.getWindow().getWidth() || height != client.getWindow().getHeight()
                    || renderDistance != client.options.renderDistance().get() || simulationDistance != client.options.simulationDistance().get()
                    || fov != client.options.fov().get() || frameLimit != client.options.framerateLimit().get()
                    || vsync != client.options.enableVsync().get() || oit != client.options.improvedTransparency().get();
            boolean moved = Math.abs(cameraX - camera.position().x) > 0.0001 || Math.abs(cameraY - camera.position().y) > 0.0001
                    || Math.abs(cameraZ - camera.position().z) > 0.0001 || Math.abs(cameraYaw - camera.yRot()) > 0.0001
                    || Math.abs(cameraPitch - camera.xRot()) > 0.0001;
            if (changed || moved) {
                contextInvalidated = true;
                capture.unavailable(changed ? "configChanged" : "sceneChanged", "Viewport, graphics or fixed camera changed during capture");
            }
        }
        return true;
    }

    public static Map<String,Object> stop(String reason) {
        RenderSystem.assertOnRenderThread();
        PerformanceCapture capture = PerformanceRuntime.active();
        if (capture == null) throw new IllegalStateException("No performance capture is active");
        Minecraft client = Minecraft.getInstance();
        if (client.level != world) capture.unavailable("worldChanged", "The client world changed during capture");
        else if (!initialConfig.equals(hash(configuration(client))))
            capture.unavailable("configChanged", "Camera, viewport, graphics or resource settings changed during capture");
        long captureCpuEnd = threadCpu == null ? -1 : threadCpu.getCurrentThreadCpuTime();
        Map<String,Object> gpuState = Map.of("status", "UNAVAILABLE");
        if (gpu != null) {
            gpu.poll(GPU_SAMPLE);
            Map<String,Object> sampling = gpu.snapshot();
            if (!"AVAILABLE".equals(sampling.get("status"))
                    || ((Number)sampling.getOrDefault("busyDrops", 0L)).longValue() > 0
                    || ((Number)sampling.getOrDefault("invalidSamples", 0L)).longValue() > 0) {
                capture.unavailable("gpuRender", "Timestamp samples were unavailable, invalid or dropped; raw query counters retain the reason");
            }
            gpu.close();
            var closedState = new LinkedHashMap<>(gpu.snapshot());
            closedState.put("samplingStatus", sampling.get("status"));
            gpuState = Collections.unmodifiableMap(closedState);
            gpu = null;
        }
        var report = new LinkedHashMap<>(PerformanceRuntime.stop(reason));
        report.put("gpuQuery", gpuState);
        var cpuTotal = new LinkedHashMap<String,Object>();
        cpuTotal.put("scope", "Whole render thread during capture, including client ticks, observer and presentation calls; not a per-render CPU span");
        cpuTotal.put("status", cpuCaptureStarted >= 0 && captureCpuEnd >= cpuCaptureStarted ? "AVAILABLE" : "UNAVAILABLE");
        cpuTotal.put("elapsedCpuNanos", cpuCaptureStarted >= 0 && captureCpuEnd >= cpuCaptureStarted ? captureCpuEnd - cpuCaptureStarted : null);
        cpuTotal.put("perRenderResolutionUsable", perRenderCpu);
        report.put("renderThreadCpuCapture", Collections.unmodifiableMap(cpuTotal));
        report.put("observerScope", "Cadence uses rendered-frame logging; render wall/CPU and graphics timestamps bracket GameRenderer.render only. GPU excludes presentation/compositor.");
        rendering = null; world = null; previousFrame = 0; threadCpu = null;
        lastReport = Collections.unmodifiableMap(report);
        return lastReport;
    }

    private static void complete(String reason) {
        boolean notify = notifyCompletion;
        Map<String,Object> report = stop(reason);
        if (notify) export(report).whenComplete((path, error) -> Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(error == null
                    ? "DarkWinter: отчёт сохранён в performance-reports/" + path.getFileName()
                    : "DarkWinter: не удалось сохранить отчёт производительности"));
        }));
    }

    public static void notifyWhenComplete() { notifyCompletion = true; }

    public static CompletableFuture<Path> export(Map<String,Object> report) {
        Path directory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("performance-reports");
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = JSON.toJson(report).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Performance report exceeds 16 MiB");
                Files.createDirectories(directory);
                Path target = directory.resolve(UUID.randomUUID() + ".json");
                Path temporary = Files.createTempFile(directory, "capture-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
                } finally { Files.deleteIfExists(temporary); }
                return target;
            } catch (java.io.IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
    }

    private static Map<String,String> metadata(Minecraft client, String scenario, String sceneHash, String harness) {
        var info = RenderSystem.getDevice().getDeviceInfo();
        var values = new LinkedHashMap<String,String>();
        values.put("scenario", safe(scenario)); values.put("sceneHash", safe(sceneHash));
        values.put("chunkMeshMode", com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.requested().name());
        values.put("chunkMeshEffective", com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.effective().name());
        values.put("chunkMeshDetailedTiming", Boolean.toString(com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.detailedTiming()));
        values.put("voxelMode", com.dark.winter.client.fixes.voxel.VoxelFaceRuntime.requested().name());
        values.put("voxelEffective", com.dark.winter.client.fixes.voxel.VoxelFaceRuntime.effective());
        values.put("configHash", hash(configuration(client))); values.put("sourceHash", sourceHash());
        values.put("minecraftVersion", net.minecraft.SharedConstants.getCurrentVersion().name());
        values.put("modVersion", modVersion("dark-winter")); values.put("fabricVersion", modVersion("fabricloader"));
        values.put("fabricApiVersion", modVersion("fabric-api")); values.put("javaVersion", safe(System.getProperty("java.runtime.version")));
        values.put("vmName", safe(System.getProperty("java.vm.name")));
        values.put("gcNames", safe(ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean -> bean.getName()).sorted().toList().toString()));
        values.put("gpu", safe(info.name())); values.put("driver", safe(info.driverInfo()));
        values.put("backend", safe(info.backendName())); values.put("harness", safe(harness));
        values.put("hardware", hash(new TreeMap<>(Map.of("gpu", info.name(), "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"), "cpu", Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"), "unavailable"),
                "processors", Runtime.getRuntime().availableProcessors()))));
        values.put("effectiveFrameLimit", Integer.toString(client.getFramerateLimitTracker().getFramerateLimit()));
        values.put("throttleReason", client.getFramerateLimitTracker().getThrottleReason().name());
        values.put("inactivityPolicy", client.options.inactivityFpsLimit().get().name());
        values.put("simulationState", "normal");
        var server = client.getSingleplayerServer();
        values.put("tickRate", server == null ? "unavailable" : Float.toString(server.tickRateManager().tickrate()));
        values.put("serverType", server == null ? "remote-unobserved" : "integrated");
        values.put("presentationPath", "unobserved");
        values.put("presentMode", client.windowSurface().currentConfiguration().map(config -> config.presentMode().name()).orElse("unavailable"));
        values.put("timestampScope", "Graphics render commands; excludes presentation, DXGI and compositor");
        return values;
    }

    private static SortedMap<String,Object> configuration(Minecraft client) {
        var values = new TreeMap<String,Object>();
        values.put("width", client.getWindow().getWidth()); values.put("height", client.getWindow().getHeight());
        values.put("renderDistance", client.options.renderDistance().get()); values.put("simulationDistance", client.options.simulationDistance().get());
        values.put("fov", client.options.fov().get()); values.put("vsync", client.options.enableVsync().get());
        values.put("frameLimit", client.options.framerateLimit().get()); values.put("oit", client.options.improvedTransparency().get());
        values.put("effectiveFrameLimit", client.getFramerateLimitTracker().getFramerateLimit());
        values.put("inactivityPolicy", client.options.inactivityFpsLimit().get().name());
        values.put("clouds", client.options.cloudStatus().get().name()); values.put("resourceEpoch", SceneFrame.resourceEpoch);
        values.put("chunkMeshMode", com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.requested().name());
        values.put("chunkMeshEffective", com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.effective().name());
        values.put("chunkMeshDetailedTiming", Boolean.toString(com.dark.winter.client.fixes.mesh.ChunkMeshRuntime.detailedTiming()));
        values.put("voxelMode", com.dark.winter.client.fixes.voxel.VoxelFaceRuntime.requested().name());
        values.put("voxelEffective", com.dark.winter.client.fixes.voxel.VoxelFaceRuntime.effective());
        if (client.player != null) {
            var camera = client.gameRenderer.mainCamera();
            values.put("camera", String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f",
                    camera.position().x, camera.position().y, camera.position().z, camera.yRot(), camera.xRot()));
            values.put("cameraType", client.options.getCameraType().name());
        }
        for (var field : VisualConfig.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !(field.getType().isPrimitive() || field.getType().isEnum())) continue;
            try { values.put("visual." + field.getName(), String.valueOf(field.get(VisualConfig.shared()))); }
            catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
        }
        var settings = TestingAccess.settings();
        values.put("modules", settings == null ? "ordinary" : new TreeMap<>(settings.modules));
        return values;
    }

    private static String sourceHash() {
        try (var input = PerformanceClient.class.getResourceAsStream("/darkwinter-performance-build.json")) {
            if (input == null) return "unavailable";
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("sourceHash").getAsString();
        } catch (java.io.IOException failure) { return "unavailable"; }
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unavailable");
    }

    private static String safe(String value) {
        String text = Objects.toString(value, "unavailable").replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.isEmpty() ? "unavailable" : text.substring(0, Math.min(256, text.length()));
    }

    private static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
