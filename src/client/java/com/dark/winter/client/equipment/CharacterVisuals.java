// Renders the local avatar from its eye position using the world model and pose.
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
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The first-person body shares limb poses, garments and held-item attachment with the world avatar. */
public final class CharacterVisuals {
    public static final RenderStateDataKey<Boolean> FIRST_PERSON_BODY = RenderStateDataKey.create(() -> "darkwinter:first_person_body");
    private static final RenderStateDataKey<Boolean> SCREEN_HANDS = RenderStateDataKey.create(() -> "darkwinter:screen_hands");
    public static final float EYE_FORWARD_OFFSET = .22f;
    /** The first-person feet are anchored in world space; there is no view-only forward leg shift. */
    public static final float LEG_FORWARD_MODEL_UNITS = 0;
    public record BodyFrame(long sequence,int extracted,int submitted,int bodyStates,int avatarStates,
                            boolean sameSnapshot,boolean headHidden,int modelArms,String context) { }
    private static final BodyModel WIDE = new BodyModel(false), SLIM = new BodyModel(true);
    private static final PlayerItemInHandLayer<AvatarRenderState, PlayerModel> WIDE_ITEMS = new PlayerItemInHandLayer<>(() -> WIDE);
    private static final PlayerItemInHandLayer<AvatarRenderState, PlayerModel> SLIM_ITEMS = new PlayerItemInHandLayer<>(() -> SLIM);
    private static boolean initialized;
    private static AvatarRenderState extractedBody,submittedBody;
    private static long frameSequence;
    private static int extractedCount,submittedCount;
    private static boolean headHidden;
    private static int modelArms;
    private static BodyFrame completedFrame=new BodyFrame(0,0,0,0,0,false,false,0,"not-rendered");
    private static String lastExtraction = "not-called";

    /** Call once from the client initializer, alongside the existing BackpackLayer.init(). */
    public static void init() { initialized = true; }

    private static boolean ownsBody(Minecraft client, Camera camera) {
        var player = client.player;
        return initialized && player != null && camera.entity() == player && client.options.getCameraType().isFirstPerson()
                && !player.isSpectator() && !player.isInvisible() && !player.isSleeping() && !player.isFallFlying()
                && !player.isVisuallySwimming() && !player.isAutoSpinAttack();
    }

    /** Put the eye in front of the neck, following the forward lean during a crouch. */
    public static Vec3 eyePosition(Minecraft client, Camera camera, float partial) {
        Vec3 eye = camera.position();
        if (camera.isDetached() || !ownsBody(client, camera) || client.player.isScoping()) return eye;
        var player = client.player;
        float scale = player.getScale();
        float crouch = ThirdPersonAnimations.gait(player, partial).crouching();
        double yaw = Math.toRadians(camera.yRot());
        double bodyYaw = Math.toRadians(Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot));
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 lean = new Vec3(-Math.sin(bodyYaw), 0, Math.cos(bodyYaw));
        float lookDown = Mth.clamp((camera.xRot() - 22) / 48, 0, 1);
        lookDown = lookDown * lookDown * (3 - 2 * lookDown);
        float lookAtFeet = Mth.clamp((camera.xRot() - 52) / 38, 0, 1);
        lookAtFeet = lookAtFeet * lookAtFeet * (3 - 2 * lookAtFeet);
        // Aim the downward offset along the torso in a crouch. Moving over the nearer shoulder
        // exposes the opening around the neck, particularly when the player turns in place.
        double downwardOffset = .14 * lookDown + .24 * lookAtFeet;
        double bodyFollow = downwardOffset * crouch;
        Vec3 desired = eye.add(forward.scale((.075 + downwardOffset - bodyFollow) * scale))
                .add(lean.scale((.10 * lookDown + bodyFollow) * scale)).add(0,.045 * scale * crouch,0);
        var hit = player.level().clip(new ClipContext(eye, desired, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return desired;
        Vec3 delta = hit.getLocation().subtract(eye);
        return eye.add(delta.normalize().scale(Math.max(0, delta.length() - .06)));
    }

    public static void extractBody(Camera camera,DeltaTracker ticks,LevelRenderer renderer,LevelRenderState output) {
        Minecraft mc=Minecraft.getInstance();
        frameSequence++;
        extractedCount=0;
        submittedCount=0;
        extractedBody=null;
        submittedBody=null;
        headHidden=false;
        modelArms=0;
        if (camera.isDetached() || !ownsBody(mc, camera)) {
            lastExtraction="guarded";
            return;
        }
        float partial=ticks.getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(mc.player));
        if (!(renderer.entityRenderDispatcher().extractEntity(mc.player,partial) instanceof AvatarRenderState state)) {
            lastExtraction="non-avatar";
            return;
        }
        ((FabricRenderState)state).setData(FIRST_PERSON_BODY,true);
        ((FabricRenderState)state).setData(SCREEN_HANDS, mc.player.isScoping()
                || mc.player.getItemInHand(InteractionHand.MAIN_HAND).has(DataComponents.MAP_ID)
                || mc.player.getItemInHand(InteractionHand.OFF_HAND).has(DataComponents.MAP_ID));
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

    private static boolean screenHands(AvatarRenderState state) {
        return Boolean.TRUE.equals(((FabricRenderState)state).getData(SCREEN_HANDS));
    }

    public static boolean usesModelHands() {
        return extractedBody != null && !screenHands(extractedBody);
    }

    /** Persist completed-frame evidence before Minecraft clears its transient entity list. */
    public static void finishBodyFrame(LevelRenderState state) {
        int bodies=0,avatars=0;
        for(var entity:state.entityRenderStates)if(entity instanceof AvatarRenderState avatar) {
            avatars++;
            if(isBody(avatar))bodies++;
        }
        completedFrame=new BodyFrame(frameSequence,extractedCount,submittedCount,bodies,avatars,
                extractedBody!=null&&extractedBody==submittedBody,headHidden,modelArms,
                describeBodyContext(Minecraft.getInstance().gameRenderer.mainCamera()));
    }

    public static BodyFrame completedBodyFrame() { return completedFrame; }

    public static String describeBodyContext(Camera camera) {
        Minecraft mc=Minecraft.getInstance();
        var player=mc.player;
        var cameraEntity=camera.entity();
        String cameraIdentity=cameraEntity==null?"null":cameraEntity.getClass().getSimpleName()+"#"+Integer.toHexString(System.identityHashCode(cameraEntity));
        String shoulder=camera instanceof com.dark.winter.client.CameraViewDiagnostics view
                ?view.darkwinter$describeShoulderView():"unavailable";
        return "cameraType="+mc.options.getCameraType()+",detached="+camera.isDetached()
                +",cameraEntity="+cameraIdentity+",samePlayer="+(cameraEntity==player)
                +",initialized="+initialized+",extraction="+lastExtraction+",shoulder={"+shoulder+"}"
                +",playerFlags="+(player==null?"null":"spectator="+player.isSpectator()+",invisible="+player.isInvisible()
                +",sleep="+player.isSleeping()+",fallFlying="+player.isFallFlying()+",swim="+player.isVisuallySwimming()
                +",crouch="+player.isCrouching()+",eyeDistance="+camera.position().distanceTo(player.getEyePosition(1)));
    }

    public static void submitBody(AvatarRenderer<?> renderer,AvatarRenderState state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera) {
        boolean slim=((PlayerModelAccessor)renderer.getModel()).darkwinter$isSlim();
        BodyModel model=slim?SLIM:WIDE;
        poses.pushPose();
        poses.scale(state.scale,state.scale,state.scale);
        poses.rotateDegrees(Axis.YP,180-state.bodyRot);
        poses.scale(-.9375f,-.9375f,.9375f);
        poses.translate(0,-1.501f,0);
        model.setupAnim(state);
        collector.submitModel(model,state,poses,RenderTypes.entityCutoutCull(state.skin.body().texturePath()),state.lightCoords,
                OverlayTexture.NO_OVERLAY,-1,null,0);
        Integer outfit=((FabricRenderState)state).getData(ClothingLayer.OUTFIT);
        if(outfit!=null) ClothingLayer.submitBody(model,outfit,poses,collector,state.lightCoords);
        if (!screenHands(state)) {
            (slim ? SLIM_ITEMS : WIDE_ITEMS).submit(poses,collector,state.lightCoords,state,state.yRot,state.xRot);
            FirstPersonGripRenderer.recordBodyGrip(state);
        }
        poses.popPose();
        submittedCount++;
        submittedBody=state;
        headHidden=!model.head.visible;
        modelArms=(model.leftArm.visible?1:0)+(model.rightArm.visible?1:0);
    }

    /** The extracted body shares only its articulated lower-body rig with the world avatar. */
    public static boolean hasWorldLegs(PlayerModel model){return model.root().hasChild("darkwinter_world_legs");}
    static boolean worldLegBone(PlayerModel model,ClothingMeshes.Bone bone){
        if(!hasWorldLegs(model))return false;
        return switch(bone){
            case LEFT_LEG,RIGHT_LEG,LEFT_SHIN,RIGHT_SHIN,LEFT_FOOT,RIGHT_FOOT -> true;
            default -> false;
        };
    }
    /** Cancel the torso's camera-only translation at both leg roots, using the inverse native body basis. */
    public static void anchorBodyFeet(PlayerModel model,float bodyYaw,float cameraYaw){
        double angle=Math.toRadians((double)cameraYaw-bodyYaw);
        double units=EYE_FORWARD_OFFSET*16/com.winterkore.engine.surface.geometry.SoleProfiles.AVATAR_SCALE;
        float x=(float)(-Math.sin(angle)*units),z=(float)(-Math.cos(angle)*units);
        model.leftLeg.x+=x;model.rightLeg.x+=x;
        model.leftLeg.z+=z;model.rightLeg.z+=z;
    }
    private static final class BodyModel extends PlayerModel {
        private BodyModel(boolean slim) { super(ThirdPersonBodyModel.createRoot(slim),slim); }
        @Override public void setupAnim(AvatarRenderState state) {
            super.setupAnim(state);
            head.visible=false;
            body.getChild("neck").visible=false;
            leftArm.visible=!screenHands(state);
            rightArm.visible=leftArm.visible;
        }
    }
    private CharacterVisuals() { }
}
