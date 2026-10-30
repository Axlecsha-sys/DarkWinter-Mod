// Sculpting in the original skeleton's local coordinates, before the compact TP proportions.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

/**
 * Sculpting in the original skeleton's local coordinates, before the compact TP proportions.
 * The same continuous map fits skin, sewn shells and their details. UVs and shared FP meshes stay intact.
 */
final class ThirdPersonContours {
    // y, lateral fullness, front depth, back depth. These are shaped masses, not extra surface noise.
    private static final float[][] TRUNK={
        {-2,1,1,1}, {-1.25f,1.01f,1.04f,1.05f}, {.6f,1.035f,1.10f,1.12f},
        {2.4f,1.055f,1.15f,1.19f}, {4.6f,1.025f,1.09f,1.13f},
        {7,1.015f,1,1.015f}, {8.75f,1.055f,1.035f,1.075f},
        {10.55f,1.10f,1.07f,1.14f}, {11.8f,1.075f,1.06f,1.11f},
        {13,1.04f,1.025f,1.05f}
    };
    private static final float[][] ARM={
        {-1.6f,.94f,.90f,0}, {-.85f,.94f,.88f,0}, {-.25f,.97f,.92f,0},
        {1.25f,1.035f,1.04f,.035f}, {3.6f,1.025f,1.045f,.045f},
        {5.45f,1.035f,1.07f,.035f}, {6.7f,1.07f,1.09f,-.075f},
        {8.3f,1.045f,1.025f,-.04f}, {9.75f,.99f,.99f,0},
        {10.25f,1,1,0}, {13,1,1,0}
    };
    private static final float[][] LEG={
        {-1.35f,1.035f,1.035f,0}, {0,1.10f,1.10f,0},
        {1.7f,1.14f,1.13f,.025f}, {3.8f,1.14f,1.12f,0},
        {5.9f,1.075f,1.105f,-.11f}, {6.65f,1.07f,1.09f,-.09f},
        {8.25f,1.17f,1.15f,.09f}, {10.7f,1.055f,1.055f,.02f},
        {11.45f,1,1,0}, {14,1,1,0}
    };
    private static final float[][] SKULL={
        {-7.2f,.98f}, {-6.5f,1.025f}, {-5.7f,.98f}, {-4.85f,.96f},
        {-3.65f,.96f}, {-2.75f,1.01f}, {-1.95f,1.09f}, {-1.55f,1.06f}, {0,1,1}
    };

    static ModelPart.Vertex vertex(ClothingMeshes.Bone bone,ModelPart.Vertex p) {
        Vector3f point=point(bone,p.x(),p.y(),p.z());
        return new ModelPart.Vertex(point.x,point.y,point.z,p.u(),p.v());
    }

    static Vector3f point(ClothingMeshes.Bone bone,float x,float y,float z) {
        return switch(bone) {
            case HEAD -> {
                float crown=1-smooth(-6.96f,-4.1f,y);
                // A less bulbous rear cranium, broad jaw plane and a slightly higher neck seat.
                float depth=z>0?.84f:.97f;
                yield new Vector3f(x*value(SKULL,y,1),y+.16f*crown,z*depth);
            }
            case BODY -> trunk(x,y,z);
            case LEFT_ARM,RIGHT_ARM,LEFT_FOREARM,RIGHT_FOREARM -> {
                float offset=bone==ClothingMeshes.Bone.LEFT_FOREARM||bone==ClothingMeshes.Bone.RIGHT_FOREARM?HumanJoints.ELBOW_Y:0;
                float h=y+offset;
                float side=bone==ClothingMeshes.Bone.LEFT_ARM||bone==ClothingMeshes.Bone.LEFT_FOREARM?1:-1;
                float cap=1-smooth(-.5f,2.2f,h);
                // The acromion slopes out from the chest; no detached rounded shoulder cap.
                float outer=smooth(-.4f,1.6f,side*x);
                yield new Vector3f(x*value(ARM,h,1)-side*.10f*cap,
                        y+.30f*cap*outer,z*value(ARM,h,2)+value(ARM,h,3));
            }
            case LEFT_LEG,RIGHT_LEG,LEFT_SHIN,RIGHT_SHIN,LEFT_FOOT,RIGHT_FOOT -> {
                float offset=switch(bone) {
                    case LEFT_SHIN,RIGHT_SHIN -> HumanJoints.KNEE_Y;
                    case LEFT_FOOT,RIGHT_FOOT -> HumanJoints.ANKLE_Y;
                    default -> 0;
                };
                float h=y+offset;
                yield new Vector3f(x*value(LEG,h,1),y,z*value(LEG,h,2)+value(LEG,h,3));
            }
        };
    }

    private static Vector3f trunk(float x,float y,float z) {
        float chest=smooth(-1.2f,.7f,y)*(1-smooth(3.5f,6.8f,y));
        float back=Math.max(0,z)/(Math.abs(z)+.35f);
        // Broad scapular planes add depth without engraved muscles or a high-poly anatomy treatment.
        float scapula=chest*back*(.10f+.10f*smooth(.3f,2.2f,Math.abs(x)));
        float traps=(1-smooth(-.45f,1.4f,y))*(-.22f+.40f*smooth(.8f,4.7f,Math.abs(x)));
        float depth=value(TRUNK,y,z<0?2:3);
        return new Vector3f(x*value(TRUNK,y,1),y+traps,z*depth+scapula);
    }

    static ModelPart garment(ClothingMeshes.Piece piece) {
        FacetedMesh result=FacetedMesh.mesh();
        piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
            if(!path.isEmpty())throw new IllegalArgumentException("Garment shells must use their actual bone-local surfaces");
            for(var polygon:cube.polygons) {
                var v=polygon.vertices();var fitted=new ModelPart.Vertex[v.length];
                for(int i=0;i<v.length;i++)fitted[i]=vertex(piece.bone(),v[i]);
                face(result,fitted);
            }
        });
        return result.bake();
    }

    static ModelPart backpack(ModelPart source,float coat,boolean harness) {
        FacetedMesh result=FacetedMesh.mesh();
        // Preserve the bag design as a rigid object. Only its harness follows the reshaped garment.
        Vector3f anchor=trunk(0,4,2.3f+coat).sub(0,4,2.3f+coat);
        source.visit(new PoseStack(),(pose,path,index,cube)->{
            for(var polygon:cube.polygons) {
                var v=polygon.vertices();var fitted=new ModelPart.Vertex[v.length];
                for(int i=0;i<v.length;i++) {
                    var p=v[i];
                    Vector3f q=harness?trunk(p.x(),p.y(),p.z()+coat).sub(0,0,coat)
                            :new Vector3f(p.x(),p.y(),p.z()).add(anchor);
                    fitted[i]=new ModelPart.Vertex(q.x,q.y,q.z,p.u(),p.v());
                }
                face(result,fitted);
            }
        });
        return result.bake();
    }

    /** Triangulate only a quad which is no longer planar after sculpting; keep original UV corners. */
    static void face(FacetedMesh mesh,ModelPart.Vertex[] v) {
        var a=v[0];var b=v[1];var c=v[2];var d=v[3];
        Vector3f normal=new Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z())
                .cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z());
        float separation=Math.abs(normal.dot(d.x()-a.x(),d.y()-a.y(),d.z()-a.z()));
        if(separation<normal.length()*.00005f){mesh.face(v);return;}
        mesh.face(a,b,c,c);
        if(new Vector3f(c.x()-a.x(),c.y()-a.y(),c.z()-a.z())
                .cross(d.x()-a.x(),d.y()-a.y(),d.z()-a.z()).lengthSquared()>1e-10f)
            mesh.face(a,c,d,d);
    }

    private static float value(float[][] profile,float y,int column) {
        if(y<=profile[0][0])return profile[0][column];
        for(int i=1;i<profile.length;i++)if(y<=profile[i][0]) {
            float t=smooth(profile[i-1][0],profile[i][0],y);
            return profile[i-1][column]+(profile[i][column]-profile[i-1][column])*t;
        }
        return profile[profile.length-1][column];
    }
    private static float smooth(float start,float end,float x) {
        float t=Math.clamp((x-start)/(end-start),0,1);return t*t*(3-2*t);
    }
    private ThirdPersonContours() { }
}
