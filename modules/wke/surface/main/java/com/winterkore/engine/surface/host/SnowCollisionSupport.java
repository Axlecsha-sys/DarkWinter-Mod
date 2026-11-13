// Entity support samples the exact authored top; Minecraft still performs its ordinary sweep and body collision.
package com.winterkore.engine.surface.host;

import com.winterkore.engine.surface.geometry.SnowTerrainGeometry;
import com.winterkore.engine.surface.SnowTravelPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** VoxelShape cannot encode a sloping triangle: each query uses a conservative flat support over the real footprint. */
public final class SnowCollisionSupport {
    private static final int FINE_CACHE_CELLS=256,FINE_MAX_GRID=16;
    private static final Map<Level,LinkedHashMap<Long,FineShape>> FINE_CACHE=new WeakHashMap<>();
    private record FineShape(double a,double b,double c,double d,Object contacts,VoxelShape shape){
        boolean matches(double[] corners,Object key){
            return a==corners[0]&&b==corners[1]&&c==corners[2]&&d==corners[3]&&Objects.equals(contacts,key);
        }
    }
    private SnowCollisionSupport(){}
    public static VoxelShape shape(BlockState state,Level level,BlockPos pos,CollisionContext context,VoxelShape ordinary) {
        if(!(context instanceof EntityCollisionContext entityContext)||context.isPlacement())return ordinary;
        boolean soil=SnowHostGeometry.soil(state);
        Entity entity=entityContext.getEntity();if(entity==null||entity.noPhysics)return ordinary;
        AABB body=SnowCollisionQuery.footprint(entity);if(body.getXsize()>2||body.getZsize()>2)return ordinary;
        SnowHostGeometry.Cell cell=soil?SnowSurfaceRuntime.geometryForSoilCollision(level,pos.above()):SnowSurfaceRuntime.geometry(level,pos);
        if(cell==null||soil&&(cell.mask()==0||cell.layers()==0))return ordinary;
        if(cell.generic())return genericShape(level,cell,entity,ordinary);
        if(state.isAir()&&cell.layers()==0){
            VoxelShape fine=fineAirShape(level,cell);
            if(fine!=null)return Shapes.or(ordinary,fine);
        }
        double x0=Math.max(body.minX,pos.getX()),x1=Math.min(body.maxX,pos.getX()+1.0);
        double z0=Math.max(body.minZ,pos.getZ()),z1=Math.min(body.maxZ,pos.getZ()+1.0);
        // Adjacent cells can participate in the sweep before the current footprint reaches them.
        if(x0>x1){double x=Math.clamp(entity.getX(),pos.getX(),pos.getX()+1.0);x0=x1=x;}
        if(z0>z1){double z=Math.clamp(entity.getZ(),pos.getZ(),pos.getZ()+1.0);z0=z1=z;}
        double top=cellSupport(level,cell,x0,z0,x1,z1)-pos.getY();
        if(soil) {
            // Only the shallow authored shoulder can lower this natural soil owner.
            // Snow above the original soil top remains the responsibility of its own owner.
            return top>=1?ordinary:Shapes.box(0,0,0,1,Math.max(0,top),1);
        }
        if(cell.layers()==0&&top<=0)return ordinary;
        if(top<=0)return Shapes.empty();
        VoxelShape snow=Shapes.box(0,0,0,1,top,1);
        // Ground plants and furniture keep their solid stem/body. Only native snow owns its whole volume.
        return SnowHostGeometry.nativeLayers(state)>0?snow:Shapes.or(ordinary,snow);
    }
    /** Clip every cap to actual exposed support, preserving holes and the ordinary furniture/stone body. */
    private static VoxelShape genericShape(Level level,SnowHostGeometry.Cell cell,Entity entity,VoxelShape ordinary){
        VoxelShape result=ordinary;AABB body=SnowCollisionQuery.footprint(entity);BlockPos pos=cell.pos();
        double[] corners=SnowSurfaceRuntime.fineCornersMillimeters(level,cell);
        for(var patch:cell.patches()){
            var face=cell.patches().size()==1?cell:new SnowHostGeometry.Cell(cell.pos(),0,0,0,cell.baseOffset(),java.util.List.of(patch));
            double x0=Math.clamp(body.minX-pos.getX(),patch.minX(),patch.maxX());
            double x1=Math.clamp(body.maxX-pos.getX(),patch.minX(),patch.maxX());
            double z0=Math.clamp(body.minZ-pos.getZ(),patch.minZ(),patch.maxZ());
            double z1=Math.clamp(body.maxZ-pos.getZ(),patch.minZ(),patch.maxZ());
            double top=finiteMax(at(level,face,corners,x0,z0),at(level,face,corners,x1,z0));
            top=finiteMax(top,at(level,face,corners,x0,z1));top=finiteMax(top,at(level,face,corners,x1,z1));
            top-=pos.getY();
            if(Double.isFinite(top)&&top>patch.top()+1e-7)
                result=Shapes.or(result,Shapes.box(patch.minX(),patch.top(),patch.minZ(),patch.maxX(),top,patch.maxZ()));
        }
        return result;
    }
    private static double finiteMax(double a,double b){return !Double.isFinite(a)?b:!Double.isFinite(b)?a:Math.max(a,b);}
    /** Stable spatial fine-snow solids: vanilla sees the same small steps before and after moving.
     * Only zero-layer AIR owners use this path; authored snow, soil and slabs retain their existing shapes. */
    private static boolean emptyContactKey(Object key){
        if(!(key instanceof java.util.List<?> list))return false;
        for(Object value:list)if(!emptyContactKey(value))return false;
        return true;
    }
    private static VoxelShape fineAirShape(Level level,SnowHostGeometry.Cell cell){
        double[] corners=SnowSurfaceRuntime.fineCornersMillimeters(level,cell);
        double min=Math.min(Math.min(corners[0],corners[1]),Math.min(corners[2],corners[3]));
        double max=Math.max(Math.max(corners[0],corners[1]),Math.max(corners[2],corners[3]));
        // Bounded fresh-cover fix. Extremely steep/deep old fields keep their previous policy.
        if(!Double.isFinite(min+max)||max-min>50)return null;
        Object contacts=SnowSurfaceRuntime.contactSnapshotKey(level,cell.pos());
        LinkedHashMap<Long,FineShape> cache;
        synchronized(FINE_CACHE){cache=FINE_CACHE.computeIfAbsent(level,ignored->new LinkedHashMap<>(32,.75f,true));}
        long position=cell.pos().asLong();
        synchronized(cache){
            FineShape old=cache.get(position);
            if(old!=null&&old.matches(corners,contacts))return old.shape;
        }
        // For an undepressed bilinear field, tile oscillation <=2*cornerRange/grid.
        // Taking its four-corner maximum is conservative and at most6.25mm above the exact top.
        int grid=Math.clamp((int)Math.ceil(2*(max-min)/6.25),1,FINE_MAX_GRID);
        if(!emptyContactKey(contacts)){
            if(max>50)return null;
            grid=Math.max(grid,8);
        }
        VoxelShape shape=Shapes.empty();
        for(int z=0;z<grid;z++)for(int x=0;x<grid;x++){
            double x0=x/(double)grid,x1=(x+1)/(double)grid,z0=z/(double)grid,z1=(z+1)/(double)grid;
            double top=0;
            // The same physical contact relief remains active; midpoints also preserve rounded contact rims.
            for(int iz=0;iz<3;iz++)for(int ix=0;ix<3;ix++)
                top=Math.max(top,at(level,cell,corners,x0+(x1-x0)*ix*.5,z0+(z1-z0)*iz*.5)-cell.pos().getY());
            if(top>1e-9)shape=Shapes.or(shape,Shapes.box(x0,0,z0,x1,top,z1));
        }
        synchronized(cache){
            cache.put(position,new FineShape(corners[0],corners[1],corners[2],corners[3],contacts,shape));
            if(cache.size()>FINE_CACHE_CELLS)cache.remove(cache.keySet().iterator().next());
        }
        return shape;
    }
    /** The same support definition used by shape(): maximum over the entity's horizontal footprint. */
    public static double supportHeight(Level level,AABB footprint,double estimatedY) {
        if(footprint.getXsize()>2||footprint.getZsize()>2)return Double.NaN;
        double max=Double.NEGATIVE_INFINITY;
        for(int x=(int)Math.floor(footprint.minX);x<=(int)Math.floor(Math.nextDown(footprint.maxX));x++)
            for(int z=(int)Math.floor(footprint.minZ);z<=(int)Math.floor(Math.nextDown(footprint.maxZ));z++) {
                if(!level.hasChunkAt(new BlockPos(x,(int)Math.floor(estimatedY),z)))continue;
                double support=Double.NaN,nearest=Double.POSITIVE_INFINITY;
                // The body can overlap a narrow tabletop/stone patch while the block centre is empty.
                int base=(int)Math.floor(estimatedY+.1);
                for(int dy=2;dy>=-3;dy--){
                    var c=SnowSurfaceRuntime.geometry(level,new BlockPos(x,base+dy,z));if(c==null)continue;
                    double candidate=cellSupport(level,c,Math.max(footprint.minX,x),Math.max(footprint.minZ,z),Math.min(footprint.maxX,x+1),Math.min(footprint.maxZ,z+1));
                    double distance=Math.abs(candidate-estimatedY);
                    if(Double.isFinite(candidate)&&distance<nearest&&distance<=1.75){support=candidate;nearest=distance;}
                }
                if(Double.isFinite(support))max=Math.max(max,support);
            }
        return max==Double.NEGATIVE_INFINITY?Double.NaN:max;
    }
    private static double cellSupport(Level level,SnowHostGeometry.Cell cell,double wx0,double wz0,double wx1,double wz1){
        double x0=wx0-cell.pos().getX(),z0=wz0-cell.pos().getZ(),x1=wx1-cell.pos().getX(),z1=wz1-cell.pos().getZ();
        double[] corners=SnowSurfaceRuntime.fineCornersMillimeters(level,cell);
        if(cell.generic()){
            double highest=Double.NaN;
            for(var patch:cell.patches()){
                var face=cell.patches().size()==1?cell:new SnowHostGeometry.Cell(cell.pos(),0,0,0,cell.baseOffset(),java.util.List.of(patch));
                double ax=Math.max(x0,patch.minX()),bx=Math.min(x1,patch.maxX());
                double az=Math.max(z0,patch.minZ()),bz=Math.min(z1,patch.maxZ());
                if(ax>=bx||az>=bz)continue;
                highest=finiteMax(highest,at(level,face,corners,ax,az));
                highest=finiteMax(highest,at(level,face,corners,bx,az));
                highest=finiteMax(highest,at(level,face,corners,ax,bz));
                highest=finiteMax(highest,at(level,face,corners,bx,bz));
            }
            return highest;
        }
        double max=Math.max(Math.max(at(level,cell,corners,x0,z0),at(level,cell,corners,x1,z0)),
                Math.max(at(level,cell,corners,x0,z1),at(level,cell,corners,x1,z1)));
        // Clipped triangle extrema can lie on an original grid/diagonal edge, not just the AABB corners.
        for(var piece:SnowTerrainGeometry.shape(Math.max(1,cell.layers()),cell.mask()).snowOwner()) {
            if(piece.face()!=0)continue;var points=piece.vertices();
            for(int i=0;i<points.size();i++){
                var a=points.get(i);var b=points.get((i+1)%points.size());
                if(a.x()>=x0&&a.x()<=x1&&a.z()>=z0&&a.z()<=z1)max=Math.max(max,at(level,cell,corners,a.x(),a.z()));
                if(a.x()!=b.x())for(double x:new double[]{x0,x1}){
                    double t=(x-a.x())/(b.x()-a.x()),z=a.z()+(b.z()-a.z())*t;
                    if(t>=0&&t<=1&&z>=z0&&z<=z1)max=Math.max(max,at(level,cell,corners,x,z));
                }
                if(a.z()!=b.z())for(double z:new double[]{z0,z1}){
                    double t=(z-a.z())/(b.z()-a.z()),x=a.x()+(b.x()-a.x())*t;
                    if(t>=0&&t<=1&&x>=x0&&x<=x1)max=Math.max(max,at(level,cell,corners,x,z));
                }
            }
            if(points.size()==4) {
                var a=points.getFirst();var b=points.get(2);
                if(a.x()!=b.x())for(double x:new double[]{x0,x1}) {
                    double t=(x-a.x())/(b.x()-a.x()),z=a.z()+(b.z()-a.z())*t;
                    if(t>=0&&t<=1&&z>=z0&&z<=z1)max=Math.max(max,at(level,cell,corners,x,z));
                }
                if(a.z()!=b.z())for(double z:new double[]{z0,z1}) {
                    double t=(z-a.z())/(b.z()-a.z()),x=a.x()+(b.x()-a.x())*t;
                    if(t>=0&&t<=1&&x>=x0&&x<=x1)max=Math.max(max,at(level,cell,corners,x,z));
                }
            }
        }
        return max;
    }
    private static double at(Level level,SnowHostGeometry.Cell cell,double[] corners,double x,double z){
        double wx=cell.pos().getX()+x,wz=cell.pos().getZ()+z;
        double height=SnowHostGeometry.sample(cell,wx,wz,corners).height();
        if(!Double.isFinite(height))return Double.NaN;
        double compacted=SnowHostGeometry.clampCompactedHeight(cell,wx,wz,height,SnowSurfaceRuntime.depressionMillimeters(level,cell,corners,wx,wz));
        double bottom=SnowHostGeometry.snowBottomHeight(cell,wx,wz);
        double sink=SnowTravelPolicy.sinkMillimeters((height-bottom)*1000,cell.layers()*125);
        // A footprint can compress more; entering loose snow does not wait for a later stamping tick to sink.
        return Math.max(bottom,Math.min(compacted,height-sink/1000));
    }
}
