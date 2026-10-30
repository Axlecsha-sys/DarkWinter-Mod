// Builds the framed leather backpack and bedroll attached to the animated torso.
package com.dark.winter.client.equipment;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import static com.dark.winter.client.equipment.FacetedMesh.mesh;

/**
 * Cinematic Northern Wanderer: a framed leather pack, stitched flap and a strapped bedroll.
 * All points use the existing torso coordinate system. The bag remains rigid; only the harness
 * is fitted to the garment. Its broad padded panels and softened corners follow the approved reference.
 */
final class NorthernWandererPack {
    private static final Identifier LEATHER=texture("clothing_leather"), CANVAS=texture("clothing_canvas"),
            WOOL=texture("clothing_wool"), RUBBER=texture("clothing_rubber");
    private static final int BODY=0xFF89694F, FLAP=0xFFA37D5C, PANEL=0xFF947257,
            SIDE=0xFF75553E, STRAP=0xFFA17A57, EDGE=0xFFB58D6B,
            PADDING=0xFF35332E, METAL=0xFFA09683, SEAM=0xFFAF977B,
            ROLL=0xFF979186, ROLL_END=0xFFAAA296, ROLL_SEAM=0xFF5D594F;

    static List<BackpackLayer.Piece> create(float coat,boolean cinematic) { return create(coat,cinematic,0); }

    static List<BackpackLayer.Piece> create(float coat,boolean cinematic,int design) {
        return create(coat,cinematic,design,false);
    }

    static List<BackpackLayer.Piece> create(float coat,boolean cinematic,int design,boolean expedition) {
        List<BackpackLayer.Piece> out=new ArrayList<>();
        float front=design>0?2.15f+coat:cinematic?3.35f:2.15f+coat, back=front+3.35f;
        add(out,beveled(0,3.25f,(front+back)*.5f,7.2f,8.25f,3.35f,.62f),BODY);
        // Broad reinforced side faces and the lower leather base keep the pack visibly structured.
        add(out,beveled(0,6.98f,(front+back)*.5f,7.30f,.65f,3.43f,.24f),SIDE);
        add(out,padded(0,.38f,back+.20f,6.88f,2.65f,.88f,.48f),FLAP);
        add(out,padded(0,4.72f,back+.21f,6.48f,4.48f,.98f,.52f),PANEL);
        add(out,beveled(0,2.72f,back+.40f,5.88f,.30f,.16f,.065f),SIDE);
        add(out,beveled(0,6.73f,back+.38f,5.88f,.22f,.16f,.065f),SIDE);
        // Deliberate seams, not noise: long edge binding and small visible stitches on the flap.
        for(float side:new float[]{-1,1}) {
            add(out,beveled(side*3.27f,3.48f,back+.12f,.17f,6.52f,.15f,.055f),EDGE);
            add(out,beveled(side*2.98f,4.7f,back+.52f,.09f,3.25f,.07f,.025f),SEAM);
            add(out,padded(side*3.69f,4.25f,front+1.52f,1.16f,4.25f,2.3f,.28f),SIDE);
            add(out,beveled(side*3.75f,2.41f,front+1.52f,1.24f,.74f,2.42f,.24f),FLAP);
            add(out,beveled(side*4.29f,4.32f,front+1.52f,.12f,2.45f,.60f,.045f),STRAP);
        }
        for(int i=0;i<8;i++) {
            float x=-2.66f+i*.76f;
            add(out,beveled(x,1.39f,back+.49f,.22f,.07f,.045f,.015f),SEAM);
        }
        for(float side:new float[]{-1,1}) {
            float x=side*2.11f;
            // These are layered leather straps with visible strap ends, holes and open metal buckles.
            add(out,BackpackLayer.strapPath(new float[][]{
                    {x,-.86f,front+1.0f},{x,-1.0f,front+2.2f},{x,-.68f,back+.43f},
                    {x,1.0f,back+.76f},{x,3.7f,back+.85f},{x,6.9f,back+.57f}},.77f,.19f),STRAP);
            add(out,beveled(x,2.38f,back+.79f,.60f,1.48f,.19f,.075f),EDGE);
            buckle(out,x,1.95f,back+.94f,1.02f,.90f,false);
            buckle(out,x,5.55f,back+.99f,.95f,.83f,false);
            for(int hole=0;hole<3;hole++)
                add(out,beveled(x,2.68f+hole*.25f,back+.905f,.12f,.085f,.025f,.008f),PADDING);
            for(float y:new float[]{.66f,4.55f})
                add(out,beveled(x,y,back+(y<1?.88f:.97f),.18f,.18f,.055f,.065f),METAL);
            // Front harness lies directly on the selected garment and crosses over the shoulder.
            harness(out,side,coat,cinematic,design,expedition);
        }
        add(out,BackpackLayer.strapPath(new float[][]{{-.93f,-.65f,front+1.25f},
                {-.78f,-1.53f,front+1.24f},{0,-1.70f,front+1.24f},
                {.78f,-1.53f,front+1.24f},{.93f,-.65f,front+1.25f}},.44f,.23f),STRAP);
        // A genuinely horizontal cylinder with lightly chamfered ends, not a rounded rectangular pouch.
        float rollY=8.70f,rollZ=front+1.95f;
        add(out,cylinderX(-4.05f,4.05f,rollY,rollZ,1.30f,false),ROLL);
        for(float side:new float[]{-1,1}) {
            add(out,endDisc(side*4.065f,rollY,rollZ,1.17f,side),ROLL_END);
            add(out,spiral(side*4.077f,rollY,rollZ,side),ROLL_SEAM);
            float x=side*2.12f;
            add(out,beltX(x-.37f,x+.37f,rollY,rollZ,1.335f,.115f),STRAP);
            buckle(out,x,rollY-.02f,rollZ+1.46f,.97f,.80f,false);
            add(out,BackpackLayer.strapPath(new float[][]{
                    {x,6.65f,back+.72f},{x,7.65f,back+.65f},
                    {x,8.52f,rollZ+1.42f}},.69f,.18f),EDGE);
        }
        return List.copyOf(out);
    }

    static List<BackpackLayer.Piece> fitted(List<BackpackLayer.Piece> parts,float coat) {
        return parts.stream().map(p->new BackpackLayer.Piece(ThirdPersonContours.backpack(
                p.geometry(),p.harness()?0:coat,p.harness()),p.color(),p.harness())).toList();
    }

    /** Matches the visible item exactly; garment fitting is a worn-render concern. */
    static List<BackpackLayer.Piece> itemParts() {return create(1,true);}

    static Identifier texture(int color) {
        if(color==ROLL||color==ROLL_END||color==ROLL_SEAM)return WOOL;
        if(color==PADDING||color==METAL)return RUBBER;
        if(color==BODY||color==SIDE)return CANVAS;
        return LEATHER;
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/"+name+".png");
    }

    private static float shell(float coat,boolean cinematic,int design,boolean expedition,float x,float y,boolean back) {
        if(expedition)return BackpackLayer.expeditionHarnessShell(x,y,back);
        if(design>0)return BackpackLayer.cinematicShell(design,x,y,back);
        return cinematic?(back?NorthernWandererClothing.shellBack(x,y):NorthernWandererClothing.shellFront(x,y))
                :(back?ClothingMeshes.shellBack(coat,x,y):ClothingMeshes.shellFront(coat,x,y));
    }

    private static void harness(List<BackpackLayer.Piece> out,float side,float coat,boolean cinematic,int design,boolean expedition) {
        float x=side*(cinematic?2.75f:2.5f);
        float shoulderY=cinematic?-1.35f:-1.48f;
        float[][] path={
                {x,7.55f,shell(coat,cinematic,design,expedition,x,7.55f,false)-.14f},
                {x,4.5f,shell(coat,cinematic,design,expedition,x,4.5f,false)-.16f},
                {x,.5f,shell(coat,cinematic,design,expedition,x,.5f,false)-.15f},
                {x,shoulderY,shell(coat,cinematic,design,expedition,x,shoulderY,false)-.17f},
                {x,shoulderY-.08f,shell(coat,cinematic,design,expedition,x,shoulderY,true)+.15f},
                {x,1.1f,shell(coat,cinematic,design,expedition,x,1.1f,true)+.17f},
                {x,5.8f,shell(coat,cinematic,design,expedition,x,5.8f,true)+.16f}};
        out.add(new BackpackLayer.Piece(BackpackLayer.strapPath(path,.96f,.24f),PADDING,true));
        out.add(new BackpackLayer.Piece(BackpackLayer.strapPath(path,.76f,.33f),EDGE,true));
        float front=shell(coat,cinematic,design,expedition,x,2.5f,false)-.37f;
        buckle(out,x,2.5f,front,1.02f,.98f,true);
        float tab=shell(coat,cinematic,design,expedition,x,3.5f,false)-.36f;
        out.add(new BackpackLayer.Piece(beveled(x,3.5f,tab,.62f,1.02f,.18f,.065f),STRAP,true));
    }

    private static void buckle(List<BackpackLayer.Piece> out,float x,float y,float z,float w,float h,boolean harness) {
        float edge=.13f;
        for(float side:new float[]{-1,1}) {
            out.add(new BackpackLayer.Piece(beveled(x+side*(w-edge)*.5f,y,z,edge,h,.13f,.03f),METAL,harness));
            out.add(new BackpackLayer.Piece(beveled(x,y+side*(h-edge)*.5f,z,w-edge,edge,.13f,.03f),METAL,harness));
        }
        out.add(new BackpackLayer.Piece(beveled(x,y+.02f,z-.01f,.10f,h*.66f,.17f,.025f),METAL,harness));
    }

    private static void add(List<BackpackLayer.Piece> out,ModelPart part,int color) {
        out.add(new BackpackLayer.Piece(part,color));
    }

    /** A filled sewn panel: broad central plane, sloped shoulders and tucked seam corners. */
    private static ModelPart padded(float x,float y,float z,float w,float h,float d,float corner) {
        FacetedMesh result=mesh();
        // Five depth rings soften a panel without adding a round backpack silhouette.
        float[][] profile={{-.50f,.91f,.89f},{-.22f,1,1},{.06f,1,1},
                {.36f,.955f,.94f},{.50f,.88f,.81f}};
        float[][][] rings=new float[profile.length][][];
        for(int row=0;row<profile.length;row++) {
            float[] p=profile[row];
            rings[row]=outline(x,y,z+p[0]*d,w*p[1],h*p[2],Math.min(corner,Math.min(w*p[1],h*p[2])*.26f));
        }
        for(int row=0;row<rings.length-1;row++)for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            quad(result,rings[row][i],rings[row][j],rings[row+1][j],rings[row+1][i]);
        }
        for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            tri(result,rings[0][j],rings[0][i],new float[]{x,y,z-d*.5f});
            tri(result,rings[rings.length-1][i],rings[rings.length-1][j],new float[]{x,y,z+d*.5f});
        }
        return result.bake();
    }

    /** Eight straight outline edges and a shallow bevel preserve the pack's rectangular character. */
    private static ModelPart beveled(float x,float y,float z,float w,float h,float d,float bevel) {
        FacetedMesh result=mesh();
        float edge=Math.min(bevel,Math.min(w,h)*.25f),rim=Math.min(edge,d*.38f);
        float[][][] rings=new float[4][][];
        rings[0]=outline(x,y,z-d*.5f,w-rim*2,h-rim*2,Math.max(.005f,edge-rim));
        rings[1]=outline(x,y,z-d*.5f+rim,w,h,edge);
        rings[2]=outline(x,y,z+d*.5f-rim,w,h,edge);
        rings[3]=outline(x,y,z+d*.5f,w-rim*2,h-rim*2,Math.max(.005f,edge-rim));
        for(int row=0;row<3;row++)for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            quad(result,rings[row][i],rings[row][j],rings[row+1][j],rings[row+1][i]);
        }
        for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            tri(result,rings[0][j],rings[0][i],new float[]{x,y,z-d*.5f});
            tri(result,rings[3][i],rings[3][j],new float[]{x,y,z+d*.5f});
        }
        return result.bake();
    }

    private static float[][] outline(float x,float y,float z,float w,float h,float b) {
        float l=x-w*.5f,r=x+w*.5f,t=y-h*.5f,bottom=y+h*.5f;
        return new float[][]{{l+b,t,z},{r-b,t,z},{r,t+b,z},{r,bottom-b,z},
                {r-b,bottom,z},{l+b,bottom,z},{l,bottom-b,z},{l,t+b,z}};
    }

    private static ModelPart cylinderX(float x0,float x1,float cy,float cz,float radius,boolean open) {
        FacetedMesh result=mesh();
        float[] xs={x0,x0+.17f,x1-.17f,x1};
        float[] radii={radius*.88f,radius,radius,radius*.88f};
        for(int row=0;row<3;row++)for(int i=0;i<16;i++) {
            float a=(float)(i*Math.PI/8),b=(float)((i+1)*Math.PI/8);
            quad(result,radial(xs[row],cy,cz,radii[row],b),radial(xs[row+1],cy,cz,radii[row+1],b),
                    radial(xs[row+1],cy,cz,radii[row+1],a),radial(xs[row],cy,cz,radii[row],a));
        }
        if(!open)for(int i=0;i<16;i++) {
            float a=(float)(i*Math.PI/8),b=(float)((i+1)*Math.PI/8);
            tri(result,radial(x0,cy,cz,radii[0],b),radial(x0,cy,cz,radii[0],a),new float[]{x0,cy,cz});
            tri(result,radial(x1,cy,cz,radii[3],a),radial(x1,cy,cz,radii[3],b),new float[]{x1,cy,cz});
        }
        return result.bake();
    }

    private static ModelPart beltX(float x0,float x1,float cy,float cz,float radius,float thickness) {
        FacetedMesh result=mesh();
        for(int i=0;i<16;i++) {
            float a=(float)(i*Math.PI/8),b=(float)((i+1)*Math.PI/8);
            quad(result,radial(x0,cy,cz,radius+thickness,b),radial(x1,cy,cz,radius+thickness,b),
                    radial(x1,cy,cz,radius+thickness,a),radial(x0,cy,cz,radius+thickness,a));
            for(int end=0;end<2;end++) {
                float x=end==0?x0:x1;
                var p=radial(x,cy,cz,radius,a);var q=radial(x,cy,cz,radius,b);
                var r=radial(x,cy,cz,radius+thickness,b);var s=radial(x,cy,cz,radius+thickness,a);
                if(end==0)quad(result,p,q,r,s);else quad(result,s,r,q,p);
            }
        }
        return result.bake();
    }

    private static ModelPart endDisc(float x,float cy,float cz,float radius,float side) {
        FacetedMesh result=mesh();
        for(int i=0;i<16;i++) {
            var a=radial(x,cy,cz,radius,(float)(i*Math.PI/8));
            var b=radial(x,cy,cz,radius,(float)((i+1)*Math.PI/8));
            if(side>0)tri(result,a,b,new float[]{x,cy,cz});else tri(result,b,a,new float[]{x,cy,cz});
        }
        return result.bake();
    }

    private static ModelPart spiral(float x,float cy,float cz,float side) {
        FacetedMesh result=mesh();
        for(int i=0;i<36;i++) {
            float t0=i/36f,t1=(i+1)/36f;
            float a=(float)(t0*Math.PI*3.6),b=(float)(t1*Math.PI*3.6);
            float r0=.15f+t0*.97f,r1=.15f+t1*.97f;
            var p=radial(x,cy,cz,r0-.035f,a);var q=radial(x,cy,cz,r1-.035f,b);
            var r=radial(x,cy,cz,r1+.035f,b);var s=radial(x,cy,cz,r0+.035f,a);
            if(side>0)quad(result,s,r,q,p);else quad(result,p,q,r,s);
        }
        return result.bake();
    }

    private static float[] radial(float x,float y,float z,float r,float angle) {
        return new float[]{x,y+(float)Math.cos(angle)*r,z+(float)Math.sin(angle)*r};
    }
    private static ModelPart.Vertex vertex(float[] p,float u,float v) {
        return new ModelPart.Vertex(p[0],p[1],p[2],u,v);
    }
    private static void tri(FacetedMesh target,float[] a,float[] b,float[] c) {
        var va=vertex(a,0,0);var vb=vertex(b,1,0);var vc=vertex(c,.5f,1);
        if(new Vector3f(b[0]-a[0],b[1]-a[1],b[2]-a[2]).cross(c[0]-a[0],c[1]-a[1],c[2]-a[2]).lengthSquared()>1e-10f)
            target.face(va,vb,vc,vc);
    }
    private static void quad(FacetedMesh target,float[] a,float[] b,float[] c,float[] d) {
        target.face(vertex(a,0,0),vertex(b,1,0),vertex(c,1,1),vertex(d,0,1));
    }
    private NorthernWandererPack() { }
}
