// Finite, reload-scoped meshes for the two test-tree IDs.
package com.dark.winter.client;

import com.dark.winter.worldgen.TestSpruceBlocks;
import com.dark.winter.worldgen.TestSpruceBoughBlock;
import com.dark.winter.worldgen.TestSpruceLogBlock;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Finite, reload-scoped meshes for the two test-tree IDs. No vanilla model or render pass is changed. */
public final class TestSpruceModels {
    public static final int WOOD_TAG=0x445754, NEEDLE_TAG=0x445755, SNOW_TAG=0x445756, ROOT_SNOW_TAG=0x445757;
    private static final int[] DX={0,1,1,1,0,-1,-1,-1}, DZ={-1,-1,0,1,1,1,0,-1};
    private static boolean registered;
    private TestSpruceModels() {}

    /** Exposes the actual baked chunk mesh to the client verification fixtures. */
    public interface MeshBackedModel { Mesh mesh(); int quadCount(); }

    public static synchronized void init() {
        if(registered)return;
        registered=true;
        ModelLoadingPlugin.register(plugin -> {
            // A new cache belongs to each resource reload, so baked atlas materials never outlive it.
            Map<ModelBaker,Models> caches=new ConcurrentHashMap<>();
            plugin.modifyBlockModelAfterBake().register((original,context) -> {
                BlockState state=context.state();
                if(!state.is(TestSpruceBlocks.LOG)&&!state.is(TestSpruceBlocks.BOUGH))return original;
                Models models=caches.computeIfAbsent(context.baker(),Models::new);
                return new TreeModel(original,models,state);
            });
            plugin.modifyItemModelAfterBake().register((original,context) -> {
                String path=context.itemId().getPath();
                if(!context.itemId().getNamespace().equals("darkwinter")
                        ||(!path.equals("test_spruce_log")&&!path.equals("test_spruce_bough")))return original;
                Models models=caches.computeIfAbsent(context.bakingContext().blockModelBaker(),Models::new);
                BlockState state=path.endsWith("log")?TestSpruceBlocks.LOG.defaultBlockState():TestSpruceBlocks.BOUGH.defaultBlockState();
                Part part=models.part(state,0);
                ModelRenderProperties properties=new ModelRenderProperties(true,part.particleMaterial(),ItemTransforms.NO_TRANSFORMS);
                Matrix4f transform=new Matrix4f(context.transformation());
                if(context.sourceModel() instanceof CuboidItemModelWrapper.Unbaked source) {
                    var baker=context.bakingContext().blockModelBaker();
                    var resolved=baker.getModel(source.model());
                    var sourceProperties=ModelRenderProperties.fromResolvedModel(baker,resolved,resolved.getTopTextureSlots());
                    properties=new ModelRenderProperties(sourceProperties.usesBlockLight(),part.particleMaterial(),sourceProperties.transforms());
                    transform.set(Transformation.compose(context.transformation(),source.transformation()));
                }
                return new TreeItemModel(part,properties,transform,state.is(TestSpruceBlocks.BOUGH));
            });
        });
    }

    private static final class Models {
        final Material.Baked bark,needles,snow,groundSnow;
        final Map<Integer,Part> parts=new ConcurrentHashMap<>();
        Models(ModelBaker baker) {
            bark=material(baker,"test_spruce_bark");
            needles=material(baker,"test_spruce_needles");
            snow=material(baker,"test_spruce_snow");
            // This small join belongs visually to the existing surface snow. The
            // branch-snow artwork would leave a differently coloured square here.
            groundSnow=baker.materials().get(new Material(Identifier.fromNamespaceAndPath("minecraft","block/snow")),
                    () -> "DarkWinter test spruce ground-snow join");
        }
        Part part(BlockState state,int aboveRadius) {
            return part(state,aboveRadius,false,0,0);
        }
        Part part(BlockState state,int aboveRadius,boolean connectedBelow,int snowHeight,int snowSides) {
            boolean log=state.is(TestSpruceBlocks.LOG);
            // Only finite neighbour classes enter the reload-scoped cache, never world
            // coordinates: radius, connection, and eight possible ground-snow heights.
            int key=log?(state.getValue(RotatedPillarBlock.AXIS).ordinal()*32+state.getValue(TestSpruceLogBlock.RADIUS)*6+aboveRadius)
                        |(connectedBelow?1<<8:0)|(snowHeight<<9)|(snowSides<<13)
                    :128+state.getValue(TestSpruceBoughBlock.DIRECTION)*16+state.getValue(TestSpruceBoughBlock.DROP)*8
                        +state.getValue(TestSpruceBoughBlock.VARIANT)*2+(state.getValue(TestSpruceBoughBlock.TIP)?1:0);
            return parts.computeIfAbsent(key,ignored -> {
                Builder b=new Builder(this);
                if(log)trunk(b,state.getValue(RotatedPillarBlock.AXIS),state.getValue(TestSpruceLogBlock.RADIUS),
                        aboveRadius,connectedBelow,snowHeight,snowSides);
                else bough(b,state.getValue(TestSpruceBoughBlock.DIRECTION),state.getValue(TestSpruceBoughBlock.DROP),
                        state.getValue(TestSpruceBoughBlock.VARIANT),state.getValue(TestSpruceBoughBlock.TIP));
                return b.finish(log?bark:needles);
            });
        }
    }

    private static Material.Baked material(ModelBaker baker,String name) {
        return baker.materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter","block/"+name)),
                () -> "DarkWinter test spruce "+name);
    }

    private static void trunk(Builder b,Direction.Axis axis,int radius,int aboveRadius,
                              boolean connectedBelow,int snowHeight,int snowSides) {
        float r=trunkRadius(radius);
        float upper=aboveRadius>0?trunkRadius(aboveRadius):radius==1?.025f:r*.84f;
        // Eight unequal broad facets read as bark and bevels, not a perfectly circular pipe.
        float[] cx={-1,-.52f,.52f,1,1,.52f,-.52f,-1};
        float[] cz={-.52f,-1,-1,-.52f,.52f,1,1,.52f};
        V[][] rings=new V[2][8];
        for(int ring=0;ring<2;ring++)for(int i=0;i<8;i++) {
            float width=ring==0?r:upper;
            rings[ring][i]=axis(new V(.5f+cx[i]*width,ring,.5f+cz[i]*width),axis);
        }
        // A section is a continuous taper, not a repeated inward waist followed by
        // an outward bulge. The former two half-sections also repeated the whole
        // bark texture twice per metre and alternated their shading at every seam.
        for(int i=0;i<8;i++) {
            int j=(i+1)&7;
            V normal=axisNormal(new V(cx[i]+cx[j],0,cz[i]+cz[j]).unit(),axis);
            b.face(WOOD_TAG,rings[0][i],rings[1][i],rings[1][j],rings[0][j],false,.01f,.99f,
                    new V[]{normal,normal,normal,normal});
        }
        V bottom=axis(new V(.5f,0,.5f),axis),top=axis(new V(.5f,1,.5f),axis);
        for(int i=0;i<8;i++) {
            int j=(i+1)&7;
            if(!connectedBelow)b.face(WOOD_TAG,bottom,rings[0][i],rings[0][j],rings[0][j]);
            if(aboveRadius==0)b.face(WOOD_TAG,top,rings[1][j],rings[1][i],rings[1][i]);
        }
        if(axis==Direction.Axis.Y&&snowHeight>0)groundSnow(b,r,upper,snowHeight,snowSides);
        if(axis==Direction.Axis.Y&&radius==1&&aboveRadius==0) {
            // The living leader ends in a narrow needle crown, not an exposed sharpened pole.
            for(int direction=0;direction<8;direction+=2) {
                V side=new V(-DZ[direction],0,DX[direction]);
                b.needles(new V(.5f,1.025f,.5f),
                        new V(.5f+DX[direction]*.42f,.23f+(direction%3)*.025f,.5f+DZ[direction]*.42f),
                        .43f,side);
            }
        }
    }

    private static float trunkRadius(int radius) { return radius==1?.065f:radius*.06f; }

    private static V axisNormal(V n,Direction.Axis axis) {
        return switch(axis){case X->new V(n.y,-n.x,n.z);case Z->new V(n.x,-n.z,n.y);default->n;};
    }

    private static void groundSnow(Builder b,float radius,float upper,int layers,int sides) {
        // Rendering only: the narrow log occupies one cell in which the ordinary
        // snow block cannot coexist. Join that empty visual rim to adjacent snow
        // instead of exposing a square of soil around an otherwise narrow trunk.
        float y=layers/8f,inner=(radius+(upper-radius)*y)*.985f;
        float[] cx={-1,-.52f,.52f,1,1,.52f,-.52f,-1};
        float[] cz={-.52f,-1,-1,-.52f,.52f,1,1,.52f};
        V[] inside=new V[8],outside=new V[8];
        for(int i=0;i<8;i++) {
            inside[i]=new V(.5f+cx[i]*inner,y+.018f,.5f+cz[i]*inner);
            outside[i]=new V(.5f+cx[i]*.5f,y,.5f+cz[i]*.5f);
        }
        V up=new V(0,1,0);V[] normals={up,up,up,up};
        for(int i=0;i<8;i++) {
            int j=(i+1)&7;
            int side=i==1?0:i==3?1:i==5?2:i==7?3:-1;
            int corner=i/2;
            boolean visible=side>=0?(sides&(1<<side))!=0
                    :(sides&((1<<corner)|(1<<((corner+3)&3))))!=0;
            if(!visible)continue;
            b.face(ROOT_SNOW_TAG,outside[i],inside[i],inside[j],outside[j],false,.01f,.99f,normals,true);
            if(side<0) {
                float cornerX=i==0||i==6?0:1,cornerZ=i<4?0:1;
                b.face(ROOT_SNOW_TAG,new V(cornerX,y,cornerZ),outside[i],outside[j],outside[j],
                        false,.01f,.99f,normals,true);
            }
        }
    }

    private static V axis(V p,Direction.Axis axis) {
        return switch(axis){case X->new V(p.y,1-p.x,p.z);case Z->new V(p.x,1-p.z,p.y);default->p;};
    }

    private static void bough(Builder b,int direction,int drop,int variant,boolean tip) {
        V root=new V(.5f,.78f,.5f), step=new V(DX[direction],-drop,DZ[direction]);
        V side=new V(-DZ[direction],0,DX[direction]).unit();
        float foliageScale=.78f+variant*.085f;
        float reach=tip?.62f+variant*.085f:1;
        V end=root.add(step.mul(reach)).add(new V(0,tip?-(.26f+variant*.035f):0,0));
        float bend=.045f+variant*.012f;
        V mid=root.add(step.mul(reach*.52f)).add(side.mul((variant-1.5f)*.018f)).add(new V(0,-bend,0));
        float thickness=(tip?.037f:.052f)*foliageScale;
        b.wood(root,mid,thickness,thickness*.76f);
        b.wood(mid,end,thickness*.76f,tip?.009f:thickness*.67f);
        // The first bough occupies the cell next to the trunk. This overlap reaches
        // even the thinnest trunk, including diagonal directions; it is not a floating root.
        b.wood(root.add(new V(-DX[direction]*.98f,0,-DZ[direction]*.98f)),root,thickness*.76f,thickness);

        for(int fork=0;fork<6;fork++) {
            int sign=(fork&1)==0?-1:1;
            float at=(fork<2?-.12f:fork<4?.26f:.66f)+(variant-1.5f)*.024f;
            V anchor=lerp(root,end,at).add(new V(0,-bend*.4f,0));
            float spread=((fork<2?.55f:fork<4?.67f:.47f)*(tip?.85f:1)+(variant%2)*.035f)*foliageScale;
            V forkEnd=anchor.add(side.mul(sign*spread)).add(new V(DX[direction]*.17f,
                    -(.20f+(fork%3)*.045f)*foliageScale,DZ[direction]*.17f));
            // The primary branch remains geometric; the small fork stems live in the needle card.
            V sprayTip=forkEnd.add(new V(DX[direction]*.11f,-.27f-(variant%3)*.025f,DZ[direction]*.11f));
            // Each lateral spray needs a width perpendicular to its OWN stem. Using
            // the main branch's side axis flattened these forks into near-collinear strips.
            V spraySide=new V(-(sprayTip.z-anchor.z),0,sprayTip.x-anchor.x).unit();
            b.needles(anchor,sprayTip,(.53f+(fork%2)*.055f)*foliageScale,spraySide,1);
            if((variant&1)!=0&&fork==3) {
                V forkSide=new V(-(forkEnd.z-anchor.z),0,forkEnd.x-anchor.x).unit();
                b.snowMantle(lerp(anchor,forkEnd,.10f),lerp(anchor,forkEnd,.93f),forkSide,
                        (tip?.23f:.29f)*foliageScale,.16f+(variant&1)*.025f,variant+fork,true);
            }
        }
        // This full-length fan covers the inner attachment as well as the outer branch.
        // Sparse endpoint-only cards left a metre of bare horizontal wood around the trunk.
        V inner=root.add(new V(-DX[direction]*.89f,.015f,-DZ[direction]*.89f));
        b.bentNeedles(inner,lerp(root,end,.18f).add(new V(0,-.045f,0)),
                end.add(new V(0,-.48f*foliageScale,0)),(tip?.90f:1.02f)*foliageScale,side);
        // A broad, low mantle follows the supporting bough. Blunt rolled edges and
        // unequal shoulders replace the long triangular planes of the former ridge.
        float mantleStart=.48f+(variant%3)*.07f;
        // The steep outer descent sheds its continuous snow blanket: retain the
        // load over the supporting inner lap and let green tips hang below it.
        // Covering the full DROP=1 segment made metre-long white downward blades.
        float mantleEnd=drop==1?.36f+(variant&1)*.07f:.80f+(variant&1)*.10f;
        b.snowMantle(root.add(new V(-DX[direction]*mantleStart,0,-DZ[direction]*mantleStart)),
                lerp(root,end,mantleEnd),side,(tip?.33f:.38f)*foliageScale,
                .19f+variant*.018f,variant,false);
    }

    private record V(float x,float y,float z) {
        V add(V p){return new V(x+p.x,y+p.y,z+p.z);}
        V mul(float s){return new V(x*s,y*s,z*s);}
        V sub(V p){return new V(x-p.x,y-p.y,z-p.z);}
        V cross(V p){return new V(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x);}
        V unit(){float l=(float)Math.sqrt(x*x+y*y+z*z);return l<1e-7f?new V(0,1,0):mul(1/l);}
    }
    private static V lerp(V a,V b,float t){return a.add(b.sub(a).mul(t));}

    private static final class Builder {
        final Models materials;
        final net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh mutable=Renderer.get().mutableMesh();
        final QuadEmitter emitter=mutable.emitter();
        final List<BakedQuad> quads=new ArrayList<>();
        Builder(Models materials){this.materials=materials;}
        void face(int tag,V a,V b,V c,V d){face(tag,a,b,c,d,false);}
        void face(int tag,V a,V b,V c,V d,boolean reverse) {
            face(tag,a,b,c,d,reverse,.01f,.99f);
        }
        void face(int tag,V a,V b,V c,V d,boolean reverse,float u0,float u1) {
            face(tag,a,b,c,d,reverse,u0,u1,null);
        }
        void face(int tag,V a,V b,V c,V d,boolean reverse,float u0,float u1,V[] normals) {
            face(tag,a,b,c,d,reverse,u0,u1,normals,false);
        }
        void face(int tag,V a,V b,V c,V d,boolean reverse,float u0,float u1,V[] normals,boolean groundSnow) {
            V[] p=reverse?new V[]{d,c,b,a}:new V[]{a,b,c,d};
            V n=p[1].sub(p[0]).cross(p[2].sub(p[0])).unit();
            Material.Baked material=groundSnow?materials.groundSnow
                    :tag==WOOD_TAG?materials.bark:tag==SNOW_TAG?materials.snow:materials.needles;
            for(int i=0;i<4;i++) {
                V v=p[i];
                if(!Float.isFinite(v.x+v.y+v.z))throw new IllegalArgumentException("Non-finite test spruce mesh");
                V lighting=normals==null?n:normals[reverse?3-i:i];
                emitter.pos(i,v.x,v.y,v.z).normal(i,lighting.x,lighting.y,lighting.z);
                // For the needle card, the first two vertices are its root edge (left of texture).
                int source=reverse?3-i:i;
                if(groundSnow) {
                    emitter.uv(i,.001f+v.x*.998f,.001f+v.z*.998f);
                } else if(tag==SNOW_TAG) {
                    // One continuous projection across the whole low snow patch. Switching
                    // projection at each face's dominant normal exposed polygon-shaped seams
                    // even when adjacent faces shared both positions and smooth normals.
                    emitter.uv(i,snowUv(v.x),snowUv(v.z));
                } else emitter.uv(i,source<2?u0:u1,source==0||source==3?.02f:.98f);
            }
            emitter.materialBake(material,MutableQuadView.BAKE_NORMALIZED);
            // Ground-snow joining geometry lies inside a log's cell: cube AO
            // incorrectly darkens its entire square instead of the thin trunk.
            // It retains the actual world lightmap and upward directional shading.
            emitter.tag(tag).cullFace(null).tintIndex(-1).ambientOcclusion(tag==NEEDLE_TAG||groundSnow?TriState.FALSE:TriState.TRUE)
                    .chunkLayer(tag==NEEDLE_TAG?ChunkSectionLayer.CUTOUT:ChunkSectionLayer.SOLID);
            quads.add(emitter.toBakedQuad(material.sprite()));emitter.emit();
        }
        private static float snowUv(float coordinate) {
            return Math.max(.04f,Math.min(.94f,.04f+(coordinate+2f)*.18f));
        }
        void wood(V from,V to,float startRadius,float endRadius) {
            V axis=to.sub(from).unit();
            V side=Math.abs(axis.y)>.92f?new V(1,0,0):axis.cross(new V(0,1,0)).unit();
            V up=side.cross(axis).unit();
            V[] dirs={side,up,side.mul(-1),up.mul(-1)};
            for(int i=0;i<4;i++) {
                int j=(i+1)&3;
                face(WOOD_TAG,from.add(dirs[i].mul(startRadius)),to.add(dirs[i].mul(endRadius)),
                        to.add(dirs[j].mul(endRadius)),from.add(dirs[j].mul(startRadius)));
            }
        }
        void needles(V from,V to,float width,V side) {
            needles(from,to,width,side,2);
        }
        void needles(V from,V to,float width,V side,int planes) {
            V axis=to.sub(from).unit(),cross=axis.cross(side).unit();
            for(int plane=0;plane<planes;plane++) {
                V spread=(plane==0?side:cross).mul(plane==0?width:width*.70f);
                V start=belowBranch(from,spread,.68f,plane),end=belowBranch(to,spread,.36f,plane);
                V a=start.sub(spread.mul(.68f)),c=end.add(spread.mul(.36f));
                V d=end.sub(spread.mul(.36f)),e=start.add(spread.mul(.68f));
                face(NEEDLE_TAG,a,e,c,d);face(NEEDLE_TAG,a,e,c,d,true);
            }
        }
        void bentNeedles(V from,V knee,V to,float width,V side) {
            V axis=to.sub(from).unit(),cross=axis.cross(side).unit();
            for(int plane=0;plane<2;plane++) {
                V spread=(plane==0?side:cross).mul(plane==0?width:width*.70f);
                V[] centers={from,knee,to};
                float[] widths={.68f,1f,.36f},u={.01f,.47f,.99f};
                for(int segment=0;segment<2;segment++) {
                    V start=belowBranch(centers[segment],spread,widths[segment],plane);
                    V end=belowBranch(centers[segment+1],spread,widths[segment+1],plane);
                    V a=start.sub(spread.mul(widths[segment]));
                    V e=start.add(spread.mul(widths[segment]));
                    V c=end.add(spread.mul(widths[segment+1]));
                    V d=end.sub(spread.mul(widths[segment+1]));
                    face(NEEDLE_TAG,a,e,c,d,false,u[segment],u[segment+1]);
                    face(NEEDLE_TAG,a,e,c,d,true,u[segment],u[segment+1]);
                }
            }
        }
        private static V belowBranch(V center,V spread,float width,int plane) {
            // An upright cross-card must hang below the branch. Centering it on the axis
            // made the card pierce the snow cap and cut black slots into its visible top.
            return plane==0?center:center.add(new V(0,-Math.abs(spread.y)*width-.035f,0));
        }
        void snowMantle(V from,V to,V side,float width,float height,int variant,boolean small) {
            // Concentric surfaces make a low snow cushion with a rounded perimeter.
            // The previous loft ended in planar polygon caps and looked sawn off.
            int around=small?8:12;
            float[] radii=small?new float[]{.62f,1}:new float[]{.38f,.78f,1};
            float[] domes=small?new float[]{.80f,.12f}:new float[]{.94f,.64f,.12f};
            List<V> points=new ArrayList<>();
            List<int[]> surfaces=new ArrayList<>();
            V forward=new V(to.x-from.x,0,to.z-from.z).unit();
            float length=(float)Math.sqrt((to.x-from.x)*(to.x-from.x)+(to.z-from.z)*(to.z-from.z));
            V middle=lerp(from,to,.5f);
            points.add(new V(middle.x,snowSupport(from,to,.5f,small)+height,middle.z));
            for(int ring=0;ring<radii.length;ring++)for(int i=0;i<around;i++) {
                double angle=2*Math.PI*i/around;
                float cosine=(float)Math.cos(angle),sine=(float)Math.sin(angle),r=radii[ring];
                float t=.5f+.5f*r*cosine;
                // Three broad unequal lobes, never per-vertex noise or spiky crystals.
                float outline=1+.075f*(float)Math.sin(3*angle+variant*.9)+.045f*(float)Math.cos(2*angle-variant);
                V surface=middle.add(forward.mul((t-.5f)*length)).add(side.mul(sine*r*width*outline));
                float dome=height*domes[ring]*(1+.07f*sine*((variant&1)==0?1:-1));
                points.add(new V(surface.x,snowSupport(from,to,t,small)+dome,surface.z));
            }
            for(int i=0;i<around;i++) {
                int j=(i+1)%around;
                surfaces.add(new int[]{0,1+j,1+i,1+i});
            }
            for(int ring=0;ring<radii.length-1;ring++)for(int i=0;i<around;i++) {
                int j=(i+1)%around,inner=1+ring*around,outer=inner+around;
                surfaces.add(new int[]{inner+i,inner+j,outer+j,outer+i});
            }
            int outer=1+(radii.length-1)*around,bottom=points.size();
            // The lower rim turns under and stays attached to the supporting lap.
            for(int i=0;i<around;i++) {
                V edge=points.get(outer+i);
                double angle=2*Math.PI*i/around;
                float t=.5f+.5f*(float)Math.cos(angle);
                points.add(new V(middle.x+(edge.x-middle.x)*.96f,
                        snowSupport(from,to,t,small)-.045f-height*.08f,
                        middle.z+(edge.z-middle.z)*.96f));
            }
            int bottomCenter=points.size();
            points.add(new V(middle.x,snowSupport(from,to,.5f,small)-.045f,middle.z));
            for(int i=0;i<around;i++) {
                int j=(i+1)%around;
                surfaces.add(new int[]{outer+i,outer+j,bottom+j,bottom+i});
                surfaces.add(new int[]{bottomCenter,bottom+i,bottom+j,bottom+j});
            }
            // Only snow receives continuous vertex normals. Bark stays faceted and
            // needles retain their cutout planes; no global lighting or shader change.
            V[] smooth=new V[points.size()];
            java.util.Arrays.fill(smooth,new V(0,0,0));
            for(int[] q:surfaces) {
                V normal=points.get(q[1]).sub(points.get(q[0])).cross(points.get(q[2]).sub(points.get(q[0])));
                for(int i=0;i<4;i++)if(i<3||q[i]!=q[i-1])smooth[q[i]]=smooth[q[i]].add(normal);
            }
            for(int i=0;i<smooth.length;i++)smooth[i]=smooth[i].unit();
            for(int[] q:surfaces)face(SNOW_TAG,points.get(q[0]),points.get(q[1]),points.get(q[2]),points.get(q[3]),
                    false,.01f,.99f,new V[]{smooth[q[0]],smooth[q[1]],smooth[q[2]],smooth[q[3]]});
        }
        private static float snowSupport(V from,V to,float t,boolean small) {
            float bend=small?t:Math.clamp((t-.30f)/.70f,0,1);
            float sag=small?bend:bend*bend*(3-2*bend);
            return from.y+(to.y-from.y)*sag+.035f;
        }
        Part finish(Material.Baked particle) {
            Mesh mesh=mutable.immutableCopy();
            if(quads.size()>128)throw new IllegalArgumentException("Test spruce mesh exceeded per-cell quad budget");
            return new Part(mesh,List.copyOf(quads),particle,new MeshQuadCollection(mesh).materialFlags());
        }
    }

    private record Part(Mesh mesh,List<BakedQuad> quads,Material.Baked particleMaterial,int materialFlags) implements BlockStateModelPart {
        @Override public List<BakedQuad> getQuads(@Nullable Direction side){return side==null?quads:List.of();}
        @Override public boolean useAmbientOcclusion(){return true;}
        @Override public void emitQuads(QuadEmitter emitter,Predicate<@Nullable Direction> cullTest){if(!cullTest.test(null))mesh.outputTo(emitter);}
    }

    private static final class TreeModel extends WrapperBlockStateModel implements MeshBackedModel {
        final Models models;final BlockState state;final Part base;
        TreeModel(BlockStateModel original,Models models,BlockState state){super(original);this.models=models;this.state=state;this.base=models.part(state,0);}
        private Part at(BlockAndTintGetter level,BlockPos pos) {
            if(!state.is(TestSpruceBlocks.LOG))return base;
            Direction axis=Direction.get(Direction.AxisDirection.POSITIVE,state.getValue(RotatedPillarBlock.AXIS));
            BlockState next=level.getBlockState(pos.relative(axis));
            int radius=next.is(TestSpruceBlocks.LOG)&&next.getValue(RotatedPillarBlock.AXIS)==state.getValue(RotatedPillarBlock.AXIS)
                    ?next.getValue(TestSpruceLogBlock.RADIUS):0;
            BlockState previous=level.getBlockState(pos.relative(axis.getOpposite()));
            boolean connectedBelow=previous.is(TestSpruceBlocks.LOG)
                    &&previous.getValue(RotatedPillarBlock.AXIS)==state.getValue(RotatedPillarBlock.AXIS);
            int snowHeight=0,snowSides=0;
            if(axis==Direction.UP&&!connectedBelow&&previous.isFaceSturdy(level,pos.below(),Direction.UP)) {
                int lowest=8;
                for(int side=0;side<4;side++) {
                    int direction=side*2;
                    BlockState neighbour=level.getBlockState(pos.offset(DX[direction],0,DZ[direction]));
                    int layers=neighbour.is(Blocks.SNOW)?neighbour.getValue(SnowLayerBlock.LAYERS)
                            :neighbour.is(Blocks.SNOW_BLOCK)?8:0;
                    if(layers>0){snowSides|=1<<side;lowest=Math.min(lowest,layers);}
                }
                if(snowSides!=0)snowHeight=lowest;
            }
            return models.part(state,radius,connectedBelow,snowHeight,snowSides);
        }
        @Override public Mesh mesh(){return base.mesh();}
        @Override public int quadCount(){return base.quads().size();}
        @Override public void collectParts(RandomSource random,List<BlockStateModelPart> output){output.add(base);}
        @Override public Material.Baked particleMaterial(){return base.particleMaterial();}
        @Override public int materialFlags(){return base.materialFlags();}
        @Override public boolean hasMaterialFlag(int flag){return (materialFlags()&flag)!=0;}
        @Override public Material.Baked particleMaterial(BlockAndTintGetter level,BlockPos pos,BlockState state){return particleMaterial();}
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random){return materialFlags();}
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag){return hasMaterialFlag(flag);}
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<@Nullable Direction> cullTest){at(level,pos).emitQuads(emitter,cullTest);}
        @Override public Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random){return at(level,pos).mesh();}
    }

    private static final class TreeItemModel implements ItemModel,MeshBackedModel {
        final Part part;final Matrix4f transform;final ModelRenderProperties properties;
        TreeItemModel(Part part,ModelRenderProperties properties,Matrix4f transform,boolean bough){
            this.part=part;this.properties=properties;this.transform=transform;
            if(bough)transform.translate(.5f,.5f,.5f).scale(.56f).translate(-.5f,-.5f,-.5f);
        }
        @Override public Mesh mesh(){return part.mesh();}
        @Override public int quadCount(){return part.quads().size();}
        @Override public void update(ItemStackRenderState output,ItemStack stack,ItemModelResolver resolver,ItemDisplayContext displayContext,
                                     @Nullable ClientLevel level,@Nullable ItemOwner owner,int seed){
            output.appendModelIdentityElement(this);
            var layer=output.newLayer();layer.setLocalTransform(transform);properties.applyToLayer(layer,displayContext);
            part.mesh().outputTo(layer.emitter());
            if((part.materialFlags()&BakedQuad.FLAG_ANIMATED)!=0)output.setAnimated();
        }
    }
}
