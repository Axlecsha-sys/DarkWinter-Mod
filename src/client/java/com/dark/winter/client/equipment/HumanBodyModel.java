// Compact northern character masses, using the existing animation skeleton and skin islands.
package com.dark.winter.client.equipment;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/** Compact northern character masses, using the existing animation skeleton and skin islands. */
public final class HumanBodyModel {
    public static final float HIP_Y = 10.2f;
    public static final float LEG_LENGTH = 13.8f;
    public static final float ARM_CENTER = 0;

    public static ModelPart createRoot(boolean slim) {
        var parts = new LinkedHashMap<String, ModelPart>();
        parts.put("darkwinter_human",mesh().bake());
        // The protagonist has a permanent sculpted haircut and beard. The stock hat
        // overlay stays empty: the wearable hat is a separate equipment layer.
        ModelPart hat = mesh().bake();
        parts.put("head", mesh().skinHead().bake(Map.of(
                "hat", hat, "hair", hair(), "beard", beard())));
        // Vanilla second-layer cubes are transparent in the fixed skin atlas.
        // Keep their named bones for compatibility without submitting invisible faces.
        ModelPart jacket = mesh().bake();
        ModelPart neck=mesh().skinPatchLoft(0,0,20,23.5f,19.5f,24.5f,20.5f,
                section(-1.95f,2.55f,2.5f,0,.02f,2.6f),
                section(-1.5f,2.75f,2.7f,0,0,2.6f),
                section(-.75f,3.25f,2.95f,0,0,2.7f)).bake();
        parts.put("body",torso(16,16,0).bake(Map.of("jacket",jacket,"neck",neck,"underwear",underwear())));
        float aw = slim ? 2.85f : 3.0f;
        for (boolean left : new boolean[]{false,true}) {
            String side = left ? "left" : "right";
            int armU = left ? 32 : 40, armV = left ? 48 : 16;
            var armSurface=ArticulatedMesh.split(arm(armU,armV,left,slim,aw,0).bake(),HumanJoints.ELBOW_Y);
            var forearm=armSurface.lower().bake(Map.of(side+"_sleeve",mesh().bake()));
            pose(forearm,0,HumanJoints.ELBOW_Y,0);
            ModelPart arm=armSurface.upper().bake(Map.of(side+"_sleeve",mesh().bake(),"forearm",forearm));
            pose(arm,(left?1:-1)*4.75f,.1f,0);
            parts.put(side+"_arm",arm);
            var legSurface=ArticulatedMesh.split(leg(left?16:0,left?48:16,0).bake(),HumanJoints.KNEE_Y);
            var lowerLeg=ArticulatedMesh.split(legSurface.lower().bake(),HumanJoints.ANKLE_Y-HumanJoints.KNEE_Y);
            var foot=lowerLeg.lower().bake(Map.of(side+"_pants",mesh().bake()));
            pose(foot,0,HumanJoints.ANKLE_Y-HumanJoints.KNEE_Y,0);
            var shin=lowerLeg.upper().bake(Map.of(side+"_pants",mesh().bake(),"foot",foot));
            pose(shin,0,HumanJoints.KNEE_Y,0);
            ModelPart leg=legSurface.upper().bake(Map.of(side+"_pants",mesh().bake(),"shin",shin,
                    "underwear",underwearLeg()));
            pose(leg,(left?1:-1)*1.8f,HIP_Y,0);
            parts.put(side+"_leg",leg);
        }
        return new ModelPart(java.util.List.of(), parts);
    }

    /** Several swept locks share the scalp; the silhouette is not a cubical helmet. */
    private static ModelPart hair() {
        FacetedMesh hair=mesh().skinPatchLoft(0,0,20,40,1,56,8,
                section(-6.96f,.85f,1.0f,.15f,.12f,2.5f),
                section(-6.78f,3.65f,3.9f,.12f,.06f,2.6f),
                section(-6.3f,4.72f,4.95f,.05f,0,3.0f),
                section(-5.6f,4.77f,5.02f,0,.03f,3.1f),
                section(-4.78f,4.43f,4.68f,0,.13f,3.0f),
                section(-4.48f,4.08f,4.31f,0,.2f,2.9f));
        // Asymmetric 1990s field haircut: short sides, a few soft locks over the brow.
        for(int i=0;i<4;i++) {
            float x=-1.58f+i*1.04f;
            float drop=switch(i) {case 0->.16f;case 1->.37f;case 2->.09f;default->-.08f;};
            hair.skinPatchLoft(x,-2.33f,8,41+i*2.7f,2,44+i*2.7f,7,
                    section(-6.44f,.38f,.45f,0,.08f,2.4f),
                    section(-5.84f,.76f,.73f,-.1f,.02f,2.6f),
                    section(-5.25f+drop,.68f,.66f,-.16f,-.06f,2.5f),
                    section(-4.71f+drop,.18f,.22f,-.23f,-.11f,2.4f));
        }
        for(int side:new int[]{-1,1}) {
            hair.skinPatchLoft(side*2.10f,.02f,12,42,1,53,8,
                    section(-5.72f,.56f,2.65f,0,0,2.5f),
                    section(-4.72f,.60f,2.82f,0,0,2.7f),
                    section(-3.92f,.33f,1.94f,0,.23f,2.5f));
            hair.skinPatchLoft(side*1.82f,.76f,8,43,2,51,8,
                    section(-4.43f,.44f,.72f,0,0,2.5f),
                    section(-3.62f,.57f,.88f,0,.04f,2.6f),
                    section(-3.15f,.23f,.4f,0,-.04f,2.4f));
        }
        hair.skinPatchLoft(.28f,2.18f,8,43,2,51,8,
                section(-5.35f,.42f,.38f,0,0,2.5f),
                section(-4.58f,.84f,.7f,0,.06f,2.6f),
                section(-4.08f,.27f,.25f,.08f,.11f,2.4f));
        return hair.bake();
    }

    /** The dark jaw mass is sunk into the face at the back, leaving a rounded chin. */
    private static ModelPart beard() {
        FacetedMesh beard=mesh().skinPatchLoft(0,-1.43f,16,40,9,57,16,
                section(-3.16f,2.45f,1.15f,0,.02f,2.7f),
                section(-2.82f,3.15f,1.63f,0,-.05f,2.8f),
                section(-2.28f,3.58f,2.05f,0,-.12f,2.9f),
                section(-1.72f,3.36f,2.12f,0,-.15f,2.9f),
                section(-1.04f,2.42f,1.68f,0,-.22f,2.7f),
                section(-.72f,1.3f,.85f,0,-.26f,2.5f));
        for(int side:new int[]{-1,1}) {
            beard.skinPatchLoft(side*1.57f,-1.48f,8,41,9,53,15,
                    section(-3.72f,.3f,.4f,0,0,2.4f),
                    section(-3.23f,.93f,1.18f,0,-.04f,2.5f),
                    section(-2.49f,1.15f,1.26f,0,-.05f,2.7f),
                    section(-1.95f,.68f,.74f,0,.04f,2.4f));
            beard.skinPatchLoft(side*.65f,-2.26f,8,42,10,55,15,
                    section(-3.28f,.16f,.18f,0,0,2.4f),
                    section(-3.05f,1.01f,.50f,0,-.07f,2.5f),
                    section(-2.78f,.89f,.44f,0,-.08f,2.5f));
        }
        return beard.bake();
    }

    /** Simple boxer briefs are part of the base body, not a survival equipment slot. */
    private static ModelPart underwear() {
        FacetedMesh briefs=mesh().skinPatchLoft(0,.07f,16,57,17,63,31,
                section(8.69f,6.82f,3.9f,0,0,2.7f),
                section(9.05f,6.9f,4.06f,0,.02f,2.7f),
                section(10.24f,6.98f,4.25f,0,.06f,2.8f),
                section(11.16f,6.84f,4.34f,0,.08f,2.8f),
                section(11.58f,5.92f,3.54f,0,.07f,2.7f),
                section(11.83f,4.3f,2.1f,0,.04f,2.6f));
        briefs.skinPatchLoft(0,.07f,16,57,17,63,18,
                section(8.71f,6.86f,3.94f,0,0,2.7f),
                section(9.04f,6.94f,4.09f,0,.02f,2.7f));
        return briefs.bake();
    }

    private static ModelPart underwearLeg() {
        return mesh().skinPatchLoft(0,0,16,57,18,63,31,
                section(-1.1f,.48f,.7f,0,0,2.5f),
                section(-.68f,2.68f,3.1f,0,0,2.6f),
                section(0,3.52f,3.78f,0,0,2.7f),
                section(1.6f,3.63f,4.0f,0,-.04f,2.7f),
                section(2.45f,3.33f,3.68f,0,-.07f,2.6f)).bake();
    }

    private static FacetedMesh torso(int u,int v,float g) {
        FacetedMesh torso=mesh().skinLoft(u,v,8,12,4,20,
                section(-1.65f-g,3.05f+g*2,2.7f+g*2,0,0,2.7f),
                section(-1.25f,6.1f+g*2,3.05f+g*2,0,.025f,2.9f),
                section(-.7f,8.1f+g*2,3.4f+g*2,0,.025f,3.0f),
                section(.6f,8.0f+g*2,3.8f+g*2,0,-.015f,3.0f),
                section(2.4f,7.45f+g*2,3.85f+g*2,0,-.06f,3.0f),
                section(4.6f,6.85f+g*2,3.5f+g*2,0,-.04f,2.8f),
                section(7.0f,6.15f+g*2,3.2f+g*2,0,.025f,2.7f),
                section(8.75f,6.45f+g*2,3.5f+g*2,0,.035f,2.8f),
                section(HIP_Y+g,6.65f+g*2,3.6f+g*2,0,.035f,2.8f));
        // A shared pelvis receives both thighs. Its atlas patch comes from the upper leg,
        // so extending the seat does not stretch the original torso/shirt UV down the crotch.
        float legV=v==16?20:36;
        return torso.skinPatchLoft(0,0,20,4.5f,legV+.2f,7.5f,legV+1.8f,
                section(9.65f,6.5f+g*2,3.65f+g*2,0,.1f,2.8f),
                section(10.55f,6.6f+g*2,4.0f+g*2,0,.18f,2.9f),
                section(11.15f,6.5f+g*2,4.0f+g*2,0,.2f,2.9f),
                section(11.55f,5.8f+g*2,3.0f+g*2,0,.15f,2.8f),
                section(11.8f+g,4.2f+g*2,1.9f+g*2,0,.1f,2.7f));
    }

    private static FacetedMesh arm(int u,int v,boolean left,boolean slim,float width,float g) {
        float inward=left?-.6f:.6f;
        FacetedMesh arm=mesh().skinLoft(u,v,slim?3:4,12,4,16,
                section(-1.3f-g,.9f+g*2,1.1f+g*2,inward*2,0,2.6f),
                section(-.95f,2.65f+g*2,2.75f+g*2,inward*1.15f,0,2.7f),
                section(-.25f,width+g*2,3.0f+g*2,inward*.6f,0,2.8f),
                section(1.25f,width*.98f+g*2,3.12f+g*2,inward*.25f,-.025f,2.8f),
                section(3.6f,width*.88f+g*2,2.75f+g*2,0,.025f,2.7f),
                section(5.45f,2.22f+g*2,2.3f+g*2,0,.08f,2.7f),
                section(6.7f,2.58f+g*2,2.72f+g*2,0,-.015f,2.9f),
                section(8.3f,2.35f+g*2,2.45f+g*2,0,-.04f,2.8f),
                section(9.75f,1.85f+g*2,1.98f+g*2,0,-.05f,2.7f),
                section(10.25f,2.1f+g*2,2.35f+g*2,0,-.08f,3.0f),
                section(11.1f,2.65f+g*2,2.55f+g*2,0,-.13f,3.5f),
                section(11.9f,2.4f+g*2,2.35f+g*2,0,-.16f,3.2f),
                section(12.15f+g,1.9f+g*2,1.9f+g*2,0,-.12f,2.8f));
        float handU=u+4+(slim?3:4)*.35f;
        return arm.skinPatchLoft(left?-1.1f:1.1f,-.65f,12,handU,v+14.2f,handU+1.0f,v+15.6f,
                section(10.25f,.6f+g*2,.9f+g*2,0,0,2.5f),
                section(10.65f,1.0f+g*2,1.45f+g*2,0,-.15f,2.7f),
                section(11.35f,.9f+g*2,1.3f+g*2,0,-.2f,2.7f),
                section(11.65f,.55f+g*2,.8f+g*2,0,-.16f,2.5f));
    }

    private static FacetedMesh leg(int u,int v,float g) {
        return mesh().skinLoft(u,v,4,12,4,16,
                section(-1.35f,.6f+g*2,.8f+g*2,0,0,2.6f),
                section(-.85f,2.4f+g*2,2.8f+g*2,0,0,2.7f),
                section(0,3.2f+g*2,3.45f+g*2,0,0,2.8f),
                section(1.7f,3.35f+g*2,3.7f+g*2,0,-.04f,2.8f),
                section(3.8f,2.95f+g*2,3.3f+g*2,0,-.08f,2.7f),
                section(5.9f,2.35f+g*2,2.7f+g*2,0,-.16f,2.8f),
                section(6.65f,2.45f+g*2,2.75f+g*2,0,-.1f,2.7f),
                section(8.25f,2.9f+g*2,3.1f+g*2,0,.18f,2.8f),
                section(10.7f,2.15f+g*2,2.45f+g*2,0,.08f,2.7f),
                section(11.7f,2.35f+g*2,2.85f+g*2,0,-.08f,2.3f),
                section(12.5f,2.75f+g*2,4.1f+g*2,0,-.55f,2.45f),
                section(13.2f,2.95f+g*2,4.45f+g*2,0,-.68f,2.5f),
                section(LEG_LENGTH,2.87f+g*2,4.35f+g*2,0,-.67f,2.5f));
    }

    private static void pose(ModelPart part,float x,float y,float z) {
        part.setInitialPose(PartPose.offset(x,y,z));
        part.resetPose();
    }
    private HumanBodyModel() { }
}
