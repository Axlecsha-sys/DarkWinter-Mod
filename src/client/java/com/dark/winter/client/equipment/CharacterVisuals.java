// Extracts a separate player-body pass while preserving native first-person hands and items.
package com.dark.winter.client.equipment;

import com.dark.winter.client.mixin.client.PlayerModelAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** A separately extracted body pass. Vanilla continues to own first-person hands and held items. */
public final class CharacterVisuals {
    public static final RenderStateDataKey<Boolean> FIRST_PERSON_BODY = RenderStateDataKey.create(() -> "darkwinter:first_person_body");
    public static final float EYE_FORWARD_OFFSET = .22f;
    public static final float LEG_FORWARD_MODEL_UNITS = .9f;
    public record BodyFrame(long sequence,int extracted,int submitted,int bodyStates,int avatarStates,
                            boolean sameSnapshot,boolean headAndArmsHidden,String context) { }
    private static final BodyModel WIDE = new BodyModel(false), SLIM = new BodyModel(true);
    private static boolean initialized;
    private static AvatarRenderState extractedBody,submittedBody;
    private static long frameSequence;
    private static int extractedCount,submittedCount;
    private static boolean headAndArmsHidden;
    private static BodyFrame completedFrame=new BodyFrame(0,0,0,0,0,false,false,"not-rendered");
    private static String lastExtraction = "not-called";

    /** Call once from the client initializer, alongside the existing BackpackLayer.init(). */
    public static void init() { initialized = true; }

    public static void extractBody(Camera camera,DeltaTracker ticks,LevelRenderer renderer,LevelRenderState output) {
        Minecraft mc=Minecraft.getInstance();
        frameSequence++;
        extractedCount=0;
        submittedCount=0;
        extractedBody=null;
        submittedBody=null;
        headAndArmsHidden=false;
        if (!initialized || camera.isDetached() || mc.player==null || camera.entity()!=mc.player
                || !mc.options.getCameraType().isFirstPerson() || mc.player.isSpectator() || mc.player.isInvisible()
                || mc.player.isSleeping() || mc.player.isFallFlying() || mc.player.isVisuallySwimming()) {
            lastExtraction="guarded";
            return;
        }
        float partial=ticks.getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(mc.player));
        if (!(renderer.entityRenderDispatcher().extractEntity(mc.player,partial) instanceof AvatarRenderState state)) {
            lastExtraction="non-avatar";
            return;
        }
        ((FabricRenderState)state).setData(FIRST_PERSON_BODY,true);
        state.displayFireAnimation=false;
        state.shadowPieces.clear();
        state.nameTag=null;
        state.scoreText=null;
        output.entityRenderStates.add(state);
        output.lastEntityRenderStateCount=output.entityRenderStates.size();
        extractedCount++;
        extractedBody=state;
        lastExtraction="body-added";
    }

    public static boolean isBody(AvatarRenderState state) {
        return Boolean.TRUE.equals(((FabricRenderState)state).getData(FIRST_PERSON_BODY));
    }

    /** Persist completed-frame evidence before Minecraft clears its transient entity list. */
    public static void finishBodyFrame(LevelRenderState state) {
        int bodies=0,avatars=0;
        for(var entity:state.entityRenderStates)if(entity instanceof AvatarRenderState avatar) {
            avatars++;
            if(isBody(avatar))bodies++;
        }
        completedFrame=new BodyFrame(frameSequence,extractedCount,submittedCount,bodies,avatars,
                extractedBody!=null&&extractedBody==submittedBody,headAndArmsHidden,
                describeBodyContext(Minecraft.getInstance().gameRenderer.mainCamera()));
    }

    public static BodyFrame completedBodyFrame() { return completedFrame; }

    public static String describeBodyContext(Camera camera) {
        Minecraft mc=Minecraft.getInstance();
        var player=mc.player;
        String shoulder=camera instanceof com.dark.winter.client.CameraViewDiagnostics view
                ?view.darkwinter$describeShoulderView():"unavailable";
        return "cameraType="+mc.options.getCameraType()+",detached="+camera.isDetached()
                +",cameraEntity="+camera.entity()+",samePlayer="+(camera.entity()==player)
                +",initialized="+initialized+",extraction="+lastExtraction+",shoulder={"+shoulder+"}"
                +",playerFlags="+(player==null?"null":"spectator="+player.isSpectator()+",invisible="+player.isInvisible()
                +",sleep="+player.isSleeping()+",fallFlying="+player.isFallFlying()+",swim="+player.isVisuallySwimming()
                +",crouch="+player.isCrouching()+",eyeDistance="+camera.position().distanceTo(player.getEyePosition(1)));
    }

    public static void submitBody(AvatarRenderer<?> renderer,AvatarRenderState state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera) {
        BodyModel model=((PlayerModelAccessor)renderer.getModel()).darkwinter$isSlim()?SLIM:WIDE;
        poses.pushPose();
        // The stock camera sits at the head centre. Put this view-only mesh behind the eyes so the
        // neck/collar top caps cannot fill the view. Neither the camera nor the third-person mesh moves.
        double yaw=Math.toRadians(camera.yRot);
        poses.translate(Math.sin(yaw)*EYE_FORWARD_OFFSET*state.scale,0,-Math.cos(yaw)*EYE_FORWARD_OFFSET*state.scale);
        poses.scale(state.scale,state.scale,state.scale);
        poses.rotateDegrees(Axis.YP,180-state.bodyRot);
        poses.scale(-.9375f,-.9375f,.9375f);
        poses.translate(0,-1.501f,0);
        model.setupAnim(state);
        collector.submitModel(model,state,poses,model.renderType(state.skin.body().texturePath()),state.lightCoords,
                OverlayTexture.NO_OVERLAY,-1,null,0);
        Integer outfit=((FabricRenderState)state).getData(ClothingLayer.OUTFIT);
        if(outfit!=null) ClothingLayer.submitBody(model,outfit,poses,collector,state.lightCoords);
        poses.popPose();
        submittedCount++;
        submittedBody=state;
        headAndArmsHidden=!model.head.visible&&!model.leftArm.visible&&!model.rightArm.visible;
    }

    private static final class BodyModel extends PlayerModel {
        private BodyModel(boolean slim) { super(FirstPersonSurfaces.bodyRoot(slim),slim); }
        @Override public void setupAnim(AvatarRenderState state) {
            super.setupAnim(state);
            head.visible=false;
            leftArm.visible=false;
            rightArm.visible=false;
            // A small view-only leg offset keeps shins and toes visible below bulky parka hems.
            leftLeg.z-=LEG_FORWARD_MODEL_UNITS;
            rightLeg.z-=LEG_FORWARD_MODEL_UNITS;
        }
    }
    private CharacterVisuals() { }
}
