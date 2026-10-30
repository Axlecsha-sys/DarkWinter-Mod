// A separate authored head, tucked bob and empty beard on the unchanged fitted winter-clothing rig.
package com.dark.winter.client.equipment;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/** A separate authored head, tucked bob and empty beard on the unchanged fitted winter-clothing rig. */
public final class FemaleHumanModel {
    private FemaleHumanModel() {}
    public static ModelPart createRoot(boolean slim) {
        ModelPart legacy=ThirdPersonBodyModel.createRoot(slim);
        Map<String,ModelPart> parts=new LinkedHashMap<>();
        for(String name:new String[]{"darkwinter_human","darkwinter_third_person","body","left_arm","right_arm","left_leg","right_leg"})
            parts.put(name,legacy.getChild(name));
        ModelPart raw=head(Map.of("hat",mesh().bake(),"hair",hair(),"beard",mesh().bake()));
        parts.put("head",ThirdPersonBodyModel.fittedProfileHead(raw));
        parts.put("darkwinter_female",mesh().bake());
        return new ModelPart(java.util.List.of(),parts);
    }
    private static ModelPart head(Map<String,ModelPart> children) {
        // Same authored atlas islands as ThirdPersonHead: uniform skin at (23.55..24.45,19.55..20.45)/64.
        // The jaw/cheek/chin are real surfaces; no male beard geometry or vanilla Alex UV layout is retained.
        FacetedMesh shape=mesh().skinPatchLoft(0,0,20,23.55f,19.55f,24.45f,20.45f,
                section(-6.43f,2.05f,2.18f,0,.07f,3.2f),
                section(-6.25f,3.62f,3.70f,0,.04f,4.0f),
                section(-5.82f,4.38f,4.28f,0,.02f,4.5f),
                section(-5.10f,4.55f,4.38f,0,0,4.8f),
                section(-3.42f,4.55f,4.30f,0,0,4.8f),
                section(-2.62f,4.20f,4.08f,0,-.02f,4.1f),
                section(-2.02f,3.50f,3.55f,0,-.05f,3.5f),
                section(-1.54f,2.58f,2.85f,0,-.04f,3.1f));
        for(float side:new float[]{-1,1}) {
            float cx=side*.98f;
            patch(shape,cx,-3.46f,-2.172f,.68f,.78f,79.5f/512,94.5f/512);
            patch(shape,cx-side*.10f,-3.46f,-2.181f,.29f,.76f,80.5f/512,94.5f/512);
        }
        return shape.bake(children);
    }
    private static ModelPart hair() {
        // Existing dark-hair island (41..55,1..7)/64; compact side/back ends stay above the winter collar.
        FacetedMesh shape=mesh().skinPatchLoft(0,.08f,20,41,1,55,7,
                section(-6.90f,1.42f,1.62f,0,.03f,3.0f),
                section(-6.62f,3.60f,3.74f,0,.03f,3.8f),
                section(-6.13f,4.67f,4.62f,0,.04f,4.1f),
                section(-5.10f,4.79f,4.73f,0,.08f,4.2f),
                section(-4.50f,4.70f,4.61f,0,.12f,4.0f),
                section(-4.19f,4.52f,4.35f,0,.17f,3.7f));
        // An asymmetric swept fringe, separate from the broad short male haircut.
        for(int i=0;i<5;i++) {
            float x=-1.65f+i*.70f;
            shape.skinPatchLoft(x,-2.10f,12,42+i*1.8f,1,43+i*1.8f,7,
                    section(-5.35f,.87f,.48f,0,.03f,3.3f),
                    section(-4.52f,.80f,.47f,-.12f,0,3.6f),
                    section(-3.90f+i*.11f,.52f,.32f,-.20f,-.02f,3.2f));
        }
        for(float side:new float[]{-1,1}) {
            shape.skinPatchLoft(side*2.19f,.31f,12,42,1,54,7,
                    section(-5.10f,.54f,3.81f,0,0,3.6f),
                    section(-3.75f,.55f,3.90f,0,.10f,3.8f),
                    section(-2.55f,.49f,3.40f,-side*.04f,.21f,3.5f),
                    section(-1.78f,.27f,2.22f,-side*.09f,.30f,3.1f));
        }
        shape.skinPatchLoft(0,2.14f,16,42,1,54,7,
                section(-5.05f,4.35f,.48f,0,0,3.8f),
                section(-3.40f,4.29f,.58f,0,.07f,3.9f),
                section(-2.15f,3.70f,.47f,0,.13f,3.6f),
                section(-1.72f,3.06f,.23f,0,.12f,3.1f));
        return shape.bake();
    }
    private static void patch(FacetedMesh mesh,float cx,float cy,float z,float width,float height,float u,float v) {
        mesh.face(new ModelPart.Vertex(cx-width/2,cy-height/2,z,u,v),
                new ModelPart.Vertex(cx-width/2,cy+height/2,z,u,v),
                new ModelPart.Vertex(cx+width/2,cy+height/2,z,u,v),
                new ModelPart.Vertex(cx+width/2,cy-height/2,z,u,v));
    }
}
