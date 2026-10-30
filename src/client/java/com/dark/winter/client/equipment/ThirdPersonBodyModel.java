// Builds compact third-person body proportions from the existing skin and rig.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;

/**
 * Approved compact third-person proportions. The legacy skin/UV islands stay intact;
 * all vertex and joint fitting is baked once, never into first-person geometry.
 */
public final class ThirdPersonBodyModel {
    public static final float HIP_Y=11.3f, LEG_LENGTH=12.7f;
    public static final float ELBOW_Y=HumanJoints.ELBOW_Y*.93f;
    public static final float KNEE_Y=HumanJoints.KNEE_Y*(LEG_LENGTH/HumanBodyModel.LEG_LENGTH);
    public static final float ANKLE_Y=HumanJoints.ANKLE_Y*(LEG_LENGTH/HumanBodyModel.LEG_LENGTH);
    private record Fit(float x,float y,float z) {
        void apply(PoseStack poses) { poses.scale(x,y,z); }
    }
    private static final Fit HEAD=new Fit(1.40f,1.25f,1.25f);
    private static final Fit TORSO=new Fit(1.09f,(HIP_Y-1.4f)/HumanBodyModel.HIP_Y,1.12f);
    private static final Fit ARM=new Fit(1.07f,.93f,1.08f);
    private static final Fit LEG=new Fit(1.15f,LEG_LENGTH/HumanBodyModel.LEG_LENGTH,1.10f);

    private static final Map<ClothingMeshes.Piece,ModelPart> GARMENTS=new IdentityHashMap<>();

    static ModelPart garment(ClothingMeshes.Piece piece) {
        return GARMENTS.computeIfAbsent(piece,ThirdPersonContours::garment);
    }

    public static ModelPart createRoot(boolean slim) {
        ModelPart source=HumanBodyModel.createRoot(slim);
        Map<String,ModelPart> parts=new LinkedHashMap<>();
        parts.put("darkwinter_human",FacetedMesh.mesh().bake());
        parts.put("darkwinter_third_person",FacetedMesh.mesh().bake());
        ModelPart originalHead=source.getChild("head");
        ModelPart oldHead=ThirdPersonHead.head(Map.of("hat",originalHead.getChild("hat"),
                "hair",ThirdPersonHead.hair(),"beard",originalHead.getChild("beard")));
        ModelPart head=copy(oldHead,HEAD,Bone.HEAD,children(oldHead,HEAD,Bone.HEAD,"hat","hair","beard"));
        pose(head,0,1.54f,0);
        parts.put("head",head);
        ModelPart oldBody=source.getChild("body");
        ModelPart body=copy(oldBody,TORSO,Bone.BODY,children(oldBody,TORSO,Bone.BODY,"jacket","neck","underwear"));
        pose(body,0,1.4f,0);
        parts.put("body",body);
        for(boolean left:new boolean[]{false,true}) {
            String side=left?"left":"right";
            Bone armBone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM,forearmBone=left?Bone.LEFT_FOREARM:Bone.RIGHT_FOREARM;
            Bone legBone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG,shinBone=left?Bone.LEFT_SHIN:Bone.RIGHT_SHIN,
                    footBone=left?Bone.LEFT_FOOT:Bone.RIGHT_FOOT;
            ModelPart oldArm=source.getChild(side+"_arm"),oldForearm=oldArm.getChild("forearm");
            ModelPart forearm=copy(oldForearm,ARM,forearmBone,children(oldForearm,ARM,forearmBone,side+"_sleeve"));
            ModelPart arm=copy(oldArm,ARM,armBone,Map.of(
                    side+"_sleeve",copy(oldArm.getChild(side+"_sleeve"),ARM,armBone,Map.of()),"forearm",forearm));
            pose(arm,(left?1:-1)*5.16f,1.5f,0);
            parts.put(side+"_arm",arm);
            ModelPart oldLeg=source.getChild(side+"_leg"),oldShin=oldLeg.getChild("shin"),oldFoot=oldShin.getChild("foot");
            ModelPart foot=copy(oldFoot,LEG,footBone,children(oldFoot,LEG,footBone,side+"_pants"));
            ModelPart shin=copy(oldShin,LEG,shinBone,Map.of(
                    side+"_pants",copy(oldShin.getChild(side+"_pants"),LEG,shinBone,Map.of()),"foot",foot));
            ModelPart leg=copy(oldLeg,LEG,legBone,Map.of(
                    side+"_pants",copy(oldLeg.getChild(side+"_pants"),LEG,legBone,Map.of()),
                    "underwear",copy(oldLeg.getChild("underwear"),LEG,legBone,Map.of()),"shin",shin));
            pose(leg,(left?1:-1)*2.35f,HIP_Y,0);
            parts.put(side+"_leg",leg);
        }
        return new ModelPart(java.util.List.of(),parts);
    }

    /** Fit a separate authored head to the same clothing/animation skeleton without touching the legacy root. */
    static ModelPart fittedProfileHead(ModelPart source) {
        ModelPart head=copy(source,HEAD,Bone.HEAD,children(source,HEAD,Bone.HEAD,"hat","hair","beard"));
        pose(head,0,1.54f,0);return head;
    }

    public static boolean isThirdPerson(PlayerModel model) { return model.root().hasChild("darkwinter_third_person"); }

    /** Existing garment meshes follow the fitted bone without altering their stored shape or materials. */
    public static void fit(ClothingMeshes.Bone bone,PoseStack poses) {
        switch(bone) {
            case HEAD -> HEAD.apply(poses);
            case BODY -> TORSO.apply(poses);
            case LEFT_ARM,RIGHT_ARM,LEFT_FOREARM,RIGHT_FOREARM -> ARM.apply(poses);
            case LEFT_LEG,RIGHT_LEG,LEFT_SHIN,RIGHT_SHIN,LEFT_FOOT,RIGHT_FOOT -> LEG.apply(poses);
        }
    }

    /** Held items keep their own dimensions while their grip origin follows the fitted palm. */
    public static void handOrigin(PoseStack poses,boolean right) {
        // ItemInHandLayer contributes (+/-1, +10, -2) model units after its fixed rotations.
        // Fit the palm anchor, not the item's dimensions or that fixed downstream translation.
        poses.translate((right?1:-1)/16f,(11.7f*ARM.y-10-ELBOW_Y)/16f,(-2*ARM.z+2)/16f);
    }

    private static Map<String,ModelPart> children(ModelPart source,Fit fit,Bone bone,String... names) {
        Map<String,ModelPart> result=new LinkedHashMap<>();
        for(String name:names)result.put(name,copy(source.getChild(name),fit,bone,Map.of()));
        return result;
    }
    private static ModelPart copy(ModelPart source,Fit fit,Bone bone,Map<String,ModelPart> children) {
        FacetedMesh mesh=FacetedMesh.mesh();
        source.visit(new PoseStack(),(poses,path,index,cube)->{
            if(!path.isEmpty())return;
            for(var face:cube.polygons) {
                var old=face.vertices();
                ModelPart.Vertex[] points=new ModelPart.Vertex[4];
                for(int i=0;i<4;i++) {
                    var p=ThirdPersonContours.vertex(bone,old[i]);
                    points[i]=new ModelPart.Vertex(p.x()*fit.x,p.y()*fit.y,p.z()*fit.z,p.u(),p.v());
                }
                ThirdPersonContours.face(mesh,points);
            }
        });
        ModelPart result=mesh.bake(children);
        PartPose p=source.getInitialPose();
        result.setInitialPose(PartPose.offsetAndRotation(p.x()*fit.x,p.y()*fit.y,p.z()*fit.z,p.xRot(),p.yRot(),p.zRot()));
        result.resetPose();
        return result;
    }
    private static void pose(ModelPart part,float x,float y,float z) {
        part.setInitialPose(PartPose.offset(x,y,z));
        part.resetPose();
    }
    private ThirdPersonBodyModel() { }
}
