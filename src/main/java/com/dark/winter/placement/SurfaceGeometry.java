package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.RoofSegmentBlock;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Shared authored furniture surface queries. Packed quads are indexed once; no client renderer enters the server. */
public final class SurfaceGeometry {
    public record Hit(BlockPos owner,Vec3 point,Direction face,double distanceSquared){}
    private record Mesh(float[] points,Node tree){}
    private record Node(AABB bounds,Node left,Node right,int[] faces){}
    private static volatile Map<String,List<Mesh>> furniture;
    private SurfaceGeometry(){}
    /** Prepare the shared packed BVHs during initialization, rather than on the first in-world aim/use. */
    public static void initialize(){meshes();}
    private static Map<String,List<Mesh>> meshes(){
        Map<String,List<Mesh>> ready=furniture;if(ready!=null)return ready;
        synchronized(SurfaceGeometry.class){
            if(furniture!=null)return furniture;
            var entries=new HashMap<String,List<Mesh>>();
            try(InputStream in=SurfaceGeometry.class.getResourceAsStream("/assets/darkwinter/furniture/meshes.json");
                JsonReader reader=new JsonReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8))){
                reader.beginObject();
                while(reader.hasNext()){
                    String name=reader.nextName();if(!name.equals("entries")){reader.skipValue();continue;}
                    reader.beginArray();while(reader.hasNext()){
                        JsonObject entry=JsonParser.parseReader(reader).getAsJsonObject();
                        String id=entry.get("id").getAsString();var parts=new ArrayList<Mesh>();
                        for(JsonElement p:entry.getAsJsonArray("parts")){
                            JsonArray quads=p.getAsJsonObject().getAsJsonArray("quads");var values=new ArrayList<Float>();
                            for(JsonElement q:quads){
                                JsonObject quad=q.getAsJsonObject();if(quad.has("cutout")&&quad.get("cutout").getAsBoolean())continue;
                                for(JsonElement vertex:quad.getAsJsonArray("p"))for(JsonElement coordinate:vertex.getAsJsonArray())
                                    values.add(coordinate.getAsFloat()/16f);
                            }
                            float[] points=new float[values.size()];for(int j=0;j<points.length;j++)points[j]=values.get(j);
                            Integer[] faces=new Integer[points.length/12];for(int j=0;j<faces.length;j++)faces[j]=j;
                            parts.add(new Mesh(points,faces.length==0?null:build(points,faces,0,faces.length)));
                        }entries.put(id,List.copyOf(parts));
                    }reader.endArray();
                }reader.endObject();
                return furniture=Map.copyOf(entries);
            }catch(IOException|RuntimeException failure){throw new IllegalStateException("Cannot load authoritative furniture surfaces",failure);}
        }
    }
    private static AABB bounds(float[] p,int face){
        int o=face*12;double x0=p[o],y0=p[o+1],z0=p[o+2],x1=x0,y1=y0,z1=z0;
        for(int v=1;v<4;v++){x0=Math.min(x0,p[o+v*3]);x1=Math.max(x1,p[o+v*3]);y0=Math.min(y0,p[o+v*3+1]);y1=Math.max(y1,p[o+v*3+1]);z0=Math.min(z0,p[o+v*3+2]);z1=Math.max(z1,p[o+v*3+2]);}
        return new AABB(x0,y0,z0,x1,y1,z1);
    }
    private static Node build(float[] points,Integer[] faces,int start,int end){
        AABB box=bounds(points,faces[start]);for(int i=start+1;i<end;i++)box=box.minmax(bounds(points,faces[i]));
        if(end-start<=8){int[] leaf=new int[end-start];for(int i=0;i<leaf.length;i++)leaf[i]=faces[start+i];return new Node(box,null,null,leaf);}
        int axis=box.getXsize()>=box.getYsize()&&box.getXsize()>=box.getZsize()?0:box.getYsize()>=box.getZsize()?1:2;
        Arrays.sort(faces,start,end,Comparator.comparingDouble(f->{AABB b=bounds(points,f);return axis==0?b.minX+b.maxX:axis==1?b.minY+b.maxY:b.minZ+b.maxZ;}));
        int middle=(start+end)>>>1;return new Node(box,build(points,faces,start,middle),build(points,faces,middle,end),null);
    }
    private static Vec3 rotate(Vec3 p,int turns){for(int n=0;n<turns;n++)p=new Vec3(1-p.z,p.y,p.x);return p;}
    private static Vec3 scaleWidth(Vec3 point, double width) {
        return width == 1 ? point : new Vec3(point.x * width, point.y, point.z);
    }
    private static Vec3 positioned(Vec3 point, int turns, double width) {
        return rotate(scaleWidth(point, width), turns);
    }
    private static int facing(BlockState state){return state.hasProperty(FurnitureBlock.FACING)?switch(state.getValue(FurnitureBlock.FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;}:0;}
    private static Vec3 vertex(float[] points,int o,int v){return new Vec3(points[o+v*3],points[o+v*3+1],points[o+v*3+2]);}
    private record RayHit(double t,Vec3 normal){}
    private static RayHit triangle(Vec3 from,Vec3 delta,Vec3 a,Vec3 b,Vec3 c){
        Vec3 ab=b.subtract(a),ac=c.subtract(a),cross=delta.cross(ac);double det=ab.dot(cross);
        if(Math.abs(det)<1e-12)return null;double inv=1/det;Vec3 s=from.subtract(a);
        double u=s.dot(cross)*inv;if(u< -1e-7||u>1.0000001)return null;
        Vec3 q=s.cross(ab);double v=delta.dot(q)*inv;if(v< -1e-7||u+v>1.0000001)return null;
        double t=ac.dot(q)*inv;if(t< -1e-7||t>1.0000001)return null;
        return new RayHit(Math.clamp(t,0,1),ab.cross(ac).normalize());
    }
    private static RayHit query(Mesh mesh,Node node,Vec3 from,Vec3 to,RayHit best){
        if(node==null||!node.bounds.inflate(1e-7).intersects(new AABB(from,to).inflate(1e-7)))return best;
        if(node.faces!=null){
            Vec3 delta=to.subtract(from);
            for(int face:node.faces){int o=face*12;Vec3 a=vertex(mesh.points,o,0),b=vertex(mesh.points,o,1),c=vertex(mesh.points,o,2),d=vertex(mesh.points,o,3);
                RayHit first=triangle(from,delta,a,b,c),second=triangle(from,delta,a,c,d);
                if(first!=null&&(best==null||first.t<best.t))best=first;
                if(second!=null&&(best==null||second.t<best.t))best=second;
            }return best;
        }
        return query(mesh,node.right,from,to,query(mesh,node.left,from,to,best));
    }
    private static Direction direction(Vec3 normal){
        double x=Math.abs(normal.x),y=Math.abs(normal.y),z=Math.abs(normal.z);
        return x>=y&&x>=z?(normal.x>=0?Direction.EAST:Direction.WEST):y>=z?(normal.y>=0?Direction.UP:Direction.DOWN):(normal.z>=0?Direction.SOUTH:Direction.NORTH);
    }
    public static Hit hit(BlockGetter level,BlockPos owner,Vec3 from,Vec3 to){
        BlockState state=level.getBlockState(owner);
        // Gates use their outline boxes so thin mesh openings remain a consistent interaction target.
        if(state.getBlock() instanceof FurnitureBlock block
                && !(block instanceof com.dark.winter.block.FenceGateFurnitureBlock || block instanceof com.dark.winter.block.DiningChairBlock)
                && !(block instanceof com.dark.winter.block.StorageFurnitureBlock storage && storage.leafIsSeparate(level,owner,state))){
            var parts=meshes().get(block.definition().id());int part=state.getValue(FurnitureBlock.PART);
            if(parts==null||part>=parts.size())return null;Mesh mesh=parts.get(part);int turns=facing(state);
            Vec3 origin=Vec3.atLowerCornerOf(owner);
            double yaw = AnchorRuntime.rotation(level, owner);
            double inverseWidth = 1 / (double) RoofSegmentBlock.width(state);
            Vec3 localFrom = scaleWidth(rotate(AnchorRuntime.logicalPoint(level, owner, from).subtract(origin), (4 - turns) & 3), inverseWidth);
            Vec3 localTo = scaleWidth(rotate(AnchorRuntime.logicalPoint(level, owner, to).subtract(origin), (4 - turns) & 3), inverseWidth);
            RayHit hit=query(mesh,mesh.tree,localFrom,localTo,null);if(hit==null)return null;
            Vec3 point=from.add(to.subtract(from).scale(hit.t));
            Vec3 normal = scaleWidth(hit.normal, inverseWidth);
            for(int n=0;n<turns;n++)normal=new Vec3(-normal.z,normal.y,normal.x);
            normal = FurnitureTransform.point(normal, Vec3.ZERO, yaw);
            return new Hit(owner,point,direction(normal),from.distanceToSqr(point));
        }
        BlockHitResult hit=state.getShape(level,owner,CollisionContext.empty()).clip(from,to,owner);
        return hit==null?null:new Hit(owner,hit.getLocation(),hit.getDirection(),from.distanceToSqr(hit.getLocation()));
    }
    /** Visual mounting bounds in the logical block's local coordinates, before its anchor displacement. */
    public static AABB bounds(BlockGetter level,BlockPos owner,BlockState state){
        if(state.getBlock() instanceof FurnitureBlock block){
            var parts=meshes().get(block.definition().id());int part=state.getValue(FurnitureBlock.PART);
            if(parts!=null&&part<parts.size()&&parts.get(part).tree!=null){
                AABB b=parts.get(part).tree.bounds;int turns=facing(state);
                double width = RoofSegmentBlock.width(state);
                Vec3 a = positioned(new Vec3(b.minX, b.minY, b.minZ), turns, width);
                Vec3 c = positioned(new Vec3(b.maxX, b.maxY, b.maxZ), turns, width);
                return new AABB(a,c);
            }
        }
        var shape=AnchorRuntime.rawShape(level,owner,state,false);
        return shape.isEmpty()?new AABB(0,0,0,1,1,1):shape.bounds();
    }

    static AABB assemblyBounds(BlockGetter level, BlockPos root, BlockState state) {
        AABB result = bounds(level, root, state);
        if (state.getBlock() instanceof FurnitureBlock furniture) {
            Direction facing = state.getValue(FurnitureBlock.FACING);
            for (int part = 1; part < furniture.definition().parts().size(); part++) {
                BlockPos offset = furniture.partOffset(part, facing);
                result = result.minmax(bounds(level, root.offset(offset), state.setValue(FurnitureBlock.PART, part)).move(offset));
            }
        }
        return result;
    }
    public static AABB assemblyBounds(BlockGetter level, BlockPos root, BlockState state, double yaw) {
        return FurnitureTransform.bounds(assemblyBounds(level, root, state), Vec3.ZERO, yaw);
    }
    private static boolean triangleBox(Vec3 a,Vec3 b,Vec3 c,AABB box){
        Vec3 center=box.getCenter();Vec3 half=new Vec3(box.getXsize()*.5,box.getYsize()*.5,box.getZsize()*.5);
        a=a.subtract(center);b=b.subtract(center);c=c.subtract(center);
        Vec3[] edges={b.subtract(a),c.subtract(b),a.subtract(c)};
        var axes=new ArrayList<Vec3>(13);axes.add(new Vec3(1,0,0));axes.add(new Vec3(0,1,0));axes.add(new Vec3(0,0,1));
        axes.add(edges[0].cross(edges[1]));
        for(Vec3 e:edges){axes.add(e.cross(new Vec3(1,0,0)));axes.add(e.cross(new Vec3(0,1,0)));axes.add(e.cross(new Vec3(0,0,1)));}
        for(Vec3 axis:axes){
            if(axis.lengthSqr()<1e-20)continue;
            double x=a.dot(axis),y=b.dot(axis),z=c.dot(axis),radius=half.x*Math.abs(axis.x)+half.y*Math.abs(axis.y)+half.z*Math.abs(axis.z);
            if(Math.min(x,Math.min(y,z))>=radius-1e-7||Math.max(x,Math.max(y,z))<=-radius+1e-7)return false;
        }return true;
    }
    /** Only true rendered support geometry on the object's side of the contact plane may obstruct it. */
    public static boolean obstructs(Level level,BlockPos owner,AABB object,Direction face,Vec3 contact){
        BlockState state=level.getBlockState(owner);if(!(state.getBlock() instanceof FurnitureBlock block))return false;
        var parts=meshes().get(block.definition().id());int part=state.getValue(FurnitureBlock.PART);if(parts==null||part>=parts.size())return false;
        Mesh mesh=parts.get(part);int turns=facing(state);Vec3 origin=Vec3.atLowerCornerOf(owner).add(AnchorRuntime.offset(level,owner));
        double width = RoofSegmentBlock.width(state);
        double yaw = AnchorRuntime.rotation(level, owner);
        Vec3 pivot = yaw == 0 ? origin : Vec3.atLowerCornerOf(block.origin(owner, state)).add(AnchorRuntime.offset(level, owner));
        Vec3 normal=new Vec3(face.getStepX(),face.getStepY(),face.getStepZ());
        for(int o=0;o<mesh.points.length;o+=12){
            Vec3[] q = {positioned(vertex(mesh.points, o, 0), turns, width).add(origin),
                positioned(vertex(mesh.points, o, 1), turns, width).add(origin),
                positioned(vertex(mesh.points, o, 2), turns, width).add(origin),
                positioned(vertex(mesh.points, o, 3), turns, width).add(origin)};
            if (yaw != 0) for (int vertex = 0; vertex < q.length; vertex++)
                q[vertex] = FurnitureTransform.point(q[vertex], pivot, yaw);
            for(int triangle=0;triangle<2;triangle++){
                var input=List.of(q[0],q[triangle+1],q[triangle+2]);var clipped=new ArrayList<Vec3>(4);
                Vec3 previous=input.getLast();double before=previous.subtract(contact).dot(normal)-1e-5;
                for(Vec3 current:input){
                    double after=current.subtract(contact).dot(normal)-1e-5;
                    if((before>0)!=(after>0))clipped.add(previous.add(current.subtract(previous).scale(before/(before-after))));
                    if(after>0)clipped.add(current);previous=current;before=after;
                }
                for(int i=1;i+1<clipped.size();i++)if(triangleBox(clipped.getFirst(),clipped.get(i),clipped.get(i+1),object))return true;
            }
        }return false;
    }
    /** Exact contact test also finds a displaced logical owner indexed in the physical cell. */
    public static boolean supports(Level level,BlockPos support,Direction face,Vec3 point){
        if(!level.hasChunkAt(support))return false;
        Vec3 n=new Vec3(face.getStepX(),face.getStepY(),face.getStepZ());
        var owners=new LinkedHashSet<BlockPos>();owners.add(support);
        BlockPos physical=BlockPos.containing(point.subtract(n.scale(.002)));
        owners.add(physical);owners.addAll(AnchorRuntime.logicalOwners(level,physical));
        for(BlockPos owner:owners){if(!level.hasChunkAt(owner))continue;Hit hit=hit(level,owner,point.add(n.scale(.025)),point.subtract(n.scale(.025)));
            if(hit!=null&&hit.face==face&&hit.point.distanceToSqr(point)<=.000025)return true;
        }return false;
    }
}
