package com.dark.winter.client.equipment;

import com.dark.winter.equipment.GearSlot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.winterkore.engine.surface.geometry.FootRigHulls;
import com.winterkore.engine.surface.geometry.HumanFootPose;
import com.winterkore.engine.surface.geometry.SoleProfiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Actual rendered foot geometry, cached once; client/server share only immutable numeric rig hulls. */
final class FootContact {
    private record Point(float x,float y,float z) { }
    private static final GearSlot[] FOOT_LAYERS={GearSlot.SOCKS,GearSlot.FOOTWEAR};
    private static final Map<ModelPart,Point[]> SOLES=new WeakHashMap<>();
    private static final Map<ModelPart,Map<Integer,FootRigHulls.Hull>> FP_HULLS=new WeakHashMap<>();
    private static final ThreadLocal<PoseStack> POSES=ThreadLocal.withInitial(PoseStack::new);
    private static boolean initialized;
    private FootContact(){}
    static void init(){
        if(initialized)return;initialized=true;bakeRigs();
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("darkwinter","foot_contact_rigs"),
                (ResourceManagerReloadListener)manager->bakeRigs());
    }
    private static Point[] points(ModelPart foot) {
        return SOLES.computeIfAbsent(foot,key->{
            var result=new ArrayList<Point>();
            key.visit(new PoseStack(),(pose,path,index,cube)->{
                if(!path.isEmpty())return;
                for(var face:cube.polygons)for(var vertex:face.vertices())result.add(new Point(vertex.x(),vertex.y(),vertex.z()));
            });
            return result.stream().distinct().toArray(Point[]::new);
        });
    }
    private static int profile(int shoe,int sock){return shoe>=6?SoleProfiles.CINEMATIC_BOOT:shoe==5?SoleProfiles.NORTHERN_BOOT:
            shoe>0?SoleProfiles.LEGACY_BOOT:sock>0?SoleProfiles.SOCK:SoleProfiles.BARE;}
    private static int rigKey(int shoe,int sock){return shoe>0?8+shoe:sock;}
    private static void add(List<FootRigHulls.Vertex> output,ModelPart geometry,PoseStack fit){
        var m=fit.last().pose();
        for(var p:points(geometry))output.add(new FootRigHulls.Vertex(
                m.m00()*p.x+m.m10()*p.y+m.m20()*p.z+16*m.m30(),
                m.m01()*p.x+m.m11()*p.y+m.m21()*p.z+16*m.m31(),
                m.m02()*p.x+m.m12()*p.y+m.m22()*p.z+16*m.m32()));
    }
    private static FootRigHulls.Hull hull(PlayerModel model,int outfit,boolean left){
        var vertices=new ArrayList<FootRigHulls.Vertex>();var poses=POSES.get();poses.pushPose();
        try{
            var leg=left?model.leftLeg:model.rightLeg;var bone=left?ClothingMeshes.Bone.LEFT_FOOT:ClothingMeshes.Bone.RIGHT_FOOT;
            // Skin is already fitted/baked; clothing uses the exact extra scale used by ClothingLayer.draw.
            add(vertices,HumanJoints.foot(leg),poses);
            boolean third=ThirdPersonBodyModel.isThirdPerson(model)||CharacterVisuals.hasWorldLegs(model);
            for(var slot:FOOT_LAYERS){
                var pieces=ClothingLayer.renderPieces(model,slot,ClothingLayer.variant(outfit,slot),null,false);
                if(pieces==null)continue;
                for(var piece:pieces)if(piece.bone()==bone){
                    var geometry=third?ThirdPersonBodyModel.garment(piece):FirstPersonSurfaces.garment(piece);
                    if(geometry==null)continue;poses.pushPose();
                    try{if(third)ThirdPersonBodyModel.fit(bone,poses);add(vertices,geometry,poses);}finally{poses.popPose();}
                }
            }
            int shoe=ClothingLayer.variant(outfit,GearSlot.FOOTWEAR),sock=ClothingLayer.variant(outfit,GearSlot.SOCKS);
            return new FootRigHulls.Hull(profile(shoe,sock),vertices.stream().distinct().toList());
        }finally{poses.popPose();}
    }
    /** Complete bake before any world opens; reload publication is one atomic immutable replacement. */
    private static void bakeRigs(){
        PlayerModel model=new PlayerModel(ThirdPersonBodyModel.createRoot(false),false);
        var rigs=new LinkedHashMap<Integer,FootRigHulls.Hull>();
        for(int sock=0;sock<=3;sock++){
            int outfit=ClothingLayer.withVariant(0,GearSlot.SOCKS,sock);
            for(boolean left:new boolean[]{false,true})rigs.put(FootRigHulls.key(sock,left),hull(model,outfit,left));
        }
        for(int shoe=1;shoe<=13;shoe++){
            int outfit=ClothingLayer.withVariant(0,GearSlot.FOOTWEAR,shoe);
            for(boolean left:new boolean[]{false,true})rigs.put(FootRigHulls.key(8+shoe,left),hull(model,outfit,left));
        }
        FootRigHulls.replaceAll(rigs);
    }
    /** Legacy entry point retained for existing geometry reviews. */
    static float depth(ModelPart leg,float upper,float lower,LocomotionCycles.Leg pose){
        float angle=pose.thigh()+pose.knee()+pose.foot(),c=(float)Math.cos(angle),s=(float)Math.sin(angle),sole=Float.NEGATIVE_INFINITY;
        for(var point:points(HumanJoints.foot(leg)))sole=Math.max(sole,point.y*c-point.z*s);
        return upper*(float)Math.cos(pose.thigh())+lower*(float)Math.cos(pose.thigh()+pose.knee())+sole;
    }
    static float depth(PlayerModel model,AvatarRenderState state,ModelPart leg,boolean left){
        Integer stored=((FabricRenderState)state).getData(ClothingLayer.OUTFIT);int outfit=stored==null?0:stored;
        int shoe=ClothingLayer.variant(outfit,GearSlot.FOOTWEAR),sock=ClothingLayer.variant(outfit,GearSlot.SOCKS);
        FootRigHulls.Hull hull;
        if(ThirdPersonBodyModel.isThirdPerson(model)||CharacterVisuals.hasWorldLegs(model)){
            if(!initialized)init();
            hull=FootRigHulls.snapshot().hull(rigKey(shoe,sock),left,profile(shoe,sock));
        }else{
            var cache=FP_HULLS.computeIfAbsent(HumanJoints.foot(leg),ignored->new java.util.HashMap<>());
            hull=cache.computeIfAbsent(rigKey(shoe,sock),ignored->hull(model,outfit,left));
        }
        return (float)HumanFootPose.supportDepth(hull,HumanJoints.shin(leg).y,HumanJoints.foot(leg).y,
                leg.xRot,HumanJoints.shin(leg).xRot,HumanJoints.foot(leg).xRot,leg.yRot,leg.zRot);
    }
    /** Independent actual ModelPart/PoseStack path for SDK parity, never used in normal render support. */
    static float matrixDepthForReview(PlayerModel model,AvatarRenderState state,ModelPart leg,boolean left){
        Integer stored=((FabricRenderState)state).getData(ClothingLayer.OUTFIT);int outfit=stored==null?0:stored;
        var actual=hull(model,outfit,left);var bone=left?ClothingMeshes.Bone.LEFT_FOOT:ClothingMeshes.Bone.RIGHT_FOOT;
        var poses=POSES.get();poses.pushPose();
        try{
            HumanJoints.transform(model,bone,poses);var m=poses.last().pose();double max=-Double.MAX_VALUE;
            for(var p:actual.vertices())max=Math.max(max,m.m01()*p.x()+m.m11()*p.y()+m.m21()*p.z()+16*m.m31());
            return (float)(max-leg.y);
        }finally{poses.popPose();}
    }
}
