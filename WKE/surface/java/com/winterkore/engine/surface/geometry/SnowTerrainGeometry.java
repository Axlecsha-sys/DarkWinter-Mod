// Shared rounded terrace mesh: native rendering, dynamic caps and physical sampling use one topology.
package com.winterkore.engine.surface.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Geometry is local to its owner cell. Mask bits0..3 are N/E/S/W edges,4..7 NW/NE/SE/SW corners. */
public class SnowTerrainGeometry {
    public static final float WIDTH=.375f, DROP=.125f;
    private static final float[] GRID={0,WIDTH,1-WIDTH,1};
    private static final int SHOULDER_CELLS=8;
    private static final AtomicReferenceArray<Terrain> TERRAINS=new AtomicReferenceArray<>(8*16);
    public record Vertex(float x,float y,float z) {}
    /** face:0=top,1=N,2=E,3=S,4=W. Snow pieces retain their snow-space UV coordinates. */
    public record Piece(int face,List<Vertex> vertices,boolean snow) {}
    public record Shape(List<Piece> snowOwner,List<Piece> soilOwner) {}
    protected SnowTerrainGeometry(){}
    private record SurfaceTriangle(Vertex a,Vertex b,Vertex c) {}
    private record Terrain(Shape shape,SurfaceTriangle[] top,boolean rounded) {}
    public static Shape shape(int layers,int mask) {return terrain(layers,mask).shape;}
    private static Terrain terrain(int layers,int mask) {
        if(layers<1||layers>8||(mask&~255)!=0)throw new IllegalArgumentException("Snow terrace key");
        // Only the globally shared corner bits affect this shape. Edge flags must not
        // multiply identical cached meshes and their sampling arrays by sixteen.
        int key=(layers-1)*16+(mask>>>4);Terrain known=TERRAINS.get(key);if(known!=null)return known;
        Shape shape=build(layers,mask);var top=new ArrayList<SurfaceTriangle>();
        for(var piece:shape.snowOwner())if(piece.face()==0){
            var vertices=piece.vertices();var a=vertices.getFirst();
            for(int i=1;i<vertices.size()-1;i++)top.add(new SurfaceTriangle(a,vertices.get(i),vertices.get(i+1)));
        }
        boolean rounded=(mask&240)!=0;
        if(top.size()!=(rounded?2*SHOULDER_CELLS*SHOULDER_CELLS:2))throw new IllegalStateException("Snow sampling topology");
        Terrain made=new Terrain(shape,top.toArray(SurfaceTriangle[]::new),rounded);
        return TERRAINS.compareAndSet(key,null,made)?made:TERRAINS.get(key);
    }
    private static int triangleIndex(boolean rounded,double x,double z){
        if(!rounded)return z>=x?0:1;
        int ix=Math.min(SHOULDER_CELLS-1,(int)(x*SHOULDER_CELLS));
        int iz=Math.min(SHOULDER_CELLS-1,(int)(z*SHOULDER_CELLS));
        double localX=x*SHOULDER_CELLS-ix,localZ=z*SHOULDER_CELLS-iz;
        return ((iz*SHOULDER_CELLS+ix)<<1)+(localZ>=localX?0:1);
    }
    /** Rounded join to the flat core; exact old linear heights remain on shared edges. */
    public static double shoulderDrop(int mask,double x,double z){
        x=Math.clamp(x,0,1);z=Math.clamp(z,0,1);
        if((mask&240)==0)return 0;
        double nw=(mask&16)==0?0:DROP,ne=(mask&32)==0?0:DROP;
        double se=(mask&64)==0?0:DROP,sw=(mask&128)==0?0:DROP;
        double north=nw+(ne-nw)*x,south=sw+(se-sw)*x;
        double boundary=north+(south-north)*z;
        double ex=edgeWeight(x),ez=edgeWeight(z);
        return boundary*(ex+ez-ex*ez);
    }
    private static double edgeWeight(double t){
        double q=Math.clamp((WIDTH-Math.min(t,1-t))/WIDTH,0,1);
        return q*q*(3-2*q);
    }
    private static double edgeDerivative(double t){
        if(t==.5)return 0;
        double q=Math.clamp((WIDTH-Math.min(t,1-t))/WIDTH,0,1);
        return 6*q*(1-q)*(t<.5?-1:1)/WIDTH;
    }
    /** Visual normal of the shared rounded field, independent of a quad's diagonal. */
    public static Sample smoothSample(int layers,int mask,int crust,double x,double z){
        double height=height(layers,mask,crust,x,z);
        x=Math.clamp(x,0,1);z=Math.clamp(z,0,1);
        double dx=0,dz=0;
        if((mask&240)!=0){
            double nw=(mask&16)==0?0:DROP,ne=(mask&32)==0?0:DROP;
            double se=(mask&64)==0?0:DROP,sw=(mask&128)==0?0:DROP;
            double north=nw+(ne-nw)*x,south=sw+(se-sw)*x;
            double boundary=north+(south-north)*z,ex=edgeWeight(x),ez=edgeWeight(z),weight=ex+ez-ex*ez;
            dx=-((ne-nw)*(1-z)+(se-sw)*z)*weight-boundary*edgeDerivative(x)*(1-ez);
            dz=-(south-north)*weight-boundary*edgeDerivative(z)*(1-ex);
        }else if(mask==0&&crust!=0){
            double nw=(crust&3)/64.0,ne=((crust>>>2)&3)/64.0;
            double se=((crust>>>4)&3)/64.0,sw=((crust>>>6)&3)/64.0;
            dx=(ne-nw)*(1-z)+(se-sw)*z;dz=(sw-nw)*(1-x)+(se-ne)*x;
        }
        double length=Math.sqrt(dx*dx+1+dz*dz);
        return new Sample(height,-dx/length,1/length,-dz/length);
    }
    public static float dropAtGrid(int mask,int x,int z) {
        if(x>0&&x<3&&z>0&&z<3)return 0;
        // Every shared edge is the straight interpolation of its two global corners.
        // Independent midpoint drops create a V-shaped notch beside a vetoed contact.
        if(z==0)return edgeDrop(mask,4,5,GRID[x]);
        if(z==3)return edgeDrop(mask,7,6,GRID[x]);
        return x==0?edgeDrop(mask,4,7,GRID[z]):edgeDrop(mask,5,6,GRID[z]);
    }
    private static float edgeDrop(int mask,int first,int second,float t) {
        float a=(mask&(1<<first))==0?0:DROP,b=(mask&(1<<second))==0?0:DROP;
        return a+(b-a)*t;
    }
    private static Shape build(int layers,int mask) {
        var snow=new ArrayList<Piece>();var soil=new ArrayList<Piece>();float height=layers/8f;
        if((mask&240)==0){
            split(snow,soil,0,List.of(new Vertex(0,height,0),new Vertex(0,height,1),
                    new Vertex(1,height,1),new Vertex(1,height,0)));
        }else for(int z=0;z<SHOULDER_CELLS;z++)for(int x=0;x<SHOULDER_CELLS;x++){
            Vertex a=roundedTop(mask,height,x,z),b=roundedTop(mask,height,x,z+1);
            Vertex c=roundedTop(mask,height,x+1,z+1),d=roundedTop(mask,height,x+1,z);
            // Native quads and the O(1) sampler share this exact012/230 diagonal.
            split(snow,soil,0,List.of(a,b,c));split(snow,soil,0,List.of(a,c,d));
        }
        for(int side=0;side<4;side++)for(int segment=0;segment<3;segment++) {
            int ax,bx,az,bz;
            switch(side){case 0->{ax=segment;bx=segment+1;az=bz=0;}case 1->{ax=bx=3;az=segment;bz=segment+1;}
                case 2->{ax=3-segment;bx=2-segment;az=bz=3;}default->{ax=bx=0;az=3-segment;bz=2-segment;}}
            float da=dropAtGrid(mask,ax,az),db=dropAtGrid(mask,bx,bz);
            Vertex ba=new Vertex(GRID[ax],-da,GRID[az]),bb=new Vertex(GRID[bx],-db,GRID[bz]);
            // Winding is outward for the four sides.
            split(snow,soil,side+1,List.of(ba,new Vertex(ba.x,height-da,ba.z),new Vertex(bb.x,height-db,bb.z),bb));
            soil.add(new Piece(side+1,List.of(new Vertex(ba.x,0,ba.z),new Vertex(ba.x,1-da,ba.z),new Vertex(bb.x,1-db,bb.z),new Vertex(bb.x,0,bb.z)),false));
        }
        return new Shape(List.copyOf(snow),List.copyOf(soil));
    }
    private static Vertex roundedTop(int mask,float height,int x,int z){
        float px=x/(float)SHOULDER_CELLS,pz=z/(float)SHOULDER_CELLS;
        return new Vertex(px,height-(float)shoulderDrop(mask,px,pz),pz);
    }
    private static void split(List<Piece> snow,List<Piece> soil,int face,List<Vertex> polygon) {
        add(snow,face,clip(polygon,true),false);
        // Coplanar y0 belongs only to the upper owner; no duplicate white surfaces at the split.
        if(polygon.stream().anyMatch(v->v.y<0))add(soil,face,clip(polygon,false),true);
    }
    private static List<Vertex> clip(List<Vertex> input,boolean upper) {
        var output=new ArrayList<Vertex>(5);
        Vertex previous=input.getLast();boolean before=upper?previous.y>=0:previous.y<=0;
        for(Vertex current:input){boolean inside=upper?current.y>=0:current.y<=0;
            if(inside!=before){float t=-previous.y/(current.y-previous.y);output.add(new Vertex(previous.x+(current.x-previous.x)*t,0,previous.z+(current.z-previous.z)*t));}
            if(inside)output.add(current);previous=current;before=inside;
        }return output;
    }
    private static void add(List<Piece> target,int face,List<Vertex> input,boolean lower) {
        if(input.size()<3)return;
        var vertices=new ArrayList<Vertex>(input.size());
        for(Vertex p:input){Vertex v=new Vertex(clamp(p.x),clamp(p.y+(lower?1:0)),clamp(p.z));if(vertices.isEmpty()||!vertices.getLast().equals(v))vertices.add(v);}
        if(vertices.size()>2&&vertices.getFirst().equals(vertices.getLast()))vertices.removeLast();
        // Clipping a sloping shoulder can put three collinear boundary vertices first.
        // Remove only redundant convex-polygon edge points, never discard the remaining cap.
        boolean removed=true;
        while(removed&&vertices.size()>3){
            removed=false;
            for(int i=0;i<vertices.size();i++){
                Vertex a=vertices.get((i+vertices.size()-1)%vertices.size()),b=vertices.get(i),c=vertices.get((i+1)%vertices.size());
                float nx=(b.y-a.y)*(c.z-b.z)-(b.z-a.z)*(c.y-b.y),ny=(b.z-a.z)*(c.x-b.x)-(b.x-a.x)*(c.z-b.z),nz=(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);
                if(nx*nx+ny*ny+nz*nz<1e-12f){vertices.remove(i);removed=true;break;}
            }
        }
        if(vertices.size()<3)return;
        Vertex a=vertices.getFirst(),b=vertices.get(1),c=vertices.get(2);
        float nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y),ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z),nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
        if(nx*nx+ny*ny+nz*nz<1e-12f)return;
        target.add(new Piece(face,List.copyOf(vertices),true));
    }
    private static float clamp(float value){if(value<-.00001f||value>1.00001f)throw new IllegalStateException("Chamfer escaped owner cell: "+value);return Math.clamp(value,0,1);}

    /** Exact top-triangle sample of the existing authored chamfer/crust mesh. */
    public record Sample(double height,double normalX,double normalY,double normalZ) {}
    public static Sample sample(int layers,int mask,int crustCode,double localX,double localZ) {
        return sample(layers,mask,crustCode,localX,localZ,0.0);
    }
    /** Apply fine displacement to original mesh vertices before barycentric interpolation. */
    public static Sample sample(int layers,int mask,int crustCode,double localX,double localZ,double weatherDeltaMillimeters) {
        return sample(layers,mask,crustCode,localX,localZ,new double[]{weatherDeltaMillimeters,weatherDeltaMillimeters,weatherDeltaMillimeters,weatherDeltaMillimeters});
    }
    /** NW/NE/SE/SW displacements share global corners; interpolation follows original mesh triangles. */
    public static Sample sample(int layers,int mask,int crustCode,double localX,double localZ,double[] fineCornersMillimeters) {
        checkCorners(fineCornersMillimeters);
        checkPoint(crustCode,localX,localZ);
        double x=Math.clamp(localX,0,1),z=Math.clamp(localZ,0,1);
        var terrain=terrain(layers,mask);var triangle=terrain.top[triangleIndex(terrain.rounded,x,z)];
        Vertex a=triangle.a,b=triangle.b,c=triangle.c;
        double denominator=triangleDenominator(a,b,c);
        double u=triangleU(a,b,c,x,z,denominator),v=triangleV(a,b,c,x,z,denominator),w=1-u-v;
        double ay=vertexY(a,mask,crustCode,fineCornersMillimeters),by=vertexY(b,mask,crustCode,fineCornersMillimeters),cy=vertexY(c,mask,crustCode,fineCornersMillimeters);
        double abx=b.x()-a.x(),aby=by-ay,abz=b.z()-a.z();
        double acx=c.x()-a.x(),acy=cy-ay,acz=c.z()-a.z();
        double nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(length<1e-15)return new Sample(weightedHeight(u,v,w,ay,by,cy),0,1,0);
        if(ny<0)length=-length;
        return new Sample(weightedHeight(u,v,w,ay,by,cy),nx/length,ny/length,nz/length);
    }
    /** Exact authored height without normals, square roots or a temporary Sample. */
    public static double height(int layers,int mask,int crustCode,double localX,double localZ){
        return height(layers,mask,crustCode,localX,localZ,0.0);
    }
    public static double height(int layers,int mask,int crustCode,double localX,double localZ,double weatherDeltaMillimeters){
        if(!Double.isFinite(weatherDeltaMillimeters)||weatherDeltaMillimeters< -1500||weatherDeltaMillimeters>1500)
            throw new IllegalArgumentException("Invalid fine snow corner");
        checkPoint(crustCode,localX,localZ);
        return heightAt(layers,mask,crustCode,localX,localZ,null,weatherDeltaMillimeters);
    }
    public static double height(int layers,int mask,int crustCode,double localX,double localZ,double[] fineCornersMillimeters){
        checkCorners(fineCornersMillimeters);checkPoint(crustCode,localX,localZ);
        return heightAt(layers,mask,crustCode,localX,localZ,fineCornersMillimeters,0);
    }
    private static double heightAt(int layers,int mask,int crustCode,double localX,double localZ,double[] corners,double uniformDelta){
        double x=Math.clamp(localX,0,1),z=Math.clamp(localZ,0,1);
        var terrain=terrain(layers,mask);var triangle=terrain.top[triangleIndex(terrain.rounded,x,z)];
        Vertex a=triangle.a,b=triangle.b,c=triangle.c;
        double denominator=triangleDenominator(a,b,c);
        double u=triangleU(a,b,c,x,z,denominator),v=triangleV(a,b,c,x,z,denominator),w=1-u-v;
        double ay=corners==null?uniformVertexY(a,mask,crustCode,uniformDelta):vertexY(a,mask,crustCode,corners);
        double by=corners==null?uniformVertexY(b,mask,crustCode,uniformDelta):vertexY(b,mask,crustCode,corners);
        double cy=corners==null?uniformVertexY(c,mask,crustCode,uniformDelta):vertexY(c,mask,crustCode,corners);
        return weightedHeight(u,v,w,ay,by,cy);
    }
    /** Immutable authored height field for repeated world-space owner probes. */
    public static CompiledHeight compileHeight(int ownerX,int ownerZ,double baseHeight,int layers,int mask,int crustCode,
            double[] fineCornersMillimeters){
        checkCorners(fineCornersMillimeters);checkPoint(crustCode,0,0);
        if(layers<0||layers>8||!Double.isFinite(baseHeight))throw new IllegalArgumentException("Snow height owner");
        var terrain=terrain(Math.max(1,layers),mask);
        var triangles=new HeightTriangle[terrain.top.length];
        for(int i=0;i<triangles.length;i++){
            var source=terrain.top[i];Vertex a=source.a,b=source.b,c=source.c;
            double denominator=triangleDenominator(a,b,c);
            // Vertex coordinates are floats. Subtract before widening, as in triangleU/V.
            triangles[i]=new HeightTriangle(c.x(),c.z(),b.z()-c.z(),c.x()-b.x(),c.z()-a.z(),a.x()-c.x(),denominator,
                    vertexY(a,mask,crustCode,fineCornersMillimeters),vertexY(b,mask,crustCode,fineCornersMillimeters),vertexY(c,mask,crustCode,fineCornersMillimeters),
                    uniformVertexY(a,mask,crustCode,0),uniformVertexY(b,mask,crustCode,0),uniformVertexY(c,mask,crustCode,0));
        }
        return new CompiledHeight(ownerX,ownerZ,baseHeight,layers/8.0,triangles,terrain.rounded);
    }
    private record HeightTriangle(double cx,double cz,double uz,double ux,double vx,double vz,double denominator,
            double topA,double topB,double topC,double baseA,double baseB,double baseC) {}

    public static final class CompiledHeight {
        private static final int TOP=0,BOTTOM=1,COMPACTED=2;
        private final int ownerX,ownerZ;
        private final double baseHeight,thickness;
        private final HeightTriangle[] triangles;
        private final boolean rounded;

        private CompiledHeight(int ownerX,int ownerZ,double baseHeight,double thickness,HeightTriangle[] triangles,boolean rounded){
            this.ownerX=ownerX;this.ownerZ=ownerZ;this.baseHeight=baseHeight;this.thickness=thickness;this.triangles=triangles;this.rounded=rounded;
        }
        public double skin(double worldX,double worldZ){return evaluate(worldX,worldZ,TOP,0);}
        public double bottom(double worldX,double worldZ){return evaluate(worldX,worldZ,BOTTOM,0);}
        public double compacted(double worldX,double worldZ,double depressionMillimeters){
            if(!Double.isFinite(depressionMillimeters))throw new IllegalArgumentException("Snow compaction height");
            return evaluate(worldX,worldZ,COMPACTED,depressionMillimeters);
        }
        private double evaluate(double worldX,double worldZ,int mode,double depressionMillimeters){
            double x=Math.clamp(worldX-ownerX,0,1),z=Math.clamp(worldZ-ownerZ,0,1);
            if(Double.isNaN(x)||Double.isNaN(z))throw new IllegalArgumentException("Snow surface sample");
            var triangle=triangles[triangleIndex(rounded,x,z)];
            double dx=x-triangle.cx,dz=z-triangle.cz;
            double u=(triangle.uz*dx+triangle.ux*dz)/triangle.denominator;
            double v=(triangle.vx*dx+triangle.vz*dz)/triangle.denominator,w=1-u-v;
            if(mode==TOP)return baseHeight+weightedHeight(u,v,w,triangle.topA,triangle.topB,triangle.topC);
            double bottom=baseHeight+weightedHeight(u,v,w,triangle.baseA,triangle.baseB,triangle.baseC)-thickness;
            if(mode==BOTTOM)return bottom;
            double top=baseHeight+weightedHeight(u,v,w,triangle.topA,triangle.topB,triangle.topC);
            return Math.max(bottom,top-depressionMillimeters/1000);
        }
    }
    private static double triangleDenominator(Vertex a,Vertex b,Vertex c){
        return (b.z()-c.z())*(a.x()-c.x())+(c.x()-b.x())*(a.z()-c.z());
    }
    private static double triangleU(Vertex a,Vertex b,Vertex c,double x,double z,double denominator){
        return ((b.z()-c.z())*(x-c.x())+(c.x()-b.x())*(z-c.z()))/denominator;
    }
    private static double triangleV(Vertex a,Vertex b,Vertex c,double x,double z,double denominator){
        return ((c.z()-a.z())*(x-c.x())+(a.x()-c.x())*(z-c.z()))/denominator;
    }
    private static double weightedHeight(double u,double v,double w,double ay,double by,double cy){return u*ay+v*by+w*cy;}
    private static double uniformVertexY(Vertex point,int mask,int crustCode,double delta){
        return Math.max(0,point.y()+(mask==0?SnowCrustRelief.offset(crustCode,point.x(),point.z()):0)+delta/1000);
    }
    private static void checkPoint(int crustCode,double localX,double localZ){
        if(!Double.isFinite(localX)||!Double.isFinite(localZ)||localX<-.00001||localX>1.00001
                ||localZ<-.00001||localZ>1.00001||(crustCode&~255)!=0)
            throw new IllegalArgumentException("Snow surface sample");
    }
    public static double vertexY(Vertex point,int mask,int crustCode,double[] fineCornersMillimeters) {
        double x=point.x(),z=point.z();
        double north=fineCornersMillimeters[0]+(fineCornersMillimeters[1]-fineCornersMillimeters[0])*x;
        double south=fineCornersMillimeters[3]+(fineCornersMillimeters[2]-fineCornersMillimeters[3])*x;
        return Math.max(0,point.y()+(mask==0?SnowCrustRelief.offset(crustCode,point.x(),point.z()):0)
                +(north+(south-north)*z)/1000);
    }
    private static void checkCorners(double[] corners) {
        if(corners==null||corners.length!=4)throw new IllegalArgumentException("Four fine snow corners required");
        for(double value:corners)if(!Double.isFinite(value)||value< -1500||value>1500)throw new IllegalArgumentException("Invalid fine snow corner");
    }
}
