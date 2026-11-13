// Owns the native shadow target and the single postprocessing chain.
package com.dark.winter.visuals;

import com.mojang.blaze3d.ProjectionType;
import com.dark.winter.client.weather.WeatherClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
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
import java.util.LinkedHashMap;
import com.winterkore.engine.graphics.effects.WkeShadowGrid;
import com.winterkore.engine.graphics.lighting.WkeSnowLighting;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import com.winterkore.engine.render.effects.WkePostGraphExecutor;
import com.winterkore.engine.render.effects.WkeParticleReactiveMask;
import java.util.Optional;
import java.util.OptionalDouble;

/** Owns the native shadow target and the single postprocessing chain. Client/render thread only. */
public final class VisualRenderer implements AutoCloseable {
    private static volatile boolean stableDepthRequested = true;
    private static volatile WeakReference<VisualRenderer> activeRenderer = new WeakReference<>(null);
    static {
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            VisualRenderer renderer = activeRenderer.get();
            Object disconnectedWorld = renderer == null ? null : renderer.world;
            if (renderer != null) client.execute(() -> {
                // A queued old disconnect must not close resources of a newly opened world.
                if (renderer.world == disconnectedWorld) {
                    ((SceneAccess) client.levelRenderer).darkwinter$releaseVisualResources(renderer);
                    renderer.close();
                }
            });
        });
    }
    private static final Identifier MAIN = Identifier.withDefaultNamespace("main");
    private static final Identifier SHADOW = Identifier.fromNamespaceAndPath("darkwinter_visuals", "shadow");
    private final VisualConfig config = VisualConfig.shared();
    private final SceneFrame frame = new SceneFrame();
    private WkeSnowLighting.Sunlight snowLight = WkeSnowLighting.overcast();
    private final WkeShadowGrid grid = new WkeShadowGrid();
    private final WkePostGraphExecutor effectGraph = new WkePostGraphExecutor();
    private final TemporalResolveHistory temporalHistory = new TemporalResolveHistory();
    private final WkeParticleReactiveMask reactiveMask = new WkeParticleReactiveMask();
    private final FilmHistory filmHistory = new FilmHistory();
    private static final boolean FUSED_COMPOSITE = Boolean.parseBoolean(System.getProperty("darkwinter.visuals.fusedComposite", "true"));
    private final ShadowDepthTerrain shadowDepth = new ShadowDepthTerrain();
    private final ShadowCaptureCache shadowCache = new ShadowCaptureCache();
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

    /** Snapshot diagnostics only outside a performance capture, on the render owner. */
    public static Map<String,Object> shadowCacheStatistics() {
        VisualRenderer renderer=activeRenderer.get();
        return renderer==null?Map.of("available",false):renderer.shadowCache.statistics();
    }

    /** Implemented effects and observed submissions; this does not claim completed GPU execution. */
    public static Map<String,Object> effectsStatus() {
        VisualRenderer renderer=activeRenderer.get();
        if(renderer==null) return Map.of("available",false);
        Map<String,Object> status=new LinkedHashMap<>(renderer.effectGraph.statistics());
        status.put("available",renderer.frameReady&&!renderer.resolveFailed);
        status.put("shadowMapValid",renderer.frame.shadowValid&&!renderer.shadowFailed);
        status.put("quality",renderer.config.quality.name());
        status.put("sceneBytes",SceneFrame.BYTES);
        status.put("antialiasRequested",renderer.config.antialiasEnabled);
        status.put("filmRequested",renderer.config.filmEnabled);
        status.put("temporalAntialias",TemporalResolveHistory.statistics());
        status.put("particleReactiveMask",renderer.reactiveMask.statistics());
        return Map.copyOf(status);
    }

    /** Actual images bound during the last resolved graph; retained across the history swap for diagnostics. */
    public static RenderTarget reactiveMaskTarget() {
        VisualRenderer renderer=activeRenderer.get();
        return renderer==null?null:renderer.reactiveMask.target();
    }
    public static RenderTarget reactiveMaskHistoryTarget() {
        VisualRenderer renderer=activeRenderer.get();
        return renderer==null?null:renderer.reactiveMask.historyTarget();
    }

    /** The same captured scene drives early sky, terrain backgrounds and the final post chain. */
    public static SceneFrame currentFrame() {
        VisualRenderer renderer=activeRenderer.get();
        return renderer!=null && renderer.frameReady && !renderer.resolveFailed
                && renderer.world==Minecraft.getInstance().level?renderer.frame:null;
    }

    /** Actual extracted solar input for snow material lighting; independent of the post-pass lifetime. */
    public static WkeSnowLighting.Sunlight snowLighting() {
        VisualRenderer renderer=activeRenderer.get();
        return renderer!=null && renderer.frameReady && renderer.world==Minecraft.getInstance().level
                ? renderer.snowLight : WkeSnowLighting.overcast();
    }

    public static boolean ordinaryAtmosphere(ClientLevel level,
            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        return level!=null && net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())
                && !camera.isPanoramicMode && camera.fogType==FogType.NONE
                && !camera.entityRenderState.doesMobEffectBlockSky
                && !Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog();
    }

    /** Vanilla distance fog must remain available if our world atmosphere is disabled or fails. */
    public static boolean controlsAtmosphere(ClientLevel level) {
        VisualRenderer renderer = activeRenderer.get();
        if(renderer==null||renderer.world!=level||!renderer.frameReady||renderer.resolveFailed)return false;
        var lab=renderer.frame.lab;
        return module(lab,"atmosphere",true)&&pipeline(lab,renderer.config.enabled)
                &&module(lab,"atmosphere",renderer.config.winterHaze)&&renderer.config.debug==0&&WeatherClient.active(lab);
    }

    /** Runs BEFORE vanilla prepares its main-view chunk draw list. No deferred terrain draw handles. */
    public void renderShadow(SceneAccess scene, GameRenderer game, LevelRenderState state,
                             GpuBufferSlice terrainFog) {
        config.poll();
        frame.lab=com.dark.winter.client.TestingAccess.presentation();
        stableDepthRequested = pipeline(frame.lab,config.enabled);
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
            temporalHistory.invalidate();
            reactiveMask.invalidate();
            shadowCache.clearConfig();
        }
        if (!pipeline(frame.lab,config.enabled) || resolveFailed || activeWorld == null || !state.cameraRenderState.initialized) {
            shadowCache.clearOff();
            filmHistory.invalidate();
            temporalHistory.invalidate();
            reactiveMask.invalidate();
            return;
        }
        var camera = state.cameraRenderState;
        boolean surfaceWorld = state.skyRenderState.skybox != Skybox.NONE && state.skyRenderState.skybox != Skybox.END;
        if ((config.overworldOnly && !surfaceWorld) || camera.isPanoramicMode
                || camera.fogType == FogType.LAVA || camera.fogType == FogType.POWDER_SNOW) {
            shadowCache.clearOff();
            filmHistory.invalidate();
            temporalHistory.invalidate();
            reactiveMask.invalidate();
            return;
        }

        try {
            ensureResources();
            frame.config = config;
            var client=Minecraft.getInstance();
            frame.weather = WeatherInput.snapshot(state.skyRenderState.rainBrightness,
                    client.getDeltaTracker().getGameTimeDeltaPartialTick(false),frame.lab);
            double angle = state.skyRenderState.sunAngle; // Already radians, from SkyRenderer render state.
            if (config.freezeSun) {
                if (!Double.isFinite(frozenAngle)) frozenAngle = angle;
                angle = frozenAngle;
            } else frozenAngle = Double.NaN;
            // The same extracted solar position drives the visible sun AND the shadow map.
            SolarEphemeris.Position geographic = config.geographicSun
                    ? SolarRuntime.current(Minecraft.getInstance().level,frame.lab) : null;
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
                    * (WeatherClient.active(frame.lab)?1f:1-.70f*frame.weather.cloudCover()) : 0;
            frame.inFluid = camera.fogType != FogType.NONE;
            frame.atmosphereAllowed=ordinaryAtmosphere(client.level,camera);
            frame.weatherSky=frame.atmosphereAllowed && module(frame.lab,"weather",true);
            frame.backgroundAvailable=com.dark.winter.visuals.vanillafog.VanillaFogRenderer.current()!=null;
            if (frame.inFluid) frame.intensity = 0;
            snowLight=WkeSnowLighting.sunlight(frame.sun.x,frame.sun.y,frame.sun.z,frame.intensity);
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
            var nativeBackground=com.dark.winter.visuals.vanillafog.VanillaFogRenderer.current();
            if(frame.weatherSky && nativeBackground!=null && !nativeBackground.weatherSky().prepare(frame))
                frame.weatherSky=false;
            if (shadowFailed || !module(frame.lab,"visuals",config.shadows) || frame.intensity <= .001f) {
                shadowCache.clearOff();
                return;
            }

            float radius = config.quality.radius;
            // Preserve the proven stable original grid in vanilla fallback mode.
            WkeShadowGrid.Capture capture = geographic == null
                    ? grid.capture(camera.pos.x,camera.pos.y,camera.pos.z,angle,radius,config.quality.resolution)
                    : grid.captureSun(camera.pos.x,camera.pos.y,camera.pos.z,
                            frame.sun.x,frame.sun.y,frame.sun.z,radius,config.quality.resolution);
            Matrix4f lightView = new Matrix4f().set(capture.view());
            Matrix4f lightProjection = new Matrix4f().ortho(-radius,radius,-radius,radius,6*radius,.1f,true);
            Matrix4f lightClip = new Matrix4f(lightProjection).mul(lightView);
            var atlas = client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            var filtering = game.gameRenderState().optionsRenderState.textureFiltering;
            boolean reuseBackend = ShadowCaptureCache.requested()
                    && "Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName());
            long captureNanos=System.nanoTime();
            long terrainEpoch=ShadowCaptureCache.terrainEpoch(),animationEpoch=ShadowCaptureCache.animationEpoch();
            if (shadowCache.reuse(reuseBackend,world,resourceEpoch,configRevision,shadowTarget,atlas,filtering,
                    shadowDepth.cacheMode(),currentResolution,config.quality.ordinal(),radius,frame.clipRange,
                    camera.pos.x,camera.pos.y,camera.pos.z,lightClip,terrainEpoch,animationEpoch,captureNanos)) {
                if (terrainEpoch==ShadowCaptureCache.terrainEpoch() && animationEpoch==ShadowCaptureCache.animationEpoch()) {
                    shadowCache.copyMatrix(frame.shadowFromRelative);
                    frame.shadowValid=true;
                    return;
                }
                shadowCache.rejectRacedReuse();
            }
            // Use light model-view DIRECTLY. No inverse(cameraRotation) cancellation in projection.
            ChunkSectionsToRender terrain = scene.darkwinter$prepareShadowTerrain(lightView, lightClip);
            RenderSystem.resizeAllAutoStorageIndexBuffers();

            GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
            ProjectionType oldType = RenderSystem.getProjectionType();
            GpuBufferSlice oldFog = RenderSystem.getShaderFog();
            GpuBufferSlice oldLights = RenderSystem.getShaderLights();
            // Background-aware camera fog must never replace the shadow capture's terrain pipelines.
            var cameraBackground=com.dark.winter.visuals.vanillafog.VanillaFogRenderer.current();
            com.dark.winter.visuals.vanillafog.VanillaFogRenderer.restore(null);
            try {
                RenderSystem.setProjectionMatrix(shadowProjection.getBuffer(lightProjection), ProjectionType.ORTHOGRAPHIC);
                RenderSystem.setShaderFog(terrainFog);
                game.lighting().setupFor(Lighting.Entry.LEVEL);
                // Resolve both candidate programs before any depth draw; native shading is the fallback.
                boolean depthOnlyShadow = shadowDepth.prepare(terrain);
                // Upload and prepare immutable snow caster handles before entering an active GPU render pass.
                SnowSurfaceClient.prepareShadowCasters(lightView,lightClip,camera.pos.x,camera.pos.y,camera.pos.z);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "DarkWinter Visuals / shadow capture",
                        shadowTarget.getColorTextureView(), Optional.of(new Vector4f(0)),
                        shadowTarget.getDepthTextureView(), OptionalDouble.of(0.0))) {
                    RenderSystem.bindDefaultUniforms(pass);
                    if (depthOnlyShadow) {
                        shadowDepth.renderPrepared(terrain,pass,terrainSampler,atlas,game.lightmap());
                    } else {
                        terrain.renderGroup(ChunkSectionLayerGroup.OPAQUE,pass,terrainSampler,atlas,false);
                    }
                    // Terrain caps claimed by snow have been omitted from chunk meshes. Cast their exact published replacements.
                    SnowSurfaceClient.drawShadow(pass,lightView,lightClip,camera.pos.x,camera.pos.y,camera.pos.z);
                }
            } finally {
                com.dark.winter.visuals.vanillafog.VanillaFogRenderer.restore(cameraBackground);
                if (oldProjection!=null) RenderSystem.setProjectionMatrix(oldProjection,oldType);
                if (oldFog!=null) RenderSystem.setShaderFog(oldFog);
                if (oldLights!=null) RenderSystem.setShaderLights(oldLights);
            }
            // Publish map + the EXACT matrix that produced it together, in this frame.
            frame.shadowFromRelative.set(lightClip);
            frame.shadowValid = true;
            shadowCache.captured(reuseBackend,world,resourceEpoch,configRevision,shadowTarget,atlas,filtering,
                    shadowDepth.cacheMode(),currentResolution,config.quality.ordinal(),radius,frame.clipRange,
                    camera.pos.x,camera.pos.y,camera.pos.z,lightClip,terrainEpoch,animationEpoch,
                    ShadowCaptureCache.terrainEpoch(),ShadowCaptureCache.animationEpoch(),captureNanos);
        } catch (RuntimeException error) {
            shadowCache.clearError();
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
        double speed=WeatherClient.active(frame.lab)?frame.weather.windSpeed():.12;
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
            temporalHistory.invalidate();
            reactiveMask.invalidate();
            return;
        }
        SceneFrame previous = SceneFrame.active;
        GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldType = RenderSystem.getProjectionType();
        GpuBufferSlice oldFog = RenderSystem.getShaderFog();
        GpuBufferSlice oldLights = RenderSystem.getShaderLights();
        try {
            var background=com.dark.winter.visuals.vanillafog.VanillaFogRenderer.latest();
            boolean backgroundReady=background!=null && background.cloudTarget()!=null;
            frame.backgroundAvailable=backgroundReady;
            boolean fusedComposite = FUSED_COMPOSITE && backgroundReady && config.debug == 0 && !frame.inFluid
                    && "Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName());
            RenderTarget main = game.mainRenderTarget();
            filmHistory.prepare(Minecraft.getInstance(), state, main, frame, config);
            var camera=state.cameraRenderState;
            boolean reactiveReady=reactiveMask.prepare(main,frame.inverseViewProjection,
                    camera.pos.x,camera.pos.y,camera.pos.z,SceneFrame.resourceEpoch,
                    config.enabled && config.antialiasEnabled && config.debug==0);
            // Missing producer coverage must never permit an unmarked temporal contribution.
            if(!reactiveReady) temporalHistory.invalidate();
            temporalHistory.prepare(Minecraft.getInstance(), state, main, frame, config);
            if(!TemporalResolveHistory.temporalReady()) reactiveMask.clearHistory();
            SceneFrame.active = frame;
            Map<Identifier,RenderTarget> targets = new HashMap<>();
            targets.put(MAIN,main);
            targets.put(SHADOW,shadowTarget);
            filmHistory.bindTargets(targets);
            temporalHistory.bindTargets(targets);
            reactiveMask.bindTargets(targets);
            targets.put(com.dark.winter.visuals.vanillafog.VanillaFogRenderer.SKY_BACKGROUND,
                    backgroundReady?background.skyTarget():main);
            targets.put(com.dark.winter.visuals.vanillafog.VanillaFogRenderer.CLOUD_BACKGROUND,
                    backgroundReady?background.cloudTarget():main);
            effectGraph.execute(shaders,main,targets,config.quality.name().toLowerCase(Locale.ROOT),
                    fusedComposite,allocator,SceneFrame.resourceEpoch);
            if(reactiveReady) {
                temporalHistory.capture();
                // This is true after the first successful warm-up capture as well.
                if(TemporalResolveHistory.temporalReady()) reactiveMask.capture();
            } else temporalHistory.invalidate();
            filmHistory.capture();
        } catch (RuntimeException error) {
            filmHistory.invalidate();
            temporalHistory.invalidate();
            reactiveMask.invalidate();
            shadowCache.clearError();
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
    private static boolean module(com.dark.winter.testing.TestingPresentation lab,String name,boolean ordinary) {
        return lab==null?ordinary:lab.enabled(name);
    }
    private static boolean pipeline(com.dark.winter.testing.TestingPresentation lab,boolean ordinary) {
        return lab==null?ordinary:lab.enabled("visuals")||lab.enabled("weather")||lab.enabled("atmosphere");
    }
    private static float smoothstep(float a,float b,float x) {
        float t=Math.max(0,Math.min(1,(x-a)/(b-a))); return t*t*(3-2*t);
    }
    private void release() {
        shadowCache.clearRelease();
        filmHistory.close();
        temporalHistory.close();
        reactiveMask.close();
        effectGraph.invalidate();
        if (shadowTarget!=null) { shadowTarget.destroyBuffers(); shadowTarget=null; }
        if (shadowProjection!=null) { shadowProjection.close(); shadowProjection=null; }
        if (terrainSampler!=null) { terrainSampler.close(); terrainSampler=null; }
        currentResolution=0; frame.shadowValid=false; grid.reset(); frozenAngle=Double.NaN;
    }
    @Override public void close() {
        release();
        world = null;
        frameReady = false;
        frame.filmHistoryValid = false;
        frame.weather = null;
        frame.lab = null;
        lastWeatherNanos = 0;
    }
}
