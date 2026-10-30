// Shared bone paths for skin, clothing, held tools and inventory silhouettes.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/** Shared bone paths for skin, clothing, held tools and inventory silhouettes. */
public final class HumanJoints {
    public static final float ELBOW_Y=5.6f,KNEE_Y=6.1f,ANKLE_Y=11.45f;
    public static ModelPart forearm(ModelPart arm) {return arm.getChild("forearm");}
    public static ModelPart shin(ModelPart leg) {return leg.getChild("shin");}
    public static ModelPart foot(ModelPart leg) {return shin(leg).getChild("foot");}
    public static ModelPart parent(PlayerModel m,ClothingMeshes.Bone bone) {
        return switch(bone) {
            case HEAD->m.head;case BODY->m.body;case LEFT_ARM,LEFT_FOREARM->m.leftArm;case RIGHT_ARM,RIGHT_FOREARM->m.rightArm;
            case LEFT_LEG,LEFT_SHIN,LEFT_FOOT->m.leftLeg;case RIGHT_LEG,RIGHT_SHIN,RIGHT_FOOT->m.rightLeg;
        };
    }
    public static void transform(PlayerModel m,ClothingMeshes.Bone bone,PoseStack pose) {
        ModelPart parent=parent(m,bone);parent.translateAndRotate(pose);
        switch(bone) {
            case LEFT_FOREARM,RIGHT_FOREARM->forearm(parent).translateAndRotate(pose);
            case LEFT_SHIN,RIGHT_SHIN->shin(parent).translateAndRotate(pose);
            case LEFT_FOOT,RIGHT_FOOT->{shin(parent).translateAndRotate(pose);foot(parent).translateAndRotate(pose);}
            default->{ }
        }
    }
    public static void resetChildren(PlayerModel model) {
        for(ModelPart arm:new ModelPart[]{model.leftArm,model.rightArm})if(arm.hasChild("forearm"))forearm(arm).resetPose();
        for(ModelPart leg:new ModelPart[]{model.leftLeg,model.rightLeg})if(leg.hasChild("shin")){shin(leg).resetPose();foot(leg).resetPose();}
    }
    public static void syncOverlays(PlayerModel m) {
        if(!m.leftArm.hasChild("forearm"))return;
        forearm(m.leftArm).getChild("left_sleeve").visible=m.leftSleeve.visible;
        forearm(m.rightArm).getChild("right_sleeve").visible=m.rightSleeve.visible;
        shin(m.leftLeg).getChild("left_pants").visible=m.leftPants.visible;
        shin(m.rightLeg).getChild("right_pants").visible=m.rightPants.visible;
        foot(m.leftLeg).getChild("left_pants").visible=m.leftPants.visible;
        foot(m.rightLeg).getChild("right_pants").visible=m.rightPants.visible;
    }
    private HumanJoints() { }
}
