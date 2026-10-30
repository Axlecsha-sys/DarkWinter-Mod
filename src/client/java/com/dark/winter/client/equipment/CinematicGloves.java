// Builds the eight authored glove meshes around the shared hand and forearm rig.
package com.dark.winter.client.equipment;

import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;
import static com.dark.winter.client.equipment.NorthernWandererClothing.add;
import static com.dark.winter.client.equipment.NorthernWandererClothing.articulate;
import static com.dark.winter.client.equipment.NorthernWandererClothing.sewn;

/**
 * The eight numbered gloves in the approved DarkWinter sheet. The colors are
 * material colors, never arbitrary item recolors. All shapes share the actual
 * palm and wrist envelope and articulate with the existing forearm.
 */
final class CinematicGloves {
    private static final int KNIT=0xFF37343E, KNIT_RIB=0xFF48454F, TIP=0xFFADA5A0;
    private static final int BROWN=0xFF866249, BROWN_EDGE=0xFF9D795C;
    private static final int OLIVE=0xFF797460, OLIVE_FOLD=0xFF89816B;
    private static final int CANVAS=0xFF9D805C, CANVAS_EDGE=0xFFAD906B;
    private static final int SHELL=0xFFB0ADB2, SHELL_EDGE=0xFFC2BEC0;
    private static final int PALM=0xFF292B30, PALM_EDGE=0xFF383A40;
    private static final int TACTICAL=0xFF41414A, ARMOR=0xFF50505B;
    private static final int MITTEN=0xFF765747, FUR=0xFFC6B8A2, FUR_LIGHT=0xFFD5CAB7;
    private static final int STRAP=0xFF57463D, METAL=0xFF97918B, STITCH=0xFFAB9680;
    private static final Identifier WOOL=material("wool"), LEATHER=material("leather"),
            FABRIC=material("canvas"), RUBBER=material("rubber");
    private static final List<List<Piece>> ALL=build();

    static List<Piece> pieces(int design) {
        if(design<1||design>8)throw new IllegalArgumentException("Glove design "+design);
        return ALL.get(design-1);
    }

    static Identifier texture(int design,int color) {
        if(color==METAL||color==PALM||color==PALM_EDGE||color==ARMOR)return RUBBER;
        if(color==KNIT||color==KNIT_RIB||color==TIP||color==FUR||color==FUR_LIGHT)return WOOL;
        if(color==STRAP||color==BROWN||color==BROWN_EDGE||color==MITTEN||color==STITCH)return LEATHER;
        return design==2||design==4?LEATHER:FABRIC;
    }

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    private static List<List<Piece>> build() {
        List<List<Piece>> result=new ArrayList<>();
        for(int design=1;design<=8;design++) {
            List<Piece> out=new ArrayList<>();
            for(boolean left:new boolean[]{false,true})hand(out,left,design);
            result.add(compact(articulate(out)));
        }
        return List.copyOf(result);
    }

    private static void hand(List<Piece> out,boolean left,int design) {
        Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;
        float inward=left?-1:1;
        int main=switch(design) {
            case 1->KNIT;case 2->BROWN;case 3,5->OLIVE;case 4->MITTEN;
            case 6->TACTICAL;case 7->CANVAS;default->SHELL;
        };
        int edge=switch(design) {
            case 1->KNIT_RIB;case 2->BROWN_EDGE;case 3,5->OLIVE_FOLD;case 4->BROWN;
            case 6->ARMOR;case 7->CANVAS_EDGE;default->SHELL_EDGE;
        };
        boolean gauntlet=design==3||design==5||design==7||design==8;
        float cuffY=gauntlet?8.48f:9.30f;
        float cuffW=gauntlet?4.08f:3.58f,cuffD=gauntlet?4.30f:3.89f;
        FacetedMesh.Section[] cuff={
                section(cuffY,cuffW*.94f,cuffD*.94f,0,-.02f,3.8f),
                section(cuffY+.19f,cuffW,cuffD,0,-.02f,3.8f),
                section(9.70f,3.40f,3.75f,0,-.03f,3.7f),
                section(10.22f,2.91f,3.25f,0,-.04f,3.7f)};
        add(out,bone,main,SurfaceRole.MAIN,mesh().softLoftOpen(0,0,16,cuff));
        add(out,bone,edge,SurfaceRole.DETAIL,mesh().openTube(0,0,16,.13f,
                section(cuffY-.03f,cuffW*.97f,cuffD*.97f,0,-.02f,3.8f),
                section(cuffY+.20f,cuffW*1.012f,cuffD*1.012f,0,-.02f,3.8f)));
        FacetedMesh.Section[] palm={
                section(9.94f,2.93f,3.26f,0,-.04f,3.6f),
                section(10.56f,3.10f,3.32f,0,-.06f,3.8f),
                section(11.22f,3.16f,3.33f,0,-.08f,3.9f),
                section(11.77f,3.01f,3.21f,0,-.09f,3.9f),
                section(12.18f,2.53f,2.80f,0,-.10f,3.7f)};
        add(out,bone,main,SurfaceRole.MAIN,mesh().softLoft(0,0,16,palm));
        // The four fingers remain partly closed around the existing grip. They
        // have distinct end profiles while the continuous glove covers the skin.
        if(design==4) {
            add(out,bone,main,SurfaceRole.MAIN,mesh().softLoft(0,-.06f,16,
                    section(11.20f,3.18f,3.39f,0,0,3.7f),
                    section(12.18f,3.04f,3.23f,0,-.02f,3.6f),
                    section(12.63f,2.55f,2.76f,0,-.03f,3.5f),
                    section(12.78f,1.95f,2.15f,0,-.04f,3.3f)));
        } else if(design==5) {
            add(out,bone,main,SurfaceRole.MAIN,mesh().softLoft(-inward*.34f,-.04f,12,
                    section(11.36f,2.50f,3.30f,0,0,3.7f),
                    section(12.18f,2.47f,3.18f,0,-.05f,3.7f),
                    section(12.65f,2.05f,2.55f,0,-.05f,3.5f)));
            finger(out,bone,inward*1.11f,12.65f,main,edge,design);
        } else {
            for(int i=0;i<4;i++) {
                float x=-1.08f+i*.72f;
                float end=12.54f+(i==1||i==2?.13f:0);
                finger(out,bone,x,end,design==8?PALM:main,edge,design);
            }
        }
        add(out,bone,design==8?PALM:main,SurfaceRole.MAIN,mesh().softLoft(inward*1.20f,-.68f,12,
                section(10.08f,.65f,.99f,0,-.02f,3.3f),
                section(10.55f,1.23f,1.76f,inward*.05f,-.12f,3.5f),
                section(11.36f,1.24f,1.79f,inward*.08f,-.22f,3.5f),
                section(11.94f,.70f,1.06f,inward*.07f,-.16f,3.3f)));
        if(design!=1) {
            add(out,bone,PALM,SurfaceRole.DETAIL,sewn(palm,0,11.12f,2.53f,1.74f,.11f,false));
            add(out,bone,PALM_EDGE,SurfaceRole.DETAIL,sewn(palm,-inward*.62f,10.74f,.76f,.70f,.15f,false));
        }
        switch(design) {
            case 1->{
                ribs(out,bone,cuff,KNIT_RIB,22);
                ribs(out,bone,palm,KNIT_RIB,22);
                add(out,bone,BROWN,SurfaceRole.DETAIL,sewn(cuff,.65f,9.63f,.76f,.52f,.11f,true));
                add(out,bone,BROWN_EDGE,SurfaceRole.DETAIL,sewn(cuff,.65f,9.63f,.58f,.33f,.14f,true));
            }
            case 2->{
                add(out,bone,BROWN_EDGE,SurfaceRole.DETAIL,sewn(palm,0,10.88f,2.47f,1.18f,.17f,true));
                strap(out,bone,cuff,9.67f,3.59f,3.89f,STRAP);
                stitches(out,bone,palm,10.42f,11.38f,1.12f,true);
            }
            case 3,5->{
                for(float y:new float[]{10.46f,11.09f})
                    add(out,bone,edge,SurfaceRole.DETAIL,sewn(palm,0,y,2.81f,.49f,.23f,true));
                strap(out,bone,cuff,9.51f,3.65f,4.01f,STRAP);
                cord(out,bone,inward,cuffY+.33f,cuffW,cuffD);
            }
            case 4->{
                add(out,bone,BROWN,SurfaceRole.DETAIL,sewn(palm,0,11.31f,2.76f,.32f,.20f,true));
                strap(out,bone,cuff,9.91f,3.43f,3.78f,STRAP);
                furCuff(out,bone,cuffY-.12f,cuffW+.13f,cuffD+.13f);
            }
            case 6->{
                add(out,bone,PALM,SurfaceRole.DETAIL,sewn(palm,0,10.88f,2.83f,.82f,.16f,true));
                for(float x:new float[]{-1.0f,-.34f,.34f,1.0f})
                    add(out,bone,ARMOR,SurfaceRole.DETAIL,sewn(palm,x,10.92f,.58f,.67f,.31f,true));
                strap(out,bone,cuff,9.67f,3.59f,3.89f,PALM);
                add(out,bone,BROWN,SurfaceRole.DETAIL,sewn(cuff,.70f,9.65f,.47f,.31f,.27f,true));
            }
            case 7->{
                add(out,bone,CANVAS_EDGE,SurfaceRole.DETAIL,sewn(palm,0,10.89f,2.72f,1.20f,.18f,true));
                strap(out,bone,cuff,9.33f,3.72f,4.10f,STRAP);
                cord(out,bone,inward,cuffY+.25f,cuffW,cuffD);
                stitches(out,bone,palm,10.36f,11.37f,1.18f,true);
            }
            case 8->{
                add(out,bone,PALM_EDGE,SurfaceRole.DETAIL,sewn(palm,0,11.21f,2.77f,.33f,.18f,true));
                add(out,bone,SHELL_EDGE,SurfaceRole.DETAIL,sewn(palm,0,10.67f,2.67f,.68f,.23f,true));
                strap(out,bone,cuff,9.36f,3.72f,4.10f,PALM);
                cord(out,bone,inward,cuffY+.23f,cuffW,cuffD);
            }
            default->throw new AssertionError();
        }
    }

    private static void finger(List<Piece> out,Bone bone,float x,float end,int color,int edge,int design) {
        add(out,bone,color,SurfaceRole.MAIN,mesh().softLoft(x,-.04f,8,
                section(11.45f,.79f,3.24f,0,-.02f,3.8f),
                section(12.17f,.77f,2.99f,0,-.05f,3.8f),
                section(end-.12f,.68f,2.56f,0,-.13f,3.7f),
                section(end,.46f,1.97f,0,-.18f,3.5f)));
        if(design==1) {
            add(out,bone,TIP,SurfaceRole.DETAIL,mesh().softLoft(x,-.04f,8,
                    section(end-.24f,.73f,2.70f,0,-.11f,3.7f),
                    section(end-.10f,.68f,2.55f,0,-.14f,3.7f),
                    section(end+.018f,.47f,1.99f,0,-.18f,3.5f)));
        } else {
            add(out,bone,design==8?SHELL_EDGE:edge,SurfaceRole.DETAIL,
                    mesh().pillow(x,11.79f,1.49f,.57f,.55f,.16f));
        }
    }

    private static void strap(List<Piece> out,Bone bone,FacetedMesh.Section[] cuff,float y,float w,float d,int color) {
        add(out,bone,color,SurfaceRole.DETAIL,mesh().openTube(0,-.03f,16,.10f,
                section(y-.16f,w,d,0,0,3.8f),section(y+.18f,w-.12f,d-.10f,0,0,3.8f)));
        add(out,bone,METAL,SurfaceRole.DETAIL,sewn(cuff,-.42f,y,.69f,.51f,.23f,true));
        add(out,bone,color,SurfaceRole.DETAIL,sewn(cuff,-.42f,y,.41f,.27f,.29f,true));
        add(out,bone,METAL,SurfaceRole.DETAIL,sewn(cuff,-.42f,y,.068f,.37f,.34f,true));
    }

    private static void stitches(List<Piece> out,Bone bone,FacetedMesh.Section[] palm,float top,float bottom,float x,boolean back) {
        for(float y=top;y<bottom;y+=.23f)for(float s:new float[]{-1,1})
            add(out,bone,STITCH,SurfaceRole.DETAIL,sewn(palm,s*x,y,.035f,.105f,.19f,back));
    }

    private static void cord(List<Piece> out,Bone bone,float inward,float y,float w,float d) {
        float x=-inward*(w*.5f-.12f),z=d*.30f;
        add(out,bone,PALM,SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                section(y,.12f,.12f,0,0,2.9f),section(y+.72f,.12f,.12f,-inward*.18f,.17f,2.9f)));
        add(out,bone,PALM_EDGE,SurfaceRole.DETAIL,mesh().pillow(x-inward*.18f,y+.61f,z+.17f,.29f,.38f,.28f));
    }

    private static void furCuff(List<Piece> out,Bone bone,float y,float w,float d) {
        add(out,bone,FUR,SurfaceRole.DETAIL,mesh().openTube(0,0,16,.22f,
                section(y,w,d,0,0,3.6f),section(y+.49f,w+.04f,d+.04f,0,0,3.6f)));
        for(int i=0;i<64;i++) {
            int seed=9137+i*43;
            var p=cuffPoint(w,d,(i+.18f+CinematicHats.scatter(seed)*.50f)/64);
            float yOffset=(CinematicHats.scatter(seed+1)-.5f)*.15f;
            int tint=CinematicHats.scatter(seed+2)<.13f?FUR_LIGHT:FUR;
            add(out,bone,tint,SurfaceRole.DETAIL,CinematicHats.furPuff(p[0],y+.23f+yOffset,p[1],
                    .28f+CinematicHats.scatter(seed+3)*.13f,.42f+CinematicHats.scatter(seed+4)*.10f,
                    .085f+CinematicHats.scatter(seed+5)*.055f,p[2],p[3],
                    CinematicHats.scatter(seed+6)*6.283185f,seed));
        }
    }

    private static float[] cuffPoint(float width,float depth,float fraction) {
        var ring=new ModelPart.Vertex[17];float total=0;
        float[] lengths=new float[16];
        var profile=section(0,width,depth,0,0,3.6f);
        for(int i=0;i<=16;i++)ring[i]=point(profile,-Math.PI*.75+i*Math.PI/8,0,0);
        for(int i=0;i<16;i++) {
            float dx=ring[i+1].x()-ring[i].x(),dz=ring[i+1].z()-ring[i].z();
            total+=lengths[i]=(float)Math.sqrt(dx*dx+dz*dz);
        }
        float distance=(fraction-(float)Math.floor(fraction))*total;
        int edge=0;while(edge<15&&distance>lengths[edge])distance-=lengths[edge++];
        float t=distance/lengths[edge];var a=ring[edge];var b=ring[edge+1];
        return new float[]{a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t,
                (b.z()-a.z())/lengths[edge],-(b.x()-a.x())/lengths[edge]};
    }

    /** Raised knit wales on the actual shell, without stacks of detached tubes. */
    private static void ribs(List<Piece> out,Bone bone,FacetedMesh.Section[] shell,int color,int count) {
        FacetedMesh stitches=mesh();
        for(int i=0;i<count;i++)for(int j=0;j<shell.length-1;j++) {
            double angle=i*Math.PI*2/count;
            var a=point(shell[j],angle-.018,0,0);var b=point(shell[j],angle+.018,1,0);
            var c=point(shell[j+1],angle+.018,1,1);var d=point(shell[j+1],angle-.018,0,1);
            stitches.face(b,a,d,c);
        }
        add(out,bone,color,SurfaceRole.DETAIL,stitches);
    }

    private static ModelPart.Vertex point(FacetedMesh.Section s,double angle,float u,float v) {
        // Match the sixteen-sided base mesh, rather than a round analytical shell.
        double phase=(angle+Math.PI*.75)/(Math.PI*2/16);
        int index=(int)Math.floor(phase);float t=(float)(phase-index),x=0,z=0;
        for(int k=0;k<2;k++) {
            double a=-Math.PI*.75+(index+k)*Math.PI*2/16;float weight=k==0?1-t:t;
            x+=weight*s.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/s.power()),Math.cos(a));
            z+=weight*s.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/s.power()),Math.sin(a));
        }
        float length=(float)Math.sqrt(x*x+z*z);
        return new ModelPart.Vertex(x+s.shiftX()+x/length*.022f,s.y(),z+s.shiftZ()+z/length*.022f,u,v);
    }

    /** Merge immutable geometry by render state; knit stitches do not need one submission each. */
    private static List<Piece> compact(List<Piece> pieces) {
        record Key(Bone bone,int color,SurfaceRole role) { }
        Map<Key,List<ModelPart.Cube>> groups=new LinkedHashMap<>();
        for(var piece:pieces) {
            var cubes=groups.computeIfAbsent(new Key(piece.bone(),piece.color(),piece.role()),key->new ArrayList<>());
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->cubes.add(cube));
        }
        List<Piece> out=new ArrayList<>();
        groups.forEach((key,cubes)->out.add(new Piece(key.bone(),new ModelPart(List.copyOf(cubes),Map.of()),key.color(),key.role())));
        return List.copyOf(out);
    }

    private CinematicGloves() { }
}
