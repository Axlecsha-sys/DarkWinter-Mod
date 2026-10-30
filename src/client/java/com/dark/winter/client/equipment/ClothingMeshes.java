// Soft, shaped fabric silhouettes on the existing animated player skeleton.
package com.dark.winter.client.equipment;

import com.dark.winter.equipment.GearSlot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/** Soft, shaped fabric silhouettes on the existing animated player skeleton. */
public final class ClothingMeshes {
    static final GearSlot[] SLOTS={GearSlot.TSHIRT,GearSlot.SWEATER,GearSlot.PANTS,GearSlot.SOCKS,
            GearSlot.JACKET,GearSlot.FOOTWEAR,GearSlot.GLOVES,GearSlot.HAT};
    public enum Bone { HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, LEFT_FOREARM, RIGHT_FOREARM, LEFT_SHIN, RIGHT_SHIN, LEFT_FOOT, RIGHT_FOOT }
    public enum SurfaceRole { MAIN, COLLAR, HOOD, POCKET, FLAP, PLACKET, DETAIL }
    record Piece(Bone bone, ModelPart geometry, int color,SurfaceRole role) {
        Piece(Bone bone,ModelPart geometry,int color){this(bone,geometry,color,SurfaceRole.MAIN);}
    }
    private static final int NAVY=0xFF34475B, OLIVE=0xFF68705A, RED=0xFFA74A43, OCHRE=0xFFB19050,
            WOOL=0xFFB9B5A4, CHARCOAL=0xFF454A50, LEATHER=0xFF795A40, RUBBER=0xFF303335;
    static final Map<Integer,List<Piece>> WARDROBE=create();

    /** Legacy keys stay stable; expanded variants occupy a separate collision-free range. */
    public static int key(GearSlot slot,int variant) {
        return variant<8?slot.ordinal()*8+variant:128+slot.ordinal()*8+(variant-8);
    }

    /** Read-only references used by the native geometry review and client smoke checks. */
    public static Map<Integer,List<ModelPart>> geometry() {
        Map<Integer,List<ModelPart>> result=new HashMap<>();
        WARDROBE.forEach((key,pieces)->result.put(key,pieces.stream().map(Piece::geometry).toList()));
        return Map.copyOf(result);
    }

    private static void add(List<Piece> out,Bone bone,int color,FacetedMesh shape) { out.add(new Piece(bone,shape.bake(),color)); }
    private static void add(List<Piece> out,Bone bone,int color,SurfaceRole role,FacetedMesh shape) {out.add(new Piece(bone,shape.bake(),color,role));}
    private static void collar(List<Piece> out,int color,float y,float width,float depth,float height,float thickness) {
        add(out,Bone.BODY,color,SurfaceRole.COLLAR,mesh().openTube(0,0,20,thickness,
                section(y,width*.96f,depth*.98f,0,0,2.6f),
                section(y+height*.35f,width,depth,0,0,2.6f),
                section(y+height,width+.14f,depth+.1f,0,0,2.7f)));
    }

    private static Map<Integer,List<Piece>> create() {
        Map<Integer,List<Piece>> result=new HashMap<>();
        for (GearSlot slot:SLOTS) for (int variant=1;variant<=switch(slot) { case TSHIRT,SOCKS->3;case JACKET->6;default->4; };variant++) {
            List<Piece> p=new ArrayList<>();
            switch(slot) {
                case TSHIRT->top(p,variant==1?WOOL:variant==2?NAVY:RED,0,false,false,false);
                case SWEATER->top(p,variant==1?OLIVE:variant==2?NAVY:variant==3?OCHRE:RED,.27f,true,false,variant==4);
                case JACKET->{
                    top(p,jacketColor(variant),variant==2||variant==3||variant==4?.85f:.56f,true,true,variant==6);
                    jacketDetails(p,variant);
                }
                case PANTS->pants(p,variant);
                case SOCKS->socks(p,variant);
                case FOOTWEAR->boots(p,variant);
                case GLOVES->gloves(p,variant);
                case HAT->hat(p,variant);
                default->{ }
            }
            List<Piece> articulated=new ArrayList<>();
            for(Piece piece:p) {
                boolean arm=piece.bone==Bone.LEFT_ARM||piece.bone==Bone.RIGHT_ARM;
                boolean leg=piece.bone==Bone.LEFT_LEG||piece.bone==Bone.RIGHT_LEG;
                if(!arm&&!leg){articulated.add(piece);continue;}
                var split=ArticulatedMesh.split(piece.geometry,arm?HumanJoints.ELBOW_Y:HumanJoints.KNEE_Y);
                ModelPart upper=split.upper().bake(),lower=split.lower().bake();
                Bone child=switch(piece.bone){case LEFT_ARM->Bone.LEFT_FOREARM;case RIGHT_ARM->Bone.RIGHT_FOREARM;
                    case LEFT_LEG->Bone.LEFT_SHIN;case RIGHT_LEG->Bone.RIGHT_SHIN;default->throw new AssertionError();};
                if(!upper.isEmpty())articulated.add(new Piece(piece.bone,upper,piece.color,piece.role));
                if(leg) {
                    var ankle=ArticulatedMesh.split(lower,HumanJoints.ANKLE_Y-HumanJoints.KNEE_Y);
                    var shin=ankle.upper().bake();var foot=ankle.lower().bake();
                    if(!shin.isEmpty())articulated.add(new Piece(child,shin,piece.color,piece.role));
                    if(!foot.isEmpty())articulated.add(new Piece(child==Bone.LEFT_SHIN?Bone.LEFT_FOOT:Bone.RIGHT_FOOT,foot,piece.color,piece.role));
                } else if(!lower.isEmpty())articulated.add(new Piece(child,lower,piece.color,piece.role));
            }
            result.put(slot.ordinal()*8+variant,List.copyOf(articulated));
        }
        for(GearSlot slot:List.of(GearSlot.JACKET,GearSlot.HAT,GearSlot.PANTS,GearSlot.GLOVES,GearSlot.FOOTWEAR))
            result.put(slot.ordinal()*8+(slot==GearSlot.JACKET?7:5),NorthernWandererClothing.pieces(slot));
        for(var design:com.dark.winter.equipment.CinematicWardrobe.DESIGNS)
            result.put(key(design.slot(),design.variant()),CinematicClothing.pieces(design.slot(),design.number()));
        return Map.copyOf(result);
    }

    private static int jacketColor(int variant) {
        return switch(variant){case 1->OLIVE;case 2->0xFF535C52;case 3->RED;case 4->NAVY;case 5->OCHRE;default->CHARCOAL;};
    }

    private static void jacketDetails(List<Piece> out,int style) {
        int color=jacketColor(style);
        if(style==5) {
            // The anorak's kangaroo pocket is sewn into the front, with a soft lower edge.
            add(out,Bone.BODY,shade(color,.96f),SurfaceRole.POCKET,sewn(topSections(.56f,11.35f),0,8.35f,5.3f,2.65f,.23f));
        }
        if(style==6) for(Bone arm:new Bone[]{Bone.LEFT_ARM,Bone.RIGHT_ARM})
            add(out,arm,shade(color,.96f),SurfaceRole.DETAIL,mesh().pillow(0,5.0f,1.66f,1.5f,1.5f,.12f));
        if(style>=2&&style<=5) {
            // A single folded hood on the upper back, rather than stacked padded rings.
            add(out,Bone.BODY,shade(color,.96f),SurfaceRole.HOOD,mesh().softLoft(0,2.78f,20,
                    section(-2.75f,2.75f,.7f,0,-.02f,2.2f),
                    section(-2.4f,4.1f,1.35f,0,.05f,2.3f),
                    section(-1.65f,5.4f,1.85f,0,.13f,2.4f),
                    section(-.55f,5.7f,1.9f,0,.05f,2.5f),
                    section(.55f,4.8f,1.35f,0,-.2f,2.5f),
                    section(1.05f,3.9f,.65f,0,-.3f,2.3f)));
            add(out,Bone.BODY,shade(color,1.04f),SurfaceRole.HOOD,mesh().pillow(0,-1.85f,3.03f,3.15f,.8f,.15f));
        }
    }

    private static void top(List<Piece> out,int color,float bulk,boolean longSleeves,boolean coat,boolean work) {
        float hem=coat?(bulk>.7f?12.45f:11.35f):longSleeves?10.85f:10.4f;
        FacetedMesh.Section[] body=topSections(bulk,hem);
        // An open sewn shell: no opaque disc below the shirt and no cap crossing the neck.
        add(out,Bone.BODY,color,mesh().softLoftOpen(0,0,20,body));
        collar(out,shade(color,.97f),coat?-1.92f:longSleeves?-1.73f:-1.56f,
                coat?4.95f:3.85f+bulk*1.7f,coat?4.6f:3.6f+bulk*1.35f,coat?1.2f:longSleeves?.8f:.48f,coat?.16f:.1f);
        var last=body[body.length-1];
        add(out,Bone.BODY,shade(color,.985f),SurfaceRole.DETAIL,mesh().openTube(0,0,20,.065f,
                section(hem-.14f,last.width()+.015f,last.depth()+.015f,0,last.shiftZ(),last.power()),
                section(hem,last.width(),last.depth(),0,last.shiftZ(),last.power())));
        for (Bone bone:new Bone[]{Bone.LEFT_ARM,Bone.RIGHT_ARM})
            add(out,bone,color,sleeve(bone==Bone.LEFT_ARM,bulk,longSleeves));
        if (coat || work) {
            add(out,Bone.BODY,shade(color,.87f),SurfaceRole.PLACKET,sewn(body,0,(hem-.25f)/2,.12f,hem-.25f,.075f));
            if(coat) {
                for(float x:new float[]{-2.15f,2.15f}) {
                    add(out,Bone.BODY,shade(color,.975f),SurfaceRole.POCKET,sewn(body,x,8.1f,2.35f,2.75f,.22f));
                    add(out,Bone.BODY,shade(color,1.015f),SurfaceRole.FLAP,sewn(body,x,6.98f,2.3f,.4f,.14f));
                }
                add(out,Bone.BODY,shade(color,.975f),SurfaceRole.POCKET,sewn(body,-2.05f,2.6f,1.8f,2.1f,.15f));
                add(out,Bone.BODY,0xFFABA795,SurfaceRole.DETAIL,sewn(body,0,1.15f,.13f,.38f,.11f));
            } else for(float x:new float[]{-2.05f,2.05f})
                add(out,Bone.BODY,shade(color,.98f),SurfaceRole.POCKET,sewn(body,x,2.65f,1.75f,1.9f,.13f));
        }
    }

    private static FacetedMesh.Section[] topSections(float bulk,float hem) {
        return new FacetedMesh.Section[]{
                section(-1.82f-bulk*.12f,3.7f+bulk*1.1f,3.3f+bulk*.85f,0,0,2.8f),
                section(-1.38f,6.55f+bulk*1.55f,3.45f+bulk*1.1f,0,.02f,3.0f),
                section(-.72f,8.65f+bulk*1.35f,3.95f+bulk*1.3f,0,.02f,3.1f),
                section(.65f,8.45f+bulk*1.4f,4.2f+bulk*1.45f,0,-.015f,3.1f),
                section(2.6f,7.95f+bulk*1.55f,4.25f+bulk*1.45f,0,-.04f,3.0f),
                section(4.8f,7.4f+bulk*1.6f,3.95f+bulk*1.4f,0,-.02f,2.9f),
                section(7.05f,6.72f+bulk*1.65f,3.65f+bulk*1.45f,.015f,.025f,2.8f),
                section(8.25f,7.2f+bulk*1.65f,4.25f+bulk*1.45f,-.015f,.025f,2.9f),
                section(9.35f,7.5f+bulk*1.65f,4.5f+bulk*1.45f,0,.025f,2.9f),
                section(hem-.26f,7.58f+bulk*1.65f,4.58f+bulk*1.45f,0,.02f,2.9f),
                section(hem,7.52f+bulk*1.65f,4.56f+bulk*1.45f,0,.02f,2.9f)};
    }

    /** Mirror the deltoid into the torso; the animated arm pivot and hand position stay unchanged. */
    private static FacetedMesh sleeve(boolean left,float bulk,boolean longSleeves) {
        float inward=left?-.6f:.6f;
        List<FacetedMesh.Section> s=new ArrayList<>(List.of(
                section(-1.5f-bulk*.12f,.45f+bulk*.25f,.65f+bulk*.3f,inward*2.2f,0,2.6f),
                section(-1.2f,2.35f+bulk*.95f,2.65f+bulk*.95f,inward*1.4f,0,2.8f),
                section(-.85f,3.15f+bulk*1.05f,3.15f+bulk*1.05f,inward*1.15f,0,2.9f),
                section(-.18f,3.4f+bulk*1.05f,3.5f+bulk*1.05f,inward*.6f,0,3.0f),
                section(1.35f,3.34f+bulk*1.05f,3.48f+bulk*1.05f,inward*.25f,-.025f,2.9f),
                section(3.55f,2.95f+bulk*1.05f,3.08f+bulk*1.05f,0,.025f,2.8f)));
        if(longSleeves) {
            s.add(section(5.45f,2.58f+bulk*.95f,2.65f+bulk*.95f,0,.08f,2.8f));
            s.add(section(6.7f,2.95f+bulk*.95f,3.05f+bulk*.95f,0,-.015f,2.9f));
            s.add(section(8.3f,2.75f+bulk*.9f,2.85f+bulk*.95f,0,-.04f,2.8f));
            s.add(section(9.75f,2.3f+bulk*.9f,2.42f+bulk*.95f,0,-.05f,2.8f));
            s.add(section(10.32f,2.5f+bulk*.85f,2.75f+bulk*.85f,0,-.07f,2.9f));
        } else s.add(section(4.3f,2.8f,2.95f,0,.045f,2.8f));
        return mesh().softLoft(0,0,16,s.toArray(FacetedMesh.Section[]::new));
    }

    /** Pocket/flap vertices follow the shell curvature, with their back embedded in the cloth. */
    private static FacetedMesh sewn(FacetedMesh.Section[] body,float x,float y,float width,float height,float depth) {
        FacetedMesh fitted=mesh();
        mesh().pillow(x,y,0,width,height,depth).bake().visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,path,index,cube)->{
            for(var face:cube.polygons) {
                var vertices=face.vertices().clone();
                for(int i=0;i<vertices.length;i++) {
                    var p=vertices[i];vertices[i]=new ModelPart.Vertex(p.x(),p.y(),front(body,p.x(),p.y())+p.z()-depth*.08f,p.u(),p.v());
                }
                fitted.face(vertices[0],vertices[1],vertices[2],vertices[2]);
                var a=vertices[0];var b=vertices[2];var c=vertices[3];
                if(new org.joml.Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()).cross(c.x()-a.x(),c.y()-a.y(),c.z()-a.z()).lengthSquared()>1e-9)
                    fitted.face(vertices[0],vertices[2],vertices[3],vertices[3]);
            }
        });
        return fitted;
    }
    private static float front(FacetedMesh.Section[] body,float x,float y) {
        int row=0;while(row<body.length-2&&y>body[row+1].y())row++;
        var a=body[row];var b=body[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        return front(a,x)*(1-t)+front(b,x)*t;
    }
    private static float front(FacetedMesh.Section section,float x) {
        float fraction=Math.min(.999f,Math.abs(x-section.shiftX())/(section.width()*.5f));
        return section.shiftZ()-section.depth()*.5f*(float)Math.pow(1-Math.pow(fraction,section.power()),1/section.power());
    }

    /** Shared shell surface for close-fitting backpack straps; does not change animated bones. */
    static float shellFront(float bulk,float x,float y) {return front(topSections(bulk,12.45f),x,y);}
    static float shellBack(float bulk,float x,float y) {
        var body=topSections(bulk,12.45f);int row=0;while(row<body.length-2&&y>body[row+1].y())row++;
        var a=body[row];var b=body[row+1];float t=Math.clamp((y-a.y())/(b.y()-a.y()),0,1);
        float shift=a.shiftZ()*(1-t)+b.shiftZ()*t;
        return shift*2-front(body,x,y);
    }
    private static void pants(List<Piece> out,int variant) {
        int color=variant==1?LEATHER:variant==2?CHARCOAL:variant==3?OLIVE:NAVY;
        float bulk=variant==2||variant==4?.27f:.12f;
        add(out,Bone.BODY,color,mesh().softLoft(0,0,20,
                section(8.65f,7.2f,4.05f,0,0,2.8f),section(9.45f,7.35f,4.4f,0,.03f,2.9f),
                section(10.65f,7.18f,4.45f,0,.04f,2.8f),
                // One trouser yoke/seat bridges the opposing torso and thigh rotations.
                // Its tucked crotch lies inside the two leg shells, rather than ending at the hip joint.
                section(11.4f,7.3f,4.65f,0,.03f,2.8f),
                section(12.05f,6.4f,3.5f,0,.01f,2.6f),
                section(12.35f,4.9f,2.15f,0,0,2.4f)));
        add(out,Bone.BODY,shade(color,.85f),SurfaceRole.DETAIL,mesh().pillow(0,9.1f,-2.11f,6.5f,.3f,.12f));
        add(out,Bone.BODY,0xFF9E9A89,SurfaceRole.DETAIL,mesh().pillow(0,9.1f,-2.2f,.48f,.4f,.09f));
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_LEG:Bone.RIGHT_LEG;
            add(out,bone,color,mesh().softLoft(0,0,16,
                    // The small buried apex cannot turn into an exposed, full-thigh disc when crouching.
                    section(-1.35f,.6f,.8f,0,0,2.5f),
                    section(-.85f,2.6f,3.1f,0,0,2.5f),
                    section(-.2f,3.55f+bulk,4+bulk,0,0,2.5f),
                    section(1.55f,3.72f+bulk,4.1f+bulk,0,-.015f,2.45f),
                    section(3.8f,3.48f+bulk,3.9f+bulk,0,-.02f,2.4f),
                    section(5.5f,3.14f+bulk,3.56f+bulk,0,-.1f,2.35f),
                    section(6.15f,3.23f+bulk,3.65f+bulk,0,-.13f,2.35f),
                    section(6.8f,3.12f+bulk,3.55f+bulk,0,-.07f,2.35f),
                    section(8.6f,3.28f+bulk,3.58f+bulk,0,.04f,2.35f),
                    section(10.3f,3.05f+bulk,3.38f+bulk,0,.025f,2.35f),
                    section(12.2f,2.85f+bulk,3.18f+bulk,0,0,2.4f),
                    section(13.6f,2.91f+bulk,3.22f+bulk,0,0,2.45f)));
            if(variant==1||variant==3) {
                // Cargo pockets lie against the outside thigh and taper into their stitching.
                add(out,bone,shade(color,.96f),SurfaceRole.POCKET,mesh().softLoft(left?1.67f:-1.67f,0,16,
                        section(2.4f,.17f,1.7f,0,0,2.6f),section(2.7f,.31f,2.25f,0,0,2.7f),
                        section(4.4f,.34f,2.35f,0,0,2.7f),section(5.2f,.15f,1.8f,0,0,2.5f)));
            }
        }
    }

    private static void socks(List<Piece> out,int variant) {
        int color=variant==1?WOOL:variant==2?0xFF9C9B91:OCHRE;
        for(Bone bone:new Bone[]{Bone.LEFT_LEG,Bone.RIGHT_LEG})
            add(out,bone,color,mesh().softLoft(0,0,16,
                    section(8.2f,3.2f,3.38f),section(8.5f,3.26f,3.44f),
                    section(9.3f,3.2f,3.4f),section(11.55f,3.04f,3.25f),
                    section(12.15f,3.26f,4.05f,0,-.3f,2.4f),
                    section(12.7f,3.42f,4.88f,0,-.65f,2.5f),
                    section(13.4f,3.47f,4.95f,0,-.69f,2.5f),
                    section(13.8f,3.4f,4.87f,0,-.67f,2.5f)));
    }

    private static void boots(List<Piece> out,int variant) {
        boolean insulated=variant==2||variant==3;
        int color=variant==2?CHARCOAL:variant==3?OCHRE:variant==4?0xFF6C4D39:LEATHER;
        for(Bone bone:new Bone[]{Bone.LEFT_LEG,Bone.RIGHT_LEG}) {
            float top=insulated?8.0f:9.3f;
            add(out,bone,color,mesh().softLoft(0,0,20,
                    section(top,3.72f,3.86f,0,.05f,2.4f),
                    section(top+.45f,3.8f,3.94f,0,.05f,2.4f),
                    section(10.5f,3.52f,3.72f,0,.02f,2.4f),
                    section(11.25f,3.45f,4.05f,0,-.18f,2.35f),
                    section(11.7f,3.68f,4.8f,0,-.52f,2.4f),
                    section(12.15f,3.92f,5.38f,0,-.78f,2.45f),
                    section(12.65f,4.08f,5.68f,0,-.87f,2.5f),
                    section(13.05f,4.1f,5.72f,0,-.88f,2.6f)));
            add(out,bone,RUBBER,mesh().softLoft(0,-.88f,20,
                    section(13.03f,4.13f,5.77f,0,0,2.6f),
                    section(13.3f,4.2f,5.84f,0,0,2.6f),
                    section(13.8f,4.15f,5.8f,0,0,2.6f)));
            // Small laces follow the instep. The toe itself is part of the continuous upper.
            for(float y:new float[]{10.0f,10.55f,11.1f})
                add(out,bone,shade(color,1.19f),SurfaceRole.DETAIL,mesh().pillow(0,y,-1.89f,1.35f,.105f,.09f));
            add(out,bone,shade(color,.94f),SurfaceRole.DETAIL,mesh().softLoft(0,1.92f,12,
                    section(11.15f,2.25f,.14f),section(11.9f,2.8f,.2f),section(12.85f,2.85f,.18f)));
        }
    }

    private static void gloves(List<Piece> out,int variant) {
        int color=variant==1?WOOL:variant==2?NAVY:variant==3?LEATHER:OLIVE;
        for(boolean left:new boolean[]{false,true}) {
            Bone bone=left?Bone.LEFT_ARM:Bone.RIGHT_ARM;
            add(out,bone,color,mesh().softLoft(0,0,16,
                    section(9.65f,2.73f,3.02f),section(10.05f,2.8f,3.08f),
                    section(10.65f,2.87f,3.18f,0,-.025f,2.4f),
                    section(11.3f,3.02f,3.28f,0,-.06f,2.5f),
                    section(12.05f,2.9f,3.15f,0,-.09f,2.45f),
                    section(12.3f,2.57f,2.82f,0,-.08f,2.3f),
                    section(12.45f,1.8f,2.0f,0,-.06f,2.2f)));
            // The thumb follows the new palm's forward offset, with cloth extending past its tip.
            add(out,bone,color,mesh().softLoft(left?-1.15f:1.15f,-.65f,16,
                    section(10.05f,.65f,1.0f,0,-.02f,2.4f),
                    section(10.35f,.95f,1.5f,0,-.05f,2.5f),
                    section(10.7f,1.28f,1.85f,0,-.18f,2.7f),
                    section(11.4f,1.22f,1.75f,0,-.24f,2.7f),
                    section(11.85f,.88f,1.3f,0,-.2f,2.5f),
                    section(12.05f,.55f,.85f,0,-.13f,2.3f)));
        }
    }

    private static void hat(List<Piece> out,int variant) {
        int color=variant==1?OLIVE:variant==2?LEATHER:variant==3?RED:NAVY;
        add(out,Bone.HEAD,color,mesh().softLoft(0,0,24,
                section(-7.15f,.7f,.85f,0,.06f,2.2f),
                section(-6.95f,2.2f,2.5f,0,.04f,2.4f),
                section(-6.6f,3.8f,4.2f,0,.02f,2.7f),
                section(-6.15f,4.75f,5.15f,0,0,3.0f),
                section(-5.6f,5.05f,5.45f,0,0,3.2f),
                section(-4.55f,5.15f,5.5f,0,0,3.2f)));
        add(out,Bone.HEAD,shade(color,.96f),mesh().openTube(0,0,24,.13f,
                section(-5.05f,5.2f,5.56f,0,0,3.2f),section(-4.78f,5.25f,5.62f,0,0,3.2f),
                section(-4.25f,5.18f,5.55f,0,0,3.2f)));
        if(variant==2||variant==4) {
            for(float x:new float[]{-2.49f,2.49f})
                add(out,Bone.HEAD,color,mesh().softLoft(x,.05f,16,
                        section(-4.85f,.7f,2.8f,0,.2f,2.4f),
                        section(-4.1f,.74f,3.05f,0,.28f,2.4f),
                        section(-2.65f,.61f,2.6f,0,.2f,2.35f),
                        section(-1.85f,.32f,1.5f,0,.15f,2.1f)));
            add(out,Bone.HEAD,color,mesh().pillow(0,-3.45f,2.4f,4.5f,2.5f,.42f));
        }
    }

    private static int shade(int color,float amount) {
        return 0xFF000000|Math.min(255,(int)(((color>>>16)&255)*amount))<<16
                |Math.min(255,(int)(((color>>>8)&255)*amount))<<8|Math.min(255,(int)((color&255)*amount));
    }
    private ClothingMeshes() { }
}
