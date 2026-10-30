// Owns the native shadow target and the single postprocessing chain.
package com.dark.winter.visuals;

import com.mojang.blaze3d.ProjectionType;
import com.dark.winter.client.weather.WeatherClient;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Owns the native shadow target and the single postprocessing chain. Client/render thread only. */
public final class VisualRenderer implements AutoCloseable {
    private static volatile boolean stableDepthRequested = true;
    private static volatile WeakReference<VisualRenderer> activeRenderer = new WeakReference<>(null);
    static {
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            VisualRenderer renderer = activeRenderer.get();
            if (renderer != null) client.execute(() -> {
                // LevelRenderer survives a world disconnect; no further world render need occur.
                // Minecraft's executor owns the render thread and all TextureTarget operations.
                renderer.filmHistory.close();
                renderer.frame.filmHistoryValid = false;
                renderer.frameReady = false;
            });
        });
    }
    private static final Identifier MAIN = Identifier.withDefaultNamespace("main");
    private static final Identifier SHADOW = Identifier.fromNamespaceAndPath("darkwinter_visuals", "shadow");
    private final VisualConfig config = VisualConfig.shared();
    private final SceneFrame frame = new SceneFrame();
    private final ShadowGrid grid = new ShadowGrid();
    private final FilmHistory filmHistory = new FilmHistory();
    private final long startNanos = System.nanoTime();
    private TextureTarget shadowTarget;
    private ProjectionMatrixBuffer shadowProjection;
    private GpuSampler terrainSampler;
    private boolean frameReady, shadowFailed, resolveFailed;
    private int currentResolution, resourceEpoch = -1;
    private long configRevision = -1;
    private Object world;
    private double frozenAngle = Double.NaN;
    private long lastWeatherNanos;
    private double cloudDriftX, cloudDriftZ, hazeDriftX, hazeDriftZ;

    public VisualRenderer() { activeRenderer = new WeakReference<>(this); }

    public static boolean needsStableDepth() { return stableDepthRequested; }

    /** Vanilla distance fog must remain available if our world atmosphere is disabled or fails. */
    public static boolean controlsAtmosphere(ClientLevel level) {
        VisualRenderer renderer = activeRenderer.get();
        return com.dark.winter.client.TestingAccess.enabled("atmosphere") && renderer != null && renderer.world == level && renderer.frameReady && !renderer.resolveFailed
                && com.dark.winter.client.TestingAccess.pipeline(renderer.config.enabled) && com.dark.winter.client.TestingAccess.resolve("atmosphere",renderer.config.winterHaze) && renderer.config.debug == 0
                && WeatherClient.active();
    }

    /** Runs BEFORE vanilla prepares its main-view chunk draw list. No deferred terrain draw handles. */
    public void renderShadow(SceneAccess scene, GameRenderer game, LevelRenderState state,
                             GpuBufferSlice terrainFog) {
        config.poll();
        stableDepthRequested = com.dark.winter.client.TestingAccess.pipeline(config.enabled);
        frameReady = false;
        frame.shadowValid = false;
        Object activeWorld = Minecraft.getInstance().level;
        if (activeWorld != world) {
            release(); world = activeWorld; shadowFailed = resolveFailed = false;
            WeatherInput.clear();
            lastWeatherNanos=0;
            cloudDriftX=cloudDriftZ=hazeDriftX=hazeDriftZ=0;
        }
        if (resourceEpoch != SceneFrame.resourceEpoch || configRevision != config.revision) {
            resourceEpoch = SceneFrame.resourceEpoch; configRevision = config.revision;
            shadowFailed = resolveFailed = false; grid.reset(); frozenAngle = Double.NaN;
            filmHistory.invalidate();
        }
        if (!com.dark.winter.client.TestingAccess.pipeline(config.enabled) || resolveFailed || activeWorld == null || !state.cameraRenderState.initialized) {
            filmHistory.invalidate();
            return;
        }
        var camera = state.cameraRenderState;
        boolean surfaceWorld = state.skyRenderState.skybox != Skybox.NONE && state.skyRenderState.skybox != Skybox.END;
        if ((config.overworldOnly && !surfaceWorld) || camera.isPanoramicMode
                || camera.fogType == FogType.LAVA || camera.fogType == FogType.POWDER_SNOW) {
            filmHistory.invalidate();
            return;
        }

        try {
            ensureResources();
            frame.config = config;
            var client=Minecraft.getInstance();
            frame.weather = WeatherInput.snapshot(state.skyRenderState.rainBrightness,
                    client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            double angle = state.skyRenderState.sunAngle; // Already radians, from SkyRenderer render state.
            if (config.freezeSun) {
                if (!Double.isFinite(frozenAngle)) frozenAngle = angle;
                angle = frozenAngle;
            } else frozenAngle = Double.NaN;
            // The same extracted solar position drives the visible sun AND the shadow map.
            SolarEphemeris.Position geographic = config.geographicSun
                    ? SolarRuntime.current(Minecraft.getInstance().level) : null;
            if (geographic != null) {
                frame.sun.set((float)geographic.x(), (float)geographic.y(), (float)geographic.z()).normalize();
            } else {
                frame.sun.set(-(float)Math.sin(angle), (float)Math.cos(angle), 0).normalize();
            }
            float elevation = geographic != null
                    ? smoothstep(-.833f, 4.0f, (float)geographic.elevationDegrees())
                    : smoothstep(.025f, .18f, frame.sun.y);
            float directSun = geographic != null
                    ? smoothstep(0.35f, 5.0f, (float)geographic.elevationDegrees())
                    : smoothstep(.04f, .22f, frame.sun.y);
            frame.intensity = surfaceWorld ? elevation * directSun * frame.weather.sunVisibility()
                    * (WeatherClient.active()?1f:1-.70f*frame.weather.cloudCover()) : 0;
            frame.inFluid = camera.fogType != FogType.NONE;
            if (frame.inFluid) frame.intensity = 0;
            frame.inverseViewProjection.set(((ProjectionAccess)(Object)game).darkwinter$renderProjection())
                    .mul(camera.viewRotationMatrix).invert();
            frame.cameraHeight = (float)camera.pos.y;
            frame.cameraX = (float)(camera.pos.x % 4096.0);
            frame.cameraZ = (float)(camera.pos.z % 4096.0);
            double elapsedSeconds=(System.nanoTime()-startNanos)*1e-9;
            frame.timeSeconds = (float)(elapsedSeconds % 8192.0);
            advanceWeatherDrift(client);
            frame.clipRange = 6 * config.quality.radius - .1f;
            frameReady = true;
            if (shadowFailed || !com.dark.winter.client.TestingAccess.resolve("visuals",config.shadows) || frame.intensity <= .001f) return;

            float radius = config.quality.radius;
            // Preserve the proven stable original grid in vanilla fallback mode.
            ShadowGrid.Capture capture = geographic == null
                    ? grid.capture(camera.pos.x,camera.pos.y,camera.pos.z,angle,radius,config.quality.resolution)
                    : grid.captureSun(camera.pos.x,camera.pos.y,camera.pos.z,
                            frame.sun.x,frame.sun.y,frame.sun.z,radius,config.quality.resolution);
            Matrix4f lightView = new Matrix4f().set(capture.view());
            Matrix4f lightProjection = new Matrix4f().ortho(-radius,radius,-radius,radius,6*radius,.1f,true);
            Matrix4f lightClip = new Matrix4f(lightProjection).mul(lightView);
            // Use light model-view DIRECTLY. No inverse(cameraRotation) cancellation in projection.
            ChunkSectionsToRender terrain = scene.darkwinter$prepareShadowTerrain(lightView, lightClip);
            RenderSystem.resizeAllAutoStorageIndexBuffers();

            GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
            ProjectionType oldType = RenderSystem.getProjectionType();
            GpuBufferSlice oldFog = RenderSystem.getShaderFog();
            GpuBufferSlice oldLights = RenderSystem.getShaderLights();
            try {
                RenderSystem.setProjectionMatrix(shadowProjection.getBuffer(lightProjection), ProjectionType.ORTHOGRAPHIC);
                RenderSystem.setShaderFog(terrainFog);
                game.lighting().setupFor(Lighting.Entry.LEVEL);
                var atlas = Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "DarkWinter Visuals / shadow capture",
                        shadowTarget.getColorTextureView(), Optional.of(new Vector4f(0)),
                        shadowTarget.getDepthTextureView(), OptionalDouble.of(0.0))) {
                    RenderSystem.bindDefaultUniforms(pass);
                    terrain.renderGroup(ChunkSectionLayerGroup.OPAQUE,pass,terrainSampler,atlas,false);
                }
            } finally {
                if (oldProjection!=null) RenderSystem.setProjectionMatrix(oldProjection,oldType);
                if (oldFog!=null) RenderSystem.setShaderFog(oldFog);
                if (oldLights!=null) RenderSystem.setShaderLights(oldLights);
            }
            // Publish map + the EXACT matrix that produced it together, in this frame.
            frame.shadowFromRelative.set(lightClip);
            frame.shadowValid = true;
        } catch (RuntimeException error) {
            shadowFailed = true; frame.shadowValid = false;
            VisualConfig.LOG.error("RC2 shadow capture disabled after an error. Film pass can still run. "
                    + "Reload resources to retry; do not hide this exception.",error);
        }
    }

    private void advanceWeatherDrift(Minecraft client) {
        long now=System.nanoTime();
        double dt=lastWeatherNanos==0 || client.isPaused()?0:Math.min((now-lastWeatherNanos)*1e-9,.25);
        lastWeatherNanos=now;
        // Integrate velocity: changing weather never multiplies accumulated time by a new speed.
        double speed=WeatherClient.active()?frame.weather.windSpeed():.12;
        double vx=frame.weather.windX()*speed, vz=frame.weather.windZ()*speed;
        cloudDriftX+=vx*18.0*dt;
        cloudDriftZ+=vz*18.0*dt;
        hazeDriftX=(hazeDriftX+vx*config.hazeWindSpeed/.12*dt)%4096.0;
        hazeDriftZ=(hazeDriftZ+vz*config.hazeWindSpeed/.12*dt)%4096.0;
        frame.cloudDriftX=(float)cloudDriftX; frame.cloudDriftZ=(float)cloudDriftZ;
        frame.hazeDriftX=(float)hazeDriftX; frame.hazeDriftZ=(float)hazeDriftZ;
    }

    /** Runs after the world and before hand/HUD. Film is always the LAST pass. */
    public void resolve(GameRenderer game, ShaderManager shaders, LevelRenderState state,
                        GraphicsResourceAllocator allocator) {
        if (!frameReady || resolveFailed || shadowTarget==null) {
            filmHistory.invalidate();
            return;
        }
        SceneFrame previous = SceneFrame.active;
        GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldType = RenderSystem.getProjectionType();
        GpuBufferSlice oldFog = RenderSystem.getShaderFog();
        GpuBufferSlice oldLights = RenderSystem.getShaderLights();
        try {
            SceneFrame.active = frame;
            Identifier chainId = Identifier.fromNamespaceAndPath("darkwinter_visuals",
                    config.quality.name().toLowerCase(Locale.ROOT));
            RenderTarget main = game.mainRenderTarget();
            filmHistory.prepare(Minecraft.getInstance(), state, main, frame, config);
            PostChain chain = shaders.getPostChain(chainId,Set.of(MAIN,SHADOW,
                    FilmHistory.CURRENT_RAW, FilmHistory.PREVIOUS_FRAME, FilmHistory.HELD_FRAME));
            if (chain==null) throw new IllegalStateException("Cannot load "+chainId+". See the first shader error above.");
            FrameGraphBuilder graph = new FrameGraphBuilder();
            Map<Identifier,ResourceHandle<RenderTarget>> targets = new HashMap<>();
            targets.put(MAIN,graph.importExternal("DarkWinter world",main));
            targets.put(SHADOW,graph.importExternal("DarkWinter shadow",shadowTarget));
            filmHistory.importTargets(graph, targets);
            PostChain.TargetBundle bundle = new PostChain.TargetBundle() {
                @Override public void replace(Identifier id, ResourceHandle<RenderTarget> value) { targets.put(id,value); }
                @Override public ResourceHandle<RenderTarget> get(Identifier id) { return targets.get(id); }
            };
            chain.addToFrame(graph,main.width,main.height,bundle);
            graph.execute(allocator);
            filmHistory.capture();
        } catch (RuntimeException error) {
            filmHistory.invalidate();
            resolveFailed=true;
            VisualConfig.LOG.error("RC2 post chain disabled after an error; reload resources to retry.",error);
        } finally {
            SceneFrame.active=previous;
            if (oldProjection!=null) RenderSystem.setProjectionMatrix(oldProjection,oldType);
            if (oldFog!=null) RenderSystem.setShaderFog(oldFog);
            if (oldLights!=null) RenderSystem.setShaderLights(oldLights);
        }
    }

    private void ensureResources() {
        if (shadowTarget!=null && currentResolution==config.quality.resolution) return;
        release();
        int n=config.quality.resolution;
        shadowTarget=new TextureTarget("DarkWinter Visuals Shadow",n,n,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        shadowProjection=new ProjectionMatrixBuffer("DarkWinter Visuals Light Projection");
        terrainSampler=RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                shadowTarget.getColorTexture(),new Vector4f(0),shadowTarget.getDepthTexture(),0.0);
        currentResolution=n;
    }
    private static float smoothstep(float a,float b,float x) {
        float t=Math.max(0,Math.min(1,(x-a)/(b-a))); return t*t*(3-2*t);
    }
    private void release() {
        filmHistory.close();
        if (shadowTarget!=null) { shadowTarget.destroyBuffers(); shadowTarget=null; }
        if (shadowProjection!=null) { shadowProjection.close(); shadowProjection=null; }
        if (terrainSampler!=null) { terrainSampler.close(); terrainSampler=null; }
        currentResolution=0; frame.shadowValid=false; grid.reset(); frozenAngle=Double.NaN;
    }
    @Override public void close() { release(); }
}
