package com.dark.winter.client;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.RoadWires;
import com.dark.winter.block.UtilityWireBlock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Private road meshes only. Normal chunk geometry, bounded caches and no retained worlds. */
public final class RoadFurnitureModels {
    private static final Set<String> SURFACES=Set.of("asphalt_block","asphalt_white_line","asphalt_dashed_line","asphalt_crosswalk","asphalt_edge_line","gravel_surface");
    private static Material.Baked material(ModelBaker baker,String name) {
        return baker.materials().get(new Material(Identifier.fromNamespaceAndPath("darkwinter","block/outdoor/road_ref_"+name)),()->"DarkWinter road materials");
    }
    public static BlockStateModel wrap(BlockStateModel model,String id,BlockState state,ModelBaker baker) {
        if(!SURFACES.contains(id)&&!id.equals("utility_pole")&&!id.startsWith("guardrail_"))return model;
        return new RoadModel(model,id,state,material(baker,id.equals("gravel_surface")?"gravel_compact":SURFACES.contains(id)?"asphalt_clean":"infrastructure_materials"));
    }
    public static BlockStateModel wire(BlockStateModel fallback,BlockState state,ModelBaker baker) {
        return new WireModel(fallback,state,material(baker,"infrastructure_materials"));
    }
    private record Key(Mesh base,int tile,int connections) {}
    private static final class RoadModel extends WrapperBlockStateModel implements FurnitureMeshModels.MeshBackedModel {
        private final String id; private final BlockState state; private final FurnitureBlock block;
        private final FurnitureMeshModels.MeshBackedModel source; private final Material.Baked material;
        private final Map<Key,Mesh> cache=new ConcurrentHashMap<>();
        RoadModel(BlockStateModel model,String id,BlockState state,Material.Baked material) {
            super(model);this.id=id;this.state=state;this.material=material;this.block=(FurnitureBlock)state.getBlock();this.source=(FurnitureMeshModels.MeshBackedModel)model;
        }
        private BlockPos origin(BlockPos pos){return block.origin(pos,state);}
        private int tile(BlockPos pos) {
            if(SURFACES.contains(id)) {
                // One continuous four-metre field, including across different marking block IDs.
                int transform=hash(Math.floorDiv(pos.getX(),4),Math.floorDiv(pos.getZ(),4))&7;
                return Math.floorMod(pos.getX(),4)+4*Math.floorMod(pos.getZ(),4)+16*transform;
            }
            BlockPos o=origin(pos);return hash(o.getX(),o.getZ())&15;
        }
        private Mesh selected(BlockPos pos,int connections) {
            Mesh base=source.mesh(pos);int tile=tile(pos);
            return cache.computeIfAbsent(new Key(base,tile,connections),key->build(base,tile,connections));
        }
        private Mesh build(Mesh base,int tile,int connections) {
            var mutable=Renderer.get().mutableMesh();QuadEmitter out=mutable.emitter();TextureAtlasSprite sprite=material.sprite();
            base.forEach(q->{
                out.copyFrom(q);
                boolean owned=true;
                for(int v=0;v<4;v++) owned&=q.u(v)>=sprite.getU0()-1e-6f&&q.u(v)<=sprite.getU1()+1e-6f&&q.v(v)>=sprite.getV0()-1e-6f&&q.v(v)<=sprite.getV1()+1e-6f;
                if(owned)for(int v=0;v<4;v++) {
                    float u=(q.u(v)-sprite.getU0())/(sprite.getU1()-sprite.getU0());
                    float w=(q.v(v)-sprite.getV0())/(sprite.getV1()-sprite.getV0());
                    if(SURFACES.contains(id)) {
                        float a=((tile&3)+q.x(v))/4f,b=(((tile>>2)&3)+q.z(v))/4f;
                        if(Math.abs(q.faceNormal().y())<.9f){a=((tile&3)+u)/4f;b=(((tile>>2)&3)+w)/4f;}
                        int transform=tile>>4;if((transform&4)!=0)a=1-a;
                        for(int t=0;t<(transform&3);t++){float n=1-b;b=a;a=n;}
                        u=a;w=b;
                    } else {
                        // Select subfields inside each material quadrant; timber grain stays vertical.
                        int cx=u>=.5f?1:0,cy=w>=.5f?1:0;
                        if(!(cx==1&&cy==1)) {
                            float a=(u-cx*.5f)*2,b=(w-cy*.5f)*2;
                            u=cx*.5f+((tile&3)+a)/8f;w=cy*.5f+((tile>>2)+b)/8f;
                        }
                    }
                    out.uv(v,sprite.getU(Math.clamp(u,0,1)),sprite.getV(Math.clamp(w,0,1)));
                }
                out.emit();
            });
            if(id.startsWith("guardrail_"))caps(out,connections);
            if(id.equals("utility_pole")&&state.getValue(FurnitureBlock.PART)==2) {
                var axis=state.getValue(FurnitureBlock.FACING).getAxis();
                for(int sign:new int[]{-1,1}) {
                    int distance=(connections>>(sign<0?0:5))&31;
                    if(distance>=2)emitWires(out,material,axis,distance,0,sign<0?0:.5f,sign<0?.5f:1,sign<0);
                }
            }
            return mutable.immutableCopy();
        }
        private int connections(BlockAndTintGetter level,BlockPos pos) {
            if(id.startsWith("guardrail_"))return guardConnections(level,origin(pos),state);
            if(!id.equals("utility_pole")||state.getValue(FurnitureBlock.PART)!=2)return 0;
            var axis=state.getValue(FurnitureBlock.FACING).getAxis();var dir=axis==Direction.Axis.X?Direction.EAST:Direction.SOUTH;int result=0;
            for(int sign:new int[]{-1,1}) {
                var s=level.getBlockState(pos.relative(dir,sign));
                if(s.is(RoadWires.WIRE)&&s.getValue(UtilityWireBlock.AXIS)==axis) {
                    int d=s.getValue(UtilityWireBlock.DISTANCE),i=s.getValue(UtilityWireBlock.INDEX);
                    if(i==(sign<0?d-1:1))result|=d<<(sign<0?0:5);
                }
            }
            return result;
        }
        private void caps(QuadEmitter out,int connected) {
            int part=state.getValue(FurnitureBlock.PART);if(part>=block.definition().parts().size())return;
            BlockPos offset=block.partOffset(part,state.getValue(FurnitureBlock.FACING));
            double[][] endpoints=ends(id,state.getValue(FurnitureBlock.FACING));
            for(int e=0;e<2;e++)if((connected&(1<<e))==0) {
                if(id.equals("guardrail_section")&&part!=e)continue;
                double[] p=endpoints[e];float x=(float)p[0]-offset.getX(),z=(float)p[1]-offset.getZ();
                // Folded closure follows the beam end, without protruding beyond its owning cell.
                boolean runX=Math.abs(p[2])>.5;
                float[][] points=new float[4][3];float low=.508f,high=.87f,half=.055f;
                for(int i=0;i<4;i++)points[i]=new float[]{x+(runX?0:(i==0||i==3?-half:half)),i<2?low:high,z+(runX?(i==0||i==3?-half:half):0)};
                quad(out,material,points,0,0,.43f,.43f,0xFFE4E6E8);reverse(points);quad(out,material,points,0,0,.43f,.43f,0xFFE4E6E8);
            }
        }
        @Override public Mesh mesh(){return selected(BlockPos.ZERO,0);}
        @Override public Mesh mesh(BlockPos pos){return selected(pos,0);}
        @Override public String furnitureId(){return id;}
        @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<@Nullable Direction> cullTest) {
            if(!cullTest.test(null))selected(pos,connections(level,pos)).outputTo(emitter);
        }
        @Override public Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {return selected(pos,connections(level,pos));}
    }
    private static int hash(int x,int z){int h=x*0x1f123bb5^z*0x5f356495;h^=h>>>16;h*=0x45d9f3b;return h^(h>>>16);}
    // Endpoint position and tangent, rotated with exactly the furniture placement convention.
    public static double[][] ends(String id,Direction facing) {
        double[][] e=id.equals("guardrail_corner")?new double[][]{{0,.4,1,0},{.6,1,0,1}}:new double[][]{{0,.4,1,0},{2,.4,1,0}};
        int n=switch(facing){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
        for(double[] p:e)for(int i=0;i<n;i++){double x=1-p[1];p[1]=p[0];p[0]=x;x=-p[3];p[3]=p[2];p[2]=x;}
        return e;
    }
    public static int guardConnections(BlockAndTintGetter level,BlockPos origin,BlockState state) {
        String id=((FurnitureBlock)state.getBlock()).definition().id();double[][] e=ends(id,state.getValue(FurnitureBlock.FACING));int result=0;
        for(int i=0;i<2;i++) {
            double x=origin.getX()+e[i][0],z=origin.getZ()+e[i][1];Set<BlockPos> seen=new java.util.HashSet<>();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                BlockPos at=new BlockPos((int)Math.floor(x)+dx,origin.getY(),(int)Math.floor(z)+dz);var s=level.getBlockState(at);
                if(!(s.getBlock() instanceof FurnitureBlock f)||!f.definition().id().startsWith("guardrail_"))continue;
                var root=f.origin(at,s);if(root.equals(origin)||!seen.add(root))continue;
                for(double[] q:ends(f.definition().id(),s.getValue(FurnitureBlock.FACING)))
                    if(Math.abs(root.getX()+q[0]-x)<1e-5&&Math.abs(root.getZ()+q[1]-z)<1e-5&&Math.abs(q[2]*e[i][2]+q[3]*e[i][3])>.99)result|=1<<i;
            }
        }
        return result;
    }
    private static final class WireModel extends WrapperBlockStateModel implements FurnitureMeshModels.MeshBackedModel {
        private final Mesh mesh;private final Material.Baked material;private final int flags;
        WireModel(BlockStateModel fallback,BlockState state,Material.Baked material) {
            super(fallback);this.material=material;var m=Renderer.get().mutableMesh();int d=state.getValue(UtilityWireBlock.DISTANCE),i=state.getValue(UtilityWireBlock.INDEX);
            if(i<d)emitWires(m.emitter(),material,state.getValue(UtilityWireBlock.AXIS),d,i,0,1,false);mesh=m.immutableCopy();
            flags=new net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection(mesh).materialFlags();
        }
        @Override public Mesh mesh(){return mesh;}
        @Override public String furnitureId(){return "utility_wire";}
        @Override public Material.Baked particleMaterial(){return material;}
        @Override public void emitQuads(QuadEmitter out,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<@Nullable Direction> cullTest){if(!cullTest.test(null))mesh.outputTo(out);}
        @Override public Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random){return mesh;}
        @Override public int materialFlags(){return flags;}
        @Override public int materialFlags(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random){return materialFlags();}
        @Override public boolean hasMaterialFlag(int flag){return (materialFlags()&flag)!=0;}
        @Override public boolean hasMaterialFlag(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,int flag){return hasMaterialFlag(flag);}
    }
    private static void emitWires(QuadEmitter out,Material.Baked material,Direction.Axis axis,int distance,int index,float lo,float hi,boolean end) {
        float sag=Math.min(.42f,.025f*distance),r=.0075f;
        for(float cross:new float[]{.15f,.5f,.85f})for(int segment=0;segment<3;segment++) {
            float a=lo+(hi-lo)*segment/3,b=lo+(hi-lo)*(segment+1)/3;
            float ta=(index+a-.5f)/distance,tb=(index+b-.5f)/distance;
            if(end){ta=1+(a-.5f)/distance;tb=1+(b-.5f)/distance;}
            float ya=.9125f-4*sag*ta*(1-ta),yb=.9125f-4*sag*tb*(1-tb);
            float[][] start=new float[4][3],finish=new float[4][3];
            for(int k=0;k<4;k++) {
                float c=cross+(k==0||k==3?-r:r),ay=ya+(k<2?-r:r),by=yb+(k<2?-r:r);
                start[k]=axis==Direction.Axis.X?new float[]{a,ay,c}:new float[]{c,ay,a};
                finish[k]=axis==Direction.Axis.X?new float[]{b,by,c}:new float[]{c,by,b};
            }
            for(int k=0;k<4;k++){int j=(k+1)%4;float[][] p={start[k],finish[k],finish[j],start[j]};quad(out,material,p,.03f,.53f,.45f,.95f,0xFF555B60);}
        }
    }
    private static void reverse(float[][] p){float[] a=p[0];p[0]=p[3];p[3]=a;a=p[1];p[1]=p[2];p[2]=a;}
    private static void quad(QuadEmitter out,Material.Baked material,float[][] p,float u0,float v0,float u1,float v1,int color) {
        var a=p[0];var b=p[1];var c=p[2];float ax=b[0]-a[0],ay=b[1]-a[1],az=b[2]-a[2],bx=c[0]-a[0],by=c[1]-a[1],bz=c[2]-a[2];
        float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(l<1e-9)return;
        for(int i=0;i<4;i++)out.pos(i,p[i][0],p[i][1],p[i][2]).normal(i,nx/l,ny/l,nz/l).uv(i,(i==0||i==3?u0:u1)*16,(i<2?v0:v1)*16).color(i,color);
        out.materialBake(material,0).tag(FurnitureMeshModels.MESH_QUAD_TAG).cullFace(null).tintIndex(-1).ambientOcclusion(TriState.TRUE).emit();
    }
    private RoadFurnitureModels(){}
}
