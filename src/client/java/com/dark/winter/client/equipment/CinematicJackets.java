// Builds the eight authored jacket meshes without changing shared rig coordinates.
package com.dark.winter.client.equipment;

import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;
import static com.dark.winter.client.equipment.NorthernWandererClothing.add;
import static com.dark.winter.client.equipment.NorthernWandererClothing.sewn;
import static com.dark.winter.client.equipment.NorthernWandererClothing.surface;

/**
 * The eight numbered jackets on the approved DarkWinter jacket sheet.
 * Their cut, palette and construction are deliberately fixed, not randomized.
 * Original shared meshes, first-person bodies and movement physics are untouched.
 */
final class CinematicJackets {
    private static final int LEATHER=0xFF634B3B, LEATHER_EDGE=0xFF79604C,
            DARK_LEATHER=0xFF4C3D36, KNIT=0xFF303039, KNIT_RIB=0xFF3A3941,
            FUR=0xFFB9AA96, FUR_LIGHT=0xFFCCBDA9, FUR_SHADOW=0xFF9C8C7B,
            METAL=0xFF9E998E, ZIP=0xFF77736B, SNOW=0xFFD1D4D4,
            SHELL_PANEL=0xFF515661;
    private static final int[] COLORS={0,0xFF716E5B,0xFF484952,0xFF79543F,
            0xFF6E6D5C,0xFF99744F,0xFF414E65,0xFF70715B,0xFFA5A3A3};
    private static final Identifier CANVAS=material("canvas"), WOOL=material("wool"),
            LEATHER_TEXTURE=material("leather"), RUBBER=material("rubber");
    private static final Map<Integer,List<Piece>> DESIGNS=create();

    static List<Piece> pieces(int design) { return DESIGNS.getOrDefault(design,List.of()); }

    static Identifier texture(int design,int color) {
        if(color==LEATHER||color==LEATHER_EDGE||color==DARK_LEATHER)return LEATHER_TEXTURE;
        if(color==KNIT||color==KNIT_RIB||color==FUR||color==FUR_LIGHT||color==FUR_SHADOW)return WOOL;
        if(color==METAL||color==ZIP||color==SNOW)return RUBBER;
        return CANVAS;
    }

    /** Matching attachment surface in the original body coordinates. */
    static float shellFront(int design,float x,float y) {return surface(body(design),x,y,false);}
    static float shellBack(int design,float x,float y) {return surface(body(design),x,y,true);}

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    private static Map<Integer,List<Piece>> create() {
        Map<Integer,List<Piece>> result=new LinkedHashMap<>();
        for(int design=1;design<=8;design++) {
            List<Piece> out=new ArrayList<>();
            FacetedMesh.Section[] shell=body(design);
            int color=COLORS[design];
            add(out,Bone.BODY,color,SurfaceRole.MAIN,mesh().softLoftOpen(0,0,20,shell));
            hem(out,shell,color,design);
            sleeves(out,design);
            switch(design) {
                case 1->expedition(out,shell,color);
                case 2->quilted(out,shell,color);
                case 3->furCollar(out,shell,color);
                case 4->military(out,shell,color);
                case 5->workwear(out,shell,color);
                case 6->anorak(out,shell,color);
                case 7->bomber(out,shell,color);
                case 8->survivalShell(out,shell,color);
                default->throw new AssertionError();
            }
            lightSnow(out,shell,design);
            result.put(design,cleanNativeFaces(NorthernWandererClothing.articulate(out)));
        }
        return Map.copyOf(result);
    }

    /** Surface fitting can collapse an invisible cap sliver inside a sewn pocket.
     * Remove only sub-.001 model-unit triangles after joint clipping; keep valid quads intact.
     */
    private static List<Piece> cleanNativeFaces(List<Piece> pieces) {
        List<Piece> clean=new ArrayList<>();
        for(Piece piece:pieces) {
            FacetedMesh m=mesh();
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    var v=face.vertices();
                    if(areaSquared(v[0],v[1],v[2])>1e-7f)m.face(v);
                    else if(areaSquared(v[0],v[2],v[3])>1e-7f)m.face(v[0],v[2],v[3],v[3]);
                }
            });
            var geometry=m.bake();
            if(!geometry.isEmpty())clean.add(new Piece(piece.bone(),geometry,piece.color(),piece.role()));
        }
        return compact(clean);
    }

    /** Batch by render state while reusing the exact immutable cubes, faces, UVs and normals. */
    private static List<Piece> compact(List<Piece> pieces) {
        record Key(Bone bone,int color,SurfaceRole role) { }
        Map<Key,List<ModelPart.Cube>> groups=new LinkedHashMap<>();
        for(Piece piece:pieces) {
            var cubes=groups.computeIfAbsent(new Key(piece.bone(),piece.color(),piece.role()),
                key->new ArrayList<>());
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
                if(!path.isEmpty())throw new IllegalArgumentException("Garment batches must be bone-local");
                cubes.add(cube);
            });
        }
        List<Piece> result=new ArrayList<>();
        groups.forEach((key,cubes)->result.add(new Piece(key.bone(),
            new ModelPart(List.copyOf(cubes),Map.of()),key.color(),key.role())));
        return List.copyOf(result);
    }

    private static float areaSquared(ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        return new org.joml.Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z())
            .cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z()).lengthSquared();
    }

    private static float hemY(int design) {
        return switch(design){case 3,5,7->10.25f;case 4,6->12.0f;case 8->11.45f;default->11.55f;};
    }

    private static FacetedMesh.Section[] body(int design) {
        float b=switch(design){case 1->.23f;case 2->.05f;case 3->.12f;case 4->.10f;
            case 5->.02f;case 6->.20f;case 7->.34f;default->-.03f;};
        float h=hemY(design);
        return new FacetedMesh.Section[]{
            section(-2.0f,4.95f,4.50f,0,.08f,3.9f),
            section(-1.25f,7.6f+b,5.10f+b,0,.07f,4.0f),
            section(-.55f,10.05f+b,5.65f+b,0,.04f,4.1f),
            section(.85f,10.12f+b,5.95f+b,0,.02f,4.1f),
            section(2.80f,9.72f+b,5.97f+b,0,0,4.0f),
            section(5.0f,9.22f+b,5.64f+b,0,.02f,3.9f),
            section(7.20f,8.90f+b,5.42f+b,0,.04f,3.9f),
            section(8.65f,9.15f+b,5.77f+b,0,.05f,4.0f),
            section(h-.40f,9.43f+b,6.00f+b,0,.05f,4.0f),
            section(h,9.35f+b,5.94f+b,0,.05f,4.0f)};
    }

    private static void hem(List<Piece> out,FacetedMesh.Section[] shell,int color,int design) {
        float h=hemY(design);
        if(design==4||design==6) {
            // Four skirt quadrants are sewn to the hip overlap and follow each thigh.
            // This preserves the long coat in a stride without making a rigid tube around both legs.
            skirt(out,design);
        } else if(design==3||design==5||design==7) {
            var s=shell[shell.length-1];
            add(out,Bone.BODY,KNIT,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.11f,
                section(h-.04f,s.width()+.02f,s.depth()+.02f,0,.05f,4),
                section(h+.78f,s.width()-.26f,s.depth()-.14f,0,.05f,4)));
            for(int i=-13;i<=13;i++) {
                float x=i*.30f;
                strip(out,Bone.BODY,KNIT_RIB,shell,x,h-.07f,x,h+.60f,.055f,.038f,false);
                strip(out,Bone.BODY,KNIT_RIB,shell,x,h-.07f,x,h+.60f,.055f,.038f,true);
            }
        } else {
            add(out,Bone.BODY,shade(color,.87f),SurfaceRole.DETAIL,
                sewn(shell,0,h-.23f,8.1f,.24f,.09f,false));
            add(out,Bone.BODY,shade(color,.87f),SurfaceRole.DETAIL,
                sewn(shell,0,h-.23f,8.1f,.24f,.09f,true));
        }
    }

    private static void skirt(List<Piece> out,int design) {
        int color=COLORS[design];float end=design==4?4.4f:3.1f;
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;float side=left?1:-1;
            FacetedMesh.Section[] shell={
                section(-.45f,5.04f,6.28f,side*.08f,.05f,4),
                section(.35f,5.20f,6.42f,side*.12f,.08f,4),
                section(end-.36f,5.35f,6.48f,side*.18f,.10f,4),
                section(end-.12f,5.33f,6.46f,side*.19f,.10f,4),
                section(end,5.23f,6.36f,side*.20f,.10f,4)};
            // One continuous front/outer-side/back cloth surface. The inner vent stays open.
            // Shared edge vertices remove the former disconnected flat side-panel corners.
            skirtSurface(out,bone,color,shell,side);
            skirtSurface(out,bone,shade(color,.86f),new FacetedMesh.Section[]{
                sample(shell,end-.16f,.025f),sample(shell,end-.04f,.025f)},side);
        }
    }

    private static void skirtSurface(List<Piece> out,Bone bone,int color,
            FacetedMesh.Section[] shell,float side) {
        FacetedMesh shape=mesh();int steps=24;
        for(int row=0;row<shell.length-1;row++)for(int k=0;k<steps;k++) {
            double a=Math.toRadians(-121+242*k/(double)steps);
            double b=Math.toRadians(-121+242*(k+1)/(double)steps);
            float[][] p={skirtPoint(shell[row],side,a),skirtPoint(shell[row],side,b),
                skirtPoint(shell[row+1],side,b),skirtPoint(shell[row+1],side,a)};
            face(shape,p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
                p[2][0],p[2][1],p[2][2],p[3][0],p[3][1],p[3][2],side<0);
        }
        add(out,bone,color,SurfaceRole.MAIN,shape);
    }

    private static float[] skirtPoint(FacetedMesh.Section s,float side,double angle) {
        float x=(float)Math.copySign(Math.pow(Math.abs(Math.cos(angle)),2/s.power()),Math.cos(angle));
        float z=(float)Math.copySign(Math.pow(Math.abs(Math.sin(angle)),2/s.power()),Math.sin(angle));
        return new float[]{side*x*s.width()*.5f+s.shiftX(),s.y(),z*s.depth()*.5f+s.shiftZ()};
    }

    private static void sleeves(List<Piece> out,int design) {
        int color=COLORS[design];
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;
            float inward=left?-.60f:.60f;
            float b=design==7?.22f:design==1?.16f:design==8?-.10f:0;
            FacetedMesh.Section[] s={
                section(-1.65f,.80f,1.0f,inward*2.1f,.03f,3.4f),
                section(-1.2f,3.18f+b,3.6f+b,inward*1.4f,.03f,3.7f),
                section(-.70f,4.32f+b,4.68f+b,inward,.02f,3.9f),
                section(.25f,4.57f+b,4.95f+b,inward*.5f,0,4),
                section(1.70f,4.60f+b,4.93f+b,inward*.15f,0,4),
                section(3.25f,4.32f+b,4.61f+b,0,.05f,3.9f),
                section(4.45f,4.08f+b,4.28f+b,0,.12f,3.8f),
                section(5.45f,3.72f+b,3.96f+b,0,.12f,3.8f),
                section(6.55f,3.96f+b,4.20f+b,0,.03f,3.9f),
                section(7.9f,3.78f+b,4.00f+b,0,-.02f,3.9f),
                section(9.30f,3.47f+b,3.66f+b,0,-.02f,3.9f),
                section(10.1f,3.36f+b,3.58f+b,0,-.02f,3.8f)};
            add(out,bone,color,SurfaceRole.MAIN,mesh().softLoft(0,0,16,s));
            add(out,bone,KNIT,SurfaceRole.DETAIL,mesh().openTube(0,-.02f,16,.12f,
                section(9.98f,3.32f,3.54f,0,0,3.9f),section(10.60f,3.13f,3.38f,0,0,3.8f)));
            if(design==2) {
                for(boolean back:new boolean[]{false,true})for(int row=0;row<5;row++)
                    for(int col=-1;col<=1;col++)
                        quiltCell(out,bone,(row+col)%3==0?shade(color,1.015f):color,s,
                            col*1.03f,1.10f+row*1.75f,1.025f,1.745f,.070f,back);
            } else if(design==7) {
                for(float y:new float[]{1.05f,2.75f,4.45f,6.15f,7.85f,9.20f})
                    baffle(out,bone,color,s,y,1.69f,16);
            } else {
                for(float y:new float[]{3.1f,6.8f,8.5f}) {
                    strip(out,bone,shade(color,.88f),s,-1.38f,y-.16f,1.38f,y+.16f,.065f,.055f,false);
                    strip(out,bone,shade(color,1.07f),s,-1.20f,y-.01f,1.20f,y+.21f,.07f,.065f,false);
                }
            }
            if(design==8) {
                panel(out,bone,SHELL_PANEL,s,0,1.4f,3.45f,3.8f,.16f,true);
                panel(out,bone,SHELL_PANEL,s,0,6.10f,2.75f,2.65f,.15f,true);
            }
            if(design==1||design==4||design==5||design==8) {
                add(out,bone,shade(color,.90f),SurfaceRole.DETAIL,mesh().openTube(0,-.02f,16,.08f,
                    section(9.22f,3.54f+b,3.77f+b,0,0,3.9f),
                    section(9.89f,3.48f+b,3.73f+b,0,0,3.9f)));
                panel(out,bone,design==8?SHELL_PANEL:shade(color,1.04f),s,left?.52f:-.52f,9.55f,2.20f,.45f,.22f,false);
                button(out,bone,s,left?1.14f:-1.14f,9.55f,.20f,.31f,false);
            }
            if(design==7) {
                panel(out,bone,shade(color,.88f),s,left?1.32f:-1.32f,2.5f,1.30f,2.55f,.26f,false);
                strip(out,bone,ZIP,s,left?1.37f:-1.37f,1.40f,left?1.37f:-1.37f,3.55f,.10f,.31f,false);
            }
        }
    }

    private static void expedition(List<Piece> out,FacetedMesh.Section[] s,int color) {
        hood(out,color,true);
        zipper(out,s,color);
        pockets(out,s,color,8.75f,3.0f,3.25f,true);
        for(int side:new int[]{-1,1}) {
            float x=side*2.68f;
            panel(out,Bone.BODY,LEATHER,s,x,1.85f,.76f,4.38f,.23f,false);
            buckle(out,s,x,1.30f,.67f,.74f,.33f,false);
            buckle(out,s,x,2.85f,.67f,.74f,.33f,false);
            panel(out,Bone.BODY,LEATHER_EDGE,s,x,3.78f,.54f,.72f,.28f,false);
            button(out,Bone.BODY,s,x,4.02f,.14f,.39f,false);
        }
        panel(out,Bone.BODY,LEATHER,s,0,2.35f,7.95f,.79f,.20f,true);
        panel(out,Bone.BODY,LEATHER_EDGE,s,0,3.02f,.58f,1.35f,.24f,true);
        buckle(out,s,0,3.58f,.65f,.60f,.33f,true);
        pocket(out,s,color,0,8.85f,6.25f,2.95f,.46f,true,2);
    }

    private static void quilted(List<Piece> out,FacetedMesh.Section[] s,int color) {
        // Broad padded cells with recessed stitching, not pointed diamond/pyramid centres.
        // The approved square grid remains intact; diagonal cloth folds catch the light.
        for(boolean back:new boolean[]{false,true}) {
            for(int row=0;row<6;row++)for(int col=-3;col<=3;col++) {
                // Skip padded cloth hidden by the leather yoke or cargo pockets.
                // It must not punch through these thinner sewn outer details.
                if(back&&row==0)continue;
                if(!back&&((row==1&&Math.abs(col)==2)||(row==5&&Math.abs(col)>=2)))continue;
                quiltCell(out,Bone.BODY,(row+col)%4==0?shade(color,1.015f):color,s,
                    col*1.12f,1.08f+row*1.62f,1.115f,1.615f,.080f,back);
            }
            for(int row=0;row<7;row++)
                strip(out,Bone.BODY,shade(color,.94f),s,-3.91f,.27f+row*1.62f,
                    3.91f,.27f+row*1.62f,.022f,.012f,back);
            for(int col=-3;col<=4;col++)
                strip(out,Bone.BODY,shade(color,.94f),s,col*1.12f-.56f,.30f,
                    col*1.12f-.56f,10.78f,.022f,.012f,back);
        }
        standCollar(out,color,.80f,false);
        shearlingArc(out,2.72f,2.58f,2.55f,.10f,-1.80f,-.55f,.34f,.38f,20);
        panel(out,Bone.BODY,DARK_LEATHER,s,0,.45f,8.95f,2.0f,.14f,true);
        strip(out,Bone.BODY,LEATHER_EDGE,s,-4.1f,1.4f,4.1f,1.4f,.08f,.21f,true);
        zipper(out,s,color);
        pockets(out,s,color,8.7f,2.9f,3.15f,true);
        for(int side:new int[]{-1,1})pocket(out,s,color,side*2.62f,3.20f,2.30f,2.45f,.26f,false,1);
        panel(out,Bone.BODY,LEATHER,s,0,-.35f,.52f,1.0f,.23f,true);
    }

    private static void furCollar(List<Piece> out,FacetedMesh.Section[] s,int color) {
        standCollar(out,color,1.25f,false);
        shearlingArc(out,3.37f,2.82f,2.85f,.15f,-1.25f,-.60f,.64f,.72f,24);
        shearlingLapels(out,s,3.25f,1.0f);
        zipper(out,s,color);
        pockets(out,s,color,8.10f,2.80f,2.65f,true);
        for(int side:new int[]{-1,1})pocket(out,s,color,side*2.6f,3.30f,2.14f,2.20f,.26f,false,1);
        panel(out,Bone.BODY,DARK_LEATHER,s,0,1.10f,8.55f,.60f,.13f,true);
        panel(out,Bone.BODY,LEATHER,s,0,1.92f,.55f,1.30f,.21f,true);
        buckle(out,s,0,2.58f,.65f,.60f,.30f,true);
    }

    private static void military(List<Piece> out,FacetedMesh.Section[] s,int color) {
        standCollar(out,color,1.1f,false);
        lapels(out,s,shade(color,1.08f),2.55f,4.55f,1.35f);
        panel(out,Bone.BODY,shade(color,1.04f),s,.38f,6.72f,3.4f,9.65f,.13f,false);
        for(int side:new int[]{-1,1})for(float y:new float[]{2.5f,4.30f,6.1f,8.20f,10.4f})
            button(out,Bone.BODY,s,side*1.39f,y,.29f,.29f,false);
        belt(out,s,5.73f,.74f);
        buckle(out,s,0,5.73f,1.20f,.88f,.38f,false);
        pockets(out,s,color,9.05f,2.85f,2.90f,true);
        for(int side:new int[]{-1,1}) {
            strip(out,Bone.BODY,shade(color,.84f),s,side*2.38f,2.30f,side*2.52f,5.20f,.08f,.08f,false);
            panel(out,Bone.BODY,shade(color,1.10f),s,side*3.65f,-.30f,1.60f,.55f,.18f,false);
            button(out,Bone.BODY,s,side*3.03f,-.30f,.20f,.25f,false);
        }
        strip(out,Bone.BODY,shade(color,.82f),s,0,1.5f,0,11.8f,.07f,.055f,true);
    }

    private static void workwear(List<Piece> out,FacetedMesh.Section[] s,int color) {
        standCollar(out,color,1.15f,false);
        shearlingArc(out,3.25f,2.70f,2.72f,.15f,-1.16f,-.55f,.60f,.68f,24);
        shearlingLapels(out,s,2.85f,.94f);
        panel(out,Bone.BODY,DARK_LEATHER,s,0,.70f,8.75f,1.65f,.13f,true);
        for(int side:new int[]{-1,1}) {
            panel(out,Bone.BODY,DARK_LEATHER,s,side*2.40f,.48f,3.35f,1.55f,.13f,false);
            pocket(out,s,color,side*2.55f,3.22f,2.25f,2.22f,.25f,false,1);
        }
        zipper(out,s,color);
        pockets(out,s,color,8.08f,2.75f,2.55f,true);
        strip(out,Bone.BODY,shade(color,.83f),s,-4.15f,1.75f,4.15f,1.75f,.07f,.06f,true);
        for(int side:new int[]{-1,1})strip(out,Bone.BODY,shade(color,.85f),s,side*3.5f,3.9f,side*3.65f,9.9f,.07f,.05f,true);
    }

    private static void anorak(List<Piece> out,FacetedMesh.Section[] s,int color) {
        hood(out,color,false);
        standCollar(out,color,1.35f,false);
        zipper(out,s,color);
        pockets(out,s,color,9.1f,2.95f,3.20f,true);
        for(int side:new int[]{-1,1}) {
            pocket(out,s,color,side*2.55f,3.32f,2.17f,2.25f,.25f,false,1);
            float x=side*1.07f;
            panel(out,Bone.BODY,DARK_LEATHER,s,x,5.58f,.42f,.85f,.22f,false);
            toggle(out,s,x,5.95f,.52f,false);
            strip(out,Bone.BODY,LEATHER,s,x,6.28f,x,7.10f,.10f,.24f,false);
            toggle(out,s,x,7.17f,.37f,false);
            panel(out,Bone.BODY,LEATHER,s,side*2.55f,-.02f,.35f,.77f,.20f,false);
        }
        for(boolean back:new boolean[]{false,true}) {
            strip(out,Bone.BODY,shade(color,.77f),s,-4.1f,6.80f,4.1f,6.80f,.11f,.08f,back);
            for(int side:new int[]{-1,1}) {
                strip(out,Bone.BODY,DARK_LEATHER,s,side*2.35f,6.75f,side*2.20f,7.75f,.09f,.23f,back);
                toggle(out,s,side*2.20f,7.80f,.34f,back);
            }
        }
        for(int side:new int[]{-1,1})panel(out,Bone.BODY,LEATHER,s,side*1.72f,1.1f,.43f,1.1f,.22f,true);
    }

    private static void bomber(List<Piece> out,FacetedMesh.Section[] s,int color) {
        for(float y:new float[]{.70f,2.40f,4.10f,5.80f,7.50f,9.20f})baffle(out,Bone.BODY,color,s,y,1.69f,20);
        standCollar(out,KNIT,1.95f,true);
        zipper(out,s,color);
        for(int side:new int[]{-1,1}) {
            strip(out,Bone.BODY,shade(color,.70f),s,side*2.15f,6.82f,side*3.18f,8.90f,.24f,.20f,false);
            strip(out,Bone.BODY,shade(color,1.10f),s,side*2.40f,6.82f,side*3.43f,8.90f,.18f,.21f,false);
        }
    }

    private static void survivalShell(List<Piece> out,FacetedMesh.Section[] s,int color) {
        hood(out,color,false);
        standCollar(out,color,1.92f,false);
        for(int side:new int[]{-1,1}) {
            panel(out,Bone.BODY,SHELL_PANEL,s,side*3.10f,.65f,2.70f,2.70f,.15f,false);
            panel(out,Bone.BODY,SHELL_PANEL,s,side*3.02f,.50f,2.95f,2.25f,.15f,true);
            strip(out,Bone.BODY,SHELL_PANEL,s,side*2.05f,1.15f,side*2.05f,5.25f,.30f,.16f,false);
            toggle(out,s,side*2.05f,4.97f,.28f,false);
        }
        zipper(out,s,color);
        pockets(out,s,color,8.93f,2.95f,3.1f,false);
        panel(out,Bone.BODY,SHELL_PANEL,s,0,1.15f,.77f,2.90f,.15f,true);
        panel(out,Bone.BODY,LEATHER,s,0,2.45f,.43f,1.15f,.22f,true);
        for(boolean back:new boolean[]{false,true}) {
            strip(out,Bone.BODY,shade(color,.85f),s,-4.05f,6.55f,4.05f,6.55f,.07f,.06f,back);
            for(int side:new int[]{-1,1})
                strip(out,Bone.BODY,shade(color,.88f),s,side*3.5f,3.0f,side*3.6f,6.4f,.065f,.05f,back);
        }
    }

    private static void zipper(List<Piece> out,FacetedMesh.Section[] s,int color) {
        float end=hemYFrom(s)-.24f;
        panel(out,Bone.BODY,shade(color,.78f),s,0,(end-.65f)*.5f,.38f,end+.65f,.12f,false);
        strip(out,Bone.BODY,ZIP,s,0,-.65f,0,end,.08f,.18f,false);
        panel(out,Bone.BODY,shade(color,1.08f),s,.33f,(end-.65f)*.5f,.42f,end+.65f,.17f,false);
        for(float y=.7f;y<end;y+=2.25f)button(out,Bone.BODY,s,.32f,y,.16f,.26f,false);
        panel(out,Bone.BODY,LEATHER_EDGE,s,-.13f,1.1f,.15f,.48f,.26f,false);
    }

    private static void pockets(List<Piece> out,FacetedMesh.Section[] s,int color,float y,float w,float h,boolean doubleStud) {
        for(int side:new int[]{-1,1})pocket(out,s,color,side*2.65f,y,w,h,.47f,false,doubleStud?2:1);
    }

    private static void pocket(List<Piece> out,FacetedMesh.Section[] s,int color,float x,float y,
            float w,float h,float depth,boolean back,int studs) {
        add(out,Bone.BODY,shade(color,.82f),SurfaceRole.POCKET,sewn(s,x,y,w,h,depth,back));
        add(out,Bone.BODY,color,SurfaceRole.POCKET,sewn(s,x,y+.035f,w-.18f,h-.18f,depth+.06f,back));
        add(out,Bone.BODY,shade(color,1.11f),SurfaceRole.FLAP,sewn(s,x,y-h*.5f+.18f,w+.12f,.66f,depth+.16f,back));
        for(int i=0;i<studs;i++) {
            float sx=studs==1?x:x+(i==0?-1:1)*w*.32f;
            button(out,Bone.BODY,s,sx,y-h*.5f+.23f,.20f,depth+.26f,back);
        }
        strip(out,Bone.BODY,shade(color,.93f),s,x-w*.39f,y+h*.43f,x+w*.39f,y+h*.43f,.055f,depth+.11f,back);
    }

    private static void belt(List<Piece> out,FacetedMesh.Section[] s,float y,float height) {
        var lo=sample(s,y-height*.5f,.13f);var hi=sample(s,y+height*.5f,.13f);
        add(out,Bone.BODY,DARK_LEATHER,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.09f,lo,hi));
        for(boolean back:new boolean[]{false,true})for(int side:new int[]{-1,1})
            panel(out,Bone.BODY,COLORS[4],s,side*3.35f,y,.40f,height+0.30f,.20f,back);
    }

    private static void standCollar(List<Piece> out,int color,float height,boolean ribbed) {
        float top=-1.4f-height;
        FacetedMesh.Section[] s={
            section(top,5.4f,4.85f,0,.08f,4.0f),
            section(top+.23f,5.68f,5.12f,0,.08f,4),
            section(-.72f,6.15f,5.45f,0,.12f,4)};
        add(out,Bone.BODY,color,SurfaceRole.COLLAR,mesh().openTube(0,0,20,.21f,s));
        if(ribbed)for(int i=-7;i<=7;i++) {
            float x=i*.31f;
            strip(out,Bone.BODY,KNIT_RIB,s,x,top+.16f,x,-.83f,.07f,.08f,false);
            strip(out,Bone.BODY,KNIT_RIB,s,x,top+.16f,x,-.83f,.07f,.08f,true);
        }
    }

    private static void hood(List<Piece> out,int color,boolean fur) {
        if(fur) {
            // The expedition hood is dropped behind the neck. Its shearling follows an
            // open U, never the closed horizontal ring that crossed the beard and mouth.
            add(out,Bone.BODY,shade(color,1.06f),SurfaceRole.HOOD,mesh().softLoft(0,2.60f,20,
                section(-2.30f,6.10f,1.35f,0,.25f,3.7f),
                section(-1.70f,7.55f,2.65f,0,.25f,3.8f),
                section(-.55f,7.95f,3.35f,0,.10f,3.9f),
                section(.80f,7.35f,3.0f,0,-.05f,3.9f),
                section(1.70f,5.85f,2.0f,0,-.20f,3.8f),
                section(2.12f,3.80f,.62f,0,-.42f,3.6f)));
            add(out,Bone.BODY,shade(color,.81f),SurfaceRole.HOOD,mesh().openTube(0,.12f,20,.30f,
                section(-1.35f,6.0f,5.0f,0,0,3.9f),
                section(-.45f,6.70f,5.55f,0,0,4)));
            shearlingArc(out,3.68f,3.48f,4.0f,.44f,-2.18f,-.37f,.67f,.76f,28);
            return;
        }
        // A broad dropped hood with an open neck, folded behind the skull as in the sheet.
        add(out,Bone.BODY,shade(color,1.06f),SurfaceRole.HOOD,mesh().softLoft(0,2.28f,20,
            section(-3.20f,4.6f,1.50f,0,.1f,3.9f),
            section(-2.75f,6.45f,2.70f,0,.20f,4),
            section(-1.55f,7.05f,3.06f,0,.30f,4.1f),
            section(-.15f,6.90f,2.95f,0,.20f,4.1f),
            section(1.30f,5.5f,1.85f,0,-.10f,4),
            section(1.82f,3.5f,.55f,0,-.45f,3.7f)));
        add(out,Bone.BODY,shade(color,.81f),SurfaceRole.HOOD,mesh().openTube(0,.38f,20,.26f,
            section(-3.05f,5.45f,5.15f,0,.20f,3.9f),
            section(-2.65f,6.45f,5.63f,0,.18f,4),
            section(-1.15f,6.70f,5.63f,0,.12f,4),
            section(-.55f,6.55f,5.40f,0,.06f,3.9f)));
    }

    /** Open shearling border, with shared sweep vertices and overlapping small curls. */
    private static void shearlingArc(List<Piece> out,float rx,float backDepth,float frontDepth,
            float shiftZ,float backY,float frontY,float radial,float vertical,int steps) {
        float[][] centres=new float[steps+1][3];float[][] radialDirections=new float[steps+1][2];
        for(int i=0;i<=steps;i++) {
            double a=Math.toRadians(-48+276*i/(double)steps);
            float nx=(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),.72),Math.cos(a));
            float nz=(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),.72),Math.sin(a));
            float t=(nz+.807f)/1.807f;
            centres[i]=new float[]{nx*rx,frontY+(backY-frontY)*t,
                nz*(nz<0?frontDepth:backDepth)+shiftZ};
            float n=(float)Math.hypot(nx,nz);radialDirections[i]=new float[]{nx/n,nz/n};
        }
        FacetedMesh ribbon=mesh();
        for(int i=0;i<steps;i++)for(int k=0;k<8;k++) {
            float[][] p=new float[4][3];int[] rings={i,i+1,i+1,i};int[] corners={k,k,k+1,k+1};
            for(int j=0;j<4;j++) {
                int ring=rings[j];double a=corners[j]*Math.PI/4;
                float fullness=1+.045f*(float)Math.sin(ring*2.2);
                float r=radial*fullness*(float)Math.cos(a);
                p[j]=new float[]{centres[ring][0]+radialDirections[ring][0]*r,
                    centres[ring][1]+vertical*fullness*(float)Math.sin(a),
                    centres[ring][2]+radialDirections[ring][1]*r};
            }
            outwardFace(ribbon,p,new float[]{(centres[i][0]+centres[i+1][0])*.5f,
                (centres[i][1]+centres[i+1][1])*.5f,(centres[i][2]+centres[i+1][2])*.5f});
        }
        // Close the two collar tips; they are visible from the front at the chest.
        for(int end:new int[]{0,steps})for(int k=0;k<8;k++) {
            float fullness=1+.045f*(float)Math.sin(end*2.2);
            float[][] p=new float[3][3];p[0]=centres[end];
            for(int j=1;j<=2;j++) {
                double a=(k+j-1)*Math.PI/4;
                float radialOffset=radial*fullness*(float)Math.cos(a);
                p[j]=new float[]{centres[end][0]+radialDirections[end][0]*radialOffset,
                    centres[end][1]+vertical*fullness*(float)Math.sin(a),
                    centres[end][2]+radialDirections[end][1]*radialOffset};
            }
            tri(ribbon,p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
                p[2][0],p[2][1],p[2][2],end==steps);
        }
        add(out,Bone.BODY,FUR,SurfaceRole.COLLAR,ribbon);
        for(int i=0;i<=steps;i++) {
            float[] p=centres[i],n=radialDirections[i];
            // A few overlapping locks change the outline and softness, rather than
            // spending triangles on an invisible dense noise layer.
            float xx=p[0]+n[0]*radial*.48f,zz=p[2]+n[1]*radial*.48f;
            float yy=p[1]-vertical*.44f+(i%3-1)*.075f,scale=i%4==0?1.10f:.94f;
            add(out,Bone.BODY,i%5==0?FUR_LIGHT:i%7==0?FUR_SHADOW:FUR,SurfaceRole.COLLAR,
                mesh().softLoft(xx,zz,8,
                    section(yy-vertical*.52f,radial*.77f*scale,radial*.80f*scale,0,0,2.5f),
                    section(yy,radial*1.20f*scale,radial*1.18f*scale,0,0,2.6f),
                    section(yy+vertical*.55f,radial*.84f*scale,radial*.80f*scale,0,0,2.5f)));
        }
    }

    private static void shearlingLapels(List<Piece> out,FacetedMesh.Section[] s,float length,float scale) {
        for(int side:new int[]{-1,1}) {
            float[] yy={-1.06f,-.50f,.52f,length*.58f,length*.83f};
            float[] xx={3.02f,2.85f,2.34f,1.65f,1.06f};
            float[] ww={1.40f,1.90f,1.95f,1.50f,.80f};
            float[] dd={.68f,.96f,1.03f,.94f,.55f};
            FacetedMesh.Section[] sections=new FacetedMesh.Section[yy.length];
            for(int i=0;i<yy.length;i++)sections[i]=section(yy[i],ww[i]*scale,dd[i],
                side*xx[i],surface(s,side*xx[i],yy[i],false)-.30f,2.8f);
            add(out,Bone.BODY,FUR,SurfaceRole.COLLAR,mesh().softLoft(0,0,12,sections));
            for(int i=0;i<7;i++) {
                float t=i/6f,x=side*(2.95f-1.80f*t),y=-.62f+length*.93f*t;
                float z=surface(s,x,y,false)-.81f;
                add(out,Bone.BODY,i%3==0?FUR_LIGHT:FUR,SurfaceRole.COLLAR,
                    mesh().softLoft(x,z,8,
                        section(y-.28f,.42f,.35f,0,0,2.5f),
                        section(y,.63f,.48f,0,0,2.6f),
                        section(y+.28f,.44f,.35f,0,0,2.5f)));
            }
        }
    }

    private static void outwardFace(FacetedMesh mesh,float[][] p,float[] centre) {
        var n=new org.joml.Vector3f(p[1][0]-p[0][0],p[1][1]-p[0][1],p[1][2]-p[0][2])
            .cross(p[2][0]-p[0][0],p[2][1]-p[0][1],p[2][2]-p[0][2]);
        var r=new org.joml.Vector3f((p[0][0]+p[1][0]+p[2][0]+p[3][0])*.25f-centre[0],
            (p[0][1]+p[1][1]+p[2][1]+p[3][1])*.25f-centre[1],
            (p[0][2]+p[1][2]+p[2][2]+p[3][2])*.25f-centre[2]);
        face(mesh,p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
            p[2][0],p[2][1],p[2][2],p[3][0],p[3][1],p[3][2],n.dot(r)>0);
    }

    private static void quiltCell(List<Piece> out,Bone bone,int color,FacetedMesh.Section[] s,
            float x,float y,float w,float h,float depth,boolean back) {
        float[] t={0,.36f,.64f,1};float sign=back?1:-1;
        FacetedMesh roundedEdge=mesh(),centre=mesh();
        for(int row=0;row<3;row++)for(int col=0;col<3;col++) {
            float[][] p=new float[4][3];int[][] at={{col,row},{col+1,row},{col+1,row+1},{col,row+1}};
            for(int k=0;k<4;k++) {
                int u=at[k][0],v=at[k][1];
                float xx=x+(t[u]-.5f)*w,yy=y+(t[v]-.5f)*h;
                float fullness=(u==0||u==3||v==0||v==3)?0:1;
                // Long gentle slopes meet the base fabric almost flush; the centre
                // is slightly lofted cloth, never a separate bevelled plate.
                float fold=fullness*.003f*(u==v?1:-1);
                p[k]=new float[]{xx,yy,surface(s,xx,yy,back)+sign*(.008f+depth*fullness+fold)};
            }
            face(row==1&&col==1?centre:roundedEdge,
                p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
                p[2][0],p[2][1],p[2][2],p[3][0],p[3][1],p[3][2],back);
        }
        add(out,bone,color,SurfaceRole.DETAIL,roundedEdge);
        add(out,bone,shade(color,1.015f),SurfaceRole.DETAIL,centre);
    }

    private static void lapels(List<Piece> out,FacetedMesh.Section[] s,int color,float x,float length,float width) {
        for(int side:new int[]{-1,1}) {
            FacetedMesh m=mesh();
            float[] px={side*(x-.9f),side*(x+width*.55f),side*(x+.15f),side*.50f};
            float[] py={-1.50f,-.50f,length*.36f,length*.86f};
            float[][] p=new float[4][3];
            for(int i=0;i<4;i++)p[i]=new float[]{px[i],py[i],surface(s,px[i],py[i],false)-.22f};
            face(m,p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
                p[2][0],p[2][1],p[2][2],p[3][0],p[3][1],p[3][2],side<0);
            add(out,Bone.BODY,color,SurfaceRole.COLLAR,m);
            if(color==FUR)for(int i=0;i<7;i++) {
                float t=i/6f,xx=side*(x*.85f*(1-t)+.65f*t),yy=-.55f+length*.82f*t;
                float zz=surface(s,xx,yy,false)-.31f;
                add(out,Bone.BODY,i%4==0?FUR_LIGHT:FUR,SurfaceRole.COLLAR,
                    mesh().softLoft(xx,zz,8,
                        section(yy-.40f,.70f,.36f,0,0,2.7f),
                        section(yy,.90f,.47f,0,0,2.8f),
                        section(yy+.40f,.70f,.36f,0,0,2.7f)));
            }
        }
    }

    private static void baffle(List<Piece> out,Bone bone,int color,FacetedMesh.Section[] s,float y,float h,int sides) {
        add(out,bone,color,SurfaceRole.MAIN,mesh().softLoftOpen(0,0,sides,
            sample(s,y-h*.5f,.018f),sample(s,y-h*.38f,.045f),
            sample(s,y-h*.18f,.082f),sample(s,y,.092f),
            sample(s,y+h*.18f,.082f),sample(s,y+h*.38f,.045f),sample(s,y+h*.5f,.018f)));
    }

    private static FacetedMesh.Section sample(FacetedMesh.Section[] s,float y,float expand) {
        int row=0;while(row<s.length-2&&y>s[row+1].y())row++;
        var a=s[row];var b=s[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return section(y,a.width()*(1-t)+b.width()*t+expand*2,
            a.depth()*(1-t)+b.depth()*t+expand*2,
            a.shiftX()*(1-t)+b.shiftX()*t,a.shiftZ()*(1-t)+b.shiftZ()*t,a.power()*(1-t)+b.power()*t);
    }

    private static void buckle(List<Piece> out,FacetedMesh.Section[] s,float x,float y,float w,float h,float d,boolean back) {
        int c=METAL;float q=.10f;
        panel(out,Bone.BODY,c,s,x,y-h*.5f,w,q,d,back);
        panel(out,Bone.BODY,c,s,x,y+h*.5f,w,q,d,back);
        panel(out,Bone.BODY,c,s,x-w*.5f,y,q,h,d,back);
        panel(out,Bone.BODY,c,s,x+w*.5f,y,q,h,d,back);
        panel(out,Bone.BODY,c,s,x,y,.07f,h*.80f,d+.04f,back);
    }

    private static void toggle(List<Piece> out,FacetedMesh.Section[] s,float x,float y,float w,boolean back) {
        panel(out,Bone.BODY,LEATHER_EDGE,s,x,y,w,.19f,.30f,back);
    }

    private static void button(List<Piece> out,Bone bone,FacetedMesh.Section[] s,float x,float y,float size,float depth,boolean back) {
        panel(out,bone,METAL,s,x,y,size,size,depth,back);
    }

    /** Subdivide along the sewn cloth surface; no large centre fans or pyramid corners. */
    private static void panel(List<Piece> out,Bone bone,int color,FacetedMesh.Section[] s,
            float x,float y,float w,float h,float relief,boolean back) {
        int columns=Math.max(2,(int)Math.ceil(w/.90f)),rows=Math.max(2,(int)Math.ceil(h/.95f));
        FacetedMesh m=mesh();float sign=back?1:-1;
        for(int row=0;row<rows;row++)for(int column=0;column<columns;column++) {
            float[] u={column/(float)columns,(column+1f)/columns};
            float[] v={row/(float)rows,(row+1f)/rows};
            float[][] p=new float[4][3];int[][] indices={{0,0},{1,0},{1,1},{0,1}};
            for(int corner=0;corner<4;corner++) {
                float a=u[indices[corner][0]],b=v[indices[corner][1]];
                float chamfer=Math.min(.13f,Math.min(w*.10f,h*.12f));
                float taper=(b==0||b==1)?chamfer:0;
                float xx=x+(a-.5f)*(w-2*taper),yy=y+(b-.5f)*h;
                float shoulder=(float)(Math.sin(Math.PI*a)*Math.sin(Math.PI*b));
                // Small rounded seam allowance, not a pointed centre.
                float z=surface(s,xx,yy,back)+sign*(relief+.006f*shoulder);
                p[corner]=new float[]{xx,yy,z};
            }
            face(m,p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],
                p[2][0],p[2][1],p[2][2],p[3][0],p[3][1],p[3][2],back);
        }
        add(out,bone,color,SurfaceRole.DETAIL,m);
    }

    private static void strip(List<Piece> out,Bone bone,int color,FacetedMesh.Section[] s,
            float ax,float ay,float bx,float by,float width,float relief,boolean back) {
        float dx=bx-ax,dy=by-ay,length=(float)Math.sqrt(dx*dx+dy*dy);
        if(length<.0001f)return;
        float nx=-dy/length*width*.5f,ny=dx/length*width*.5f,sign=back?1:-1;
        int steps=Math.max(1,(int)Math.ceil(length/1.2f));FacetedMesh m=mesh();
        for(int i=0;i<steps;i++) {
            float ta=i/(float)steps,tb=(i+1f)/steps;
            float xa=ax+dx*ta,ya=ay+dy*ta,xb=ax+dx*tb,yb=ay+dy*tb;
            face(m,xa-nx,ya-ny,surface(s,xa-nx,ya-ny,back)+sign*relief,
                xb-nx,yb-ny,surface(s,xb-nx,yb-ny,back)+sign*relief,
                xb+nx,yb+ny,surface(s,xb+nx,yb+ny,back)+sign*relief,
                xa+nx,ya+ny,surface(s,xa+nx,ya+ny,back)+sign*relief,back);
        }
        add(out,bone,color,SurfaceRole.DETAIL,m);
    }

    private static void face(FacetedMesh m,float ax,float ay,float az,float bx,float by,float bz,
            float cx,float cy,float cz,float dx,float dy,float dz,boolean reverse) {
        tri(m,ax,ay,az,bx,by,bz,cx,cy,cz,reverse);
        tri(m,ax,ay,az,cx,cy,cz,dx,dy,dz,reverse);
    }

    private static void tri(FacetedMesh m,float ax,float ay,float az,float bx,float by,float bz,
            float cx,float cy,float cz,boolean reverse) {
        var a=new ModelPart.Vertex(ax,ay,az,0,0);
        var b=new ModelPart.Vertex(bx,by,bz,1,0);
        var c=new ModelPart.Vertex(cx,cy,cz,1,1);
        if(reverse)m.face(a,b,c,c);else m.face(c,b,a,a);
    }

    private static void lightSnow(List<Piece> out,FacetedMesh.Section[] s,int design) {
        // Only the sparse edge dust visible on the reference, not age damage or a recolour.
        for(boolean back:new boolean[]{false,true})for(int i=0;i<9;i++) {
            float x=-3.65f+i*.90f,y=i%3==0?.25f:hemY(design)-.24f;
            panel(out,Bone.BODY,SNOW,s,x,y,.14f+(i%3)*.09f,.07f+(i%2)*.06f,.10f,back);
        }
    }

    private static float hemYFrom(FacetedMesh.Section[] s){return s[s.length-1].y();}
    private static int shade(int c,float factor) {
        int r=Math.min(255,Math.round(((c>>>16)&255)*factor));
        int g=Math.min(255,Math.round(((c>>>8)&255)*factor));
        int b=Math.min(255,Math.round((c&255)*factor));
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }
    private CinematicJackets() { }
}
