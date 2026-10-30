// Builds the eight authored headwear meshes in the clothing catalog.
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
import static com.dark.winter.client.equipment.NorthernWandererClothing.sewn;

/** Eight fixed headwear designs from the user's original numbered reference sheet. */
final class CinematicHats {
    private static final int KNIT=0xFF36343E, KNIT_RIB=0xFF48434F, KNIT_DARK=0xFF292831;
    private static final int NAVY=0xFF333641, CREAM=0xFFC4BAAA;
    private static final int BROWN=0xFF75523F, BROWN_LIGHT=0xFF8A6650, BROWN_DARK=0xFF514137;
    private static final int STORM=0xFF4D403B, STORM_EDGE=0xFF655146;
    private static final int OLIVE=0xFF6B6B57, OLIVE_EDGE=0xFF7F7C66;
    private static final int FUR=0xFFC2AF95, FUR_LIGHT=0xFFD2C1A8, FUR_SHADE=0xFFAB9781;
    private static final int METAL=0xFF9B9386, LEATHER=0xFF79533C, STITCH=0xFFB29A7A;
    private static final Identifier WOOL=material("wool"), CANVAS=material("canvas"),
            LEATHER_TEXTURE=material("leather"), HARDWARE=material("rubber");
    private static final List<List<Piece>> ALL=build();

    static List<Piece> pieces(int design) {
        if(design<1||design>8)throw new IllegalArgumentException("Hat design "+design);
        return ALL.get(design-1);
    }

    static Identifier texture(int design,int color) {
        if(color==METAL)return HARDWARE;
        if(color==FUR||color==FUR_LIGHT||color==FUR_SHADE||color==CREAM||
                color==KNIT||color==KNIT_RIB||color==KNIT_DARK||color==NAVY)return WOOL;
        if(color==LEATHER||color==STITCH||color==BROWN_DARK)return LEATHER_TEXTURE;
        return design==3||design==4||design==7?LEATHER_TEXTURE:CANVAS;
    }

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    private static List<List<Piece>> build() {
        List<List<Piece>> all=new ArrayList<>();
        for(int design=1;design<=8;design++) {
            List<Piece> out=new ArrayList<>();
            switch(design) {
                case 1->beanie(out,false);
                case 2->beanie(out,true);
                case 3->furHat(out,design);
                case 4->furHat(out,design);
                case 5->earCap(out);
                case 6->balaclava(out);
                case 7->furHat(out,design);
                case 8->fieldCap(out);
                default->throw new AssertionError();
            }
            all.add(compact(out));
        }
        return List.copyOf(all);
    }

    private static void beanie(List<Piece> out,boolean folded) {
        FacetedMesh.Section[] crown=folded?new FacetedMesh.Section[]{
                section(-7.89f,3.42f,3.65f,.06f,.08f,4.0f),
                section(-7.72f,4.78f,5.01f,.03f,.04f,4.1f),
                section(-7.20f,5.44f,5.69f,0,.02f,4.1f),
                section(-6.23f,5.60f,5.88f,0,0,4.2f),
                section(-5.31f,5.68f,5.96f,0,0,4.2f),
                section(-4.10f,5.62f,5.90f,0,0,4.1f)}
                :new FacetedMesh.Section[]{
                section(-8.48f,1.38f,1.58f,.09f,.04f,3.5f),
                section(-8.18f,2.96f,3.22f,.06f,.05f,3.7f),
                section(-7.62f,4.63f,4.90f,.02f,.04f,3.9f),
                section(-6.76f,5.46f,5.73f,0,.02f,4.0f),
                section(-5.63f,5.65f,5.94f,0,0,4.0f),
                section(-4.13f,5.60f,5.89f,0,0,4.1f)};
        add(out,Bone.HEAD,KNIT,SurfaceRole.MAIN,mesh().softLoft(0,0,20,crown));
        ribs(out,crown,32,KNIT_RIB,.038f);
        FacetedMesh.Section[] band={
                section(folded?-5.89f:-5.52f,5.77f,6.07f,0,0,4.2f),
                section(folded?-5.66f:-5.29f,5.94f,6.25f,0,0,4.2f),
                section(-4.24f,5.94f,6.25f,0,0,4.2f),
                section(-4.04f,5.78f,6.08f,0,0,4.1f)};
        add(out,Bone.HEAD,KNIT,SurfaceRole.MAIN,mesh().openTube(0,0,20,.18f,band));
        ribs(out,band,36,KNIT_RIB,.035f);
        if(!folded) {
            // Reference 01 has a small leather maker's patch at the temple.
            add(out,Bone.HEAD,LEATHER,SurfaceRole.DETAIL,sewn(band,1.92f,-4.75f,.63f,.83f,.12f,false));
            add(out,Bone.HEAD,STITCH,SurfaceRole.DETAIL,sewn(band,1.92f,-4.75f,.44f,.62f,.14f,false));
            add(out,Bone.HEAD,LEATHER,SurfaceRole.DETAIL,sewn(band,1.92f,-4.75f,.36f,.53f,.17f,false));
        }
    }

    private static FacetedMesh.Section[] capCrown(float top,float width,float depth) {
        return new FacetedMesh.Section[]{
                section(top,width*.56f,depth*.57f,0,.04f,3.8f),
                section(top+.21f,width*.83f,depth*.84f,0,.02f,4.0f),
                section(top+.70f,width*.98f,depth*.98f,0,0,4.2f),
                section(-5.34f,width,depth,0,0,4.2f),
                section(-4.27f,width*.99f,depth*.99f,0,0,4.1f)};
    }

    private static void furHat(List<Piece> out,int design) {
        boolean ushanka=design==3;
        int main=design==7?STORM:BROWN;
        int edge=design==7?STORM_EDGE:BROWN_LIGHT;
        var crown=capCrown(ushanka?-7.68f:-7.52f,5.72f,6.02f);
        add(out,Bone.HEAD,main,SurfaceRole.MAIN,mesh().softLoft(0,0,20,crown));
        // Subdued panel seams follow the crown, with a practical leather shell.
        ribs(out,crown,12,edge,.024f);
        add(out,Bone.HEAD,edge,SurfaceRole.DETAIL,sewn(crown,0,-6.07f,4.78f,.09f,.04f,true));
        if(!ushanka)visor(out,main,edge,design==7?1.41f:1.29f);
        if(ushanka||design==4) {
            float y=ushanka?-5.30f:-5.62f,h=ushanka?1.62f:1.17f;
            add(out,Bone.HEAD,FUR,SurfaceRole.DETAIL,mesh().softLoft(0,-2.79f,16,
                    section(y-h*.5f,5.20f,.60f,0,0,3.8f),
                    section(y-h*.32f,5.65f,.94f,0,-.06f,3.9f),
                    section(y+h*.35f,5.72f,1.00f,0,-.07f,3.9f),
                    section(y+h*.5f,5.33f,.64f,0,-.03f,3.7f)));
            furFront(out,y,h,5.37f,-3.27f);
        } else {
            add(out,Bone.HEAD,edge,SurfaceRole.DETAIL,sewn(crown,0,-5.52f,4.90f,.43f,.13f,false));
            for(float x:new float[]{-1.86f,1.86f})
                add(out,Bone.HEAD,METAL,SurfaceRole.DETAIL,sewn(crown,x,-5.52f,.22f,.22f,.24f,false));
        }
        for(int side:new int[]{-1,1}) {
            float bottom=ushanka?-1.65f:design==4?-1.87f:-2.02f;
            // Flaps have a curved outer silhouette and an uninterrupted fur lining.
            add(out,Bone.HEAD,main,SurfaceRole.MAIN,mesh().softLoft(side*2.78f,.22f,12,
                    section(-5.33f,.58f,4.59f,0,0,3.6f),
                    section(-4.40f,.65f,4.68f,side*.04f,.02f,3.7f),
                    section(-3.08f,.74f,4.30f,side*.08f,.19f,3.7f),
                    section(bottom,.65f,3.15f,side*.12f,.40f,3.6f),
                    section(bottom+.16f,.37f,2.40f,side*.10f,.47f,3.4f)));
            add(out,Bone.HEAD,FUR,SurfaceRole.DETAIL,mesh().softLoft(side*3.11f,.18f,12,
                    section(-5.12f,.18f,4.16f,0,0,3.6f),
                    section(-3.25f,.23f,4.12f,side*.03f,.11f,3.6f),
                    section(bottom+.02f,.19f,2.94f,side*.04f,.34f,3.5f)));
            for(int i=0;i<72;i++) {
                int seed=7109+i*37+side*101;
                float t=(float)((.37+i*.569840290998)%1);
                float y=-4.91f+t*(bottom+4.78f);
                float span=3.91f-t*.97f;
                float z=(float)(((.23+i*.754877666247)%1)-.5)*span+.24f+t*.21f;
                int tint=fleeceTint(seed);
                add(out,Bone.HEAD,tint,SurfaceRole.DETAIL,furPuff(side*3.20f,y,z,
                        .40f+scatter(seed+1)*.19f,.32f+scatter(seed+2)*.17f,
                        .075f+scatter(seed+3)*.055f,side,0,scatter(seed+4)*6.283185f,seed));
            }
            add(out,Bone.HEAD,LEATHER,SurfaceRole.DETAIL,mesh().pillow(side*3.25f,-4.58f,.38f,.14f,.65f,1.16f));
            add(out,Bone.HEAD,METAL,SurfaceRole.DETAIL,mesh().pillow(side*3.35f,-4.58f,.36f,.12f,.59f,.55f));
            add(out,Bone.HEAD,BROWN_DARK,SurfaceRole.DETAIL,mesh().pillow(side*3.43f,-4.58f,.36f,.08f,.34f,.28f));
        }
        add(out,Bone.HEAD,main,SurfaceRole.MAIN,mesh().softLoft(0,2.72f,16,
                section(-4.91f,5.59f,.65f,0,0,3.8f),
                section(-3.30f,5.85f,.73f,0,.02f,3.8f),
                section(-2.15f,5.49f,.74f,0,.08f,3.7f),
                section(-1.96f,4.99f,.49f,0,.08f,3.6f)));
        add(out,Bone.HEAD,LEATHER,SurfaceRole.DETAIL,mesh().pillow(0,-3.48f,3.17f,5.58f,.46f,.16f));
        add(out,Bone.HEAD,METAL,SurfaceRole.DETAIL,mesh().pillow(.54f,-3.48f,3.31f,.69f,.60f,.13f));
        add(out,Bone.HEAD,BROWN_DARK,SurfaceRole.DETAIL,mesh().pillow(.54f,-3.48f,3.40f,.40f,.32f,.10f));
        add(out,Bone.HEAD,FUR,SurfaceRole.DETAIL,mesh().softLoft(0,3.13f,16,
                section(-2.29f,5.12f,.22f,0,0,3.8f),section(-1.91f,5.08f,.20f,0,0,3.8f)));
        for(int i=0;i<28;i++) {
            int seed=8101+i*31;
            float x=-2.47f+(i+scatter(seed)*.5f)*4.89f/28;
            add(out,Bone.HEAD,fleeceTint(seed),SurfaceRole.DETAIL,
                    furPuff(x,-2.08f+(scatter(seed+1)-.5f)*.15f,3.21f,
                            .27f+scatter(seed+2)*.12f,.31f+scatter(seed+3)*.13f,
                            .065f+scatter(seed+4)*.055f,0,1,scatter(seed+5)*6.283185f,seed));
        }
    }

    private static void earCap(List<Piece> out) {
        FacetedMesh.Section[] crown={
                section(-8.45f,1.45f,1.67f,0,0,3.2f),
                section(-8.09f,3.20f,3.42f,0,0,3.5f),
                section(-7.42f,4.85f,5.14f,0,0,3.8f),
                section(-6.50f,5.53f,5.83f,0,0,3.9f),
                section(-5.34f,5.76f,6.06f,0,0,4.1f),
                section(-4.12f,5.69f,5.99f,0,0,4.1f)};
        add(out,Bone.HEAD,NAVY,SurfaceRole.MAIN,mesh().softLoft(0,0,20,crown));
        ribs(out,crown,32,KNIT_RIB,.031f);
        // The cream Nordic knit is geometry on the knitted shell, not a recolor.
        for(float y:new float[]{-7.26f,-6.72f})
            patternBand(out,crown,y,.12f);
        for(int col=0;col<16;col++) {
            double angle=col*Math.PI*2/16;
            for(int row=-2;row<=2;row++) {
                double a=angle+Math.abs(row)*.033;
                patch(out,crown,a,-5.83f+row*.16f,.070,.19f,CREAM);
                if(row!=0)patch(out,crown,angle-Math.abs(row)*.033,-5.83f+row*.16f,.070,.19f,CREAM);
            }
        }
        for(int side:new int[]{-1,1}) {
            add(out,Bone.HEAD,NAVY,SurfaceRole.MAIN,mesh().softLoft(side*2.74f,.20f,12,
                    section(-4.77f,.54f,3.71f,0,0,3.7f),
                    section(-3.80f,.67f,3.86f,side*.04f,.06f,3.7f),
                    section(-2.86f,.74f,3.31f,side*.09f,.17f,3.6f),
                    section(-2.57f,.41f,2.59f,side*.07f,.22f,3.5f)));
            for(int j=0;j<6;j++)
                add(out,Bone.HEAD,CREAM,SurfaceRole.DETAIL,mesh().pillow(side*3.10f,-4.08f+j*.22f,-1.51f+j*.15f,.14f,.18f,.24f));
        }
        // A compact knitted pom-pom retains the reference's top silhouette.
        add(out,Bone.HEAD,NAVY,SurfaceRole.DETAIL,mesh().softLoft(0,0,12,
                section(-9.32f,.62f,.71f,0,0,2.8f),
                section(-9.08f,1.49f,1.60f,0,0,2.9f),
                section(-8.59f,1.69f,1.78f,0,0,3.1f),
                section(-8.26f,1.02f,1.10f,0,0,2.9f)));
        for(int i=0;i<14;i++) {
            double a=i*Math.PI*2/14;
            add(out,Bone.HEAD,i%3==0?CREAM:KNIT_RIB,SurfaceRole.DETAIL,
                    mesh().pillow((float)Math.cos(a)*.67f,-8.79f+(i%2)*.10f,(float)Math.sin(a)*.73f,.35f,.48f,.35f));
        }
    }

    private static void balaclava(List<Piece> out) {
        FacetedMesh.Section[] shell={
                section(-6.00f,5.35f,5.70f,0,0,4.0f),
                section(-5.04f,5.58f,5.90f,0,0,4.1f),
                section(-2.98f,5.65f,5.96f,0,-.02f,4.1f),
                section(-2.02f,5.40f,5.76f,0,-.08f,4.0f),
                section(-.57f,4.57f,4.85f,0,0,3.7f)};
        // Side and rear wool around a genuine open eye area.
        FacetedMesh sides=mesh();
        for(int row=0;row<shell.length-1;row++)for(int i=0;i<20;i++) {
            double angle=-Math.PI*.75+i*Math.PI*2/20;
            double mid=angle+Math.PI/20;
            if(row<2&&Math.sin(mid)<-.40)continue;
            var a=point(shell[row],angle,0,0);var b=point(shell[row],angle+Math.PI/10,1,0);
            var c=point(shell[row+1],angle+Math.PI/10,1,1);var d=point(shell[row+1],angle,0,1);
            sides.face(b,a,d,c);
        }
        add(out,Bone.HEAD,KNIT_DARK,SurfaceRole.MAIN,sides);
        FacetedMesh.Section[] mouth={
                section(-2.95f,5.64f,6.03f,0,-.08f,4.2f),
                section(-2.68f,5.72f,6.15f,0,-.10f,4.2f),
                section(-1.53f,5.21f,5.66f,0,-.07f,4.0f),
                section(-.62f,4.64f,4.95f,0,0,3.7f)};
        add(out,Bone.HEAD,KNIT,SurfaceRole.MAIN,mesh().openTube(0,0,20,.14f,mouth));
        ribs(out,mouth,32,KNIT_RIB,.026f);
        var cap=capCrown(-7.64f,5.81f,6.12f);
        add(out,Bone.HEAD,KNIT_DARK,SurfaceRole.MAIN,mesh().softLoft(0,0,20,cap));
        ribs(out,cap,12,KNIT_RIB,.026f);
        visor(out,KNIT_DARK,KNIT_RIB,1.35f);
        add(out,Bone.HEAD,KNIT,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.10f,
                section(-5.31f,5.95f,6.25f,0,0,4.2f),section(-4.53f,5.92f,6.22f,0,0,4.2f)));
        for(int side:new int[]{-1,1}) {
            add(out,Bone.HEAD,LEATHER,SurfaceRole.DETAIL,mesh().pillow(side*2.98f,-4.88f,.56f,.12f,.48f,1.3f));
            add(out,Bone.HEAD,METAL,SurfaceRole.DETAIL,mesh().pillow(side*3.07f,-4.87f,.15f,.10f,.24f,.24f));
        }
    }

    private static void fieldCap(List<Piece> out) {
        var shell=capCrown(-7.28f,5.70f,6.02f);
        add(out,Bone.HEAD,OLIVE,SurfaceRole.MAIN,mesh().softLoft(0,0,20,shell));
        ribs(out,shell,10,OLIVE_EDGE,.024f);
        visor(out,OLIVE,OLIVE_EDGE,1.17f);
        FacetedMesh.Section[] band={
                section(-5.34f,5.82f,6.13f,0,0,4.2f),
                section(-4.47f,5.82f,6.13f,0,0,4.2f),
                section(-4.12f,5.70f,6.01f,0,0,4.0f)};
        add(out,Bone.HEAD,OLIVE,SurfaceRole.MAIN,mesh().openTube(0,0,20,.14f,band));
        add(out,Bone.HEAD,OLIVE_EDGE,SurfaceRole.DETAIL,sewn(band,0,-4.71f,5.10f,.12f,.06f,true));
        for(int side:new int[]{-1,1}) {
            add(out,Bone.HEAD,OLIVE_EDGE,SurfaceRole.DETAIL,mesh().pillow(side*2.90f,-4.65f,.25f,.16f,.72f,2.71f));
            add(out,Bone.HEAD,METAL,SurfaceRole.DETAIL,mesh().pillow(side*3.02f,-4.65f,-.28f,.10f,.26f,.26f));
            for(int i=0;i<6;i++)
                add(out,Bone.HEAD,i%2==0?FUR:FUR_SHADE,SurfaceRole.DETAIL,
                        mesh().pillow(side*2.94f,-4.02f,-1.22f+i*.53f,.18f,.31f,.47f));
        }
    }

    private static void visor(List<Piece> out,int main,int edge,float projection) {
        add(out,Bone.HEAD,main,SurfaceRole.MAIN,mesh().softLoft(0,-2.62f,20,
                section(-4.90f,5.10f,1.54f+projection,0,0,4.0f),
                section(-4.72f,5.45f,1.84f+projection,0,-.11f,4.2f),
                section(-4.48f,5.17f,1.77f+projection,0,-.10f,4.0f)));
        add(out,Bone.HEAD,edge,SurfaceRole.DETAIL,mesh().softLoft(0,-3.59f-projection*.40f,16,
                section(-4.65f,4.49f,.17f,0,0,4.0f),section(-4.49f,4.46f,.18f,0,0,4.0f)));
    }

    private static void furFront(List<Piece> out,float y,float h,float width,float z) {
        // Fixed low-discrepancy positions avoid rows, checkerboard tints and repeated bolt silhouettes.
        for(int i=0;i<110;i++) {
            int seed=6113+i*29;
            float x=(float)(((.31+i*.754877666247)%1)-.5)*(width-.25f);
            float yy=y+(float)(((.47+i*.569840290998)%1)-.5)*(h-.15f);
            add(out,Bone.HEAD,fleeceTint(seed),SurfaceRole.DETAIL,
                    furPuff(x,yy,z-.075f,.28f+scatter(seed+1)*.15f,
                            .23f+scatter(seed+2)*.15f,.07f+scatter(seed+3)*.045f,
                            0,-1,scatter(seed+4)*6.283185f,seed));
        }
    }

    private static int fleeceTint(int seed) {
        float shade=scatter(seed+113);
        return shade<.10f?FUR_LIGHT:shade>.92f?FUR_SHADE:FUR;
    }

    /** A shallow six-lobed puff follows the fabric normal; three bevel levels soften its silhouette. */
    static FacetedMesh furPuff(float x,float y,float z,float width,float height,float depth,
                               float nx,float nz,float rotation,int seed) {
        ModelPart.Vertex[] outer=new ModelPart.Vertex[6],inner=new ModelPart.Vertex[6];
        float tx=nz,tz=-nx;
        float leanU=(scatter(seed+23)-.5f)*width*.17f;
        float leanV=(scatter(seed+41)-.5f)*height*.17f;
        for(int i=0;i<6;i++) {
            double angle=rotation+i*Math.PI/3+(scatter(seed+i*17)-.5f)*.18;
            float radial=.88f+scatter(seed+i*19+7)*.20f;
            float u=(float)Math.cos(angle)*width*.5f*radial;
            float v=(float)Math.sin(angle)*height*.5f*radial;
            outer[i]=new ModelPart.Vertex(x+tx*u,y+v,z+tz*u,Math.clamp(u/(width*1.10f)+.5f,0,1),Math.clamp(v/(height*1.10f)+.5f,0,1));
            inner[i]=new ModelPart.Vertex(x+tx*(u*.53f+leanU)+nx*depth*.78f,
                    y+v*.53f+leanV,z+tz*(u*.53f+leanU)+nz*depth*.78f,
                    Math.clamp(u*.53f/(width*1.10f)+.5f,0,1),Math.clamp(v*.53f/(height*1.10f)+.5f,0,1));
        }
        var center=new ModelPart.Vertex(x+tx*leanU+nx*depth,y+leanV,z+tz*leanU+nz*depth,.5f,.5f);
        FacetedMesh shape=mesh();
        for(int i=0;i<6;i++) {
            int j=(i+1)%6;
            shape.face(outer[i],outer[j],inner[j],inner[j]);
            shape.face(outer[i],inner[j],inner[i],inner[i]);
            shape.face(inner[i],inner[j],center,center);
        }
        return shape;
    }

    static float scatter(int seed) {
        int x=seed;
        x^=x>>>16;x*=0x7feb352d;x^=x>>>15;x*=0x846ca68b;x^=x>>>16;
        return (x&0x00ffffff)/16777216f;
    }

    private static void patternBand(List<Piece> out,FacetedMesh.Section[] shell,float y,float height) {
        for(int i=0;i<40;i++)patch(out,shell,i*Math.PI*2/40,y,Math.PI/40,height,CREAM);
    }

    private static void patch(List<Piece> out,FacetedMesh.Section[] shell,double angle,float y,double halfAngle,float h,int color) {
        var top=interpolate(shell,y-h*.5f);var bottom=interpolate(shell,y+h*.5f);
        var a=point(top,angle-halfAngle,0,0);var b=point(top,angle+halfAngle,1,0);
        var c=point(bottom,angle+halfAngle,1,1);var d=point(bottom,angle-halfAngle,0,1);
        add(out,Bone.HEAD,color,SurfaceRole.DETAIL,mesh().face(b,a,d,c));
    }

    private static FacetedMesh.Section interpolate(FacetedMesh.Section[] shell,float y) {
        int row=0;while(row<shell.length-2&&y>shell[row+1].y())row++;
        var a=shell[row];var b=shell[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return section(y,mix(a.width(),b.width(),t),mix(a.depth(),b.depth(),t),
                mix(a.shiftX(),b.shiftX(),t),mix(a.shiftZ(),b.shiftZ(),t),mix(a.power(),b.power(),t));
    }

    private static void ribs(List<Piece> out,FacetedMesh.Section[] shell,int count,int color,float halfAngle) {
        FacetedMesh rib=mesh();
        boolean knit=count>=20;
        int wales=knit?count*3:count;
        for(int i=0;i<wales;i++)for(int row=0;row<shell.length-1;row++) {
            double angle=i*Math.PI*2/wales;
            var a=knit?perimeterPoint(shell[row],(i-.20)/wales,0,0):point(shell[row],angle-halfAngle,0,0);
            var b=knit?perimeterPoint(shell[row],(i+.20)/wales,1,0):point(shell[row],angle+halfAngle,1,0);
            var c=knit?perimeterPoint(shell[row+1],(i+.20)/wales,1,1):point(shell[row+1],angle+halfAngle,1,1);
            var d=knit?perimeterPoint(shell[row+1],(i-.20)/wales,0,1):point(shell[row+1],angle-halfAngle,0,1);
            rib.face(b,a,d,c);
        }
        add(out,Bone.HEAD,color,SurfaceRole.DETAIL,rib);
    }

    /** Uniform physical wale spacing around the existing softened square, not wide center stripes. */
    private static ModelPart.Vertex perimeterPoint(FacetedMesh.Section s,double fraction,float u,float v) {
        ModelPart.Vertex[] ring=new ModelPart.Vertex[21];
        float[] length=new float[20];float total=0;
        for(int i=0;i<=20;i++)ring[i]=point(s,-Math.PI*.75+i*Math.PI/10,u,v);
        for(int i=0;i<20;i++) {
            float dx=ring[i+1].x()-ring[i].x(),dz=ring[i+1].z()-ring[i].z();
            total+=length[i]=(float)Math.sqrt(dx*dx+dz*dz);
        }
        float distance=(float)(fraction-Math.floor(fraction))*total;
        int edge=0;while(edge<19&&distance>length[edge])distance-=length[edge++];
        float t=distance/length[edge];var a=ring[edge];var b=ring[edge+1];
        return new ModelPart.Vertex(mix(a.x(),b.x(),t),s.y(),mix(a.z(),b.z(),t),u,v);
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

    /** Surface points are interpolated on the actual twenty-sided softened square. */
    private static ModelPart.Vertex point(FacetedMesh.Section s,double angle,float u,float v) {
        double phase=(angle+Math.PI*.75)/(Math.PI*2/20);
        int index=(int)Math.floor(phase);float t=(float)(phase-index),x=0,z=0;
        for(int k=0;k<2;k++) {
            double a=-Math.PI*.75+(index+k)*Math.PI*2/20;float weight=k==0?1-t:t;
            x+=weight*s.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(a)),2/s.power()),Math.cos(a));
            z+=weight*s.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(a)),2/s.power()),Math.sin(a));
        }
        float length=(float)Math.sqrt(x*x+z*z);
        return new ModelPart.Vertex(x+s.shiftX()+x/length*.025f,s.y(),z+s.shiftZ()+z/length*.025f,u,v);
    }

    private static float mix(float a,float b,float t) {return a+(b-a)*t;}
    private CinematicHats() { }
}
