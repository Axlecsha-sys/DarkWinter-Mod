// Builds the eight authored trouser meshes in the existing clothing rig.
package com.dark.winter.client.equipment;

import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;
import static com.dark.winter.client.equipment.NorthernWandererClothing.*;

/** The eight numbered trousers in the approved catalog, in the existing rig space. */
final class CinematicTrousers {
    private static final int CHARCOAL=0xFF46454F, OLIVE=0xFF777565, NAVY=0xFF4C5060,
            SHELL=0xFFA8A6AB, BROWN=0xFF81634F, BIB=0xFF4F5260, QUILT=0xFF757565,
            SCOUT=0xFF46464F, PANEL=0xFF34363F, BELT=0xFF383232, BUCKLE=0xFF978675,
            RIB=0xFF36353D, SNOW_DUST=0xFFC5C9CD;
    private static final int[] COLORS={0,CHARCOAL,OLIVE,NAVY,SHELL,BROWN,BIB,QUILT,SCOUT};
    private static final Identifier CANVAS=material("canvas"), WOOL=material("wool"),
            LEATHER=material("leather"), RUBBER=material("rubber");
    private static final List<List<Piece>> ALL=create();

    static List<Piece> pieces(int design) {
        if(design<1||design>8)throw new IllegalArgumentException("Trouser design must be 1..8");
        return ALL.get(design-1);
    }

    static Identifier texture(int design,int color) {
        if(color==RIB)return WOOL;
        if(color==BELT||color==BUCKLE)return LEATHER;
        if(color==SNOW_DUST)return RUBBER;
        return CANVAS;
    }

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    private static List<List<Piece>> create() {
        List<List<Piece>> result=new ArrayList<>();
        for(int design=1;design<=8;design++) {
            List<Piece> p=new ArrayList<>();
            build(p,design);
            result.add(compact(articulate(p)));
        }
        return List.copyOf(result);
    }

    private static FacetedMesh.Section[] seat() {
        return new FacetedMesh.Section[]{
            section(8.65f,7.65f,4.70f,0,.02f,3.5f),section(9.65f,7.83f,5.05f,0,.06f,3.6f),
            section(10.85f,7.84f,5.12f,0,.08f,3.6f),section(11.45f,7.60f,4.9f,0,.07f,3.4f),
            section(12.08f,6.65f,3.95f,0,.03f,3.0f),section(12.42f,4.85f,2.3f,0,0,2.7f)};
    }

    private static FacetedMesh.Section[] leg(int design,float side) {
        // Deliberately retain a broad thigh, tucked knee and full calf, not two cylinders.
        float full=design==3?.16f:design==7?.10f:design==4?-.10f:0;
        List<FacetedMesh.Section> s=new ArrayList<>(List.of(
            section(-1.35f,.65f,.85f,0,0,2.8f),section(-.82f,2.90f,3.45f,0,.04f,3.2f),
            section(-.1f,3.95f+full,4.5f+full,0,.04f,3.6f),
            section(1.55f,4.10f+full,4.65f+full,side*.025f,.04f,3.7f)));
        if(design==3) {
            // Four actual padded lobes: broad flat planes, compressed stitched boundaries.
            for(float y:new float[]{2.9f,4.55f,6.3f,8.05f}) {
                float w=y<5?3.94f:y<7?3.65f:3.58f;
                s.add(section(y-.44f,w+.22f,w+.65f,0,-.02f,3.7f));
                s.add(section(y+.24f,w+.21f,w+.62f,0,-.01f,3.7f));
                s.add(section(y+.51f,w-.10f,w+.26f,0,0,3.5f));
            }
        } else {
            s.addAll(List.of(section(3.7f,3.93f+full,4.44f+full,side*.035f,-.02f,3.6f),
                section(5.25f,3.62f+full,4.05f+full,0,-.1f,3.4f),
                section(6.0f,3.71f+full,4.15f+full,0,-.15f,3.6f),
                section(6.9f,3.55f+full,3.97f+full,0,-.08f,3.5f),
                section(8.3f,3.70f+full,4+full,0,.08f,3.4f)));
        }
        s.addAll(List.of(section(9.65f,3.45f+full,3.76f+full,0,.035f,3.4f),
            section(10.5f,3.16f,3.43f,0,.02f,3.3f),section(11.65f,3.0f,3.30f,0,0,3.2f)));
        return s.toArray(FacetedMesh.Section[]::new);
    }

    private static void build(List<Piece> p,int design) {
        int color=COLORS[design];
        var seat=seat();
        add(p,Bone.BODY,color,SurfaceRole.MAIN,mesh().softLoft(0,0,20,seat));
        // Functional-looking belt and loops preserve the same waist scale on every numbered design.
        add(p,Bone.BODY,BELT,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.10f,
            section(8.68f,7.79f,4.84f,0,.02f,3.5f),section(9.25f,7.94f,5.11f,0,.04f,3.6f)));
        add(p,Bone.BODY,BUCKLE,SurfaceRole.DETAIL,sewn(seat,-.35f,8.99f,.83f,.65f,.28f,false));
        add(p,Bone.BODY,BELT,SurfaceRole.DETAIL,sewn(seat,-.35f,8.99f,.52f,.35f,.34f,false));
        for(float x:new float[]{-3.05f,-1.7f,1.7f,3.05f})for(boolean back:new boolean[]{false,true})
            add(p,Bone.BODY,shade(color,1.06f),SurfaceRole.DETAIL,sewn(seat,x,8.99f,.23f,.75f,.19f,back));
        add(p,Bone.BODY,shade(color,.82f),SurfaceRole.PLACKET,sewn(seat,.12f,10.4f,.13f,1.65f,.07f,false));
        for(float x:new float[]{-2.25f,2.25f}) {
            add(p,Bone.BODY,shade(color,.94f),SurfaceRole.POCKET,sewn(seat,x,10.7f,1.8f,1.62f,.14f,true));
            add(p,Bone.BODY,shade(color,1.06f),SurfaceRole.FLAP,sewn(seat,x,10.02f,1.91f,.34f,.23f,true));
        }
        if(design==6)bib(p,color);
        for(boolean left:new boolean[]{false,true}) {
            float side=left?1:-1;
            Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;
            var leg=leg(design,side);
            add(p,bone,color,SurfaceRole.MAIN,mesh().softLoft(0,0,16,leg));
            sidePocket(p,bone,color,side,2.45f,2.57f,design==8?1.9f:2.65f,design==4?.28f:.47f);
            if(design==1)sidePocket(p,bone,color,side,7.08f,1.72f,2.38f,.40f);
            if(design==4||design==5||design==8) {
                float h=design==4?4.12f:design==5?2.50f:2.11f;
                float y=design==4?7.37f:6.16f;
                add(p,bone,PANEL,SurfaceRole.DETAIL,sewn(leg,0,y,3.05f,h,.25f,false));
                add(p,bone,shade(color,.86f),SurfaceRole.DETAIL,sewn(leg,0,y-h*.43f,2.74f,.13f,.31f,false));
                if(design==5)for(float x:new float[]{-1.20f,1.20f})for(float dy:new float[]{-.84f,.84f})
                    add(p,bone,BUCKLE,SurfaceRole.DETAIL,sewn(leg,x,y+dy,.13f,.14f,.33f,false));
            }
            if(design==3)for(float y:new float[]{3.41f,5.06f,6.81f,8.56f})
                add(p,bone,shade(color,.72f),SurfaceRole.DETAIL,mesh().openTube(0,0,16,.055f,
                    profile(leg,y-.045f,.035f),profile(leg,y+.045f,.035f)));
            else if(design==7)diamondQuilt(p,bone,leg,color);
            else {
                // A few compressed cloth folds are integral to the heavy trouser silhouette.
                for(float y:new float[]{4.9f,6.8f,8.5f})
                    add(p,bone,shade(color,1.07f),SurfaceRole.DETAIL,sewn(leg,side*.21f,y,2.6f,.20f,.12f,false));
                add(p,bone,shade(color,.86f),SurfaceRole.DETAIL,sewn(leg,0,5.12f,2.75f,.13f,.10f,true));
            }
            add(p,bone,RIB,SurfaceRole.DETAIL,mesh().openTube(0,.02f,16,.11f,
                section(10.07f,3.35f,3.65f,0,0,3.4f),section(10.73f,3.22f,3.51f,0,0,3.3f)));
            for(int i=0;i<14;i++) {
                double a=i*Math.PI*2/14;float x=1.69f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/3.4),Math.cos(a));
                float z=.02f+1.84f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/3.4),Math.sin(a));
                add(p,bone,shade(RIB,1.13f),SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                    section(10.10f,.055f,.055f),section(10.64f,.055f,.055f)));
            }
            // Sparse little lodged flakes on upper pocket seams; never repaint the garment white.
            for(int i=0;i<3;i++)
                add(p,bone,SNOW_DUST,SurfaceRole.DETAIL,sewn(leg,side*(.75f+i*.19f),2.52f-i*.035f,.12f,.06f,.06f,false));
        }
    }

    private static void sidePocket(List<Piece> p,Bone bone,int color,float side,float top,float height,float depth,float thickness) {
        float x=side*1.94f;
        add(p,bone,shade(color,.87f),SurfaceRole.POCKET,mesh().softLoft(x,-.22f,12,
            section(top,.15f,depth*.84f,0,0,3.8f),
            section(top+.20f,thickness,depth,side*.06f,0,4.0f),
            section(top+height-.22f,thickness+.04f,depth,side*.06f,0,4.0f),
            section(top+height,.14f,depth*.85f,0,0,3.8f)));
        add(p,bone,shade(color,1.06f),SurfaceRole.FLAP,mesh().softLoft(x+side*.12f,-.22f,12,
            section(top-.07f,.22f,depth*.94f,0,0,4.0f),
            section(top+.08f,thickness+.20f,depth+ .11f,side*.045f,0,4.0f),
            section(top+.52f,thickness+.15f,depth+.05f,side*.045f,0,4.0f),
            section(top+.64f,.18f,depth*.88f,0,0,3.8f)));
        for(float z:new float[]{-.22f-depth*.32f,-.22f+depth*.32f})
            add(p,bone,BUCKLE,SurfaceRole.DETAIL,mesh().softLoft(x+side*(thickness*.5f+.24f),z,8,
                section(top+.24f,.085f,.15f),section(top+.41f,.085f,.15f)));
    }

    private static void bib(List<Piece> p,int color) {
        // The bib is its own BODY shell inside outer jackets, not geometry across the leg joint.
        FacetedMesh.Section[] bib={
            section(.28f,5.95f,4.31f,0,.02f,3.6f),section(2.35f,6.75f,4.45f,0,.02f,3.6f),
            section(4.8f,6.60f,4.31f,0,.03f,3.6f),section(7.0f,6.82f,4.19f,0,.03f,3.5f),
            section(8.75f,7.65f,4.80f,0,.04f,3.5f)};
        add(p,Bone.BODY,color,SurfaceRole.MAIN,mesh().softLoftOpen(0,0,20,bib));
        add(p,Bone.BODY,shade(color,.9f),SurfaceRole.POCKET,sewn(bib,0,4.25f,4.25f,2.75f,.31f,false));
        add(p,Bone.BODY,shade(color,1.1f),SurfaceRole.FLAP,sewn(bib,0,3.1f,4.3f,.55f,.39f,false));
        for(float x:new float[]{-1.62f,1.62f})
            add(p,Bone.BODY,BUCKLE,SurfaceRole.DETAIL,sewn(bib,x,3.16f,.16f,.17f,.47f,false));
        for(float x:new float[]{-2.39f,2.39f}) {
            add(p,Bone.BODY,shade(color,.82f),SurfaceRole.DETAIL,mesh().softLoft(x,0,12,
                section(-1.5f,.55f,3.78f,0,.10f,4),
                section(-.85f,.62f,4.28f,0,.04f,4),
                section(.45f,.60f,4.47f,0,.02f,4),
                section(1.65f,.56f,4.45f,0,.02f,4)));
            add(p,Bone.BODY,BUCKLE,SurfaceRole.DETAIL,sewn(bib,x,1.2f,.69f,.59f,.23f,false));
            add(p,Bone.BODY,BELT,SurfaceRole.DETAIL,sewn(bib,x,1.2f,.42f,.28f,.29f,false));
        }
    }

    private static FacetedMesh.Section profile(FacetedMesh.Section[] s,float y,float extra) {
        int i=0;while(i<s.length-2&&s[i+1].y()<y)i++;
        var a=s[i];var b=s[i+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return section(y,a.width()+(b.width()-a.width())*t+extra,
            a.depth()+(b.depth()-a.depth())*t+extra,a.shiftX()+(b.shiftX()-a.shiftX())*t,
            a.shiftZ()+(b.shiftZ()-a.shiftZ())*t,a.power()+(b.power()-a.power())*t);
    }

    private static void diamondQuilt(List<Piece> p,Bone bone,FacetedMesh.Section[] leg,int color) {
        // The reference's diagonal quilting follows both broad faces, not a different camouflage.
        FacetedMesh seams=mesh();
        for(boolean back:new boolean[]{false,true})for(int dir:new int[]{-1,1})
            for(float base=-1.3f;base<11.0f;base+=2.20f) {
                for(int i=0;i<6;i++) {
                    float x0=-1.50f+i*.50f,x1=x0+.50f;
                    float y0=base+dir*x0*.95f,y1=base+dir*x1*.95f;
                    if(Math.min(y0,y1)<.2f||Math.max(y0,y1)>9.8f)continue;
                    ribbon(seams,leg,x0,y0,x1,y1,.045f,back);
                }
            }
        add(p,bone,shade(color,.70f),SurfaceRole.DETAIL,seams);
    }

    private static void ribbon(FacetedMesh out,FacetedMesh.Section[] shell,float x0,float y0,float x1,float y1,float w,boolean back) {
        float[] xs={x0-w,x0+w,x1+w,x1-w},ys={y0,y0,y1,y1};
        ModelPart.Vertex[] v=new ModelPart.Vertex[4];
        for(int i=0;i<4;i++)v[i]=new ModelPart.Vertex(xs[i],ys[i],
            surface(shell,xs[i],ys[i],back)+(back?.034f:-.034f),i==0||i==3?0:1,i<2?0:1);
        boolean forwardWinding=(y1>y0);
        if(forwardWinding==back)out.face(v);else out.face(v[3],v[2],v[1],v[0]);
    }


    /** Batch identical material/role surfaces per animated bone without changing any polygon. */
    private static List<Piece> compact(List<Piece> source) {
        record Key(Bone bone,int color,SurfaceRole role) { }
        var grouped=new java.util.LinkedHashMap<Key,FacetedMesh>();
        for(var p:source) {
            FacetedMesh target=grouped.computeIfAbsent(new Key(p.bone(),p.color(),p.role()),k->mesh());
            p.geometry().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons)target.face(face.vertices());
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
    private CinematicTrousers() { }
}
