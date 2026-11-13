// Minecraft host ownership for WKE's native-resolution, camera-reprojected temporal resolve.
package com.dark.winter.visuals;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.winterkore.engine.graphics.WkeTemporalResolve;
import java.nio.ByteBuffer;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Render-owner only. Color history is raw TAA output, never film, hand or HUD.
 * Depth history is copied from the same completed world before the hand clears depth.
 */
public final class TemporalResolveHistory implements AutoCloseable {
    public static final Identifier CURRENT=Identifier.fromNamespaceAndPath("wke","temporal_current");
    public static final Identifier HISTORY=Identifier.fromNamespaceAndPath("wke","temporal_history");
    // The shader writes this format directly; only the later presentation pass quantizes to main RGBA8.
    public static final GpuFormat COLOR_FORMAT=GpuFormat.RGBA16_FLOAT;
    private static final long MAX_GAP_NANOS=250_000_000L;
    private static final boolean DEBUG_WEIGHTS=Boolean.getBoolean("wke.temporal.debugWeights");
    private static TemporalResolveHistory active;
    private static final WkeTemporalResolve DISABLED=new WkeTemporalResolve();
    private final WkeTemporalResolve controls=new WkeTemporalResolve();
    private final Matrix4f jitteredProjection=new Matrix4f();
    private final Matrix4f currentViewProjection=new Matrix4f();
    private final Matrix4f previousViewProjection=new Matrix4f();
    private TextureTarget current,previous;
    private RenderTarget main;
    private Object world,previousCameraType,previousCameraEntity;
    private Vec3 currentPosition=Vec3.ZERO,previousPosition;
    private float currentYaw,currentPitch,previousYaw,previousPitch;
    private float currentProjectionX,currentProjectionY,previousProjectionX,previousProjectionY;
    private boolean currentFirstPerson,previousFirstPerson;
    private boolean prepared,enabled,valid,allowCapture,graphReady,projectionJittered;
    private float jitterX,jitterY,previousJitterX,previousJitterY;
    private int phase,resourceEpoch=-1;
    private long configRevision=-1,currentNanos,lastNanos,captures,resets,jitteredFrames;
    private String resetReason="initial";

    public TemporalResolveHistory() { active=this; }

    /**
     * Called only at GameRenderer.renderLevel's world projection upload. The host
     * uploads a separate projection in render3dHud, so hand/HUD never receive jitter.
     */
    public static Matrix4f jitterProjection(Matrix4f original,GameRenderer game) {
        TemporalResolveHistory owner=active;
        if(owner==null) return original;
        Minecraft client=Minecraft.getInstance();
        VisualConfig config=VisualConfig.shared();
        config.poll();
        CameraRenderState camera=game.gameRenderState().levelRenderState.cameraRenderState;
        boolean eligible=owner.graphReady && owner.world==client.level && client.level!=null
                && config.enabled && config.antialiasEnabled && config.debug==0
                && !client.isPaused() && client.gui.screen()==null
                && camera.initialized && !camera.isPanoramicMode && camera.fogType==FogType.NONE;
        RenderTarget target=game.mainRenderTarget();
        eligible=eligible && target.width>0 && target.height>0 && owner.current!=null
                && owner.current.width==target.width && owner.current.height==target.height;
        owner.projectionJittered=eligible;
        owner.jitterX=eligible?WkeTemporalResolve.jitterX(owner.phase):0;
        owner.jitterY=eligible?WkeTemporalResolve.jitterY(owner.phase):0;
        if(!eligible) return original;
        // Left multiplication shifts clip.xy by offset*clip.w, including bob/skew.
        // It does not mutate CameraRenderState's unjittered culling projection.
        owner.jitteredProjection.identity().translation(2f*owner.jitterX/target.width,
                2f*owner.jitterY/target.height,0).mul(original);
        owner.jitteredFrames++;
        return owner.jitteredProjection;
    }

    public void prepare(Minecraft client,LevelRenderState state,RenderTarget main,
                        SceneFrame frame,VisualConfig config) {
        active=this;
        ensureTargets(main);
        this.main=main;
        prepared=true;
        CameraRenderState camera=state.cameraRenderState;
        currentNanos=System.nanoTime();
        boolean nextEnabled=config.antialiasEnabled && config.debug==0 && config.enabled
                && !client.isPaused() && client.gui.screen()==null
                && camera.initialized && !camera.isPanoramicMode && camera.fogType==FogType.NONE;
        if(world!=client.level) { invalidate("world");world=client.level; }
        if(resourceEpoch!=SceneFrame.resourceEpoch || configRevision!=config.revision) {
            invalidate("resources/config");
            resourceEpoch=SceneFrame.resourceEpoch;configRevision=config.revision;
        }
        if(!nextEnabled) invalidate("disabled/paused/unsupported view");
        else if(lastNanos!=0 && (currentNanos<lastNanos || currentNanos-lastNanos>MAX_GAP_NANOS))
            invalidate("frame gap");
        else if(valid && cameraCut(client,camera)) invalidate("camera cut");
        // invalidate() resets published frame flags; this frame still has a valid preparation.
        prepared=true;
        enabled=nextEnabled;
        allowCapture=nextEnabled;
        currentPosition=camera.pos;
        currentYaw=camera.yRot;currentPitch=camera.xRot;
        currentFirstPerson=camera.isFirstPerson;
        currentProjectionX=camera.projectionMatrix.m00();
        currentProjectionY=camera.projectionMatrix.m11();
        currentViewProjection.set(frame.inverseViewProjection).invert();
        frame.inverseViewProjection.get(controls.currentInverse);
        previousViewProjection.get(controls.previousViewProjection);
        controls.cameraDeltaHistory[0]=previousPosition==null?0:(float)(currentPosition.x-previousPosition.x);
        controls.cameraDeltaHistory[1]=previousPosition==null?0:(float)(currentPosition.y-previousPosition.y);
        controls.cameraDeltaHistory[2]=previousPosition==null?0:(float)(currentPosition.z-previousPosition.z);
        controls.cameraDeltaHistory[3]=enabled&&valid?1:0;
        controls.resolveControl[0]=WkeTemporalResolve.STATIC_HISTORY_RETENTION;
        controls.resolveControl[1]=.01f;
        controls.resolveControl[2]=config.flipDepthY?1:0;
        // Only the actual nearby animated spruce pass requests its conservative
        // high-frequency motion guard. Static distant bough meshes retain full history.
        boolean windReactive=com.dark.winter.client.SpruceNeedleRenderer.lastDrawCount()>0
                && com.dark.winter.client.SpruceNeedleRenderer.lastWindAmplitude()>0;
        controls.resolveControl[3]=enabled?(windReactive?2:1)+(DEBUG_WEIGHTS?WkeTemporalResolve.DEBUG_WEIGHTS_FLAG:0):0;
        controls.jitterControl[0]=jitterX;controls.jitterControl[1]=jitterY;
        controls.jitterControl[2]=previousJitterX;controls.jitterControl[3]=previousJitterY;
        if(com.dark.winter.client.diagnostics.vegetation.VegetationTrace.enabled())
            com.dark.winter.client.diagnostics.vegetation.VegetationTrace.temporal(currentViewProjection,enabled,
                    controls.cameraDeltaHistory[3]>0,projectionJittered,phase,jitterX,jitterY,resets,captures,
                    resourceEpoch,configRevision,resetReason);
    }

    public void bindTargets(Map<Identifier,RenderTarget> targets) {
        if(current==null||previous==null) throw new IllegalStateException("Temporal targets were not prepared");
        targets.put(CURRENT,current);targets.put(HISTORY,previous);
    }

    /** Called only after successful post graph execution, before GameRenderer renders the hand. */
    public void capture() {
        if(!allowCapture || current==null || previous==null || main==null) return;
        current.copyDepthFrom(main);
        TextureTarget reusable=previous;previous=current;current=reusable;
        previousViewProjection.set(currentViewProjection);
        previousPosition=currentPosition;previousYaw=currentYaw;previousPitch=currentPitch;
        previousFirstPerson=currentFirstPerson;
        previousProjectionX=currentProjectionX;previousProjectionY=currentProjectionY;
        Minecraft client=Minecraft.getInstance();
        previousCameraType=client.options.getCameraType();previousCameraEntity=client.getCameraEntity();
        previousJitterX=jitterX;previousJitterY=jitterY;
        lastNanos=currentNanos;
        captures++;
        valid=graphReady=true;
        phase=(phase+1)%WkeTemporalResolve.SAMPLE_COUNT;
    }

    public void invalidate() { invalidate("host invalidation"); }
    private void invalidate(String reason) {
        if(valid || allowCapture || graphReady || prepared) resets++;
        valid=allowCapture=graphReady=prepared=enabled=false;
        phase=0;lastNanos=0;previousPosition=null;
        previousCameraType=previousCameraEntity=null;
        previousJitterX=previousJitterY=0;
        resetReason=reason;
    }

    private boolean cameraCut(Minecraft client,CameraRenderState camera) {
        return previousPosition==null || camera.isFirstPerson!=previousFirstPerson
                || client.options.getCameraType()!=previousCameraType || client.getCameraEntity()!=previousCameraEntity
                || camera.pos.distanceToSqr(previousPosition)>16.0
                || Math.abs(Math.IEEEremainder(camera.yRot-previousYaw,360.0))>45.0
                || Math.abs(camera.xRot-previousPitch)>45.0
                || changedProjection(camera.projectionMatrix.m00(),previousProjectionX)
                || changedProjection(camera.projectionMatrix.m11(),previousProjectionY);
    }
    private static boolean changedProjection(float a,float b) {
        return Math.abs(a-b)>Math.max(.01f,Math.abs(b)*.08f);
    }

    private void ensureTargets(RenderTarget main) {
        var color=COLOR_FORMAT;
        var depth=main.getDepthTexture().getFormat();
        if(current!=null && current.width==main.width && current.height==main.height
                && current.getColorTexture().getFormat()==color && current.getDepthTexture().getFormat()==depth) return;
        destroyTargets();invalidate("resolution/format");
        current=new TextureTarget("WKE current temporal world",main.width,main.height,color,depth);
        previous=new TextureTarget("WKE previous temporal world",main.width,main.height,color,depth);
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(current.getColorTexture(),new Vector4f(0),current.getDepthTexture(),0);
        encoder.clearColorAndDepthTextures(previous.getColorTexture(),new Vector4f(0),previous.getDepthTexture(),0);
    }

    public static boolean temporalReady() {
        return active!=null && active.prepared && active.enabled && active.valid;
    }
    public static void writeActive(ByteBuffer target) {
        (active==null?DISABLED:active.controls).write(target);
    }
    public static void resetActive() {
        if(active!=null) active.invalidate("explicit test reset");
    }
    /** Actual matrices and jitter for image registration; counters do not assert visual correctness. */
    public static Map<String,Object> statistics() {
        TemporalResolveHistory a=active;
        if(a==null) return Map.of("prepared",false,"historyValid",false,"temporalEnabled",false,"captures",0L,"resets",0L);
        return Map.ofEntries(
                Map.entry("prepared",a.prepared),Map.entry("historyValid",a.valid),
                Map.entry("temporalEnabled",a.enabled),Map.entry("captures",a.captures),Map.entry("resets",a.resets),
                Map.entry("width",a.current==null?0:a.current.width),Map.entry("height",a.current==null?0:a.current.height),
                Map.entry("currentColorFormat",a.current==null?"":a.current.getColorTexture().getFormat().toString()),
                Map.entry("historyColorFormat",a.previous==null?"":a.previous.getColorTexture().getFormat().toString()),
                Map.entry("finalMainColorFormat",a.main==null?"":a.main.getColorTexture().getFormat().toString()),
                Map.entry("phase",a.phase),Map.entry("jitterX",a.jitterX),Map.entry("jitterY",a.jitterY),
                Map.entry("projectionJittered",a.projectionJittered),Map.entry("jitteredFrames",a.jitteredFrames),
                Map.entry("debugWeightsEncoded",DEBUG_WEIGHTS&&a.enabled),
                Map.entry("currentInverse",a.controls.currentInverse.clone()),
                Map.entry("currentCameraXYZ",new double[]{a.currentPosition.x,a.currentPosition.y,a.currentPosition.z}),
                Map.entry("previousViewProjection",a.controls.previousViewProjection.clone()),
                Map.entry("resetReason",a.resetReason));
    }
    private void destroyTargets() {
        if(current!=null) { current.destroyBuffers();current=null; }
        if(previous!=null) { previous.destroyBuffers();previous=null; }
    }
    @Override public void close() {
        invalidate("closed");destroyTargets();main=null;world=null;
        if(active==this) active=null;
    }
}
