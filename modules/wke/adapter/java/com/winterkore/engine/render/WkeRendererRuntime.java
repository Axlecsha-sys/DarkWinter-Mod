// Selects WKE-owned world pipelines while Minecraft keeps world state, visibility and GPU resource ownership.
package com.winterkore.engine.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.winterkore.engine.material.WkeFoliageTextures;
import net.minecraft.client.renderer.ShaderDefines;
import java.util.List;
import java.util.ArrayList;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicLong;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WkeRendererRuntime {
    public enum Mode { AUTO, HOST, WKE }
    private static final Logger LOG = LoggerFactory.getLogger("WinterKore/Renderer");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Identifier HOST_TERRAIN = Identifier.fromNamespaceAndPath("minecraft", "core/terrain");
    private static final Identifier WKE_TERRAIN = Identifier.fromNamespaceAndPath("wke", "core/terrain");
    private static final Identifier OWN_SHADOW = Identifier.fromNamespaceAndPath("darkwinter_visuals", "core/shadow_cutout_depth");
    private static final Identifier OWN_FOG_TERRAIN = Identifier.fromNamespaceAndPath("darkwinter_visuals", "core/vanillafog/terrain");
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static final AtomicLong PIPELINE_IDS = new AtomicLong();
    private static final ThreadLocal<int[]> TERRAIN_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> WORLD_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final AtomicLong NATIVE_WORLD_DISPATCHES = new AtomicLong();
    private static final AtomicLong NATIVE_TERRAIN_DISPATCHES = new AtomicLong();
    private static final AtomicLong HOST_WORLD_FALLBACKS = new AtomicLong();
    private static final AtomicLong HOST_TERRAIN_FALLBACKS = new AtomicLong();
    private static volatile Mode mode = initialMode();
    private static volatile boolean eligible;
    private static volatile String eligibilityReason = "Client graphics device has not started";
    private static boolean initialized;
    private static volatile UnaryOperator<RenderPipeline> terrainPreprocessor=UnaryOperator.identity();
    private static volatile boolean terrainPreprocessorConfigured;

    private WkeRendererRuntime() { }
    private static Mode initialMode() {
        try { return Mode.valueOf(System.getProperty("winterkore.renderer.mode", "AUTO").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return Mode.AUTO; }
    }
    public static synchronized void initialize() {
        if (initialized) return;
        WkeFoliageTextures.initialize();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> refreshEligibility());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            eligible = false;
            eligibilityReason = "Client stopping";
            synchronized (PIPELINES) { PIPELINES.clear(); }
        });
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath("wke", "renderer_pipelines"),
            (ResourceManagerReloadListener) manager -> {
                // These are CPU pipeline descriptions only. The host retires compiled GPU objects.
                synchronized (PIPELINES) { PIPELINES.clear(); }
            });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("wkerender")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(context -> {
                    context.getSource().sendFeedback(Component.literal(status()));
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("host").executes(context -> commandMode(context.getSource(), Mode.HOST)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("native").executes(context -> commandMode(context.getSource(), Mode.WKE)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auto").executes(context -> commandMode(context.getSource(), Mode.AUTO)))));
        initialized = true;
    }
    private static int commandMode(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, Mode next) {
        mode = next;
        source.sendFeedback(Component.literal(status()));
        return 1;
    }
    private static void refreshEligibility() {
        eligible = false;
        if (!WINDOWS) {
            eligibilityReason = "WKE renderer currently requires Windows";
            return;
        }
        if (!WkeRendererRuntime.class.getModule().isNativeAccessEnabled()) {
            eligibilityReason = "Native access is disabled";
            return;
        }
        try {
            if (!"Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName())) {
                eligibilityReason = "The selected Minecraft graphics backend is not Vulkan";
                return;
            }
            if (!WkeVulkanBridge.available()) {
                eligibilityReason = WkeVulkanBridge.statusReason();
                return;
            }
            eligible = true;
            eligibilityReason = "";
            LOG.info("WKE world renderer eligible; mode={}; activation requires native pipeline creation and draw evidence", mode);
        } catch (RuntimeException | LinkageError failure) {
            eligibilityReason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            LOG.warn("WKE world renderer unavailable; host path retained: {}", eligibilityReason);
        }
    }
    /** The host installs its terrain material/fog adaptation once, before world rendering starts. */
    public static void setTerrainPreprocessor(UnaryOperator<RenderPipeline> preprocessor) {
        synchronized(PIPELINES) {
            terrainPreprocessor=Objects.requireNonNull(preprocessor);
            terrainPreprocessorConfigured=true;
            PIPELINES.clear();
        }
    }
    public static void setModeForTest(boolean nativeEnabled) { mode = nativeEnabled ? Mode.WKE : Mode.HOST; }
    public static void setMode(String value) { mode = Mode.valueOf(value.toUpperCase(Locale.ROOT)); }
    public static boolean nativeTerrainActive() { return eligible && mode != Mode.HOST && TERRAIN_DEPTH.get()[0] > 0; }
    public static boolean isTerrainScope() { return TERRAIN_DEPTH.get()[0] > 0; }
    public static boolean isWorldScope() { return WORLD_DEPTH.get()[0] > 0; }
    public static boolean nativeWorldActive() { return eligible && mode != Mode.HOST && isWorldScope(); }
    // Shader/pipeline precompilation starts before CLIENT_STARTED. Pipeline creation
    // may be native early; command recording still requires a proven Vulkan world scope.
    public static boolean allowNativePipelineCreation() {
        return WINDOWS && WkeRendererRuntime.class.getModule().isNativeAccessEnabled() && mode != Mode.HOST;
    }
    public static void enterWorld() { WORLD_DEPTH.get()[0]++; }
    public static void leaveWorld() {
        int[] depth = WORLD_DEPTH.get();
        if (depth[0] <= 0) throw new IllegalStateException("Unbalanced WKE world scope");
        depth[0]--;
    }
    /** Java ingress dispatches; a multidraw dispatch may record several native Vulkan commands. */
    public static void onNativeDispatchAccepted() {
        WkeFoliageTextures.onNativeDispatchAccepted();
        if (isWorldScope()) NATIVE_WORLD_DISPATCHES.incrementAndGet();
        if (isTerrainScope()) NATIVE_TERRAIN_DISPATCHES.incrementAndGet();
    }
    public static void onHostDispatchFallback() {
        if (isWorldScope()) HOST_WORLD_FALLBACKS.incrementAndGet();
        if (isTerrainScope()) HOST_TERRAIN_FALLBACKS.incrementAndGet();
    }
    public static void enterTerrain() { TERRAIN_DEPTH.get()[0]++; }
    public static void leaveTerrain() {
        int[] depth = TERRAIN_DEPTH.get();
        if (depth[0] <= 0) throw new IllegalStateException("Unbalanced WKE terrain scope");
        depth[0]--;
    }
    public static RenderPipeline pipeline(RenderPipeline source) {
        if (source == null || (!nativeWorldActive() && !nativeTerrainActive())) return source;
        // The terrain selector and the common RenderSystem selector may see the
        // same description. Never recursively clone an already-owned WKE name.
        if ("wke".equals(source.getLocation().getNamespace())) return source;
        // Canonical host fog/material adaptation precedes WKE ownership regardless of mixin order.
        if (isTerrainScope()) source=Objects.requireNonNull(terrainPreprocessor.apply(source));
        if ("wke".equals(source.getLocation().getNamespace())) return source;
        // Do not cache a reduced material clone before the render-thread GPU warmup succeeds.
        if (materialPipeline(source)&&!WkeFoliageTextures.descriptorsReady()) return source;
        synchronized (PIPELINES) {
            return PIPELINES.computeIfAbsent(source, original -> {
                Identifier id = original.getLocation();
                Identifier location = Identifier.fromNamespaceAndPath("wke",
                    (isTerrainScope() ? "terrain/" : "world/") + materialTag(original) + id.getNamespace() + "/" + id.getPath() + "/" + PIPELINE_IDS.incrementAndGet());
                return new WorldPipeline(original, location);
            });
        }
    }
    private static Map<ShaderType, Identifier> shaders(RenderPipeline source) {
        Map<ShaderType, Identifier> result = new EnumMap<>(ShaderType.class);
        result.putAll(source.getShaders());
        // Custom shadow/extension materials retain their shaders and exact state.
        // Vanilla terrain variants (including OIT) use the WKE-owned stage source.
        if (standardTerrain(source)) result.replaceAll((stage, id) -> HOST_TERRAIN.equals(id) ? WKE_TERRAIN : id);
        return result;
    }
    private static boolean standardTerrain(RenderPipeline source) {
        Identifier vertex=source.getShaders().get(ShaderType.VERTEX);
        Identifier fragment=source.getShaders().get(ShaderType.FRAGMENT);
        return (HOST_TERRAIN.equals(vertex)||WKE_TERRAIN.equals(vertex))
            && (HOST_TERRAIN.equals(fragment)||WKE_TERRAIN.equals(fragment));
    }
    private static boolean ownFogTerrain(RenderPipeline source) {
        Identifier vertex=source.getShaders().get(ShaderType.VERTEX);
        return OWN_FOG_TERRAIN.equals(source.getShaders().get(ShaderType.FRAGMENT))
            &&(HOST_TERRAIN.equals(vertex)||OWN_FOG_TERRAIN.equals(vertex));
    }
    private static String materialTag(RenderPipeline source) {
        if(!materialPipeline(source)) return "";
        return OWN_SHADOW.equals(source.getShaders().get(ShaderType.FRAGMENT))?"material_shadow/":"material_main/";
    }
    private static boolean materialPipeline(RenderPipeline source) {
        return standardTerrain(source)||ownFogTerrain(source)||(HOST_TERRAIN.equals(source.getShaders().get(ShaderType.VERTEX))
            &&OWN_SHADOW.equals(source.getShaders().get(ShaderType.FRAGMENT)));
    }
    private static ShaderDefines defines(RenderPipeline source) {
        return materialPipeline(source)?source.getShaderDefines().withOverrides(
            ShaderDefines.builder().define("WKE_FOLIAGE_TEXTURES").build()):source.getShaderDefines();
    }
    private static List<BindGroupLayout> bindings(RenderPipeline source) {
        if (!materialPipeline(source)) return source.getBindGroupLayouts();
        List<BindGroupLayout> result=new ArrayList<>(source.getBindGroupLayouts());
        result.add(BindGroupLayout.builder()
            .withUniform(WkeFoliageTextures.NEEDLES_SAMPLER,UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(WkeFoliageTextures.BRANCH_SAMPLER,UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(WkeFoliageTextures.UNIFORM,UniformType.UNIFORM_BUFFER).build());
        return result;
    }
    private static final class WorldPipeline extends RenderPipeline {
        WorldPipeline(RenderPipeline source, Identifier location) {
            super(location, shaders(source), defines(source), bindings(source),
                source.getColorTargetStates().toArray(ColorTargetState[]::new), source.getDepthStencilState(),
                source.getPolygonMode(), source.isCull(), source.getVertexFormatBindings().toArray(VertexFormat[]::new),
                source.getPrimitiveTopology(), source.pushConstantSize(), source.getSortKey());
        }
    }

    /** Allocates/copies native diagnostics only on explicit status/test requests, never per draw. */
    public static Map<String, Object> statusSnapshot() {
        long[] stats = new long[16];
        String bridgeReason = "";
        long hostFallbacks = 0, nativeDispatches = 0;
        boolean nativeAccess = WkeRendererRuntime.class.getModule().isNativeAccessEnabled();
        // Querying status without the JVM native-access flag must remain a cold Java-only path.
        if (nativeAccess) {
            bridgeReason = WkeVulkanBridge.statusReason();
            try { stats = WkeVulkanBridge.snapshotStats(); }
            catch (RuntimeException failure) { bridgeReason = String.valueOf(failure.getMessage()); }
            hostFallbacks = WkeVulkanBridge.recordedHostDraws();
            nativeDispatches = WkeVulkanBridge.recordedNativeDispatches();
        }
        long nativeCalls = stats[5] + stats[6] + stats[7] + stats[8];
        boolean actual = nativeAccess && eligible && mode != Mode.HOST && stats[1] > 0 && nativeCalls > 0;
        String reason = !nativeAccess ? "Native access is disabled"
            : mode == Mode.HOST ? "Host mode requested"
            : !eligible ? eligibilityReason
            : !bridgeReason.isEmpty() ? bridgeReason
            : stats[1] == 0 ? "Waiting for a WKE-owned graphics pipeline"
            : nativeCalls == 0 ? "Waiting for a native world draw"
            : "";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode.name());
        result.put("effective", actual ? "WKE" : "HOST");
        result.put("reason", reason);
        result.put("eligible", eligible);
        result.put("terrainPreprocessorConfigured",terrainPreprocessorConfigured);
        result.put("nativePipelines", stats[1]);
        result.put("nativePipelinesCreated", stats[2]);
        result.put("nativePipelinesDestroyed", stats[3]);
        result.put("nativeDrawCalls", nativeCalls);
        result.put("nativeDirectDraws", stats[5]);
        result.put("nativeIndexedDraws", stats[6]);
        result.put("nativeIndirectCalls", stats[7]);
        result.put("nativeIndexedIndirectCalls", stats[8]);
        result.put("nativeIndices", stats[10]);
        result.put("nativeIndicesScope", "direct-indexed commands only; indirect GPU contents are not read back");
        result.put("nativeIndirectDraws", stats[11]);
        result.put("nativeRejected", stats[12]);
        result.put("nativeFailures", stats[13]);
        result.put("hostDrawCalls", hostFallbacks);
        result.put("skippedHostDrawCalls", nativeDispatches);
        result.put("hostWorldDrawCalls", HOST_WORLD_FALLBACKS.get());
        result.put("hostTerrainDrawCalls", HOST_TERRAIN_FALLBACKS.get());
        result.put("skippedWorldDrawCalls", NATIVE_WORLD_DISPATCHES.get());
        result.put("skippedTerrainDrawCalls", NATIVE_TERRAIN_DISPATCHES.get());
        result.put("nativeWorldDispatches", NATIVE_WORLD_DISPATCHES.get());
        result.put("nativeTerrainDispatches", NATIVE_TERRAIN_DISPATCHES.get());
        result.put("dispatchCounterScope", "Java ingress; world includes nested terrain, terrain is tracked separately");
        result.put("hostDrawCounterScope", "fallback dispatches inside requested WKE world/terrain scopes");
        return Collections.unmodifiableMap(result);
    }
    public static String status() {
        Map<String, Object> state = statusSnapshot();
        return "WKE renderer: mode=" + state.get("mode") + ", effective=" + state.get("effective")
            + ", pipelines=" + state.get("nativePipelines") + ", nativeDrawCalls=" + state.get("nativeDrawCalls")
            + ", hostFallbacks=" + state.get("hostDrawCalls") + ", reason=" + state.get("reason");
    }
}
