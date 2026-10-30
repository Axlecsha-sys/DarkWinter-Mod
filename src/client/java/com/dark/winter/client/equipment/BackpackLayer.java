// A compact pack attached to the animated torso, not the camera or the world position.
package com.dark.winter.client.equipment;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import static com.dark.winter.client.equipment.FacetedMesh.mesh;
import static com.dark.winter.client.equipment.FacetedMesh.section;

/** A compact pack attached to the animated torso, not the camera or the world position. */
public final class BackpackLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public static final RenderStateDataKey<Boolean> EQUIPPED = RenderStateDataKey.create(() -> "darkwinter:backpack");
    private static final Identifier CANVAS_TEXTURE = Identifier.fromNamespaceAndPath("darkwinter", "textures/entity/clothing_canvas.png");
    private static final Identifier RUBBER_TEXTURE = Identifier.fromNamespaceAndPath("darkwinter", "textures/entity/clothing_rubber.png");
    private static final int CANVAS=0xFF5F6D59, FLAP=0xFF707B64, POCKET=0xFF57634F,
            WEBBING=0xFF9E8B64, PADDING=0xFF343D39, HARDWARE=0xFF646B65;
    record Piece(ModelPart geometry,int color,boolean harness) {
        Piece(ModelPart geometry,int color){this(geometry,color,false);}
    }
    private static final float[] SHELL_BULK={0,.27f,.56f,.85f,1};
    private final List<List<Piece>> packs = List.of(createPack(0),createPack(.27f),createPack(.56f),createPack(.85f),createPack(1));

    private final List<List<Piece>> fittedPacks = java.util.stream.IntStream.range(0,packs.size()).mapToObj(index ->
            packs.get(index).stream().map(piece -> new Piece(ThirdPersonContours.backpack(piece.geometry(),
                    SHELL_BULK[index],piece.harness()),piece.color(),piece.harness())).toList()).toList();

    private static final float[] NORTHERN_BULK={0,.27f,.56f,.85f,1};
    private static final List<List<Piece>> NORTHERN_PACKS=java.util.stream.IntStream.range(0,5)
            .mapToObj(index->NorthernWandererPack.create(NORTHERN_BULK[index],index==4)).toList();
    private static final List<List<Piece>> FITTED_NORTHERN_PACKS=java.util.stream.IntStream.range(0,5)
            .mapToObj(index->NorthernWandererPack.fitted(NORTHERN_PACKS.get(index),NORTHERN_BULK[index])).toList();

    // Catalog fitting is prepared once per cut, preserving both bags' rigid geometry.
    private static final float[] CATALOG_BULK=java.util.stream.IntStream.rangeClosed(1,8)
            .collect(()->new float[8],(a,design)->a[design-1]=catalogBulk(design),(a,b)->{} );
    private static final List<List<Piece>> CATALOG_PACKS=java.util.stream.IntStream.rangeClosed(1,8)
            .mapToObj(design->createPack(CATALOG_BULK[design-1],design)).toList();
    private static final List<List<Piece>> FITTED_CATALOG_PACKS=java.util.stream.IntStream.range(0,8)
            .mapToObj(index->CATALOG_PACKS.get(index).stream().map(p->new Piece(
                    ThirdPersonContours.backpack(p.geometry(),CATALOG_BULK[index],p.harness()),
                    p.color(),p.harness())).toList()).toList();
    private static final List<List<Piece>> CATALOG_NORTHERN_PACKS=java.util.stream.IntStream.rangeClosed(1,8)
            .mapToObj(design->NorthernWandererPack.create(CATALOG_BULK[design-1],true,design)).toList();
    private static final List<List<Piece>> FITTED_CATALOG_NORTHERN_PACKS=java.util.stream.IntStream.range(0,8)
            .mapToObj(index->NorthernWandererPack.fitted(CATALOG_NORTHERN_PACKS.get(index),CATALOG_BULK[index])).toList();

    /** A separate lazy fit keeps catalog/first-person caches byte-for-byte on their old source. */
    private static final class ExpeditionFit {
        static final float BULK=expeditionBulk();
        static final List<Piece> PACK=createPack(BULK,1,true).stream().map(p->new Piece(
                ThirdPersonContours.backpack(p.geometry(),BULK,p.harness()),p.color(),p.harness())).toList();
        static final List<Piece> NORTHERN=NorthernWandererPack.fitted(
                NorthernWandererPack.create(BULK,true,1,true),BULK);
    }

    static List<Piece> catalogParts(int design,boolean northern,boolean thirdPerson) {
        if(design==1&&thirdPerson)return northern?ExpeditionFit.NORTHERN:ExpeditionFit.PACK;
        return (northern?(thirdPerson?FITTED_CATALOG_NORTHERN_PACKS:CATALOG_NORTHERN_PACKS)
                :(thirdPerson?FITTED_CATALOG_PACKS:CATALOG_PACKS)).get(design-1);
    }

    static float catalogOffset(int design,boolean thirdPerson) {
        return design==1&&thirdPerson?ExpeditionFit.BULK:CATALOG_BULK[design-1];
    }

    private static float expeditionBulk() {
        // Fit the rigid contact plane in the same TP space as the worn parka.
        // Its open hood and lower pocket still count wherever they overlap the bags' height.
        float[] rear={0};
        for(var piece:CinematicExpeditionParka.pieces()) {
            if(piece.bone()!=ClothingMeshes.Bone.BODY)continue;
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    var vertices=face.vertices();
                    for(int i=0;i<vertices.length;i++) {
                        var a=vertices[i];var b=vertices[(i+1)%vertices.length];
                        var p=ThirdPersonContours.point(ClothingMeshes.Bone.BODY,a.x(),a.y(),a.z());
                        var q=ThirdPersonContours.point(ClothingMeshes.Bone.BODY,b.x(),b.y(),b.z());
                        if(p.y>=-1.85f&&p.y<=9.75f)rear[0]=Math.max(rear[0],p.z);
                        // Include an edge clipped by either end of the bags' contact height.
                        for(float edge:new float[]{-1.85f,9.75f})if((p.y<edge&&q.y>edge)||(q.y<edge&&p.y>edge))
                            rear[0]=Math.max(rear[0],p.z+(q.z-p.z)*(edge-p.y)/(q.y-p.y));
                    }
                }
            });
        }
        // Undo only the rigid anchor's depth change; applying raw bulk and then that
        // anchor again used to suspend both bags away from the already fitted cloth.
        float low=0,high=Math.max(4,rear[0]);
        for(int step=0;step<16;step++) {
            float bulk=(low+high)*.5f;
            float anchor=ThirdPersonContours.point(ClothingMeshes.Bone.BODY,0,4,2.3f+bulk).z-(2.3f+bulk);
            if(2.15f+bulk+anchor<rear[0]+.18f)low=bulk;else high=bulk;
        }
        return high;
    }

    private static float catalogBulk(int design) {
        float rear=0;
        for(float y=.75f;y<=8.5f;y+=.5f)for(float x:new float[]{-2.75f,0,2.75f})
            rear=Math.max(rear,cinematicShell(design,x,y,true));
        return rear+.18f-2.15f;
    }

    /** Actual garment envelope includes sewn pockets, quilted loft and fastenings.
     * Sampled only while constructing the cached harness, never per render frame. */
    static float cinematicShell(int design,float x,float y,boolean back) {
        return garmentShell(CinematicJackets.pieces(design),
                back?CinematicJackets.shellBack(design,x,y):CinematicJackets.shellFront(design,x,y),x,y,back);
    }

    /** Full parka envelope, including the hood and fur, for rigid bag clearance. */
    static float expeditionShell(float x,float y,boolean back) {
        return garmentShell(CinematicExpeditionParka.pieces(),
                back?CinematicExpeditionParka.shellBack(x,y):CinematicExpeditionParka.shellFront(x,y),x,y,back);
    }

    /** Straps are worn on the cloth beneath the open hood and its fur facing. */
    static float expeditionHarnessShell(float x,float y,boolean back) {
        return garmentShell(CinematicExpeditionParka.pieces(),
                back?CinematicExpeditionParka.shellBack(x,y):CinematicExpeditionParka.shellFront(x,y),x,y,back,true);
    }

    private static float garmentShell(List<ClothingMeshes.Piece> pieces,float base,float x,float y,boolean back) {
        return garmentShell(pieces,base,x,y,back,false);
    }

    private static float garmentShell(List<ClothingMeshes.Piece> pieces,float base,float x,float y,boolean back,
                                       boolean underHood) {
        float[] surface={base};
        for(var piece:pieces) {
            if(piece.bone()!=ClothingMeshes.Bone.BODY)continue;
            if(underHood&&(piece.role()==ClothingMeshes.SurfaceRole.HOOD
                    ||piece.role()==ClothingMeshes.SurfaceRole.COLLAR))continue;
            piece.geometry().visit(new PoseStack(),(pose,path,index,cube)->{
                for(var face:cube.polygons) {
                    var v=face.vertices();
                    envelope(surface,x,y,back,v[0],v[1],v[2]);
                    envelope(surface,x,y,back,v[0],v[2],v[3]);
                }
            });
        }
        return surface[0];
    }

    private static void envelope(float[] result,float x,float y,boolean back,
                                 ModelPart.Vertex a,ModelPart.Vertex b,ModelPart.Vertex c) {
        float ax=a.x()-c.x(),ay=a.y()-c.y(),bx=b.x()-c.x(),by=b.y()-c.y();
        float det=ax*by-bx*ay;
        if(Math.abs(det)<1e-8f)return;
        float u=((x-c.x())*by-bx*(y-c.y()))/det,v=(ax*(y-c.y())-(x-c.x())*ay)/det;
        if(u<-.0001f||v<-.0001f||u+v>1.0001f)return;
        float z=u*a.z()+v*b.z()+(1-u-v)*c.z();
        result[0]=back?Math.max(result[0],z):Math.min(result[0],z);
    }

    public static void init() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                helper.register(new ClothingLayer(avatar));
                helper.register(new BackpackLayer(avatar));
            }
        });
    }

    private BackpackLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    private static List<Piece> createPack(float coat) { return createPack(coat,0); }

    private static List<Piece> createPack(float coat,int design) { return createPack(coat,design,false); }

    private static List<Piece> createPack(float coat,int design,boolean expedition) {
        List<Piece> pieces=new ArrayList<>();
        // A small daypack: the fullest section sits below the shoulder line,
        // then the body contracts into a rounded bottom above the coat hem.
        pieces.add(new Piece(mesh().softLoft(0,4.0f,24,
                section(.35f,2.9f,1.9f,0,-.15f,2.3f),section(.75f,4.35f,2.65f,0,-.08f,2.5f),
                section(1.6f,5.45f,3.28f,0,0,2.7f),section(3.5f,5.95f,3.72f,0,.06f,2.8f),
                section(5.7f,5.85f,3.7f,0,.1f,2.8f),section(7.7f,5.38f,3.32f,0,.06f,2.6f),
                section(9.0f,4.5f,2.65f,0,-.03f,2.4f),section(9.65f,2.7f,1.65f,0,-.1f,2.2f)).bake(),CANVAS));
        // A cloth flap and half-embedded pouch follow the pack's fullness;
        // neither produces the old lid or a separate rectangular suitcase.
        pieces.add(new Piece(mesh().pillow(0,2.0f,5.51f,4.85f,3.0f,.68f).bake(),FLAP));
        pieces.add(new Piece(mesh().pillow(0,6.7f,5.66f,4.25f,3.5f,.95f).bake(),POCKET));
        for(float side:new float[]{-1,1}) {
            pieces.add(new Piece(mesh().softLoft(side*2.82f,4.07f,16,
                    section(3.6f,.44f,1.05f),section(4.1f,1.12f,1.85f),
                    section(5.8f,1.28f,2.0f),section(7.1f,1.02f,1.7f),section(7.6f,.5f,.94f)).bake(),POCKET));
            // Narrow compression webbing follows the pouch instead of a wide slab.
            pieces.add(new Piece(mesh().softLoft(side*1.55f,5.87f,12,
                    section(1.7f,.23f,.10f,0,-.16f,3),section(2.8f,.23f,.12f,0,.10f,3),
                    section(4.5f,.23f,.10f,0,-.02f,3),section(5.0f,.23f,.10f,0,-.1f,3)).bake(),WEBBING));
            pieces.add(new Piece(mesh().pillow(side*1.55f,3.8f,6.05f,.5f,.6f,.16f).bake(),HARDWARE));
            pieces.add(new Piece(shoulderStrap(side,coat,.72f,.25f,design,expedition),PADDING,true));
            pieces.add(new Piece(shoulderStrap(side,coat,.24f,.29f,design,expedition),WEBBING,true));
        }
        // An open, arched carrying loop stays below the hood and leaves the neck visible.
        pieces.add(new Piece(strapPath(new float[][]{{-.75f,.9f,3.85f},{-.6f,-.05f,3.85f},
                {0,-.25f,3.85f},{.6f,-.05f,3.85f},{.75f,.9f,3.85f}},.22f,.18f),WEBBING));
        return List.copyOf(pieces);
    }

    static List<Piece> itemParts() { return createPack(0); }

    static Identifier texture(int color) { return color==PADDING||color==HARDWARE?RUBBER_TEXTURE:CANVAS_TEXTURE; }

    private static ModelPart shoulderStrap(float side,float coat,float width,float depth,int design,boolean expedition) {
        // Compute both sides against the actual shell, then undo the pack's render offset.
        // This also fits an independently worn sweater without suspending straps in front of it.
        float x0=side*2.5f,x1=side*2.55f,x2=side*2.7f;
        return strapPath(new float[][]{
                {x0,8.1f,harnessFront(coat,design,expedition,x0,8.1f)-.13f-coat},
                {x1,4.0f,harnessFront(coat,design,expedition,x1,4.0f)-.14f-coat},
                {x2,.55f,harnessFront(coat,design,expedition,x2,.55f)-.14f-coat},
                {side*2.8f,-1.5f,harnessFront(coat,design,expedition,side*2.8f,-1.5f)-.13f-coat},
                {side*2.8f,-1.53f,harnessBack(coat,design,expedition,side*2.8f,-1.53f)+.13f-coat},
                {side*2.75f,-.1f,harnessBack(coat,design,expedition,side*2.75f,-.1f)+.15f-coat},
                {side*2.6f,2.3f,harnessBack(coat,design,expedition,side*2.6f,2.3f)+.14f-coat},
                {side*2.6f,6.7f,harnessBack(coat,design,expedition,side*2.6f,6.7f)+.13f-coat}},width,depth);
    }

    private static float harnessFront(float coat,int design,boolean expedition,float x,float y) {
        if(expedition)return expeditionHarnessShell(x,y,false);
        if(design>0)return cinematicShell(design,x,y,false);
        return coat==1?NorthernWandererClothing.shellFront(x,y):ClothingMeshes.shellFront(coat,x,y);
    }

    private static float harnessBack(float coat,int design,boolean expedition,float x,float y) {
        if(expedition)return expeditionHarnessShell(x,y,true);
        if(design>0)return cinematicShell(design,x,y,true);
        return coat==1?NorthernWandererClothing.shellBack(x,y):ClothingMeshes.shellBack(coat,x,y);
    }

    /** One sewn sweep: shared rings join the curve, with caps only at its two visible ends. */
    static ModelPart strapPath(float[][] path,float width,float depth) {
        float[][] curve=softPath(path);
        int sides=16;
        var rings=new ModelPart.Vertex[curve.length][sides];
        var centers=new org.joml.Vector3f[curve.length];
        org.joml.Vector3f across=null;
        float exponent=2/Math.max(2f,3-depth*.4f);
        for(int row=0;row<curve.length;row++) {
            float[] before=curve[Math.max(0,row-1)],after=curve[Math.min(curve.length-1,row+1)];
            var tangent=new org.joml.Vector3f(after[0]-before[0],after[1]-before[1],after[2]-before[2]).normalize();
            if(across==null)across=new org.joml.Vector3f(1,0,0);
            across.sub(new org.joml.Vector3f(tangent).mul(across.dot(tangent)));
            if(across.lengthSquared()<1e-8f)across.set(0,0,1).sub(new org.joml.Vector3f(tangent).mul(tangent.z()));
            across.normalize();
            var outward=new org.joml.Vector3f(across).cross(tangent).normalize();
            var center=new org.joml.Vector3f(curve[row][0],curve[row][1],curve[row][2]);
            if(row==0)center.fma(-.045f,tangent);
            else if(row==curve.length-1)center.fma(.045f,tangent);
            centers[row]=center;
            for(int side=0;side<sides;side++) {
                double angle=-Math.PI*.75+Math.PI*2*side/sides,co=Math.cos(angle),si=Math.sin(angle);
                float a=width*.5f*(float)Math.copySign(Math.pow(Math.abs(co),exponent),co);
                float b=depth*.5f*(float)Math.copySign(Math.pow(Math.abs(si),exponent),si);
                var point=new org.joml.Vector3f(center).fma(a,across).fma(b,outward);
                rings[row][side]=new ModelPart.Vertex(point.x(),point.y(),point.z(),side/(float)sides,row/(float)(curve.length-1));
            }
        }
        FacetedMesh result=mesh();
        for(int row=0;row<curve.length-1;row++)for(int side=0;side<sides;side++) {
            int next=(side+1)%sides;
            // The shared-ring sweep closes the bend itself, rather than hiding it behind end discs.
            result.face(rings[row][next],rings[row][side],rings[row+1][side],rings[row+1][side]);
            result.face(rings[row][next],rings[row+1][side],rings[row+1][next],rings[row+1][next]);
        }
        for(int end:new int[]{0,curve.length-1})for(int side=0;side<sides;side++) {
            int next=(side+1)%sides;
            var center=centers[end];
            var middle=new ModelPart.Vertex(center.x(),center.y(),center.z(),.5f,end==0?0:1);
            if(end==0)result.face(rings[end][side],rings[end][next],middle,middle);
            else result.face(rings[end][next],rings[end][side],middle,middle);
        }
        return result.bake();
    }

    private static float[][] softPath(float[][] path) {
        List<float[]> samples=new ArrayList<>();
        for(int i=0;i<path.length-1;i++) for(int step=0;step<2;step++) {
            float t=step*.5f,t2=t*t,t3=t2*t;float[] point=new float[3];
            for(int axis=0;axis<3;axis++) {
                float a=path[Math.max(0,i-1)][axis],b=path[i][axis],c=path[i+1][axis],d=path[Math.min(path.length-1,i+2)][axis];
                point[axis]=.5f*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3);
            }
            samples.add(point);
        }
        samples.add(path[path.length-1]);return samples.toArray(float[][]::new);
    }

    @Override
    public void submit(PoseStack poses, SubmitNodeCollector collector, int light, AvatarRenderState state, float yaw, float pitch) {
        if (state.isInvisible || !Boolean.TRUE.equals(((FabricRenderState) state).getData(EQUIPPED))) return;
        poses.pushPose();
        getParentModel().body.translateAndRotate(poses);
        if(ThirdPersonBodyModel.isThirdPerson(getParentModel())) ThirdPersonBodyModel.fit(ClothingMeshes.Bone.BODY,poses);
        Integer outfit = ((FabricRenderState) state).getData(ClothingLayer.OUTFIT);
        int style = outfit == null ? 0 : ClothingLayer.variant(outfit, com.dark.winter.equipment.GearSlot.JACKET);
        int fit=style==0?(outfit!=null&&ClothingLayer.variant(outfit,com.dark.winter.equipment.GearSlot.SWEATER)>0?1:0)
                :style>=7?4:style==2||style==3||style==4?3:2;
        boolean northern=outfit!=null&&ClothingLayer.variant(outfit,com.dark.winter.equipment.GearSlot.BACKPACK)==2;
        int design=com.dark.winter.equipment.CinematicWardrobe.design(com.dark.winter.equipment.GearSlot.JACKET,style);
        if(design>0) {
            boolean fitted=ThirdPersonBodyModel.isThirdPerson(getParentModel());
            var visible=catalogParts(design,northern,fitted);
            if(!northern)poses.translate(0,0,catalogOffset(design,fitted)/16);
            for(Piece piece:visible)collector.submitModelPart(piece.geometry(),poses,
                    RenderTypes.entityCutout(northern?NorthernWandererPack.texture(piece.color()):texture(piece.color())),
                    light,OverlayTexture.NO_OVERLAY,null,piece.color(),state.outlineColor);
        } else if(northern) {
            var visible=ThirdPersonBodyModel.isThirdPerson(getParentModel())?FITTED_NORTHERN_PACKS:NORTHERN_PACKS;
            for(Piece piece:visible.get(style>=7?4:fit)) collector.submitModelPart(piece.geometry(),poses,
                    RenderTypes.entityCutout(NorthernWandererPack.texture(piece.color())),light,OverlayTexture.NO_OVERLAY,
                    null,piece.color(),state.outlineColor);
        } else {
            poses.translate(0,0,SHELL_BULK[fit]/16);
            var visiblePacks=ThirdPersonBodyModel.isThirdPerson(getParentModel())?fittedPacks:packs;
            for(Piece piece:visiblePacks.get(fit)) collector.submitModelPart(piece.geometry(), poses,
                    RenderTypes.entityCutout(texture(piece.color())), light, OverlayTexture.NO_OVERLAY,
                    null, piece.color(), state.outlineColor);
        }
        poses.popPose();
    }
}
