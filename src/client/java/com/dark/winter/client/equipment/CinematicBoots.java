// Builds the eight authored winter-boot meshes in the clothing catalog.
package com.dark.winter.client.equipment;

import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;
import static com.dark.winter.client.equipment.NorthernWandererClothing.*;

/** Eight numbered, reference-locked winter boots. Every pair shares the existing sole plane. */
final class CinematicBoots {
    private static final int OLIVE=0xFF7A7561, WORK=0xFF80563E, RUBBER_OLIVE=0xFF777662,
            TACTICAL=0xFF454344, MOUNTAIN=0xFF77543F, FELT_LEATHER=0xFF6E5342,
            SURVIVAL=0xFF74705A, FUR_LEATHER=0xFF79563F;
    private static final int BLACK=0xFF26262B, EDGE=0xFF434044, KNIT=0xFF35353E,
            FUR=0xFFB9AC98, FUR_LIGHT=0xFFCBBBA7, FELT=0xFF938975,
            LEATHER_STRAP=0xFF644736, LACE=0xFF967258, STEEL=0xFF989492,
            SNOW_WHITE=0xFFD5D9DE, STITCH=0xFFB4A18C;
    private static final int[] COLORS={0,OLIVE,WORK,RUBBER_OLIVE,TACTICAL,MOUNTAIN,FELT_LEATHER,SURVIVAL,FUR_LEATHER};
    private static final Identifier CANVAS=material("canvas"), WOOL=material("wool"),
            LEATHER=material("leather"), RUBBER=material("rubber");
    private static final List<List<Piece>> ALL=create();

    static List<Piece> pieces(int design) {
        if(design<1||design>8)throw new IllegalArgumentException("Boot design must be 1..8");
        return ALL.get(design-1);
    }
    static Identifier texture(int design,int color) {
        if(color==BLACK||color==EDGE||color==STEEL||color==SNOW_WHITE)return RUBBER;
        if(color==FUR||color==FUR_LIGHT||color==FELT||color==KNIT||color==shade(KNIT,1.14f))return WOOL;
        if(color==OLIVE||color==SURVIVAL)return CANVAS;
        if(design==3&&color==RUBBER_OLIVE)return RUBBER;
        return LEATHER;
    }
    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }
    private static final java.util.Map<Piece,java.util.Map<Integer,Piece>> TUCKED=new java.util.IdentityHashMap<>();

    /** Fit only trouser fabric covered by this boot; original FP/item meshes and bone pivots stay intact. */
    static Piece tuckTrousers(Piece source,int design) {
        if(design<1||design>8)return source;
        float heightOffset=switch(source.bone()) {
            case LEFT_LEG,RIGHT_LEG -> 0;
            case LEFT_SHIN,RIGHT_SHIN -> HumanJoints.KNEE_Y;
            case LEFT_FOOT,RIGHT_FOOT -> HumanJoints.ANKLE_Y;
            default -> Float.NaN;
        };
        if(!Float.isFinite(heightOffset))return source;
        var cache=TUCKED.computeIfAbsent(source,ignored->new java.util.HashMap<>());
        return cache.computeIfAbsent(design,ignored->tuck(source,design,heightOffset));
    }

    private static Piece tuck(Piece source,int design,float heightOffset) {
        float side=switch(source.bone()){case LEFT_LEG,LEFT_SHIN,LEFT_FOOT->1;default->-1;};
        float start=top(design)-.34f,end=top(design)+.26f;
        var shell=upper(design,side*.25f,side*.34f);
        FacetedMesh fitted=mesh();boolean[] changed={false};
        source.geometry().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
            for(var face:cube.polygons) {
                List<List<ModelPart.Vertex>> cells=new ArrayList<>();
                cells.add(uniqueVertices(List.of(face.vertices())));
                // Long knee reinforcement faces must follow the tuck boundary rather than bridge across it.
                for(float cut:new float[]{start,end,8.30f,9.75f,10.45f,10.75f,11.45f}) {
                    List<List<ModelPart.Vertex>> next=new ArrayList<>();
                    for(var cell:cells) {
                        float plane=cut-heightOffset;
                        boolean above=cell.stream().anyMatch(v->v.y()<plane-.00001f);
                        boolean below=cell.stream().anyMatch(v->v.y()>plane+.00001f);
                        if(cut>=start&&above&&below) {
                            next.add(clipped(cell,plane,true));next.add(clipped(cell,plane,false));
                        } else next.add(cell);
                    }
                    cells=next;
                }
                for(var cell:cells) {
                    List<ModelPart.Vertex> vertices=new ArrayList<>();
                    for(var p:cell) {
                        float h=p.y()+heightOffset;
                        if(h<=start){vertices.add(p);continue;}
                        float blend=Math.clamp((h-start)/(end-start),0,1);blend=blend*blend*(3-2*blend);
                        Vector3f target=tuckedPoint(shell,side,p.x(),h,p.z());
                        float x=p.x()+(target.x-p.x())*blend,z=p.z()+(target.z-p.z())*blend;
                        changed[0]|=Math.abs(x-p.x())+Math.abs(z-p.z())>.00001f;
                        vertices.add(new ModelPart.Vertex(x,p.y(),z,p.u(),p.v()));
                    }
                    if(vertices.size()==4)ThirdPersonContours.face(fitted,vertices.toArray(ModelPart.Vertex[]::new));
                    else for(int i=1;i<vertices.size()-1;i++)
                        fitted.face(vertices.getFirst(),vertices.get(i),vertices.get(i+1),vertices.get(i+1));
                }
            }
        });
        // Buried trim can flatten onto the inner shaft; drop only its zero-area slivers.
        return changed[0]?compact(List.of(new Piece(source.bone(),fitted.bake(),source.color(),source.role()))).getFirst():source;
    }

    private static List<ModelPart.Vertex> uniqueVertices(List<ModelPart.Vertex> input) {
        List<ModelPart.Vertex> result=new ArrayList<>();
        for(var p:input)if(result.stream().noneMatch(q->Math.abs(p.x()-q.x())+Math.abs(p.y()-q.y())+Math.abs(p.z()-q.z())<.000001f))
            result.add(p);
        return result;
    }

    private static List<ModelPart.Vertex> clipped(List<ModelPart.Vertex> input,float y,boolean above) {
        List<ModelPart.Vertex> result=new ArrayList<>();
        for(int i=0;i<input.size();i++) {
            var a=input.get(i);var b=input.get((i+1)%input.size());
            boolean ia=above?a.y()<=y:a.y()>=y,ib=above?b.y()<=y:b.y()>=y;
            if(ia)result.add(a);
            if(ia!=ib) {
                float t=(y-a.y())/(b.y()-a.y());
                result.add(new ModelPart.Vertex(a.x()+(b.x()-a.x())*t,y,a.z()+(b.z()-a.z())*t,
                    a.u()+(b.u()-a.u())*t,a.v()+(b.v()-a.v())*t));
            }
        }
        return uniqueVertices(result);
    }

    /** Radial projection against the actual 20-sided shaft, with cloth clearance on every side. */
    private static Vector3f tuckedPoint(FacetedMesh.Section[] shell,float side,float x,float y,float z) {
        float fit=Math.clamp((11.45f-y)/1.0f,0,1);
        float center=side*(.25f+.17f*fit);
        float cz=profile(shell,y,2)*(1-.025f*fit);
        float dx=x-center,dz=z-cz,scale=1;
        for(int i=0;i<20;i++) {
            var a=shaftPoint(shell,side,y,i);var b=shaftPoint(shell,side,y,(i+1)%20);
            float nx=b.z-a.z,nz=-(b.x-a.x),denominator=nx*dx+nz*dz;
            if(denominator<=.000001f)continue;
            float allowance=nx*(a.x-center)+nz*(a.z-cz)-.18f*(float)Math.sqrt(nx*nx+nz*nz);
            scale=Math.min(scale,Math.max(0,allowance/denominator));
        }
        return new Vector3f(center+dx*scale,y,cz+dz*scale);
    }

    private static Vector3f shaftPoint(FacetedMesh.Section[] shell,float side,float y,int index) {
        int row=0;while(row<shell.length-2&&y>shell[row+1].y())row++;
        var a=shell[row];var b=shell[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        double angle=-Math.PI*.75+index*Math.PI/10,co=Math.cos(angle),si=Math.sin(angle);
        float ax=a.shiftX()+a.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(co),2/a.power()),co);
        float bx=b.shiftX()+b.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(co),2/b.power()),co);
        float az=a.shiftZ()+a.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(si),2/a.power()),si);
        float bz=b.shiftZ()+b.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(si),2/b.power()),si);
        float x=ax+(bx-ax)*t,z=az+(bz-az)*t,fit=Math.clamp((11.45f-y)/1.0f,0,1),center=side*.25f;
        return new Vector3f(center+(x-center)*(1-.08f*fit)+side*.17f*fit,y,z*(1-.025f*fit));
    }

    private static List<List<Piece>> create() {
        List<List<Piece>> result=new ArrayList<>();
        for(int design=1;design<=8;design++) {
            List<Piece> p=new ArrayList<>();
            for(boolean left:new boolean[]{false,true})build(p,design,left);
            result.add(compact(articulate(fitShaft(p))));
        }
        return List.copyOf(result);
    }

    private static FacetedMesh.Section[] upper(int design,float offset,float toe) {
        float top=top(design),width=design==3?4.30f:design==6||design==8?4.18f:4.06f;
        List<FacetedMesh.Section> rows=new ArrayList<>(List.of(
            section(top,width,4.43f,offset,.06f,3.9f),
            section(top+.32f,width+.10f,4.53f,offset,.06f,4.0f)));
        // Follow the actual calf maximum used by the third-person contour map.
        // A long top-to-ankle face otherwise cuts inside the trouser's intermediate bulge.
        if(top+.32f<8.30f)rows.add(section(8.30f,4.08f,4.55f,offset,.06f,3.9f));
        rows.addAll(List.of(
            section(9.75f,3.88f,4.31f,offset,.04f,3.9f),
            section(10.75f,3.70f,4.05f,offset,-.07f,3.8f),
            section(11.4f,3.89f,4.50f,(offset+toe)*.5f,-.42f,3.7f),
            section(11.9f,4.20f,5.61f,toe,-.81f,3.9f),
            section(12.40f,4.35f,5.91f,toe,-.94f,4.1f),
            section(12.91f,4.40f,6.0f,toe,-.97f,4.2f)));
        return rows.toArray(FacetedMesh.Section[]::new);
    }
    private static float top(int design) {
        return switch(design){case 3->6.45f;case 6->7.45f;case 8->6.95f;case 2,4->7.65f;case 5->7.90f;default->7.65f;};
    }

    private static void build(List<Piece> p,int design,boolean left) {
        Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;
        float side=left?1:-1,offset=side*.25f,toe=side*.34f,top=top(design);
        int color=COLORS[design];
        var shell=upper(design,offset,toe);
        add(p,bone,color,SurfaceRole.MAIN,mesh().softLoft(0,0,20,shell));
        sole(p,bone,toe);
        // Rubber toe bumper and heel counter are distinct rounded fitted layers.
        add(p,bone,BLACK,SurfaceRole.DETAIL,patch(shell,toe,12.30f,4.0f,1.13f,.17f,false));
        add(p,bone,EDGE,SurfaceRole.DETAIL,patch(shell,toe,12.73f,4.06f,.14f,.24f,false));
        add(p,bone,BLACK,SurfaceRole.DETAIL,patch(shell,offset,11.85f,3.15f,1.77f,.16f,true));
        add(p,bone,EDGE,SurfaceRole.DETAIL,patch(shell,offset,11.45f,1.20f,.33f,.24f,true));
        add(p,bone,shade(color,.86f),SurfaceRole.DETAIL,patch(shell,offset,10.86f,.56f,2.7f,.08f,true));
        for(float x:new float[]{offset-1.15f,offset+1.15f}) {
            add(p,bone,shade(color,1.12f),SurfaceRole.DETAIL,patch(shell,x,11.55f,.21f,1.20f,.16f,false));
            for(float y:new float[]{11.18f,11.52f,11.86f})
                add(p,bone,STITCH,SurfaceRole.DETAIL,patch(shell,x,y,.065f,.09f,.21f,false));
        }
        if(design==6||design==8)furCuff(p,bone,offset,top,design==8?1.05f:.88f);
        else if(design==5)feltCuff(p,bone,offset,top);
        else knitCuff(p,bone,offset,top,design==3?4.48f:4.26f);
        switch(design) {
            case 1 -> {
                paddedShaft(p,bone,shell,color,offset,top);
                strap(p,bone,shell,offset,top+1.05f,BLACK,side,true);
                strap(p,bone,shell,offset,top+2.35f,BLACK,side,true);
                add(p,bone,color,SurfaceRole.DETAIL,patch(shell,offset,11.38f,1.79f,1.52f,.19f,false));
            }
            case 2 -> {
                laces(p,bone,shell,offset,top+.55f,11.80f,LACE,color,true);
                strap(p,bone,shell,offset,top+.27f,LEATHER_STRAP,side,false);
            }
            case 3 -> {
                // A continuous rubber boot has no invented laces or segmented tactical plates.
                add(p,bone,BLACK,SurfaceRole.DETAIL,patch(shell,offset,10.37f,2.13f,2.57f,.11f,false));
                add(p,bone,EDGE,SurfaceRole.DETAIL,patch(shell,offset,9.55f,1.02f,.32f,.18f,false));
                FacetedMesh cord=mesh();
                float x=offset+1.55f;
                for(int i=0;i<5;i++) {
                    float y=top+.2f+i*.24f;
                    line(cord,x,y,bootSurface(shell,x,y,false)-.13f,x+.1f,y+.27f,bootSurface(shell,x+.1f,y+.27f,false)-.15f,.07f);
                }
                add(p,bone,BLACK,SurfaceRole.DETAIL,cord);
            }
            case 4 -> {
                laces(p,bone,shell,offset,top+.80f,11.76f,EDGE,color,true);
                strap(p,bone,shell,offset,top+.25f,EDGE,side,true);
                strap(p,bone,shell,offset,10.65f,EDGE,side,true);
            }
            case 5 -> {
                laces(p,bone,shell,offset,top+.18f,11.91f,LACE,color,true);
                for(float y:new float[]{top+.9f,top+1.9f})
                    strap(p,bone,shell,offset,y,shade(color,1.13f),side,false);
            }
            case 6 -> {
                strap(p,bone,shell,offset,top+.85f,LEATHER_STRAP,side,true);
                strap(p,bone,shell,offset,10.39f,LEATHER_STRAP,side,true);
                add(p,bone,LEATHER_STRAP,SurfaceRole.DETAIL,patch(shell,offset,9.72f,.53f,4.11f,.21f,true));
                add(p,bone,STEEL,SurfaceRole.DETAIL,patch(shell,offset,11.20f,.16f,.17f,.27f,true));
            }
            case 7 -> {
                paddedShaft(p,bone,shell,color,offset,top);
                strap(p,bone,shell,offset,top+.91f,BLACK,side,true);
                strap(p,bone,shell,offset,top+2.29f,BLACK,side,true);
                add(p,bone,EDGE,SurfaceRole.DETAIL,patch(shell,offset-side*.85f,9.60f,.66f,3.03f,.24f,true));
                for(float y:new float[]{8.77f,9.55f,10.31f})
                    add(p,bone,STEEL,SurfaceRole.DETAIL,patch(shell,offset-side*.85f,y,.14f,.14f,.32f,true));
            }
            case 8 -> laces(p,bone,shell,offset,top+1.18f,11.90f,LACE,color,true);
            default -> throw new AssertionError();
        }
        // The reference has settled snow on the welt plus a small interrupted toe accent.
        for(int i=0;i<5;i++) {
            float x=toe-1.15f+i*.55f,y=11.91f+(i%2)*.06f;
            add(p,bone,SNOW_WHITE,SurfaceRole.DETAIL,patch(shell,x,y,.34f,.10f,.20f,false));
        }
    }

    private static void sole(List<Piece> p,Bone bone,float toe) {
        add(p,bone,BLACK,SurfaceRole.MAIN,mesh().softLoft(toe,-.98f,20,
            section(12.86f,4.43f,6.06f,0,0,4.2f),section(13.08f,4.53f,6.16f,0,0,4.2f),
            section(13.61f,4.52f,6.15f,0,0,4.1f),section(13.80f,4.40f,6.05f,0,0,4.0f)));
        add(p,bone,EDGE,SurfaceRole.DETAIL,mesh().openTube(toe,-.98f,20,.09f,
            section(13.02f,4.56f,6.20f,0,0,4.2f),section(13.23f,4.56f,6.20f,0,0,4.2f)));
        // Physical tread blocks are useful on all sides and in the heel-lift frames.
        FacetedMesh tread=mesh();
        FacetedMesh.Section[] soleVolume={
            section(12.86f,4.43f,6.06f,0,0,4.2f),section(13.08f,4.53f,6.16f,0,0,4.2f),
            section(13.61f,4.52f,6.15f,0,0,4.1f),section(13.80f,4.40f,6.05f,0,0,4.0f)};
        for(float x:new float[]{toe-1.51f,toe,toe+1.51f})for(float z:new float[]{-3.37f,-1.82f,-.20f,1.35f}) {
            var block=mesh().softLoft(x,z,8,
                section(13.57f,.79f,.94f,0,0,4.1f),section(13.80f,.75f,.90f,0,0,4.1f));
            block.bake().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    boolean buried=true;
                    for(var v:face.vertices()) {
                        float px=v.x()-toe,pz=v.z()+.98f;
                        buried&=v.y()>=12.86f&&v.y()<=13.80001f
                            &&Math.abs(px)<profile(soleVolume,v.y(),0)*.5f
                            &&pz>=bootSurface(soleVolume,px,v.y(),false)-.00001f
                            &&pz<=bootSurface(soleVolume,px,v.y(),true)+.00001f;
                    }
                    // Retain every exposed tread facet; hidden/coplanar rubber never reaches the item baker.
                    if(!buried)tread.face(face.vertices());
                }
            });
        }
        add(p,bone,BLACK,SurfaceRole.DETAIL,tread);
        for(int i=0;i<7;i++) {
            float x=toe-1.64f+i*.54f;
            float z=-.98f-3.09f*(float)Math.pow(1-Math.pow(Math.abs((x-toe)/2.28f),4.2),1/4.2);
            float y=13.41f+(i%3)*.045f;
            add(p,bone,SNOW_WHITE,SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                section(y-.17f,.37f,.13f,0,0,4),section(y+.16f,.46f,.16f,0,0,4)));
        }
        for(int side:new int[]{-1,1})for(int i=0;i<4;i++) {
            float z=-2.95f+i*1.32f,f=Math.abs((z+.98f)/3.09f);
            float x=toe+side*2.28f*(float)Math.pow(1-Math.pow(f,4.2),1/4.2);
            float y=13.39f+(i%2)*.10f;
            add(p,bone,SNOW_WHITE,SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                section(y-.15f,.12f,.47f,0,0,3.8f),section(y+.16f,.18f,.61f,0,0,3.8f)));
        }
    }

    private static void knitCuff(List<Piece> p,Bone bone,float offset,float top,float width) {
        add(p,bone,KNIT,SurfaceRole.DETAIL,mesh().openTube(offset,.06f,20,.16f,
            section(top-.32f,width-.10f,4.57f,0,0,3.8f),
            section(top-.10f,width,4.67f,0,0,3.9f),section(top+.35f,width-.06f,4.62f,0,0,3.9f)));
        FacetedMesh ribs=mesh();
        for(int i=0;i<22;i++) {
            double a=i*Math.PI*2/22;
            float x=offset+width*.505f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/3.9),Math.cos(a));
            float z=.06f+2.35f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/3.9),Math.sin(a));
            line(ribs,x,top-.23f,z,x,top+.29f,z,.035f);
        }
        add(p,bone,shade(KNIT,1.14f),SurfaceRole.DETAIL,ribs);
    }

    private static void feltCuff(List<Piece> p,Bone bone,float offset,float top) {
        knitCuff(p,bone,offset,top-.12f,4.26f);
        add(p,bone,FELT,SurfaceRole.DETAIL,mesh().openTube(offset,.06f,20,.20f,
            section(top+.05f,4.43f,4.53f,0,0,3.9f),section(top+.43f,4.47f,4.57f,0,0,3.9f)));
    }

    private static void furCuff(List<Piece> p,Bone bone,float offset,float top,float height) {
        add(p,bone,FUR,SurfaceRole.DETAIL,mesh().openTube(offset,.06f,20,.22f,
            section(top-.12f,4.45f,4.65f,0,0,3.8f),
            section(top+.10f,4.62f,4.81f,0,0,3.9f),
            section(top+height,4.48f,4.67f,0,0,3.8f)));
        // Compact faceted wool curls, never long realistic hair strands or a smooth white torus.
        FacetedMesh curls=mesh();
        for(int i=0;i<20;i++) {
            double a=i*Math.PI*2/20;
            float x=offset+2.30f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/3.9),Math.cos(a));
            float z=.06f+2.40f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/3.9),Math.sin(a));
            float y=top+.10f+(i%3)*height*.21f;
            var curl=mesh().softLoft(x,z,8,section(y-.14f,.18f,.20f,0,0,2.7f),
                section(y+.08f,.34f,.35f,0,0,2.8f),section(y+.28f,.18f,.20f,0,0,2.7f));
            FacetedMesh.Section[] cuff={
                section(top-.12f,4.45f,4.65f,0,0,3.8f),
                section(top+.10f,4.62f,4.81f,0,0,3.9f),
                section(top+height,4.48f,4.67f,0,0,3.8f)};
            curl.bake().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    boolean buried=true;
                    for(var vertex:face.vertices()) {
                        float px=vertex.x()-offset,pz=vertex.z()-.06f,py=vertex.y();
                        buried&=py>=top-.12f&&py<=top+height
                            &&Math.abs(px)<profile(cuff,py,0)*.5f
                            &&pz>bootSurface(cuff,px,py,false)+.0001f
                            &&pz<bootSurface(cuff,px,py,true)-.0001f;
                    }
                    // Only faces fully enclosed by the opaque fur band are removed.
                    // Every visible curl vertex, contour, colour and normal is unchanged.
                    if(!buried)curls.face(face.vertices());
                }
            });
        }
        add(p,bone,FUR_LIGHT,SurfaceRole.DETAIL,curls);
    }

    private static void paddedShaft(List<Piece> p,Bone bone,FacetedMesh.Section[] shell,int color,float offset,float top) {
        for(float y:new float[]{top+.49f,top+1.76f}) {
            float w=profile(shell,y,0)+.08f,d=profile(shell,y,1)+.08f;
            add(p,bone,shade(color,1.07f),SurfaceRole.DETAIL,mesh().openTube(offset,.05f,16,.10f,
                section(y-.16f,w,d,0,0,3.9f),section(y+.07f,w+.12f,d+.12f,0,0,4),
                section(y+.30f,w,d,0,0,3.9f)));
        }
    }

    private static void strap(List<Piece> p,Bone bone,FacetedMesh.Section[] shell,float offset,float y,int color,float side,boolean buckle) {
        float width=profile(shell,y,0),depth=profile(shell,y,1),cz=profile(shell,y,2);
        add(p,bone,color,SurfaceRole.DETAIL,mesh().openTube(offset,cz,20,.12f,
            section(y,width+.16f,depth+.16f,0,0,3.9f),section(y+.41f,width+.15f,depth+.15f,0,0,3.9f)));
        if(buckle) {
            add(p,bone,STEEL,SurfaceRole.DETAIL,patch(shell,offset+side*.95f,y+.20f,.72f,.58f,.23f,false));
            add(p,bone,color,SurfaceRole.DETAIL,patch(shell,offset+side*.95f,y+.20f,.42f,.31f,.30f,false));
            add(p,bone,STEEL,SurfaceRole.DETAIL,patch(shell,offset+side*.95f,y+.20f,.07f,.37f,.34f,false));
        }
    }

    private static float profile(FacetedMesh.Section[] s,float y,int component) {
        int i=0;while(i<s.length-2&&s[i+1].y()<y)i++;
        var a=s[i];var b=s[i+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return switch(component){case 0->a.width()+(b.width()-a.width())*t;case 1->a.depth()+(b.depth()-a.depth())*t;default->a.shiftZ()+(b.shiftZ()-a.shiftZ())*t;};
    }

    private static void laces(List<Piece> p,Bone bone,FacetedMesh.Section[] shell,float offset,float first,float last,int color,int upperColor,boolean eyes) {
        add(p,bone,shade(upperColor,.72f),SurfaceRole.DETAIL,patch(shell,offset,(first+last)*.5f,1.50f,last-first+.42f,.14f,false));
        FacetedMesh laces=mesh();
        int count=7;
        for(int i=0;i<count;i++) {
            float y=first+i*(last-first)/(count-1),next=Math.min(last+.13f,y+.37f);
            float x0=offset-.78f,x1=offset+.78f;
            surfaceLace(laces,shell,x0,y,x1,next);
            surfaceLace(laces,shell,x1,y,x0,next);
            if(eyes)for(float x:new float[]{x0,x1}) {
                add(p,bone,STEEL,SurfaceRole.DETAIL,patch(shell,x,y,.25f,.22f,.21f,false));
                add(p,bone,BLACK,SurfaceRole.DETAIL,patch(shell,x,y,.10f,.085f,.27f,false));
            }
        }
        add(p,bone,color,SurfaceRole.DETAIL,laces);
    }


    /** Chamfered fitted leather/rubber panel. Buried backs are omitted; its visible outline stays faceted. */
    private static FacetedMesh patch(FacetedMesh.Section[] shell,float x,float y,float w,float h,float depth,boolean back) {
        float[][] q={{-.36f,-.5f},{.36f,-.5f},{.5f,-.32f},{.5f,.32f},
                {.36f,.5f},{-.36f,.5f},{-.5f,.32f},{-.5f,-.32f}};
        ModelPart.Vertex[] rim=new ModelPart.Vertex[8],face=new ModelPart.Vertex[8];
        float sign=back?1:-1;
        if(w>.9f||h>.65f)return fittedPanel(shell,x,y,w,h,depth,back);
        for(int i=0;i<8;i++) {
            float px=x+w*q[i][0],py=y+h*q[i][1],z=bootSurface(shell,px,py,back);
            rim[i]=new ModelPart.Vertex(px,py,z-sign*.015f,q[i][0]+.5f,q[i][1]+.5f);
            face[i]=new ModelPart.Vertex(px,py,z+sign*depth*.72f,q[i][0]+.5f,q[i][1]+.5f);
        }
        var center=new ModelPart.Vertex(x,y,bootSurface(shell,x,y,back)+sign*depth,.5f,.5f);
        FacetedMesh result=mesh();
        for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            if(back){result.face(face[i],face[j],center,center);result.face(rim[i],rim[j],face[j],face[i]);}
            else{result.face(face[j],face[i],center,center);result.face(rim[j],rim[i],face[i],face[j]);}
        }
        return result;
    }


    /** A small local fit keeps tall cuffs separate without moving the sole or skeleton. */
    private static List<Piece> fitShaft(List<Piece> source) {
        List<Piece> result=new ArrayList<>();
        for(var p:source) {
            float side=p.bone()==Bone.LEFT_LEG?1:-1,center=side*.25f;
            FacetedMesh fitted=mesh();
            p.geometry().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    var v=face.vertices().clone();
                    for(int i=0;i<v.length;i++) {
                        var a=v[i];float blend=Math.clamp((11.45f-a.y())/1.0f,0,1);
                        v[i]=new ModelPart.Vertex(center+(a.x()-center)*(1-.08f*blend)+side*.17f*blend,
                            a.y(),a.z()*(1-.025f*blend),a.u(),a.v());
                    }
                    fitted.face(v);
                }
            });
            result.add(new Piece(p.bone(),fitted.bake(),p.color(),p.role()));
        }
        return result;
    }

    /** Sample the real polygonal loft rather than its unsampled superellipse. */
    private static float bootSurface(FacetedMesh.Section[] shell,float x,float y,boolean back) {
        int row=0;while(row<shell.length-2&&y>shell[row+1].y())row++;
        var a=shell[row];var b=shell[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return contourSurface(a,x,back)*(1-t)+contourSurface(b,x,back)*t;
    }
    private static float contourSurface(FacetedMesh.Section section,float x,boolean back) {
        float result=back?Float.NEGATIVE_INFINITY:Float.POSITIVE_INFINITY;
        for(int i=0;i<20;i++) {
            double a=-Math.PI*.75+i*Math.PI/10,b=a+Math.PI/10;
            float ax=section.shiftX()+section.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/section.power()),Math.cos(a));
            float bx=section.shiftX()+section.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(b)),2/section.power()),Math.cos(b));
            if(x<Math.min(ax,bx)-1e-5||x>Math.max(ax,bx)+1e-5||Math.abs(bx-ax)<1e-6)continue;
            float az=section.shiftZ()+section.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/section.power()),Math.sin(a));
            float bz=section.shiftZ()+section.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(b)),2/section.power()),Math.sin(b));
            float z=az+(bz-az)*(x-ax)/(bx-ax);
            result=back?Math.max(result,z):Math.min(result,z);
        }
        return Float.isFinite(result)?result:surface(section,x,back);
    }

    /** Subdivisions track every changing instep profile so a patch cannot cut through the upper. */
    private static FacetedMesh fittedPanel(FacetedMesh.Section[] shell,float x,float y,float w,float h,float d,boolean back) {
        java.util.TreeSet<Float> rows=new java.util.TreeSet<>();
        float top=y-h*.5f,bottom=y+h*.5f;
        rows.add(top);rows.add(y-h*.32f);rows.add(y);rows.add(y+h*.32f);rows.add(bottom);
        for(var section:shell)if(section.y()>top&&section.y()<bottom)rows.add(section.y());
        float[] ys=new float[rows.size()];int index=0;for(float row:rows)ys[index++]=row;
        int columns=w>1.7f?5:3;
        ModelPart.Vertex[][] v=new ModelPart.Vertex[ys.length][columns];
        float sign=back?1:-1;
        for(int row=0;row<ys.length;row++) {
            float fraction=Math.clamp((ys[row]-top)/h,0,1);
            float edge=Math.min(fraction,1-fraction);
            float half=w*(.36f+.14f*Math.min(1,edge/.18f));
            for(int col=0;col<columns;col++) {
                float t=col/(float)(columns-1),px=x+(t*2-1)*half;
                float bevel=Math.min(1,Math.min(Math.min(t,1-t)*6,edge*8));
                float relief=.026f+(d-.026f)*(.72f+.12f*(1-Math.abs(t*2-1)))*bevel;
                v[row][col]=new ModelPart.Vertex(px,ys[row],bootSurface(shell,px,ys[row],back)+sign*relief,t,fraction);
            }
        }
        FacetedMesh out=mesh();
        for(int row=0;row<ys.length-1;row++)for(int col=0;col<columns-1;col++) {
            var a=v[row][col];var b=v[row][col+1];var c=v[row+1][col+1];var e=v[row+1][col];
            if(back){out.face(a,b,c,c);out.face(a,c,e,e);}
            else{out.face(c,b,a,a);out.face(e,c,a,a);}
        }
        return out;
    }

    private static void surfaceLace(FacetedMesh out,FacetedMesh.Section[] shell,float x0,float y0,float x1,float y1) {
        for(int i=0;i<4;i++) {
            float a=i/4f,b=(i+1)/4f,ax=x0+(x1-x0)*a,bx=x0+(x1-x0)*b,ay=y0+(y1-y0)*a,by=y0+(y1-y0)*b;
            line(out,ax,ay,bootSurface(shell,ax,ay,false)-.17f,bx,by,bootSurface(shell,bx,by,false)-.17f,.06f);
        }
    }

    /** Eight-sided slender cord between two real shell points; caps are buried in eyelets. */
    private static void line(FacetedMesh out,float ax,float ay,float az,float bx,float by,float bz,float radius) {
        Vector3f d=new Vector3f(bx-ax,by-ay,bz-az).normalize();
        Vector3f u=new Vector3f(0,0,1).cross(d);
        if(u.lengthSquared()<.01f)u.set(0,1,0).cross(d);
        u.normalize().mul(radius);Vector3f v=new Vector3f(d).cross(u).normalize().mul(radius);
        ModelPart.Vertex[] a=new ModelPart.Vertex[8],b=new ModelPart.Vertex[8];
        for(int i=0;i<8;i++) {
            double t=i*Math.PI/4;Vector3f q=new Vector3f(u).mul((float)Math.cos(t)).add(new Vector3f(v).mul((float)Math.sin(t)));
            a[i]=new ModelPart.Vertex(ax+q.x,ay+q.y,az+q.z,i/8f,0);
            b[i]=new ModelPart.Vertex(bx+q.x,by+q.y,bz+q.z,i/8f,1);
        }
        for(int i=0;i<8;i++)out.face(a[i],a[(i+1)%8],b[(i+1)%8],b[i]);
    }

    /** Batch identical material/role surfaces per animated bone without changing any polygon. */
    private static List<Piece> compact(List<Piece> source) {
        record Key(Bone bone,int color,SurfaceRole role) { }
        var grouped=new java.util.LinkedHashMap<Key,FacetedMesh>();
        for(var p:source) {
            FacetedMesh target=grouped.computeIfAbsent(new Key(p.bone(),p.color(),p.role()),k->mesh());
            p.geometry().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    var v=face.vertices();var a=v[0];var b=v[1];var c=v[2];
                    // Joint clipping can produce microscopic lace slivers below the native geometry contract.
                    float area=new Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()).cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z()).lengthSquared();
                    if(area>1e-8f)target.face(v);
                }
            });
        }
        List<Piece> result=new ArrayList<>();
        grouped.forEach((k,g)->result.add(new Piece(k.bone(),g.bake(),k.color(),k.role())));
        return List.copyOf(result);
    }

    private static int shade(int c,float n) {
        return 0xFF000000|Math.min(255,(int)(((c>>>16)&255)*n))<<16
            |Math.min(255,(int)(((c>>>8)&255)*n))<<8|Math.min(255,(int)((c&255)*n));
    }
    private CinematicBoots() { }
}
