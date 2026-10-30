// Builds the Northern Wanderer clothing shells in the existing animation rig.
package com.dark.winter.client.equipment;

import com.dark.winter.equipment.GearSlot;
import com.dark.winter.client.equipment.ClothingMeshes.Bone;
import com.dark.winter.client.equipment.ClothingMeshes.Piece;
import com.dark.winter.client.equipment.ClothingMeshes.SurfaceRole;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/**
 * Cinematic Northern Wanderer: reference-matched winter shells in the original
 * rig coordinates. Third-person fitting is applied by the existing layer.
 * No legacy garment, skin atlas or first-person mesh is mutated.
 */
final class NorthernWandererClothing {
    static final int OLIVE=0xFF737063, OLIVE_LIGHT=0xFF807C6F, OLIVE_DARK=0xFF646254;
    static final int SCARF=0xFFB5B1A9, SCARF_FOLD=0xFFA6A29B, SCARF_EDGE=0xFFC4C0B7;
    static final int HAT=0xFF30303B, HAT_RIB=0xFF3B3A45, HAT_SEAM=0xFF272731;
    static final int CARGO=0xFF42424A, CARGO_LIGHT=0xFF4E4E56, CARGO_DARK=0xFF37373E;
    static final int LEATHER=0xFF594239, LEATHER_EDGE=0xFF6F5549, GLOVE=0xFF292C32;
    static final int RUBBER=0xFF24262B, SOLE_EDGE=0xFF4A4546, SNOW=0xFFDBDEE0, METAL=0xFF98948A;
    private static final Identifier WOOL=material("wool"), CANVAS=material("canvas"),
            LEATHER_TEXTURE=material("leather"), RUBBER_TEXTURE=material("rubber");
    private static final Map<GearSlot,List<Piece>> PIECES=create();

    static List<Piece> pieces(GearSlot slot) { return PIECES.getOrDefault(slot,List.of()); }

    static Identifier texture(GearSlot slot,int color) {
        if(color==RUBBER||color==SOLE_EDGE||color==SNOW||color==METAL)return RUBBER_TEXTURE;
        if(color==SCARF||color==SCARF_FOLD||color==SCARF_EDGE||slot==GearSlot.HAT)return WOOL;
        if(slot==GearSlot.FOOTWEAR||slot==GearSlot.GLOVES||color==LEATHER||color==LEATHER_EDGE)return LEATHER_TEXTURE;
        return CANVAS;
    }

    private static Identifier material(String name) {
        return Identifier.fromNamespaceAndPath("darkwinter","textures/entity/clothing_"+name+".png");
    }

    private static Map<GearSlot,List<Piece>> create() {
        var result=new EnumMap<GearSlot,List<Piece>>(GearSlot.class);
        for(GearSlot slot:new GearSlot[]{GearSlot.JACKET,GearSlot.HAT,GearSlot.PANTS,GearSlot.GLOVES,GearSlot.FOOTWEAR}) {
            List<Piece> source=new ArrayList<>();
            switch(slot) {
                case JACKET->jacket(source);
                case HAT->hat(source);
                case PANTS->pants(source);
                case GLOVES->gloves(source);
                case FOOTWEAR->boots(source);
                default->throw new AssertionError();
            }
            result.put(slot,articulate(source));
        }
        return Map.copyOf(result);
    }

    static List<Piece> articulate(List<Piece> source) {
        List<Piece> result=new ArrayList<>();
        for(Piece p:source) {
            boolean arm=p.bone()==Bone.LEFT_ARM||p.bone()==Bone.RIGHT_ARM;
            boolean leg=p.bone()==Bone.LEFT_LEG||p.bone()==Bone.RIGHT_LEG;
            if(!arm&&!leg){result.add(p);continue;}
            var split=ArticulatedMesh.split(p.geometry(),arm?HumanJoints.ELBOW_Y:HumanJoints.KNEE_Y);
            var upper=split.upper().bake();var lower=split.lower().bake();
            Bone child=switch(p.bone()) {
                case LEFT_ARM->Bone.LEFT_FOREARM;case RIGHT_ARM->Bone.RIGHT_FOREARM;
                case LEFT_LEG->Bone.LEFT_SHIN;case RIGHT_LEG->Bone.RIGHT_SHIN;
                default->throw new AssertionError();
            };
            if(!upper.isEmpty())result.add(new Piece(p.bone(),upper,p.color(),p.role()));
            if(leg) {
                var ankle=ArticulatedMesh.split(lower,HumanJoints.ANKLE_Y-HumanJoints.KNEE_Y);
                var shin=ankle.upper().bake();var foot=ankle.lower().bake();
                if(!shin.isEmpty())result.add(new Piece(child,shin,p.color(),p.role()));
                if(!foot.isEmpty())result.add(new Piece(child==Bone.LEFT_SHIN?Bone.LEFT_FOOT:Bone.RIGHT_FOOT,foot,p.color(),p.role()));
            } else if(!lower.isEmpty())result.add(new Piece(child,lower,p.color(),p.role()));
        }
        return List.copyOf(result);
    }

    private static FacetedMesh.Section[] jacketSections() {
        return new FacetedMesh.Section[]{
                section(-2.05f,4.9f,4.4f,0,0,3.4f),
                section(-1.4f,7.5f,4.9f,0,.04f,3.6f),
                section(-.60f,10.15f,5.5f,0,.07f,3.8f),
                section(.7f,10.1f,5.85f,0,.04f,3.8f),
                section(2.6f,9.8f,5.95f,0,0,3.7f),
                section(4.8f,9.25f,5.65f,0,0,3.6f),
                section(7.0f,8.65f,5.3f,0,.04f,3.5f),
                section(8.5f,9.0f,5.75f,.015f,.05f,3.6f),
                section(10.5f,9.30f,6.05f,0,.05f,3.7f),
                section(10.96f,9.36f,6.08f,0,.05f,3.7f),
                section(11.25f,9.28f,6.0f,0,.05f,3.7f)};
    }

    /** Both front straps and rear bag attachment sample the actual new coat shell. */
    static float shellFront(float x,float y) { return surface(jacketSections(),x,y,false); }
    static float shellBack(float x,float y) { return surface(jacketSections(),x,y,true); }

    private static void jacket(List<Piece> out) {
        var body=jacketSections();
        add(out,Bone.BODY,OLIVE,SurfaceRole.MAIN,mesh().softLoftOpen(0,0,20,body));
        // A substantial, gently flared hem; the opening remains genuinely open.
        add(out,Bone.BODY,OLIVE_DARK,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.10f,
                section(10.85f,9.39f,6.11f,0,.05f,3.7f),section(11.24f,9.32f,6.04f,0,.05f,3.7f)));
        add(out,Bone.BODY,OLIVE_LIGHT,SurfaceRole.COLLAR,mesh().openTube(0,.12f,20,.24f,
                section(-2.23f,5.5f,5.04f,0,.08f,3.4f),
                section(-1.64f,6.2f,5.55f,0,.08f,3.4f),
                section(-.73f,6.35f,5.58f,0,.10f,3.5f)));
        // Folded insulated collar/back yoke integrates the scarf into the shoulders.
        add(out,Bone.BODY,OLIVE,SurfaceRole.HOOD,mesh().softLoft(0,2.17f,20,
                section(-2.55f,3.25f,1.1f,0,0,3.1f),
                section(-2.12f,5.1f,1.8f,0,.15f,3.3f),
                section(-1.30f,6.4f,2.2f,0,.18f,3.4f),
                section(-.28f,6.3f,2.1f,0,.12f,3.5f),
                section(.8f,5.55f,1.2f,0,-.15f,3.4f),
                section(1.15f,4.6f,.42f,0,-.35f,3.2f)));
        add(out,Bone.BODY,OLIVE_DARK,SurfaceRole.DETAIL,sewn(body,0,3.05f,7.7f,.12f,.055f,true));
        add(out,Bone.BODY,OLIVE_LIGHT,SurfaceRole.PLACKET,sewn(body,.10f,5.05f,.72f,11.5f,.20f,false));
        add(out,Bone.BODY,OLIVE_DARK,SurfaceRole.PLACKET,sewn(body,.53f,5.05f,.10f,11.4f,.22f,false));
        for(float y:new float[]{.4f,2.75f,5.15f,8.6f,10.7f})
            add(out,Bone.BODY,METAL,SurfaceRole.DETAIL,sewn(body,.08f,y,.17f,.18f,.26f,false));
        for(float x:new float[]{-2.65f,2.65f}) {
            add(out,Bone.BODY,OLIVE_DARK,SurfaceRole.POCKET,sewn(body,x,8.9f,2.75f,3.35f,.50f,false));
            add(out,Bone.BODY,OLIVE,SurfaceRole.POCKET,sewn(body,x,8.92f,2.49f,3.05f,.56f,false));
            add(out,Bone.BODY,OLIVE_LIGHT,SurfaceRole.FLAP,sewn(body,x,7.39f,2.84f,.72f,.62f,false));
            add(out,Bone.BODY,METAL,SurfaceRole.DETAIL,sewn(body,x,7.51f,.19f,.18f,.73f,false));
            add(out,Bone.BODY,OLIVE_DARK,SurfaceRole.DETAIL,sewn(body,x,9.1f,.08f,2.45f,.61f,false));
        }
        // Small chest pocket remains readable between the backpack straps.
        add(out,Bone.BODY,OLIVE,SurfaceRole.POCKET,sewn(body,-1.10f,3.1f,1.65f,1.85f,.25f,false));
        add(out,Bone.BODY,OLIVE_LIGHT,SurfaceRole.FLAP,sewn(body,-1.10f,2.3f,1.72f,.40f,.32f,false));
        add(out,Bone.BODY,METAL,SurfaceRole.DETAIL,sewn(body,-1.1f,2.34f,.14f,.14f,.42f,false));
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;
            float inward=left?-.60f:.60f;
            add(out,bone,OLIVE,SurfaceRole.MAIN,mesh().softLoft(0,0,16,
                    section(-1.72f,.8f,1.0f,inward*2.15f,.04f,3.0f),
                    section(-1.3f,3.2f,3.55f,inward*1.4f,.03f,3.1f),
                    section(-.75f,4.35f,4.65f,inward*1.02f,.02f,3.4f),
                    section(.20f,4.6f,4.95f,inward*.52f,0,3.6f),
                    section(1.65f,4.65f,4.95f,inward*.15f,-.02f,3.5f),
                    section(3.0f,4.35f,4.65f,0,.06f,3.4f),
                    section(4.1f,4.23f,4.40f,0,.12f,3.3f),
                    section(5.45f,3.74f,3.95f,0,.12f,3.3f),
                    section(6.5f,3.95f,4.20f,0,.03f,3.3f),
                    section(7.8f,3.78f,3.99f,0,-.02f,3.4f),
                    section(9.45f,3.42f,3.64f,0,-.02f,3.4f),
                    section(10.05f,3.35f,3.58f,0,-.02f,3.4f)));
            add(out,bone,OLIVE_DARK,SurfaceRole.DETAIL,mesh().openTube(0,-.02f,16,.09f,
                    section(9.45f,3.49f,3.72f,0,0,3.4f),section(10.11f,3.45f,3.70f,0,0,3.4f)));
            add(out,bone,HAT,SurfaceRole.DETAIL,mesh().openTube(0,-.02f,16,.10f,
                    section(10.05f,3.22f,3.45f,0,0,3.3f),section(10.46f,3.11f,3.35f,0,0,3.3f)));
            // Small compressed folds, not separate padded armour segments.
            for(float[] fold:new float[][]{{3.8f,1.6f,.20f},{6.9f,1.35f,.18f},{8.65f,1.3f,.17f}})
                add(out,bone,OLIVE_LIGHT,SurfaceRole.DETAIL,mesh().pillow(left?.65f:-.65f,fold[0],-1.98f,fold[1],fold[2],.12f));
            add(out,bone,METAL,SurfaceRole.DETAIL,mesh().pillow(left?1.53f:-1.53f,9.78f,-.72f,.17f,.18f,.16f));
        }
        scarf(out);
    }

    private static void scarf(List<Piece> out) {
        // The upper wrap follows the head, the resting lower fold follows the jacket.
        add(out,Bone.HEAD,SCARF,SurfaceRole.COLLAR,mesh().openTube(0,-.03f,20,.24f,
                section(-2.79f,5.40f,5.65f,0,-.04f,3.8f),
                section(-2.54f,5.72f,5.92f,0,-.10f,4.0f),
                section(-1.54f,5.92f,6.07f,.04f,-.09f,3.8f),
                section(-.61f,5.69f,5.77f,.05f,-.02f,3.7f)));
        add(out,Bone.HEAD,SCARF_EDGE,SurfaceRole.DETAIL,mesh().openTube(0,-.03f,20,.11f,
                section(-2.83f,5.43f,5.70f,0,-.04f,3.8f),section(-2.62f,5.75f,5.95f,0,-.10f,4.0f)));
        add(out,Bone.HEAD,SCARF_FOLD,SurfaceRole.DETAIL,mesh().openTube(0,-.03f,20,.07f,
                section(-1.61f,5.98f,6.13f,.04f,-.09f,3.8f),section(-1.38f,5.94f,6.12f,.04f,-.08f,3.8f)));
        add(out,Bone.BODY,SCARF,SurfaceRole.COLLAR,mesh().openTube(0,.13f,20,.23f,
                section(-2.1f,5.77f,5.65f,0,0,3.5f),
                section(-1.55f,6.50f,6.02f,-.05f,.04f,3.7f),
                section(-.73f,6.55f,5.95f,-.03f,.06f,3.7f),
                section(-.35f,6.04f,5.36f,0,.10f,3.6f)));
        add(out,Bone.BODY,SCARF_FOLD,SurfaceRole.DETAIL,mesh().openTube(0,.13f,20,.08f,
                section(-.84f,6.60f,6.0f,-.03f,.06f,3.7f),
                section(-.55f,6.40f,5.80f,-.02f,.08f,3.7f)));
    }

    private static void hat(List<Piece> out) {
        // A low folded watch cap: flat softened crown, dense ribbed band, no pom-pom.
        add(out,Bone.HEAD,HAT,SurfaceRole.MAIN,mesh().softLoft(0,0,20,
                section(-7.76f,3.65f,3.95f,.09f,.05f,3.6f),
                section(-7.56f,4.92f,5.22f,.05f,.04f,3.9f),
                section(-7.08f,5.36f,5.61f,0,.02f,4.1f),
                section(-6.12f,5.52f,5.78f,0,.01f,4.1f),
                section(-5.05f,5.57f,5.86f,0,0,4.0f),
                section(-4.28f,5.54f,5.83f,0,0,4.0f)));
        add(out,Bone.HEAD,HAT,SurfaceRole.MAIN,mesh().openTube(0,0,20,.18f,
                section(-5.44f,5.70f,5.99f,0,0,4.1f),
                section(-5.18f,5.85f,6.13f,0,0,4.2f),
                section(-4.22f,5.84f,6.12f,0,0,4.2f),
                section(-4.05f,5.69f,5.97f,0,0,4.1f)));
        // Closely spaced raised knitted wales follow the softened-square band.
        for(int i=0;i<28;i++) {
            double theta=2*Math.PI*i/28.0;
            float cx=(float)Math.copySign(Math.pow(Math.abs(Math.cos(theta)),2/4.2),Math.cos(theta))*2.94f;
            float cz=(float)Math.copySign(Math.pow(Math.abs(Math.sin(theta)),2/4.2),Math.sin(theta))*3.08f;
            float nx=(float)Math.cos(theta),nz=(float)Math.sin(theta);
            // These sub-pixel knitted wales need only end profiles: the cap's
            // large crown/band masses own its silhouette, not intermediate rib rings.
            var rib=mesh().softLoft(cx,cz,8,
                    section(-5.29f,.095f,.095f,nx*.018f,nz*.018f,2.8f),
                    section(-4.12f,.085f,.085f,nx*.012f,nz*.012f,2.8f));
            add(out,Bone.HEAD,i%4==0?HAT_SEAM:HAT_RIB,SurfaceRole.DETAIL,rib);
        }
        // Flat knitted wales continue across the crown. They lie on the actual
        // twenty-sided shell, so they cannot hover above its chamfered planes.
        // Four strips per wale replace a costly stack of tiny solid prisms.
        FacetedMesh.Section[] crown={
                section(-7.76f,3.65f,3.95f,.09f,.05f,3.6f),
                section(-7.56f,4.92f,5.22f,.05f,.04f,3.9f),
                section(-7.08f,5.36f,5.61f,0,.02f,4.1f),
                section(-6.12f,5.52f,5.78f,0,.01f,4.1f),
                section(-5.43f,5.552f,5.831f,0,.0036f,4.036f)};
        for(int i=0;i<28;i++) {
            double angle=2*Math.PI*i/28.0;
            FacetedMesh wale=mesh();
            for(int row=0;row<crown.length-1;row++) {
                var a=hatSurface(crown[row],angle+.024,1,0);
                var b=hatSurface(crown[row],angle-.024,0,0);
                var c=hatSurface(crown[row+1],angle-.024,0,1);
                var d=hatSurface(crown[row+1],angle+.024,1,1);
                triangle(wale,a,b,c);triangle(wale,a,c,d);
            }
            add(out,Bone.HEAD,i%4==0?HAT_SEAM:HAT_RIB,SurfaceRole.DETAIL,wale);
        }
        // Gathered knit at the crown is a small continuous fold.
        add(out,Bone.HEAD,HAT_RIB,SurfaceRole.DETAIL,mesh().softLoft(.42f,.12f,12,
                section(-8.02f,1.52f,1.5f,0,0,3.2f),
                section(-7.86f,2.05f,1.98f,-.08f,.04f,3.4f),
                section(-7.59f,2.30f,2.13f,-.14f,.07f,3.4f)));
    }

    /** Sample a point on a native twenty-sided loft section, then offset only .018 units. */
    private static ModelPart.Vertex hatSurface(FacetedMesh.Section section,double angle,float u,float v) {
        double phase=(angle+Math.PI*.75)/(Math.PI*2/20);
        int segment=(int)Math.floor(phase);float t=(float)(phase-segment);
        float x=0,z=0;
        for(int i=0;i<2;i++) {
            double theta=-Math.PI*.75+(segment+i)*Math.PI*2/20;
            float weight=i==0?1-t:t;
            x+=weight*section.width()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.cos(theta)),2/section.power()),Math.cos(theta));
            z+=weight*section.depth()*.5f*(float)Math.copySign(Math.pow(Math.abs(Math.sin(theta)),2/section.power()),Math.sin(theta));
        }
        float length=(float)Math.sqrt(x*x+z*z);
        return new ModelPart.Vertex(section.shiftX()+x+x/length*.018f,section.y(),
                section.shiftZ()+z+z/length*.018f,u,v);
    }

    private static void pants(List<Piece> out) {
        add(out,Bone.BODY,CARGO,SurfaceRole.MAIN,mesh().softLoft(0,0,20,
                section(8.65f,7.65f,4.70f,0,.02f,3.4f),
                section(9.65f,7.83f,5.05f,0,.06f,3.5f),
                section(10.85f,7.84f,5.12f,0,.08f,3.5f),
                section(11.45f,7.60f,4.90f,0,.07f,3.3f),
                section(12.08f,6.65f,3.95f,0,.03f,3.0f),
                section(12.42f,4.85f,2.30f,0,0,2.7f)));
        add(out,Bone.BODY,CARGO_DARK,SurfaceRole.DETAIL,mesh().openTube(0,0,20,.08f,
                section(8.68f,7.72f,4.77f,0,.02f,3.4f),section(9.18f,7.85f,5.02f,0,.04f,3.4f)));
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;
            float side=left?1:-1;
            FacetedMesh.Section[] leg={
                    section(-1.35f,.65f,.85f,0,0,2.8f),
                    section(-.82f,2.90f,3.45f,0,.04f,3.2f),
                    section(-.1f,3.95f,4.5f,0,.04f,3.4f),
                    section(1.55f,4.10f,4.65f,side*.025f,.04f,3.5f),
                    section(3.7f,3.93f,4.44f,side*.035f,-.02f,3.4f),
                    section(5.25f,3.62f,4.05f,0,-.1f,3.3f),
                    section(6.0f,3.71f,4.15f,0,-.15f,3.5f),
                    section(6.9f,3.55f,3.97f,0,-.08f,3.4f),
                    section(8.3f,3.70f,4.00f,0,.08f,3.3f),
                    section(9.65f,3.45f,3.76f,0,.035f,3.2f),
                    section(10.5f,3.16f,3.43f,0,.02f,3.1f),
                    section(11.65f,3.0f,3.30f,0,0,3.1f)};
            add(out,bone,CARGO,SurfaceRole.MAIN,mesh().softLoft(0,0,16,leg));
            // A substantial outer-thigh gusset and flap, with their front edge visible in front view.
            add(out,bone,CARGO_DARK,SurfaceRole.POCKET,mesh().softLoft(side*1.96f,-.19f,12,
                    section(2.25f,.16f,2.20f,0,0,3.4f),
                    section(2.58f,.48f,2.97f,side*.03f,-.02f,3.6f),
                    section(4.54f,.55f,3.06f,side*.03f,-.02f,3.6f),
                    section(5.13f,.23f,2.58f,0,0,3.4f)));
            add(out,bone,CARGO_LIGHT,SurfaceRole.FLAP,mesh().softLoft(side*2.10f,-.20f,12,
                    section(2.20f,.26f,2.84f,0,0,3.6f),
                    section(2.40f,.53f,3.12f,side*.04f,0,3.7f),
                    section(2.89f,.51f,3.08f,side*.04f,0,3.7f),
                    section(3.02f,.20f,2.71f,0,0,3.5f)));
            for(float z:new float[]{-1.15f,.78f})
                add(out,bone,METAL,SurfaceRole.DETAIL,mesh().softLoft(side*2.39f,z,8,
                        section(2.55f,.12f,.16f,0,0,3),section(2.75f,.12f,.16f,0,0,3)));
            add(out,bone,CARGO_LIGHT,SurfaceRole.DETAIL,sewn(leg,0,5.82f,2.45f,.76f,.16f,false));
            add(out,bone,CARGO_DARK,SurfaceRole.DETAIL,sewn(leg,-side*.30f,6.66f,2.65f,.17f,.11f,false));
            add(out,bone,CARGO_LIGHT,SurfaceRole.DETAIL,sewn(leg,side*.26f,8.48f,2.30f,.23f,.13f,false));
            add(out,bone,CARGO_DARK,SurfaceRole.DETAIL,sewn(leg,0,4.96f,2.22f,.15f,.10f,true));
        }
    }

    private static void gloves(List<Piece> out) {
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;
            add(out,bone,GLOVE,SurfaceRole.MAIN,mesh().softLoft(0,0,16,
                    section(9.85f,2.88f,3.22f,0,-.025f,3.3f),
                    section(10.4f,2.95f,3.23f,0,-.04f,3.5f),
                    section(11.12f,3.08f,3.27f,0,-.07f,3.7f),
                    section(11.93f,2.92f,3.15f,0,-.1f,3.7f),
                    section(12.31f,2.55f,2.79f,0,-.1f,3.4f),
                    section(12.47f,1.80f,2.10f,0,-.08f,3.2f)));
            add(out,bone,GLOVE,SurfaceRole.MAIN,mesh().softLoft(left?-1.18f:1.18f,-.66f,12,
                    section(10.08f,.64f,.95f,0,-.02f,3),
                    section(10.55f,1.20f,1.72f,0,-.12f,3.2f),
                    section(11.40f,1.22f,1.76f,0,-.24f,3.3f),
                    section(11.97f,.65f,1.0f,0,-.16f,3)));
            add(out,bone,CARGO_DARK,SurfaceRole.DETAIL,mesh().pillow(0,11.0f,1.59f,2.12f,1.25f,.13f));
            for(float x:new float[]{-.75f,0,.75f})
                add(out,bone,RUBBER,SurfaceRole.DETAIL,mesh().pillow(x,11.89f,-1.61f,.075f,.40f,.055f));
        }
    }

    private static void boots(List<Piece> out) {
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;
            float offset=left?.25f:-.25f;
            // The heavy toe/sole sits slightly outboard of the cuff. Keep the
            // cuff centred on the tucked trouser leg, and leave actual clearance
            // between the two irregular snow rims in a neutral planted stance.
            float footOffset=offset+(left?.09f:-.09f);
            FacetedMesh.Section[] upper={
                    section(8.25f,3.98f,4.17f,offset,.06f,3.8f),
                    section(8.55f,4.12f,4.31f,offset,.05f,3.9f),
                    section(9.75f,3.88f,4.15f,offset,.03f,3.8f),
                    section(10.75f,3.70f,4.05f,offset,-.07f,3.7f),
                    section(11.4f,3.89f,4.74f,(offset+footOffset)*.5f,-.42f,3.6f),
                    section(11.9f,4.20f,5.61f,footOffset,-.81f,3.8f),
                    section(12.40f,4.35f,5.91f,footOffset,-.94f,4.0f),
                    section(12.91f,4.40f,6.0f,footOffset,-.97f,4.1f)};
            add(out,bone,LEATHER,SurfaceRole.MAIN,mesh().softLoft(0,0,20,upper));
            add(out,bone,RUBBER,SurfaceRole.MAIN,mesh().softLoft(footOffset,-.98f,20,
                    section(12.86f,4.43f,6.06f,0,0,4.1f),
                    section(13.08f,4.53f,6.16f,0,0,4.1f),
                    section(13.61f,4.52f,6.15f,0,0,4.0f),
                    section(13.80f,4.40f,6.05f,0,0,3.9f)));
            add(out,bone,SOLE_EDGE,SurfaceRole.DETAIL,mesh().openTube(footOffset,-.98f,20,.10f,
                    section(13.03f,4.56f,6.20f,0,0,4.1f),
                    section(13.24f,4.56f,6.20f,0,0,4.1f)));
            // Thick cuff and two fastened leather straps are distinct from the leg.
            add(out,bone,CARGO_DARK,SurfaceRole.DETAIL,mesh().openTube(offset,.06f,20,.17f,
                    section(8.14f,4.12f,4.32f,0,0,3.8f),
                    section(8.50f,4.21f,4.41f,0,0,3.9f)));
            for(float y:new float[]{8.78f,10.12f}) {
                float width=y<9?4.20f:3.96f,depth=y<9?4.39f:4.30f;
                add(out,bone,LEATHER_EDGE,SurfaceRole.DETAIL,mesh().openTube(offset,.02f,20,.14f,
                        section(y,width,depth,0,0,3.9f),section(y+.40f,width-.02f,depth,0,0,3.9f)));
                add(out,bone,METAL,SurfaceRole.DETAIL,sewn(upper,offset+(left?.93f:-.93f),y+.19f,.62f,.51f,.19f,false));
                add(out,bone,LEATHER,SurfaceRole.DETAIL,sewn(upper,offset+(left?.93f:-.93f),y+.19f,.30f,.23f,.24f,false));
            }
            add(out,bone,LEATHER_EDGE,SurfaceRole.DETAIL,sewn(upper,offset,11.51f,1.48f,1.48f,.16f,false));
            for(float y:new float[]{10.95f,11.31f,11.67f})
                add(out,bone,CARGO_LIGHT,SurfaceRole.DETAIL,sewn(upper,offset,y,1.11f,.09f,.24f,false));
            // Irregular snow on the sole rim, not a continuous white painted boot.
            for(int i=0;i<7;i++) {
                float x=footOffset-1.62f+i*.54f;
                float y=13.38f+(i%3)*.055f;
                float z=-.98f-3.09f*(float)Math.pow(1-Math.pow(Math.abs((x-footOffset)/2.28f),4.1),1/4.1);
                add(out,bone,SNOW,SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                        section(y-.17f,.38f,.13f,0,0,3.5f),
                        section(y+.16f,.46f,.16f,0,0,3.7f)));
            }
            for(int side:new int[]{-1,1})for(int i=0;i<4;i++) {
                float z=-2.95f+i*1.32f;
                float fraction=Math.abs((z+.98f)/3.09f);
                float x=footOffset+side*2.28f*(float)Math.pow(1-Math.pow(fraction,4.1),1/4.1);
                float y=13.36f+(i%2)*.13f;
                add(out,bone,SNOW,SurfaceRole.DETAIL,mesh().softLoft(x,z,8,
                        section(y-.15f,.12f,.48f,0,0,3.4f),
                        section(y+.16f,.18f,.62f,0,0,3.5f)));
            }
            for(float x:new float[]{footOffset-1.43f,footOffset,footOffset+1.43f})
                add(out,bone,RUBBER,SurfaceRole.DETAIL,mesh().softLoft(x,-3.98f,8,
                        section(13.40f,.45f,.18f,0,0,3.5f),
                        section(13.78f,.48f,.22f,0,0,3.5f)));
        }
    }

    /** Thin rounded rectangular sewn patches, rather than floating pocket boxes. */
    static FacetedMesh sewn(FacetedMesh.Section[] shell,float x,float y,float w,float h,float d,boolean back) {
        var local=mesh().softLoft(x,0,12,
                section(y-h*.5f,w*.87f,d*.30f,0,0,4.0f),
                section(y-h*.5f+Math.min(.12f,h*.18f),w,d,0,0,4.0f),
                section(y+h*.5f-Math.min(.12f,h*.18f),w,d,0,0,4.0f),
                section(y+h*.5f,w*.88f,d*.32f,0,0,4.0f)).bake();
        FacetedMesh result=mesh();
        local.visit(new PoseStack(),(pose,path,index,cube)->{
            for(var face:cube.polygons) {
                var v=face.vertices().clone();
                for(int i=0;i<v.length;i++) {
                    var p=v[i];float z=surface(shell,p.x(),p.y(),back);
                    v[i]=new ModelPart.Vertex(p.x(),p.y(),z+p.z()+(back?1:-1)*d*.40f,p.u(),p.v());
                }
                triangle(result,v[0],v[1],v[2]);triangle(result,v[0],v[2],v[3]);
            }
        });
        return result;
    }

    private static void triangle(FacetedMesh mesh,ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        var normal=new org.joml.Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z())
                .cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z());
        if(normal.lengthSquared()>1e-10f)mesh.face(a,b,c,c);
    }

    static float surface(FacetedMesh.Section[] shell,float x,float y,boolean back) {
        int row=0;while(row<shell.length-2&&y>shell[row+1].y())row++;
        var a=shell[row];var b=shell[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return surface(a,x,back)*(1-t)+surface(b,x,back)*t;
    }

    static float surface(FacetedMesh.Section s,float x,boolean back) {
        float f=Math.min(.999f,Math.abs(x-s.shiftX())/(s.width()*.5f));
        float z=s.depth()*.5f*(float)Math.pow(1-Math.pow(f,s.power()),1/s.power());
        return s.shiftZ()+(back?z:-z);
    }

    static void add(List<Piece> out,Bone bone,int color,SurfaceRole role,FacetedMesh mesh) {
        out.add(new Piece(bone,mesh.bake(),color,role));
    }
    private NorthernWandererClothing() { }
}
