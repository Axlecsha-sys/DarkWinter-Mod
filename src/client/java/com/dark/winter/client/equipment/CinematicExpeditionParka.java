// Builds the expedition-parka mesh for third-person and inventory rendering.
package com.dark.winter.client.equipment;

import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;
import static com.dark.winter.client.equipment.NorthernWandererClothing.surface;

/**
 * Approved jacket sheet, design 01. Dedicated third-person / inventory shell.
 * The original wardrobe geometry remains the first-person source.
 * All coordinates precede the established third-person body fitting.
 */
final class CinematicExpeditionParka {
    private static final int CLOTH=0xFF8B8060, CLOTH_EDGE=0xFF978A6B,
            CLOTH_SEAM=0xFF7B7056, FLAP_CLOTH=0xFFA09474, POCKET_SEAM=0xFF635C49, LINING=0xFF5D5947,
            LEATHER=0xFF846045, LEATHER_EDGE=0xFF987356,
            FUR=0xFFF1D8AF, FUR_LIGHT=0xFFFBE5C5, FUR_SHADE=0xFFD8BD94,
            METAL=0xFFAAA499, ZIP=0xFF6D6B62, KNIT=0xFF33343A, SNOW=0xFFD8DAD6;
    private static final Identifier ATLAS=Identifier.fromNamespaceAndPath(
            "darkwinter","textures/entity/expedition_parka.png");
    private static final Identifier WOOL=Identifier.fromNamespaceAndPath(
            "darkwinter","textures/entity/clothing_wool.png");
    private enum Material { CLOTH, LEATHER, FUR, METAL, KNIT }
    private static final FacetedMesh.Section[] BODY={
            section(-2.00f,4.96f,4.52f,0,.08f,3.8f),
            section(-1.25f,7.54f,4.94f,0,.07f,4.0f),
            section(-.54f,9.79f,5.52f,0,.04f,4.1f),
            section(.82f,9.91f,5.68f,0,.02f,4.1f),
            section(2.75f,9.69f,5.67f,0,.02f,4.0f),
            section(4.70f,9.58f,5.78f,0,.04f,4.0f),
            section(6.65f,9.53f,5.91f,0,.06f,4.0f),
            section(8.55f,9.25f,5.89f,0,.06f,4.0f),
            section(10.65f,9.28f,5.86f,0,.07f,4.0f),
            section(11.16f,9.31f,5.88f,0,.07f,4.0f),
            section(11.55f,9.26f,5.83f,0,.07f,4.0f)};
    private static final List<Piece> PIECES=create();

    static List<Piece> pieces() { return PIECES; }
    static Identifier texture(int color) { return color==KNIT?WOOL:ATLAS; }
    static float shellFront(float x,float y) { return surface(BODY,x,y,false); }
    static float shellBack(float x,float y) { return surface(BODY,x,y,true); }

    private static List<Piece> create() {
        List<Piece> out=new ArrayList<>();
        put(out,Bone.BODY,CLOTH,SurfaceRole.MAIN,Material.CLOTH,
                mesh().softLoftOpen(0,0,20,BODY));
        // A folded fabric edge, open underneath, rather than a solid skirt plate.
        put(out,Bone.BODY,CLOTH_SEAM,SurfaceRole.DETAIL,Material.CLOTH,
                mesh().openTube(0,0,20,.09f,
                    section(11.19f,9.34f,5.92f,0,.07f,4),
                    section(11.55f,9.29f,5.87f,0,.07f,4)));
        sleeves(out);
        hood(out);
        closure(out);
        for(int sign:new int[]{-1,1}) {
            pocket(out,sign*2.63f,8.60f,2.77f,3.73f,.51f,false);
            // The leather continues over the shoulder; two buckles and a loose end
            // are separate construction parts, not an abstract painted band.
            panel(out,Bone.BODY,LEATHER,SurfaceRole.DETAIL,Material.LEATHER,BODY,
                    sign*2.72f,1.73f,.91f,4.59f,.20f,false);
            panel(out,Bone.BODY,LEATHER_EDGE,SurfaceRole.DETAIL,Material.LEATHER,BODY,
                    sign*2.72f,3.66f,.66f,.94f,.25f,false);
            buckle(out,BODY,sign*2.72f,1.06f,.80f,.79f,.35f,false);
            buckle(out,BODY,sign*2.72f,2.63f,.80f,.77f,.35f,false);
            stud(out,Bone.BODY,BODY,sign*2.72f,4.0f,.16f,.32f,false);
            strapOverShoulder(out,sign);
        }
        panel(out,Bone.BODY,LEATHER,SurfaceRole.DETAIL,Material.LEATHER,BODY,
                0,2.72f,7.71f,.86f,.16f,true);
        panel(out,Bone.BODY,LEATHER_EDGE,SurfaceRole.DETAIL,Material.LEATHER,BODY,
                0,3.12f,.65f,1.39f,.22f,true);
        buckle(out,BODY,0,3.68f,.68f,.67f,.33f,true);
        pocket(out,0,8.62f,6.03f,3.64f,.46f,true);
        // The back yoke is a seam in the cloth, not another rigid armour slab.
        ribbon(out,Bone.BODY,CLOTH_SEAM,Material.CLOTH,BODY,
                -4.02f,4.21f,4.02f,4.21f,.045f,.025f,true);
        snow(out);
        return compact(NorthernWandererClothing.articulate(out));
    }

    private static void sleeves(List<Piece> out) {
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;float side=left?1:-1;
            FacetedMesh.Section[] sleeve={
                section(-1.66f,.83f,1.05f,-side*1.18f,.04f,3.8f),
                section(-1.22f,2.83f,3.23f,-side*.78f,.03f,4.1f),
                section(-.62f,4.06f,4.42f,-side*.39f,.03f,4.2f),
                section(.34f,4.22f,4.61f,-side*.13f,.02f,4.1f),
                section(1.72f,4.22f,4.56f,-side*.04f,.02f,4.0f),
                section(2.40f,4.17f,4.47f,-side*.02f,.03f,3.95f),
                section(3.10f,4.12f,4.37f,0,.04f,3.9f),
                section(3.74f,4.05f,4.26f,0,.055f,3.9f),
                section(4.43f,3.98f,4.15f,0,.07f,3.9f),
                // Width is continuous across the joint. No lower-arm balloon.
                section(5.25f,3.94f,4.08f,0,.09f,3.9f),
                section(5.95f,3.92f,4.03f,0,.07f,3.9f),
                section(6.40f,3.90f,4.01f,0,.045f,3.9f),
                section(6.84f,3.87f,3.99f,0,.02f,3.9f),
                section(7.36f,3.81f,3.97f,0,.01f,3.9f),
                section(7.95f,3.75f,3.94f,0,0,3.9f),
                section(8.43f,3.68f,3.87f,0,-.005f,3.9f),
                section(8.90f,3.61f,3.81f,0,-.01f,3.9f),
                section(9.13f,3.57f,3.77f,0,-.01f,3.9f),
                section(9.35f,3.54f,3.73f,0,-.01f,3.9f),
                section(10.06f,3.43f,3.62f,0,-.01f,3.9f)};
            // Broad diagonal compression folds sculpt the surface itself. Each
            // fold has an explicit construction location, direction and falloff.
            FacetedMesh shaped=mesh();
            mesh().softLoft(0,0,16,sleeve).bake().visit(new PoseStack(),(pose,path,index,cube)->{
                for(var polygon:cube.polygons) {
                    var vs=polygon.vertices();var points=new ModelPart.Vertex[vs.length];
                    for(int i=0;i<vs.length;i++) {
                        var p=vs[i];float y=p.y(),x=p.x(),z=p.z();
                        float front=Math.clamp((-z-.4f)/1.8f,0,1);
                        float back=Math.clamp((z-.35f)/1.7f,0,1);
                        float outer=Math.clamp((side*x+.35f)/2.25f,0,1);
                        float delta=.10f*tent(y,3.10f+side*x*.42f,1.70f)*(front+.45f*outer)
                                +.080f*tent(y,6.58f-side*x*.44f,1.50f)*(front+.50f*outer+.22f*back)
                                +.075f*tent(y,8.85f+side*x*.30f,1.10f)*(front+back*.55f+outer*.4f);
                        float r=(float)Math.hypot(x,z);
                        float scale=r>.01f?1+delta/r:1;
                        float cuffBlend=Math.clamp((y-8.30f)/1.05f,0,1);
                        float sleeveWidth=.94f+.06f*cuffBlend;
                        points[i]=new ModelPart.Vertex(x*scale*sleeveWidth,y,z*scale,p.u(),p.v());
                    }
                    emit(shaped,points);
                }
            });
            put(out,bone,CLOTH,SurfaceRole.MAIN,Material.CLOTH,shaped);
            put(out,bone,CLOTH_SEAM,SurfaceRole.DETAIL,Material.CLOTH,
                    mesh().openTube(0,-.01f,16,.075f,
                        section(9.35f,3.61f,3.81f,0,0,3.9f),
                        section(10.04f,3.51f,3.72f,0,0,3.9f)));
            panel(out,bone,CLOTH_EDGE,SurfaceRole.DETAIL,Material.CLOTH,sleeve,
                    side*.39f,9.67f,2.45f,.55f,.14f,false);
            stud(out,bone,sleeve,side*1.18f,9.67f,.20f,.24f,false);
            put(out,bone,KNIT,SurfaceRole.DETAIL,Material.KNIT,
                    mesh().openTube(0,-.01f,16,.12f,
                        section(10.01f,3.25f,3.46f,0,0,3.8f),
                        section(10.63f,3.10f,3.31f,0,0,3.8f)));
        }
    }

    /**
     * A lowered hood is an open cloth bowl. Its outer shell and darker lining
     * meet at a U-shaped mouth, whose front tips rest beside the neck/chest.
     * Neither a closed torus nor a solid pillow fills the opening.
     */
    private static void hood(List<Piece> out) {
        int steps=32;FacetedMesh outside=mesh(),inside=mesh();
        for(int i=0;i<steps;i++)for(int row=0;row<4;row++) {
            float a=i/(float)steps,b=(i+1f)/steps,t0=row/4f,t1=(row+1f)/4;
            var p=new ModelPart.Vertex[]{hoodVertex(a,t0,false),hoodVertex(b,t0,false),
                    hoodVertex(b,t1,false),hoodVertex(a,t1,false)};
            orient(outside,p,hoodNormal((a+b)*.5f,false));
            var q=new ModelPart.Vertex[]{hoodVertex(a,t0,true),hoodVertex(b,t0,true),
                    hoodVertex(b,t1,true),hoodVertex(a,t1,true)};
            orient(inside,q,hoodNormal((a+b)*.5f,true));
        }
        put(out,Bone.BODY,CLOTH,SurfaceRole.HOOD,Material.CLOTH,outside);
        put(out,Bone.BODY,LINING,SurfaceRole.HOOD,Material.CLOTH,inside);
        // Close the fabric at the two front ends. The wide neck opening stays open.
        FacetedMesh ends=mesh();
        for(float u:new float[]{0,1})for(int row=0;row<4;row++) {
            float t=row/4f,nt=(row+1)/4f;
            orient(ends,new ModelPart.Vertex[]{hoodVertex(u,t,false),hoodVertex(u,nt,false),
                    hoodVertex(u,nt,true),hoodVertex(u,t,true)},new Vector3f(u==0?-1:1,0,-1));
        }
        put(out,Bone.BODY,CLOTH_EDGE,SurfaceRole.HOOD,Material.CLOTH,ends);
        furBorder(out,steps);
    }

    private static ModelPart.Vertex hoodVertex(float u,float t,boolean inside) {
        float[] c=hoodRim(u);Vector3f n=hoodNormal(u,false);
        float back=Math.max(0,(c[2]-.35f)/3.15f);
        float x,z,y;
        if(inside) {
            float depth=.32f+.51f*t;
            x=c[0]-n.x*depth;
            z=c[2]-n.z*depth;
            y=c[1]+.20f+1.70f*t;
        } else {
            float bulge=.35f*(float)Math.sin(Math.PI*t);
            x=c[0]*(1-.18f*t)+n.x*bulge;
            z=c[2]*(1-.18f*t)+n.z*(bulge+.13f*back*(float)Math.sin(Math.PI*t));
            y=c[1]+(2.0f+2.05f*back)*t;
        }
        return new ModelPart.Vertex(x,y,z,u,t);
    }

    private static float[] hoodRim(float u) {
        double angle=Math.toRadians(-64+308*u);
        float co=(float)Math.cos(angle),si=(float)Math.sin(angle);
        float nx=signedPow(co,.76f),nz=signedPow(si,.79f);
        float front=Math.max(0,-nz),back=Math.max(0,nz);
        return new float[]{nx*3.51f,
                -2.13f-.53f*back+2.42f*front*front,
                .32f+nz*(nz<0?3.81f:3.18f)};
    }

    private static Vector3f hoodNormal(float u,boolean inside) {
        double a=Math.toRadians(-64+308*u);
        return new Vector3f((float)Math.cos(a),0,(float)Math.sin(a)).mul(inside?-1:1);
    }

    private static void furBorder(List<Piece> out,int steps) {
        FacetedMesh base=mesh();
        for(int i=0;i<steps;i++)for(int k=0;k<8;k++) {
            var p=new ModelPart.Vertex[]{furVertex(i,steps,k),furVertex(i+1,steps,k),
                    furVertex(i+1,steps,k+1),furVertex(i,steps,k+1)};
            float[] c=hoodRim((i+.5f)/steps);
            Vector3f centre=new Vector3f(c[0],c[1],c[2]);
            Vector3f average=new Vector3f();
            for(var v:p)average.add(v.x(),v.y(),v.z());
            orient(base,p,average.mul(.25f).sub(centre));
        }
        // Close only the tips of the fur welt, not the hood's open mouth.
        for(int end:new int[]{0,steps})for(int k=0;k<8;k++) {
            var a=furVertex(end,steps,k);var b=furVertex(end,steps,k+1);
            float[] c=hoodRim(end/(float)steps);var n=hoodNormal(end/(float)steps,false);
            float full=1+.10f*(float)Math.sin(end*1.91)+.055f*(float)Math.cos(end*.79);
            a=new ModelPart.Vertex(a.x(),a.y(),a.z(),
                    .5f+((a.x()-c[0])*n.x+(a.z()-c[2])*n.z)/(.54f*full),
                    .5f+(a.y()-c[1])/(.68f*full));
            b=new ModelPart.Vertex(b.x(),b.y(),b.z(),
                    .5f+((b.x()-c[0])*n.x+(b.z()-c[2])*n.z)/(.54f*full),
                    .5f+(b.y()-c[1])/(.68f*full));
            var centre=new ModelPart.Vertex(c[0],c[1],c[2],.5f,.5f);
            orient(base,new ModelPart.Vertex[]{a,b,centre,centre},
                    new Vector3f(end==0?-1:1,.3f,-1));
        }
        put(out,Bone.BODY,FUR,SurfaceRole.COLLAR,Material.FUR,base);
        // Three staggered rows distribute the same fur mass into smaller locks.
        // The supporting welt stays covered; no separate head-sized capsules
        // dictate the outline of the lowered hood.
        List<FurPatch> patches=new ArrayList<>();
        for(int row=0;row<3;row++) {
            int count=switch(row){case 0->19;case 1->20;default->21;};
            for(int i=0;i<count;i++) {
                float stagger=switch(row){case 0->-.06f;case 1->.34f;default->-.20f;};
                float u=Math.clamp((i+stagger+(i%3-1)*.055f)/(count-1f),0,1);
                float[] c=hoodRim(u);var n=hoodNormal(u,false);
                float[] before=hoodRim(Math.max(0,u-.004f)),after=hoodRim(Math.min(1,u+.004f));
                Vector3f tangent=new Vector3f(after[0]-before[0],after[1]-before[1],after[2]-before[2]).normalize();
                n.sub(new Vector3f(tangent).mul(n.dot(tangent))).normalize();
                float scale=switch((i+row*3)%7) {
                    case 0->1.09f;case 1->.91f;case 2->1.03f;
                    case 3->.94f;case 4->1.12f;case 5->1.0f;default->.97f;
                };
                Vector3f up=new Vector3f(0,1,0).sub(new Vector3f(tangent).mul(tangent.y));
                up.sub(new Vector3f(n).mul(up.dot(n))).normalize();
                float tilt=switch((i+row*2)%5) {
                    case 0->-.31f;case 1->.26f;case 2->-.18f;case 3->.34f;default->.08f;
                };
                tangent.mul((float)Math.cos(tilt)).add(up.mul((float)Math.sin(tilt))).normalize();
                float radial=switch(row){case 0->-.04f;case 1->.22f;default->.35f;};
                radial+=(i%4-1.5f)*.025f;
                float yy=c[1]+switch(row){case 0->-.28f;case 1->-.05f;default->.27f;};
                yy+=(i%3-1)*.065f;
                // The rising front mouth needs overlapping fur above the
                // shoulder straps, rather than exposing them between small caps.
                float frontEnd=Math.clamp((.22f-Math.min(u,1-u))/.16f,0,1);
                yy-=.28f*frontEnd;
                float width=switch(row){case 0->1.26f;case 1->1.17f;default->1.28f;};
                float depth=(row==1?.92f:.86f)*(1+(i%3-1)*.05f);
                float height=switch(row){case 0->1.0f;case 1->.91f;default->.88f;};
                var lock=furLock(c[0]+n.x*radial,yy,c[2]+n.z*radial,
                        tangent,n,width*scale,depth,height*scale,(i%4-1.5f)*.075f);
                patches.add(new FurPatch(lock.bake(),
                        (i+row)%9==0?FUR_LIGHT:(i+row)%8==0?FUR_SHADE:FUR,.06f+(i%3)*.20f));
            }
        }
        // Closed overlapping locks contain fully hidden faces. Remove only a
        // face whose entire polygon lies inside one neighbouring convex lock;
        // this preserves visible caps and the silhouette in every camera view.
        List<FurHull> hulls=patches.stream().map(p->furHull(p.geometry())).toList();
        for(int index=0;index<patches.size();index++) {
            FurPatch patch=patches.get(index);final int own=index;FacetedMesh visible=mesh();
            patch.geometry().visit(new PoseStack(),(pose,path,cubeIndex,cube)->{
                for(var face:cube.polygons) {
                    boolean buried=false;
                    for(int neighbour=0;neighbour<hulls.size()&&!buried;neighbour++)
                        if(neighbour!=own)buried=furInside(face.vertices(),hulls.get(neighbour));
                    if(!buried)emit(visible,face.vertices());
                }
            });
            put(out,Bone.BODY,patch.color(),SurfaceRole.COLLAR,Material.FUR,
                    visible,patch.u(),.09f,.40f,.42f);
        }
    }

    private record FurPatch(ModelPart geometry,int color,float u) { }
    private record FurHull(float[] bounds,List<float[]> planes) { }

    private static FurHull furHull(ModelPart geometry) {
        float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        List<float[]> planes=new ArrayList<>();
        geometry.visit(new PoseStack(),(pose,path,index,cube)->{
            for(var face:cube.polygons) {
                var n=face.normal();var p=face.vertices()[0];
                planes.add(new float[]{n.x(),n.y(),n.z(),n.x()*p.x()+n.y()*p.y()+n.z()*p.z()});
                for(var v:face.vertices()) {
                    bounds[0]=Math.min(bounds[0],v.x());bounds[1]=Math.min(bounds[1],v.y());
                    bounds[2]=Math.min(bounds[2],v.z());bounds[3]=Math.max(bounds[3],v.x());
                    bounds[4]=Math.max(bounds[4],v.y());bounds[5]=Math.max(bounds[5],v.z());
                }
            }
        });
        return new FurHull(bounds,planes);
    }

    private static boolean furInside(ModelPart.Vertex[] vertices,FurHull hull) {
        float[] b=hull.bounds();
        for(var v:vertices) {
            if(v.x()<=b[0]||v.y()<=b[1]||v.z()<=b[2]
                    ||v.x()>=b[3]||v.y()>=b[4]||v.z()>=b[5])return false;
            for(float[] p:hull.planes())
                if(v.x()*p[0]+v.y()*p[1]+v.z()*p[2]>=p[3]-.002f)return false;
        }
        return true;
    }

    /** Broad overlapping locks with curved shoulders, never a pointed cone or flat puck. */
    private static FacetedMesh furLock(float cx,float cy,float cz,Vector3f tangent,
            Vector3f radial,float width,float depth,float height,float lean) {
        FacetedMesh m=mesh();int sides=12;
        Vector3f up=new Vector3f(0,1,0).sub(new Vector3f(tangent).mul(tangent.y));
        up.sub(new Vector3f(radial).mul(up.dot(radial))).normalize();
        float[] yy={-.5f,-.32f,-.04f,.28f,.5f},scale={.28f,.79f,1,.85f,.28f};
        ModelPart.Vertex[][] p=new ModelPart.Vertex[5][sides];
        float[] lobe={.98f,1.01f,1.025f,1.0f,.98f,.97f,1.0f,1.025f,1.03f,1.0f,.98f,.97f};
        for(int row=0;row<5;row++)for(int k=0;k<sides;k++) {
            double a=(k+.5)*Math.PI*2/sides;
            float tx=(float)Math.cos(a)*width*.5f*scale[row]*lobe[k]+lean*yy[row];
            float rz=(float)Math.sin(a)*depth*.5f*scale[row]*lobe[k]+lean*.4f*yy[row];
            float vertical=yy[row]*height+scale[row]*height*.045f*(float)Math.cos(a*3);
            p[row][k]=new ModelPart.Vertex(cx+tangent.x*tx+radial.x*rz+up.x*vertical,
                    cy+tangent.y*tx+radial.y*rz+up.y*vertical,
                    cz+tangent.z*tx+radial.z*rz+up.z*vertical,
                    k/(float)sides,row/4f);
        }
        for(int row=0;row<4;row++)for(int k=0;k<sides;k++) {
            int next=(k+1)%sides;
            var b=p[row][next];var c=p[row+1][next];
            if(next==0) {
                b=new ModelPart.Vertex(b.x(),b.y(),b.z(),1,b.v());
                c=new ModelPart.Vertex(c.x(),c.y(),c.z(),1,c.v());
            }
            var v=new ModelPart.Vertex[]{p[row][k],b,c,p[row+1][k]};
            var outward=new Vector3f();
            for(var q:v)outward.add(q.x()-cx,q.y()-cy,q.z()-cz);
            outward.sub(new Vector3f(up).mul(outward.dot(up)));
            orient(m,v,outward);
        }
        for(int row:new int[]{0,4})for(int k=1;k<sides-1;k++) {
            var va=furCapUV(p[row][0],cx,cy,cz,tangent,radial,width,depth);
            var vb=furCapUV(p[row][k],cx,cy,cz,tangent,radial,width,depth);
            var vc=furCapUV(p[row][k+1],cx,cy,cz,tangent,radial,width,depth);
            orient(m,new ModelPart.Vertex[]{va,vb,vc,vc},new Vector3f(up).mul(row==0?-1:1));
        }
        return m;
    }

    private static ModelPart.Vertex furCapUV(ModelPart.Vertex p,float cx,float cy,float cz,
            Vector3f tangent,Vector3f radial,float width,float depth) {
        float dx=p.x()-cx,dy=p.y()-cy,dz=p.z()-cz;
        return new ModelPart.Vertex(p.x(),p.y(),p.z(),
                .5f+(dx*tangent.x+dy*tangent.y+dz*tangent.z)/width,
                .5f+(dx*radial.x+dy*radial.y+dz*radial.z)/depth);
    }

    private static ModelPart.Vertex furVertex(int ring,int steps,int corner) {
        float u=ring/(float)steps;float[] c=hoodRim(u);var n=hoodNormal(u,false);
        double a=corner*Math.PI/4;
        float fullness=1+.10f*(float)Math.sin(ring*1.91)+.055f*(float)Math.cos(ring*.79);
        float radial=.27f*fullness*(float)Math.cos(a);
        float vertical=.34f*fullness*(float)Math.sin(a);
        return new ModelPart.Vertex(c[0]+n.x*radial,c[1]+vertical,
                c[2]+n.z*radial,u,corner/8f);
    }

    private static void closure(List<Piece> out) {
        panel(out,Bone.BODY,CLOTH_EDGE,SurfaceRole.PLACKET,Material.CLOTH,BODY,
                .03f,5.0f,.66f,12.1f,.12f,false);
        ribbon(out,Bone.BODY,CLOTH_SEAM,Material.CLOTH,BODY,
                -.35f,-.80f,-.35f,11.2f,.065f,.16f,false);
        ribbon(out,Bone.BODY,ZIP,Material.METAL,BODY,
                .40f,-.80f,.40f,11.2f,.075f,.13f,false);
        for(float y:new float[]{-.2f,2.5f,5.0f,8.5f,10.8f})
            stud(out,Bone.BODY,BODY,.02f,y,.15f,.19f,false);
        panel(out,Bone.BODY,LEATHER,SurfaceRole.DETAIL,Material.LEATHER,BODY,
                .45f,.65f,.18f,.57f,.21f,false);
    }

    private static void pocket(List<Piece> out,float x,float y,float w,float h,float relief,boolean back) {
        panel(out,Bone.BODY,CLOTH_SEAM,SurfaceRole.POCKET,Material.CLOTH,BODY,
                x,y,w+.08f,h+.06f,relief-.035f,back);
        panel(out,Bone.BODY,CLOTH,SurfaceRole.POCKET,Material.CLOTH,BODY,
                x,y+.025f,w,h,relief,back);
        // A wide folded flap with a small air gap and a shaped lower edge.
        float flapY=y-h*.5f+.31f;
        panel(out,Bone.BODY,FLAP_CLOTH,SurfaceRole.FLAP,Material.CLOTH,BODY,
                x,flapY,w+.16f,.93f,relief+.13f,back);
        // Narrow dark fabric under the flap is its physical contact seam.
        ribbon(out,Bone.BODY,POCKET_SEAM,Material.CLOTH,BODY,
                x-w*.47f,flapY+.49f,x+w*.47f,flapY+.49f,.09f,relief+.045f,back);
        for(float offset:new float[]{-.31f,.31f})
            stud(out,Bone.BODY,BODY,x+w*offset,flapY+.15f,.22f,relief+.21f,back);
        // Two recessed vertical construction seams and a lower stitched edge.
        for(float sign:new float[]{-1,1})
            ribbon(out,Bone.BODY,CLOTH_SEAM,Material.CLOTH,BODY,
                x+sign*w*.42f,y-h*.32f,x+sign*w*.42f,y+h*.41f,
                .035f,relief+.022f,back);
        ribbon(out,Bone.BODY,CLOTH_SEAM,Material.CLOTH,BODY,
                x-w*.41f,y+h*.42f,x+w*.41f,y+h*.42f,.035f,relief+.022f,back);
    }

    /** Low-sided, bevelled rectangular patch, with real thickness at its edges. */
    private static void panel(List<Piece> out,Bone bone,int color,SurfaceRole role,Material material,
            FacetedMesh.Section[] shell,float x,float y,float w,float h,float relief,boolean back) {
        float bevel=Math.min(.12f,Math.min(w,h)*.16f),sgn=back?1:-1;
        int columns=Math.max(2,(int)Math.ceil(w/.85f));
        int rows=Math.max(2,(int)Math.ceil(h/1.0f));
        FacetedMesh m=mesh();
        ModelPart.Vertex[][] front=new ModelPart.Vertex[rows+1][columns+1];
        ModelPart.Vertex[][] rear=new ModelPart.Vertex[rows+1][columns+1];
        for(int row=0;row<=rows;row++)for(int col=0;col<=columns;col++) {
            float b=row/(float)rows,a=col/(float)columns;
            float taper=row==0||row==rows?bevel:0;
            float xx=x+(a-.5f)*(w-taper*2),yy=y+(b-.5f)*h;
            float depth=surface(shell,xx,yy,back);
            float faceDepth=depth;
            // Every panel follows the actual cloth surface. A straight chord
            // across the curved chest/back would sink the middle into the coat.
            float pu=(xx-x)/14f+.47f,pv=(yy-y)/14f+.47f;
            // Leather grain and metal wear use a readable local construction map.
            if(material!=Material.CLOTH){pu=(xx-x)/w+.5f;pv=b;}
            rear[row][col]=new ModelPart.Vertex(xx,yy,depth+sgn*.018f,pu,pv);
            front[row][col]=new ModelPart.Vertex(xx,yy,faceDepth+sgn*relief,pu,pv);
        }
        for(int row=0;row<rows;row++)for(int col=0;col<columns;col++)
            orient(m,new ModelPart.Vertex[]{front[row][col],front[row][col+1],
                    front[row+1][col+1],front[row+1][col]},new Vector3f(0,0,sgn));
        for(int row=0;row<rows;row++)for(int col:new int[]{0,columns})
            orient(m,new ModelPart.Vertex[]{rear[row][col],rear[row+1][col],
                    front[row+1][col],front[row][col]},new Vector3f(col==0?-1:1,0,0));
        for(int col=0;col<columns;col++)for(int row:new int[]{0,rows})
            orient(m,new ModelPart.Vertex[]{rear[row][col],rear[row][col+1],
                    front[row][col+1],front[row][col]},new Vector3f(0,row==0?-1:1,0));
        put(out,bone,color,role,material,m);
    }

    private static void ribbon(List<Piece> out,Bone bone,int color,Material material,
            FacetedMesh.Section[] shell,float ax,float ay,float bx,float by,float width,float relief,boolean back) {
        float dx=bx-ax,dy=by-ay,length=(float)Math.hypot(dx,dy);
        float nx=-dy/length*width*.5f,ny=dx/length*width*.5f,sign=back?1:-1;
        FacetedMesh m=mesh();int steps=Math.max(1,(int)Math.ceil(length/.8f));
        for(int i=0;i<steps;i++) {
            float t0=i/(float)steps,t1=(i+1f)/steps;
            float x0=ax+dx*t0,y0=ay+dy*t0,x1=ax+dx*t1,y1=ay+dy*t1;
            ModelPart.Vertex[] v={
                on(shell,x0-nx,y0-ny,relief,back,0,t0),
                on(shell,x0+nx,y0+ny,relief,back,1,t0),
                on(shell,x1+nx,y1+ny,relief,back,1,t1),
                on(shell,x1-nx,y1-ny,relief,back,0,t1)};
            orient(m,v,new Vector3f(0,0,sign));
        }
        put(out,bone,color,SurfaceRole.DETAIL,material,m);
    }

    private static ModelPart.Vertex on(FacetedMesh.Section[] shell,float x,float y,float relief,
            boolean back,float u,float v) {
        return new ModelPart.Vertex(x,y,surface(shell,x,y,back)+(back?1:-1)*relief,u,v);
    }

    private static void stud(List<Piece> out,Bone bone,FacetedMesh.Section[] s,
            float x,float y,float size,float relief,boolean back) {
        panel(out,bone,METAL,SurfaceRole.DETAIL,Material.METAL,s,x,y,size,size,relief,back);
    }

    private static void buckle(List<Piece> out,FacetedMesh.Section[] s,float x,float y,
            float w,float h,float relief,boolean back) {
        float border=.095f,sgn=back?1:-1;FacetedMesh frame=mesh();
        float[][] outer={{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{-.5f,.5f}};
        for(int i=0;i<4;i++) {
            int j=(i+1)%4;
            float ax=x+outer[i][0]*w,ay=y+outer[i][1]*h;
            float bx=x+outer[j][0]*w,by=y+outer[j][1]*h;
            float iax=x+outer[i][0]*(w-border*2),iay=y+outer[i][1]*(h-border*2);
            float ibx=x+outer[j][0]*(w-border*2),iby=y+outer[j][1]*(h-border*2);
            var a=on(s,ax,ay,relief,back,outer[i][0]+.5f,outer[i][1]+.5f);
            var b=on(s,bx,by,relief,back,outer[j][0]+.5f,outer[j][1]+.5f);
            var ia=on(s,iax,iay,relief,back,.5f+outer[i][0]*.7f,.5f+outer[i][1]*.7f);
            var ib=on(s,ibx,iby,relief,back,.5f+outer[j][0]*.7f,.5f+outer[j][1]*.7f);
            orient(frame,new ModelPart.Vertex[]{a,b,ib,ia},new Vector3f(0,0,sgn));
            var da=on(s,ax,ay,relief-.09f,back,a.u(),a.v());
            var db=on(s,bx,by,relief-.09f,back,b.u(),b.v());
            orient(frame,new ModelPart.Vertex[]{a,da,db,b},
                    new Vector3f((ax+bx)*.5f-x,(ay+by)*.5f-y,0));
        }
        put(out,Bone.BODY,METAL,SurfaceRole.DETAIL,Material.METAL,frame);
        ribbon(out,Bone.BODY,METAL,Material.METAL,s,x,y-h*.38f,x,y+h*.38f,
                .075f,relief+.025f,back);
    }

    private static void strapOverShoulder(List<Piece> out,int side) {
        FacetedMesh m=mesh();float[] zs={-2.58f,-1.6f,-.4f,.8f,2.2f,2.68f};
        float[] ys={-.53f,-1.12f,-1.34f,-1.25f,-.79f,-.22f};
        for(int i=0;i<zs.length-1;i++) {
            float x=side*2.72f;
            var v=new ModelPart.Vertex[]{
                    new ModelPart.Vertex(x-.45f,ys[i],zs[i],0,i/5f),
                    new ModelPart.Vertex(x+.45f,ys[i],zs[i],1,i/5f),
                    new ModelPart.Vertex(x+.45f,ys[i+1],zs[i+1],1,(i+1)/5f),
                    new ModelPart.Vertex(x-.45f,ys[i+1],zs[i+1],0,(i+1)/5f)};
            orient(m,v,new Vector3f(0,-1,0));
        }
        put(out,Bone.BODY,LEATHER,SurfaceRole.DETAIL,Material.LEATHER,m);
    }

    private static void snow(List<Piece> out) {
        // Sparse sewn-edge deposits, not a replacement for light fabric/fur.
        for(boolean back:new boolean[]{false,true})for(int i=0;i<7;i++) {
            float x=-3.55f+i*1.14f,y=i%3==0?-.13f:11.24f;
            ribbon(out,Bone.BODY,SNOW,Material.METAL,BODY,x,y,
                    x+.13f+(i%3)*.06f,y-.01f,.048f,.13f,back);
        }
    }

    private static void put(List<Piece> out,Bone bone,int color,SurfaceRole role,
            Material material,FacetedMesh source) {
        put(out,bone,color,role,material,source,0,0,1,1);
    }

    /** Material islands include a gutter; UVs remain continuous across triangles. */
    private static void put(List<Piece> out,Bone bone,int color,SurfaceRole role,
            Material material,FacetedMesh source,float u,float v,float w,float h) {
        FacetedMesh mapped=mesh();
        source.bake().visit(new PoseStack(),(pose,path,index,cube)->{
            for(var f:cube.polygons) {
                var a=f.vertices();var b=new ModelPart.Vertex[a.length];
                boolean edgeUV=Math.abs(uvArea(a[0],a[1],a[2]))<1e-9f
                        &&Math.abs(uvArea(a[0],a[2],a[3]))<1e-9f;
                for(int i=0;i<a.length;i++) {
                    var p=a[i];float pu=u+p.u()*w,pv=v+p.v()*h;
                    if(edgeUV||(material==Material.CLOTH&&(role==SurfaceRole.MAIN||role==SurfaceRole.HOOD))) {
                        // Front/back/side fabric panels share the same physical
                        // weave scale as pockets, rather than stretching one
                        // entire material tile around the whole chest.
                        float nx=Math.abs(f.normal().x()),ny=Math.abs(f.normal().y()),nz=Math.abs(f.normal().z());
                        if(ny>=nx&&ny>=nz) {
                            pu=p.x()/14f+.5f;pv=p.z()/14f+.5f;
                        } else {
                            pu=(nz>=nx?p.x():p.z())/14f+.5f;
                            pv=role==SurfaceRole.HOOD?(p.y()+4)/16f:(p.y()+2)/14f;
                        }
                    }
                    if(material!=Material.KNIT) {
                        pu=.012f+.476f*pu+(material==Material.LEATHER||material==Material.METAL?.5f:0);
                        pv=.012f+.476f*pv+(material==Material.FUR||material==Material.METAL?.5f:0);
                    }
                    b[i]=new ModelPart.Vertex(p.x(),p.y(),p.z(),pu,pv);
                }
                emit(mapped,b);
            }
        });
        out.add(new Piece(bone,mapped.bake(),color,role));
    }

    /** Drop only collapsed triangles produced at a clipping boundary; never valid surfaces. */
    private static List<Piece> compact(List<Piece> pieces) {
        record Key(Bone bone,int color,SurfaceRole role) { }
        Map<Key,List<ModelPart.Cube>> groups=new LinkedHashMap<>();
        for(Piece piece:pieces) {
            FacetedMesh clean=mesh();
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons)emit(clean,face.vertices());
            });
            var cubes=groups.computeIfAbsent(new Key(piece.bone(),piece.color(),piece.role()),
                    key->new ArrayList<>());
            clean.bake().visit(new PoseStack(),(pose,path,index,cube)->cubes.add(cube));
        }
        List<Piece> result=new ArrayList<>();
        groups.forEach((key,cubes)->{
            if(!cubes.isEmpty())result.add(new Piece(key.bone(),
                    new ModelPart(List.copyOf(cubes),Map.of()),key.color(),key.role()));
        });
        return List.copyOf(result);
    }

    private static void orient(FacetedMesh m,ModelPart.Vertex[] v,Vector3f outward) {
        Vector3f n=normal(v[0],v[1],v[2]);
        if(n.dot(outward)<0)v=new ModelPart.Vertex[]{v[3],v[2],v[1],v[0]};
        // Duplicated fourth vertices represent triangles; reverse them without
        // putting the duplicate at the leading edge.
        if(area(v[0],v[1],v[2])<1e-10f&&area(v[0],v[2],v[3])>1e-10f)
            v=new ModelPart.Vertex[]{v[0],v[2],v[3],v[3]};
        emit(m,v);
    }

    private static void emit(FacetedMesh m,ModelPart.Vertex[] v) {
        float a=area(v[0],v[1],v[2]),b=area(v[0],v[2],v[3]);
        if(a<1e-10f) {
            if(b>1e-10f)m.face(v[0],v[2],v[3],v[3]);
            return;
        }
        // Keep planar quads; triangulate a curved cloth patch only if needed.
        Vector3f n=normal(v[0],v[1],v[2]);
        float d=Math.abs(n.dot(v[3].x()-v[0].x(),v[3].y()-v[0].y(),v[3].z()-v[0].z()));
        if(d<n.length()*.00005f)m.face(v);
        else {
            m.face(v[0],v[1],v[2],v[2]);
            if(b>1e-10f)m.face(v[0],v[2],v[3],v[3]);
        }
    }

    private static float uvArea(ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        return (b.u()-a.u())*(c.v()-a.v())-(c.u()-a.u())*(b.v()-a.v());
    }

    private static Vector3f normal(ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        return new Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z())
                .cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z());
    }
    private static float area(ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        return normal(a,b,c).lengthSquared();
    }
    private static float signedPow(float value,float power) {
        return Math.copySign((float)Math.pow(Math.abs(value),power),value);
    }
    private static float tent(float y,float centre,float width) {
        float t=Math.clamp(1-Math.abs(y-centre)/width,0,1);
        return t*t*(3-2*t);
    }
    private CinematicExpeditionParka() { }
}
