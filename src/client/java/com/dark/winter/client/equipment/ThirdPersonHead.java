// Authored third-person head: broad facial planes and connected, tucked hair locks.
package com.dark.winter.client.equipment;

import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/** Authored third-person head: broad facial planes and connected, tucked hair locks. */
final class ThirdPersonHead {
    static ModelPart head(Map<String,ModelPart> children) {
        // Sample the existing skin colour island, not the realistic face painting.
        FacetedMesh shape=mesh().skinPatchLoft(0,0,20,23.55f,19.55f,24.45f,20.45f,
                section(-6.50f,2.2f,2.25f,0,.08f,3.3f),
                section(-6.32f,3.8f,3.75f,0,.05f,4.0f),
                section(-5.85f,4.65f,4.35f,0,.02f,4.6f),
                section(-5.15f,4.80f,4.55f,0,0,5.0f),
                section(-3.30f,4.80f,4.55f,0,0,5.0f),
                section(-2.40f,4.58f,4.30f,0,-.04f,4.5f),
                section(-1.95f,4.12f,3.88f,0,-.07f,4.0f),
                section(-1.55f,3.20f,3.10f,0,-.06f,3.2f));
        // Simple vertical eyes are actual shallow surfaces; the atlas itself stays unchanged.
        for(float side:new float[]{-1,1}) {
            float cx=side*1.06f;
            patch(shape,cx,-3.39f,-2.282f,.67f,.88f,79.5f/512,94.5f/512);
            patch(shape,cx-side*.10f,-3.39f,-2.291f,.29f,.87f,80.5f/512,94.5f/512);
        }
        return shape.bake(children);
    }

    static ModelPart hair() {
        FacetedMesh shape=mesh().skinPatchLoft(0,0,20,41,1,55,7,
                section(-6.96f,1.50f,1.65f,.08f,.06f,3.2f),
                section(-6.72f,3.80f,3.85f,.04f,.04f,3.7f),
                section(-6.20f,4.88f,4.71f,0,.02f,4.0f),
                section(-5.25f,4.94f,4.79f,0,.02f,4.2f),
                section(-4.55f,4.84f,4.70f,0,.05f,4.1f),
                section(-4.31f,4.69f,4.53f,0,.06f,3.8f));
        float[] ends={-3.90f,-3.76f,-3.86f,-3.68f,-3.77f,-3.88f,-3.72f,-3.91f};
        for(int i=0;i<8;i++) {
            float x=-2.1f+i*.60f;
            shape.skinPatchLoft(x,-2.27f,12,41+i*1.5f,1,42+i*1.5f,7,
                    section(-5.22f,.65f,.44f,0,.025f,3.2f),
                    section(-4.55f,.63f,.48f,-.025f,0,3.6f),
                    section(ends[i]-.16f,.52f,.42f,-.065f,-.035f,3.1f),
                    section(ends[i],.30f,.22f,-.08f,.01f,2.8f));
        }
        // Continuous side/back fringe; ends tuck into the cap/scarf instead of flaring out.
        for(float side:new float[]{-1,1}) {
            shape.skinPatchLoft(side*2.30f,.25f,12,42,1,54,7,
                    section(-5.10f,.45f,3.72f,0,0,3.5f),
                    section(-4.28f,.43f,3.65f,0,0,3.5f),
                    section(-3.46f,.33f,3.03f,-side*.06f,.11f,3.3f),
                    section(-3.27f,.17f,2.34f,-side*.13f,.14f,3.0f));
        }
        shape.skinPatchLoft(0,2.22f,16,42,1,54,7,
                section(-5.10f,4.38f,.46f,0,0,3.5f),
                section(-3.72f,4.12f,.48f,0,.03f,3.4f),
                section(-3.30f,3.74f,.24f,0,-.02f,3.0f));
        return shape.bake();
    }

    private static void patch(FacetedMesh mesh,float cx,float cy,float z,float w,float h,float u,float v) {
        mesh.face(new ModelPart.Vertex(cx-w/2,cy-h/2,z,u,v),
                new ModelPart.Vertex(cx-w/2,cy+h/2,z,u,v),
                new ModelPart.Vertex(cx+w/2,cy+h/2,z,u,v),
                new ModelPart.Vertex(cx+w/2,cy-h/2,z,u,v));
    }
    private ThirdPersonHead() { }
}
